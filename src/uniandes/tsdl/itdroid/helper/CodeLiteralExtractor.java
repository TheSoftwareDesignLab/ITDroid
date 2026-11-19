package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.commons.io.FileUtils;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

/**
 * Moves hardcoded strings of the code to string resources so one APK shows them in the device language.
 * Each literal is passed to a small added class that looks up the resource by a name built from the
 * text hash and returns the text unchanged when there is none. It needs no free register or Context.
 */
public final class CodeLiteralExtractor {

    /** Prefix of the resource names, the added class builds the same names. */
    public static final String PREFIX = "itdroid_code_";

    private static final String HELPER_RESOURCE = "itdroid-strings.smali";
    private static final String HELPER_PATH = "itdroid/hc/Strings.smali";
    private static final String CALL = "invoke-static/range {%1$s .. %1$s}, Litdroid/hc/Strings;->t(Ljava/lang/String;)Ljava/lang/String;";
    private static final Pattern CONST_STRING = Pattern.compile("^(\\s*)const-string(?:/jumbo)?\\s+([vp]\\d+),\\s*\"(.*)\"$");
    /** Most method or type references a dex file holds, the call adds one of each. */
    private static final int DEX_REFERENCE_LIMIT = 65536;

    /** What an extraction did, and how to undo it. */
    public static final class Extraction {
        /** Literal text to the name of its resource, in extraction order. */
        public final Map<String, String> nameByText = new LinkedHashMap<>();
        /** Resource name to its value exactly as written in strings.xml. */
        public final Map<String, String> valueByName = new LinkedHashMap<>();
        /** The places rewritten, as the scan gave them. */
        public final Set<SmaliScan.Occurrence> places = new HashSet<>();
        /** Literals that could not be moved, with the reason. They stay in the source language. */
        public final Map<String, String> notMoved = new LinkedHashMap<>();
        /** Why nothing was moved at all, when the extraction is off or was put back, else null. */
        public final String reasonNoneMoved;

        private final Map<Path, List<String>> originals = new LinkedHashMap<>();
        private Path stringsFile;
        private String stringsBefore;
        private boolean stringsExisted;
        private Path helperDexFolder;
        private String decodedPath;

        Extraction(String reasonNoneMoved) {
            this.reasonNoneMoved = reasonNoneMoved;
        }

        /** True when at least one literal was moved. */
        public boolean active() {
            return !places.isEmpty();
        }

        public Set<String> names() {
            return valueByName.keySet();
        }

        /** Why a literal chosen for translation stays in the code, or null when it was moved. */
        public String notMovedReason(String text) {
            String reason = notMoved.get(text);
            if (reason != null || nameByText.containsKey(text)) {
                return reason;
            }
            return reasonNoneMoved != null ? reasonNoneMoved : "it was not moved to a resource";
        }

        /** Puts the code and strings.xml back as they were and removes the added class. */
        public void revert() throws IOException {
            for (Map.Entry<Path, List<String>> e : originals.entrySet()) {
                Files.write(e.getKey(), e.getValue(), StandardCharsets.UTF_8);
            }
            if (stringsFile != null) {
                if (stringsExisted) {
                    Files.writeString(stringsFile, stringsBefore, StandardCharsets.UTF_8);
                } else {
                    Files.deleteIfExists(stringsFile);
                }
            }
            if (helperDexFolder != null) {
                FileUtils.deleteDirectory(helperDexFolder.toFile());
            }
            if (decodedPath != null) {
                // translations of resources that no longer exist would stop the build
                File[] folders = new File(decodedPath, "res").listFiles((d, n) -> n.startsWith("values-"));
                if (folders != null) {
                    for (File folder : folders) {
                        removeTranslations(new File(folder, "strings.xml"));
                    }
                }
            }
            originals.clear();
            places.clear();
            nameByText.clear();
            valueByName.clear();
        }
    }

    private CodeLiteralExtractor() {
    }

    /** An extraction that moved nothing, with the reason every literal stays in the code. */
    public static Extraction none(String reason) {
        return new Extraction(reason);
    }

    /**
     * Moves the literals at the given places to resources. What cannot be moved stays in the code, in
     * the source language, and {@link Extraction#notMoved} says why.
     *
     * @param fullDexFolders dex folders with no room for the added call, their literals are left alone
     */
    public static Extraction extract(String decodedPath, List<SmaliScan.Occurrence> places, Set<String> fullDexFolders)
            throws IOException {
        Extraction result = new Extraction(null);
        result.decodedPath = decodedPath;
        if (places.isEmpty()) {
            return result;
        }
        Set<String> taken = XmlLiterals.existingNames(decodedPath);
        Map<String, String> textByName = new HashMap<>();
        Set<String> skippedTexts = new HashSet<>();
        Map<String, List<SmaliScan.Occurrence>> byFile = new LinkedHashMap<>();
        for (SmaliScan.Occurrence place : places) {
            String dexFolder = place.file.contains("/") ? place.file.substring(0, place.file.indexOf('/')) : "";
            if (fullDexFolders.contains(dexFolder)) {
                result.notMoved.putIfAbsent(place.text, "its dex file (" + dexFolder + ") is at the limit of " + DEX_REFERENCE_LIMIT
                        + " method references and has no room for the call that reads the resource");
                continue;
            }
            String name = resourceName(place.text);
            String owner = textByName.get(name);
            if (skippedTexts.contains(place.text) || (owner == null && taken.contains(name)) || (owner != null && !owner.equals(place.text))) {
                // hash collision or a name the app already uses, so this one stays in the code
                if (skippedTexts.add(place.text)) {
                    result.notMoved.putIfAbsent(place.text, "the resource name " + name + " is already in use");
                }
                continue;
            }
            textByName.put(name, place.text);
            byFile.computeIfAbsent(place.file, k -> new ArrayList<>()).add(place);
        }

        for (Map.Entry<String, List<SmaliScan.Occurrence>> f : byFile.entrySet()) {
            Path file = Paths.get(decodedPath).resolve(f.getKey());
            List<String> original = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>(original);
            List<SmaliScan.Occurrence> inFile = new ArrayList<>(f.getValue());
            inFile.sort(Comparator.comparingInt((SmaliScan.Occurrence o) -> o.line).reversed());   // bottom up so lines above keep their number
            boolean changed = false;
            for (SmaliScan.Occurrence place : inFile) {
                int index = place.line - 1;
                Matcher m = index >= 0 && index < lines.size() ? CONST_STRING.matcher(lines.get(index)) : null;
                if (m == null || !m.matches() || !SmaliScan.unescape(m.group(3)).equals(place.text)) {
                    result.notMoved.putIfAbsent(place.text, place.origin() + " no longer holds the literal found by the scan");
                    continue;
                }
                lines.add(index + 1, m.group(1) + String.format(CALL, m.group(2)));
                lines.add(index + 2, m.group(1) + "move-result-object " + m.group(2));
                changed = true;
                result.places.add(place);
                String name = resourceName(place.text);
                result.nameByText.putIfAbsent(place.text, name);
                result.valueByName.putIfAbsent(name, resourceValue(place.text));
            }
            if (changed) {
                result.originals.put(file, original);
                Files.write(file, lines, StandardCharsets.UTF_8);
            }
        }
        if (!result.active()) {
            return result;
        }
        result.helperDexFolder = addHelperClass(decodedPath);
        result.stringsFile = Paths.get(decodedPath, "res", "values", "strings.xml");
        result.stringsExisted = Files.isRegularFile(result.stringsFile);
        result.stringsBefore = result.stringsExisted ? Files.readString(result.stringsFile, StandardCharsets.UTF_8) : null;
        // formatted false, a literal may hold several % that are not a positional format
        XmlLiterals.appendToDefaultStrings(decodedPath, result.valueByName, " formatted=\"false\"");
        return result;
    }

    /** The resource name of a text, the added class computes the same at run time. */
    static String resourceName(String text) {
        return PREFIX + Integer.toHexString(text.hashCode());
    }

    /** Escapes a code string as a resource value that reads back exactly the same. */
    static String resourceValue(String literal) {
        StringBuilder sb = new StringBuilder(literal.length() + 8);
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\'':
                    sb.append("\\'");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        String v = sb.toString();
        if (v.startsWith("@") || v.startsWith("?")) {
            v = "\\" + v;
        }
        if (!literal.equals(literal.strip()) || literal.contains("  ")) {
            v = "\"" + v + "\"";
        }
        return v;
    }

    /** Copies the class the calls go to into a dex folder of its own, so no existing dex grows by a class. */
    private static Path addHelperClass(String decodedPath) throws IOException {
        int last = 1;
        for (File dir : AppCode.smaliDirs(new File(decodedPath))) {
            String digits = dir.getName().replaceAll("\\D", "");
            last = Math.max(last, digits.isEmpty() ? 1 : Integer.parseInt(digits));
        }
        Path folder = Paths.get(decodedPath, "smali_classes" + (last + 1));
        Path target = folder.resolve(HELPER_PATH);
        Files.createDirectories(target.getParent());
        try (InputStream in = CodeLiteralExtractor.class.getResourceAsStream(HELPER_RESOURCE)) {
            if (in == null) {
                throw new IOException(HELPER_RESOURCE + " is missing from the ITDroid jar");
            }
            Files.copy(in, target);
        }
        return folder;
    }

    /** Dex folders whose dex file is already at the method or type reference limit, read from the APK. */
    public static Set<String> fullDexFolders(String apkPath) {
        Set<String> full = new HashSet<>();
        try (ZipFile zip = new ZipFile(apkPath)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Matcher m = Pattern.compile("^classes(\\d*)\\.dex$").matcher(entry.getName());
                if (!m.matches()) {
                    continue;
                }
                byte[] header = new byte[0x70];
                try (InputStream in = zip.getInputStream(entry)) {
                    if (in.readNBytes(header, 0, header.length) < header.length) {
                        continue;
                    }
                }
                long types = littleEndianInt(header, 0x40);
                long methods = littleEndianInt(header, 0x58);
                if (types >= DEX_REFERENCE_LIMIT || methods >= DEX_REFERENCE_LIMIT) {
                    full.add(m.group(1).isEmpty() ? "smali" : "smali_classes" + m.group(1));
                }
            }
        } catch (IOException e) {
            // unreadable APK, a failed build makes ITDroid put the literals back
        }
        return full;
    }

    private static long littleEndianInt(byte[] b, int offset) {
        return (b[offset] & 0xffL) | (b[offset + 1] & 0xffL) << 8 | (b[offset + 2] & 0xffL) << 16 | (b[offset + 3] & 0xffL) << 24;
    }

    /** Removes the code literal translations from a language's strings.xml and returns how many. */
    public static int removeTranslations(File stringsXml) {
        if (!stringsXml.isFile()) {
            return 0;
        }
        try {
            Document document = new SAXBuilder().build(stringsXml);
            List<Element> ours = new ArrayList<>();
            for (Element e : document.getRootElement().getChildren("string")) {
                String name = e.getAttributeValue("name");
                if (name != null && name.startsWith(PREFIX)) {
                    ours.add(e);
                }
            }
            if (ours.isEmpty()) {
                return 0;
            }
            for (Element e : ours) {
                e.detach();
            }
            XMLOutputter output = new XMLOutputter(Format.getPrettyFormat().setTextMode(Format.TextMode.PRESERVE));
            try (Writer w = new OutputStreamWriter(Files.newOutputStream(stringsXml.toPath()), StandardCharsets.UTF_8)) {
                output.output(document, w);
            }
            return ours.size();
        } catch (Exception e) {
            System.out.println("WARNING: could not remove the translated code literals from " + stringsXml + ": " + e.getMessage());
            return 0;
        }
    }
}

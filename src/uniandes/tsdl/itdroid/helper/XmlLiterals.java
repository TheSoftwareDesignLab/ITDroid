package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jdom2.Attribute;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import org.jdom2.located.Located;
import org.jdom2.located.LocatedJDOMFactory;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import uniandes.tsdl.itdroid.translator.LLMTranslator;

/**
 * Finds text written directly in the app's XML instead of a string reference, which the resource
 * system cannot translate, and can move it to string resources. Library layouts are left out.
 */
public final class XmlLiterals {

    /** One attribute that holds literal text. */
    public static final class Literal {
        /** Path relative to the decoded folder, with forward slashes. */
        public final String file;
        /** Line of the element, 0 when the parser did not give one. */
        public final int line;
        /** Tag of the element. */
        public final String element;
        /** Its id, key or name attribute, empty when it has none. */
        public final String id;
        /** Attribute name with its prefix. */
        public final String attribute;
        public final String text;

        Literal(String file, int line, String element, String id, String attribute, String text) {
            this.file = file;
            this.line = line;
            this.element = element;
            this.id = id;
            this.attribute = attribute;
            this.text = text;
        }

        public String origin() {
            return line > 0 ? file + ":" + line : file;
        }
    }

    /** Attributes whose value is text for the user, in the android or app namespace. */
    private static final Set<String> TEXT_ATTRIBUTES = Set.of("text", "hint", "contentDescription", "title", "subtitle",
            "summary", "summaryOn", "summaryOff", "label", "description", "dialogTitle", "dialogMessage",
            "positiveButtonText", "negativeButtonText", "textOn", "textOff", "queryHint", "tooltipText", "helperText",
            "placeholderText", "prefixText", "suffixText", "titleCondensed", "accessibilityPaneTitle", "stateDescription");

    /** Resource folders that hold screens, menus, preference screens and navigation graphs. */
    private static final List<String> FOLDERS = List.of("layout", "menu", "xml", "navigation");

    /** The android and app namespaces, whose attributes the framework reads as text. */
    private static final Set<String> TEXT_NAMESPACES = Set.of("http://schemas.android.com/apk/res/android",
            "http://schemas.android.com/apk/res-auto");

    private XmlLiterals() {
    }

    /** Every literal of the app's layouts, menus, preference screens, navigation graphs and manifest. */
    public static List<Literal> find(String decodedPath) {
        List<Literal> out = new ArrayList<>();
        File decoded = new File(decodedPath);
        File[] resDirs = new File(decoded, "res").listFiles(File::isDirectory);
        if (resDirs != null) {
            Arrays.sort(resDirs, Comparator.comparing(File::getName));
            for (File dir : resDirs) {
                String type = dir.getName().contains("-") ? dir.getName().substring(0, dir.getName().indexOf('-')) : dir.getName();
                if (!FOLDERS.contains(type)) {
                    continue;
                }
                File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".xml"));
                if (files == null) {
                    continue;
                }
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File f : files) {
                    if (!Translatability.isLibraryLayout(f.getName())) {
                        collect(f, "res/" + dir.getName() + "/" + f.getName(), out);
                    }
                }
            }
        }
        File manifest = new File(decoded, "AndroidManifest.xml");
        if (manifest.isFile()) {
            collect(manifest, "AndroidManifest.xml", out);
        }
        return out;
    }

    private static void collect(File file, String relativePath, List<Literal> out) {
        Element root;
        try {
            SAXBuilder builder = new SAXBuilder();
            builder.setJDOMFactory(new LocatedJDOMFactory());   // elements remember their line
            root = builder.build(file).getRootElement();
        } catch (Exception e) {
            return;   // not well formed XML, nothing to report
        }
        walk(root, relativePath, out);
    }

    private static void walk(Element element, String relativePath, List<Literal> out) {
        for (Attribute attribute : element.getAttributes()) {
            // other namespaces are design time or metadata, not interface
            if (!TEXT_ATTRIBUTES.contains(attribute.getName()) || !TEXT_NAMESPACES.contains(attribute.getNamespaceURI())) {
                continue;
            }
            String text = Translatability.layoutText(attribute.getValue());
            if (text == null) {
                continue;
            }
            int line = element instanceof Located ? ((Located) element).getLine() : 0;
            out.add(new Literal(relativePath, line, element.getName(), idOf(element), attribute.getQualifiedName(), text));
        }
        for (Element child : element.getChildren()) {
            walk(child, relativePath, out);
        }
    }

    private static String idOf(Element element) {
        for (String name : new String[] {"id", "key", "name"}) {
            for (Attribute a : element.getAttributes()) {
                if (a.getName().equals(name) && !a.getValue().isEmpty()) {
                    return a.getValue();
                }
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ extraction to resources

    /** Prefix of the names given to the extracted strings. */
    public static final String PREFIX = "itdroid_hc_";

    /** What an extraction did. */
    public static final class Extraction {
        /** Attribute text to the name of its new resource, in extraction order. */
        public final Map<String, String> nameByText = new LinkedHashMap<>();
        /** Resource name to its escaped value as written in strings.xml. */
        public final Map<String, String> valueByName = new LinkedHashMap<>();
        /** Attributes rewritten, several can share one resource. */
        public int attributes;
        /** Files that could not be rewritten, with the reason. */
        public final List<String> problems = new ArrayList<>();

        public Set<String> names() {
            return valueByName.keySet();
        }
    }

    /**
     * Moves the given literals to string resources, except text not meant for a person and the app name.
     * Port of repairLayoutHCS from the original team's unmerged work.
     */
    public static Extraction extract(String decodedPath, List<XmlLiterals.Literal> literals, Set<String> appNames) {
        Extraction result = new Extraction();
        // file to the literals to replace in it
        Map<String, List<XmlLiterals.Literal>> byFile = new LinkedHashMap<>();
        for (XmlLiterals.Literal l : literals) {
            if (Translatability.layoutSkip(l, appNames) == null) {
                byFile.computeIfAbsent(l.file, k -> new ArrayList<>()).add(l);
            }
        }
        if (byFile.isEmpty()) {
            return result;
        }
        Set<String> taken = existingNames(decodedPath);
        int next = 1;
        for (Map.Entry<String, List<XmlLiterals.Literal>> f : byFile.entrySet()) {
            Path file = Paths.get(decodedPath, f.getKey().split("/"));
            try {
                SAXBuilder builder = new SAXBuilder();
                builder.setJDOMFactory(new LocatedJDOMFactory());
                Document document = builder.build(file.toFile());
                int before = result.attributes;
                for (XmlLiterals.Literal l : f.getValue()) {
                    Attribute attribute = find(document.getRootElement(), l);
                    if (attribute == null) {
                        continue;
                    }
                    String name = result.nameByText.get(l.text);
                    if (name == null) {
                        do {
                            name = PREFIX + next++;
                        } while (taken.contains(name));
                        result.nameByText.put(l.text, name);
                        result.valueByName.put(name, xml(resourceValue(l.text)));
                    }
                    attribute.setValue("@string/" + name);
                    result.attributes++;
                }
                if (result.attributes > before) {
                    // raw format writes everything else back as it was read
                    try (Writer w = new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
                        new XMLOutputter(Format.getRawFormat()).output(document, w);
                    }
                }
            } catch (Exception e) {
                result.problems.add(f.getKey() + ": " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        }
        if (!result.valueByName.isEmpty()) {
            try {
                appendToDefaultStrings(decodedPath, result.valueByName, "");
            } catch (IOException e) {
                throw new IllegalStateException("could not add the extracted strings to res/values/strings.xml: " + e.getMessage(), e);
            }
        }
        return result;
    }

    /** The attribute a literal was read from, matched by element, line, attribute and value. */
    private static Attribute find(Element element, XmlLiterals.Literal l) {
        int line = element instanceof Located ? ((Located) element).getLine() : 0;
        if (element.getName().equals(l.element) && (l.line == 0 || line == l.line)) {
            for (Attribute a : element.getAttributes()) {
                if (a.getQualifiedName().equals(l.attribute) && l.text.equals(Translatability.layoutText(a.getValue()))) {
                    return a;
                }
            }
        }
        for (Element child : element.getChildren()) {
            Attribute found = find(child, l);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The attribute text as a resource value, with quotes escaped and edge whitespace kept by quoting. */
    static String resourceValue(String attributeText) {
        String v = LLMTranslator.replaceUnscapedCharacters(attributeText);
        if (v.startsWith("@") || v.startsWith("?")) {
            v = "\\" + v;                                    // text, not a reference
        }
        if (!v.equals(v.strip()) || v.contains("  ")) {
            v = "\"" + v + "\"";
        }
        return v;
    }

    /** Names of the string resources the app already defines in res/values. */
    static Set<String> existingNames(String decodedPath) {
        Set<String> names = new java.util.HashSet<>();
        File[] files = new File(decodedPath, "res" + File.separator + "values").listFiles((d, n) -> n.toLowerCase().endsWith(".xml"));
        if (files == null) {
            return names;
        }
        for (File f : files) {
            try {
                for (Element e : new SAXBuilder().build(f).getRootElement().getChildren()) {
                    String type = "item".equals(e.getName()) ? e.getAttributeValue("type") : e.getName();
                    if ("string".equals(type) && e.getAttributeValue("name") != null) {
                        names.add(e.getAttributeValue("name"));
                    }
                }
            } catch (Exception e) {
                // not a resource file, no names to avoid
            }
        }
        return names;
    }

    /**
     * Adds the already escaped strings to res/values/strings.xml, creating it when missing.
     * {@code attributes} is added to each element.
     */
    static void appendToDefaultStrings(String decodedPath, Map<String, String> valueByName, String attributes) throws IOException {
        Path strings = Paths.get(decodedPath, "res", "values", "strings.xml");
        StringBuilder added = new StringBuilder();
        for (Map.Entry<String, String> e : valueByName.entrySet()) {
            added.append("    <string name=\"").append(e.getKey()).append("\"").append(attributes).append(">").append(e.getValue()).append("</string>\n");
        }
        if (!Files.isRegularFile(strings)) {
            Files.createDirectories(strings.getParent());
            Files.writeString(strings, "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n" + added + "</resources>\n", StandardCharsets.UTF_8);
            return;
        }
        String content = Files.readString(strings, StandardCharsets.UTF_8);
        int close = content.lastIndexOf("</resources>");
        if (close < 0) {
            // an empty self closing resources element
            content = content.replaceFirst("<resources\\s*/>", "<resources>\n</resources>");
            close = content.lastIndexOf("</resources>");
            if (close < 0) {
                throw new IOException("res/values/strings.xml has no <resources> element");
            }
        }
        String head = content.substring(0, close);
        Files.writeString(strings, head + (head.endsWith("\n") ? "" : "\n") + added + content.substring(close), StandardCharsets.UTF_8);
    }

    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

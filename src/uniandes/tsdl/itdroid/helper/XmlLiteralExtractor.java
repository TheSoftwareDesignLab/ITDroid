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
 * Moves the text written in the app's XML to string resources: {@code android:text="Add to cart"}
 * becomes {@code android:text="@string/itdroid_hc_1"} and {@code strings.xml} gains
 * {@code <string name="itdroid_hc_1">Add to cart</string>}. The app shows the same text in its
 * source language, and from then on the text is translated like any other resource. (Port of
 * {@code repairLayoutHCS} of the original team's unmerged work.)
 */
public final class XmlLiteralExtractor {

    /** Prefix of the names given to the extracted strings. */
    public static final String PREFIX = "itdroid_hc_";

    /** What an extraction did. */
    public static final class Extraction {
        /** Literal text (as written in the attribute) to the name of its new resource, in extraction order. */
        public final Map<String, String> nameByText = new LinkedHashMap<>();
        /** Resource name to the value exactly as written in strings.xml: the text with the escaping a resource needs. */
        public final Map<String, String> valueByName = new LinkedHashMap<>();
        /** Attributes rewritten (a text used in three places is one resource and three attributes). */
        public int attributes;
        /** Files that could not be rewritten, with the reason. */
        public final List<String> problems = new ArrayList<>();

        public Set<String> names() {
            return valueByName.keySet();
        }
    }

    private XmlLiteralExtractor() {
    }

    /**
     * Extracts the given literals. Left as they are: text that is not for a person (the same rules
     * as every other string), and the name of the app, which is not translated.
     *
     * @param appNames texts that are the app's name (see {@link StringInventory#appNames})
     */
    public static Extraction extract(String decodedPath, List<XmlLiteralFinder.Literal> literals, Set<String> appNames) {
        Extraction result = new Extraction();
        // file -> the literals to replace in it
        Map<String, List<XmlLiteralFinder.Literal>> byFile = new LinkedHashMap<>();
        for (XmlLiteralFinder.Literal l : literals) {
            if (extractable(l, appNames)) {
                byFile.computeIfAbsent(l.file, k -> new ArrayList<>()).add(l);
            }
        }
        if (byFile.isEmpty()) {
            return result;
        }
        Set<String> taken = existingNames(decodedPath);
        int next = 1;
        for (Map.Entry<String, List<XmlLiteralFinder.Literal>> f : byFile.entrySet()) {
            Path file = Paths.get(decodedPath, f.getKey().split("/"));
            try {
                SAXBuilder builder = new SAXBuilder();
                builder.setJDOMFactory(new LocatedJDOMFactory());
                Document document = builder.build(file.toFile());
                int before = result.attributes;
                for (XmlLiteralFinder.Literal l : f.getValue()) {
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
                    // Raw format: everything but the rewritten attributes is written back as it was read.
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

    /** True when a literal of the XML is moved to a resource. */
    static boolean extractable(XmlLiteralFinder.Literal l, Set<String> appNames) {
        if (StringInventory.textSkipReason(l.text) != null) {
            return false;
        }
        return !isAppName(l, appNames);
    }

    /** The label of the application element, or any literal equal to the app's name. */
    static boolean isAppName(XmlLiteralFinder.Literal l, Set<String> appNames) {
        return ("AndroidManifest.xml".equals(l.file) && "application".equals(l.element) && l.attribute.endsWith(":label"))
                || appNames.contains(l.text);
    }

    /** The attribute a literal was read from: same element (line and tag), same attribute, same value. */
    private static Attribute find(Element element, XmlLiteralFinder.Literal l) {
        int line = element instanceof Located ? ((Located) element).getLine() : 0;
        if (element.getName().equals(l.element) && (l.line == 0 || line == l.line)) {
            for (Attribute a : element.getAttributes()) {
                if (a.getQualifiedName().equals(l.attribute) && l.text.equals(XmlLiteralFinder.literalOf(a.getValue()))) {
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

    /**
     * The text of an attribute as the value of a string resource. Both go through the same escape
     * processing when the app is built, except that a resource needs its quotes escaped and loses
     * the whitespace at its edges unless the whole value is quoted.
     */
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
                // not a resource file: no names to avoid
            }
        }
        return names;
    }

    /**
     * Adds the strings before the closing tag of res/values/strings.xml, creating the file when the
     * app has none. The values are written as given (already escaped); {@code attributes} is added
     * to each element ("" for none).
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
            // <resources/> with nothing inside
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

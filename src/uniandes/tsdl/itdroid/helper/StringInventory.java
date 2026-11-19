package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * Every user visible string of the app with its origin, whether it is translated and why not, and its
 * translation per language. Saved as {@code strings_inventory.json} for review, it is also the
 * translation memory that a later run in the same output folder reuses with the same model.
 */
public class StringInventory {

    public static final String KIND_STRING = "string";
    public static final String KIND_PLURALS = "plurals";
    public static final String KIND_ARRAY = "string-array";
    /** A literal of the smali code. */
    public static final String KIND_HARDCODED = "hardcoded";
    /** A literal written in a layout, menu, preference screen or the manifest. */
    public static final String KIND_HARDCODED_XML = "hardcoded-xml";

    /** Use recorded for the XML literals, next to the uses the code scan finds. */
    public static final String CONSUMER_XML = "xml";
    /** Why an XML literal is listed but not translated. */
    static final String XML_NOT_EXTRACTED = "XML literal (not extracted to a resource)";
    /** Places saved per literal in the JSON file, the occurrences field keeps the real count. */
    private static final int MAX_PLACES_SAVED = 50;

    /** One string of the app, a resource value, a container item or a hardcoded literal. */
    public static class Entry {
        public final String id;
        public final String kind;
        public final String name;        // resource name, empty for hardcoded literals
        public final String quantity;    // plurals item quantity, else null
        public final int index;          // string-array item position, else -1
        public final String text;        // inner XML of the resource, or the decoded smali literal
        public final String origin;      // resource file, or smali file and line
        public final String formatted;   // the formatted attribute, or null
        public final boolean translatable;
        public final String skipReason;  // why it is kept verbatim, null when translatable
        /** Hardcoded only, what the app does with it, else null. */
        public String consumer;
        /** Hardcoded only, the places of the code that hold this text. */
        public List<SmaliScan.Occurrence> codePlaces = List.of();
        /** Hardcoded only, the XML attributes that hold this text. */
        public List<XmlLiterals.Literal> xmlPlaces = List.of();
        /** Code places the {@link HardcodedPolicy} chose that could not be moved to a resource. They stay in the source language. */
        public List<SmaliScan.Occurrence> notMovedPlaces = List.of();
        /** Why those places were not moved, else null. */
        public String notMovedReason;
        /** Code places rewritten by {@link CodeLiteralExtractor} to read the text from a resource. */
        public List<SmaliScan.Occurrence> extractedPlaces = List.of();
        /** The name of the string resource a hardcoded text was moved to, else null. */
        public String extractedTo;
        /** The text as the resource it was moved to holds it, the key of its translation. */
        public String resourceText;

        Entry(String id, String kind, String name, String quantity, int index, String text, String origin,
              String formatted, String skipReason) {
            this.id = id;
            this.kind = kind;
            this.name = name == null ? "" : name;
            this.quantity = quantity;
            this.index = index;
            this.text = text == null ? "" : text;
            this.origin = origin;
            this.formatted = formatted;
            this.skipReason = skipReason;
            this.translatable = skipReason == null;
        }

        public boolean isResource() {
            return !KIND_HARDCODED.equals(kind) && !KIND_HARDCODED_XML.equals(kind);
        }

        /** How many places hold this text, 0 for a resource. */
        public int occurrences() {
            return codePlaces.size() + xmlPlaces.size();
        }

        /** Entries of the same resource share this key. */
        public String groupKey() {
            return kind + ":" + name;
        }
    }

    private static final XMLOutputter RAW = new XMLOutputter(Format.getRawFormat());

    private final List<Entry> entries = new ArrayList<>();
    /** Language to source text to translation, keyed by text so repeated texts are translated once. */
    private final Map<String, Map<String, String>> translations = new TreeMap<>();
    /** The model that made the translations of each language, only those of the current model are reused. */
    private final Map<String, String> modelByLanguage = new TreeMap<>();
    private String packageName = "";
    private String sourceLanguage = "";
    private HardcodedPolicy policy = HardcodedPolicy.DEFAULT;
    private Translatability.AppName appName = new Translatability.AppName();
    private int nextId = 0;

    /** The code places translated under a policy in scan order, decided as the inventory decides it. */
    public static List<SmaliScan.Occurrence> placesToTranslate(String decodedPath, List<HardcodedStringFinder.Hit> hits,
            HardcodedPolicy policy) {
        Translatability.AppName name = Translatability.appName(decodedPath);
        Set<String> sqlIdentifiers = Translatability.sqlIdentifiers(hits);
        List<SmaliScan.Occurrence> out = new ArrayList<>();
        for (HardcodedStringFinder.Hit h : hits) {
            if (name.texts.contains(h.text.trim()) || Translatability.codeSkip(h, sqlIdentifiers, policy) != null) {
                continue;
            }
            for (SmaliScan.Occurrence o : h.places) {
                if (policy.translates(o.consumer)) {
                    out.add(o);
                }
            }
        }
        out.sort(java.util.Comparator.comparingInt(o -> o.order));
        return out;
    }

    /**
     * The inventory of a decoded app, with the code and XML literals of one scan. With an empty package
     * there are no literals to give.
     *
     * @param extraction the XML literals already moved to resources, or null
     * @param codeExtraction the code literals already moved to resources, or null when none were
     */
    public static StringInventory collect(String decodedPath, String packageName, NotTranslatableStringsDictionary dictionary,
            List<HardcodedStringFinder.Hit> hits, List<XmlLiterals.Literal> xmlLiterals, HardcodedPolicy policy,
            XmlLiterals.Extraction extraction, CodeLiteralExtractor.Extraction codeExtraction) throws IOException {
        StringInventory inv = new StringInventory();
        inv.packageName = packageName == null ? "" : packageName;
        inv.policy = policy == null ? HardcodedPolicy.DEFAULT : policy;
        inv.appName = Translatability.appName(decodedPath);

        File valuesDir = new File(decodedPath, "res" + File.separator + "values");
        File[] files = valuesDir.listFiles((d, n) -> n.toLowerCase().endsWith(".xml"));
        if (files != null) {
            // strings.xml first, then the rest alphabetically, for stable ids and order
            Arrays.sort(files, (a, b) -> {
                boolean sa = a.getName().equalsIgnoreCase("strings.xml");
                boolean sb = b.getName().equalsIgnoreCase("strings.xml");
                return sa == sb ? a.getName().compareTo(b.getName()) : (sa ? -1 : 1);
            });
            for (File f : files) {
                if (f.getName().equalsIgnoreCase("donottranslate.xml")) {
                    continue;   // by Android convention everything in it stays verbatim
                }
                inv.collectResourceFile(f, "res/values/" + f.getName(), dictionary);
            }
        }
        Set<String> sqlIdentifiers = Translatability.sqlIdentifiers(hits);
        CodeLiteralExtractor.Extraction moved = codeExtraction != null ? codeExtraction
                : CodeLiteralExtractor.none("it was not moved to a resource");
        int n = 0;
        for (HardcodedStringFinder.Hit h : hits) {
            n++;
            String skip = inv.appName.texts.contains(h.text.trim()) ? Translatability.APP_NAME
                    : Translatability.codeSkip(h, sqlIdentifiers, inv.policy);
            Entry e = new Entry("h" + n, KIND_HARDCODED, "", null, -1, h.text, h.origin() + more(h.occurrences()), null, skip);
            e.consumer = h.consumer().label;
            e.codePlaces = h.places;
            if (skip == null) {
                List<SmaliScan.Occurrence> notMoved = new ArrayList<>();
                List<SmaliScan.Occurrence> extracted = new ArrayList<>();
                for (SmaliScan.Occurrence o : h.places) {
                    if (!inv.policy.translates(o.consumer)) {
                        continue;
                    }
                    if (moved.places.contains(o)) {
                        extracted.add(o);
                    } else {
                        notMoved.add(o);
                    }
                }
                e.notMovedPlaces = notMoved;
                e.extractedPlaces = extracted;
                if (!notMoved.isEmpty()) {
                    e.notMovedReason = moved.notMovedReason(h.text);
                    if (e.notMovedReason == null) {
                        e.notMovedReason = "the line of this place no longer held the literal";
                    }
                }
                if (!extracted.isEmpty()) {
                    e.extractedTo = moved.nameByText.get(h.text);
                    e.resourceText = moved.valueByName.get(e.extractedTo);
                }
            }
            inv.entries.add(e);
        }
        n = 0;
        for (Map.Entry<String, List<XmlLiterals.Literal>> x : HardcodedStringFinder.byText(xmlLiterals).entrySet()) {
            n++;
            // translated through the resource it was moved to, or listed for the report when not moved
            String resource = extraction == null ? null : extraction.nameByText.get(x.getKey());
            String skip = Translatability.layoutSkip(x.getValue().get(0), inv.appName.texts);
            if (skip == null && resource == null) {
                skip = XML_NOT_EXTRACTED;
            }
            Entry e = new Entry("x" + n, KIND_HARDCODED_XML, "", null, -1, x.getKey(),
                    x.getValue().get(0).origin() + more(x.getValue().size()), null, skip);
            e.consumer = CONSUMER_XML;
            e.xmlPlaces = x.getValue();
            if (skip == null) {
                e.extractedTo = resource;
                e.resourceText = extraction.valueByName.get(resource);
            }
            inv.entries.add(e);
        }
        return inv;
    }

    private static String more(int occurrences) {
        return occurrences > 1 ? " (+" + (occurrences - 1) + " more)" : "";
    }

    private void collectResourceFile(File file, String origin, NotTranslatableStringsDictionary dictionary) {
        Element root;
        try {
            root = new SAXBuilder().build(file).getRootElement();
        } catch (Exception e) {
            System.out.println("  inventory: skipping unreadable " + origin + " (" + e.getMessage() + ")");
            return;
        }
        if (!"resources".equals(root.getName())) {
            return;
        }
        for (Element e : root.getChildren()) {
            String kind = e.getName();
            String name = e.getAttributeValue("name");
            if (name == null) {
                continue;
            }
            if (KIND_STRING.equals(kind)) {
                String text = innerXml(e);
                String skip = Translatability.stringSkip(e, text, dictionary, appName.resources);
                entries.add(new Entry(nextId("r"), kind, name, null, -1, text, origin, e.getAttributeValue("formatted"), skip));
            } else if (KIND_PLURALS.equals(kind) || KIND_ARRAY.equals(kind)) {
                String containerSkip = Translatability.containerSkip(e, dictionary, appName.resources);
                int index = 0;
                for (Element item : e.getChildren("item")) {
                    String text = innerXml(item);
                    String skip = containerSkip != null ? containerSkip : Translatability.resourceValueSkip(name, text);
                    entries.add(new Entry(nextId("r"), kind, name, item.getAttributeValue("quantity"),
                            KIND_ARRAY.equals(kind) ? index : -1, text, origin, null, skip));
                    index++;
                }
            }
            // other resource types are never user visible text
        }
    }

    private String nextId(String prefix) {
        nextId++;
        return prefix + nextId;
    }

    /** The element's content as XML text, so inline markup is kept. */
    static String innerXml(Element e) {
        return RAW.outputString(e.getContent());
    }

    // ----------------------------------------------------------------- queries

    public List<Entry> entries() {
        return entries;
    }

    public List<Entry> resourceEntries() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.isResource()) {
                out.add(e);
            }
        }
        return out;
    }

    /** Code literals chosen for translation that stay in the code, in discovery order, with the reason. */
    public Map<String, String> notMovedCodeLiterals() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (KIND_HARDCODED.equals(e.kind) && e.translatable && !e.notMovedPlaces.isEmpty()) {
                out.putIfAbsent(e.text, e.notMovedReason);
            }
        }
        return out;
    }

    /** Names of the string resources the XML and code literals were moved to. */
    public Set<String> extractedResources() {
        Set<String> out = new LinkedHashSet<>();
        for (Entry e : entries) {
            if (e.extractedTo != null) {
                out.add(e.extractedTo);
            }
        }
        return out;
    }

    public synchronized String translation(String lang, String text) {
        Map<String, String> m = translations.get(lang);
        return m == null ? null : m.get(text);
    }

    public synchronized void putTranslation(String lang, String text, String translated) {
        translations.computeIfAbsent(lang, k -> new LinkedHashMap<>()).put(text, translated);
    }

    /** Forgets a translation, one that no longer passes the checks of the translator. */
    public synchronized void removeTranslation(String lang, String text) {
        Map<String, String> m = translations.get(lang);
        if (m != null) {
            m.remove(text);
        }
    }

    /**
     * Declares the model that translates a language from now on. What another model translated, or a
     * run that did not record its model, is forgotten for that language.
     */
    public synchronized void useModel(String lang, String model) {
        if (!Objects.equals(modelByLanguage.get(lang), model)) {
            translations.remove(lang);
            modelByLanguage.put(lang, model);
        }
    }

    public void setSourceLanguage(String sourceLanguage) {
        this.sourceLanguage = sourceLanguage == null ? "" : sourceLanguage;
    }

    /** Counts for the report, with the one breakdown of hardcoded strings every output shows. */
    @SuppressWarnings("unchecked")
    public JSONObject summary() {
        long resources = 0, resourcesTranslatable = 0, hardcodedCode = 0, hardcodedXml = 0, hardcodedTranslatable = 0, places = 0;
        long extracted = 0, notMovedPlaces = 0, codeExtracted = 0, extractedPlaces = 0;
        Map<String, Long> byConsumer = new TreeMap<>();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (Entry e : entries) {
            if (e.isResource()) {
                resources++;
                if (e.translatable) {
                    resourcesTranslatable++;
                }
            } else {
                if (KIND_HARDCODED_XML.equals(e.kind)) {
                    hardcodedXml++;
                } else {
                    hardcodedCode++;
                }
                places += e.occurrences();
                notMovedPlaces += e.notMovedPlaces.size();
                extractedPlaces += e.extractedPlaces.size();
                if (e.extractedTo != null && KIND_HARDCODED_XML.equals(e.kind)) {
                    extracted++;
                } else if (e.extractedTo != null) {
                    codeExtracted++;
                }
                byConsumer.merge(e.consumer == null ? "unknown" : e.consumer, 1L, Long::sum);
                if (e.translatable) {
                    hardcodedTranslatable++;
                }
            }
            if (e.translatable) {
                unique.add(e.text);
            }
        }
        JSONObject o = new JSONObject();
        o.put("resources", resources);
        o.put("resourcesTranslatable", resourcesTranslatable);
        o.put("hardcoded", hardcodedCode + hardcodedXml);
        o.put("hardcodedCode", hardcodedCode);
        o.put("hardcodedXml", hardcodedXml);
        o.put("hardcodedOccurrences", places);
        o.put("hardcodedTranslatable", hardcodedTranslatable);
        o.put("hardcodedPlacesNotMoved", notMovedPlaces);
        o.put("hardcodedXmlExtracted", extracted);
        o.put("hardcodedCodeExtracted", codeExtracted);
        o.put("hardcodedPlacesExtracted", extractedPlaces);
        o.put("hardcodedPolicy", policy.key);
        JSONObject consumers = new JSONObject();
        consumers.putAll(byConsumer);
        o.put("hardcodedByConsumer", consumers);
        o.put("uniqueTranslatableTexts", (long) unique.size());
        JSONObject perLang = new JSONObject();
        synchronized (this) {
            for (Map.Entry<String, Map<String, String>> l : translations.entrySet()) {
                perLang.put(l.getKey(), (long) l.getValue().size());
            }
        }
        o.put("translationsPerLanguage", perLang);
        return o;
    }

    // ----------------------------------------------------------------- persistence

    /**
     * Seeds the memory with the translations of a previous run's inventory, with the model each language
     * was translated by. Returns how many were loaded.
     */
    public int loadPreviousTranslations(Path json) {
        if (json == null || !Files.isRegularFile(json)) {
            return 0;
        }
        int reused = 0;
        try {
            JSONObject root = (JSONObject) new JSONParser().parse(Files.readString(json, StandardCharsets.UTF_8));
            JSONArray arr = (JSONArray) root.get("entries");
            if (arr == null) {
                return 0;
            }
            Object models = root.get("translationModels");
            if (models instanceof JSONObject) {
                for (Object k : ((JSONObject) models).keySet()) {
                    Object v = ((JSONObject) models).get(k);
                    if (v != null) {
                        modelByLanguage.put(String.valueOf(k), String.valueOf(v));
                    }
                }
            }
            for (Object o : arr) {
                JSONObject e = (JSONObject) o;
                Object text = e.get("text");
                Object tr = e.get("translations");
                if (!(text instanceof String) || !(tr instanceof JSONObject)) {
                    continue;
                }
                for (Object k : ((JSONObject) tr).keySet()) {
                    Object v = ((JSONObject) tr).get(k);
                    if (v != null && translation(String.valueOf(k), (String) text) == null) {
                        putTranslation(String.valueOf(k), (String) text, String.valueOf(v));
                        reused++;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("  inventory: could not reuse " + json + " (" + e.getMessage() + ")");
        }
        return reused;
    }

    /** Writes the entries, origins, skip reasons and translations so far as readable JSON. */
    @SuppressWarnings("unchecked")
    public synchronized void save(Path json) throws IOException {
        JSONObject root = new JSONObject();
        root.put("package", packageName);
        root.put("sourceLanguage", sourceLanguage);
        root.put("generatedAt", LocalDateTime.now().withNano(0).toString());
        root.put("counts", summary());
        JSONObject models = new JSONObject();
        for (Map.Entry<String, String> m : modelByLanguage.entrySet()) {
            if (translations.containsKey(m.getKey())) {
                models.put(m.getKey(), m.getValue());
            }
        }
        root.put("translationModels", models);
        JSONArray arr = new JSONArray();
        for (Entry e : entries) {
            JSONObject o = new JSONObject();
            o.put("id", e.id);
            o.put("kind", e.kind);
            if (!e.name.isEmpty()) {
                o.put("name", e.name);
            }
            if (e.quantity != null) {
                o.put("quantity", e.quantity);
            }
            if (e.index >= 0) {
                o.put("index", (long) e.index);
            }
            o.put("text", e.text);
            o.put("origin", e.origin);
            o.put("translatable", e.translatable);
            if (e.skipReason != null) {
                o.put("skipReason", e.skipReason);
            }
            if (!e.isResource()) {
                o.put("consumer", e.consumer);
                o.put("occurrences", (long) e.occurrences());
                JSONArray places = new JSONArray();
                for (SmaliScan.Occurrence p : e.codePlaces) {
                    if (places.size() >= MAX_PLACES_SAVED) {
                        break;
                    }
                    JSONObject place = new JSONObject();
                    place.put("origin", p.origin());
                    place.put("class", p.className);
                    place.put("method", p.method);
                    place.put("consumer", p.consumer.label);
                    if (!p.via.isEmpty()) {
                        place.put("via", p.via);
                    }
                    place.put("translated", e.extractedPlaces.contains(p));
                    if (e.extractedPlaces.contains(p)) {
                        place.put("extracted", true);
                    }
                    if (e.notMovedPlaces.contains(p)) {
                        place.put("notMoved", true);
                    }
                    places.add(place);
                }
                for (XmlLiterals.Literal p : e.xmlPlaces) {
                    if (places.size() >= MAX_PLACES_SAVED) {
                        break;
                    }
                    JSONObject place = new JSONObject();
                    place.put("origin", p.origin());
                    place.put("element", p.element);
                    if (!p.id.isEmpty()) {
                        place.put("id", p.id);
                    }
                    place.put("attribute", p.attribute);
                    places.add(place);
                }
                o.put("places", places);
                if (e.extractedTo != null) {
                    o.put("extractedTo", "@string/" + e.extractedTo);
                }
                if (e.notMovedReason != null) {
                    o.put("notMovedReason", e.notMovedReason);
                }
            }
            JSONObject tr = new JSONObject();
            for (String lang : translations.keySet()) {
                String t = translation(lang, e.resourceText != null ? e.resourceText : e.text);
                if (t != null) {
                    tr.put(lang, t);
                }
            }
            if (!tr.isEmpty()) {
                o.put("translations", tr);
            }
            arr.add(o);
        }
        root.put("entries", arr);
        Files.writeString(json, pretty(root.toJSONString()), StandardCharsets.UTF_8);
    }

    /** Indents compact JSON for review by hand, json-simple has no pretty printer. */
    static String pretty(String json) {
        StringBuilder sb = new StringBuilder(json.length() * 2);
        int indent = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                sb.append(c);
                if (c == '\\' && i + 1 < json.length()) {
                    sb.append(json.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"':
                    inString = true;
                    sb.append(c);
                    break;
                case '{':
                case '[': {
                    char close = c == '{' ? '}' : ']';
                    if (i + 1 < json.length() && json.charAt(i + 1) == close) {
                        sb.append(c).append(close);
                        i++;
                    } else {
                        sb.append(c);
                        indent++;
                        newline(sb, indent);
                    }
                    break;
                }
                case '}':
                case ']':
                    indent--;
                    newline(sb, indent);
                    sb.append(c);
                    break;
                case ',':
                    sb.append(c);
                    newline(sb, indent);
                    break;
                case ':':
                    sb.append(": ");
                    break;
                default:
                    if (!Character.isWhitespace(c)) {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static void newline(StringBuilder sb, int indent) {
        sb.append('\n');
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
    }
}

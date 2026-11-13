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
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import uniandes.tsdl.itdroid.translator.LLMTranslator;

/**
 * Every user-visible string of the app in one place, each with the file (and smali line) it came
 * from, whether it will be translated and why not, and the translation obtained per language. A
 * hardcoded string also says what the code does with it and lists every place it appears in.
 * Written to {@code <output>/strings_inventory.json} so a human can review or correct it. It is
 * also the translation memory: identical texts are translated once, and a previous inventory in
 * the same output folder seeds the next run so unchanged texts are not sent to the LLM again.
 */
public class StringInventory {

    public static final String KIND_STRING = "string";
    public static final String KIND_PLURALS = "plurals";
    public static final String KIND_ARRAY = "string-array";
    /** A literal of the code (smali). */
    public static final String KIND_HARDCODED = "hardcoded";
    /** A literal written in a layout, a menu, a preference screen or the manifest. */
    public static final String KIND_HARDCODED_XML = "hardcoded-xml";

    /** Use recorded for the literals of the XML, next to the uses the code scan finds (ui, log...). */
    public static final String CONSUMER_XML = "xml";
    /** Why a literal of the XML is listed but not translated. */
    static final String XML_NOT_EXTRACTED = "XML literal (not extracted to a resource)";
    /** Why the name of the app, and any literal equal to it, is kept as written. */
    static final String APP_NAME = "application name";
    /** Places written per literal in the JSON file; {@code occurrences} always has the real count. */
    private static final int MAX_PLACES_SAVED = 50;

    /** One string of the app: a resource value, one item of a container, or a hardcoded literal. */
    public static class Entry {
        public final String id;
        public final String kind;
        public final String name;        // resource name; "" for hardcoded literals
        public final String quantity;    // <plurals> item quantity, else null
        public final int index;          // <string-array> item position, else -1
        public final String text;        // inner XML of the resource, or the decoded smali literal
        public final String origin;      // res/values/strings.xml, or smali_classes2/.../Foo.smali:123
        public final String formatted;   // the <string formatted="..."> attribute, or null
        public final boolean translatable;
        public final String skipReason;  // why it is kept verbatim (null when translatable)
        /** Hardcoded only: what the app does with it (ui, log, exception, data..., or xml); else null. */
        public String consumer;
        /** Hardcoded only: the places of the code that hold this text. */
        public List<SmaliScan.Occurrence> codePlaces = List.of();
        /** Hardcoded only: the XML attributes that hold this text. */
        public List<XmlLiteralFinder.Literal> xmlPlaces = List.of();
        /**
         * Literals of the code: the places that are patched with the translation, chosen by the
         * {@link HardcodedPolicy} from what each place does with the text. Empty when not translatable.
         */
        public List<SmaliScan.Occurrence> patchPlaces = List.of();
        /** Literals of the code: the places rewritten to read the text from a resource (see {@link CodeLiteralExtractor}). */
        public List<SmaliScan.Occurrence> extractedPlaces = List.of();
        /** The name of the string resource a hardcoded text was moved to, else null. */
        public String extractedTo;
        /** A text moved to a resource: the text as that resource holds it (its translation is stored under it). */
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

        /** How many places hold this text (0 for a resource). */
        public int occurrences() {
            return codePlaces.size() + xmlPlaces.size();
        }

        /** Entries of the same resource (all the items of one plurals/array) share this key. */
        public String groupKey() {
            return kind + ":" + name;
        }
    }

    /**
     * Container names that hold data, never UI text: preference entry values, keys, ids, urls,
     * colors, fonts... Translating them breaks the app (the values are compared in code).
     */
    private static final Pattern DATA_CONTAINER_NAME = Pattern.compile(
            "(?i)(^|_)(values?|keys?|ids?|codes?|urls?|uris?|paths?|tags?|locales?|packages?|classes|fonts?"
            + "|colou?rs?|patterns?|formats?|regex|hex|icons?|langs?|languages|entryvalues|models|vendors"
            + "|prefixes|suffixes|mimetypes|extensions)$");

    private static final XMLOutputter RAW = new XMLOutputter(Format.getRawFormat());

    private final List<Entry> entries = new ArrayList<>();
    /** language -> (source text -> translation). Keyed by text so repeated texts are translated once. */
    private final Map<String, Map<String, String>> translations = new TreeMap<>();
    private String packageName = "";
    private String sourceLanguage = "";
    private HardcodedPolicy policy = HardcodedPolicy.DEFAULT;
    private AppName appName = new AppName();
    private int nextId = 0;

    /**
     * Scans {@code res/values/*.xml}, the app's smali and its XML under the decoded folder. With an
     * empty package name only the resources are read.
     */
    public static StringInventory collect(String decodedPath, String packageName,
            NotTranslatableStringsDictionary dictionary) throws IOException {
        boolean code = packageName != null && !packageName.isEmpty();
        return collect(decodedPath, packageName, dictionary,
                code ? HardcodedStringFinder.hits(SmaliScan.scan(decodedPath, packageName)) : List.of(),
                code ? XmlLiteralFinder.find(decodedPath) : List.of());
    }

    /** The same, from a scan already made: the resources plus the given hardcoded literals, with the default policy. */
    public static StringInventory collect(String decodedPath, String packageName, NotTranslatableStringsDictionary dictionary,
            List<HardcodedStringFinder.Hit> hits, List<XmlLiteralFinder.Literal> xmlLiterals) throws IOException {
        return collect(decodedPath, packageName, dictionary, hits, xmlLiterals, HardcodedPolicy.DEFAULT, null);
    }

    /** The same, when no literal of the code was moved to a resource: the chosen places are all patched. */
    public static StringInventory collect(String decodedPath, String packageName, NotTranslatableStringsDictionary dictionary,
            List<HardcodedStringFinder.Hit> hits, List<XmlLiteralFinder.Literal> xmlLiterals, HardcodedPolicy policy,
            XmlLiteralExtractor.Extraction extraction) throws IOException {
        return collect(decodedPath, packageName, dictionary, hits, xmlLiterals, policy, extraction, null);
    }

    /**
     * The places of the code that are translated under a policy, in the order of the scan: what
     * {@link CodeLiteralExtractor} moves to resources. Decided exactly as the inventory decides it.
     */
    public static List<SmaliScan.Occurrence> placesToTranslate(String decodedPath, List<HardcodedStringFinder.Hit> hits,
            HardcodedPolicy policy) {
        AppName name = appName(decodedPath);
        Set<String> sqlIdentifiers = sqlIdentifiers(hits);
        List<SmaliScan.Occurrence> out = new ArrayList<>();
        for (HardcodedStringFinder.Hit h : hits) {
            if (name.texts.contains(h.text.trim()) || codeSkipReason(h, sqlIdentifiers, policy) != null) {
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
     * The inventory of a decoded app.
     *
     * @param policy which places of a literal of the code are translated
     * @param extraction the literals of the XML already moved to resources (they are translated as
     *        resources), or null when none were
     * @param codeExtraction the literals of the code already moved to resources, or null when
     *        none were; the chosen places it did not move are patched per language
     */
    public static StringInventory collect(String decodedPath, String packageName, NotTranslatableStringsDictionary dictionary,
            List<HardcodedStringFinder.Hit> hits, List<XmlLiteralFinder.Literal> xmlLiterals, HardcodedPolicy policy,
            XmlLiteralExtractor.Extraction extraction, CodeLiteralExtractor.Extraction codeExtraction) throws IOException {
        StringInventory inv = new StringInventory();
        inv.packageName = packageName == null ? "" : packageName;
        inv.policy = policy == null ? HardcodedPolicy.DEFAULT : policy;
        inv.appName = appName(decodedPath);

        File valuesDir = new File(decodedPath, "res" + File.separator + "values");
        File[] files = valuesDir.listFiles((d, n) -> n.toLowerCase().endsWith(".xml"));
        if (files != null) {
            // strings.xml first (canonical), then the rest alphabetically: deterministic ids and order.
            Arrays.sort(files, (a, b) -> {
                boolean sa = a.getName().equalsIgnoreCase("strings.xml");
                boolean sb = b.getName().equalsIgnoreCase("strings.xml");
                return sa == sb ? a.getName().compareTo(b.getName()) : (sa ? -1 : 1);
            });
            for (File f : files) {
                if (f.getName().equalsIgnoreCase("donottranslate.xml")) {
                    continue;   // Android convention: everything in it stays verbatim
                }
                inv.collectResourceFile(f, "res/values/" + f.getName(), dictionary);
            }
        }
        Set<String> sqlIdentifiers = sqlIdentifiers(hits);
        int n = 0;
        for (HardcodedStringFinder.Hit h : hits) {
            n++;
            String skip = inv.appName.texts.contains(h.text.trim()) ? APP_NAME : codeSkipReason(h, sqlIdentifiers, inv.policy);
            Entry e = new Entry("h" + n, KIND_HARDCODED, "", null, -1, h.text, h.origin() + more(h.occurrences()), null, skip);
            e.consumer = h.consumer().label;
            e.codePlaces = h.places;
            if (skip == null) {
                List<SmaliScan.Occurrence> toPatch = new ArrayList<>();
                List<SmaliScan.Occurrence> moved = new ArrayList<>();
                for (SmaliScan.Occurrence o : h.places) {
                    if (!inv.policy.translates(o.consumer)) {
                        continue;
                    }
                    if (codeExtraction != null && codeExtraction.places.contains(o)) {
                        moved.add(o);
                    } else {
                        toPatch.add(o);
                    }
                }
                e.patchPlaces = toPatch;
                e.extractedPlaces = moved;
                if (!moved.isEmpty()) {
                    e.extractedTo = codeExtraction.nameByText.get(h.text);
                    e.resourceText = codeExtraction.valueByName.get(e.extractedTo);
                }
            }
            inv.entries.add(e);
        }
        n = 0;
        for (Map.Entry<String, List<XmlLiteralFinder.Literal>> x : HardcodedStringFinder.byText(xmlLiterals).entrySet()) {
            n++;
            // A literal of the XML is translated through the resource it was moved to; one that was
            // not moved is listed so the report shows it.
            String resource = extraction == null ? null : extraction.nameByText.get(x.getKey());
            String skip = textSkipReason(x.getKey());
            if (skip == null && XmlLiteralExtractor.isAppName(x.getValue().get(0), inv.appName.texts)) {
                skip = APP_NAME;
            }
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

    /** The name of the app: the resources that hold it and the texts it is written as. It is never translated. */
    public static final class AppName {
        /** Resource names: {@code app_name} and the string the application label refers to. */
        public final Set<String> resources = new LinkedHashSet<>();
        /** The name itself, as those resources (or a literal label in the manifest) give it. */
        public final Set<String> texts = new LinkedHashSet<>();
    }

    /**
     * Finds the name of the app in a decoded folder: the {@code android:label} of the application
     * element of the manifest (a reference or a literal) and the string called {@code app_name}.
     */
    public static AppName appName(String decodedPath) {
        AppName out = new AppName();
        out.resources.add("app_name");
        try {
            File manifest = new File(decodedPath, "AndroidManifest.xml");
            if (manifest.isFile()) {
                Element application = new SAXBuilder().build(manifest).getRootElement().getChild("application");
                String label = application == null ? null : application.getAttributeValue("label",
                        org.jdom2.Namespace.getNamespace("android", "http://schemas.android.com/apk/res/android"));
                if (label != null && label.startsWith("@string/")) {
                    out.resources.add(label.substring("@string/".length()));
                } else if (label != null && !label.startsWith("@") && !label.trim().isEmpty()) {
                    out.texts.add(label.trim());
                }
            }
        } catch (Exception e) {
            // unreadable manifest: only app_name is known
        }
        File[] files = new File(decodedPath, "res" + File.separator + "values").listFiles((d, f) -> f.toLowerCase().endsWith(".xml"));
        if (files != null) {
            for (File f : files) {
                try {
                    for (Element e : new SAXBuilder().build(f).getRootElement().getChildren("string")) {
                        String text = e.getTextTrim();
                        if (out.resources.contains(e.getAttributeValue("name")) && !text.isEmpty() && !text.startsWith("@")) {
                            out.texts.add(text);
                        }
                    }
                } catch (Exception e) {
                    // not a resource file
                }
            }
        }
        return out;
    }

    private static String more(int occurrences) {
        return occurrences > 1 ? " (+" + (occurrences - 1) + " more)" : "";
    }

    /**
     * Shapes that are data when nothing is known about the literal, but ordinary labels when the
     * code shows it: an all-caps button ("CANCEL"), a word that is also the name of a table.
     */
    private static final Set<String> REASONS_UI_OVERRIDES = Set.of("constant", "SQL identifier");

    /**
     * Why a literal of the code is kept verbatim, or null when it is translated. Three checks, in
     * order: the text rules every string passes; the shapes only code has (intent actions, SQL,
     * table names, hashes, regular expressions: a translated table name or query crashes the app
     * at startup); and what the code does with it, under the policy. A literal is translated
     * when the policy translates at least one of its places; one whose every use is a log, an
     * exception, data or compiler plumbing never reaches the screen, and is listed but never sent
     * to the LLM.
     */
    static String codeSkipReason(HardcodedStringFinder.Hit h, Set<String> sqlIdentifiers, HardcodedPolicy policy) {
        String skip = textSkipReason(h.text);
        if (skip != null) {
            return skip;
        }
        String shape = hardcodedSkipReason(h.text, sqlIdentifiers);
        if (shape != null && !(h.shown() && REASONS_UI_OVERRIDES.contains(shape))) {
            return shape;
        }
        for (SmaliScan.Occurrence o : h.places) {
            if (policy.translates(o.consumer)) {
                return null;
            }
        }
        return h.neverShown() ? h.consumer().description : "use not known: " + h.consumer().description;
    }

    /**
     * SQL statements, recognised by their syntax rather than their first word: "Select a file" and
     * "Create JSON file" are UI labels, "SELECT * FROM shortcut" and "CREATE TABLE x" are not.
     */
    private static final Pattern SQL_STATEMENT = Pattern.compile(
            "(?is)^\\s*(insert\\s+(or\\s+\\w+\\s+)?into\\b|update\\s+(or\\s+\\w+\\s+)?`?[A-Za-z_]\\w*`?\\s+set\\b|delete\\s+from\\b"
            + "|create\\s+(temp\\s+|temporary\\s+|unique\\s+|virtual\\s+)?(table|index|view|trigger)\\b|drop\\s+(table|index|view|trigger)\\b"
            + "|alter\\s+table\\b|pragma\\s+\\w+|with\\s+\\w+\\s+as\\s*\\(|replace\\s+into\\b|(begin|commit|rollback|vacuum)(\\s+transaction)?\\s*;?\\s*$)");
    private static final Pattern SQL_SELECT = Pattern.compile("(?is)^\\s*select\\b.*(\\*|\\bwhere\\b|\\border\\s+by\\b|\\bgroup\\s+by\\b|\\bjoin\\b|\\bcount\\s*\\(|\\bexists\\s*\\(|\\bdistinct\\b|\\blimit\\b|`).*");

    static boolean isSql(String text) {
        return SQL_STATEMENT.matcher(text).find() || SQL_SELECT.matcher(text).matches();
    }
    /** Table/column names used by the app's own SQL: FROM x, INTO x, TABLE x, `x`. */
    private static final Pattern SQL_IDENTIFIER = Pattern.compile(
            "(?i)\\b(?:from|into|table|update|join)\\s+(?:if\\s+(?:not\\s+)?exists\\s+)?`?([A-Za-z_]\\w*)`?|`([A-Za-z_]\\w*)`");
    /** A backslash followed by a regex metacharacter or class, or the classic "(.*)" capture. */
    private static final Pattern REGEX_LIKE = Pattern.compile("\\\\[{}()\\[\\]dswDSW.+*?|^$]|\\(\\.\\*\\)|\\[\\^");
    /** Messages the Kotlin/Android runtime throws, inlined into the app's smali; never shown as UI. */
    private static final Pattern RUNTIME_MESSAGE = Pattern.compile(
            "^(null cannot be cast to non-null type|lateinit property|Required value was null|Parameter specified as non-null"
            + "|Check failed|Failed requirement|An operation is not implemented|Super calls with default arguments"
            + "|call to 'resume' before 'invoke'|Missing required view with ID|Index \\d+ out of bounds|This job has not completed"
            + "|Cannot coerce|Unexpected (token|char|end)|No such (table|column))");

    /** Why a smali literal is data rather than UI text, or null when it may be shown to the user. */
    static String hardcodedSkipReason(String text, Set<String> sqlIdentifiers) {
        String t = text.trim();
        if (isSql(t)) {
            return "SQL statement";
        }
        if (t.indexOf('`') >= 0) {
            return "code with backticks";
        }
        if (t.matches("^[0-9a-fA-F]{16,}$")) {
            return "hash";
        }
        if (t.matches("^<[^<>]*>$")) {
            return "code token";
        }
        if (t.matches("^\\.[A-Za-z0-9]{1,10}$")) {
            return "file extension";                 // .png, .json, .mmt
        }
        if (t.matches("^\\S*_\\S*$")) {
            return "identifier";                     // __db, cover_, song_allsongs_, is_sort_ascending_, room_fts_content_sync_
        }
        if (t.matches("^[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+$")) {
            return "dotted identifier";              // Filled.Headphones, window.decorView, com.x.Y
        }
        if (t.matches("^'?[A-Z][A-Z0-9_]{2,}'?$") || t.matches("(?i)^(NO ACTION|SET NULL|SET DEFAULT|CASCADE|RESTRICT)$")) {
            return "constant";                       // TEXT, INTEGER, MD5, NO_FILE_SELECTED, 'TEXT', NO ACTION
        }
        if (t.matches("^[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+$")) {
            return "class-like identifier";          // JsonShortcutUtils, MainActivity
        }
        if (t.matches("(?i)^[a-z_][\\w.]*\\s*(=|!=|<>|<=|>=|<|>|like|in|is)\\s*(\\?|-?\\d+|'[^']*'|\\(.*\\)|null|not null)\\s*$")) {
            return "SQL fragment";                   // is_music != 0, duration > ?, _data LIKE ?
        }
        if (t.matches("(?i)^[\\w.()]+\\s+(asc|desc)(\\s*,\\s*[\\w.()]+(\\s+(asc|desc))?)*$")) {
            return "SQL fragment";                   // title ASC, date_added DESC, album, track ASC
        }
        if (t.matches("^[\\w-]+(,[\\w-]+){2,}$")) {
            return "token list";                     // like,shuffle,repeat,crossfade
        }
        if (REGEX_LIKE.matcher(t).find()) {
            return "regular expression";
        }
        if (RUNTIME_MESSAGE.matcher(t).find()) {
            return "runtime message";
        }
        if (!t.contains(" ") && sqlIdentifiers.contains(t.toLowerCase())) {
            return "SQL identifier";                 // the table name passed to Room next to its queries
        }
        return null;
    }

    /** Identifiers named by the SQL literals of the app (lower-cased), to spot bare table/column names. */
    static Set<String> sqlIdentifiers(List<HardcodedStringFinder.Hit> hits) {
        Set<String> ids = new java.util.HashSet<>();
        for (HardcodedStringFinder.Hit h : hits) {
            if (!isSql(h.text)) {
                continue;
            }
            java.util.regex.Matcher m = SQL_IDENTIFIER.matcher(h.text);
            while (m.find()) {
                String id = m.group(1) != null ? m.group(1) : m.group(2);
                if (id != null) {
                    ids.add(id.toLowerCase());
                }
            }
        }
        return ids;
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
                String skip = resourceSkipReason(e, name, dictionary);
                if (skip == null) {
                    skip = textSkipReason(text);
                }
                entries.add(new Entry(nextId("r"), kind, name, null, -1, text, origin, e.getAttributeValue("formatted"), skip));
            } else if (KIND_PLURALS.equals(kind) || KIND_ARRAY.equals(kind)) {
                String containerSkip = resourceSkipReason(e, name, dictionary);
                if (containerSkip == null && DATA_CONTAINER_NAME.matcher(name).find()) {
                    containerSkip = "data container name";
                }
                int index = 0;
                for (Element item : e.getChildren("item")) {
                    String text = innerXml(item);
                    String skip = containerSkip != null ? containerSkip : textSkipReason(text);
                    entries.add(new Entry(nextId("r"), kind, name, item.getAttributeValue("quantity"),
                            KIND_ARRAY.equals(kind) ? index : -1, text, origin, null, skip));
                    index++;
                }
            }
            // color, dimen, bool, integer, style, item type=...: never user-visible text
        }
    }

    private String nextId(String prefix) {
        nextId++;
        return prefix + nextId;
    }

    private String resourceSkipReason(Element e, String name, NotTranslatableStringsDictionary dictionary) {
        if ("false".equalsIgnoreCase(e.getAttributeValue("translatable"))) {
            return "translatable=\"false\"";
        }
        if (appName.resources.contains(name)) {
            return APP_NAME;
        }
        if (dictionary != null && !dictionary.translatable(name)) {
            return "not-translatable dictionary";
        }
        return null;
    }

    /** Why a text must stay verbatim, or null when it is human-readable UI text. */
    public static String textSkipReason(String text) {
        if (text == null) {
            return "empty";
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return "empty";
        }
        if (t.startsWith("@") || t.startsWith("?")) {
            return "resource reference";
        }
        if (!t.matches("(?s).*\\p{L}.*")) {
            return "no letters";
        }
        if (LLMTranslator.isOnlyNumbersAndSpecs(t)) {
            return "numbers/symbols only";
        }
        if (LLMTranslator.looksLikeNonText(t)) {
            return "not human-readable text";
        }
        if (looksLikeKeyOrToken(t)) {
            return "key or token";
        }
        return null;
    }

    /**
     * An API key, a token, an encoded secret: one long run of letters and digits with no space,
     * such as {@code AIzaSyD4x...} or a Base64 value. Never text for a person, in a resource or in
     * the code.
     */
    static boolean looksLikeKeyOrToken(String t) {
        return t.length() >= 24 && t.matches("[A-Za-z0-9+/_=.-]+") && t.matches(".*[0-9].*") && t.matches(".*[A-Za-z].*")
                && !t.contains("..") && t.chars().filter(c -> c == '.').count() <= 2;
    }

    /** The element's content as XML text, so inline markup ({@code <b>}, {@code <xliff:g>}) is kept. */
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

    /**
     * Unique literals of the code that are patched into the smali, in discovery order. A literal
     * whose places were all moved to a resource is not here: it is translated as a resource.
     */
    public List<String> hardcodedTexts() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Entry e : entries) {
            if (KIND_HARDCODED.equals(e.kind) && e.translatable && !e.patchPlaces.isEmpty()) {
                out.add(e.text);
            }
        }
        return new ArrayList<>(out);
    }

    /** The places of the code to patch for the translatable literals, in the order of the scan. */
    public List<SmaliScan.Occurrence> hardcodedPlaces() {
        List<SmaliScan.Occurrence> out = new ArrayList<>();
        for (Entry e : entries) {
            if (KIND_HARDCODED.equals(e.kind) && e.translatable) {
                out.addAll(e.patchPlaces);
            }
        }
        out.sort(java.util.Comparator.comparingInt(o -> o.order));
        return out;
    }

    /** Names of the string resources the hardcoded literals (of the XML and of the code) were moved to. */
    public Set<String> extractedResources() {
        Set<String> out = new LinkedHashSet<>();
        for (Entry e : entries) {
            if (e.extractedTo != null) {
                out.add(e.extractedTo);
            }
        }
        return out;
    }

    public HardcodedPolicy policy() {
        return policy;
    }

    public String translation(String lang, String text) {
        Map<String, String> m = translations.get(lang);
        return m == null ? null : m.get(text);
    }

    public void putTranslation(String lang, String text, String translated) {
        translations.computeIfAbsent(lang, k -> new LinkedHashMap<>()).put(text, translated);
    }

    public void setSourceLanguage(String sourceLanguage) {
        this.sourceLanguage = sourceLanguage == null ? "" : sourceLanguage;
    }

    /**
     * Counts for the report. The hardcoded strings have one breakdown, the one every output shows:
     * {@code hardcoded} distinct literals ({@code hardcodedCode} in the code plus
     * {@code hardcodedXml} in the XML), found in {@code hardcodedOccurrences} places, split by what
     * the app does with them in {@code hardcodedByConsumer}; {@code hardcodedTranslatable} of them
     * are translated.
     */
    @SuppressWarnings("unchecked")
    public JSONObject summary() {
        long resources = 0, resourcesTranslatable = 0, hardcodedCode = 0, hardcodedXml = 0, hardcodedTranslatable = 0, places = 0;
        long extracted = 0, patchPlaces = 0, codeExtracted = 0, extractedPlaces = 0;
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
                patchPlaces += e.patchPlaces.size();
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
        o.put("hardcodedPlacesToPatch", patchPlaces);
        o.put("hardcodedXmlExtracted", extracted);
        o.put("hardcodedCodeExtracted", codeExtracted);
        o.put("hardcodedPlacesExtracted", extractedPlaces);
        o.put("hardcodedPolicy", policy.key);
        JSONObject consumers = new JSONObject();
        consumers.putAll(byConsumer);
        o.put("hardcodedByConsumer", consumers);
        o.put("uniqueTranslatableTexts", (long) unique.size());
        JSONObject perLang = new JSONObject();
        for (Map.Entry<String, Map<String, String>> l : translations.entrySet()) {
            perLang.put(l.getKey(), (long) l.getValue().size());
        }
        o.put("translationsPerLanguage", perLang);
        return o;
    }

    // ----------------------------------------------------------------- persistence

    /** Seeds the memory with the translations of a previous run's inventory. Returns how many were reused. */
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

    /** Writes the inventory (entries, origins, skip reasons and the translations so far) as readable JSON. */
    @SuppressWarnings("unchecked")
    public void save(Path json) throws IOException {
        JSONObject root = new JSONObject();
        root.put("package", packageName);
        root.put("sourceLanguage", sourceLanguage);
        root.put("generatedAt", LocalDateTime.now().withNano(0).toString());
        root.put("counts", summary());
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
                    place.put("translated", e.patchPlaces.contains(p) || e.extractedPlaces.contains(p));
                    if (e.extractedPlaces.contains(p)) {
                        place.put("extracted", true);
                    }
                    places.add(place);
                }
                for (XmlLiteralFinder.Literal p : e.xmlPlaces) {
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

    /** Indents compact JSON (json-simple has no pretty printer) so the file is reviewable by hand. */
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

package uniandes.tsdl.itdroid.helper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;

/**
 * Every rule that decides whether a text of the app is translated, in one place, so a resource, a
 * code literal and a layout literal of the same shape get the same answer. The sections are resource
 * names, resource values, code literals, layout literals, the app name and the protected terms that
 * travel masked inside a translated text.
 */
public final class Translatability {

    private Translatability() {
    }

    // ================================================================== resource names

    /** Why the app name, and any literal equal to it, is kept as written. */
    public static final String APP_NAME = "application name";

    /** Container names that hold data, never UI text, translating them breaks the app. */
    private static final Pattern DATA_CONTAINER_NAME = Pattern.compile(
            "(?i)(^|_)(values?|keys?|ids?|codes?|urls?|uris?|paths?|tags?|locales?|packages?|classes|fonts?"
            + "|colou?rs?|patterns?|formats?|regex|hex|icons?|langs?|languages|entryvalues|models|vendors"
            + "|prefixes|suffixes|mimetypes|extensions)$");

    /** Names of keys, ids and addresses, a hint that only counts for a value without spaces. */
    private static final Pattern IDENTIFIER_NAME = Pattern.compile(
            "(?i)^(pref|key|event)_|_(key|url|uri|scheme|endpoint|path|token|tag|id|action|extra|authority|package|class|fragment)$");

    /** Why a resource is kept verbatim because of its element or its name, or null. */
    public static String nameSkip(Element e, NotTranslatableStringsDictionary dictionary, Set<String> appNameResources) {
        String name = e.getAttributeValue("name");
        if ("false".equalsIgnoreCase(e.getAttributeValue("translatable"))) {
            return "translatable=\"false\"";
        }
        if (appNameResources != null && appNameResources.contains(name)) {
            return APP_NAME;
        }
        if (dictionary != null && !dictionary.translatable(name)) {
            return "not-translatable dictionary";
        }
        return null;
    }

    /** The same for a plurals or string-array, whose name may also say it holds data. */
    public static String containerSkip(Element e, NotTranslatableStringsDictionary dictionary, Set<String> appNameResources) {
        String skip = nameSkip(e, dictionary, appNameResources);
        if (skip == null && DATA_CONTAINER_NAME.matcher(e.getAttributeValue("name")).find()) {
            skip = "data container name";
        }
        return skip;
    }

    /** Why a string resource is kept verbatim, by its element and name first and then by its value. */
    public static String stringSkip(Element e, String text, NotTranslatableStringsDictionary dictionary, Set<String> appNameResources) {
        String skip = nameSkip(e, dictionary, appNameResources);
        return skip != null ? skip : resourceValueSkip(e.getAttributeValue("name"), text);
    }

    // ================================================================== resource values

    /** Why a text must stay verbatim whatever holds it, or null when it may be text for a person. */
    public static String textSkip(String text) {
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
        if (looksLikeNonText(t)) {
            return "not human-readable text";
        }
        if (looksLikeKeyOrToken(t)) {
            return "key or token";
        }
        return null;
    }

    /**
     * Why a resource value is kept verbatim, or null. A value without spaces is also checked for the
     * shapes of identifiers, and with a name like pref_ or _id it stays unless it is an ordinary word.
     */
    public static String resourceValueSkip(String name, String text) {
        String skip = textSkip(text);
        if (skip == null) {
            skip = identifierShape(text);
        }
        if (skip == null && name != null && IDENTIFIER_NAME.matcher(name).find()) {
            String t = unquote(text.trim());
            if (!t.contains(" ") && t.matches("[\\w.:/-]+") && !isOrdinaryWord(t)) {
                skip = "identifier (by its resource name)";
            }
        }
        return skip;
    }

    private static final Pattern SNAKE_CASE = Pattern.compile("^[A-Za-z0-9]*_[A-Za-z0-9_]*$");
    private static final Pattern CAMEL_CASE = Pattern.compile("^[a-z][a-z0-9]*(?:[A-Z][a-z0-9]*)+$");
    private static final Pattern DOTTED_NAME = Pattern.compile("^[A-Za-z_][\\w-]*(?:\\.[A-Za-z0-9_][\\w-]*)+$");
    private static final Pattern CLASS_NAME = Pattern.compile(
            "^[A-Z][A-Za-z0-9]*(?:Fragment|Activity|Service|Receiver|Provider|Dialog|View|Adapter)$");
    /** Runs of one date pattern letter, or quoted literals, with separators between them. */
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "^(?:([GyYMLwWDdFEuaHkKhmsSzZX])\\1*|'[^']*')(?:[\\s,./:-]+(?:([GyYMLwWDdFEuaHkKhmsSzZX])\\2*|'[^']*'))+$");
    private static final Pattern DATE_PATTERN_RUN = Pattern.compile("y{2,}|M{2,}|d{2}|H{2}|h:|m{2}|s{2}|E{3,}|k{2}");
    private static final Pattern DATE_PATTERN_ALONE = Pattern.compile("^(?:y{2,4}|M{3,4}|E{3,4}|d{2}|H{2})$");
    private static final Pattern URI = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9+.-]*://\\S*$|^(?:mailto|tel|sms|geo|market|intent):\\S+$|^[\\w-]+(?:\\.[\\w-]+)+/\\S*$");
    private static final Pattern PATH = Pattern.compile(
            "^(?:~|\\.{1,2})?/[\\w.@%+~-]+(?:/[\\w.@%+~-]*)*$|^[a-z0-9._-]+(?:/[a-z0-9._#-]+){2,}/?$|^[\\w@%+~-]+(?:/[\\w@%+~-]+)+\\.[A-Za-z0-9]{1,5}$");
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9+/_=.-]{16,}$");
    private static final Pattern MIME_OR_COLOR = Pattern.compile(
            "^(?:application|text|image|audio|video|font|multipart|message|model)/[\\w.+*-]+$|^#[0-9A-Fa-f]{3,8}$");
    private static final Pattern ORDINARY_WORD = Pattern.compile("^\\p{Lu}\\p{Ll}+(?:['\u2019-]\\p{Ll}+)*[:.!?\u2026]?$");

    /**
     * Why a value is an identifier rather than text, or null. Only values without spaces are checked,
     * except date patterns such as MMM d, yyyy.
     */
    public static String identifierShape(String text) {
        if (text == null) {
            return null;
        }
        String t = unquote(text.trim());
        if (isDatePattern(t)) {
            return "date or time pattern";
        }
        if (t.isEmpty() || t.contains(" ") || !t.matches("(?s).*\\p{L}.*")) {
            return null;
        }
        if (URI.matcher(t).matches()) {
            return "URI or address";
        }
        if (MIME_OR_COLOR.matcher(t).matches()) {
            return "MIME type or color";
        }
        if (PATH.matcher(t).matches()) {
            return "path";
        }
        if (SNAKE_CASE.matcher(t).matches()) {
            return "identifier";
        }
        if (CAMEL_CASE.matcher(t).matches()) {
            return "camelCase identifier";
        }
        if (DOTTED_NAME.matcher(t).matches()) {
            return "dotted name";
        }
        if (CLASS_NAME.matcher(t).matches()) {
            return "class name";
        }
        if (TOKEN.matcher(t).matches() && t.matches(".*\\d.*") && t.matches(".*[A-Za-z].*")) {
            return "key or token";
        }
        return null;
    }

    /** A pattern of SimpleDateFormat such as yyyy-MM-dd or MMM d, yyyy, or a time pattern of hours and minutes. */
    static boolean isDatePattern(String t) {
        return DATE_PATTERN_ALONE.matcher(t).matches() || (DATE_PATTERN.matcher(t).matches() && DATE_PATTERN_RUN.matcher(t).find());
    }

    /** A capitalised word, or a word with letters beyond ASCII, which a name hint does not make an identifier. */
    private static boolean isOrdinaryWord(String t) {
        return ORDINARY_WORD.matcher(t).matches() || t.matches(".*[^\\x00-\\x7f].*");
    }

    /** The value without the double quotes that Android puts around a whole value. */
    private static String unquote(String t) {
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1).trim() : t;
    }

    /** An API key, token or encoded secret, one long run of letters and digits with no space. */
    static boolean looksLikeKeyOrToken(String t) {
        return t.length() >= 24 && t.matches("[A-Za-z0-9+/_=.-]+") && t.matches(".*[0-9].*") && t.matches(".*[A-Za-z].*")
                && !t.contains("..") && t.chars().filter(c -> c == '.').count() <= 2;
    }

    /**
     * True for values that are not UI text, such as path data, easing functions, class names and font
     * families, which Material Components ships in strings.xml. Translating them corrupts the app.
     */
    static boolean looksLikeNonText(String v) {
        return v.matches("(?s)^[MmLlHhVvCcSsQqTtAaZz]\\s*-?\\d+(\\.\\d+)?[\\s,]-?\\d.*")   // M12,4.5C7 path data
                || v.matches("(?is)^(cubic-bezier|path|linear|steps|spline)\\s*\\(.*")
                || v.matches("^[a-z]\\w*(\\.[A-Za-z_$][\\w$]*){2,}$")                     // com.example.Foo$Bar
                || v.matches("^[a-z]\\w*\\.[a-zA-Z_$][\\w$]*$")                            // window.decorView
                || v.matches("(?i)^(sans-serif(-[a-z]+)?|serif|monospace|casual|cursive)$");
    }

    // ================================================================== code literals

    /** Shapes that are data unless the code shows the literal, such as an all caps button label. */
    private static final Set<String> REASONS_UI_OVERRIDES = Set.of("constant", "SQL identifier");

    /**
     * Why a code literal is kept verbatim, or null when it is translated. Checks the text rules, the
     * shapes of code and identifiers, then whether the policy translates any place it is used in.
     */
    public static String codeSkip(HardcodedStringFinder.Hit h, Set<String> sqlIdentifiers, HardcodedPolicy policy) {
        String skip = textSkip(h.text);
        if (skip != null) {
            return skip;
        }
        String shape = codeShape(h.text, sqlIdentifiers);
        if (shape != null && !(h.shown() && REASONS_UI_OVERRIDES.contains(shape))) {
            return shape;
        }
        shape = identifierShape(h.text);
        if (shape != null) {
            return shape;
        }
        for (SmaliScan.Occurrence o : h.places) {
            if (translatesUse(policy, o.consumer)) {
                return null;
            }
        }
        return h.neverShown() ? h.consumer().description : "use not known: " + h.consumer().description;
    }

    /** SQL statements, recognised by their syntax rather than their first word. */
    private static final Pattern SQL_STATEMENT = Pattern.compile(
            "(?is)^\\s*(insert\\s+(or\\s+\\w+\\s+)?into\\b|update\\s+(or\\s+\\w+\\s+)?`?[A-Za-z_]\\w*`?\\s+set\\b|delete\\s+from\\b"
            + "|create\\s+(temp\\s+|temporary\\s+|unique\\s+|virtual\\s+)?(table|index|view|trigger)\\b|drop\\s+(table|index|view|trigger)\\b"
            + "|alter\\s+table\\b|pragma\\s+\\w+|with\\s+\\w+\\s+as\\s*\\(|replace\\s+into\\b|(begin|commit|rollback|vacuum)(\\s+transaction)?\\s*;?\\s*$)");
    private static final Pattern SQL_SELECT = Pattern.compile("(?is)^\\s*select\\b.*(\\*|\\bwhere\\b|\\border\\s+by\\b|\\bgroup\\s+by\\b|\\bjoin\\b|\\bcount\\s*\\(|\\bexists\\s*\\(|\\bdistinct\\b|\\blimit\\b|`).*");
    /** Table and column names used by the app's own SQL. */
    private static final Pattern SQL_IDENTIFIER = Pattern.compile(
            "(?i)\\b(?:from|into|table|update|join)\\s+(?:if\\s+(?:not\\s+)?exists\\s+)?`?([A-Za-z_]\\w*)`?|`([A-Za-z_]\\w*)`");
    /** A backslash before a regex metacharacter or class, or a catch all capture group. */
    private static final Pattern REGEX_LIKE = Pattern.compile("\\\\[{}()\\[\\]dswDSW.+*?|^$]|\\(\\.\\*\\)|\\[\\^");
    /** Kotlin and Android runtime messages inlined into the smali, never shown as UI. */
    private static final Pattern RUNTIME_MESSAGE = Pattern.compile(
            "^(null cannot be cast to non-null type|lateinit property|Required value was null|Parameter specified as non-null"
            + "|Check failed|Failed requirement|An operation is not implemented|Super calls with default arguments"
            + "|call to 'resume' before 'invoke'|Missing required view with ID|Index \\d+ out of bounds|This job has not completed"
            + "|Cannot coerce|Unexpected (token|char|end)|No such (table|column))");

    static boolean isSql(String text) {
        return SQL_STATEMENT.matcher(text).find() || SQL_SELECT.matcher(text).matches();
    }

    /** Why a smali literal has the shape of code rather than UI text, or null. */
    static String codeShape(String text, Set<String> sqlIdentifiers) {
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
            return "file extension";                 // .png, .json
        }
        if (t.matches("^\\S*_\\S*$")) {
            return "identifier";                     // cover_, is_sort_ascending_
        }
        if (t.matches("^[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+$")) {
            return "dotted identifier";              // window.decorView, com.x.Y
        }
        if (t.matches("^'?[A-Z][A-Z0-9_]{2,}'?$") || t.matches("(?i)^(NO ACTION|SET NULL|SET DEFAULT|CASCADE|RESTRICT)$")) {
            return "constant";                       // TEXT, MD5, NO ACTION
        }
        if (t.matches("^[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+$")) {
            return "class-like identifier";          // MainActivity
        }
        if (t.matches("(?i)^[a-z_][\\w.]*\\s*(=|!=|<>|<=|>=|<|>|like|in|is)\\s*(\\?|-?\\d+|'[^']*'|\\(.*\\)|null|not null)\\s*$")) {
            return "SQL fragment";                   // a WHERE condition
        }
        if (t.matches("(?i)^[\\w.()]+\\s+(asc|desc)(\\s*,\\s*[\\w.()]+(\\s+(asc|desc))?)*$")) {
            return "SQL fragment";                   // an ORDER BY clause
        }
        if (t.matches("^[\\w-]+(,[\\w-]+){2,}$")) {
            return "token list";                     // like,shuffle,repeat
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

    /** Lower cased identifiers named by the app's SQL literals, to spot bare table and column names. */
    static Set<String> sqlIdentifiers(List<HardcodedStringFinder.Hit> hits) {
        Set<String> ids = new HashSet<>();
        for (HardcodedStringFinder.Hit h : hits) {
            if (!isSql(h.text)) {
                continue;
            }
            Matcher m = SQL_IDENTIFIER.matcher(h.text);
            while (m.find()) {
                String id = m.group(1) != null ? m.group(1) : m.group(2);
                if (id != null) {
                    ids.add(id.toLowerCase());
                }
            }
        }
        return ids;
    }

    /** Framework and compiler noise such as Compose keys, descriptors, URLs, paths and formats, never listed. */
    static boolean isNoise(String s) {
        String t = s.trim();
        if (t.length() < 2) {
            return true;                                   // single chars
        }
        if (!t.matches("(?s).*\\p{L}.*")) {
            return true;                                   // no letter
        }
        if (t.contains(".kt#") || t.contains(".java#") || t.contains(".kt:") || t.contains(".java:")
                || t.matches("(?s).*@\\d+L\\d+.*") || t.contains("<anonymous>")) {
            return true;                                   // Compose keys and trace strings
        }
        if (t.startsWith("$")) {
            return true;                                   // Kotlin synthetic names
        }
        if (t.startsWith(", ") || t.endsWith("=")) {
            return true;                                   // generated toString() fragments
        }
        if (t.contains("://")) {
            return true;                                   // URLs
        }
        if (t.startsWith("L") && t.endsWith(";")) {
            return true;                                   // type descriptors
        }
        if (t.contains("/") && !t.contains(" ")) {
            return true;                                   // class or file paths
        }
        if (t.contains("(...)")) {
            return true;                                   // method references
        }
        if (t.contains("yyyy") || t.contains("HH:mm") || t.matches("(?s).*\\bMMM?\\b.*dd.*")) {
            return true;                                   // date and time format patterns
        }
        return false;
    }

    /** A bare identifier, listed only when the code is seen showing it. */
    static boolean isBareIdentifier(String s) {
        String t = s.trim();
        if (!t.contains(" ") && t.matches("[a-z][a-zA-Z0-9]*")) {
            return true;                                   // camelCase identifier
        }
        return t.matches("[a-z0-9]+(_[a-z0-9]+)+");        // snake_case identifiers and routes
    }

    /** True for the uses that never put the text in front of the user. */
    public static boolean neverShown(SmaliScan.Consumer consumer) {
        return consumer == SmaliScan.Consumer.LOG || consumer == SmaliScan.Consumer.EXCEPTION
                || consumer == SmaliScan.Consumer.DATA || consumer == SmaliScan.Consumer.COMPILER;
    }

    /** True when a literal used this way is translated under the policy of hardcoded.translate. */
    public static boolean translatesUse(HardcodedPolicy policy, SmaliScan.Consumer consumer) {
        switch (policy) {
            case UI:
                return consumer == SmaliScan.Consumer.UI || consumer == SmaliScan.Consumer.COMPOSABLE;
            case UI_AND_UNKNOWN:
                return !neverShown(consumer);
            default:
                return true;
        }
    }

    /**
     * True when a setter of a setter rule shows the text. A framework or library setter does, a method
     * of the app only when its one argument is the text, since JsonUtils.setText(node, key, value) is not UI.
     */
    static boolean setterShowsText(boolean appClass, String callee) {
        if (!appClass) {
            return true;
        }
        int open = callee.indexOf('(');
        int close = callee.indexOf(')', open + 1);
        String params = open >= 0 && close > open ? callee.substring(open + 1, close) : "";
        return params.equals("Ljava/lang/CharSequence;") || params.equals("Ljava/lang/String;");
    }

    // ================================================================== layout literals

    /** File name prefixes of the XML that common libraries add to every APK. */
    private static final String[] LIBRARY_LAYOUT_PREFIXES = {"abc_", "mtrl_", "m3_", "material_", "design_", "notification_template_",
            "notification_action", "select_dialog_", "support_simple_", "browser_actions_", "custom_dialog", "ime_",
            "preference_", "expand_button", "image_frame", "test_", "text_view_with_", "md_", "exo_", "leak_", "ucrop_",
            "zxing_", "fingerprint_dialog_", "splits", "standalone_badge", "tooltip", "androidx_", "com_google_android_",
            "glance_", "invalid_list_item", "bonuspack_"};
    private static final Pattern FORMAT_SPECIFIER = Pattern.compile("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z]");

    /** True for a layout, menu or preference file a library adds, whose literals are not the app's. */
    static boolean isLibraryLayout(String fileName) {
        for (String prefix : LIBRARY_LAYOUT_PREFIXES) {
            if (fileName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** The text of an attribute value when it is literal text with at least one letter, else null. */
    static String layoutText(String value) {
        if (value == null) {
            return null;
        }
        String v = value;
        if (v.startsWith("\\@") || v.startsWith("\\?")) {
            v = v.substring(1);                              // an escaped @ or ? is text, not a reference
        } else if (v.startsWith("@") || v.startsWith("?")) {
            return null;                                     // a reference or binding
        }
        // format specifiers alone hold no text of their own
        return FORMAT_SPECIFIER.matcher(v).replaceAll("").matches("(?s).*\\p{L}.*") ? v : null;
    }

    /** Why a literal of the XML is kept where it is, or null when it is moved to a resource and translated. */
    static String layoutSkip(XmlLiterals.Literal l, Set<String> appNames) {
        String skip = textSkip(l.text);
        if (skip == null) {
            skip = identifierShape(l.text);
        }
        if (skip == null && isAppNameLiteral(l, appNames)) {
            skip = APP_NAME;
        }
        return skip;
    }

    /** The label of the application element, or any literal equal to the app's name. */
    static boolean isAppNameLiteral(XmlLiterals.Literal l, Set<String> appNames) {
        return ("AndroidManifest.xml".equals(l.file) && "application".equals(l.element) && l.attribute.endsWith(":label"))
                || appNames.contains(l.text);
    }

    // ================================================================== app name

    /** The app name, which is never translated, with the resources that hold it and its texts. */
    public static final class AppName {
        /** {@code app_name} and the string the application label refers to. */
        public final Set<String> resources = new LinkedHashSet<>();
        /** The name itself, as those resources or a literal manifest label give it. */
        public final Set<String> texts = new LinkedHashSet<>();
    }

    /** Finds the app name from the manifest application label and the {@code app_name} string. */
    public static AppName appName(String decodedPath) {
        AppName out = new AppName();
        out.resources.add("app_name");
        try {
            File manifest = new File(decodedPath, "AndroidManifest.xml");
            if (manifest.isFile()) {
                Element application = new SAXBuilder().build(manifest).getRootElement().getChild("application");
                String label = application == null ? null : application.getAttributeValue("label",
                        Namespace.getNamespace("android", "http://schemas.android.com/apk/res/android"));
                if (label != null && label.startsWith("@string/")) {
                    out.resources.add(label.substring("@string/".length()));
                } else if (label != null && !label.startsWith("@") && !label.trim().isEmpty()) {
                    out.texts.add(label.trim());
                }
            }
        } catch (Exception e) {
            // unreadable manifest, only app_name is known
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

    /**
     * True for a name safe to protect unasked, several words or a word with an inner capital, digit or
     * symbol. A single ordinary word is not, or it would stay untranslated where a sentence starts with it.
     */
    public static boolean looksLikeBrand(String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() < 3) {
            return false;
        }
        return n.matches(".*\\s.*") || n.substring(1).matches(".*[\\p{Lu}\\p{N}+&@#!].*");
    }

    /** A word an app name may end with that is not part of the product name, such as Free in Forest Pond Free. */
    private static final Pattern EDITION = Pattern.compile(
            "(?i)[\\s\\-\u2013\u2014]+(free|pro|lite|premium|plus|hd|beta|demo|full|trial|ad[- ]?free|no ads)$");

    /**
     * The app name and the product name inside it, without an edition word at its end or a subtitle
     * after a dash or a colon, so Forest Pond Free also protects Forest Pond.
     */
    public static List<String> nameVariants(String appName) {
        List<String> out = new ArrayList<>();
        String n = appName == null ? "" : appName.trim();
        while (!n.isEmpty() && !out.contains(n)) {
            out.add(n);
            String shorter = EDITION.matcher(n).replaceFirst("").trim();
            if (shorter.equals(n)) {
                Matcher subtitle = Pattern.compile("\\s*(?:[:\u2013\u2014]|\\s-)\\s").matcher(n);
                shorter = subtitle.find() && subtitle.start() > 0 ? n.substring(0, subtitle.start()).trim() : n;
            }
            n = shorter;
        }
        return out;
    }

    // ================================================================== protected terms

    /**
     * Format specifiers with date conversions, named and numbered placeholders, escaped newlines, tags
     * and entities, which a translation keeps unchanged. A lone percent sign counts only when a
     * conversion follows it directly and no digit precedes it, so "50% of users" stays a percentage.
     */
    public static final Pattern PLACEHOLDER = Pattern.compile(
            "%%|%\\d+\\$[-#+ 0,(]*\\d*(?:\\.\\d+)?(?:[tT][a-zA-Z]|[a-zA-Z])"
            + "|(?<![0-9%])%[-#+0,(]*\\d*(?:\\.\\d+)?(?:[tT][a-zA-Z]|[bBhHsScCdoxXeEfgGaAn])"
            + "|\\{[A-Za-z_][\\w.]*\\}|\\{\\d+\\}|\\\\n|<[^<>]+>|&[a-z]+;");

    /** A tag written with entities, such as a link, kept as one unit with what is inside it. */
    public static final Pattern ENTITY_TAG = Pattern.compile("&lt;/?[A-Za-z](?:(?!&gt;).)*&gt;");

    /** A character of a URL. An escaped entity such as the closing bracket of a tag ends it, the ampersand of a query does not. */
    private static final String URL_CHAR = "(?:[^\\s<>\"&]|&(?![a-zA-Z]+;|#\\d+;))";
    /** URLs with any scheme (https, content, myapp), www addresses and mailto, tel or geo links. */
    private static final Pattern URL = Pattern.compile("(?:\\b[a-zA-Z][a-zA-Z0-9+.-]*://" + URL_CHAR + "*|\\bwww\\." + URL_CHAR + "+"
            + "|\\b(?:mailto|tel|sms|geo|market):" + URL_CHAR + "+)(?<![.,;:!?)'])");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    /** A domain with a common top level domain, with its path when it has one. */
    private static final Pattern DOMAIN = Pattern.compile("\\b[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.(?:com|org|net|io|dev|app|edu|gov|info"
            + "|co|xyz|ai|fm|tv|me|de|fr|es|uk|ru|nl|br|jp|eu|ca|au|ch|pl|su|ro)\\b(?:/" + URL_CHAR + "*(?<![.,;:!?)']))?");
    /** An absolute path of two or more segments, or of one file with its extension. */
    private static final Pattern PATH_IN_TEXT = Pattern.compile("(?<![\\w/.:])(?:~|\\.{1,2})?(?:/[A-Za-z0-9_.~@%+-]+){2,}/?"
            + "|(?<![\\w/.:])(?:~|\\.{1,2})?/[A-Za-z0-9_~@%+-]+\\.[A-Za-z0-9]{1,5}\\b");
    /** A package or class name such as com.example.app or com.example.app.MainActivity. */
    private static final Pattern PACKAGE = Pattern.compile("\\b[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*){2,}(?:\\.[A-Z][A-Za-z0-9_$]*)?\\b"
            + "|\\b[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+\\.[A-Z][A-Za-z0-9_$]*\\b");
    /** Where a word starts, after a character that is no letter or digit or after an escaped newline or tab. */
    private static final String WORD_START = "(?:(?<![\\p{L}\\p{N}\\\\])|(?<=\\\\[nt]))";
    /** A token with an inner capital, a brand or an identifier such as GeoJSON, iPhone or SettingsFragment. */
    private static final Pattern INNER_CAPITAL = Pattern.compile(WORD_START + "[\\p{L}\\p{N}]*\\p{Ll}\\p{Lu}[\\p{L}\\p{N}]*");
    private static final String NUMBER = "\\d+(?:[.,:/'\\u00a0\\u202f -]\\d+)*";
    private static final String UNIT = "(?:km/h|mph|km|mm|cm|mi|ft|kg|mg|lbs?|oz|ml|\u00b0C|\u00b0F|\u00b0|KB|MB|GB|TB|kB|kbps|Mbps|Gbps"
            + "|Hz|kHz|MHz|GHz|kWh|kW|mAh|ms|px|dp|sp|pt|EUR|USD|GBP|JPY|CNY|RUB|INR|BRL|MXN|COP|ARS|CLP|PEN|CAD|AUD|CHF|SEK|NOK"
            + "|DKK|PLN|CZK|HUF|TRY|KRW|ZAR|NZD|SGD|HKD|BTC|ETH|\u20ac|\u00a3|\u00a5|\u20b9|\u20bd|\\$)";
    private static final String CURRENCY_SYMBOL = "(?:R\\$|US\\$|[$\u20ac\u00a3\u00a5\u20b9\u20bd\u20a9\u20ba\u20bf\u20b4\u20a6\u20b1])";
    /** A number with its unit, currency code or currency symbol, such as 5 km, 120 MB, 6,568.65 EUR or $5. */
    public static final Pattern FIGURE_WITH_UNIT = Pattern.compile(WORD_START + "(?:" + CURRENCY_SYMBOL + "\\s?" + NUMBER
            + "|" + NUMBER + "\\s?" + UNIT + ")(?![\\p{L}\\p{N}])");
    /**
     * A figure with separators (amount, time, date, version, phone). Models rewrite these in the target
     * locale's format, so they are masked. A bare number stays visible so the words around it agree.
     */
    public static final Pattern FIGURE = Pattern.compile("\\d+(?:[.,:/'\\u00a0\\u202f -]\\d+)+");

    private static final String BRANDS_FILE = "protected-brands.txt";
    private static volatile Pattern brands;

    /**
     * The patterns of the text that every translation keeps as written, in the order they claim a span.
     * The user's terms come before the built-in names so a longer listed name wins.
     */
    public static List<Pattern> termPatterns(Pattern userTerms) {
        List<Pattern> out = new ArrayList<>();
        out.add(ENTITY_TAG);
        out.add(URL);
        out.add(EMAIL);
        if (userTerms != null) {
            out.add(userTerms);
        }
        out.add(PACKAGE);
        out.add(DOMAIN);
        out.add(PATH_IN_TEXT);
        Pattern b = brands();
        if (b != null) {
            out.add(b);
        }
        out.add(INNER_CAPITAL);
        out.add(FIGURE_WITH_UNIT);
        out.add(FIGURE);
        return out;
    }

    /** True for the patterns whose matches are figures, which are checked as figures rather than as names. */
    public static boolean isFigurePattern(Pattern p) {
        return p == FIGURE || p == FIGURE_WITH_UNIT;
    }

    /** Whole words exactly as written, the longest first so New York City wins over New York. */
    public static Pattern wholeWords(Collection<String> terms) {
        List<String> longestFirst = new ArrayList<>();
        for (String t : terms) {
            if (t != null && t.trim().length() >= 2 && !longestFirst.contains(t.trim())) {
                longestFirst.add(t.trim());
            }
        }
        if (longestFirst.isEmpty()) {
            return null;
        }
        longestFirst.sort((a, b) -> b.length() - a.length());
        StringBuilder alternatives = new StringBuilder();
        for (String term : longestFirst) {
            alternatives.append(alternatives.length() == 0 ? "" : "|").append(Pattern.quote(term));
        }
        return Pattern.compile(WORD_START + "(?:" + alternatives + ")(?![\\p{L}\\p{N}])");
    }

    /** The built-in names of protected-brands.txt, packaged next to this class. */
    static Pattern brands() {
        Pattern p = brands;
        if (p != null) {
            return p;
        }
        List<String> names = new ArrayList<>();
        try (InputStream in = Translatability.class.getResourceAsStream(BRANDS_FILE)) {
            if (in == null) {
                throw new IllegalStateException(BRANDS_FILE + " is missing from the ITDroid jar");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    names.add(t);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read the packaged " + BRANDS_FILE, e);
        }
        brands = wholeWords(names);
        return brands;
    }

    // ================================================================== unchanged answers and copies

    /** True when two texts have the same letters and digits, ignoring case, spaces and punctuation. */
    public static boolean sameLetters(String a, String b) {
        String x = lettersOf(a);
        return !x.isEmpty() && x.equals(lettersOf(b));
    }

    private static String lettersOf(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        s.codePoints().filter(Character::isLetterOrDigit).map(Character::toLowerCase).forEach(sb::appendCodePoint);
        return sb.toString();
    }

    /** A word, an identifier such as pref_dark_mode counts as one. */
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_'\u2019-]+");

    /**
     * The words of a text a translation is expected to change, those with letters outside placeholders
     * and protected terms. An acronym in capitals (GPX, OK) does not count.
     */
    public static int wordsToTranslate(String text, Pattern userTerms) {
        if (text == null) {
            return 0;
        }
        // the escaped tags go first, as when masking, so their entities are not taken apart
        String bare = ENTITY_TAG.matcher(text).replaceAll(" ");
        bare = PLACEHOLDER.matcher(bare).replaceAll(" ").replaceAll("\\\\[tr'\"@?]", " ");
        for (Pattern p : termPatterns(userTerms)) {
            bare = p.matcher(bare).replaceAll(" ");
        }
        int words = 0;
        Matcher m = WORD.matcher(bare);
        while (m.find()) {
            String w = m.group();
            int letters = 0;
            boolean allUpper = true;
            for (int i = 0; i < w.length(); i++) {
                char c = w.charAt(i);
                if (Character.isLetter(c)) {
                    letters++;
                    allUpper &= Character.isUpperCase(c);
                }
            }
            if (letters > 0 && !(letters >= 2 && allUpper)) {
                words++;
            }
        }
        return words;
    }

    /**
     * True when a value an app ships for a language is only a copy of its default text, the same letters
     * in a text with a word to translate that is neither an identifier, an acronym nor a name. It counts
     * as missing. A single word the model then keeps as it is (Audio, General) was a fair copy.
     */
    public static boolean isCopyOfSource(String name, String defaultValue, String languageValue) {
        return sameLetters(defaultValue, languageValue) && resourceValueSkip(name, defaultValue) == null
                && wordsToTranslate(defaultValue, null) >= 1;
    }
}

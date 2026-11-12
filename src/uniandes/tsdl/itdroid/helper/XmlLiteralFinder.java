package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.jdom2.Attribute;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import org.jdom2.located.Located;
import org.jdom2.located.LocatedJDOMFactory;

/**
 * Text written directly in the app's XML instead of a {@code @string/} reference:
 * {@code android:text="Add to cart"} in a layout, the title of a menu item or of a preference, a
 * label in the manifest. Such text cannot be translated by the resource system, exactly like a
 * literal in the code. The XML is read as a tree (an attribute can sit on any line of its element)
 * and the layouts that libraries ship inside every APK are left out.
 */
public final class XmlLiteralFinder {

    /** One attribute that holds literal text. */
    public static final class Literal {
        /** Path relative to the decoded folder, with forward slashes: res/layout/activity_main.xml. */
        public final String file;
        /**
         * Line where the start tag of the element closes (apktool writes each element on one line,
         * so it is the line of the element); 0 when the parser did not give one.
         */
        public final int line;
        /** Tag of the element: Button, item, Preference... */
        public final String element;
        /** Its {@code android:id} (or {@code android:key}, or {@code android:name}); "" when it has none. */
        public final String id;
        /** Attribute with its prefix: android:text, app:title. */
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

    /** Attributes whose value is text for the user, whatever their namespace (android: or app:). */
    private static final Set<String> TEXT_ATTRIBUTES = Set.of("text", "hint", "contentDescription", "title", "subtitle",
            "summary", "summaryOn", "summaryOff", "label", "description", "dialogTitle", "dialogMessage",
            "positiveButtonText", "negativeButtonText", "textOn", "textOff", "queryHint", "tooltipText", "helperText",
            "placeholderText", "prefixText", "suffixText", "titleCondensed", "accessibilityPaneTitle", "stateDescription");

    /** Resource folders that hold screens, menus, preference screens and navigation graphs. */
    private static final List<String> FOLDERS = List.of("layout", "menu", "xml", "navigation");

    /**
     * File-name prefixes of the XML that AndroidX, Material and other common libraries add to every
     * APK. Their text is not the app's and the app cannot change it.
     */
    private static final String[] LIBRARY_PREFIXES = {"abc_", "mtrl_", "m3_", "material_", "design_", "notification_template_",
            "notification_action", "select_dialog_", "support_simple_", "browser_actions_", "custom_dialog", "ime_",
            "preference_", "expand_button", "image_frame", "test_", "text_view_with_", "md_", "exo_", "leak_", "ucrop_",
            "zxing_", "fingerprint_dialog_", "splits", "standalone_badge", "tooltip", "androidx_", "com_google_android_"};

    private static final String TOOLS_NAMESPACE = "http://schemas.android.com/tools";
    private static final Pattern FORMAT_SPECIFIER = Pattern.compile("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z]");

    private XmlLiteralFinder() {
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
                    if (!isLibraryFile(f.getName())) {
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

    static boolean isLibraryFile(String fileName) {
        for (String prefix : LIBRARY_PREFIXES) {
            if (fileName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static void collect(File file, String relativePath, List<Literal> out) {
        Element root;
        try {
            SAXBuilder builder = new SAXBuilder();
            builder.setJDOMFactory(new LocatedJDOMFactory());   // elements remember their line
            root = builder.build(file).getRootElement();
        } catch (Exception e) {
            return;   // not well-formed (or not XML at all): nothing to report
        }
        walk(root, relativePath, out);
    }

    private static void walk(Element element, String relativePath, List<Literal> out) {
        for (Attribute attribute : element.getAttributes()) {
            if (!TEXT_ATTRIBUTES.contains(attribute.getName()) || TOOLS_NAMESPACE.equals(attribute.getNamespaceURI())) {
                continue;
            }
            String text = literalOf(attribute.getValue());
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

    /** The text of an attribute value when it is literal text with at least one letter, else null. */
    static String literalOf(String value) {
        if (value == null) {
            return null;
        }
        String v = value;
        if (v.startsWith("\\@") || v.startsWith("\\?")) {
            v = v.substring(1);                              // an escaped @ or ?: text, not a reference
        } else if (v.startsWith("@") || v.startsWith("?")) {
            return null;                                     // @string/x, ?attr/x, @{binding}
        }
        // "%s" (a preference summary that shows its own value) or "%1$d" hold no text of their own
        return FORMAT_SPECIFIER.matcher(v).replaceAll("").matches("(?s).*\\p{L}.*") ? v : null;
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
}

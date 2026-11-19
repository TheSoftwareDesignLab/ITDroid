package uniandes.tsdl.itdroid.explorer;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import javax.xml.parsers.DocumentBuilderFactory;

import org.json.simple.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * One uiautomator dump, parsed once, and what the explorer reads from it. The signature tells two
 * screens apart within a language, the structure compares them across languages, and the texts tell
 * apart two pages with the same elements. The keyboard is left out of all three.
 */
final class Screen {

    /** Most elements a passing message adds or changes (box, text, icon, button). */
    static final int MESSAGE_NODES = 5;
    /**
     * Characters of text that must be new, and their share of all the text, for a screen with the
     * same elements to be another page of it, as in a presentation or a setup of several steps.
     */
    static final int PAGE_CHARS = 20;
    static final double PAGE_SHARE = 0.5;
    /**
     * Share of the elements a replayed screen must have in common with the recorded one to be stored
     * as that screen, the same as the default comparison.minSimilarity.
     */
    static final double SAME_SCREEN_SHARE = 0.5;

    /** The dump as uiautomator wrote it, which result.json keeps. */
    final String xml;
    /**
     * Resource id, class, content-desc with digits folded, and whether a node shows text, of each
     * element. The text itself is left out, and the extra fields tell apart Compose screens without ids.
     */
    final String signature;
    /** Depth, resource id and class of each element. */
    final String structure;
    /** The texts it shows with their digits folded, leaving out what a field holds. */
    final List<String> texts;
    private final List<UiNode> nodes;

    private Screen(String xml, List<UiNode> nodes) {
        this.xml = xml;
        this.nodes = nodes;
        StringBuilder sig = new StringBuilder();
        StringBuilder tree = new StringBuilder();
        List<String> shown = new ArrayList<>();
        for (UiNode n : nodes) {
            if (n.isIme()) {
                continue;
            }
            sig.append(n.resourceId).append(':').append(lastSegment(n.clazz));
            if (!n.contentDesc.isEmpty()) {
                sig.append('[').append(n.contentDesc.replaceAll("\\d+", "#")).append(']');
            }
            if (!n.text.isEmpty() && !n.isEditField()) {
                sig.append('t');
            }
            sig.append(';');
            tree.append(n.depth).append(':').append(n.resourceId).append(':').append(lastSegment(n.clazz)).append(';');
            if (!n.isEditField() && !n.text.isBlank()) {
                shown.add(n.text.trim().replaceAll("\\d+", "#"));
            }
        }
        signature = sig.toString();
        structure = tree.toString();
        texts = shown;
    }

    /** Reads a dump. */
    static Screen parse(String xml) throws Exception {
        List<UiNode> out = new ArrayList<>();
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        NodeList list = doc.getElementsByTagName("node");
        for (int i = 0; i < list.getLength(); i++) {
            NamedNodeMap attrs = list.item(i).getAttributes();
            if (attrs == null) {
                continue;
            }
            UiNode n = new UiNode();
            for (Node p = list.item(i).getParentNode(); p != null; p = p.getParentNode()) {
                if ("node".equals(p.getNodeName())) {
                    n.depth++;
                }
            }
            n.resourceId = nz(attr(attrs, "resource-id"));
            n.clazz = nz(attr(attrs, "class"));
            n.text = nz(attr(attrs, "text"));
            n.contentDesc = nz(attr(attrs, "content-desc"));
            n.pkg = nz(attr(attrs, "package"));
            n.clickable = "true".equals(attr(attrs, "clickable"));
            n.center = boundsCenter(attr(attrs, "bounds"));
            out.add(n);
        }
        return new Screen(xml, out);
    }

    /** True when the dump has some element of the app, not only a foreign dialog drawn over it. */
    boolean showsPackage(String pkg) {
        return xml.contains("package=\"" + pkg + "\"");
    }

    /** The elements that can be tapped, in an order that does not depend on the language. */
    List<Selector> clickables() {
        List<Selector> out = new ArrayList<>();
        for (UiNode n : numbered(UiNode::tappable)) {
            out.add(n.selector);
        }
        return out;
    }

    /** Tap center of the element a selector of {@link #clickables} finds, null when there is none. */
    int[] center(Selector sel) {
        return centerOf(numbered(UiNode::tappable), sel);
    }

    /** The text fields, each with a selector numbered among the text fields. */
    List<UiNode> editFields() {
        return numbered(n -> n.isEditField() && n.center != null);
    }

    /** Tap center of the text field a selector of {@link #editFields} finds, null when there is none. */
    int[] editCenter(Selector sel) {
        return centerOf(editFields(), sel);
    }

    /**
     * The elements of one kind, out of the keyboard, each given its selector, numbered per resource id
     * and class in the order of the dump. Tappable elements and text fields are kinds apart.
     */
    private List<UiNode> numbered(Predicate<UiNode> kind) {
        List<UiNode> out = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (UiNode n : nodes) {
            if (kind.test(n) && !n.isIme()) {
                int occurrence = seen.merge(n.resourceId + "|" + n.clazz, 1, Integer::sum) - 1;
                n.selector = new Selector(n.resourceId, n.clazz, occurrence);
                out.add(n);
            }
        }
        return out;
    }

    private static int[] centerOf(List<UiNode> nodes, Selector sel) {
        for (UiNode n : nodes) {
            if (n.selector.equals(sel)) {
                return n.center;
            }
        }
        return null;
    }

    // ================================================================= comparisons of signatures

    /**
     * True when signature {@code candidate} differs from {@code base} by only a few added or changed
     * elements, as a snackbar or a short status line does.
     */
    static boolean differsLittle(String base, String candidate) {
        String[] before = base.split(";");
        String[] after = candidate.split(";");
        if (Math.abs(after.length - before.length) > MESSAGE_NODES) {
            return false;
        }
        int shared = commonInOrder(before, after);
        int added = after.length - shared;
        int gone = before.length - shared;
        return added + gone > 0 && added <= MESSAGE_NODES && gone <= MESSAGE_NODES;
    }

    /**
     * True when two signatures have the same elements, in the same order or not. A tap that moves a
     * selection, a check or a highlight from one element to another only changes their order.
     */
    static boolean sameElements(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        String[] x = a.split(";"), y = b.split(";");
        Arrays.sort(x);
        Arrays.sort(y);
        return Arrays.equals(x, y);
    }

    /** True when two signatures show the same screen, whatever is selected on it. */
    static boolean sameState(String a, String b) {
        return a != null && b != null && (a.equals(b) || sameElements(a, b));
    }

    /**
     * True when most of the text of a screen is new while its elements stayed, as on the pages of a
     * presentation or of a setup of several steps. A button that changes its label or a figure that
     * counts is not that.
     */
    static boolean otherTexts(List<String> before, List<String> after) {
        Map<String, Integer> left = new HashMap<>();
        int beforeChars = 0;
        for (String t : before) {
            left.merge(t, 1, Integer::sum);
            beforeChars += t.length();
        }
        int afterChars = 0, fresh = 0;
        for (String t : after) {
            afterChars += t.length();
            if (left.getOrDefault(t, 0) > 0) {
                left.merge(t, -1, Integer::sum);
            } else {
                fresh += t.length();
            }
        }
        return fresh >= PAGE_CHARS && fresh >= PAGE_SHARE * Math.max(beforeChars, afterChars);
    }

    /**
     * Share of the elements of the larger of two structures that both hold in the same order, from 0
     * to 1. A translated screen keeps its tree, though a longer text may push some rows out of view.
     */
    static double structureSimilarity(String a, String b) {
        String[] x = a == null || a.isEmpty() ? new String[0] : a.split(";");
        String[] y = b == null || b.isEmpty() ? new String[0] : b.split(";");
        int larger = Math.max(x.length, y.length);
        return larger == 0 ? 1.0 : commonInOrder(x, y) / (double) larger;
    }

    /** True when a capture shows the recorded screen. An unknown recorded structure says nothing against it. */
    static boolean sameScreen(String recorded, String captured) {
        return recorded == null || recorded.isEmpty() || structureSimilarity(recorded, captured) >= SAME_SCREEN_SHARE;
    }

    /** Length of the longest common subsequence of two element lists. */
    private static int commonInOrder(String[] before, String[] after) {
        int[] previous = new int[after.length + 1];
        for (String element : before) {
            int[] current = new int[after.length + 1];
            for (int j = 0; j < after.length; j++) {
                current[j + 1] = element.equals(after[j]) ? previous[j] + 1 : Math.max(previous[j + 1], current[j]);
            }
            previous = current;
        }
        return previous[after.length];
    }

    // ================================================================= small helpers

    private static String attr(NamedNodeMap attrs, String name) {
        Node a = attrs.getNamedItem(name);
        return a == null ? null : a.getNodeValue();
    }

    /** The center of bounds written as [x1,y1][x2,y2], null when they cannot be read. */
    static int[] boundsCenter(String bounds) {
        if (bounds == null) {
            return null;
        }
        try {
            String[] p = bounds.replace("][", ",").replace("[", "").replace("]", "").split(",");
            int x1 = Integer.parseInt(p[0].trim());
            int y1 = Integer.parseInt(p[1].trim());
            int x2 = Integer.parseInt(p[2].trim());
            int y2 = Integer.parseInt(p[3].trim());
            return new int[]{(x1 + x2) / 2, (y1 + y2) / 2};
        } catch (Exception e) {
            return null;
        }
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }

    static String lastSegment(String clazz) {
        int dot = clazz.lastIndexOf('.');
        return dot >= 0 ? clazz.substring(dot + 1) : clazz;
    }

    /** One node of a dump. */
    static final class UiNode {
        String resourceId = "";
        String clazz = "";
        String text = "";
        String contentDesc = "";
        String pkg = "";
        boolean clickable;
        int[] center;
        /** Nodes above it in the tree. */
        int depth;
        /** How it is found again, given when the elements of its kind are numbered. */
        Selector selector;

        boolean isEditField() {
            return clazz.contains("EditText") || clazz.contains("AutoComplete");
        }

        /** Can be tapped, leaving out text fields, as tapping one only opens the keyboard. */
        boolean tappable() {
            return (clickable || clazz.contains("Button")) && center != null && !isEditField();
        }

        boolean isIme() {
            return pkg.contains("inputmethod");
        }
    }

    /** Finds an element again in any language by resource id, class and occurrence. */
    static final class Selector {
        final String resourceId;
        final String clazz;
        final int occurrence;

        Selector(String resourceId, String clazz, int occurrence) {
            this.resourceId = nz(resourceId);
            this.clazz = nz(clazz);
            this.occurrence = occurrence;
        }

        @SuppressWarnings("unchecked")
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("resourceId", resourceId);
            o.put("clazz", clazz);
            o.put("occurrence", (long) occurrence);
            return o;
        }

        static Selector fromJson(JSONObject o) {
            return new Selector(AdbExplorer.str(o, "resourceId"), AdbExplorer.str(o, "clazz"), AdbExplorer.toInt(o.get("occurrence")));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Selector && ((Selector) o).resourceId.equals(resourceId) && ((Selector) o).clazz.equals(clazz)
                    && ((Selector) o).occurrence == occurrence;
        }

        @Override
        public int hashCode() {
            return Objects.hash(resourceId, clazz, occurrence);
        }

        @Override
        public String toString() {
            return (resourceId.isEmpty() ? lastSegment(clazz) : resourceId) + "#" + occurrence;
        }
    }
}

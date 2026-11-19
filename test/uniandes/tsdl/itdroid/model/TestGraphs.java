package uniandes.tsdl.itdroid.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/** Builds tiny hand-made screens and explorations, so a comparison rule can be tested in isolation. */
final class TestGraphs {

    private TestGraphs() {
    }

    /** A node as uiautomator dumps it, with nested node strings as children. */
    static String node(int index, String clazz, String resourceId, int x1, int y1, int x2, int y2, String... children) {
        StringBuilder sb = new StringBuilder();
        sb.append("<node index=\"").append(index).append("\" text=\"\" resource-id=\"").append(resourceId)
                .append("\" class=\"").append(clazz)
                .append("\" package=\"org.example\" content-desc=\"\" clickable=\"false\" enabled=\"true\" bounds=\"[")
                .append(x1).append(',').append(y1).append("][").append(x2).append(',').append(y2).append("]\">");
        for (String c : children) {
            sb.append(c);
        }
        return sb.append("</node>").toString();
    }

    /** A full-screen root (1000 x 2000) holding the given nodes. Its own node index is 0. */
    static String screen(String... children) {
        return "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">"
                + node(0, "android.widget.FrameLayout", "", 0, 0, 1000, 2000, children) + "</hierarchy>";
    }

    /** A window that does not fill the screen, such as a menu or the window of an app shown as a dialog. */
    static String window(int x1, int y1, int x2, int y2, String... children) {
        return "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">"
                + node(0, "android.widget.FrameLayout", "", x1, y1, x2, y2, children) + "</hierarchy>";
    }

    static String button(int index, int x1, int y1, int x2, int y2) {
        return node(index, "android.widget.Button", "", x1, y1, x2, y2);
    }

    /** A button that shows a text, which a person reads when another element steps on it. */
    static String button(int index, String text, int x1, int y1, int x2, int y2) {
        return button(index, x1, y1, x2, y2).replaceFirst("text=\"\"", "text=\"" + text + "\"");
    }

    static String textView(int index, int x1, int y1, int x2, int y2) {
        return node(index, "android.widget.TextView", "", x1, y1, x2, y2);
    }

    /** A TextView that shows a text. */
    static String label(int index, String text, int x1, int y1, int x2, int y2) {
        return node(index, "android.widget.TextView", "", x1, y1, x2, y2).replaceFirst("text=\"\"", "text=\"" + text + "\"");
    }

    /** A scroll view whose content does not fit, so what passes its bottom edge is out of view. */
    static String scroller(int index, int x1, int y1, int x2, int y2, String... children) {
        return node(index, "android.widget.ScrollView", "", x1, y1, x2, y2, children).replaceFirst("clickable=\"false\"", "clickable=\"false\" scrollable=\"true\"");
    }

    /** A layout that holds other nodes. */
    static String group(int index, int x1, int y1, int x2, int y2, String... children) {
        return node(index, "android.widget.LinearLayout", "", x1, y1, x2, y2, children);
    }

    /** A plain view with no text, as Compose dumps its boxes. */
    static String view(int index, int x1, int y1, int x2, int y2, String... children) {
        return node(index, "android.view.View", "", x1, y1, x2, y2, children);
    }

    /** A screenshot of the given size filled with one colour, to draw on. */
    static java.awt.image.BufferedImage canvas(int width, int height, java.awt.Color colour) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        fill(img, colour, 0, 0, width, height);
        return img;
    }

    static void fill(java.awt.image.BufferedImage img, java.awt.Color colour, int x1, int y1, int x2, int y2) {
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(colour);
        g.fillRect(x1, y1, x2 - x1, y2 - y1);
        g.dispose();
    }

    /**
     * A line of text as one stroke 5 pixels wide per letter, a letter every {@code letter} pixels, and
     * nothing for a space. It stops at {@code clip}, as a box cuts what passes its side.
     */
    static void text(java.awt.image.BufferedImage img, java.awt.Color colour, String text, int x, int y1, int y2, int letter, int clip) {
        for (int i = 0; i < text.length(); i++) {
            int from = x + i * letter;
            if (text.charAt(i) != ' ' && from < clip) {
                fill(img, colour, from, y1, Math.min(clip, from + 5), y2);
            }
        }
    }

    /** As {@link #writeResult}, one screen with its screenshot in screenshots/state_1.png. */
    @SuppressWarnings("unchecked")
    static Path writeResult(Path folder, boolean replay, String activity, String screen, java.awt.image.BufferedImage shot) throws IOException {
        writeResult(folder, replay, activity, screen);
        Files.createDirectories(folder.resolve("screenshots"));
        javax.imageio.ImageIO.write(shot, "png", folder.resolve("screenshots").resolve("state_1.png").toFile());
        Path result = folder.resolve("result.json");
        String json = Files.readString(result, StandardCharsets.UTF_8).replace("\"screenShot\":\"\"", "\"screenShot\":\"screenshots\\/state_1.png\"");
        Files.writeString(result, json, StandardCharsets.UTF_8);
        return folder;
    }

    static State state(String screen) throws Exception {
        return new State(1, ".Main", screen, "");
    }

    /**
     * Writes a {@code result.json} with one state per screen (ids 1..n) into the folder. With
     * {@code replay}, every state carries the source id it reproduces, as a replayed exploration does.
     */
    @SuppressWarnings("unchecked")
    static Path writeResult(Path folder, boolean replay, String activity, String... screens) throws IOException {
        Files.createDirectories(folder);
        JSONObject states = new JSONObject();
        for (int i = 0; i < screens.length; i++) {
            long id = i + 1;
            JSONObject s = new JSONObject();
            s.put("id", id);
            if (replay) {
                s.put("sourceStateId", id);
            }
            s.put("activityName", activity);
            s.put("rawXML", screens[i]);
            s.put("screenShot", "");
            states.put(String.valueOf(id), s);
        }
        JSONObject root = new JSONObject();
        root.put("amountStates", (long) screens.length);
        root.put("amountTransitions", 0L);
        root.put("states", states);
        root.put("transitions", new JSONObject());
        Files.writeString(folder.resolve("result.json"), root.toJSONString(), StandardCharsets.UTF_8);
        return folder;
    }

    /** Compares an English exploration with a replayed Spanish one and returns the language report. */
    static JSONObject compare(Path source, Path language, Path out) throws IOException {
        return compare(source, language, out, "es", "Spanish");
    }

    /** The same for the given language code, "ar" gives a right-to-left comparison. */
    static JSONObject compare(Path source, Path language, Path out, String code, String name) throws IOException {
        Files.createDirectories(out);
        LayoutGraph sourceGraph = new LayoutGraph(source.toString());
        LayoutGraph languageGraph = new LayoutGraph(language.toString());
        JSONObject report = new JSONObject();
        new LayoutGraphComparision("en", sourceGraph, name, code, languageGraph, report);
        return report;
    }

    static long count(JSONObject report, String key) {
        Object v = report.get(key);
        return v instanceof Number ? ((Number) v).longValue() : -1;
    }

    /** A number of the breakdown of the language ({@code ipfSummary}), -1 when it is not there. */
    static long summary(JSONObject report, String key) {
        Object summary = report.get("ipfSummary");
        return summary instanceof JSONObject ? count((JSONObject) summary, key) : -1;
    }

    /** How many findings of the language have the given type ({@code ipfSummary.byType}). */
    static long ofType(JSONObject report, String type) {
        Object summary = report.get("ipfSummary");
        Object byType = summary instanceof JSONObject ? ((JSONObject) summary).get("byType") : null;
        return byType instanceof JSONObject ? count((JSONObject) byType, type) : -1;
    }

    /** The findings as they are written in the report. */
    static List<JSONObject> findings(JSONObject report) {
        List<JSONObject> out = new ArrayList<>();
        Object ipfs = report.get("ipfs");
        if (ipfs instanceof JSONArray) {
            for (Object o : (JSONArray) ipfs) {
                out.add((JSONObject) o);
            }
        }
        return out;
    }

    /** The finding of the element at that position of the source screen, or null. */
    static JSONObject finding(JSONObject report, long node) {
        for (JSONObject f : findings(report)) {
            if (((Number) f.get("nodeID")).longValue() == node) {
                return f;
            }
        }
        return null;
    }

    /** The types of the finding of that element of the source screen, in the order of the report. */
    static List<String> types(JSONObject report, long node) {
        List<String> out = new ArrayList<>();
        JSONObject f = finding(report, node);
        if (f != null) {
            for (Object t : (JSONArray) f.get("types")) {
                out.add(String.valueOf(t));
            }
        }
        return out;
    }

    /** Positions in the source screen of the elements reported, in order (-1 for one only the translated screen has). */
    static Set<Long> ipfNodes(JSONObject report) {
        Set<Long> nodes = new TreeSet<>();
        for (JSONObject f : findings(report)) {
            nodes.add(((Number) f.get("nodeID")).longValue());
        }
        return nodes;
    }

    /** The relations of {@code field} ("added", "removed" or "missing") reported for {@code node} against {@code other}. */
    static Set<String> relations(JSONObject report, long node, long other, String field) {
        Set<String> out = new TreeSet<>();
        JSONObject ipf = finding(report, node);
        if (ipf == null) {
            return out;
        }
        for (Object r : (JSONArray) ipf.get("relations")) {
            JSONObject rel = (JSONObject) r;
            if (((Number) rel.get("relNode")).longValue() == other && rel.get(field) != null) {
                String raw = String.valueOf(rel.get(field));
                Arrays.stream(raw.split(";")).filter(s -> !s.isEmpty()).forEach(out::add);
            }
        }
        return out;
    }
}

package uniandes.tsdl.itdroid.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/** Builds tiny hand-made screens and explorations, so a comparison rule can be tested in isolation. */
final class TestGraphs {

    private TestGraphs() {
    }

    /** A node as uiautomator dumps it; {@code children} are nested {@code node(...)} strings. */
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

    static String button(int index, int x1, int y1, int x2, int y2) {
        return node(index, "android.widget.Button", "", x1, y1, x2, y2);
    }

    static String textView(int index, int x1, int y1, int x2, int y2) {
        return node(index, "android.widget.TextView", "", x1, y1, x2, y2);
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

    /** Compares a source exploration with a replayed one, as the pipeline does; returns the language report. */
    static JSONObject compare(Path source, Path language, Path out) throws IOException {
        Files.createDirectories(out);
        LayoutGraph sourceGraph = new LayoutGraph("en", source.toString());
        LayoutGraph languageGraph = new LayoutGraph("es", language.toString());
        JSONObject report = new JSONObject();
        new LayoutGraphComparision("en", sourceGraph, "Spanish", "es", languageGraph, language.toString(), out.toString(), report);
        return report;
    }

    static long count(JSONObject report, String key) {
        Object v = report.get(key);
        return v instanceof Number ? ((Number) v).longValue() : -1;
    }

    /** Node indexes reported as IPF, in order. */
    static Set<Long> ipfNodes(JSONObject report) {
        Set<Long> nodes = new TreeSet<>();
        Object ipfs = report.get("ipfs");
        if (ipfs instanceof JSONArray) {
            for (Object o : (JSONArray) ipfs) {
                nodes.add(((Number) ((JSONObject) o).get("nodeID")).longValue());
            }
        }
        return nodes;
    }

    /** The relations of {@code field} ("added" or "removed") reported for {@code node} against {@code other}. */
    static Set<String> relations(JSONObject report, long node, long other, String field) {
        Set<String> out = new TreeSet<>();
        Object ipfs = report.get("ipfs");
        if (!(ipfs instanceof JSONArray)) {
            return out;
        }
        for (Object o : (JSONArray) ipfs) {
            JSONObject ipf = (JSONObject) o;
            if (((Number) ipf.get("nodeID")).longValue() != node) {
                continue;
            }
            for (Object r : (JSONArray) ipf.get("relations")) {
                JSONObject rel = (JSONObject) r;
                if (((Number) rel.get("relNode")).longValue() == other) {
                    String raw = String.valueOf(rel.get(field));
                    Arrays.stream(raw.split(";")).filter(s -> !s.isEmpty()).forEach(out::add);
                }
            }
        }
        return out;
    }
}

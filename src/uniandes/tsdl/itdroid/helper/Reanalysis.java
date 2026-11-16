package uniandes.tsdl.itdroid.helper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import uniandes.tsdl.itdroid.model.LayoutGraph;
import uniandes.tsdl.itdroid.model.LayoutGraphComparision;
import uniandes.tsdl.itdroid.model.ScreenTexts;

/**
 * The --reanalyze mode, which recomputes the layout comparison of stored evaluations without a device
 * or an LLM. It only reads the input and writes a reanalysis.json and an ipfs.csv per evaluation plus
 * a summary.csv to the output folder.
 */
public class Reanalysis {

    /** One target language of one evaluation, as reported by the run and as recomputed now. */
    public static class Row {
        public String evaluation;
        public String language;
        public Long reportedIpfs;
        public Long recomputedIpfs;
        public Long reportedPaired;
        public Long recomputedPaired;
        /** Why there is no recomputed value, else empty. */
        public String note = "";
        /** The ipfSummary of the recomputed comparison, or null. */
        public JSONObject summary;
        /** How much source text the translated screens still show, or null. */
        public JSONObject screenText;

        public boolean same() {
            return reportedIpfs != null && reportedIpfs.equals(recomputedIpfs);
        }
    }

    public static void run(String[] args) throws Exception {
        String input = null;
        String out = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--out") && i + 1 < args.length) {
                out = args[++i];
            } else if (args[i].equals("--min-similarity") && i + 1 < args.length) {
                // same threshold as comparison.minSimilarity, to try values on stored runs
                String raw = args[++i];
                try {
                    LayoutGraphComparision.setMinSimilarity(Double.parseDouble(raw));
                } catch (IllegalArgumentException e) {
                    throw new ITDroidException("--min-similarity must be a number between 0 and 1, got '" + raw + "'.");
                }
            } else {
                input = args[i];
            }
        }
        if (input == null) {
            throw new ITDroidException("Usage: --reanalyze <evaluation or folder of evaluations> [--out <folder>] [--min-similarity <0..1>]");
        }
        Path in = Paths.get(input).toAbsolutePath().normalize();
        if (!Files.isDirectory(in)) {
            throw new ITDroidException("Folder not found: " + in);
        }
        Path outDir = (out != null ? Paths.get(out) : Paths.get("reanalysis", in.getFileName().toString()))
                .toAbsolutePath().normalize();
        long t0 = System.currentTimeMillis();
        List<Row> rows = reanalyze(in, outDir);
        printSummary(rows, outDir, (System.currentTimeMillis() - t0) / 1000.0);
    }

    /** Recomputes every evaluation in {@code in} into {@code outDir}, which must be outside it. */
    public static List<Row> reanalyze(Path in, Path outDir) throws Exception {
        in = in.toAbsolutePath().normalize();
        outDir = outDir.toAbsolutePath().normalize();
        if (outDir.startsWith(in)) {
            throw new ITDroidException("The output folder must be outside the folder being re-analysed ("
                    + outDir + " is inside " + in + "): the stored evaluations are only read.");
        }
        List<Path> evaluations = evaluations(in);
        if (evaluations.isEmpty()) {
            throw new ITDroidException("No evaluation found in " + in + " (expected a report.json there or in its sub-folders).");
        }
        Files.createDirectories(outDir);
        List<Row> rows = new ArrayList<>();
        for (Path evaluation : evaluations) {
            rows.addAll(reanalyzeOne(evaluation, outDir.resolve(evaluation.getFileName().toString())));
        }
        writeSummaryCsv(rows, outDir.resolve("summary.csv"));
        return rows;
    }

    /** The folder itself when it holds a report.json, else its sub-folders that do, by name. */
    static List<Path> evaluations(Path in) throws IOException {
        List<Path> found = new ArrayList<>();
        if (Files.isRegularFile(in.resolve("report.json"))) {
            found.add(in);
            return found;
        }
        try (Stream<Path> s = Files.list(in)) {
            s.filter(Files::isDirectory).filter(d -> Files.isRegularFile(d.resolve("report.json"))).forEach(found::add);
        }
        found.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        return found;
    }

    /** A built APK the evaluation kept, which carries the code of the original, or null. */
    static Path storedApk(Path evaluation) {
        try (Stream<Path> files = Files.list(evaluation)) {
            return files.filter(p -> p.getFileName().toString().endsWith("-aligned-debugSigned.apk")).sorted().findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** The texts of the strings_inventory.json of the evaluation, or null without one. */
    static List<String> apkTexts(Path evaluation) {
        Path file = evaluation.resolve("strings_inventory.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JSONObject inventory = (JSONObject) new JSONParser().parse(Files.readString(file, StandardCharsets.UTF_8));
            List<String> texts = new ArrayList<>();
            if (inventory.get("entries") instanceof org.json.simple.JSONArray) {
                for (Object o : (org.json.simple.JSONArray) inventory.get("entries")) {
                    if (o instanceof JSONObject && ((JSONObject) o).get("text") instanceof String) {
                        texts.add((String) ((JSONObject) o).get("text"));
                    }
                }
            }
            return texts;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Row> reanalyzeOne(Path evaluation, Path evalOut) throws Exception {
        List<Row> rows = new ArrayList<>();
        String name = evaluation.getFileName().toString();
        JSONObject report;
        try {
            report = (JSONObject) new JSONParser().parse(Files.readString(evaluation.resolve("report.json"), StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.out.println("reanalyze :: " + name + ": report.json could not be read (" + describe(e) + "), skipped");
            return rows;
        }
        Object langsObj = report.get("langsReport");
        String source = report.get("dfltLang") instanceof String ? (String) report.get("dfltLang") : null;
        if (!(langsObj instanceof JSONObject) || source == null) {
            System.out.println("reanalyze :: " + name + ": the run stopped before exploring (no source language in the report), skipped");
            return rows;
        }
        JSONObject langs = (JSONObject) langsObj;
        List<String> texts = apkTexts(evaluation);
        String files = "";
        Path apk = storedApk(evaluation);
        if (texts != null && apk != null) {
            try {
                ApkContent content = ApkContent.read(apk.toString());
                texts.addAll(content.codeStrings);
                files = content.files;
            } catch (IOException e) {
                // only the inventory is known, so texts it lacks count as not in the APK
            }
        }
        ScreenTexts.setApkTexts(texts, files);
        Files.createDirectories(evalOut);
        Path ipfsCsv = evalOut.resolve("ipfs.csv");
        Files.writeString(ipfsCsv, "language;state;nodePos;ipfScore;langNodePos;kind" + System.lineSeparator(), StandardCharsets.UTF_8);

        LayoutGraph sourceGraph = null;
        String sourceNote = "";
        Path sourceFolder = resultFolder(evaluation, source, true);
        if (sourceFolder == null) {
            sourceNote = "no result.json for the source language " + source;
        } else {
            try {
                sourceGraph = new LayoutGraph(source, sourceFolder.toString(), false);
            } catch (Exception e) {
                sourceNote = "source graph unreadable: " + describe(e);
            }
        }

        JSONObject outLangs = new JSONObject();
        for (String code : new TreeMap<String, Object>(langs).keySet()) {
            if (!(langs.get(code) instanceof JSONObject)) {
                continue;
            }
            JSONObject reported = (JSONObject) langs.get(code);
            if (Boolean.TRUE.equals(reported.get("dflt")) || code.equals(source)) {
                continue;
            }
            Row row = new Row();
            row.evaluation = name;
            row.language = code;
            row.reportedIpfs = asLong(reported.get("amIPFs"));
            row.reportedPaired = asLong(reported.get("pairedStates"));
            JSONObject recomputed = new JSONObject();
            String display = reported.get("lang") != null ? String.valueOf(reported.get("lang")) : code;
            recomputed.put("lang", display);
            if (sourceGraph == null) {
                row.note = sourceNote;
            } else {
                Path langFolder = resultFolder(evaluation, code, false);
                if (langFolder == null) {
                    row.note = "no result.json for " + code;
                } else {
                    try {
                        LayoutGraph langGraph = new LayoutGraph(code, langFolder.toString(), false);
                        recomputed.put("amStates", (long) langGraph.getStates().size());
                        recomputed.put("amTrans", (long) langGraph.getTransitions().size());
                        new LayoutGraphComparision(source, sourceGraph, display, code, langGraph, langFolder.toString(),
                                evalOut.toString(), recomputed);
                        row.recomputedIpfs = asLong(recomputed.get("amIPFs"));
                        row.recomputedPaired = asLong(recomputed.get("pairedStates"));
                        row.summary = recomputed.get("ipfSummary") instanceof JSONObject ? (JSONObject) recomputed.get("ipfSummary") : null;
                        row.screenText = recomputed.get("screenText") instanceof JSONObject ? (JSONObject) recomputed.get("screenText") : null;
                    } catch (Exception e) {
                        row.note = "comparison failed: " + describe(e);
                    }
                }
            }
            JSONObject langOut = new JSONObject();
            JSONObject rep = new JSONObject();
            for (String key : new String[] {"amIPFs", "pairedStates", "missingDfltStates", "missingLangStates", "amStates", "amTrans", "pairing", "error"}) {
                if (reported.get(key) != null) {
                    rep.put(key, reported.get(key));
                }
            }
            langOut.put("reported", rep);
            langOut.put("recomputed", recomputed);
            if (!row.note.isEmpty()) {
                langOut.put("note", row.note);
            }
            outLangs.put(code, langOut);
            rows.add(row);
        }

        JSONObject root = new JSONObject();
        root.put("evaluation", name);
        root.put("appName", report.get("appName"));
        root.put("dfltLang", source);
        root.put("generatedAt", Instant.now().toString());
        root.put("itdroidVersion", RunMetrics.VERSION);
        root.put("langs", outLangs);
        Files.writeString(evalOut.resolve("reanalysis.json"), StringInventory.pretty(root.toJSONString()), StandardCharsets.UTF_8);
        return rows;
    }

    /**
     * The folder with the result.json of a language, under trnsResults when the app shipped it and
     * under noTrnsResults when ITDroid translated it. When both exist the newer one wins.
     */
    static Path resultFolder(Path evaluation, String lang, boolean isSource) {
        Path shipped = evaluation.resolve("trnsResults").resolve(lang);
        Path translated = evaluation.resolve("noTrnsResults").resolve(lang);
        boolean hasShipped = Files.isRegularFile(shipped.resolve("result.json"));
        boolean hasTranslated = Files.isRegularFile(translated.resolve("result.json"));
        if (hasShipped && hasTranslated && !isSource) {
            try {
                return Files.getLastModifiedTime(translated.resolve("result.json"))
                        .compareTo(Files.getLastModifiedTime(shipped.resolve("result.json"))) > 0 ? translated : shipped;
            } catch (IOException e) {
                return shipped;
            }
        }
        if (hasShipped) {
            return shipped;
        }
        return hasTranslated ? translated : null;
    }

    /** The kinds of change, in the column order of summary.csv. */
    private static final String[] TYPES = {"positionLost", "positionAdded", "alignmentLost", "alignmentAdded", "overlapAdded",
            "overlapRemoved", "missingMirroring", "componentLost", "componentAdded", "textOverGraphic", "outOfFrame"};

    private static void writeSummaryCsv(List<Row> rows, Path file) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("evaluation;language;reportedIPFs;recomputedIPFs;same;reportedPaired;recomputedPaired;note"
                    + ";nodesCompared;nodesChanged;nodesAffected;componentsLost;componentsAdded;textOverGraphicOnly;" + String.join(";", TYPES)
                    + ";siblingPairsChecked;siblingPairsNotMirrored;sourceTexts;unchangedTexts;sameTextAsSource;unchangedNotInApk");
            w.newLine();
            for (Row r : rows) {
                StringBuilder line = new StringBuilder();
                line.append(r.evaluation).append(';').append(r.language).append(';').append(cell(r.reportedIpfs)).append(';')
                        .append(cell(r.recomputedIpfs)).append(';').append(r.recomputedIpfs == null ? "" : String.valueOf(r.same()))
                        .append(';').append(cell(r.reportedPaired)).append(';').append(cell(r.recomputedPaired)).append(';')
                        .append(r.note.replace(';', ',').replace('\n', ' '));
                // empty for a row that could not be recomputed
                for (String key : new String[] {"nodesCompared", "nodesChanged", "nodesAffected", "componentsLost", "componentsAdded", "textOverGraphic"}) {
                    line.append(';').append(r.summary == null ? "" : String.valueOf(r.summary.get(key)));
                }
                Object byType = r.summary == null ? null : r.summary.get("byType");
                for (String type : TYPES) {
                    line.append(';').append(byType instanceof JSONObject ? String.valueOf(((JSONObject) byType).get(type)) : "");
                }
                for (String key : new String[] {"siblingPairsChecked", "siblingPairsNotMirrored"}) {
                    Object v = r.summary == null ? null : r.summary.get(key);
                    line.append(';').append(v == null ? "" : String.valueOf(v));
                }
                // whether the language reached the screen
                for (String key : new String[] {"sourceTexts", "unchanged", "sameAsSource", "unchangedNotInApk"}) {
                    Object v = r.screenText == null ? null : r.screenText.get(key);
                    line.append(';').append(v == null ? "" : String.valueOf(v));
                }
                w.write(line.toString());
                w.newLine();
            }
        }
    }

    private static void printSummary(List<Row> rows, Path outDir, double seconds) {
        Map<String, long[]> perLang = new TreeMap<>();   // reported, recomputed and pairs per language
        int compared = 0;
        int same = 0;
        List<Row> different = new ArrayList<>();
        List<Row> skipped = new ArrayList<>();
        for (Row r : rows) {
            if (r.recomputedIpfs == null) {
                skipped.add(r);
                continue;
            }
            compared++;
            long[] t = perLang.computeIfAbsent(r.language, k -> new long[3]);
            t[0] += r.reportedIpfs == null ? 0 : r.reportedIpfs;
            t[1] += r.recomputedIpfs;
            t[2]++;
            if (r.same()) {
                same++;
            } else {
                different.add(r);
            }
        }
        System.out.println();
        System.out.println("Re-analysis: " + compared + " evaluation-language pairs recomputed in "
                + String.format(Locale.ROOT, "%.1f", seconds) + " s, " + same + " equal to the stored report, "
                + different.size() + " different, " + skipped.size() + " without data.");
        for (Map.Entry<String, long[]> e : perLang.entrySet()) {
            System.out.println("  " + e.getKey() + ": IPFs reported " + e.getValue()[0] + ", recomputed " + e.getValue()[1]
                    + " (" + e.getValue()[2] + " evaluations)");
        }
        int shown = 0;
        for (Row r : different) {
            if (shown++ == 30) {
                System.out.println("  ... " + (different.size() - 30) + " more, see summary.csv");
                break;
            }
            System.out.println("  different: " + r.evaluation + " [" + r.language + "] reported " + cell(r.reportedIpfs)
                    + ", recomputed " + r.recomputedIpfs);
        }
        for (Row r : skipped) {
            System.out.println("  no data: " + r.evaluation + " [" + r.language + "] " + r.note);
        }
        System.out.println("Results written to " + outDir);
    }

    private static String cell(Long v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static Long asLong(Object o) {
        return o instanceof Number ? Long.valueOf(((Number) o).longValue()) : null;
    }

    private static String describe(Exception e) {
        Throwable t = e.getCause() != null && e.getMessage() != null && e.getMessage().startsWith("java.") ? e.getCause() : e;
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}

package uniandes.tsdl.itdroid.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.json.simple.JSONObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import uniandes.tsdl.itdroid.Fixtures;
import uniandes.tsdl.itdroid.helper.Reanalysis;

/**
 * Fixes the comparison of four real stored explorations. A change that moves these numbers must be
 * intended and updated here. Each stored report keeps the older figure for reference.
 */
class StoredEvaluationsComparisonTest {

    @TempDir
    static Path out;

    private static final Map<String, Reanalysis.Row> ROWS = new HashMap<>();

    @BeforeAll
    static void reanalyseTheStoredEvaluations() throws Exception {
        for (Reanalysis.Row row : Reanalysis.reanalyze(Fixtures.evaluations(), out)) {
            ROWS.put(row.evaluation + " " + row.language, row);
        }
    }

    @ParameterizedTest(name = "{0} [{1}]: {3} paired screens, {4} IPFs (the stored report said {2})")
    @CsvSource({
            // a small Compose app, few nodes change
            "com.elasticrock.candle_14,  es,   7, 5,   7",
            // in Arabic it mirrors well, three elements only that screen has are left
            "com.elasticrock.candle_14,  ar,  41, 5,   3",
            // many nodes per screen, so the count follows the share of the screen affected
            "cc.polysfaer.stochapop_8,   es, 215, 5, 214",
            "cc.polysfaer.stochapop_8,   ru, 283, 5, 274",
            // the old figure counted mostly the mirroring itself
            "cc.polysfaer.stochapop_8,   ar, 376, 5,  15",
            // both screens have four nodes fewer than the source
            "com.chmouel.noisetimer_2,   es, 104, 2, 106",
            // an app that does not mirror, so every finding is a missing mirroring
            "org.birthdayadapter_31304,  ar, 157, 5,  44",
    })
    void comparisonOfAStoredEvaluation(String evaluation, String language, long storedIpfs, long pairedScreens, long ipfs) {
        Reanalysis.Row row = ROWS.get(evaluation + " " + language);

        assertNotNull(row, "no comparison was produced for " + evaluation + " " + language);
        assertEquals("", row.note);
        assertEquals(storedIpfs, row.reportedIpfs);
        assertEquals(pairedScreens, row.recomputedPaired);
        assertEquals(ipfs, row.recomputedIpfs);
    }

    @ParameterizedTest(name = "{0} [{1}]: {2} compared, {3} changed, {4} affected, {5} lost, {6} added, {7} not mirrored")
    @CsvSource({
            "com.elasticrock.candle_14,  es, 143,   4,  3, 0, 0,  0",
            "com.elasticrock.candle_14,  ar, 143,   0,  0, 0, 3,  0",
            "cc.polysfaer.stochapop_8,   es, 436, 196, 18, 0, 0,  0",
            "cc.polysfaer.stochapop_8,   ru, 436, 274,  0, 0, 0,  0",
            "cc.polysfaer.stochapop_8,   ar, 436,  11,  4, 0, 0,  8",
            "com.chmouel.noisetimer_2,   es, 124,  90, 12, 4, 0,  0",
            "org.birthdayadapter_31304,  ar, 228,  35,  9, 0, 0, 44",
    })
    void breakdownOfAStoredEvaluation(String evaluation, String language, long compared, long changed, long affected, long lost,
            long added, long notMirrored) {
        JSONObject summary = ROWS.get(evaluation + " " + language).summary;

        assertNotNull(summary, "no breakdown for " + evaluation + " " + language);
        assertEquals(compared, summary.get("nodesCompared"));
        assertEquals(changed, summary.get("nodesChanged"));
        assertEquals(affected, summary.get("nodesAffected"));
        assertEquals(lost, summary.get("componentsLost"));
        assertEquals(added, summary.get("componentsAdded"));
        assertEquals(notMirrored, ((JSONObject) summary.get("byType")).get("missingMirroring"));
    }

    @Test
    void everyStoredEvaluationWasCompared() {
        assertEquals(7, ROWS.size());
        // the total kept for continuity is the sum of the four parts of the breakdown
        for (Reanalysis.Row row : ROWS.values()) {
            long parts = 0;
            for (String key : new String[] {"nodesChanged", "nodesAffected", "componentsLost", "componentsAdded", "textOverGraphic"}) {
                parts += (Long) row.summary.get(key);
            }
            assertEquals(row.recomputedIpfs.longValue(), parts, row.evaluation + " " + row.language);
        }
    }

    @Test
    void twoRunsOverTheSameDataWriteTheSameFiles(@TempDir Path again) throws Exception {
        Reanalysis.reanalyze(Fixtures.evaluations(), again);

        List<String> different;
        try (Stream<Path> files = Files.walk(out)) {
            different = files.filter(Files::isRegularFile).map(f -> out.relativize(f).toString())
                    .filter(name -> !sameContent(out.resolve(name), again.resolve(name)))
                    .collect(Collectors.toList());
        }
        assertTrue(different.isEmpty(), "files that differ between two runs: " + different);
    }

    /** Same text, leaving out the line that carries the time of the run. */
    private static boolean sameContent(Path a, Path b) {
        try {
            return withoutTheTime(a).equals(withoutTheTime(b));
        } catch (IOException e) {
            return false;
        }
    }

    private static List<String> withoutTheTime(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(line -> !line.contains("\"generatedAt\""))
                .collect(Collectors.toList());
    }

    @Test
    void theSummaryCarriesTheBreakdownOfEveryLanguage() throws IOException {
        List<String> lines = Files.readAllLines(out.resolve("summary.csv"), StandardCharsets.UTF_8);

        assertTrue(lines.get(0).endsWith(";nodesCompared;nodesChanged;nodesAffected;componentsLost;componentsAdded;textOverGraphicOnly;positionLost;positionAdded;"
                + "alignmentLost;alignmentAdded;overlapAdded;overlapRemoved;missingMirroring;componentLost;componentAdded;textOverGraphic;outOfFrame;"
                + "siblingPairsChecked;siblingPairsNotMirrored;sourceTexts;unchangedTexts;sameTextAsSource;unchangedNotInApk"), lines.get(0));
        // the Arabic screens keep 1 of the 37 source texts, so the language was shown
        assertTrue(lines.contains("org.birthdayadapter_31304;ar;157;44;false;5;5;;228;35;9;0;0;0;0;0;0;0;0;0;44;0;0;0;0;61;61;37;1;false;"),
                String.join("\n", lines));
    }

    @Test
    void reanalysingDoesNotWriteIntoTheEvaluations() throws IOException {
        try (Stream<Path> files = Files.walk(Fixtures.evaluations())) {
            List<String> unexpected = files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.equals("result.json") && !n.equals("report.json"))
                    .collect(Collectors.toList());
            assertTrue(unexpected.isEmpty(), "files written next to the stored results: " + unexpected);
        }
        assertTrue(Files.isRegularFile(out.resolve("summary.csv")));
        assertTrue(Files.isRegularFile(out.resolve("com.elasticrock.candle_14").resolve("reanalysis.json")));
    }
}

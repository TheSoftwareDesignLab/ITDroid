package uniandes.tsdl.itdroid.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import uniandes.tsdl.itdroid.Fixtures;
import uniandes.tsdl.itdroid.helper.Reanalysis;

/**
 * Characterization of the comparison on real explorations: four apps of the evaluation batch of
 * 2026-10-19, re-analysed from their stored {@code result.json}. The numbers are what the code
 * produced when the fixtures were taken. A change to the comparison that moves any of them must be
 * intended, and the expected value is then updated here together with the change.
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

    @ParameterizedTest(name = "{0} [{1}]: {2} paired screens, {3} IPFs")
    @CsvSource({
            // a small Compose app: few nodes change
            "com.elasticrock.candle_14,  es, 5,   7",
            // ...and in Arabic one of its five screens is not paired
            "com.elasticrock.candle_14,  ar, 4,  41",
            // many nodes per screen: the count grows with the share of the screen affected
            "cc.polysfaer.stochapop_8,   es, 5, 215",
            "cc.polysfaer.stochapop_8,   ru, 5, 283",
            "cc.polysfaer.stochapop_8,   ar, 5, 376",
            // both screens have a different number of nodes than the source (positional pairing, TODO 4.2)
            "com.chmouel.noisetimer_2,   es, 2, 104",
            // the case most affected by the TextView rule (TODO 4.1: 91 with the 2020 rule)
            "org.birthdayadapter_31304,  ar, 5, 157",
    })
    void comparisonOfAStoredEvaluation(String evaluation, String language, long pairedScreens, long ipfs) {
        Reanalysis.Row row = ROWS.get(evaluation + " " + language);

        assertNotNull(row, "no comparison was produced for " + evaluation + " " + language);
        assertEquals("", row.note);
        assertEquals(pairedScreens, row.recomputedPaired);
        assertEquals(ipfs, row.recomputedIpfs);
    }

    @Test
    void theCountsStoredWithTheFixturesAreTheOnesTheCodeProduces() {
        assertEquals(7, ROWS.size());
        List<String> different = ROWS.values().stream().filter(r -> !r.same())
                .map(r -> r.evaluation + " " + r.language + ": stored " + r.reportedIpfs + ", computed " + r.recomputedIpfs)
                .collect(Collectors.toList());
        assertTrue(different.isEmpty(), different.toString());
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

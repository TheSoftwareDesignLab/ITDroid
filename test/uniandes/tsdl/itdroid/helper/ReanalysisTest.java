package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The guards of the re-analysis mode, which must never write into the evaluations it reads. */
class ReanalysisTest {

    @TempDir
    Path tmp;

    @Test
    void refusesAnOutputFolderInsideTheInput() throws Exception {
        Path evaluation = Files.createDirectories(tmp.resolve("batch").resolve("org.foo.app"));
        Files.writeString(evaluation.resolve("report.json"), "{}");
        Path inside = tmp.resolve("batch").resolve("reanalysis");

        ITDroidException e = assertThrows(ITDroidException.class,
                () -> Reanalysis.reanalyze(tmp.resolve("batch"), inside));

        assertTrue(e.getMessage().contains("outside"), e.getMessage());
        assertFalse(Files.exists(inside), "nothing may be created inside the input folder");
    }

    @Test
    void failsClearlyWhenTheFolderHoldsNoEvaluation() throws Exception {
        Path empty = Files.createDirectories(tmp.resolve("empty"));

        ITDroidException e = assertThrows(ITDroidException.class,
                () -> Reanalysis.reanalyze(empty, tmp.resolve("out")));

        assertTrue(e.getMessage().contains("No evaluation found"), e.getMessage());
    }

    @Test
    void findsASingleEvaluationOrTheEvaluationsOfABatch() throws Exception {
        Path batch = Files.createDirectories(tmp.resolve("batch"));
        for (String name : new String[] {"b.app", "A.app"}) {
            Files.writeString(Files.createDirectories(batch.resolve(name)).resolve("report.json"), "{}");
        }
        Files.createDirectories(batch.resolve("not-an-evaluation"));

        List<Path> found = Reanalysis.evaluations(batch);

        assertEquals(List.of("A.app", "b.app"), found.stream().map(p -> p.getFileName().toString()).toList());
        assertEquals(List.of(batch.resolve("A.app")), Reanalysis.evaluations(batch.resolve("A.app")));
    }

    @Test
    void anEvaluationThatStoppedBeforeExploringIsSkippedNotFatal() throws Exception {
        Path evaluation = Files.createDirectories(tmp.resolve("batch").resolve("org.foo.app"));
        Files.writeString(evaluation.resolve("report.json"), "{\"error\":\"PREFLIGHT: no device\"}");

        List<Reanalysis.Row> rows = Reanalysis.reanalyze(tmp.resolve("batch"), tmp.resolve("out"));

        assertTrue(rows.isEmpty());
        assertTrue(Files.isRegularFile(tmp.resolve("out").resolve("summary.csv")));
    }
}

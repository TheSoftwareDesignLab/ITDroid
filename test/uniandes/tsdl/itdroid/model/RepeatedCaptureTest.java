package uniandes.tsdl.itdroid.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uniandes.tsdl.itdroid.model.TestGraphs.button;
import static uniandes.tsdl.itdroid.model.TestGraphs.compare;
import static uniandes.tsdl.itdroid.model.TestGraphs.count;
import static uniandes.tsdl.itdroid.model.TestGraphs.screen;
import static uniandes.tsdl.itdroid.model.TestGraphs.writeResult;

import java.nio.file.Path;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests a replay that lost its way and kept the same capture for two source screens, as Forest
 * Pond in Spanish did with screens 1 and 4, and 3 and 5.
 */
class RepeatedCaptureTest {

    @TempDir
    Path tmp;

    @Test
    void oneCaptureIsComparedOnlyWithTheScreenItIs() throws Exception {
        String first = screen(button(0, 50, 100, 450, 200), button(1, 500, 100, 900, 200));
        // the second source screen is the same one with a third button below
        String second = screen(button(0, 50, 100, 450, 200), button(1, 500, 100, 900, 200), button(2, 50, 300, 450, 400));
        // the translated version captured the first screen for both
        String captured = screen(button(0, 50, 100, 450, 200), button(1, 500, 100, 900, 200));

        JSONObject report = compare(writeResult(tmp.resolve("en"), false, ".Main", first, second),
                writeResult(tmp.resolve("es"), true, ".Main", captured, captured), tmp.resolve("out"));

        assertEquals(1, count(report, "pairedStates"));
        JSONArray skipped = (JSONArray) report.get("statesNotCompared");
        assertEquals(1, skipped.size());
        JSONObject s = (JSONObject) skipped.get(0);
        assertEquals(2L, ((Number) s.get("stateID")).longValue());
        assertTrue(String.valueOf(s.get("reason")).contains("same capture as screen 1"), String.valueOf(s.get("reason")));
        assertEquals(0, count(report, "amIPFs"), "the first screen is the capture: nothing missing or extra");
    }

    @Test
    void twoScreensThatDifferAreBothCompared() throws Exception {
        String first = screen(button(0, 50, 100, 450, 200), button(1, 500, 100, 900, 200));
        String second = screen(button(0, 50, 100, 450, 200), button(1, 500, 100, 900, 200), button(2, 50, 300, 450, 400));

        JSONObject report = compare(writeResult(tmp.resolve("en"), false, ".Main", first, second),
                writeResult(tmp.resolve("es"), true, ".Main", first, second), tmp.resolve("out"));

        assertEquals(2, count(report, "pairedStates"));
        assertEquals(0, ((JSONArray) report.get("statesNotCompared")).size());
    }
}

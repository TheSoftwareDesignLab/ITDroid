package uniandes.tsdl.itdroid.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uniandes.tsdl.itdroid.model.TestGraphs.button;
import static uniandes.tsdl.itdroid.model.TestGraphs.compare;
import static uniandes.tsdl.itdroid.model.TestGraphs.count;
import static uniandes.tsdl.itdroid.model.TestGraphs.ipfNodes;
import static uniandes.tsdl.itdroid.model.TestGraphs.relations;
import static uniandes.tsdl.itdroid.model.TestGraphs.screen;
import static uniandes.tsdl.itdroid.model.TestGraphs.textView;
import static uniandes.tsdl.itdroid.model.TestGraphs.writeResult;

import java.nio.file.Path;
import java.util.Set;

import org.json.simple.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rules of the comparison between the source screen and the translated one, each on a
 * hand-made screen. In every screen node 0 is the full-screen root.
 *
 * <p>Tests named {@code knownGap_...} pin behaviour the audit found wrong. They assert what the code
 * does TODAY and name the TODO item that changes it; when that item is done the assertion flips.
 */
class ComparisonRulesTest {

    @TempDir
    Path tmp;

    private JSONObject compareScreens(String source, String translated) throws Exception {
        return compare(writeResult(tmp.resolve("en"), false, ".Main", source),
                writeResult(tmp.resolve("es"), true, ".Main", translated), tmp.resolve("out"));
    }

    @Test
    void identicalScreensHaveNoIpf() throws Exception {
        String s = screen(button(0, 50, 100, 450, 200), button(1, 500, 100, 900, 200));

        JSONObject report = compareScreens(s, s);

        assertEquals(1, count(report, "pairedStates"));
        assertEquals(0, count(report, "amIPFs"));
        assertEquals("replay", report.get("pairing"));
    }

    @Test
    void aButtonPushedToTheNextRowIsReportedOnBothButtons() throws Exception {
        // Source: two buttons side by side. Translated: the second one wrapped below the first.
        String source = screen(button(0, 50, 100, 450, 200), button(1, 500, 100, 900, 200));
        String translated = screen(button(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400));

        JSONObject report = compareScreens(source, translated);

        assertEquals(2, count(report, "amIPFs"));
        assertEquals(Set.of(1L, 2L), ipfNodes(report));
        assertEquals(Set.of("LEFT", "TOP_ALIGNED", "BOTTOM_ALIGNED"), relations(report, 1, 2, "removed"));
        assertEquals(Set.of("ABOVE", "LEFT_ALIGNED", "RIGHT_ALIGNED"), relations(report, 1, 2, "added"));
    }

    @Test
    void anAlignmentChangeIsIgnoredWhenTheTextViewComesFirstInTheDump() throws Exception {
        // A label loses its right alignment with the button below it because its text got wider.
        String source = screen(textView(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400));
        String translated = screen(textView(0, 50, 100, 600, 200), button(1, 50, 300, 450, 400));

        JSONObject report = compareScreens(source, translated);

        assertEquals(0, count(report, "amIPFs"));
    }

    /**
     * KNOWN GAP (TODO 4.1): the same change as in the previous test, with the two elements in the
     * other order in the dump, IS reported. The 2020 code ignored alignment changes when either node
     * of the pair was a TextView; the refactor only looks at the first one. Expected after 4.1: 0.
     */
    @Test
    void knownGap_theSameAlignmentChangeIsReportedWhenTheTextViewComesSecond() throws Exception {
        String source = screen(button(0, 50, 100, 450, 200), textView(1, 50, 300, 450, 400));
        String translated = screen(button(0, 50, 100, 450, 200), textView(1, 50, 300, 600, 400));

        JSONObject report = compareScreens(source, translated);

        assertEquals(2, count(report, "amIPFs"));
        assertEquals(Set.of("RIGHT_ALIGNED"), relations(report, 1, 2, "removed"));
    }

    @Test
    void aReplayedStateOnAnotherActivityIsNotPaired() throws Exception {
        String s = screen(button(0, 50, 100, 450, 200));

        JSONObject report = compare(writeResult(tmp.resolve("en"), false, ".Main", s),
                writeResult(tmp.resolve("es"), true, ".Settings", s), tmp.resolve("out"));

        assertEquals(0, count(report, "pairedStates"));
        assertEquals(1, count(report, "missingDfltStates"));
        assertEquals(1, count(report, "missingLangStates"));
        assertEquals(0, count(report, "amIPFs"));
    }

    @Test
    void aScreenWithFarMoreNodesThanTheSourceIsNotPaired() throws Exception {
        // 4 nodes against 6: beyond the tolerance of one node that a screen this small gets.
        String source = screen(button(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400), button(2, 50, 500, 450, 600));
        String translated = screen(button(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400), button(2, 50, 500, 450, 600),
                button(3, 50, 700, 450, 800), button(4, 50, 900, 450, 1000));

        JSONObject report = compareScreens(source, translated);

        assertEquals(0, count(report, "pairedStates"));
    }

    /**
     * KNOWN GAP (TODO 4.2 and 4.3): nodes are paired by position in the dump. The translated screen
     * has one extra element at the start; the three buttons did not move, yet each is compared with
     * its neighbour and reported. Expected after 4.2/4.3: the only finding is the added node.
     */
    @Test
    void knownGap_oneExtraNodeShiftsEveryLaterNodeAndReportsThemAll() throws Exception {
        String source = screen(button(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400), button(2, 50, 500, 450, 600));
        String translated = screen(button(0, 600, 100, 900, 200),
                button(1, 50, 100, 450, 200), button(2, 50, 300, 450, 400), button(3, 50, 500, 450, 600));

        JSONObject report = compareScreens(source, translated);

        assertEquals(1, count(report, "pairedStates"));
        assertTrue(count(report, "amIPFs") > 0, "positional pairing reports unmoved nodes");
        assertTrue(ipfNodes(report).contains(1L), "the first button is compared with the new element");
    }
}

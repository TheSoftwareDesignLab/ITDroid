package uniandes.tsdl.itdroid.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uniandes.tsdl.itdroid.model.TestGraphs.button;
import static uniandes.tsdl.itdroid.model.TestGraphs.compare;
import static uniandes.tsdl.itdroid.model.TestGraphs.count;
import static uniandes.tsdl.itdroid.model.TestGraphs.finding;
import static uniandes.tsdl.itdroid.model.TestGraphs.findings;
import static uniandes.tsdl.itdroid.model.TestGraphs.group;
import static uniandes.tsdl.itdroid.model.TestGraphs.ipfNodes;
import static uniandes.tsdl.itdroid.model.TestGraphs.ofType;
import static uniandes.tsdl.itdroid.model.TestGraphs.relations;
import static uniandes.tsdl.itdroid.model.TestGraphs.screen;
import static uniandes.tsdl.itdroid.model.TestGraphs.summary;
import static uniandes.tsdl.itdroid.model.TestGraphs.textView;
import static uniandes.tsdl.itdroid.model.TestGraphs.types;
import static uniandes.tsdl.itdroid.model.TestGraphs.writeResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rules of the comparison between the source screen and the translated one, each on a
 * hand-made screen. In every screen node 0 is the full-screen root (1000 x 2000).
 */
class ComparisonRulesTest {

    @TempDir
    Path tmp;

    @AfterEach
    void restoreTheThreshold() {
        LayoutGraphComparision.setMinSimilarity(LayoutGraphComparision.DEFAULT_MIN_SIMILARITY);
    }

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
        assertEquals(3, summary(report, "nodesCompared"));
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

    // ------------------------------------------------------------------ the kind of each change

    @Test
    void everyFindingSaysWhatKindOfChangeItIs() throws Exception {
        String source = screen(button(0, 50, 100, 450, 200), button(1, 500, 100, 900, 200));
        String translated = screen(button(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400));

        JSONObject report = compareScreens(source, translated);

        List<String> all = List.of("positionLost", "positionAdded", "alignmentLost", "alignmentAdded");
        assertEquals(all, types(report, 1));
        assertEquals(all, types(report, 2));
        assertEquals("relations", finding(report, 1).get("kind"));
        for (String type : all) {
            assertEquals(2, ofType(report, type), type);
        }
        assertEquals(0, ofType(report, "overlapAdded"));
        assertEquals(0, ofType(report, "componentLost"));
    }

    @Test
    void theElementThatMovedIsToldApartFromTheOneThatOnlyHasADifferentNeighbour() throws Exception {
        String source = screen(button(0, 50, 100, 450, 200), button(1, 500, 100, 900, 200));
        String translated = screen(button(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400));

        JSONObject report = compareScreens(source, translated);

        assertEquals("same", finding(report, 1).get("geometry"));
        assertEquals("moved", finding(report, 2).get("geometry"));
        assertEquals(1, summary(report, "nodesChanged"));
        assertEquals(1, summary(report, "nodesAffected"));
        // the old total is still there, and it is the sum of the parts
        assertEquals(count(report, "amIPFs"), summary(report, "nodesChanged") + summary(report, "nodesAffected")
                + summary(report, "componentsLost") + summary(report, "componentsAdded"));
    }

    @Test
    void aButtonThatGrowsOverItsNeighbourIsAnOverlap() throws Exception {
        String source = screen(button(0, 50, 100, 450, 200), button(1, 500, 100, 900, 200));
        String translated = screen(button(0, 50, 100, 550, 200), button(1, 500, 100, 900, 200));

        JSONObject report = compareScreens(source, translated);

        assertEquals(Set.of("LEFT"), relations(report, 1, 2, "removed"));
        assertEquals(Set.of("INTERSECTS"), relations(report, 1, 2, "added"));
        assertEquals(List.of("positionLost", "overlapAdded"), types(report, 1));
        assertEquals("resized", finding(report, 1).get("geometry"));
        assertEquals(2, ofType(report, "overlapAdded"));
    }

    @Test
    void theRelationsAreAlwaysWrittenInTheSameOrder() throws Exception {
        String source = screen(button(0, 50, 100, 450, 200), button(1, 500, 100, 900, 200));
        String translated = screen(button(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400));

        JSONObject report = compareScreens(source, translated);

        // the order of the enum, not that of a hash set, which changed from one run to the next
        JSONObject relation = (JSONObject) ((JSONArray) finding(report, 1).get("relations")).get(0);
        assertEquals("LEFT;TOP_ALIGNED;BOTTOM_ALIGNED;", relation.get("removed"));
        assertEquals("ABOVE;RIGHT_ALIGNED;LEFT_ALIGNED;", relation.get("added"));
        assertNull(relation.get("missing"));
    }

    // ------------------------------------------------------------------ texts

    @Test
    void anAlignmentChangeIsIgnoredWhenTheTextViewComesFirstInTheDump() throws Exception {
        // A label loses its right alignment with the button below it because its text got wider.
        String source = screen(textView(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400));
        String translated = screen(textView(0, 50, 100, 600, 200), button(1, 50, 300, 450, 400));

        JSONObject report = compareScreens(source, translated);

        assertEquals(0, count(report, "amIPFs"));
    }

    /** The rule of 2020: it is enough that either element of the pair is a text, whichever comes first. */
    @Test
    void anAlignmentChangeIsIgnoredWhenTheTextViewComesSecondInTheDump() throws Exception {
        String source = screen(button(0, 50, 100, 450, 200), textView(1, 50, 300, 450, 400));
        String translated = screen(button(0, 50, 100, 450, 200), textView(1, 50, 300, 600, 400));

        JSONObject report = compareScreens(source, translated);

        assertEquals(0, count(report, "amIPFs"));
    }

    @Test
    void aTextThatChangesPlaceIsStillReported() throws Exception {
        // only alignments are forgiven to a text: one pushed below its neighbour is a finding
        String source = screen(textView(0, 50, 100, 450, 200), button(1, 500, 100, 900, 200));
        String translated = screen(textView(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400));

        JSONObject report = compareScreens(source, translated);

        assertEquals(2, count(report, "amIPFs"));
        assertEquals(Set.of("LEFT"), relations(report, 1, 2, "removed"));
        assertEquals(Set.of("ABOVE"), relations(report, 1, 2, "added"));
    }

    // ------------------------------------------------------------------ which screens are compared

    @Test
    void aReplayedStateOnAnotherActivityIsNotPaired() throws Exception {
        String s = screen(button(0, 50, 100, 450, 200));

        JSONObject report = compare(writeResult(tmp.resolve("en"), false, ".Main", s),
                writeResult(tmp.resolve("es"), true, ".Settings", s), tmp.resolve("out"));

        assertEquals(0, count(report, "pairedStates"));
        assertEquals(1, count(report, "missingDfltStates"));
        assertEquals(1, count(report, "missingLangStates"));
        assertEquals(0, count(report, "amIPFs"));
        JSONObject skipped = (JSONObject) ((JSONArray) report.get("statesNotCompared")).get(0);
        assertEquals(1L, ((Number) skipped.get("stateID")).longValue());
        assertTrue(String.valueOf(skipped.get("reason")).contains("another activity"), String.valueOf(skipped));
    }

    @Test
    void aScreenWithMoreNodesThanTheSourceIsComparedAndTheExtraOnesAreFindings() throws Exception {
        // 4 nodes against 6. The number of nodes used to decide; now the screen is the same one
        // because the four of the source are all there.
        String source = screen(button(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400), button(2, 50, 500, 450, 600));
        String translated = screen(button(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400), button(2, 50, 500, 450, 600),
                button(3, 50, 700, 450, 800), button(4, 50, 900, 450, 1000));

        JSONObject report = compareScreens(source, translated);

        assertEquals(1, count(report, "pairedStates"));
        assertEquals(2, count(report, "amIPFs"));
        assertEquals(2, summary(report, "componentsAdded"));
        assertEquals(4, summary(report, "nodesCompared"));
        assertEquals(0, ((JSONArray) report.get("statesNotCompared")).size());
    }

    @Test
    void aScreenThatShowsSomethingElseIsNotCompared() throws Exception {
        // same activity, same step of the replay, but only the root can be matched: 1 of 8
        String source = screen(button(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400), button(2, 50, 500, 450, 600));
        String translated = screen(textView(0, 50, 100, 950, 200), textView(1, 50, 300, 950, 400), textView(2, 50, 500, 950, 600),
                textView(3, 50, 700, 950, 800), textView(4, 50, 900, 950, 1000), textView(5, 50, 1100, 950, 1200),
                textView(6, 50, 1300, 950, 1400));

        JSONObject report = compareScreens(source, translated);

        assertEquals(0, count(report, "pairedStates"));
        assertEquals(0, count(report, "amIPFs"));
        JSONObject skipped = (JSONObject) ((JSONArray) report.get("statesNotCompared")).get(0);
        assertTrue(String.valueOf(skipped.get("reason")).contains("different screen"), String.valueOf(skipped));
    }

    @Test
    void theShareOfElementsThatMakesTwoScreensTheSameOneCanBeChanged() throws Exception {
        String source = screen(button(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400), button(2, 50, 500, 450, 600));
        String translated = screen(textView(0, 50, 100, 950, 200), textView(1, 50, 300, 950, 400), textView(2, 50, 500, 950, 600),
                textView(3, 50, 700, 950, 800), textView(4, 50, 900, 950, 1000), textView(5, 50, 1100, 950, 1200),
                textView(6, 50, 1300, 950, 1400));
        LayoutGraphComparision.setMinSimilarity(0.1);

        JSONObject report = compareScreens(source, translated);

        // compared after all: the three buttons are gone and seven texts are new
        assertEquals(1, count(report, "pairedStates"));
        assertEquals(3, summary(report, "componentsLost"));
        assertEquals(7, summary(report, "componentsAdded"));
    }

    // ------------------------------------------------------------------ elements only one screen has

    @Test
    void anExtraElementIsTheOnlyFindingAndTheRestKeepTheirPairs() throws Exception {
        // The translated screen has one more element at the start of the dump. Pairing by position
        // compared every button with its neighbour and reported the three, which did not move.
        String source = screen(button(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400), button(2, 50, 500, 450, 600));
        String translated = screen(button(0, 600, 100, 900, 200),
                button(1, 50, 100, 450, 200), button(2, 50, 300, 450, 400), button(3, 50, 500, 450, 600));

        JSONObject report = compareScreens(source, translated);

        assertEquals(1, count(report, "pairedStates"));
        assertEquals(1, count(report, "amIPFs"));
        JSONObject added = findings(report).get(0);
        assertEquals("componentAdded", added.get("kind"));
        assertEquals(-1L, ((Number) added.get("nodeID")).longValue());
        assertEquals(1L, ((Number) added.get("langNodeID")).longValue());
        assertEquals(1, ofType(report, "componentAdded"));
    }

    @Test
    void aMissingContainerIsOneFindingThatCountsWhatItHeld() throws Exception {
        // nodes of the source: 0 root, 1 the container, 2 and 3 what it holds, 4 to 6 the buttons below
        String source = screen(group(0, 0, 100, 1000, 400, button(0, 50, 150, 450, 250), textView(1, 500, 150, 900, 250)),
                button(1, 50, 500, 450, 600), button(2, 50, 700, 450, 800), button(3, 50, 900, 450, 1000));
        String translated = screen(button(0, 50, 500, 450, 600), button(1, 50, 700, 450, 800), button(2, 50, 900, 450, 1000));

        JSONObject report = compareScreens(source, translated);

        assertEquals(1, count(report, "amIPFs"));
        JSONObject lost = finding(report, 1);
        assertEquals("componentLost", lost.get("kind"));
        assertEquals(-1L, ((Number) lost.get("langNodeID")).longValue());
        assertEquals(3L, ((Number) lost.get("score")).longValue());
        assertEquals(1, summary(report, "componentsLost"));
        assertEquals(4, summary(report, "nodesCompared"));
    }
}

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
 * Tests each rule of the comparison between the source and the translated screen on a hand-made
 * screen. Node 0 is always the full-screen root (1000 x 2000).
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

    /**
     * Two buttons side by side, and in the translation the second one wrapped below the first. The
     * finding is the second one's, the first one did not move and is only what it relates to.
     */
    @Test
    void aButtonPushedToTheNextRowIsReportedOnTheButtonThatMoved() throws Exception {
        String source = screen(button(0, 50, 100, 450, 200), button(1, 500, 100, 900, 200));
        String translated = screen(button(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400));

        JSONObject report = compareScreens(source, translated);

        assertEquals(1, count(report, "amIPFs"));
        assertEquals(Set.of(2L), ipfNodes(report));
        assertEquals(Set.of("RIGHT", "TOP_ALIGNED", "BOTTOM_ALIGNED"), relations(report, 2, 1, "removed"));
        assertEquals(Set.of("BELOW"), relations(report, 2, 1, "added"), "the edges it now shares with the other button meet by chance");
    }

    // ------------------------------------------------------------------ the kind of each change

    @Test
    void everyFindingSaysWhatKindOfChangeItIs() throws Exception {
        String source = screen(button(0, 50, 100, 450, 200), button(1, 500, 100, 900, 200));
        String translated = screen(button(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400));

        JSONObject report = compareScreens(source, translated);

        List<String> all = List.of("positionLost", "positionAdded", "alignmentLost");
        assertEquals(all, types(report, 2));
        assertEquals("relations", finding(report, 2).get("kind"));
        for (String type : all) {
            assertEquals(1, ofType(report, type), type);
        }
        assertEquals(0, ofType(report, "alignmentAdded"), "an edge shared by chance with an element apart");
        assertEquals(0, ofType(report, "overlapAdded"));
        assertEquals(0, ofType(report, "componentLost"));
    }

    @Test
    void theElementThatMovedIsTheOneReported() throws Exception {
        String source = screen(button(0, 50, 100, 450, 200), button(1, 500, 100, 900, 200));
        String translated = screen(button(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400));

        JSONObject report = compareScreens(source, translated);

        assertNull(finding(report, 1), "the neighbour that stayed has no finding of its own");
        assertEquals("moved", finding(report, 2).get("geometry"));
        assertEquals(1, summary(report, "nodesChanged"));
        assertEquals(0, summary(report, "nodesAffected"));
        // the old total is still there, and it is the sum of the parts
        assertEquals(count(report, "amIPFs"), summary(report, "nodesChanged") + summary(report, "nodesAffected")
                + summary(report, "componentsLost") + summary(report, "componentsAdded"));
    }

    @Test
    void aButtonThatGrowsOverItsNeighbourIsAnOverlap() throws Exception {
        String source = screen(button(0, "Save", 50, 100, 450, 200), button(1, "Cancel", 500, 100, 900, 200));
        String translated = screen(button(0, "Guardar", 50, 100, 550, 200), button(1, "Cancelar", 500, 100, 900, 200));

        JSONObject report = compareScreens(source, translated);

        assertEquals(Set.of("LEFT"), relations(report, 1, 2, "removed"));
        assertEquals(Set.of("INTERSECTS"), relations(report, 1, 2, "added"));
        assertEquals(List.of("positionLost", "overlapAdded"), types(report, 1));
        assertEquals("resized", finding(report, 1).get("geometry"));
        assertEquals(1, ofType(report, "overlapAdded"), "the neighbour it steps on did not change");
    }

    @Test
    void theRelationsAreAlwaysWrittenInTheSameOrder() throws Exception {
        String source = screen(button(0, 50, 100, 450, 200), button(1, 500, 100, 900, 200));
        String translated = screen(button(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400));

        JSONObject report = compareScreens(source, translated);

        // the order of the enum, not that of a hash set, which changed from one run to the next
        JSONObject relation = (JSONObject) ((JSONArray) finding(report, 2).get("relations")).get(0);
        assertEquals("RIGHT;TOP_ALIGNED;BOTTOM_ALIGNED;", relation.get("removed"));
        assertEquals("BELOW;", relation.get("added"));
        assertNull(relation.get("missing"));
    }

    // ------------------------------------------------------------------ texts

    @Test
    void anAlignmentChangeIsIgnoredWhenTheTextComesFirstInTheDump() throws Exception {
        // A label loses its right alignment with the button below it because its text got wider.
        String source = screen(TestGraphs.label(0, "Name", 50, 100, 450, 200), button(1, 50, 300, 450, 400));
        String translated = screen(TestGraphs.label(0, "Nombre del producto", 50, 100, 600, 200), button(1, 50, 300, 450, 400));

        JSONObject report = compareScreens(source, translated);

        assertEquals(0, count(report, "amIPFs"));
    }

    /** As in the 2020 rule, it is enough that either element of the pair is a text. */
    @Test
    void anAlignmentChangeIsIgnoredWhenTheTextComesSecondInTheDump() throws Exception {
        String source = screen(button(0, 50, 100, 450, 200), TestGraphs.label(1, "Name", 50, 300, 450, 400));
        String translated = screen(button(0, 50, 100, 450, 200), TestGraphs.label(1, "Nombre del producto", 50, 300, 600, 400));

        JSONObject report = compareScreens(source, translated);

        assertEquals(0, count(report, "amIPFs"));
    }

    /** What makes a text is what it shows, not its class. A button with a text is one, a text view with none is not. */
    @Test
    void aButtonWithATextIsATextAndAnEmptyTextViewIsNot() throws Exception {
        String source = screen(button(0, "Save", 50, 100, 450, 200), button(1, 50, 300, 450, 400));
        String translated = screen(button(0, "Guardar los cambios", 50, 100, 600, 200), button(1, 50, 300, 450, 400));
        assertEquals(0, count(compareScreens(source, translated), "amIPFs"));

        String empty = screen(textView(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400));
        String emptyWider = screen(textView(0, 50, 100, 600, 200), button(1, 50, 300, 450, 400));
        assertEquals(List.of("alignmentLost"), types(compareScreens(empty, emptyWider), 1));
    }

    @Test
    void aTextThatChangesPlaceIsStillReported() throws Exception {
        // only alignments are forgiven to a text, one pushed below its neighbour is a finding
        String source = screen(TestGraphs.label(0, "Name", 50, 100, 450, 200), button(1, 500, 100, 900, 200));
        String translated = screen(TestGraphs.label(0, "Nombre", 50, 100, 450, 200), button(1, 50, 300, 450, 400));

        JSONObject report = compareScreens(source, translated);

        assertEquals(1, count(report, "amIPFs"), "on the button that moved");
        assertEquals(Set.of("RIGHT"), relations(report, 2, 1, "removed"));
        assertEquals(Set.of("BELOW"), relations(report, 2, 1, "added"));
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
        // 4 nodes against 6. The node count used to decide, now it is the same screen because
        // the four of the source are all there.
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
        // same activity and replay step, but only the root can be matched (1 of 8)
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

        // compared after all, the three buttons are gone and seven texts are new
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
        // source nodes are 0 root, 1 the container, 2 and 3 what it holds, 4 to 6 the buttons below
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

    /** A text that wraps into one more line is a finding although every relation holds, with the element it pushed down. */
    @Test
    void aTextThatTakesOneMoreLineIsAFindingWithTheElementItPushed() throws Exception {
        String source = screen(TestGraphs.label(1, "Settings", 50, 100, 950, 160), button(2, 50, 200, 950, 300));
        String translated = screen(TestGraphs.label(1, "Configuraciones de la app", 50, 100, 950, 220), button(2, 50, 260, 950, 360));

        JSONObject report = compareScreens(source, translated);

        assertEquals(List.of("linesAdded"), types(report, 1));
        assertEquals(60L, ((Number) finding(report, 1).get("heightChange")).longValue());
        JSONObject pushed = (JSONObject) ((List<?>) finding(report, 1).get("relations")).get(0);
        assertEquals(2L, ((Number) pushed.get("relNode")).longValue());
        assertEquals(60L, ((Number) pushed.get("moved")).longValue());
        assertEquals(1, count(report, "amIPFs"), "the button only moved with it");
        assertEquals(1, summary(report, "moderate"));
    }

    /** A script with taller letters (Devanagari, Arabic) adds a few pixels, not a line. */
    @Test
    void tallerLettersOfAnotherScriptAreNotAnotherLine() throws Exception {
        String source = screen(TestGraphs.label(1, "All settings below are locked", 50, 100, 950, 230), button(2, 50, 300, 950, 400));
        String translated = screen(TestGraphs.label(1, "सभी सेटिंग्स", 50, 100, 950, 244), button(2, 50, 314, 950, 414));

        assertEquals(0, count(compareScreens(source, translated), "amIPFs"));
    }

    /**
     * A text that fits in one line less lets the translated screen show more of the element cut by the
     * bottom of the scroll view, which is reported as such and not as more lines.
     */
    @Test
    void anElementCutByTheBottomOfItsScrollViewThatShowsMoreIsAFinding() throws Exception {
        String source = screen(TestGraphs.scroller(1, 0, 0, 1000, 2000,
                TestGraphs.label(0, "Sky, Time And Light Settings", 50, 1700, 950, 1880), TestGraphs.label(1, "General Settings", 50, 1900, 950, 2000)));
        String translated = screen(TestGraphs.scroller(1, 0, 0, 1000, 2000,
                TestGraphs.label(0, "Sky", 50, 1700, 950, 1800), TestGraphs.label(1, "General", 50, 1820, 950, 2000)));

        JSONObject report = compareScreens(source, translated);

        assertEquals(List.of("linesRemoved"), types(report, 2));
        assertEquals(List.of("moreOnScreen"), types(report, 3));
        assertEquals(80L, ((Number) finding(report, 3).get("heightChange")).longValue());
        assertEquals(0, summary(report, "severe"));
    }

    /**
     * A title that gets wider is no longer left of a button two rows below, which says nothing of the
     * layout. Left and right are only compared between elements of one row.
     */
    @Test
    void leftAndRightAreComparedOnlyWithinARow() throws Exception {
        String source = screen(button(0, 50, 100, 400, 200), button(1, 500, 600, 900, 700));
        String translated = screen(button(0, 50, 100, 700, 200), button(1, 500, 600, 900, 700));

        assertEquals(0, count(compareScreens(source, translated), "amIPFs"));

        // in one row, the same growth makes the first one step on the second
        String rowSource = screen(button(0, "Save", 50, 100, 400, 200), button(1, "Cancel", 500, 100, 900, 200));
        String rowTranslated = screen(button(0, "Guardar", 50, 100, 700, 200), button(1, "Cancelar", 500, 100, 900, 200));
        assertEquals(List.of("positionLost", "overlapAdded"), types(compareScreens(rowSource, rowTranslated), 1));
    }

    /** Two buttons that share a pixel or two after the translation still sit side by side. */
    @Test
    void twoElementsThatShareASliverDoNotOverlap() throws Exception {
        String source = screen(button(0, 50, 100, 400, 200), button(1, 400, 100, 900, 200));
        String translated = screen(button(0, 50, 100, 402, 200), button(1, 400, 100, 900, 200));

        assertEquals(0, count(compareScreens(source, translated), "amIPFs"));
    }

    // ------------------------------------------------------------------ shifts of a few pixels

    /** The icon of a bottom bar item gets 16 px shorter when its label is wider, and nothing else changes. */
    @Test
    void anIconALittleShorterBesideAWiderLabelIsNoFinding() throws Exception {
        String source = screen(button(0, 100, 1800, 250, 1926), TestGraphs.label(1, "Watchlists", 110, 1910, 240, 1944),
                button(2, 700, 1800, 850, 1926), TestGraphs.label(3, "Settings", 720, 1910, 830, 1944));
        String translated = screen(button(0, 100, 1800, 250, 1910), TestGraphs.label(1, "Listas de observación", 40, 1910, 310, 1944),
                button(2, 700, 1800, 850, 1926), TestGraphs.label(3, "Ajustes", 730, 1910, 820, 1944));

        assertEquals(0, count(compareScreens(source, translated), "amIPFs"));
    }

    /** An edge the layout rounds one pixel another way keeps its alignment. */
    @Test
    void anEdgeOnePixelOffKeepsItsAlignment() throws Exception {
        String source = screen(button(0, 50, 100, 450, 200), button(1, 500, 100, 900, 200));
        String translated = screen(button(0, 50, 100, 450, 199), button(1, 500, 100, 900, 200));

        assertEquals(0, count(compareScreens(source, translated), "amIPFs"));
    }

    /** Taller letters make a text step 3 px out of the box it sits in, which does not put it over the box. */
    @Test
    void aTextAFewPixelsOutOfItsBoxIsNotOverIt() throws Exception {
        String source = screen(button(0, 100, 500, 500, 560), TestGraphs.label(1, "New watchlist", 100, 505, 400, 555));
        String translated = screen(button(0, 100, 500, 500, 560), TestGraphs.label(1, "नया वॉचलिस्ट", 100, 497, 350, 563));

        assertEquals(0, count(compareScreens(source, translated), "amIPFs"));
    }

    /** A button that reaches 6 px into its neighbour is not over it, one that reaches 20 px is. */
    @Test
    void aNeighbourIsOverlappedOnlyPastTheSlack() throws Exception {
        String source = screen(button(0, "Save", 50, 100, 400, 200), button(1, "Cancel", 420, 100, 900, 200));
        String shallow = screen(button(0, "Guardar", 50, 100, 426, 200), button(1, "Cancelar", 420, 100, 900, 200));
        String deep = screen(button(0, "Guardar", 50, 100, 440, 200), button(1, "Cancelar", 420, 100, 900, 200));

        assertEquals(0, count(compareScreens(source, shallow), "amIPFs"));
        assertEquals(List.of("positionLost", "overlapAdded"), types(compareScreens(source, deep), 1));
    }

    // ------------------------------------------------------------------ out of the frame

    private JSONObject compareTwoScreens(String app, String menu, String translatedApp, String translatedMenu) throws Exception {
        return compare(writeResult(tmp.resolve("en"), false, ".Main", app, menu),
                writeResult(tmp.resolve("es"), true, ".Main", translatedApp, translatedMenu), tmp.resolve("out"));
    }

    /** A menu dumped alone grows to the left to fit its rows and stays inside the app, as it is meant to. */
    @Test
    void aMenuThatGrowsWithinTheAppIsNotOutOfTheFrame() throws Exception {
        String app = screen(button(0, 50, 100, 450, 200));
        String menu = TestGraphs.window(700, 1500, 980, 1800,
                TestGraphs.label(0, "Rename", 700, 1520, 980, 1600), TestGraphs.label(1, "Delete", 700, 1620, 980, 1700));
        String wider = TestGraphs.window(500, 1500, 980, 1800,
                TestGraphs.label(0, "Cambiar el nombre", 500, 1520, 980, 1600), TestGraphs.label(1, "Borrar", 500, 1620, 980, 1700));

        JSONObject report = compareTwoScreens(app, menu, app, wider);

        assertEquals(2, count(report, "pairedStates"));
        assertEquals(0, ofType(report, "outOfFrame"));
        assertEquals(0, count(report, "amIPFs"));
    }

    /** A list that grows past the side of an app shown as a window reaches the edge of the screen and cuts its text. */
    @Test
    void aMenuWiderThanTheAppIsOutOfTheFrame() throws Exception {
        String app = TestGraphs.window(40, 48, 680, 1232, button(0, 80, 100, 640, 200));
        String menu = TestGraphs.window(80, 48, 640, 694,
                TestGraphs.label(0, "Select Preset", 80, 64, 640, 168), TestGraphs.label(1, "Afternoon", 80, 169, 640, 273));
        String wider = TestGraphs.window(57, 125, 720, 786,
                TestGraphs.label(0, "Выбрать предварительно заданное", 57, 141, 720, 245), TestGraphs.label(1, "Afternoon", 57, 246, 720, 350));

        JSONObject report = compareTwoScreens(app, menu, app, wider);

        assertEquals(1, ofType(report, "outOfFrame"), "only the menu, its rows go with it");
        assertEquals(List.of("outOfFrame"), types(report, 0));
        assertEquals("severe", finding(report, 0).get("severity"));
    }

    /** A row that passes the side of the menu holding it is out of the frame, while the menu is not. */
    @Test
    void aRowWiderThanItsWindowIsOutOfTheFrame() throws Exception {
        String app = screen(button(0, 50, 100, 450, 200));
        String menu = TestGraphs.window(500, 1500, 980, 1800, button(0, 520, 1520, 960, 1600));
        String wider = TestGraphs.window(500, 1500, 980, 1800, button(0, 400, 1520, 960, 1600));

        JSONObject report = compareTwoScreens(app, menu, app, wider);

        assertEquals(1, ofType(report, "outOfFrame"));
        assertTrue(types(report, 1).contains("outOfFrame"), String.valueOf(types(report, 1)));
        assertNull(finding(report, 0));
    }
}

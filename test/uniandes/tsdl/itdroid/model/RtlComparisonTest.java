package uniandes.tsdl.itdroid.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static uniandes.tsdl.itdroid.model.TestGraphs.button;
import static uniandes.tsdl.itdroid.model.TestGraphs.compare;
import static uniandes.tsdl.itdroid.model.TestGraphs.count;
import static uniandes.tsdl.itdroid.model.TestGraphs.finding;
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

import org.json.simple.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The comparison between a left-to-right source and a right-to-left language. There the layout is
 * expected to be mirrored: that the element that was on the left is now on the right is not a
 * failure, and that it is still on the left is. The screen is 1000 wide.
 */
class RtlComparisonTest {

    @TempDir
    Path tmp;

    /** Two buttons side by side, and the same two with the row mirrored. */
    private static final String ROW = screen(button(0, 50, 100, 450, 200), button(1, 500, 100, 900, 200));
    private static final String MIRRORED_ROW = screen(button(0, 550, 100, 950, 200), button(1, 100, 100, 500, 200));

    private JSONObject compareWith(String code, String name, String source, String translated) throws Exception {
        return compare(writeResult(tmp.resolve("en"), false, ".Main", source),
                writeResult(tmp.resolve(code), true, ".Main", translated), tmp.resolve("out"), code, name);
    }

    @Test
    void aMirroredLayoutIsNotAFailureInArabic() throws Exception {
        JSONObject report = compareWith("ar", "Arabic", ROW, MIRRORED_ROW);

        assertEquals(1, count(report, "pairedStates"));
        assertEquals(0, count(report, "amIPFs"));
        assertEquals(Boolean.TRUE, ((JSONObject) report.get("ipfSummary")).get("mirrorExpected"));
        assertEquals(1, summary(report, "siblingPairsChecked"));
        assertEquals(0, summary(report, "siblingPairsNotMirrored"));
    }

    @Test
    void theSameMirroredLayoutIsAFailureInALeftToRightLanguage() throws Exception {
        JSONObject report = compareWith("es", "Spanish", ROW, MIRRORED_ROW);

        assertEquals(2, count(report, "amIPFs"));
        assertEquals(Set.of("LEFT"), relations(report, 1, 2, "removed"));
        assertEquals(Set.of("RIGHT"), relations(report, 1, 2, "added"));
        assertEquals(Boolean.FALSE, ((JSONObject) report.get("ipfSummary")).get("mirrorExpected"));
        assertEquals(-1, summary(report, "siblingPairsChecked"));
    }

    @Test
    void aLayoutThatStaysAsItWasInArabicIsMissingItsMirroring() throws Exception {
        JSONObject report = compareWith("ar", "Arabic", ROW, ROW);

        assertEquals(2, count(report, "amIPFs"));
        assertEquals(Set.of(1L, 2L), ipfNodes(report));
        assertEquals(List.of("missingMirroring"), types(report, 1));
        // what should be there and is not: the first button to the right of the second
        assertEquals(Set.of("RIGHT"), relations(report, 1, 2, "missing"));
        assertEquals(Set.of("LEFT"), relations(report, 2, 1, "missing"));
        assertEquals(Set.of(), relations(report, 1, 2, "removed"));
        assertEquals("same", finding(report, 1).get("geometry"));
        assertEquals(2, ofType(report, "missingMirroring"));
        assertEquals(1, summary(report, "siblingPairsChecked"));
        assertEquals(1, summary(report, "siblingPairsNotMirrored"));
    }

    @Test
    void hebrewAndPersianAreRightToLeftToo() throws Exception {
        assertEquals(0, count(compareWith("iw", "Hebrew", ROW, MIRRORED_ROW), "amIPFs"));
        assertEquals(0, count(compareWith("fa-rIR", "Persian", ROW, MIRRORED_ROW), "amIPFs"));
    }

    @Test
    void onlyElementsOfTheSameContainerAreAskedToSwapSides() throws Exception {
        // 0 root; 1 the left half with button 2; 3 the right half with button 4. Nothing is mirrored.
        String s = screen(group(0, 0, 100, 500, 300, button(0, 50, 150, 450, 250)),
                group(1, 500, 100, 1000, 300, button(0, 550, 150, 950, 250)));

        JSONObject report = compareWith("ar", "Arabic", s, s);

        // the two halves are siblings and should have swapped; a button and the one of the other
        // half are not, and are not reported on top of it
        assertEquals(Set.of(1L, 3L), ipfNodes(report));
        assertEquals(1, summary(report, "siblingPairsChecked"));
    }

    @Test
    void aMovementThatMirroringDoesNotExplainIsStillReportedInArabic() throws Exception {
        // the second button dropped below the first: neither as it was nor mirrored
        String translated = screen(button(0, 550, 100, 950, 200), button(1, 550, 300, 950, 400));

        JSONObject report = compareWith("ar", "Arabic", ROW, translated);

        assertEquals(2, count(report, "amIPFs"));
        assertEquals(Set.of("TOP_ALIGNED", "BOTTOM_ALIGNED"), relations(report, 1, 2, "removed"));
        assertEquals(Set.of("ABOVE"), relations(report, 1, 2, "added"));
        assertEquals(Set.of("RIGHT"), relations(report, 1, 2, "missing"));
        assertEquals(List.of("positionAdded", "alignmentLost", "missingMirroring"), types(report, 1));
    }

    @Test
    void aTextIsNotAskedToMirrorItsAlignment() throws Exception {
        // a label above a wider button, both starting at the left edge, the same in Arabic: the
        // width of a text says nothing about the side it is aligned to
        String withText = screen(textView(0, 50, 100, 450, 200), button(1, 50, 300, 600, 400));
        String withoutText = screen(button(0, 50, 100, 450, 200), button(1, 50, 300, 600, 400));

        assertEquals(0, count(compareWith("ar", "Arabic", withText, withText), "amIPFs"));

        JSONObject report = compareWith("ar", "Arabic", withoutText, withoutText);
        assertEquals(2, count(report, "amIPFs"));
        assertEquals(Set.of("RIGHT_ALIGNED"), relations(report, 1, 2, "missing"));
    }

    @Test
    void aMirroredElementIsNotCountedAsMoved() throws Exception {
        // one button of the row mirrored and the other left where it was
        String translated = screen(button(0, 550, 100, 950, 200), button(1, 500, 100, 900, 200));

        JSONObject report = compareWith("ar", "Arabic", ROW, translated);

        assertEquals("mirrored", finding(report, 1).get("geometry"));
        assertEquals("same", finding(report, 2).get("geometry"));
        assertEquals(0, summary(report, "nodesChanged"));
        assertEquals(2, summary(report, "nodesAffected"));
    }

    /** A dialog from 100 to 900 of the screen: its dump only reaches its own edges. */
    private static String dialog(String... children) {
        return "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">"
                + TestGraphs.node(0, "android.widget.FrameLayout", "", 100, 500, 900, 1200, children) + "</hierarchy>";
    }

    /**
     * The mirror image of an element is taken across the middle of the screen. The screen is as wide
     * as the widest screen of the exploration, not as the dialog whose elements are compared.
     */
    @Test
    void anElementOfADialogIsMirroredAcrossTheMiddleOfTheScreen() throws Exception {
        String main = screen(button(0, 50, 100, 450, 200));
        String mainInArabic = screen(button(0, 550, 100, 950, 200));
        // two buttons over a line of text; in Arabic the buttons swap sides and the text went above them
        String sourceDialog = dialog(button(0, 150, 600, 400, 700), button(1, 600, 600, 850, 700), textView(2, 150, 800, 850, 900));
        String arabicDialog = dialog(button(0, 600, 600, 850, 700), button(1, 150, 600, 400, 700), textView(2, 150, 520, 850, 580));

        JSONObject report = compare(writeResult(tmp.resolve("en"), false, ".Main", main, sourceDialog),
                writeResult(tmp.resolve("ar"), true, ".Main", mainInArabic, arabicDialog), tmp.resolve("out"), "ar", "Arabic");

        assertEquals(2, count(report, "pairedStates"));
        // the buttons have a finding because of the text, and they themselves are where a mirrored screen has them
        assertEquals("mirrored", finding(report, 1).get("geometry"));
        assertEquals("mirrored", finding(report, 2).get("geometry"));
        assertEquals(0, summary(report, "siblingPairsNotMirrored"));
    }
}

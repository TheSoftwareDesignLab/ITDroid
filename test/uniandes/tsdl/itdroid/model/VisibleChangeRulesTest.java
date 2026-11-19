package uniandes.tsdl.itdroid.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uniandes.tsdl.itdroid.model.TestGraphs.button;
import static uniandes.tsdl.itdroid.model.TestGraphs.canvas;
import static uniandes.tsdl.itdroid.model.TestGraphs.count;
import static uniandes.tsdl.itdroid.model.TestGraphs.fill;
import static uniandes.tsdl.itdroid.model.TestGraphs.finding;
import static uniandes.tsdl.itdroid.model.TestGraphs.group;
import static uniandes.tsdl.itdroid.model.TestGraphs.ipfNodes;
import static uniandes.tsdl.itdroid.model.TestGraphs.label;
import static uniandes.tsdl.itdroid.model.TestGraphs.relations;
import static uniandes.tsdl.itdroid.model.TestGraphs.summary;
import static uniandes.tsdl.itdroid.model.TestGraphs.text;
import static uniandes.tsdl.itdroid.model.TestGraphs.types;
import static uniandes.tsdl.itdroid.model.TestGraphs.view;
import static uniandes.tsdl.itdroid.model.TestGraphs.window;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rules that keep to what a person sees, on hand-made screens of 500 x 800 with hand-drawn
 * screenshots. Node 0 is always the root.
 */
class VisibleChangeRulesTest {

    private static final int W = 500, H = 800;
    private static final Color PAGE = new Color(30, 30, 60), CARD = new Color(200, 200, 220);

    @TempDir
    Path tmp;

    private JSONObject compare(String source, BufferedImage sourceShot, String translated, BufferedImage translatedShot) throws Exception {
        Path en = sourceShot == null ? TestGraphs.writeResult(tmp.resolve("en"), false, ".Main", source)
                : TestGraphs.writeResult(tmp.resolve("en"), false, ".Main", source, sourceShot);
        Path es = translatedShot == null ? TestGraphs.writeResult(tmp.resolve("es"), true, ".Main", translated)
                : TestGraphs.writeResult(tmp.resolve("es"), true, ".Main", translated, translatedShot);
        return TestGraphs.compare(en, es, tmp.resolve("out"));
    }

    private static String screen(String... children) {
        return window(0, 0, W, H, children);
    }

    // ------------------------------------------------------------------ an element and what holds it

    /** The box of a check mark passes the side of its card, the mark it draws does not. */
    @Test
    void aBoxThatLeavesItsContainerWithItsDrawingInsideIsNoFinding() throws Exception {
        String source = screen(group(0, 100, 100, 400, 200, view(0, 110, 110, 200, 190)));
        String translated = screen(group(0, 100, 100, 400, 200, view(0, 80, 110, 170, 190)));
        BufferedImage before = canvas(W, H, PAGE), after = canvas(W, H, PAGE);
        fill(before, CARD, 100, 100, 400, 200);
        fill(before, Color.RED, 140, 140, 160, 160);
        fill(after, CARD, 100, 100, 400, 200);
        fill(after, Color.RED, 110, 140, 130, 160);

        JSONObject report = compare(source, before, translated, after);

        assertEquals(0, count(report, "amIPFs"), String.valueOf(report.get("ipfs")));
    }

    @Test
    void anElementThatDrawsPastTheSideOfItsContainerIsSevere() throws Exception {
        String source = screen(group(0, 100, 100, 400, 200, view(0, 110, 110, 200, 190)));
        String translated = screen(group(0, 100, 100, 400, 200, view(0, 80, 110, 170, 190)));
        BufferedImage before = canvas(W, H, PAGE), after = canvas(W, H, PAGE);
        fill(before, CARD, 100, 100, 400, 200);
        fill(before, Color.RED, 140, 140, 160, 160);
        fill(after, CARD, 100, 100, 400, 200);
        fill(after, Color.RED, 84, 140, 104, 160);

        JSONObject report = compare(source, before, translated, after);

        assertEquals(List.of("outOfContainer"), types(report, 2));
        assertEquals("severe", finding(report, 2).get("severity"));
        assertEquals(Set.of("CONTAINED"), relations(report, 2, 1, "removed"));
        assertNull(finding(report, 1), "the card did not change");
    }

    /** Without screenshots the box decides, and an element and what holds it never overlap. */
    @Test
    void withoutScreenshotsTheBoxThatLeavesItsContainerIsOutOfIt() throws Exception {
        String source = screen(group(0, 100, 100, 400, 200, view(0, 110, 110, 200, 190)));
        String translated = screen(group(0, 100, 100, 400, 200, view(0, 80, 110, 170, 190)));

        JSONObject report = compare(source, null, translated, null);

        assertEquals(List.of("outOfContainer"), types(report, 2));
        assertEquals(0, TestGraphs.ofType(report, "overlapAdded"));
    }

    /** A button of a segmented choice, its left side drawn as a line, with an icon and a label. */
    private static BufferedImage segment(int iconFrom) {
        BufferedImage img = canvas(W, H, PAGE);
        fill(img, CARD, 250, 100, 450, 160);
        fill(img, Color.DARK_GRAY, 250, 100, 252, 160);
        fill(img, Color.RED, iconFrom, 120, iconFrom + 20, 140);
        return img;
    }

    /** reReminder, Alarm in Russian. A longer label leaves no room for the icon, the button cuts it and the clock lies on its border. */
    @Test
    void anIconALongerLabelPushesOntoTheBorderOfItsButtonIsCutBySevere() throws Exception {
        String source = screen(group(0, 250, 100, 450, 160, view(0, 300, 100, 360, 160), label(1, "Alarm", 350, 115, 400, 145)));
        String translated = screen(group(0, 250, 100, 450, 160, view(0, 250, 100, 290, 160), label(1, "Будильник", 290, 115, 450, 145)));

        JSONObject report = compare(source, segment(320), translated, segment(253));

        assertTrue(types(report, 2).contains("outOfContainer"), String.valueOf(report.get("ipfs")));
        assertEquals("severe", finding(report, 2).get("severity"));
        JSONObject relation = (JSONObject) ((JSONArray) finding(report, 2).get("relations")).get(0);
        assertEquals("left", relation.get("cut"));
    }

    @Test
    void anIconCutByItsButtonThatDrawsClearOfTheBorderIsNotOutOfIt() throws Exception {
        String source = screen(group(0, 250, 100, 450, 160, view(0, 300, 100, 360, 160), label(1, "Alarm", 350, 115, 400, 145)));
        String translated = screen(group(0, 250, 100, 450, 160, view(0, 250, 100, 290, 160), label(1, "Будильник", 290, 115, 450, 145)));

        JSONObject report = compare(source, segment(320), translated, segment(266));

        assertEquals(0, TestGraphs.ofType(report, "outOfContainer"), String.valueOf(report.get("ipfs")));
    }

    // ------------------------------------------------------------------ overlaps that are seen

    private static BufferedImage withIcon() {
        BufferedImage img = canvas(W, H, PAGE);
        fill(img, Color.RED, 330, 100, 370, 140);
        return img;
    }

    /** The box of a wider text reaches the box of an icon, while its letters stop well before it. */
    @Test
    void anOverlapOfEmptyBoxesIsNoFinding() throws Exception {
        String source = screen(label(0, "Play a mix", 20, 100, 300, 140), view(1, 320, 90, 400, 150));
        String translated = screen(label(0, "Reproduce una mezcla", 20, 100, 345, 140), view(1, 320, 90, 400, 150));
        BufferedImage before = withIcon(), after = withIcon();
        text(before, Color.WHITE, "Play a mix", 20, 110, 130, 12, 300);
        text(after, Color.WHITE, "Reproduce una", 20, 110, 130, 12, 345);

        JSONObject report = compare(source, before, translated, after);

        assertEquals(0, count(report, "amIPFs"), String.valueOf(report.get("ipfs")));
    }

    @Test
    void lettersDrawnOverAnIconAreASevereOverlapOfTheTextAlone() throws Exception {
        String source = screen(label(0, "Play a mix", 20, 100, 300, 140), view(1, 320, 90, 400, 150));
        String translated = screen(label(0, "Reproduce una mezcla", 20, 100, 345, 140), view(1, 320, 90, 400, 150));
        BufferedImage before = withIcon(), after = withIcon();
        text(before, Color.WHITE, "Play a mix", 20, 110, 130, 12, 300);
        text(after, Color.WHITE, "Reproduce una mezcla", 20, 110, 130, 17, 345);

        JSONObject report = compare(source, before, translated, after);

        assertEquals(Set.of(1L), ipfNodes(report), "the icon did not move, it is only what the text steps on");
        assertTrue(types(report, 1).contains("overlapAdded"), String.valueOf(types(report, 1)));
        assertEquals("severe", finding(report, 1).get("severity"));
        assertEquals(Set.of("INTERSECTS"), relations(report, 1, 2, "added"));
    }

    /** Without a text, a description or a picture on either side, nothing a person reads is covered. */
    @Test
    void twoBoxesWithoutTextDoNotOverlapSeverely() throws Exception {
        String source = screen(view(0, 20, 100, 300, 140), view(1, 320, 90, 400, 150));
        String translated = screen(view(0, 20, 100, 345, 140), view(1, 320, 90, 400, 150));

        JSONObject report = compare(source, null, translated, null);

        assertEquals(0, TestGraphs.ofType(report, "overlapAdded"));
        assertEquals(0, summary(report, "severe"));
    }

    // ------------------------------------------------------------------ the element that changed

    @Test
    void theNeighbourThatDidNotMoveIsOnlyRelated() throws Exception {
        String source = screen(button(0, 20, 100, 200, 160), button(1, 250, 100, 450, 160));
        String translated = screen(button(0, 20, 100, 200, 160), button(1, 20, 200, 200, 260));

        JSONObject report = compare(source, null, translated, null);

        assertEquals(Set.of(2L), ipfNodes(report));
        assertEquals(1L, ((Number) finding(report, 2).get("score")).longValue());
        assertEquals(1, summary(report, "nodesChanged"));
        assertEquals(0, summary(report, "nodesAffected"));
    }

    // ------------------------------------------------------------------ nested elements with one box

    /** Three layers of one bar move together. They are one element, reported once on the outermost. */
    @Test
    void nestedElementsWithTheSameBoxAreOneFinding() throws Exception {
        String source = screen(view(0, 0, 300, 500, 360, view(0, 0, 300, 500, 360, view(0, 0, 300, 500, 360))), button(1, 0, 400, 500, 460));
        String translated = screen(view(0, 0, 500, 500, 560, view(0, 0, 500, 500, 560, view(0, 0, 500, 500, 560))), button(1, 0, 400, 500, 460));

        JSONObject report = compare(source, null, translated, null);

        assertEquals(1, count(report, "amIPFs"), String.valueOf(report.get("ipfs")));
        assertNotNull(finding(report, 1));
        assertEquals(1, ((JSONArray) finding(report, 1).get("relations")).size());
    }

    /** The layer with the text speaks for the chain. */
    @Test
    void theLayerWithTheTextSpeaksForItsChain() throws Exception {
        String source = screen(view(0, 0, 300, 500, 360, label(0, "Now playing", 0, 300, 500, 360)), button(1, 0, 400, 500, 460));
        String translated = screen(view(0, 0, 500, 500, 560, label(0, "Reproduciendo", 0, 500, 500, 560)), button(1, 0, 400, 500, 460));

        JSONObject report = compare(source, null, translated, null);

        assertEquals(Set.of(2L), ipfNodes(report));
    }

    /** A moved element relates to a chain once, by its outermost layer. */
    @Test
    void aRelationToAChainNamesItOnce() throws Exception {
        String source = screen(view(0, 0, 300, 500, 360, view(0, 0, 300, 500, 360, view(0, 0, 300, 500, 360))), button(1, 0, 400, 500, 460));
        String translated = screen(view(0, 0, 300, 500, 360, view(0, 0, 300, 500, 360, view(0, 0, 300, 500, 360))), button(1, 0, 200, 500, 260));

        JSONObject report = compare(source, null, translated, null);

        assertEquals(Set.of(4L), ipfNodes(report));
        JSONArray rel = (JSONArray) finding(report, 4).get("relations");
        assertEquals(1, rel.size(), rel.toJSONString());
        assertEquals(1L, ((Number) ((JSONObject) rel.get(0)).get("relNode")).longValue());
    }

    // ------------------------------------------------------------------ what draws nothing

    @Test
    void anEmptyViewThatMovedHasNoFindingOfItsOwn() throws Exception {
        String source = screen(view(0, 100, 100, 200, 200), button(1, 300, 100, 400, 200));
        String translated = screen(view(0, 100, 300, 200, 400), button(1, 300, 100, 400, 200));
        BufferedImage shot = canvas(W, H, PAGE);
        fill(shot, CARD, 300, 100, 400, 200);

        assertEquals(0, count(compare(source, shot, translated, shot), "amIPFs"));
        // without the screenshot the box is all there is to judge
        assertEquals(1, count(compare(source, null, translated, null), "amIPFs"));
    }

    // ------------------------------------------------------------------ scrolled content under a bar

    private static String list(String item) {
        return screen(TestGraphs.scroller(0, 0, 0, W, 700, item), view(1, 0, 700, W, H, label(0, "Not playing", 50, 720, 250, 780)));
    }

    /** A list item pushed under a bar outside the list can be scrolled to, it is not an overlap. */
    @Test
    void scrolledContentUnderABarIsAtMostLessOnScreen() throws Exception {
        JSONObject report = compare(list(label(0, "Adele", 50, 600, 250, 670)), null, list(label(0, "Jaime El King", 50, 650, 250, 720)), null);

        assertEquals(0, TestGraphs.ofType(report, "overlapAdded"));
        assertEquals(List.of("lessOnScreen"), types(report, 2));
        assertEquals("moderate", finding(report, 2).get("severity"));
        assertEquals(0, summary(report, "severe"));
    }

    /** When the letters stay above the bar and only the empty bottom of the box passes under it, nothing is hidden. */
    @Test
    void anEmptyPartUnderABarHidesNothing() throws Exception {
        BufferedImage before = canvas(W, H, Color.WHITE), after = canvas(W, H, Color.WHITE);
        fill(before, CARD, 0, 700, W, H);
        fill(after, CARD, 0, 700, W, H);
        text(before, Color.BLACK, "Adele", 60, 605, 630, 14, 250);
        text(after, Color.BLACK, "Jaime El King", 60, 655, 680, 14, 250);

        JSONObject report = compare(list(label(0, "Adele", 50, 600, 250, 670)), before, list(label(0, "Jaime El King", 50, 650, 250, 720)), after);

        assertEquals(0, count(report, "amIPFs"), String.valueOf(report.get("ipfs")));
    }

    // ------------------------------------------------------------------ text cut inside its box

    /**
     * Three texts that fit, from which the width of a letter of the language is learned, and the one
     * tested in a box from x 20 to {@code box}, its translation drawn up to {@code clip}. Letters are
     * 12 pixels apart in the source and 13 in the translation.
     */
    private JSONObject cut(String sourceText, String translatedText, int box, int clip) throws Exception {
        String[] sources = { "Settings", "Free mode", "Sound on", sourceText };
        String[] translations = { "Ajustes de app", "Modo gratis", "Sonido activo", translatedText };
        String[] sourceNodes = new String[4], translatedNodes = new String[4];
        BufferedImage before = canvas(W, H, PAGE), after = canvas(W, H, PAGE);
        for (int i = 0; i < 4; i++) {
            int y = 100 + 70 * i, right = i == 3 ? box : 420;
            sourceNodes[i] = label(i, sources[i], 20, y, right, y + 50);
            translatedNodes[i] = label(i, translations[i], 20, y, right, y + 50);
            text(before, Color.WHITE, sources[i], 20, y + 5, y + 45, 12, right);
            text(after, Color.WHITE, translations[i], 20, y + 5, y + 45, 13, i == 3 ? clip : right);
        }
        return compare(screen(sourceNodes), before, screen(translatedNodes), after);
    }

    @Test
    void aTextCutAtTheSideOfItsBoxIsSevere() throws Exception {
        JSONObject report = cut("Select Preset", "Seleccionar predeterminado", 260, 260);

        assertEquals(List.of("textCut"), types(report, 4));
        assertEquals("textCut", finding(report, 4).get("kind"));
        assertEquals("severe", finding(report, 4).get("severity"));
        assertTrue(((Number) finding(report, 4).get("neededWidth")).longValue() > 240);
        assertEquals(1, summary(report, "textCut"));
        assertEquals(1, count(report, "amIPFs"), "the texts that fit are no finding");
    }

    /** The words that fit are drawn and the rest went to a line the box hides. */
    @Test
    void aTextWhoseLastWordsAreOnAHiddenLineIsCut() throws Exception {
        JSONObject report = cut("Select Preset", "Elegir el ajuste predeterminado", 260, 20 + 16 * 13);

        assertEquals(List.of("textCut"), types(report, 4));
    }

    @Test
    void aLongerTextThatFitsIsNotCut() throws Exception {
        assertNull(finding(cut("Select", "Elegir uno", 260, 260), 4));
    }
}

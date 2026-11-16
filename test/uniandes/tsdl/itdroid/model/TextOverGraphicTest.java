package uniandes.tsdl.itdroid.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Text drawn over a graphic that is not an element, on hand-drawn screenshots: a drop-down list
 * (800 x 120, purple) whose text view spans its whole width and whose arrow, white, is part of its
 * background. The text is drawn as black strokes, like letters.
 */
class TextOverGraphicTest {

    private static final int W = 1000, H = 600;
    private static final Color SCREEN = new Color(220, 220, 220), LIST = new Color(150, 90, 200);

    @TempDir
    Path tmp;

    @AfterEach
    void restore() {
        LayoutGraphComparision.setTextOverGraphic(true);
    }

    /** The dump: the root, the drop-down at [100,100][900,220] and its text at [100,120][900,200]; optionally the arrow as an element. */
    private static String dump(String text, boolean arrowIsAnElement) {
        String arrow = arrowIsAnElement
                ? "<node index=\"1\" text=\"\" resource-id=\"\" class=\"android.widget.ImageView\" package=\"org.example\" content-desc=\"\" bounds=\"[798,138][862,182]\"></node>"
                : "";
        return "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">"
                + "<node index=\"0\" text=\"\" resource-id=\"\" class=\"android.widget.FrameLayout\" package=\"org.example\" content-desc=\"\" bounds=\"[0,0][1000,600]\">"
                + "<node index=\"0\" text=\"\" resource-id=\"\" class=\"android.widget.Spinner\" package=\"org.example\" content-desc=\"\" bounds=\"[100,100][900,220]\">"
                + "<node index=\"0\" text=\"" + text + "\" resource-id=\"\" class=\"android.widget.TextView\" package=\"org.example\" content-desc=\"\" bounds=\"[100,120][900,200]\"></node>"
                + arrow + "</node></node></hierarchy>";
    }

    /** The drop-down with its arrow, and strokes of text 3 px wide every 12 px from x1 to x2, between y1 and y2. */
    private static BufferedImage shot(int x1, int x2, int y1, int y2) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(SCREEN);
        g.fillRect(0, 0, W, H);
        g.setColor(LIST);
        g.fillRect(100, 100, 800, 120);
        g.setColor(Color.WHITE);
        g.fillPolygon(new int[] { 800, 860, 830 }, new int[] { 140, 140, 180 }, 3);
        g.setColor(Color.BLACK);
        for (int x = x1; x <= x2; x += 12) {
            g.fillRect(x, y1, 3, y2 - y1);
        }
        g.dispose();
        return img;
    }

    private static List<TextOverGraphic.Hit> find(String sourceDump, BufferedImage sourceShot, String languageDump, BufferedImage languageShot) throws Exception {
        State source = new State(1, ".Main", sourceDump, "");
        State language = new State(1, ".Main", languageDump, "");
        return TextOverGraphic.find(source, sourceShot, language, languageShot, NodePairing.of(source, language));
    }

    @Test
    void textRunningOverTheArrowOfADropDownIsFound() throws Exception {
        // source: the text on the left, far from the arrow; translated: aligned to the right, over it
        List<TextOverGraphic.Hit> hits = find(dump("Select preset", false), shot(120, 400, 145, 175),
                dump("Elegir", false), shot(640, 860, 150, 170));

        assertEquals(1, hits.size());
        TextOverGraphic.Hit hit = hits.get(0);
        assertEquals(2, hit.sourceNode, "reported on the text view");
        int[] g = hit.graphic;
        assertTrue(g[0] >= 790 && g[0] <= 805 && g[2] >= 855 && g[2] <= 870 && g[1] >= 130 && g[3] <= 190,
                "the graphic is the arrow: " + java.util.Arrays.toString(g));
    }

    @Test
    void textThatKeepsAwayFromTheArrowIsNot() throws Exception {
        assertTrue(find(dump("Select preset", false), shot(120, 400, 145, 175), dump("Elegir", false), shot(125, 405, 150, 170)).isEmpty());
    }

    @Test
    void aLetterThatStaysWhereItWasIsNotAGraphic() throws Exception {
        // the same strokes in both, and a bar drawn across them in the translation: letters next to
        // letters, which a graphic would not have
        BufferedImage translated = shot(120, 400, 145, 175);
        Graphics2D g = translated.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(118, 158, 290, 5);
        g.dispose();

        assertTrue(find(dump("Select preset", false), shot(120, 400, 145, 175), dump("Elegir", false), translated).isEmpty());
    }

    @Test
    void aGraphicThatIsAnElementIsLeftToTheComparisonOfBoxes() throws Exception {
        assertTrue(find(dump("Select preset", true), shot(120, 400, 145, 175), dump("Elegir", true), shot(640, 860, 150, 170)).isEmpty());
    }

    @Test
    void withoutBothScreenshotsThereIsNothingToLookAt() throws Exception {
        assertTrue(find(dump("Select preset", false), null, dump("Elegir", false), shot(640, 860, 150, 170)).isEmpty());
    }

    // ------------------------------------------------------------------ in the comparison and the report

    @SuppressWarnings("unchecked")
    private static Path exploration(Path folder, boolean replay, String dump, BufferedImage shot) throws Exception {
        Files.createDirectories(folder.resolve("screenshots"));
        ImageIO.write(shot, "png", folder.resolve("screenshots").resolve("state_1.png").toFile());
        JSONObject s = new JSONObject();
        s.put("id", 1L);
        if (replay) {
            s.put("sourceStateId", 1L);
        }
        s.put("activityName", ".Main");
        s.put("rawXML", dump);
        s.put("screenShot", "screenshots/state_1.png");
        JSONObject states = new JSONObject();
        states.put("1", s);
        JSONObject root = new JSONObject();
        root.put("amountStates", 1L);
        root.put("amountTransitions", 0L);
        root.put("states", states);
        root.put("transitions", new JSONObject());
        Files.writeString(folder.resolve("result.json"), root.toJSONString(), StandardCharsets.UTF_8);
        return folder;
    }

    private JSONObject compare() throws Exception {
        return TestGraphs.compare(exploration(tmp.resolve("en"), false, dump("Select preset", false), shot(120, 400, 145, 175)),
                exploration(tmp.resolve("es"), true, dump("Elegir", false), shot(640, 860, 150, 170)), tmp.resolve("out"));
    }

    @Test
    void theReportCarriesItAsAFindingOfItsOwnKind() throws Exception {
        JSONObject report = compare();

        assertEquals(1, TestGraphs.count(report, "amIPFs"), "nothing moved: the only finding is the text over the arrow");
        assertEquals(1, TestGraphs.summary(report, "textOverGraphic"));
        assertEquals(1, TestGraphs.ofType(report, "textOverGraphic"));
        assertEquals(0, TestGraphs.summary(report, "nodesChanged") + TestGraphs.summary(report, "nodesAffected"));
        JSONObject f = TestGraphs.finding(report, 2);
        assertEquals("textOverGraphic", f.get("kind"));
        assertEquals(List.of("textOverGraphic"), TestGraphs.types(report, 2));
        JSONArray graphics = (JSONArray) f.get("graphics");
        assertEquals(1, graphics.size());
        assertEquals(4, ((JSONArray) graphics.get(0)).size());
    }

    @Test
    void itCanBeTurnedOff() throws Exception {
        LayoutGraphComparision.setTextOverGraphic(false);

        JSONObject report = compare();

        assertEquals(0, TestGraphs.count(report, "amIPFs"));
        assertEquals(0, TestGraphs.summary(report, "textOverGraphic"));
    }
}

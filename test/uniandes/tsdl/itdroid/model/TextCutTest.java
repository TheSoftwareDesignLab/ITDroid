package uniandes.tsdl.itdroid.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uniandes.tsdl.itdroid.model.TestGraphs.canvas;
import static uniandes.tsdl.itdroid.model.TestGraphs.fill;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** How a text is measured in its box, on hand-drawn boxes of 300 x 100. */
class TextCutTest {

    private static final int[] BOX = { 0, 0, 300, 100 };

    /** A drop-down filled with a gradient, darker row by row. */
    private static BufferedImage gradient() {
        BufferedImage img = canvas(300, 100, Color.BLACK);
        for (int y = 0; y < 100; y++) {
            fill(img, new Color(230 - y / 2, 170 - y / 2, 240 - y / 2), 0, y, 300, y + 1);
        }
        return img;
    }

    /** A bold text covers most of its rows, and the background is followed down the gradient all the same. */
    @Test
    void aBoldTextOverAGradientIsMeasuredAsTheText() {
        BufferedImage img = gradient();
        fill(img, Color.WHITE, 40, 30, 240, 70);

        TextCut.Drawn d = TextCut.drawn(new Ink(img), BOX);
        assertTrue(TextCut.settle(d, null));

        assertEquals(40, d.start, TextCut.describe(d));
        assertEquals(239, d.end, TextCut.describe(d));
        assertTrue(d.oneLine);
    }

    /** The outline and the arrow are drawn the same in both screens, the text is not. */
    @Test
    void theOutlineAndTheArrowOfTheBoxAreNotText() {
        BufferedImage before = gradient(), after = gradient();
        for (BufferedImage img : new BufferedImage[] { before, after }) {
            fill(img, Color.DARK_GRAY, 0, 0, 2, 100);
            fill(img, Color.DARK_GRAY, 298, 0, 300, 100);
            fill(img, Color.WHITE, 250, 40, 275, 60);
        }
        fill(before, Color.WHITE, 20, 30, 120, 70);
        fill(after, Color.WHITE, 20, 30, 210, 70);

        TextCut.Drawn s = TextCut.drawn(new Ink(before), BOX), l = TextCut.drawn(new Ink(after), BOX);
        assertTrue(TextCut.settle(s, l));
        assertTrue(TextCut.settle(l, s));

        assertEquals(20, l.start, TextCut.describe(l));
        assertEquals(209, l.end, TextCut.describe(l));
        assertEquals(119, s.end, TextCut.describe(s));
        assertEquals(3, l.others.size(), "the two sides of the outline and the arrow: " + TextCut.describe(l));
    }

    @Test
    void marksDrawnOverALetterAndInvisibleMarksTakeNoRoom() {
        assertEquals(5, TextCut.chars("Hello"));
        assertEquals(1, TextCut.chars("é"));
        assertEquals(3, TextCut.chars("‫abc"));
        assertEquals(4, TextCut.chars("नमस्ते"), "the virama and the vowel sign over a letter take no room");
    }

    private static TextCut.Sample fits(int sourceWidth, int sourceChars, int languageWidth, int languageChars) {
        TextCut.Sample m = new TextCut.Sample();
        m.sourceWidth = sourceWidth;
        m.sourceChars = sourceChars;
        m.languageWidth = languageWidth;
        m.languageChars = languageChars;
        m.sourceOneLine = true;
        m.languageOneLine = true;
        m.sourceGap = 50;
        m.languageGap = 50;
        return m;
    }

    @Test
    void theWidthOfALetterOfTheLanguageIsLearnedFromThreeTextsThatFit() {
        List<TextCut.Sample> samples = new ArrayList<>(List.of(fits(100, 10, 120, 10), fits(100, 10, 110, 10)));

        assertTrue(Double.isNaN(TextCut.ratio(samples)), "two are too few");

        samples.add(fits(100, 10, 130, 10));
        assertEquals(1.2, TextCut.ratio(samples), 1e-9);
        assertTrue(TextCut.cuts(samples, TextCut.ratio(samples)).isEmpty());
    }

    @Test
    void aTextDrawnShorterThanItNeedsAndReachingTheEndOfItsBoxIsCut() {
        TextCut.Sample m = fits(100, 10, 200, 30);
        m.keptBox = true;
        m.room = 202;
        m.languageGap = 1;
        m.languageText = "a text of thirty letters ......";

        List<TextCut.Hit> hits = TextCut.cuts(List.of(m), 1.0);

        assertEquals(1, hits.size());
        assertEquals(300, hits.get(0).needed);
        assertEquals(200, hits.get(0).drawn);
        // with room to spare at its end and not as wide as the words that fit, what is drawn is no cut text
        m.languageGap = 80;
        assertTrue(TextCut.cuts(List.of(m), 1.0).isEmpty());
        // nor is a text that needs less than its room
        m.languageGap = 1;
        m.languageChars = 18;
        assertTrue(TextCut.cuts(List.of(m), 1.0).isEmpty());
    }

    /**
     * reReminder, Alarm in Russian. The box of the text grew until the side of its button and shows
     * the first two words, the third went to a line the box hides. The source box fitted its text.
     */
    @Test
    void aTextWhoseBoxGrewUntilTheSideOfItsButtonCanBeCut() {
        TextCut.Sample m = fits(96, 5, 263, 24);
        m.languageText = "Уведомление о будильнике";
        m.sourceGap = 3;
        m.languageGap = 100;
        m.room = 363;
        m.keptBox = true;
        m.bounded = true;

        List<TextCut.Hit> hits = TextCut.cuts(List.of(m), 1.16);

        assertEquals(1, hits.size());
        assertFalse(m.reference(), "its width per letter says nothing of the language");
        // a box that only fitted its text, with no side that stopped it, is not judged
        m.bounded = false;
        assertTrue(TextCut.cuts(List.of(m), 1.16).isEmpty());
    }
}

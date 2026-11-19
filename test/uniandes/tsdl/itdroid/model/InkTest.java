package uniandes.tsdl.itdroid.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The box of what an element draws, read from a hand-drawn screenshot. */
class InkTest {

    private static final Color PAGE = new Color(30, 30, 60), CARD = new Color(230, 230, 240);

    private static BufferedImage page() {
        BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(PAGE);
        g.fillRect(0, 0, 400, 300);
        g.dispose();
        return img;
    }

    private static void fill(BufferedImage img, Color c, int x1, int y1, int x2, int y2) {
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(x1, y1, x2 - x1, y2 - y1);
        g.dispose();
    }

    @Test
    void theInkOfABoxIsWhatDiffersFromItsBackground() {
        BufferedImage img = page();
        fill(img, Color.WHITE, 120, 40, 160, 60);   // a short text in a wide box

        int[] ink = new Ink(img).box(new int[] {100, 30, 300, 70});

        assertArrayEquals(new int[] {120, 40, 160, 60}, ink);
    }

    @Test
    void aBoxThatShowsOnlyItsBackgroundDrawsNothing() {
        assertNull(new Ink(page()).box(new int[] {100, 30, 300, 70}));
    }

    @Test
    void aStrayPixelIsNotADrawing() {
        BufferedImage img = page();
        img.setRGB(150, 50, Color.WHITE.getRGB());

        assertNull(new Ink(img).box(new int[] {100, 30, 300, 70}));
    }

    @Test
    void theBackgroundIsTheColourOfMostOfTheBorder() {
        BufferedImage img = page();
        fill(img, CARD, 50, 50, 250, 150);   // a card
        fill(img, Color.BLACK, 60, 60, 100, 80);   // its text

        Ink ink = new Ink(img);

        assertEquals(CARD.getRGB() & 0xffffff, ink.background(new int[] {50, 50, 250, 150}));
        assertArrayEquals(new int[] {60, 60, 100, 80}, ink.box(new int[] {50, 50, 250, 150}));
        assertEquals(PAGE.getRGB() & 0xffffff, ink.surroundings(new int[] {50, 50, 250, 150}, 2));
    }

    @Test
    void theHolesAreLeftOut() {
        BufferedImage img = page();
        fill(img, Color.WHITE, 120, 40, 160, 60);

        assertNull(new Ink(img).box(new int[] {100, 30, 300, 70}, List.of(new int[] {110, 35, 170, 65})));
    }

    @Test
    void aBoxPartlyOffTheScreenshotIsCutToIt() {
        BufferedImage img = page();
        fill(img, Color.WHITE, 380, 10, 400, 20);

        assertArrayEquals(new int[] {380, 10, 400, 20}, new Ink(img).box(new int[] {350, 0, 500, 40}));
        assertNull(new Ink(img).box(new int[] {500, 0, 600, 40}));
    }

    @Test
    void withoutAScreenshotTheBoxStandsForWhatIsDrawn() {
        Ink none = new Ink(null);

        assertFalse(none.available());
        assertArrayEquals(new int[] {10, 10, 50, 50}, none.box(new int[] {10, 10, 50, 50}));
        assertNull(none.box(new int[] {10, 10, 10, 50}), "an empty box draws nothing");
        assertEquals(-1, none.background(new int[] {10, 10, 50, 50}));
    }

    @Test
    void pixelsUnlikeEveryColourAreCounted() {
        BufferedImage img = page();
        fill(img, CARD, 0, 0, 100, 100);
        fill(img, Color.RED, 40, 40, 50, 50);

        Ink ink = new Ink(img);

        assertEquals(100, ink.count(new int[] {0, 0, 200, 200}, PAGE.getRGB() & 0xffffff, CARD.getRGB() & 0xffffff));
        assertTrue(Ink.distance(Color.RED.getRGB(), Color.BLACK.getRGB()) > Ink.INK);
    }
}

package uniandes.tsdl.itdroid.explorer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * What the explorer decides without a device. A screen is known by its signature: one entry per
 * element (resource id, class, description, whether it shows text), each closed by a semicolon.
 */
class AdbExplorerTest {

    /** The settings of a currency converter: a title, a "Sync Now" row with an empty line of status under it, another row. */
    private static final String SETTINGS = ":FrameLayout;:View;:TextViewt;:View[Sync Now];:TextView;:View;:TextViewt;";

    /**
     * The case that asked for it: after "Sync Now" the line of status says "Sync completed
     * successfully" for three seconds. Same elements, one of them with a text it did not have.
     */
    @Test
    void aScreenWithALineThatChangedLooksLikeAMessage() {
        String withStatus = ":FrameLayout;:View;:TextViewt;:View[Sync Now];:TextViewt;:View;:TextViewt;";

        assertTrue(AdbExplorer.differsLittle(SETTINGS, withStatus));
    }

    @Test
    void soDoesAScreenWithAFewElementsDrawnOverIt() {
        String withSnackbar = SETTINGS + ":View;:TextViewt;:Button[Undo];";
        String withBannerInTheMiddle = ":FrameLayout;:View;:TextViewt;:View;:TextViewt;:View[Sync Now];:TextView;:View;:TextViewt;";

        assertTrue(AdbExplorer.differsLittle(SETTINGS, withSnackbar));
        assertTrue(AdbExplorer.differsLittle(SETTINGS, withBannerInTheMiddle), "wherever in the tree the message is drawn");
    }

    @Test
    void anotherScreenDoesNot() {
        String picker = ":FrameLayout;:EditText;:View[Clear];:ImageView;:ImageView;:ImageView;:ImageView;:ImageView;:ImageView;";
        String muchMore = SETTINGS + ":View;:TextViewt;:View;:TextViewt;:View;:TextViewt;";

        assertFalse(AdbExplorer.differsLittle(SETTINGS, SETTINGS), "the same screen does not differ");
        assertFalse(AdbExplorer.differsLittle(SETTINGS, picker), "six of the seven elements are gone");
        assertFalse(AdbExplorer.differsLittle(SETTINGS, muchMore), "six elements more is a part of the screen that opened, not a message");
        assertFalse(AdbExplorer.differsLittle(SETTINGS, ":FrameLayout;"), "nearly everything is gone");
    }
}

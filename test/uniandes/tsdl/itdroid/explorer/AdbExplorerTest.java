package uniandes.tsdl.itdroid.explorer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests what the explorer decides without a device. A screen signature has one entry per element,
 * each closed by a semicolon.
 */
class AdbExplorerTest {

    /** Settings of a currency converter with a title, a Sync Now row over an empty status line, and another row. */
    private static final String SETTINGS = ":FrameLayout;:View;:TextViewt;:View[Sync Now];:TextView;:View;:TextViewt;";

    /** After Sync Now the status line shows a text for three seconds, so one element gains text. */
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

    /** An app that overwrites the system language on its first start leaves the locale list empty. */
    @Test
    void anAppThatTookTheLanguageAwayIsSeenInTheAnswerOfTheSystem() {
        assertTrue(AdbExplorer.holdsLanguage("Locales for org.tasks for user 0 are [ru]", "ru"));
        assertFalse(AdbExplorer.holdsLanguage("Locales for org.tasks for user 0 are []", "ru"), "the app emptied it");
        assertFalse(AdbExplorer.holdsLanguage("Locales for org.tasks for user 0 are [de]", "ru"), "the app chose another");
    }

    @Test
    void theLanguageIsComparedAsAndroidWritesIt() {
        assertTrue(AdbExplorer.holdsLanguage("Locales for org.example for user 0 are [pt_BR]", "pt-BR"));
        assertTrue(AdbExplorer.holdsLanguage("Locales for org.example for user 0 are [pt_BR,en_US]", "pt"));
        assertTrue(AdbExplorer.holdsLanguage("Locales for org.example for user 0 are [iw]", "he"), "Hebrew under its old code");
        assertTrue(AdbExplorer.holdsLanguage("Locales for org.example for user 0 are [in_ID]", "id"), "Indonesian under its old code");
    }

    @Test
    void anAnswerThatCannotBeReadDoesNotRestartTheApp() {
        assertTrue(AdbExplorer.holdsLanguage(null, "ru"));
        assertTrue(AdbExplorer.holdsLanguage("", "ru"));
        assertTrue(AdbExplorer.holdsLanguage("Error: unknown command 'get-app-locales'", "ru"));
    }
}

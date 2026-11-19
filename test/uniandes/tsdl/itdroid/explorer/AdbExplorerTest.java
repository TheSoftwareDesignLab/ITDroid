package uniandes.tsdl.itdroid.explorer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

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

        assertTrue(Screen.differsLittle(SETTINGS, withStatus));
    }

    @Test
    void soDoesAScreenWithAFewElementsDrawnOverIt() {
        String withSnackbar = SETTINGS + ":View;:TextViewt;:Button[Undo];";
        String withBannerInTheMiddle = ":FrameLayout;:View;:TextViewt;:View;:TextViewt;:View[Sync Now];:TextView;:View;:TextViewt;";

        assertTrue(Screen.differsLittle(SETTINGS, withSnackbar));
        assertTrue(Screen.differsLittle(SETTINGS, withBannerInTheMiddle), "wherever in the tree the message is drawn");
    }

    @Test
    void anotherScreenDoesNot() {
        String picker = ":FrameLayout;:EditText;:View[Clear];:ImageView;:ImageView;:ImageView;:ImageView;:ImageView;:ImageView;";
        String muchMore = SETTINGS + ":View;:TextViewt;:View;:TextViewt;:View;:TextViewt;";

        assertFalse(Screen.differsLittle(SETTINGS, SETTINGS), "the same screen does not differ");
        assertFalse(Screen.differsLittle(SETTINGS, picker), "six of the seven elements are gone");
        assertFalse(Screen.differsLittle(SETTINGS, muchMore), "six elements more is a part of the screen that opened, not a message");
        assertFalse(Screen.differsLittle(SETTINGS, ":FrameLayout;"), "nearly everything is gone");
    }

    /** A main screen of depth, id and class entries, as the replay compares screens across builds. */
    private static final String MAIN = "0::FrameLayout;1:app:id/content:FrameLayout;2:app:id/scroll:ScrollView;3::LinearLayout;"
            + "4:app:id/title:TextView;4:app:id/btnSet:Button;4:app:id/btnUnlock:Button;4:app:id/heading:TextView;"
            + "4:app:id/btnPromo:Button;4:app:id/spinner:Spinner;4:app:id/footer:TextView;";

    /** A longer text pushes the last row out of the scroll, which is still the same screen. */
    @Test
    void aTranslatedScreenWithARowPushedOutOfViewIsTheSameScreen() {
        String translated = MAIN.substring(0, MAIN.lastIndexOf("4:app:id/footer"));

        assertTrue(Screen.sameScreen(MAIN, translated));
        assertTrue(Screen.sameScreen(MAIN, MAIN));
    }

    @Test
    void aDialogOrAnotherScreenIsNot() {
        String dialog = "0::FrameLayout;1:android:id/content:FrameLayout;2:android:id/message:TextView;2:android:id/button1:Button;";

        assertFalse(Screen.sameScreen(MAIN, dialog));
    }

    @Test
    void theSameElementsAtAnotherDepthAreAnotherTree() {
        String flat = MAIN.replaceAll("(?<=^|;)\\d+:", "0:");

        assertTrue(Screen.structureSimilarity(MAIN, flat) < Screen.SAME_SCREEN_SHARE);
    }

    @Test
    void aScreenRecordedWithoutStructureIsNotRejected() {
        assertTrue(Screen.sameScreen("", MAIN));
        assertTrue(Screen.sameScreen(null, MAIN));
    }

    /** An app that overwrites the system language on its first start leaves the locale list empty. */
    @Test
    void anAppThatTookTheLanguageAwayIsSeenInTheAnswerOfTheSystem() {
        assertTrue(Device.holdsLanguage("Locales for org.tasks for user 0 are [ru]", "ru"));
        assertFalse(Device.holdsLanguage("Locales for org.tasks for user 0 are []", "ru"), "the app emptied it");
        assertFalse(Device.holdsLanguage("Locales for org.tasks for user 0 are [de]", "ru"), "the app chose another");
    }

    @Test
    void theLanguageIsComparedAsAndroidWritesIt() {
        assertTrue(Device.holdsLanguage("Locales for org.example for user 0 are [pt_BR]", "pt-BR"));
        assertTrue(Device.holdsLanguage("Locales for org.example for user 0 are [pt_BR,en_US]", "pt"));
        assertTrue(Device.holdsLanguage("Locales for org.example for user 0 are [iw]", "he"), "Hebrew under its old code");
        assertTrue(Device.holdsLanguage("Locales for org.example for user 0 are [in_ID]", "id"), "Indonesian under its old code");
    }

    @Test
    void anAnswerThatCannotBeReadDoesNotRestartTheApp() {
        assertTrue(Device.holdsLanguage(null, "ru"));
        assertTrue(Device.holdsLanguage("", "ru"));
        assertTrue(Device.holdsLanguage("Error: unknown command 'get-app-locales'", "ru"));
    }

    // ------------------------------------------------------------------ one screen or another

    /** reReminder marks the colour of a new reminder with a description, which moves to the circle tapped. */
    @Test
    void aSelectionThatMovesKeepsTheScreen() {
        String first = ":View;:View[Color # selected];:View;:View;:TextViewt;";
        String third = ":View;:View;:View;:View[Color # selected];:TextViewt;";

        assertTrue(Screen.sameState(first, third));
        assertFalse(Screen.sameState(first, ":View;:View[Color # selected];:View;:TextViewt;"), "a circle less is another screen");
        assertFalse(Screen.sameState(first, ":View;:View[Color # selected];:View;:View;:TextViewt;:Button;"));
    }

    /** The pages of a presentation have the same elements, only their title and their text change. */
    @Test
    void aPageOfAPresentationIsAnotherScreen() {
        List<String> welcome = List.of("Welcome to reReminder", "Reminders that repeat on a loop, as often as you need", "Skip", "Next");
        List<String> second = List.of("Never miss one", "An alarm rings at the exact time, even with the phone asleep", "Skip", "Next");

        assertTrue(Screen.otherTexts(welcome, second));
        assertFalse(Screen.otherTexts(welcome, welcome));
    }

    @Test
    void aLabelThatChangesOrAFigureThatCountsIsTheSameScreen() {
        List<String> playing = List.of("Thriller", "Michael Jackson", "Pause", "Next: in # min");
        List<String> paused = List.of("Thriller", "Michael Jackson", "Play", "Next: in # min");
        List<String> status = List.of("Sync now", "Settings", "Language", "Currency", "Rates of the European Central Bank");
        List<String> synced = List.of("Sync now", "Settings", "Language", "Currency", "Rates of the European Central Bank", "Updated");

        assertFalse(Screen.otherTexts(playing, paused), "a button that changes its label");
        assertFalse(Screen.otherTexts(status, synced), "a short status line");
    }
}

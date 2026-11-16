package uniandes.tsdl.itdroid.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uniandes.tsdl.itdroid.model.TestGraphs.compare;
import static uniandes.tsdl.itdroid.model.TestGraphs.writeResult;

import java.nio.file.Path;
import java.util.Set;

import org.json.simple.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests whether a translated version showed other text than the source, which tells "no findings
 * because the translation fits" from "no findings because nothing was translated on screen".
 */
class ScreenTextsTest {

    @TempDir
    Path tmp;

    /** A screen with one label per text, one under the other. The desc prefix makes a content description and field an edit field. */
    private static String screen(String... labels) {
        StringBuilder nodes = new StringBuilder();
        for (int i = 0; i < labels.length; i++) {
            boolean description = labels[i].startsWith("desc:");
            boolean field = labels[i].startsWith("field:");
            String value = description || field ? labels[i].substring(labels[i].indexOf(':') + 1) : labels[i];
            nodes.append("<node index=\"").append(i).append("\" text=\"").append(description ? "" : value)
                    .append("\" resource-id=\"\" class=\"").append(field ? "android.widget.EditText" : "android.widget.TextView")
                    .append("\" package=\"org.example\" content-desc=\"").append(description ? value : "")
                    .append("\" clickable=\"false\" enabled=\"true\" bounds=\"[0,").append(i * 100).append("][1000,")
                    .append(i * 100 + 90).append("]\"></node>");
        }
        return "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">"
                + "<node index=\"0\" text=\"\" resource-id=\"\" class=\"android.widget.FrameLayout\" package=\"org.example\""
                + " content-desc=\"\" clickable=\"false\" enabled=\"true\" bounds=\"[0,0][1000,2000]\">" + nodes + "</node></hierarchy>";
    }

    private static State state(String xml) throws Exception {
        return new State(1, ".Main", xml, "");
    }

    @Test
    void aTextIsWhatAnElementShowsOrAnnouncesAndHasAWord() throws Exception {
        Set<String> texts = ScreenTexts.of(state(screen("Settings", "  Save changes ", "desc:Close menu", "12:45", "4.50", "+", "",
                "Настройки", "field:typed by the explorer")));

        assertEquals(Set.of("Settings", "Save changes", "Close menu", "Настройки"), texts,
                "figures, symbols and what was typed into a field are not text of the app");
    }

    @Test
    void aTranslatedScreenKeepsFewOfTheSourceTexts() throws Exception {
        ScreenTexts texts = new ScreenTexts();
        texts.add(state(screen("Settings", "Save", "Cancel", "Language", "About", "ITDroid")),
                state(screen("Ajustes", "Guardar", "Cancelar", "Idioma", "Acerca de", "ITDroid")));

        assertEquals(6, texts.sourceTexts());
        assertEquals(1, texts.unchanged(), "only the name of the app stays");
        assertFalse(texts.sameAsSource());
    }

    @Test
    void aScreenThatShowsTheSourceTextIsReportedAsSuch() throws Exception {
        ScreenTexts texts = new ScreenTexts();
        String english = screen("Add account", "Continue without sync", "Import backup", "desc:Open menu", "Sign in");
        texts.add(state(english), state(english));

        assertEquals(5, texts.sourceTexts());
        assertEquals(5, texts.unchanged());
        assertTrue(texts.sameAsSource());
        JSONObject json = texts.toJson();
        assertEquals(5L, json.get("sourceTexts"));
        assertEquals(5L, json.get("unchanged"));
        assertEquals(1.0, json.get("share"));
        assertEquals(true, json.get("sameAsSource"));
    }

    @Test
    void fewTextsAreNotJudged() throws Exception {
        ScreenTexts texts = new ScreenTexts();
        String english = screen("ITDroid", "USD", "EUR");
        texts.add(state(english), state(english));

        assertEquals(1.0, texts.share());
        assertFalse(texts.sameAsSource(), "a name and two units being the same says nothing");
    }

    @Test
    void theShareThatCountsAsTheSameTextIsNineInTen() throws Exception {
        String[] english = {"One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten"};
        String[] oneChanged = english.clone();
        oneChanged[0] = "Uno";
        String[] twoChanged = oneChanged.clone();
        twoChanged[1] = "Dos";

        ScreenTexts nine = new ScreenTexts();
        nine.add(state(screen(english)), state(screen(oneChanged)));
        assertTrue(nine.sameAsSource(), "9 of 10 unchanged: a screen of data, or a language that was not shown");

        ScreenTexts eight = new ScreenTexts();
        eight.add(state(screen(english)), state(screen(twoChanged)));
        assertFalse(eight.sameAsSource());
    }

    @Test
    void theTextsOfEveryComparedScreenAreCountedOnce() throws Exception {
        ScreenTexts texts = new ScreenTexts();
        texts.add(state(screen("Home", "Settings", "Help")), state(screen("Inicio", "Ajustes", "Help")));
        texts.add(state(screen("Settings", "Language", "Help")), state(screen("Ajustes", "Idioma", "Help")));

        assertEquals(4, texts.sourceTexts(), "Home, Settings, Help, Language");
        assertEquals(1, texts.unchanged(), "Help");
    }

    @Test
    void theComparisonOfALanguageSaysHowMuchTextStayedTheSame() throws Exception {
        String english = screen("Add account", "Continue without sync", "Import backup", "Terms of service", "Sign in");
        Path source = writeResult(tmp.resolve("en"), false, ".Main", english);

        JSONObject notShown = compare(source, writeResult(tmp.resolve("ru"), true, ".Main", english), tmp.resolve("out-ru"));
        JSONObject shownText = (JSONObject) notShown.get("screenText");
        assertEquals(0L, ((Number) notShown.get("amIPFs")).longValue(), "the same screen: no findings");
        assertEquals(true, shownText.get("sameAsSource"), "and the report says why: nothing was translated on it");
        assertEquals(5L, shownText.get("unchanged"));

        String spanish = screen("Añadir cuenta", "Continuar sin sincronizar", "Importar copia", "Condiciones del servicio", "Iniciar sesión");
        JSONObject shown = compare(source, writeResult(tmp.resolve("es"), true, ".Main", spanish), tmp.resolve("out-es"));
        JSONObject translatedText = (JSONObject) shown.get("screenText");
        assertEquals(false, translatedText.get("sameAsSource"));
        assertEquals(0L, translatedText.get("unchanged"));
        assertEquals(5L, translatedText.get("sourceTexts"));
    }

    // ------------------------------------------------------------------ texts the APK does not have

    @Test
    void aTextComesFromTheApkAsItIsWithItsPlaceholdersFilledOrInCapitals() {
        ScreenTexts.ApkTexts apk = new ScreenTexts.ApkTexts(java.util.List.of("Settings", "%d tasks", "\"Don\\'t ask again\"",
                "<b>Bold</b> move", "Wallpaper Priority: %1$s", "Presets", "Not now"));

        assertTrue(apk.has("Settings"));
        assertTrue(apk.has("3 tasks"), "a plural with its number");
        assertTrue(apk.has("Don't ask again"), "the quotes and escapes of a resource");
        assertTrue(apk.has("Bold move"), "its markup");
        assertTrue(apk.has("Wallpaper Priority: 90"));
        assertTrue(apk.has("NOT NOW"), "a button that shows its text in capitals");
        assertTrue(apk.has("Presets (PREVIEW)"), "made mostly of a text of the APK");
        assertFalse(apk.has("Try my other apps:"), "an ad that comes from the network");
        assertFalse(apk.has("Sun, Nov 15"), "a date");
        assertFalse(apk.has("Settings of my numbers game, the full list"), "a text of the APK that is a small part of it");
    }

    @Test
    void aFormatWithNoWordsOfItsOwnDoesNotMatchEveryText() {
        ScreenTexts.ApkTexts apk = new ScreenTexts.ApkTexts(java.util.List.of("%s", "%1$s: %2$s", "%d / %d", "Settings"));

        assertFalse(apk.has("Try my other apps:"));
        assertFalse(apk.has("A little ad for my numbers game: DIGOLUX"));
        assertTrue(apk.has("Settings"));
    }

    @Test
    void aTextOfSeveralLinesIsInTheApkWhenEachLineIs() {
        ScreenTexts.ApkTexts apk = new ScreenTexts.ApkTexts(java.util.List.of("Pick a place. Put your phone away.", "It rings when you arrive."));

        assertTrue(apk.has("Pick a place. Put your phone away.\nIt rings when you arrive."));
        assertFalse(apk.has("Pick a place. Put your phone away.\nAn ad that arrived from the network for this screen today."));
    }

    @Test
    void aTextInTheFilesOfTheApkIsInTheApk() {
        ScreenTexts.ApkTexts apk = new ScreenTexts.ApkTexts(java.util.List.of("Settings"), "const t={songs:\"songs\",openmenu:\"open menu\"};\n");

        assertTrue(apk.has("Open menu"), "a web interface keeps its texts in the assets");
        assertFalse(apk.has("Try my other apps:"));
    }

    @Test
    void theReportNamesTheUnchangedTextsTheApkDoesNotHave() throws Exception {
        ScreenTexts.setApkTexts(java.util.List.of("Settings", "Save", "Language"));
        try {
            ScreenTexts texts = new ScreenTexts();
            texts.add(state(screen("Settings", "Save", "Language", "Try my other apps:", "Gmail")),
                    state(screen("Ajustes", "Guardar", "Idioma", "Try my other apps:", "Gmail")));

            JSONObject o = texts.toJson();
            assertEquals(2L, o.get("unchanged"));
            assertEquals(2L, o.get("unchangedNotInApk"));
            assertEquals(java.util.List.of("Try my other apps:", "Gmail"), o.get("notInApkExamples"));
        } finally {
            ScreenTexts.setApkTexts(null);
        }
    }

    @Test
    void withoutTheTextsOfTheApkNothingIsSaidAboutThem() throws Exception {
        ScreenTexts texts = new ScreenTexts();
        texts.add(state(screen("Settings", "Try my other apps:")), state(screen("Ajustes", "Try my other apps:")));

        assertFalse(texts.toJson().containsKey("unchangedNotInApk"));
    }
}

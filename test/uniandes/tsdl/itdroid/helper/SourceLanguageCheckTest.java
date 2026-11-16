package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import uniandes.tsdl.itdroid.Fixtures;

/**
 * Tests the warning for an app whose default strings are not in the source language of the run.
 * Such an app is translated from the wrong language and the model answers with a transliteration.
 */
class SourceLanguageCheckTest {

    private static final List<String> ENGLISH = List.of("Settings", "Add a new reminder", "Ring at the exact time", "Keep running in the background",
            "Asks Android to stop putting the app to sleep", "Show notifications", "Let's make reminders stick", "You can change this later",
            "Delete this reminder?", "Nothing here yet", "Tap the button to add one", "Remind me every day");

    /** The first strings of the Esperanto dictionary app of the validation, whose default language is Esperanto. */
    private static final List<String> ESPERANTO = List.of("Serĉi lingvon", "Movu ilin por ŝanĝi ilian ordon.", "Aliaj lingvoj",
            "Tenu kaj movu por ŝanĝi la ordon", "Bonvenon! En kiuj lingvoj vi volas serĉi vortojn?", "Nenio troviĝis por tiu vorto.",
            "Aldonu aŭ forigu lingvojn per tuŝo", "Elektu la lingvojn de la vortaro", "Montri la artikolojn de la vorto",
            "Kopii la tradukon al la tondujo", "Malfermi la retpaĝon de la vortaro", "Pri tiu ĉi aplikaĵo");

    private static final List<String> SPANISH = List.of("Añadir una entrada nueva", "Guardar los cambios de la cuenta", "No hay nada que mostrar",
            "Elige la carpeta con tus audiolibros", "Se requiere el servidor para continuar", "Mostrar las notificaciones de la aplicación",
            "Toca el botón para añadir uno", "Puedes cambiar esto más tarde", "Eliminar este recordatorio de la lista",
            "Volver a la pantalla de inicio");

    @TempDir
    Path tmp;

    @Test
    void stringsInTheSourceLanguageRaiseNothing() {
        SourceLanguageCheck.Result english = SourceLanguageCheck.of(ENGLISH, "en");

        assertFalse(english.mismatch);
        assertEquals("", english.message());
        assertTrue(english.share >= 0.6, "share " + english.share);
        assertFalse(SourceLanguageCheck.of(SPANISH, "es").mismatch);
    }

    @Test
    void stringsInALanguageTheCheckDoesNotKnowAreReportedAsNotTheSourceLanguage() {
        SourceLanguageCheck.Result r = SourceLanguageCheck.of(ESPERANTO, "en");

        assertTrue(r.mismatch);
        assertTrue(r.share < 0.3, "share " + r.share);
        assertTrue(r.message().contains("do not look like 'en'") && r.message().contains("defaultLng"), r.message());
        assertEquals(true, r.toJson().get("mismatch"));
        assertEquals("en", r.toJson().get("configured"));
    }

    @Test
    void theCheckSaysWhatTheStringsAreNotAndDoesNotGuessWhatTheyAre() {
        SourceLanguageCheck.Result spanishAsEnglish = SourceLanguageCheck.of(SPANISH, "en");
        assertTrue(spanishAsEnglish.mismatch);
        assertFalse(spanishAsEnglish.toJson().containsKey("looksLike"));

        // Esperanto shares the "la", "de" and "por" of Spanish, so taken for Spanish it would pass
        assertFalse(SourceLanguageCheck.of(ESPERANTO, "es").mismatch, "which is why the check does not name a language");
    }

    @Test
    void fewSentencesOrAnUnknownSourceLanguageAreNotJudged() {
        assertFalse(SourceLanguageCheck.of(List.of("Buy", "Cancel", "Add to cart", "Check out", "Total: %1$d items"), "en").mismatch,
                "labels of one or two words have no little words in any language");
        assertFalse(SourceLanguageCheck.of(ESPERANTO, "eo").mismatch, "a source language the check has no words for");
        assertFalse(SourceLanguageCheck.of(ESPERANTO, null).mismatch);
    }

    @Test
    void readsTheDefaultStringsOfTheApp() throws Exception {
        Path strings = tmp.resolve("strings.xml");
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n");
        for (int i = 0; i < ESPERANTO.size(); i++) {
            xml.append("    <string name=\"s").append(i).append("\">").append(ESPERANTO.get(i)).append("</string>\n");
        }
        xml.append("    <string name=\"key\" translatable=\"false\">this is not text of the app to translate</string>\n</resources>\n");
        Files.writeString(strings, xml.toString(), StandardCharsets.UTF_8);

        SourceLanguageCheck.Result r = SourceLanguageCheck.of(strings.toString(), "en");
        assertTrue(r.mismatch);
        assertEquals(10, r.sentences, "the ten strings of three words or more; two labels of two words are not counted");

        assertFalse(SourceLanguageCheck.of(tmp.resolve("missing.xml").toString(), "en").mismatch, "an app without strings.xml has nothing to check");
        assertFalse(SourceLanguageCheck.of(Fixtures.decodedApp().resolve("res/values/strings.xml").toString(), "en").mismatch);
    }
}

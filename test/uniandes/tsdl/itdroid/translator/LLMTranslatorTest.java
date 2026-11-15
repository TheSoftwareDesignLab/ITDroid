package uniandes.tsdl.itdroid.translator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.FileUtils;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import uniandes.tsdl.itdroid.Fixtures;
import uniandes.tsdl.itdroid.helper.NotTranslatableStringsDictionary;
import uniandes.tsdl.itdroid.helper.StringInventory;

/**
 * The LLM translator against {@link FakeLlmServer}: no model, no network beyond localhost.
 *
 * <p>Tests named {@code knownGap_...} pin behaviour the audit found wrong: they assert what the code
 * does TODAY and name the TODO item that changes it; when that item is done the assertion flips.
 */
class LLMTranslatorTest {

    private static final String[] KEYS = {"ITDROID_LLM_ENDPOINT", "ITDROID_LLM_MODEL", "ITDROID_LLM_TIMEOUT",
            "ITDROID_LLM_TEMPERATURE", "ITDROID_LLM_STYLE", "ITDROID_LLM_MASK_PLACEHOLDERS", "ITDROID_LLM_PROMPT_TEMPLATE",
            "ITDROID_LLM_CONTEXT"};

    @TempDir
    Path tmp;

    private FakeLlmServer server;
    private Path settingsDir;
    private final Map<String, String> savedProperties = new LinkedHashMap<>();

    @BeforeEach
    void startTheFakeServer() throws IOException {
        server = new FakeLlmServer();
        // Its own settings folder with an empty .env, so a developer's real .env is never read.
        settingsDir = Files.createDirectories(tmp.resolve("settings"));
        Files.writeString(settingsDir.resolve(".env"), "");
        FileUtils.copyFile(Fixtures.settingsDir().resolve("strings.xml").toFile(), settingsDir.resolve("strings.xml").toFile());
        for (String k : KEYS) {
            savedProperties.put(k, System.getProperty(k));
        }
        System.setProperty("ITDROID_LLM_ENDPOINT", server.endpoint());
        System.setProperty("ITDROID_LLM_MODEL", "test-model");
        System.setProperty("ITDROID_LLM_TIMEOUT", "20");
        System.setProperty("ITDROID_LLM_TEMPERATURE", "0");
        System.setProperty("ITDROID_LLM_PROMPT_TEMPLATE", "Translate into {target}:\\n\\n{text}");
        useJsonStyle();
    }

    @AfterEach
    void stopTheFakeServer() {
        server.close();
        savedProperties.forEach((k, v) -> {
            if (v == null) {
                System.clearProperty(k);
            } else {
                System.setProperty(k, v);
            }
        });
    }

    /** Batches of texts in one JSON object: the style used with general chat models. */
    private static void useJsonStyle() {
        System.setProperty("ITDROID_LLM_STYLE", "json");
        System.setProperty("ITDROID_LLM_MASK_PLACEHOLDERS", "false");
    }

    /** One text per request in a prompt template: the style used with translation-only models. */
    private static void useTemplateStyle(boolean maskPlaceholders) {
        System.setProperty("ITDROID_LLM_STYLE", "template");
        System.setProperty("ITDROID_LLM_MASK_PLACEHOLDERS", String.valueOf(maskPlaceholders));
    }

    private LLMTranslator translator() {
        return new LLMTranslator(settingsDir.toString());
    }

    // ------------------------------------------------------------------ batched JSON style

    @Test
    void sendsABatchAndMapsEveryAnswerToItsText() {
        server.responder = r -> FakeLlmServer.translateItems(r, v -> "ES " + v);

        Map<String, String> out = translator().translateResourceTexts(List.of("Your cart", "Checkout now"), "en", "es");

        assertEquals(Map.of("Your cart", "ES Your cart", "Checkout now", "ES Checkout now"), out);
        assertEquals(1, server.requests.size());
        FakeLlmServer.Request request = server.requests.get(0);
        assertTrue(request.jsonMode);
        assertEquals("test-model", request.body.get("model"));
        assertTrue(request.system.contains("English") && request.system.contains("Spanish"), request.system);
    }

    @Test
    void splitsManyTextsIntoBatchesOfTwentyFive() {
        server.responder = r -> FakeLlmServer.translateItems(r, v -> "ES " + v);
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            texts.add("Label number " + i);
        }

        Map<String, String> out = translator().translateResourceTexts(texts, "en", "es");

        assertEquals(60, out.size());
        assertEquals(List.of(25, 25, 10), server.requests.stream().map(r -> r.items.size()).toList());
    }

    @Test
    void aTextAlreadyInTheInventoryIsNotSentAgain() throws Exception {
        server.responder = r -> FakeLlmServer.translateItems(r, v -> "ES " + v);
        StringInventory inventory = StringInventory.collect(Fixtures.decodedApp().toString(), "com.example.shop.free",
                new NotTranslatableStringsDictionary(settingsDir.toString()));
        LLMTranslator translator = new LLMTranslator(settingsDir.toString(), inventory);

        translator.translateResourceTexts(List.of("Your cart"), "en", "es");
        Map<String, String> second = translator.translateResourceTexts(List.of("Your cart", "Checkout now"), "en", "es");

        assertEquals("ES Your cart", second.get("Your cart"));
        assertEquals(2, server.requests.size());
        assertEquals(List.of("Checkout now"), List.copyOf(server.requests.get(1).items.values()));
        assertEquals("ES Your cart", inventory.translation("es", "Your cart"));
        assertNull(inventory.translation("fr", "Your cart"), "the memory is per language");
    }

    @Test
    void retriesWhatTheModelLeftOutOfItsAnswer() {
        AtomicInteger calls = new AtomicInteger();
        server.responder = r -> {
            Map<String, String> answer = new LinkedHashMap<>();
            boolean first = calls.getAndIncrement() == 0;
            r.items.forEach((k, v) -> {
                if (!(first && v.equals("Checkout now"))) {
                    answer.put(k, "ES " + v);
                }
            });
            return FakeLlmServer.jsonAnswer(answer);
        };

        Map<String, String> out = translator().translateResourceTexts(List.of("Your cart", "Checkout now"), "en", "es");

        assertEquals("ES Checkout now", out.get("Checkout now"));
        assertEquals(2, server.requests.size());
        assertEquals(List.of("Checkout now"), List.copyOf(server.requests.get(1).items.values()));
    }

    @Test
    void keepsTheSourceTextWhenTheModelNeverAnswersIt() {
        server.responder = r -> {
            Map<String, String> answer = new LinkedHashMap<>();
            r.items.forEach((k, v) -> {
                if (!v.equals("Checkout now")) {
                    answer.put(k, "ES " + v);
                }
            });
            return FakeLlmServer.jsonAnswer(answer);
        };

        Map<String, String> out = translator().translateResourceTexts(List.of("Your cart", "Checkout now"), "en", "es");

        assertEquals("Checkout now", out.get("Checkout now"));
        assertEquals("ES Your cart", out.get("Your cart"));
    }

    @Test
    void anAnswerThatIsNotJsonDoesNotAbortTheRun() {
        server.responder = r -> "Sure! Here are your translations.";

        Map<String, String> out = translator().translateResourceTexts(List.of("Your cart"), "en", "es");

        assertEquals(Map.of("Your cart", "Your cart"), out);
    }

    @Test
    void anHttpErrorDoesNotAbortTheRun() {
        server.responder = r -> {
            throw new FakeLlmServer.HttpError(500);
        };

        Map<String, String> out = translator().translateResourceTexts(List.of("Your cart"), "en", "es");

        assertEquals(Map.of("Your cart", "Your cart"), out);
    }

    /**
     * A translation without the {@code %1$s} of its source makes the app crash when it formats it.
     * In the batched style, as in the others, such an answer is rejected, asked for again and
     * finally the source text is kept.
     */
    @Test
    void theBatchedStyleRejectsATranslationThatLostItsPlaceholder() {
        server.responder = r -> FakeLlmServer.translateItems(r, v -> v.startsWith("Hello") ? "Hola" : "ES " + v);

        Map<String, String> out = translator().translateResourceTexts(List.of("Hello %1$s", "Saved"), "en", "es");

        assertEquals("Hello %1$s", out.get("Hello %1$s"), "kept in the source language");
        assertEquals("ES Saved", out.get("Saved"), "the rest of the batch is not affected");
        assertEquals(2, server.requests.size(), "the batch and one retry of the rejected text");
        assertEquals(List.of("Hello %1$s"), List.copyOf(server.requests.get(1).items.values()));
    }

    // ------------------------------------------------------------------ one text per request

    @Test
    void theTemplateStyleSendsOneRequestPerText() {
        useTemplateStyle(true);
        server.responder = r -> "ES " + r.text;

        Map<String, String> out = translator().translateResourceTexts(List.of("Your cart", "Checkout now", "Saved"), "en", "es");

        assertEquals("ES Your cart", out.get("Your cart"));
        assertEquals(3, server.requests.size());
        assertTrue(server.requests.stream().noneMatch(r -> r.jsonMode));
        assertTrue(server.requests.stream().allMatch(r -> r.user.startsWith("Translate into Spanish:")));
    }

    @Test
    void placeholdersTravelMaskedAndComeBackRestored() {
        useTemplateStyle(true);
        server.responder = r -> r.text.replace("Hello", "Hola").replace("you have", "tienes").replace("items", "artículos");

        Map<String, String> out = translator().translateResourceTexts(List.of("Hello %1$s, you have %2$d items"), "en", "es");

        assertEquals("Hello <ph0>, you have <ph1> items", server.requests.get(0).text);
        assertEquals("Hola %1$s, tienes %2$d artículos", out.get("Hello %1$s, you have %2$d items"));
    }

    @Test
    void aTranslationThatLosesItsPlaceholderTwiceIsKeptInTheSourceLanguage() {
        useTemplateStyle(false);
        server.responder = r -> "Hola";

        Map<String, String> out = translator().translateResourceTexts(List.of("Hello %1$s"), "en", "es");

        assertEquals("Hello %1$s", out.get("Hello %1$s"));
        assertEquals(2, server.requests.size(), "one attempt and one retry");
    }

    /**
     * A literal is often one piece of a sentence built in the code ("Items: " + n + " of 3"): the
     * translation takes the edge whitespace of the source, whatever the model returned, or the
     * pieces would run together ("2de 3").
     */
    @Test
    void theEdgeSpacesOfTheSourceAreKept() {
        useTemplateStyle(true);
        server.responder = r -> r.text.contains("of 3") ? "de 3 en tu carrito" : "  Art\u00edculos:";

        Map<String, String> out = translator().translateUiLabels(List.of(" of 3 in your cart", "Items: "), "en", "es");

        assertEquals(" de 3 en tu carrito", out.get(" of 3 in your cart"));
        assertEquals("Art\u00edculos: ", out.get("Items: "), "the model's own edge whitespace is dropped");
    }

    @Test
    void theBatchedStyleKeepsTheEdgeSpacesToo() {
        server.responder = r -> FakeLlmServer.translateItems(r, v -> "ES " + v.trim());

        Map<String, String> out = translator().translateUiLabels(List.of(" of 3 in your cart", "Saved\n"), "en", "es");

        assertEquals(" ES of 3 in your cart", out.get(" of 3 in your cart"));
        assertEquals("ES Saved\n", out.get("Saved\n"));
    }

    @Test
    void withEdgesPutsTheWhitespaceOfTheSourceAroundTheTranslation() {
        assertEquals(" de 3 ", LLMTranslator.withEdges(" of 3 ", "de 3"));
        assertEquals("Guardado", LLMTranslator.withEdges("Saved", "  Guardado \n"));
        assertEquals("\n\tTexto", LLMTranslator.withEdges("\n\tText", "Texto"));
        assertEquals("", LLMTranslator.withEdges(" x ", "   "), "an empty answer stays empty: it means no answer");
        assertEquals("hola", LLMTranslator.withEdges("   ", "hola"), "a source that is only whitespace gives no edges");
    }

    // ------------------------------------------------------------------ values wrapped in quotes

    /**
     * The quotes around a whole resource value are Android syntax ("keep the whitespace inside";
     * apktool also writes that way every string with an apostrophe), not text. The model gets what
     * is inside and the answer is wrapped again; sent as they were, the quotes came back as part
     * of the translation and the app showed them.
     */
    @Test
    void aValueWrappedInQuotesIsTranslatedWithoutThem() {
        useTemplateStyle(true);
        server.responder = r -> r.text.replace("Here you can switch to", "Aqu\u00ed puedes cambiar a").replace("calendar", "calendario");

        String source = "\"Here you can switch to %1$s's calendar.\"";
        Map<String, String> out = translator().translateResourceTexts(List.of(source, "\" of 3 in your cart\""), "en", "es");

        // the two requests travel in parallel: either may arrive first
        List<String> sent = server.requests.stream().map(r -> r.text.trim()).toList();
        assertTrue(sent.contains("Here you can switch to <ph0>'s calendar."), "no quotes in what the model sees: " + sent);
        assertTrue(sent.stream().noneMatch(t -> t.contains("\"")), sent.toString());
        assertEquals("\"Aqu\u00ed puedes cambiar a %1$s's calendario.\"", out.get(source));
        assertEquals("\" of 3 in your cart\"", out.get("\" of 3 in your cart\""), "the edge space inside the quotes is kept");
    }

    @Test
    void theBatchedStyleAlsoSendsWhatIsInsideTheQuotes() {
        server.responder = r -> FakeLlmServer.translateItems(r, v -> v.equals("Items: ") ? "\"Art\u00edculos:\"" : "ES " + v);

        Map<String, String> out = translator().translateResourceTexts(List.of("\"Items: \"", "\"It's here\""), "en", "es");

        assertEquals(List.of("Items: ", "It's here"), List.copyOf(server.requests.get(0).items.values()));
        assertEquals("\"Art\u00edculos: \"", out.get("\"Items: \""), "quotes the model added on its own are not doubled");
        assertEquals("\"ES It's here\"", out.get("\"It's here\""));
    }

    @Test
    void isQuotedTellsAWrappedValueFromQuotedWords() {
        assertTrue(LLMTranslator.isQuoted("\"It's here\""));
        assertTrue(LLMTranslator.isQuoted("\" padded \""));
        assertFalse(LLMTranslator.isQuoted("\"Yes\" or \"No\""), "two quoted words, not one wrapped value");
        assertFalse(LLMTranslator.isQuoted("He said \"hi\""));
        assertFalse(LLMTranslator.isQuoted("\"escaped end\\\""));
        assertFalse(LLMTranslator.isQuoted("\""));
        assertFalse(LLMTranslator.isQuoted("plain"));
    }

    @Test
    void translateWritesAWrappedValueWithItsQuotesUnescaped() throws Exception {
        Path app = tmp.resolve("shop");
        FileUtils.copyDirectory(Fixtures.decodedApp().toFile(), app.toFile());
        Path strings = app.resolve("res/values/strings.xml");
        Files.writeString(strings, Files.readString(strings).replace("</resources>",
                "    <string name=\"hint\">\"It's here\"</string>\n</resources>"));
        server.responder = r -> FakeLlmServer.translateItems(r, v -> "ES " + v);
        StringInventory inventory = StringInventory.collect(app.toString(), "",
                new NotTranslatableStringsDictionary(settingsDir.toString()));

        new LLMTranslator(settingsDir.toString(), inventory).translate(strings.toString(), "en", "es");

        String written = Files.readString(app.resolve("res/values-es/strings.xml"));
        assertTrue(written.contains(">\"ES It\\'s here\"<"), written);
    }

    // ------------------------------------------------------------------ PLaMo Translate

    @Test
    void thePlamoStyleSendsTheOptionsAsTheSystemMessageAndTheBareText() {
        System.setProperty("ITDROID_LLM_STYLE", "plamo");
        System.clearProperty("ITDROID_LLM_MASK_PLACEHOLDERS");
        server.responder = r -> r.user.replace("Hello", "Hola").replace("you have", "tienes").replace("items", "art\u00edculos");

        Map<String, String> out = translator().translateResourceTexts(List.of("Hello %1$s, you have %2$d items "), "en", "es-rMX");

        FakeLlmServer.Request request = server.requests.get(0);
        assertEquals("[plamo-translate]\nsource_lang=en\ntarget_lang=es", request.system, "the bare language code, without Android's region suffix");
        assertEquals("Hello <ph0>, you have <ph1> items", request.user, "masked by default, and without the edge space");
        assertEquals(List.of("<|plamo:tag|>", "<|plamo:bos|>"), request.body.get("stop"));
        assertEquals(1024L, request.body.get("max_tokens"));
        assertEquals("Hola %1$s, tienes %2$d art\u00edculos ", out.get("Hello %1$s, you have %2$d items "));
    }

    /**
     * A word alone is ambiguous ("Open": a verb on a button, an adjective in a sentence). PLaMo is
     * told that a short text is a label of an app; a sentence carries its own context.
     */
    @Test
    void aShortLabelIsSentToPlamoWithItsContext() {
        System.setProperty("ITDROID_LLM_STYLE", "plamo");
        server.responder = r -> "ES " + r.user;

        translator().translateUiLabels(List.of("Open", "Your cart is empty right now"), "en", "es");

        Map<String, String> systemByText = new LinkedHashMap<>();
        server.requests.forEach(r -> systemByText.put(r.user, r.system));
        assertEquals("[plamo-translate]\nsource_lang=en\ntarget_lang=es\n---\nText of a button, menu or label in the user interface of a mobile app.",
                systemByText.get("Open"));
        assertEquals("[plamo-translate]\nsource_lang=en\ntarget_lang=es", systemByText.get("Your cart is empty right now"));
        assertTrue(LLMTranslator.isShortLabel("Sign in"));
        assertFalse(LLMTranslator.isShortLabel("Sign in to your account"));
    }

    @Test
    void theContextOfShortLabelsCanBeTurnedOff() {
        System.setProperty("ITDROID_LLM_STYLE", "plamo");
        System.setProperty("ITDROID_LLM_CONTEXT", "none");
        server.responder = r -> "ES " + r.user;

        translator().translateUiLabels(List.of("Open"), "en", "es");

        assertEquals("[plamo-translate]\nsource_lang=en\ntarget_lang=es", server.requests.get(0).system);
    }

    /** Asked for a language it does not have, PLaMo answers in another one (Portuguese came back in French). */
    @Test
    void saysWhichLanguagesPlamoCannotTranslate() {
        System.setProperty("ITDROID_LLM_STYLE", "plamo");
        LLMTranslator plamo = translator();

        assertNull(plamo.unsupportedLanguage("es"));
        assertNull(plamo.unsupportedLanguage("ru"));
        assertNull(plamo.unsupportedLanguage("zh-rTW"));
        assertEquals("the translation model (PLaMo Translate) does not support the language 'pt-rBR'", plamo.unsupportedLanguage("pt-rBR"));
        assertEquals("zh-hant", LLMTranslator.plamoCode("zh-rTW"));
        assertEquals("zh-hans", LLMTranslator.plamoCode("zh"));
        assertEquals("he", LLMTranslator.plamoCode("iw"));
        assertEquals("id", LLMTranslator.plamoCode("in"));
        assertEquals("es", LLMTranslator.plamoCode("es-rMX"));

        useJsonStyle();
        assertNull(translator().unsupportedLanguage("pt-rBR"), "a general chat model has no closed list");
    }

    @Test
    void aModelCalledPlamoGetsThePlamoStyleWithoutBeingAsked() {
        System.setProperty("ITDROID_LLM_STYLE", "auto");
        System.setProperty("ITDROID_LLM_MODEL", "hf.co/pfnet/plamo-3-translate-0.8b-GGUF:Q4_K_M");
        System.clearProperty("ITDROID_LLM_MASK_PLACEHOLDERS");
        server.responder = r -> "Guardado";

        LLMTranslator translator = translator();
        Map<String, String> out = translator.translateUiLabels(List.of("Saved"), "en", "es");

        assertEquals("plamo", translator.settings().get("style"));
        assertEquals(true, translator.settings().get("maskPlaceholders"));
        assertEquals("Guardado", out.get("Saved"));
        assertEquals("Saved", server.requests.get(0).user);
    }

    /**
     * A model that repeats its instruction instead of translating (seen with "English" in a real
     * batch) used to have that sentence stored as the translation. The answer is now rejected,
     * asked for again, and the source text kept; the rejections are counted for the report.
     */
    @Test
    void anAnswerThatRepeatsTheInstructionIsNotStoredAsTheTranslation() {
        useTemplateStyle(true);
        String echo = "Nota: debe solo mostrar el resultado traducido, sin ninguna explicaci\u00f3n adicional.";
        server.responder = r -> echo;
        long before = (Long) LLMTranslator.stats().get("answersRejected");

        Map<String, String> out = translator().translateUiLabels(List.of("English"), "en", "es");

        assertEquals("English", out.get("English"));
        assertEquals(2, server.requests.size(), "asked once more before giving up");
        assertEquals(before + 2, LLMTranslator.stats().get("answersRejected"));
    }

    @Test
    void tellsATranslationFromAnAnswerThatIsNotOne() {
        LLMTranslator t = translator();

        assertNull(t.rejectReason("Settings", "Configuraci\u00f3n", "es"));
        assertNull(t.rejectReason("OK", "Aceptar", "es"), "a short text may grow a lot");
        assertNull(t.rejectReason("Translation: on", "Traducci\u00f3n: activada", "es"), "the source starts the same way");
        assertNull(t.rejectReason("Machine translation", "Traducci\u00f3n autom\u00e1tica", "es"), "a label, not a comment: no colon");
        assertNull(t.rejectReason("Note: tap to edit", "Nota: toca para editar", "es"));
        assertEquals("empty answer", t.rejectReason("Settings", "  ", "es"));
        assertEquals("a placeholder was lost", t.rejectReason("Hello %1$s", "Hola", "es"));
        assertEquals("far longer than the text", t.rejectReason("Save", "Guardar. " + "x".repeat(60), "es"));
        assertEquals("a comment instead of a translation", t.rejectReason("Your cart is empty", "Sure! Tu carrito est\u00e1 vac\u00edo", "es"));
        assertEquals("a comment instead of a translation", t.rejectReason("Your cart is empty", "Translation: Tu carrito", "es"));

        // a language with its own alphabet: an answer left in Latin letters is not a translation
        assertNull(t.rejectReason("Settings", "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438", "ru"));
        assertNull(t.rejectReason("Hello %1$s", "\u041f\u0440\u0438\u0432\u0435\u0442 %1$s", "ru"));
        assertNull(t.rejectReason("Open GitHub", "\u041e\u0442\u043a\u0440\u044b\u0442\u044c GitHub", "ru"), "a brand inside a translated sentence is fine");
        assertEquals("not written in the script of the language", t.rejectReason("Settings", "Configuraci\u00f3n", "ar"));
        assertEquals("not written in the script of the language", t.rejectReason("Your cart is empty", "Votre panier est vide", "ru"));
        assertNull(t.rejectReason("GitHub", "GitHub", "ru"), "left as it was: a brand is not a wrong answer");
        assertNull(t.rejectReason("12:30", "12:30", "ru"), "nothing to translate, nothing to check");
        assertNull(t.rejectReason("Settings", "Settings", "es"), "Latin-script languages are not checked this way");
    }

    // ------------------------------------------------------------------ protected terms

    private void protect(String... terms) throws IOException {
        Files.writeString(settingsDir.resolve(LLMTranslator.PROTECTED_TERMS_FILE),
                "# names that are never translated\n\n" + String.join("\n", terms) + "\n");
    }

    /**
     * A name in protected-terms.txt never reaches the model: it travels as a token, like a
     * placeholder, and comes back exactly as written, whatever the model would have done with it.
     */
    @Test
    void aProtectedTermTravelsMaskedAndComesBackAsWritten() throws IOException {
        protect("Juan P\u00e9rez", "Beanbook Cloud");
        useTemplateStyle(true);
        server.responder = r -> r.text.replace("Welcome back,", "\u0421\u043d\u043e\u0432\u0430 \u0440\u0430\u0434\u044b \u0432\u0438\u0434\u0435\u0442\u044c \u0432\u0430\u0441,");

        LLMTranslator translator = translator();
        Map<String, String> out = translator.translateUiLabels(List.of("Welcome back, Juan P\u00e9rez"), "en", "ru");

        assertEquals(List.of("Juan P\u00e9rez", "Beanbook Cloud"), translator.protectedTerms());
        assertEquals("Welcome back, <ph0>", server.requests.get(0).text);
        assertEquals("\u0421\u043d\u043e\u0432\u0430 \u0440\u0430\u0434\u044b \u0432\u0438\u0434\u0435\u0442\u044c \u0432\u0430\u0441, Juan P\u00e9rez", out.get("Welcome back, Juan P\u00e9rez"));
        assertEquals(2L, translator.settings().get("protectedTerms"));
    }

    @Test
    void aTranslationThatLosesAProtectedTermIsKeptInTheSourceLanguage() throws IOException {
        protect("ShiftSwift");
        useTemplateStyle(true);
        server.responder = r -> "Inicia sesi\u00f3n";     // the model dropped the token

        Map<String, String> out = translator().translateUiLabels(List.of("Log in to ShiftSwift"), "en", "es");

        assertEquals("Log in to ShiftSwift", out.get("Log in to ShiftSwift"));
        assertEquals(2, server.requests.size());
    }

    @Test
    void theBatchedStyleMasksProtectedTermsToo() throws IOException {
        protect("Maria Smith");
        server.responder = r -> FakeLlmServer.translateItems(r, v -> v.replace("Shared by", "Compartido por"));

        Map<String, String> out = translator().translateResourceTexts(List.of("Shared by Maria Smith"), "en", "es");

        assertEquals(List.of("Shared by <ph0>"), List.copyOf(server.requests.get(0).items.values()));
        assertEquals("Compartido por Maria Smith", out.get("Shared by Maria Smith"));
    }

    @Test
    void protectsWholeWordsExactlyAsWrittenAndAlsoAddressesInsideAText() throws IOException {
        protect("Tasks", "New York", "New York City");
        LLMTranslator t = translator();
        t.addProtectedTerms(List.of("uMath+"));
        List<String> originals = new ArrayList<>();

        assertEquals("Open <ph0> in <ph1>", t.maskTerms("Open Tasks in New York City", originals));
        assertEquals(List.of("Tasks", "New York City"), originals, "the longest term wins");
        assertEquals("your tasks, Taskset", t.maskTerms("your tasks, Taskset", new ArrayList<>()), "case-sensitive, whole words only");
        assertEquals("Write to <ph0> or see <ph1>", t.maskTerms("Write to help@acme.com or see https://acme.com/help?id=1", new ArrayList<>()));
        assertTrue(t.termsKept("Mail help@acme.com", "Escribe a help@acme.com"));
        assertFalse(t.termsKept("Mail help@acme.com", "Escribe a ayuda@acme.com"));

        assertTrue(LLMTranslator.looksLikeBrand("ShiftSwift"));
        assertTrue(LLMTranslator.looksLikeBrand("Super Productivity"));
        assertTrue(LLMTranslator.looksLikeBrand("uMath+"));
        assertFalse(LLMTranslator.looksLikeBrand("Tasks"), "an ordinary word is only protected when the user lists it");
    }

    /**
     * A translation model writes a figure the way the target language does: "6,568.65 EUR" came back
     * as "6.568,65 EUR" in Spanish and "6 568,65" in Russian. A figure of the app is data, so it
     * travels as a token and comes back as it was written.
     */
    @Test
    void aFigureTravelsMaskedAndComesBackAsWritten() throws IOException {
        useTemplateStyle(true);
        server.responder = r -> r.text.replace("Balance on", "Saldo al").replace("at", "a las");

        Map<String, String> out = translator().translateUiLabels(List.of("Balance on 20/09/2026 at 09:41: 6,568.65 EUR"), "en", "es");

        assertEquals("Balance on <ph0> at <ph1>: <ph2> EUR", server.requests.get(0).text);
        assertEquals("Saldo al 20/09/2026 a las 09:41: 6,568.65 EUR", out.get("Balance on 20/09/2026 at 09:41: 6,568.65 EUR"));
    }

    @Test
    void whatCountsAsAFigureHasASeparatorBetweenItsDigits() throws IOException {
        LLMTranslator t = translator();

        assertEquals("Version <ph0> of <ph1>", t.maskTerms("Version 1.13.0 of 2026-11-14", new ArrayList<>()));
        assertEquals("Call <ph0> or <ph1>", t.maskTerms("Call 300 123 4567 or 192.168.1.1", new ArrayList<>()));
        assertEquals("2 items of 3 in 5 minutes", t.maskTerms("2 items of 3 in 5 minutes", new ArrayList<>()),
                "a bare number stays: the words around it agree with it");
        assertEquals("Chapters 1, 2 and 3 in 4K", t.maskTerms("Chapters 1, 2 and 3 in 4K", new ArrayList<>()));
        assertEquals("50% of users at %.2f", t.maskTerms("50% of users at %.2f", new ArrayList<>()), "a format is not a figure");

        assertTrue(LLMTranslator.figuresKept("Total: 6,568.65 EUR", "Total: 6,568.65 EUR"));
        assertFalse(LLMTranslator.figuresKept("Total: 6,568.65 EUR", "Total: 6.568,65 EUR"));
        assertTrue(LLMTranslator.figuresKept("Step 2 of 4", "Paso 2 de 4"));
    }

    @Test
    void aTranslationThatRewritesAFigureIsKeptInTheSourceLanguage() throws IOException {
        useTemplateStyle(true);
        server.responder = r -> "6.568,65 EUR en total";     // the model wrote the figure itself instead of the token

        Map<String, String> out = translator().translateUiLabels(List.of("6,568.65 EUR in total"), "en", "es");

        assertEquals("6,568.65 EUR in total", out.get("6,568.65 EUR in total"));
        assertEquals(2, server.requests.size(), "asked again once, then the source text is kept");
    }

    // ------------------------------------------------------------------ placeholder helpers

    @Test
    void maskReplacesFormatsNamedPlaceholdersTagsAndEntities() {
        List<String> originals = new ArrayList<>();

        String masked = LLMTranslator.mask("Hi %1$s &amp; {name}: <b>%.2f</b>", originals);

        assertEquals("Hi <ph0> <ph1> <ph2>: <ph3><ph4><ph5>", masked);
        assertEquals(List.of("%1$s", "&amp;", "{name}", "<b>", "%.2f", "</b>"), originals);
        assertEquals("Hola %1$s &amp; {name}: <b>%.2f</b>", LLMTranslator.unmask("Hola <ph0> <ph1> <ph2>: <ph3><ph4><ph5>", originals, false));
    }

    @Test
    void unmaskToleratesTheSpacingAndCaseModelsGiveTheTokens() {
        assertEquals("Hola %1$s y %2$d", LLMTranslator.unmask("Hola < PH0 > y <ph 1>", List.of("%1$s", "%2$d"), false));
    }

    @Test
    void anEscapedLineBreakTravelsAsARealOneAndComesBackEscaped() {
        List<String> originals = new ArrayList<>();

        String masked = LLMTranslator.mask("Line one\\nLine two", originals);

        assertEquals("Line one\nLine two", masked);
        assertEquals("Línea uno\\nLínea dos", LLMTranslator.unmask("Línea uno\nLínea dos", originals, true));
    }

    @Test
    void placeholdersKeptComparesWhatTheSourceNeedsWithWhatTheTranslationHas() {
        assertTrue(LLMTranslator.placeholdersKept("Hello %1$s", "Hola %1$s"));
        assertTrue(LLMTranslator.placeholdersKept("No placeholders", "Sin marcadores"));
        assertFalse(LLMTranslator.placeholdersKept("Hello %1$s", "Hola"));
        assertFalse(LLMTranslator.placeholdersKept("%d of %d", "%d de"));
        assertFalse(LLMTranslator.placeholdersKept("<b>Bold</b>", "Negrita"));
    }

    /**
     * A percent sign is a format only where one can be: "% o" in "50% of users" used to be cut out
     * as a placeholder (the space is a valid printf flag) and put back untranslated, leaving a
     * stray letter in the translation.
     */
    @Test
    void aPercentageIsNotTakenForAPlaceholder() {
        List<String> originals = new ArrayList<>();

        assertEquals("50% of users", LLMTranslator.mask("50% of users", originals));
        assertEquals("Symbol size (% of line width)", LLMTranslator.mask("Symbol size (% of line width)", originals));
        assertEquals("20%off today", LLMTranslator.mask("20%off today", originals));
        assertTrue(originals.isEmpty());

        assertEquals("<ph0><ph1> of goal", LLMTranslator.mask("%1$d%% of goal", originals));
        assertEquals(List.of("%1$d", "%%"), originals, "the doubled percent is kept as it is");
        originals.clear();
        assertEquals("<ph0> items (<ph1>), <ph2>MB of <ph3>", LLMTranslator.mask("%d items (%.2f), %dMB of %s", originals));
        assertEquals(List.of("%d", "%.2f", "%d", "%s"), originals);
        assertTrue(LLMTranslator.placeholdersKept("50% of users", "50 % de los usuarios"), "no placeholder to keep");
        assertFalse(LLMTranslator.placeholdersKept("%d%% done", "%d hecho"));
    }

    // ------------------------------------------------------------------ writing values-<lang>/strings.xml

    @Test
    void translateWritesTheResourcesOfTheTargetLanguage() throws Exception {
        Path app = tmp.resolve("shop");
        FileUtils.copyDirectory(Fixtures.decodedApp().toFile(), app.toFile());
        server.responder = r -> FakeLlmServer.translateItems(r, v -> "ES " + v);
        StringInventory inventory = StringInventory.collect(app.toString(), "com.example.shop.free",
                new NotTranslatableStringsDictionary(settingsDir.toString()));

        new LLMTranslator(settingsDir.toString(), inventory)
                .translate(app.resolve("res/values/strings.xml").toString(), "en", "es");

        Element root = new SAXBuilder().build(app.resolve("res/values-es/strings.xml").toFile()).getRootElement();
        Map<String, Element> byName = new LinkedHashMap<>();
        for (Element e : root.getChildren()) {
            byName.put(e.getAttributeValue("name"), e);
        }
        assertNull(byName.get("app_name"), "the name of the app is not translated");
        assertTrue(server.requests.stream().noneMatch(r -> r.items.containsValue("Shop")));
        assertEquals("ES Hello %1$s", byName.get("greeting").getText());
        assertEquals("Terms", byName.get("terms").getChild("b").getText(), "inline markup survives as markup");
        // plurals and arrays that lived in other files end up here too, item by item
        assertEquals(List.of("ES %d item", "ES %d items"), byName.get("items_count").getChildren("item").stream().map(Element::getText).toList());
        assertEquals("one", byName.get("items_count").getChildren("item").get(0).getAttributeValue("quantity"));
        assertEquals(List.of("ES Newest first", "@string/cart_title"), byName.get("sort_labels").getChildren("item").stream().map(Element::getText).toList());
        // data, references, dictionary entries and letter-less values are not written: the default applies
        for (String kept : new String[] {"sort_values", "title_reference", "abc_action_bar_home_description", "version_label"}) {
            assertNull(byName.get(kept), kept + " must not be redefined for the language");
        }
    }

    @Test
    void translateLeavesAloneWhatTheLanguageAlreadyDefines() throws Exception {
        Path app = tmp.resolve("shop");
        FileUtils.copyDirectory(Fixtures.decodedApp().toFile(), app.toFile());
        Path values = Files.createDirectories(app.resolve("res/values-es"));
        Files.writeString(values.resolve("strings.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <string name=\"cart_title\">Tu carrito</string>\n</resources>\n");
        server.responder = r -> FakeLlmServer.translateItems(r, v -> "ES " + v);
        StringInventory inventory = StringInventory.collect(app.toString(), "com.example.shop.free",
                new NotTranslatableStringsDictionary(settingsDir.toString()));

        new LLMTranslator(settingsDir.toString(), inventory)
                .translate(app.resolve("res/values/strings.xml").toString(), "en", "es");

        Element root = new SAXBuilder().build(values.resolve("strings.xml").toFile()).getRootElement();
        List<Element> titles = root.getChildren().stream().filter(e -> "cart_title".equals(e.getAttributeValue("name"))).toList();
        assertEquals(1, titles.size(), "a resource must not be defined twice");
        assertEquals("Tu carrito", titles.get(0).getText());
        assertTrue(server.requests.stream().noneMatch(r -> r.items.containsValue("Your cart")), "the developer's translation is not sent again");
    }

    /**
     * A language the app already ships is evaluated as the developer left it: only the strings
     * ITDroid added (the literals moved out of the XML) are written, never the ones it lacks.
     */
    @Test
    void translateCanBeRestrictedToTheResourcesItdroidAdded() throws Exception {
        Path app = tmp.resolve("shop");
        FileUtils.copyDirectory(Fixtures.decodedApp().toFile(), app.toFile());
        Path values = Files.createDirectories(app.resolve("res/values-es"));
        Files.writeString(values.resolve("strings.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <string name=\"cart_title\">Tu carrito</string>\n</resources>\n");
        uniandes.tsdl.itdroid.helper.XmlLiteralExtractor.Extraction extraction = uniandes.tsdl.itdroid.helper.XmlLiteralExtractor.extract(
                app.toString(), uniandes.tsdl.itdroid.helper.XmlLiteralFinder.find(app.toString()), java.util.Set.of("Shop"));
        server.responder = r -> FakeLlmServer.translateItems(r, v -> "ES " + v);
        StringInventory inventory = StringInventory.collect(app.toString(), "",
                new NotTranslatableStringsDictionary(settingsDir.toString()));

        new LLMTranslator(settingsDir.toString(), inventory)
                .translate(app.resolve("res/values/strings.xml").toString(), "en", "es", extraction.names());

        Element root = new SAXBuilder().build(values.resolve("strings.xml").toFile()).getRootElement();
        assertEquals(List.of("cart_title", "itdroid_hc_1", "itdroid_hc_2", "itdroid_hc_3"),
                root.getChildren().stream().map(e -> e.getAttributeValue("name")).toList(), "greeting and terms stay missing, as the developer left them");
        assertEquals("ES Add to cart", root.getChildren().get(1).getText());
    }
}

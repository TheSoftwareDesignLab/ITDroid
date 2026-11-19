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
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import uniandes.tsdl.itdroid.Fixtures;
import uniandes.tsdl.itdroid.helper.LanguageBundle;
import uniandes.tsdl.itdroid.helper.StringInventory;
import uniandes.tsdl.itdroid.helper.Translatability;

/** Tests the LLM translator against {@link FakeLlmServer}, with no model and no network beyond localhost. */
class LLMTranslatorTest {

    private static final String[] KEYS = {"ITDROID_LLM_ENDPOINT", "ITDROID_LLM_MODEL", "ITDROID_LLM_TIMEOUT",
            "ITDROID_LLM_TEMPERATURE", "ITDROID_LLM_STYLE", "ITDROID_LLM_MASK_PLACEHOLDERS", "ITDROID_LLM_PROMPT_TEMPLATE",
            "ITDROID_LLM_CONTEXT", "ITDROID_LLM_PROTECTED_TERMS"};

    @TempDir
    Path tmp;

    private FakeLlmServer server;
    private final Map<String, String> savedProperties = new LinkedHashMap<>();

    @BeforeEach
    void startTheFakeServer() throws IOException {
        server = new FakeLlmServer();
        for (String k : KEYS) {
            savedProperties.put(k, System.getProperty(k));
        }
        System.clearProperty("ITDROID_LLM_PROTECTED_TERMS");
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

    /** Batched JSON, the style for general chat models. */
    private static void useJsonStyle() {
        System.setProperty("ITDROID_LLM_STYLE", "json");
        System.setProperty("ITDROID_LLM_MASK_PLACEHOLDERS", "false");
    }

    /** One text per request in a prompt template, the style for translation models. */
    private static void useTemplateStyle(boolean maskPlaceholders) {
        System.setProperty("ITDROID_LLM_STYLE", "template");
        System.setProperty("ITDROID_LLM_MASK_PLACEHOLDERS", String.valueOf(maskPlaceholders));
    }

    private LLMTranslator translator() {
        return new LLMTranslator();
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
        StringInventory inventory = Fixtures.inventory(Fixtures.decodedApp().toString(), "com.example.shop.free");
        LLMTranslator translator = new LLMTranslator(inventory);

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
     * A translation that lost its placeholder would crash the app, so it is rejected. The second request is
     * not the same one again, the text is cut at its placeholder and the piece is translated alone.
     */
    @Test
    void theBatchedStyleTranslatesByPartsATextWhoseAnswerLostItsPlaceholder() {
        server.responder = r -> FakeLlmServer.translateItems(r, v -> v.startsWith("Hello") ? "Hola" : "ES " + v);

        LLMTranslator translator = translator();
        Map<String, String> out = translator.translateResourceTexts(List.of("Hello %1$s", "Saved"), "en", "es");

        assertEquals("Hola %1$s", out.get("Hello %1$s"), "the piece translated and the placeholder put back where it was");
        assertEquals("ES Saved", out.get("Saved"), "the rest of the batch is not affected");
        assertEquals(2, server.requests.size(), "the batch and one request with the piece");
        assertEquals(List.of("Hello"), List.copyOf(server.requests.get(1).items.values()));
        JSONObject report = translator.report("es");
        assertEquals(1L, report.get("byPartsCount"));
        assertEquals("Hola %1$s", ((JSONObject) ((JSONArray) report.get("byParts")).get(0)).get("translation"));
        assertEquals(0L, report.get("keptInSourceCount"));
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
    void aTranslationThatLosesItsPlaceholderIsTranslatedByParts() {
        useTemplateStyle(false);
        server.responder = r -> "Hola";

        Map<String, String> out = translator().translateResourceTexts(List.of("Hello %1$s"), "en", "es");

        assertEquals("Hola %1$s", out.get("Hello %1$s"));
        assertEquals(2, server.requests.size(), "one attempt and one request for the piece");
        assertEquals("Hello", server.requests.get(1).text, "the second request is not the first one again");
    }

    /** When a piece cannot be used either, the text stays in the source language and the language records why. */
    @Test
    void whatFailsAsAWholeAndByPartsIsKeptAndReportedForThatLanguage() throws Exception {
        useTemplateStyle(true);
        server.responder = r -> r.text.contains("tap to place") ? "Sure! here it is" : "\u041d\u0430\u0436\u043c\u0438\u0442\u0435";

        LLMTranslator translator = translator();
        Map<String, String> out = translator.translateResourceTexts(List.of("%1$s \u00b7 tap to place"), "en", "ru");

        assertEquals("%1$s \u00b7 tap to place", out.get("%1$s \u00b7 tap to place"));
        assertEquals("<ph0> \u00b7 tap to place", server.requests.get(0).text);
        assertEquals("tap to place", server.requests.get(1).text, "the piece between the placeholder and the end");
        Map<String, String> kept = translator.keptInSource("ru");
        assertTrue(kept.get("%1$s \u00b7 tap to place").startsWith("a placeholder was lost, and by parts: 'tap to place' "), kept.toString());
        JSONObject report = translator.report("ru");
        assertEquals(1L, report.get("keptInSourceCount"));
        JSONObject entry = (JSONObject) ((JSONArray) report.get("keptInSource")).get(0);
        assertEquals("%1$s \u00b7 tap to place", entry.get("text"));
        assertEquals(List.of(), entry.get("names"), "a loose text has no resource name");
        assertTrue(translator.keptInSource("es").isEmpty(), "recorded per language");
    }

    /** A literal is often a piece of a sentence built in code, so the translation takes the source's edge whitespace. */
    @Test
    void theEdgeSpacesOfTheSourceAreKept() {
        useTemplateStyle(true);
        server.responder = r -> r.text.contains("of 3") ? "de 3 en tu carrito" : "  Art\u00edculos:";

        Map<String, String> out = translator().translateResourceTexts(List.of(" of 3 in your cart", "Items: "), "en", "es");

        assertEquals(" de 3 en tu carrito", out.get(" of 3 in your cart"));
        assertEquals("Art\u00edculos: ", out.get("Items: "), "the model's own edge whitespace is dropped");
    }

    @Test
    void theBatchedStyleKeepsTheEdgeSpacesToo() {
        server.responder = r -> FakeLlmServer.translateItems(r, v -> "ES " + v.trim());

        Map<String, String> out = translator().translateResourceTexts(List.of(" of 3 in your cart", "Saved\n"), "en", "es");

        assertEquals(" ES of 3 in your cart", out.get(" of 3 in your cart"));
        assertEquals("ES Saved\n", out.get("Saved\n"));
    }

    // ------------------------------------------------------------------ values wrapped in quotes

    /** Quotes around a whole value are Android syntax, so the model gets only the inside and the answer is rewrapped. */
    @Test
    void aValueWrappedInQuotesIsTranslatedWithoutThem() {
        useTemplateStyle(true);
        server.responder = r -> r.text.replace("Here you can switch to", "Aqu\u00ed puedes cambiar a").replace("calendar", "calendario");

        String source = "\"Here you can switch to %1$s's calendar.\"";
        Map<String, String> out = translator().translateResourceTexts(List.of(source, "\" of 3 in your cart\""), "en", "es");

        // the two requests run in parallel, either may arrive first
        List<String> sent = server.requests.stream().map(r -> r.text.trim()).toList();
        assertTrue(sent.contains("Here you can switch to <ph0>'s calendar."), "no quotes in what the model sees: " + sent);
        assertTrue(sent.stream().noneMatch(t -> t.contains("\"")), sent.toString());
        assertEquals("\"Aqu\u00ed puedes cambiar a %1$s's calendario.\"", out.get(source));
        assertEquals("\" of 3 in your cart\"", out.get("\" of 3 in your cart\""), "the edge space inside the quotes is kept");
    }

    @Test
    void theBatchedStyleAlsoSendsWhatIsInsideTheQuotes() {
        server.responder = r -> FakeLlmServer.translateItems(r, v -> v.equals("Items:") ? "\"Art\u00edculos:\"" : "ES " + v);

        Map<String, String> out = translator().translateResourceTexts(List.of("\"Items: \"", "\"It's here\""), "en", "es");

        assertEquals(List.of("Items:", "It's here"), List.copyOf(server.requests.get(0).items.values()), "the edge space is set aside too");
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
        StringInventory inventory = Fixtures.inventory(app.toString(), "");

        new LLMTranslator(inventory).translate(strings.toString(), "en", "es");

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

    /** A word alone is ambiguous, so PLaMo is told a short text is an app label. A sentence carries its own context. */
    @Test
    void aShortLabelIsSentToPlamoWithItsContext() {
        System.setProperty("ITDROID_LLM_STYLE", "plamo");
        server.responder = r -> "ES " + r.user;

        translator().translateResourceTexts(List.of("Open", "Your cart is empty right now"), "en", "es");

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

        translator().translateResourceTexts(List.of("Open"), "en", "es");

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
        Map<String, String> out = translator.translateResourceTexts(List.of("Saved"), "en", "es");

        assertEquals("plamo", translator.settings().get("style"));
        assertEquals(true, translator.settings().get("maskPlaceholders"));
        assertEquals("Guardado", out.get("Saved"));
        assertEquals("Saved", server.requests.get(0).user);
    }

    /**
     * An answer that echoes the instruction instead of translating is rejected, asked again and the
     * source kept, and the rejections are counted for the report.
     */
    @Test
    void anAnswerThatRepeatsTheInstructionIsNotStoredAsTheTranslation() {
        useTemplateStyle(true);
        String echo = "Nota: debe solo mostrar el resultado traducido, sin ninguna explicaci\u00f3n adicional.";
        server.responder = r -> echo;
        long before = (Long) LLMTranslator.stats().get("answersRejected");

        Map<String, String> out = translator().translateResourceTexts(List.of("English"), "en", "es");

        assertEquals("English", out.get("English"));
        assertEquals(1, server.requests.size(), "one word cannot be cut, and the same request would give the same answer");
        assertEquals(before + 1, LLMTranslator.stats().get("answersRejected"));
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

        // for a language with its own alphabet, an answer in Latin letters is not a translation
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

    /** The translation.protectedTerms line of the config, comma separated. */
    private void protect(String... terms) {
        System.setProperty("ITDROID_LLM_PROTECTED_TERMS", String.join(", ", terms));
    }

    /** A name of translation.protectedTerms travels as a token and comes back exactly as written. */
    @Test
    void aProtectedTermTravelsMaskedAndComesBackAsWritten() throws IOException {
        protect("Juan P\u00e9rez", "Beanbook Cloud");
        useTemplateStyle(true);
        server.responder = r -> r.text.replace("Welcome back,", "\u0421\u043d\u043e\u0432\u0430 \u0440\u0430\u0434\u044b \u0432\u0438\u0434\u0435\u0442\u044c \u0432\u0430\u0441,");

        LLMTranslator translator = translator();
        Map<String, String> out = translator.translateResourceTexts(List.of("Welcome back, Juan P\u00e9rez"), "en", "ru");

        assertEquals(List.of("Juan P\u00e9rez", "Beanbook Cloud"), translator.protectedTerms());
        assertEquals("Welcome back, <ph0>", server.requests.get(0).text);
        assertEquals("\u0421\u043d\u043e\u0432\u0430 \u0440\u0430\u0434\u044b \u0432\u0438\u0434\u0435\u0442\u044c \u0432\u0430\u0441, Juan P\u00e9rez", out.get("Welcome back, Juan P\u00e9rez"));
        assertEquals(2L, translator.settings().get("protectedTerms"));
    }

    @Test
    void aTranslationThatLosesAProtectedTermIsTranslatedByParts() throws IOException {
        protect("Shiftswift");
        useTemplateStyle(true);
        server.responder = r -> "Inicia sesi\u00f3n en";     // the model dropped the token

        Map<String, String> out = translator().translateResourceTexts(List.of("Log in to Shiftswift"), "en", "es");

        assertEquals("Inicia sesi\u00f3n en Shiftswift", out.get("Log in to Shiftswift"));
        assertEquals(List.of("Log in to <ph0>", "Log in to"), server.requests.stream().map(r -> r.text).toList());
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

        assertTrue(Translatability.looksLikeBrand("ShiftSwift"));
        assertTrue(Translatability.looksLikeBrand("Super Productivity"));
        assertTrue(Translatability.looksLikeBrand("uMath+"));
        assertFalse(Translatability.looksLikeBrand("Tasks"), "an ordinary word is only protected when the user lists it");
    }

    /** Models rewrite figures in the target locale's format, so a figure travels as a token and comes back as written. */
    @Test
    void aFigureTravelsMaskedAndComesBackAsWritten() throws IOException {
        useTemplateStyle(true);
        server.responder = r -> r.text.replace("Balance on", "Saldo al").replace("at", "a las");

        Map<String, String> out = translator().translateResourceTexts(List.of("Balance on 20/09/2026 at 09:41: 6,568.65 EUR"), "en", "es");

        assertEquals("Balance on <ph0> at <ph1>: <ph2>", server.requests.get(0).text, "the amount travels with its currency");
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

        Map<String, String> out = translator().translateResourceTexts(List.of("6,568.65 EUR in total"), "en", "es");

        assertEquals("6,568.65 EUR in total", out.get("6,568.65 EUR in total"));
        assertEquals(2, server.requests.size(), "asked again by parts, where the piece came back with a figure it did not have");
    }

    // ------------------------------------------------------------------ placeholder helpers

    /** The placeholders, tags and entities of a text masked as a request with maskPlaceholders masks them. */
    private static String mask(String text, List<String> originals) {
        return LLMTranslator.maskSpans(text, List.of(Translatability.PLACEHOLDER), originals, true);
    }

    @Test
    void maskReplacesFormatsNamedPlaceholdersTagsAndEntities() {
        List<String> originals = new ArrayList<>();

        String masked = mask("Hi %1$s &amp; {name}: <b>%.2f</b>", originals);

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

        String masked = mask("Line one\\nLine two", originals);

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

    /** A percent sign is a format only where one can be, so "% o" in "50% of users" is left alone. */
    @Test
    void aPercentageIsNotTakenForAPlaceholder() {
        List<String> originals = new ArrayList<>();

        assertEquals("50% of users", mask("50% of users", originals));
        assertEquals("Symbol size (% of line width)", mask("Symbol size (% of line width)", originals));
        assertEquals("20%off today", mask("20%off today", originals));
        assertTrue(originals.isEmpty());

        assertEquals("<ph0><ph1> of goal", mask("%1$d%% of goal", originals));
        assertEquals(List.of("%1$d", "%%"), originals, "the doubled percent is kept as it is");
        originals.clear();
        assertEquals("<ph0> items (<ph1>), <ph2>MB of <ph3>", mask("%d items (%.2f), %dMB of %s", originals));
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
        StringInventory inventory = Fixtures.inventory(app.toString(), "com.example.shop.free");

        new LLMTranslator(inventory)
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
        // data, references, dictionary entries and letter-less values keep the default
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
        StringInventory inventory = Fixtures.inventory(app.toString(), "com.example.shop.free");

        new LLMTranslator(inventory)
                .translate(app.resolve("res/values/strings.xml").toString(), "en", "es");

        Element root = new SAXBuilder().build(values.resolve("strings.xml").toFile()).getRootElement();
        List<Element> titles = root.getChildren().stream().filter(e -> "cart_title".equals(e.getAttributeValue("name"))).toList();
        assertEquals(1, titles.size(), "a resource must not be defined twice");
        assertEquals("Tu carrito", titles.get(0).getText());
        assertTrue(server.requests.stream().noneMatch(r -> r.items.containsValue("Your cart")), "the developer's translation is not sent again");
    }

    /** A language the app ships stays as the developer left it, only the strings ITDroid added are written. */
    @Test
    void translateCanBeRestrictedToTheResourcesItdroidAdded() throws Exception {
        Path app = tmp.resolve("shop");
        FileUtils.copyDirectory(Fixtures.decodedApp().toFile(), app.toFile());
        Path values = Files.createDirectories(app.resolve("res/values-es"));
        Files.writeString(values.resolve("strings.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <string name=\"cart_title\">Tu carrito</string>\n</resources>\n");
        uniandes.tsdl.itdroid.helper.XmlLiterals.Extraction extraction = uniandes.tsdl.itdroid.helper.XmlLiterals.extract(
                app.toString(), uniandes.tsdl.itdroid.helper.XmlLiterals.find(app.toString()), java.util.Set.of("Shop"));
        server.responder = r -> FakeLlmServer.translateItems(r, v -> "ES " + v);
        StringInventory inventory = Fixtures.inventory(app.toString(), "");

        new LLMTranslator(inventory)
                .translate(app.resolve("res/values/strings.xml").toString(), "en", "es", extraction.names());

        Element root = new SAXBuilder().build(values.resolve("strings.xml").toFile()).getRootElement();
        assertEquals(List.of("cart_title", "itdroid_hc_1", "itdroid_hc_2", "itdroid_hc_3"),
                root.getChildren().stream().map(e -> e.getAttributeValue("name")).toList(), "greeting and terms stay missing, as the developer left them");
        assertEquals("ES Add to cart", root.getChildren().get(1).getText());
    }

    // ------------------------------------------------------------------ answers that are not translations

    /** A sentence left as it was is not a translation, a word, an acronym or a name may stay as it is. */
    @Test
    void aTextOfSeveralWordsLeftAsItWasIsRejected() {
        LLMTranslator t = translator();

        assertEquals("left in the source language", t.rejectReason("Backup to Google Drive now", "Backup to Google Drive now", "it"));
        assertEquals("left in the source language", t.rejectReason("Sync failed.", "Sync failed", "es"), "punctuation does not count");
        assertNull(t.rejectReason("Magnet", "Magnet", "it"));
        assertNull(t.rejectReason("OK", "OK", "ar"));
        assertNull(t.rejectReason("(GPX, KML)", "(GPX, KML)", "es"), "acronyms in capitals");
        assertNull(t.rejectReason("Google Drive", "Google Drive", "es"), "a name of the built-in list");
        assertNull(t.rejectReason("&lt;a href=\"https://x.org\"&gt;Link&lt;/a&gt;", "&lt;a href=\"https://x.org\"&gt;Link&lt;/a&gt;", "it"),
                "one word inside an escaped link, Link is Italian too");
        assertNull(t.rejectReason("pref_dark_mode", "pref_dark_mode", "es"), "an identifier is one word");
    }

    /** An answer with the letters of the source but other punctuation or case is unchanged, not in a wrong script. */
    @Test
    void anAnswerWithTheLettersOfTheSourceIsUnchangedNotInAWrongScript() {
        LLMTranslator t = translator();

        assertNull(t.rejectReason("(GPX, GeoJSON, KML)", "(GPX\u060c GeoJSON\u060c KML)", "ar"));
        assertNull(t.rejectReason("Youtube", "YouTube", "ru"));
    }

    /** A Latin language takes no letters of another script, any other language takes Latin names besides its own script. */
    @Test
    void lettersOfAnotherScriptAreRejected() {
        LLMTranslator t = translator();

        assertEquals("not written in the script of the language", t.rejectReason("Backup to Google Drive", "\u5099\u4efd su Google Drive", "it"));
        assertNull(t.rejectReason("Backup to Google Drive", "Backup su Google Drive", "it"));
        assertEquals("not written in the script of the language",
                t.rejectReason("Settings", "\u041f\u043e\u0434\u0435\u0448\u0430\u0432\u0430\u045a\u0430", "b+sr+Latn"), "Serbian in Latin letters");
        assertNull(t.rejectReason("Settings", "Pode\u0161avanja", "b+sr+Latn"));
        assertEquals("not written in the script of the language",
                t.rejectReason("Open settings", "\u041e\u0442\u043a\u0440\u044b\u0442\u044c \u8a2d\u5b9a", "ru"), "a third script in a Russian answer");
        assertNull(t.rejectReason("Value of \u03c0", "Valor de \u03c0", "es"), "a letter the source has");
    }

    @Test
    void placeholdersMustNotMoveRepeatOrBeLeftAsTokens() {
        LLMTranslator t = translator();

        assertEquals("the order of the placeholders changed", t.rejectReason("%s has %d items", "%d art\u00edculos en %s", "es"));
        assertNull(t.rejectReason("%1$s of %2$s", "%2$s de %1$s", "es"), "a positional placeholder may move");
        assertEquals("a placeholder was repeated", t.rejectReason("%1$s items", "%1$s %1$s art\u00edculos", "es"));
        assertEquals("a masking token was left in the answer", t.rejectReason("Save", "Guardar <ph1>", "es"));
        assertEquals("a placeholder was lost", t.rejectReason("Hello {0}, you have {1} messages", "Hola, tienes mensajes", "es"));
        assertEquals("a masking token was repeated", LLMTranslator.tokenProblem("<ph0> y <ph0>", 1));
        assertEquals("the answer has a masking token the text did not have", LLMTranslator.tokenProblem("<ph0> y <ph1>", 1));
        assertNull(LLMTranslator.tokenProblem("<ph1> y < PH0 >", 2));
    }

    // ------------------------------------------------------------------ what travels masked

    @Test
    void aDateSpecifierAndANumberedPlaceholderTravelWhole() {
        List<String> originals = new ArrayList<>();

        assertEquals("<ph0>-<ph1> or <ph2>", mask("%1$tY-%1$tm or {0}", originals));
        assertEquals(List.of("%1$tY", "%1$tm", "{0}"), originals);
    }

    /** A link written with entities is one token, and the URL inside it ends at the escaped bracket. */
    @Test
    void anEscapedTagIsOneTokenAndAUrlStopsAtAnEntity() {
        LLMTranslator t = translator();
        List<String> originals = new ArrayList<>();
        String source = "&lt;a href=https://www.cyclosm.org/&gt;CyclOSM&lt;/a&gt;, by OSM";

        assertEquals("<ph0><ph1><ph2>, by OSM", t.maskTerms(source, originals));
        assertEquals(List.of("&lt;a href=https://www.cyclosm.org/&gt;", "CyclOSM", "&lt;/a&gt;"), originals);
        assertNull(t.rejectReason(source, "&lt;a href=https://www.cyclosm.org/&gt;CyclOSM&lt;/a&gt;\u060c \u0645\u0646 OSM", "ar"));
        assertEquals("Visit <ph0>. Today", t.maskTerms("Visit https://acme.com/help. Today", new ArrayList<>()), "the period ends the sentence");
    }

    @Test
    void addressesPathsNamesAndFiguresWithUnitsTravelMasked() {
        LLMTranslator t = translator();

        assertEquals(List.of("myapp://open/item"), masked(t, "Open myapp://open/item"));
        assertEquals(List.of("content://com.example.provider/items"), masked(t, "Read content://com.example.provider/items"));
        assertEquals(List.of("/sdcard/Download/MyApp/backup.json"), masked(t, "Saved to /sdcard/Download/MyApp/backup.json"));
        assertEquals(List.of("com.example.app.MainActivity"), masked(t, "Started com.example.app.MainActivity"));
        assertEquals(List.of("SettingsFragment"), masked(t, "Open SettingsFragment first"));
        assertEquals(List.of("GeoJSON", "TabGreater", "iPhone"), masked(t, "GeoJSON for TabGreater on iPhone"));
        assertEquals(List.of("Google Drive", "Ko-fi"), masked(t, "Backup to Google Drive or Ko-fi"));
        assertEquals(List.of("5 km", "120 MB", "$5", "6,568.65 EUR"), masked(t, "Walk 5 km, download 120 MB, pay $5 of 6,568.65 EUR"));
        assertEquals(List.of(), masked(t, "Top 10 results in 5 minutes"), "a bare number stays, the words around it agree with it");
        assertEquals(List.of(), masked(t, "Yes/No and/or km/h"), "a slash between words is not a path");
    }

    private static List<String> masked(LLMTranslator t, String text) {
        List<String> originals = new ArrayList<>();
        t.maskTerms(text, originals);
        return originals;
    }

    /** An escaped newline at the edge of a value is set aside and put back, so the answer is not rejected for losing it. */
    @Test
    void anEscapedNewlineAtTheEdgeIsSetAsideAndPutBack() {
        useTemplateStyle(true);
        server.responder = r -> r.text.equals("Sync failed.") ? "La sincronizaci\u00f3n fall\u00f3." : "ES " + r.text;

        Map<String, String> out = translator().translateResourceTexts(List.of("Sync failed.\\n", "\\n\\tTap to retry"), "en", "es");

        assertEquals("La sincronizaci\u00f3n fall\u00f3.\\n", out.get("Sync failed.\\n"));
        assertEquals("\\n\\tES Tap to retry", out.get("\\n\\tTap to retry"));
        assertTrue(server.requests.stream().noneMatch(r -> r.text.contains("\\")), "no escape reaches the model");
    }

    /** A value that is only a placeholder or a name has nothing to translate, so it is not sent. */
    @Test
    void aValueWithNothingToTranslateIsNotSent() {
        useJsonStyle();
        server.responder = r -> FakeLlmServer.translateItems(r, v -> "ES " + v);

        Map<String, String> out = translator().translateResourceTexts(List.of("%1$s", "GitHub", "https://acme.com"), "en", "es");

        assertEquals(Map.of("%1$s", "%1$s", "GitHub", "GitHub", "https://acme.com", "https://acme.com"), out);
        assertTrue(server.requests.isEmpty());
    }

    // ------------------------------------------------------------------ the translation memory

    /** An answer stored by an older run that the checks now reject, or that another model gave, is asked again. */
    @Test
    void theMemoryOnlyReusesWhatTheSameModelGaveAndStillPassesTheChecks() throws Exception {
        server.responder = r -> FakeLlmServer.translateItems(r, v -> "ES " + v);
        StringInventory inventory = Fixtures.inventory(Fixtures.decodedApp().toString(), "com.example.shop.free");
        inventory.useModel("es", "test-model");
        inventory.putTranslation("es", "Your cart", "Your cart");            // identical, several words
        inventory.putTranslation("es", "Checkout now", "Pagar ahora");      // fine

        Map<String, String> out = new LLMTranslator(inventory).translateResourceTexts(List.of("Your cart", "Checkout now"), "en", "es");

        assertEquals("ES Your cart", out.get("Your cart"));
        assertEquals("Pagar ahora", out.get("Checkout now"), "reused");
        assertEquals(List.of("Your cart"), List.copyOf(server.requests.get(0).items.values()));

        // saved with the model, and a run with another model does not reuse it
        Path saved = tmp.resolve("strings_inventory.json");
        inventory.save(saved);
        assertTrue(Files.readString(saved).contains("\"translationModels\": {\n    \"es\": \"test-model\""), Files.readString(saved));
        StringInventory next = Fixtures.inventory(Fixtures.decodedApp().toString(), "com.example.shop.free");
        next.loadPreviousTranslations(saved);
        System.setProperty("ITDROID_LLM_MODEL", "other-model");
        server.requests.clear();
        new LLMTranslator(next).translateResourceTexts(List.of("Checkout now"), "en", "es");
        assertEquals(1, server.requests.size(), "another model translates it again");
        assertEquals("ES Checkout now", next.translation("es", "Checkout now"));
    }

    // ------------------------------------------------------------------ languages

    /** Every code gets its English name with region or script, Swedish was answered in Polish when the code went bare. */
    @Test
    void theModelIsToldTheFullNameOfAnyLanguage() {
        useTemplateStyle(true);
        server.responder = r -> "X " + r.text;
        LLMTranslator t = translator();

        t.translateResourceTexts(List.of("Your cart"), "en", "sv");
        t.translateResourceTexts(List.of("Your cart"), "en", "zh-rTW");
        t.translateResourceTexts(List.of("Your cart"), "en", "b+sr+Latn");

        assertEquals(List.of("Translate into Swedish:", "Translate into Chinese (Taiwan):", "Translate into Serbian (Latin):"),
                server.requests.stream().map(r -> r.user.substring(0, r.user.indexOf('\n'))).toList());
        assertEquals("Portuguese (Portugal)", LanguageBundle.name("pt-rPT"));
        assertEquals("sr-Latn", LanguageBundle.tag("b+sr+Latn"));
        assertEquals("sr", LLMTranslator.plamoCode("b+sr+Latn"));
    }

    // ------------------------------------------------------------------ the app's copies of the source text

    /** A string the app ships for a language with the English text is translated where it is, never defined twice. */
    @Test
    void theAppsCopyOfTheSourceTextIsTranslatedInPlace() throws Exception {
        Path app = tmp.resolve("shop");
        FileUtils.copyDirectory(Fixtures.decodedApp().toFile(), app.toFile());
        Path values = Files.createDirectories(app.resolve("res/values-es"));
        Files.writeString(values.resolve("strings.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <string name=\"cart_title\">Your cart</string>\n</resources>\n");
        server.responder = r -> FakeLlmServer.translateItems(r, v -> v.equals("Your cart") ? "Tu carrito" : "ES " + v);
        StringInventory inventory = Fixtures.inventory(app.toString(), "com.example.shop.free");

        LLMTranslator translator = new LLMTranslator(inventory);
        translator.translate(app.resolve("res/values/strings.xml").toString(), "en", "es", null, java.util.Set.of("cart_title"));

        Element root = new SAXBuilder().build(values.resolve("strings.xml").toFile()).getRootElement();
        List<Element> titles = root.getChildren().stream().filter(e -> "cart_title".equals(e.getAttributeValue("name"))).toList();
        assertEquals(1, titles.size(), "a resource must not be defined twice");
        assertEquals("Tu carrito", titles.get(0).getText());
        assertTrue(root.getChildren().stream().anyMatch(e -> "greeting".equals(e.getAttributeValue("name"))), "the missing ones are added as usual");
        JSONObject report = translator.report("es");
        assertEquals(1L, report.get("appCopiesCount"));
        JSONObject copy = (JSONObject) ((JSONArray) report.get("appCopies")).get(0);
        assertEquals("cart_title", copy.get("name"));
        assertEquals("Your cart", copy.get("text"));
        assertEquals("Tu carrito", copy.get("translation"));
    }
}

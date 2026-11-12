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
            "ITDROID_LLM_TEMPERATURE", "ITDROID_LLM_STYLE", "ITDROID_LLM_MASK_PLACEHOLDERS", "ITDROID_LLM_PROMPT_TEMPLATE"};

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
     * KNOWN GAP (TODO 3.1): in the batched style nothing checks that the placeholders survived, so a
     * translation without its {@code %1$s} is accepted and the app crashes when it formats it.
     * Expected after 3.1: the answer is rejected, retried and finally kept in the source language.
     */
    @Test
    void knownGap_theBatchedStyleAcceptsATranslationThatLostItsPlaceholder() {
        server.responder = r -> FakeLlmServer.translateItems(r, v -> "Hola");

        Map<String, String> out = translator().translateResourceTexts(List.of("Hello %1$s"), "en", "es");

        assertEquals("Hola", out.get("Hello %1$s"));
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

        Map<String, String> out = translator().translateResourceTexts(List.of("Hello %1$s, you have %2$d items "), "en", "pt-rBR");

        FakeLlmServer.Request request = server.requests.get(0);
        assertEquals("[plamo-translate]\nsource_lang=en\ntarget_lang=pt", request.system, "the bare language code, without Android's region suffix");
        assertEquals("Hello <ph0>, you have <ph1> items", request.user, "masked by default, and without the edge space");
        assertEquals(List.of("<|plamo:tag|>", "<|plamo:bos|>"), request.body.get("stop"));
        assertEquals(1024L, request.body.get("max_tokens"));
        assertEquals("Hola %1$s, tienes %2$d art\u00edculos ", out.get("Hello %1$s, you have %2$d items "));
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
     * KNOWN GAP (TODO 3.3): nothing looks at the answer itself. A model that echoes its instruction
     * instead of translating (seen with "English" in a real batch) has that sentence stored as the
     * translation. Expected after 3.3: the answer is rejected and the source text kept.
     */
    @Test
    void knownGap_anAnswerThatRepeatsTheInstructionIsStoredAsTheTranslation() {
        useTemplateStyle(true);
        String echo = "Nota: debe solo mostrar el resultado traducido, sin ninguna explicación adicional.";
        server.responder = r -> echo;

        Map<String, String> out = translator().translateUiLabels(List.of("English"), "en", "es");

        assertEquals(echo, out.get("English"));
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
     * KNOWN GAP (TODO 3.2): a percent sign followed by a space and a word matches the placeholder
     * pattern (the space is a valid printf flag), so "% o" is cut out of the text and put back
     * untranslated, leaving a stray letter in the translation. Expected after 3.2: nothing is masked.
     */
    @Test
    void knownGap_aPercentSignBeforeAWordIsTakenForAPlaceholder() {
        List<String> originals = new ArrayList<>();

        String masked = LLMTranslator.mask("50% of users", originals);

        assertEquals("50<ph0>f users", masked);
        assertEquals(List.of("% o"), originals);
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

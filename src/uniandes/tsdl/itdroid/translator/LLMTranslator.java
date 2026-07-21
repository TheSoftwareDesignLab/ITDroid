package uniandes.tsdl.itdroid.translator;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import uniandes.tsdl.itdroid.helper.NotTranslatableStringsDictionary;

/**
 * Translates strings via a local LLM behind an OpenAI-compatible endpoint (Ollama, llama.cpp,
 * LM Studio). Runs on the host, CPU-only, no cloud key. Configured by {@code ITDROID_LLM_*}
 * system properties / env vars / {@code .env} (see {@code .env.example}).
 */
public class LLMTranslator implements TranslationInterface {

    private static final String OUTPUT_FOLDER = "./temp/res/values";
    private static final String NO_ATTRIBUTE_FOUND = "NOT_FOUND";
    /** Strings translated per LLM request. Keeps prompts small enough for CPU inference. */
    private static final int TRANSLATOR_PACKAGE_SIZE = 25;

    /**
     * Categories the model must copy VERBATIM (never translate). Shared by the resource-string and the
     * hardcoded-label prompts so both behave consistently.
     */
    private static final String DO_NOT_TRANSLATE =
            "Do NOT translate the following; copy them VERBATIM, exactly as written: "
            + "people's names and proper names; company, product and brand names; usernames and @handles; "
            + "email addresses; URLs, domains and hostnames; postal addresses; countries and cities; ages; "
            + "numbers written in 0-9 form, including phone numbers, card numbers, dates, times and monetary amounts; "
            + "units of measurement (temperature, length, money and currency); ISO, country and language codes; "
            + "code variables, labels, identifiers, enum or constant names, file paths and any code tokens; "
            + "and format placeholders (e.g. %1$s, %d, %.2f, {count}). "
            + "If a value consists ONLY of such data (not a human-facing phrase), return it unchanged.";

    private final List<String> values;
    private final List<String> names;
    private final List<String> formatted;
    private final String propertiesDirectory;

    private final String endpoint;
    private final String model;
    private final String apiKey;
    private final int timeoutSeconds;
    private final String temperature;

    private final HttpClient httpClient;

    public LLMTranslator(String directory) {
        this.values = new ArrayList<>();
        this.names = new ArrayList<>();
        this.formatted = new ArrayList<>();
        this.propertiesDirectory = directory;

        Map<String, String> env = loadDotEnv(directory);
        this.endpoint = resolve(env, "ITDROID_LLM_ENDPOINT", "http://localhost:11434/v1/chat/completions");
        this.model = resolve(env, "ITDROID_LLM_MODEL", "qwen2.5:3b");
        this.apiKey = resolve(env, "ITDROID_LLM_API_KEY", "");
        this.temperature = resolve(env, "ITDROID_LLM_TEMPERATURE", "0");
        int timeout;
        try {
            timeout = Integer.parseInt(resolve(env, "ITDROID_LLM_TIMEOUT", "180"));
        } catch (NumberFormatException e) {
            timeout = 180;
        }
        this.timeoutSeconds = timeout;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public void translate(String xmlPath, String inputLang, String outputLang) throws Exception {
        System.out.println("LLM translation: " + inputLang + " -> " + outputLang
                + " using model '" + model + "' at " + endpoint);

        // Strings the developer marked as non translatable.
        NotTranslatableStringsDictionary dictionary = new NotTranslatableStringsDictionary(propertiesDirectory);

        // Read the default strings.xml file.
        SAXBuilder builder = new SAXBuilder();
        File xmlFile = new File(xmlPath);
        Document document = builder.build(xmlFile);

        // Read (or create) the language specific strings.xml file.
        File xmlOutputFolder = new File(OUTPUT_FOLDER + "-" + outputLang + "/");
        File xmlOutputFile = new File(OUTPUT_FOLDER + "-" + outputLang + "/strings.xml");
        if (!xmlOutputFolder.exists()) {
            xmlOutputFolder.mkdirs();
            xmlOutputFile.createNewFile();
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(xmlOutputFile))) {
                writer.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
                writer.newLine();
                writer.write("<resources>");
                writer.newLine();
                writer.write("</resources>");
                writer.newLine();
            }
        }
        Document outputDocument = builder.build(xmlOutputFile);
        Element outputRoot = outputDocument.getRootElement();

        // Strings that were already translated (by the developer or a previous run).
        Set<String> translatedStrings = new HashSet<>();
        for (Element existing : outputRoot.getChildren()) {
            translatedStrings.add(existing.getAttributeValue("name"));
        }

        // Collect the <string> values that must be translated.
        Element root = document.getRootElement();
        for (Element e : root.getChildren("string")) {
            String attributeValue = e.getAttributeValue("name");
            String attributeFormatted = e.getAttributeValue("formatted", NO_ATTRIBUTE_FOUND);
            String text = e.getText();
            if (dictionary.translatable(attributeValue)
                    && !translatedStrings.contains(attributeValue)
                    && !isOnlyNumbersAndSpecs(text)
                    && !text.startsWith("@")) {
                values.add(text);
                names.add(attributeValue);
                formatted.add(attributeFormatted);
            }
        }

        // Collect <plurals> and <string-array> elements (each <item> is translated too).
        List<Element> containers = new ArrayList<>();
        for (Element e : root.getChildren("plurals")) {
            if (dictionary.translatable(e.getAttributeValue("name")) && !translatedStrings.contains(e.getAttributeValue("name"))) {
                containers.add(e);
            }
        }
        for (Element e : root.getChildren("string-array")) {
            if (dictionary.translatable(e.getAttributeValue("name")) && !translatedStrings.contains(e.getAttributeValue("name"))) {
                containers.add(e);
            }
        }

        // <plurals>/<string-array> often live in SIBLING files (plurals.xml, arrays.xml), not in
        // strings.xml. Scan the rest of the source values/ folder so those get translated too;
        // Android merges resources by type, so writing them into the output strings.xml is valid.
        File valuesDir = xmlFile.getParentFile();
        File[] siblings = valuesDir == null ? null
                : valuesDir.listFiles((d, n) -> n.endsWith(".xml") && !n.equals(xmlFile.getName()));
        if (siblings != null) {
            for (File sibling : siblings) {
                Element sibRoot;
                try {
                    sibRoot = builder.build(sibling).getRootElement();
                } catch (Exception ex) {
                    continue; // not a parseable resource file
                }
                for (Element e : sibRoot.getChildren("plurals")) {
                    if (dictionary.translatable(e.getAttributeValue("name")) && !translatedStrings.contains(e.getAttributeValue("name"))) {
                        containers.add(e);
                    }
                }
                for (Element e : sibRoot.getChildren("string-array")) {
                    if (dictionary.translatable(e.getAttributeValue("name")) && !translatedStrings.contains(e.getAttributeValue("name"))) {
                        containers.add(e);
                    }
                }
            }
        }

        if (values.isEmpty() && containers.isEmpty()) {
            System.out.println("Nothing to translate for " + outputLang + ".");
            return;
        }

        // Translate and write the simple <string> values.
        List<String> fullTranslations = translateList(values, inputLang, outputLang);
        System.out.println("  translated " + fullTranslations.size() + "/" + values.size());
        for (int i = 0; i < fullTranslations.size(); i++) {
            Element newString = new Element("string");
            newString.setAttribute("name", names.get(i));
            String attributeFormatted = formatted.get(i);
            if (!attributeFormatted.equals(NO_ATTRIBUTE_FOUND)) {
                newString.setAttribute("formatted", attributeFormatted);
            }
            newString.setText(replaceUnscapedCharacters(fullTranslations.get(i)));
            outputRoot.addContent(newString);
        }

        // Translate and write <plurals> / <string-array> (each <item> translated, attributes kept).
        for (Element container : containers) {
            outputRoot.addContent(translateItemContainer(container, inputLang, outputLang));
        }

        // Persist changes.
        XMLOutputter output = new XMLOutputter();
        // PRESERVE text mode: keep leading/trailing whitespace in translated values (TRIM would strip it).
        output.setFormat(Format.getPrettyFormat().setTextMode(Format.TextMode.PRESERVE));
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(xmlOutputFile), StandardCharsets.UTF_8)) {
            output.output(outputDocument, writer);
        }
    }

    /** Translates a list of texts in small batches (keeps prompts manageable for CPU inference). */
    private List<String> translateList(List<String> texts, String inputLang, String outputLang) throws Exception {
        List<String> out = new ArrayList<>();
        int index = 0;
        while (index < texts.size()) {
            int end = Math.min(index + TRANSLATOR_PACKAGE_SIZE, texts.size());
            out.addAll(translateBatch(texts.subList(index, end), inputLang, outputLang));
            index = end;
        }
        return out;
    }

    /** Rebuilds a {@code <plurals>}/{@code <string-array>} with every {@code <item>} translated, keeping attributes. */
    private Element translateItemContainer(Element source, String inputLang, String outputLang) throws Exception {
        List<Element> items = source.getChildren("item");
        List<String> texts = new ArrayList<>();
        for (Element item : items) {
            texts.add(item.getText());
        }
        List<String> translated = translateList(texts, inputLang, outputLang);
        Element out = new Element(source.getName());
        out.setAttribute("name", source.getAttributeValue("name"));
        for (int i = 0; i < items.size(); i++) {
            Element newItem = new Element("item");
            String quantity = items.get(i).getAttributeValue("quantity");
            if (quantity != null) {
                newItem.setAttribute("quantity", quantity);
            }
            newItem.setText(replaceUnscapedCharacters(translated.get(i)));
            out.addContent(newItem);
        }
        System.out.println("  translated " + source.getName() + " '" + source.getAttributeValue("name") + "' (" + items.size() + " items)");
        return out;
    }

    /** Translates one batch in a single request: JSON keyed by index; placeholders preserved via the prompt. */
    @SuppressWarnings("unchecked")
    private List<String> translateBatch(List<String> batch, String inputLang, String outputLang) throws Exception {
        JSONObject items = new JSONObject();
        for (int i = 0; i < batch.size(); i++) {
            items.put(String.valueOf(i), batch.get(i));
        }

        String systemPrompt = buildSystemPrompt(languageName(inputLang), languageName(outputLang));
        String userPrompt = "Translate the values of this JSON object. Return ONLY a JSON object with the"
                + " exact same keys and the translated values:\n" + items.toJSONString();

        JSONArray messages = new JSONArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));

        // A malformed/empty LLM response (parse or HTTP failure) must not abort the whole run: fall back
        // to keeping the source strings for this batch (filled in below) and continue with the next batch/language.
        Map<String, String> translatedByKey;
        try {
            String content = chatCompletion(messages, true);
            translatedByKey = parseTranslations(content);
        } catch (Exception e) {
            System.out.println("  WARNING: batch translation failed (" + e.getMessage()
                    + "); keeping original strings for this batch.");
            translatedByKey = new HashMap<>();
        }

        List<String> result = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            String translated = translatedByKey.get(String.valueOf(i));
            if (translated == null) {
                // The model dropped this entry: keep the source text rather than losing it.
                System.out.println("  WARNING: missing translation for item " + i + "; keeping original.");
                translated = batch.get(i);
            }
            result.add(translated);
        }
        return result;
    }

    /**
     * Translates hardcoded UI labels, leaving proper names, emails, URLs, codes and data unchanged.
     * Returns a map original -> result (the original verbatim when it should not change).
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> translateUiLabels(List<String> texts, String inputLang, String outputLang) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        int index = 0;
        while (index < texts.size()) {
            int end = Math.min(index + TRANSLATOR_PACKAGE_SIZE, texts.size());
            List<String> batch = texts.subList(index, end);
            JSONObject items = new JSONObject();
            for (int i = 0; i < batch.size(); i++) {
                items.put(String.valueOf(i), batch.get(i));
            }
            JSONArray messages = new JSONArray();
            messages.add(message("system", buildLabelSystemPrompt(languageName(inputLang), languageName(outputLang))));
            messages.add(message("user", "Process this JSON object and return ONLY a JSON object with the exact same keys:\n" + items.toJSONString()));
            // On parse/HTTP failure, keep the source labels for this batch instead of aborting the run.
            Map<String, String> byKey;
            try {
                byKey = parseTranslations(chatCompletion(messages, true));
            } catch (Exception e) {
                System.out.println("  WARNING: label batch translation failed (" + e.getMessage()
                        + "); keeping original strings for this batch.");
                byKey = new HashMap<>();
            }
            for (int i = 0; i < batch.size(); i++) {
                String t = byKey.get(String.valueOf(i));
                result.put(batch.get(i), (t != null && !t.isEmpty()) ? t : batch.get(i));
            }
            index = end;
        }
        return result;
    }

    private static String buildLabelSystemPrompt(String inputLang, String outputLang) {
        return "You are a native " + outputLang + " localizer. Localize hardcoded Android UI strings from "
                + inputLang + " to " + outputLang + ". For each JSON value decide whether it is a human-facing "
                + "UI label or data:\n"
                + "1. If it is a UI label (button, title, menu, message), translate it into natural, fluent, "
                + "grammatically correct " + outputLang + " (respect gender, number and agreement; keep it short).\n"
                + "2. " + DO_NOT_TRANSLATE + "\n"
                + "Keep the same JSON keys and do not add explanations. "
                + "Return ONLY a valid JSON object mapping each key to its result.";
    }

    /** Detects the language of the default {@code strings.xml}; returns an ISO 639-1 code, or null on failure. */
    @SuppressWarnings("unchecked")
    public String detectSourceLanguage(String xmlPath) {
        try {
            Document document = new SAXBuilder().build(new File(xmlPath));
            List<String> samples = new ArrayList<>();
            for (Element e : (List<Element>) document.getRootElement().getChildren()) {
                String text = e.getText();
                if (text != null && text.trim().length() > 2 && text.matches(".*\\p{L}.*") && !text.startsWith("@")) {
                    samples.add(text.trim());
                }
                if (samples.size() >= 15) {
                    break;
                }
            }
            if (samples.isEmpty()) {
                return null;
            }
            JSONArray messages = new JSONArray();
            messages.add(message("system", "Identify the language of these UI strings. "
                    + "Reply with ONLY the two-letter ISO 639-1 code (e.g. en, es, fr), nothing else."));
            messages.add(message("user", String.join("\n", samples)));
            String token = chatCompletion(messages, false).trim().toLowerCase().split("\\s+")[0].replaceAll("[^a-z]", "");
            return token.length() >= 2 ? token.substring(0, 2) : null;
        } catch (Exception e) {
            System.out.println("Source language detection failed: " + e.getMessage());
            return null;
        }
    }

    /** Sends a chat-completion request and returns the assistant message content. */
    @SuppressWarnings("unchecked")
    private String chatCompletion(JSONArray messages, boolean jsonMode) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", false);
        try {
            body.put("temperature", Double.parseDouble(temperature));
        } catch (NumberFormatException ignored) {
            body.put("temperature", 0);
        }
        if (jsonMode) {
            // Hint OpenAI-compatible servers (incl. Ollama) to emit strict JSON. Ignored if unsupported.
            JSONObject responseFormat = new JSONObject();
            responseFormat.put("type", "json_object");
            body.put("response_format", responseFormat);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }
        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("LLM endpoint returned HTTP " + response.statusCode()
                    + ". Is the local model server running at " + endpoint + "? Response: " + response.body());
        }
        return extractContent(response.body());
    }

    private static String buildSystemPrompt(String inputLang, String outputLang) {
        return "You are a professional native " + outputLang + " software localizer for Android apps. "
                + "Translate UI string values from " + inputLang + " to " + outputLang + ". "
                + "Strict rules:\n"
                + "1. Translate ONLY the text values; never translate, add or remove the JSON keys.\n"
                + "2. Preserve EXACTLY every format placeholder and markup: %s, %d, %f, positional ones like "
                + "%1$s and %2$d, escape sequences (\\n, \\t, \\', \\\"), HTML/Android tags such as <b>, </b>, "
                + "<xliff:g ...>, named placeholders like {count}, and HTML entities such as &amp;.\n"
                + "3. Keep leading and trailing whitespace.\n"
                + "4. Write natural, fluent, idiomatic " + outputLang + " as it actually appears in real apps; "
                + "respect grammar, gender, number and agreement, and keep labels short.\n"
                + "5. " + DO_NOT_TRANSLATE + "\n"
                + "6. Do not add explanations, comments or markdown fences.\n"
                + "Return ONLY a valid JSON object mapping each original key to its translated string.";
    }

    @SuppressWarnings("unchecked")
    private static JSONObject message(String role, String content) {
        JSONObject m = new JSONObject();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /** Extracts the assistant message text from an OpenAI-compatible chat completion response. */
    private static String extractContent(String responseBody) throws Exception {
        JSONParser parser = new JSONParser();
        JSONObject parsed = (JSONObject) parser.parse(responseBody);
        JSONArray choices = (JSONArray) parsed.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("LLM response had no choices: " + responseBody);
        }
        JSONObject firstChoice = (JSONObject) choices.get(0);
        JSONObject messageObj = (JSONObject) firstChoice.get("message");
        if (messageObj == null || messageObj.get("content") == null) {
            throw new RuntimeException("LLM response had no message content: " + responseBody);
        }
        return (String) messageObj.get("content");
    }

    /** Parses the model output into a key -> translation map, tolerating markdown fences. */
    private Map<String, String> parseTranslations(String content) throws Exception {
        String json = stripCodeFences(content);
        JSONParser parser = new JSONParser();
        JSONObject obj = (JSONObject) parser.parse(json);
        Map<String, String> result = new LinkedHashMap<>();
        for (Object key : obj.keySet()) {
            Object value = obj.get(key);
            result.put(String.valueOf(key), value == null ? "" : String.valueOf(value));
        }
        return result;
    }

    /** Removes ```json ... ``` fences and any prose around the JSON object, if present. */
    private static String stripCodeFences(String content) {
        String trimmed = content.trim();
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }

    /**
     * Checks if a string only contains numbers and special characters (kept from the
     * previous implementation: such strings are not worth translating).
     */
    public boolean isOnlyNumbersAndSpecs(String string) {
        return string.matches("[\\d/@#$%^&_+=():\\s-]+");
    }

    /** Escapes unescaped double and single quotes as required by Android string resources. */
    public static String replaceUnscapedCharacters(String text) {
        String modifier1 = text.replaceAll("(?<!\\\\)\"", "\\\\\"");
        return modifier1.replaceAll("(?<!\\\\)'", "\\\\'");
    }

    /** Maps an ISO language code to an English language name to give the model clearer context. */
    private static String languageName(String code) {
        if (code == null) {
            return "";
        }
        String key = code.toLowerCase();
        int dash = key.indexOf('-');
        if (dash > 0) {
            key = key.substring(0, dash);
        }
        switch (key) {
            case "en": return "English";
            case "es": return "Spanish";
            case "hi": return "Hindi";
            case "ar": return "Arabic";
            case "ru": return "Russian";
            case "pt": return "Portuguese";
            case "fr": return "French";
            case "it": return "Italian";
            case "de": return "German";
            case "zh": return "Chinese";
            case "ja": return "Japanese";
            case "ko": return "Korean";
            case "ms": return "Malay";
            case "bn": return "Bengali";
            case "nl": return "Dutch";
            case "tr": return "Turkish";
            case "pl": return "Polish";
            case "vi": return "Vietnamese";
            default: return code;
        }
    }

    /** Returns the configured LLM endpoint (system property &gt; env var &gt; .env &gt; default). */
    public static String resolveEndpoint(String settingsDir) {
        return resolve(loadDotEnv(settingsDir), "ITDROID_LLM_ENDPOINT", "http://localhost:11434/v1/chat/completions");
    }

    /** Returns the configured LLM API token, or "" if none (system property &gt; env var &gt; .env). */
    public static String resolveApiKey(String settingsDir) {
        return resolve(loadDotEnv(settingsDir), "ITDROID_LLM_API_KEY", "");
    }

    /** Resolves a configuration value from system properties, environment, then .env, else default. */
    private static String resolve(Map<String, String> env, String key, String defaultValue) {
        String fromProperty = System.getProperty(key);
        if (fromProperty != null && !fromProperty.isEmpty()) {
            return fromProperty;
        }
        String fromEnvVar = System.getenv(key);
        if (fromEnvVar != null && !fromEnvVar.isEmpty()) {
            return fromEnvVar;
        }
        String fromFile = env.get(key);
        if (fromFile != null && !fromFile.isEmpty()) {
            return fromFile;
        }
        return defaultValue;
    }

    /**
     * Loads a simple KEY=VALUE .env file. Looks first in the provided settings directory
     * (the {@code <settingsDir>} CLI argument) and falls back to the working directory.
     */
    private static Map<String, String> loadDotEnv(String settingsDir) {
        Map<String, String> values = new HashMap<>();
        Path envPath = null;
        if (settingsDir != null && !settingsDir.isEmpty()) {
            Path candidate = Paths.get(settingsDir, ".env");
            if (Files.isRegularFile(candidate)) {
                envPath = candidate;
            }
        }
        if (envPath == null) {
            Path candidate = Paths.get(System.getProperty("user.dir"), ".env");
            if (Files.isRegularFile(candidate)) {
                envPath = candidate;
            }
        }
        if (envPath == null) {
            return values;
        }
        try {
            for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
        } catch (Exception e) {
            System.out.println("Could not read .env file: " + e.getMessage());
        }
        return values;
    }
}
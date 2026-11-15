package uniandes.tsdl.itdroid.translator;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.OutputStreamWriter;
import java.io.StringReader;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jdom2.Content;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Text;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import uniandes.tsdl.itdroid.helper.NotTranslatableStringsDictionary;
import uniandes.tsdl.itdroid.helper.StringInventory;

/**
 * Translates strings via a local LLM behind an OpenAI-compatible endpoint (Ollama, llama.cpp,
 * LM Studio). Runs on the host, no cloud key. Configured by {@code ITDROID_LLM_*} system
 * properties / env vars / {@code .env} (see {@code .env.example}).
 *
 * <p>The texts come from the {@link StringInventory}: every string, plurals item and array item of
 * the app, deduplicated, with the ones that must stay verbatim already flagged. All of them are
 * sent in global batches (not one request per container), and the inventory acts as translation
 * memory so a text is never translated twice.
 */
public class LLMTranslator implements TranslationInterface {

    /** Strings translated per LLM request. Keeps prompts small enough for local inference. */
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

    private final String propertiesDirectory;
    private final String endpoint;
    private final String model;
    private final String apiKey;
    private final int timeoutSeconds;
    private final String temperature;
    private final HttpClient httpClient;

    /** Source of the texts and translation memory; built on demand when none was given. */
    private StringInventory inventory;

    /**
     * Request style. General chat models get batched JSON. Translation-specialised models expect
     * ONE text per user message and no JSON: {@code translategemma} uses Google's fixed prompt,
     * {@code template} uses {@code ITDROID_LLM_PROMPT_TEMPLATE} (placeholders {source}, {target},
     * {text}; default: the Hy-MT2 default-translation prompt), {@code plamo} sends the options of
     * PLaMo Translate (source and target language codes) as the system message and the bare text
     * as the user message. {@code ITDROID_LLM_STYLE} = {@code auto} (by model name), {@code json},
     * {@code translategemma}, {@code template} or {@code plamo}.
     */
    private final boolean nativeTranslateGemma;
    private final boolean templateStyle;
    private final boolean plamoStyle;
    private final String promptTemplate;
    /**
     * Mask format placeholders before a single-text request and restore them afterwards (the
     * original ITDroid did this with IBM Watson): small translation models mangle {@code %1$s} or
     * {@code {count}} more often than they copy them. {@code ITDROID_LLM_MASK_PLACEHOLDERS}.
     */
    private final boolean maskPlaceholders;
    private static final String DEFAULT_TEMPLATE = "Translate the following text into {target}. Note that you should "
            + "**only output the translated result without any additional explanation**:\n\n{text}";
    /** Concurrent requests in the one-text-per-request mode (Ollama serves them in parallel when it can). */
    private static final int NATIVE_PARALLELISM = 4;

    /** File of the settings folder with the terms that are never translated, one per line (# for comments). */
    public static final String PROTECTED_TERMS_FILE = "protected-terms.txt";
    /**
     * Terms that go through every translation unchanged: names of people, places, products. They
     * travel masked, like placeholders, so no model can translate, transliterate or inflect them.
     */
    private final List<String> protectedTerms = new ArrayList<>();
    private volatile java.util.regex.Pattern protectedPattern;
    /** E-mail addresses and URLs inside a text: data, masked the same way. */
    private static final java.util.regex.Pattern EMAIL_OR_URL = java.util.regex.Pattern.compile(
            "[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+|https?://[^\\s<>\"]+|www\\.[^\\s<>\"]+");
    /**
     * A figure written with separators: an amount (6,568.65), a time (09:41), a date (20/09/2026), a
     * version (1.13.0), a phone number in groups. A model rewrites these the way the target language
     * writes them (6.568,65 in Spanish, 6 568,65 in Russian), and a figure of the app is data: it is
     * masked like an address. A bare number ("2 items") stays in sight, because the words around it
     * have to agree with it.
     */
    private static final java.util.regex.Pattern FIGURE = java.util.regex.Pattern.compile(
            "\\d+(?:[.,:/'\\u00a0\\u202f -]\\d+)+");

    /**
     * What PLaMo Translate is told about a short text (up to three words), which on its own is
     * ambiguous: "Open" is a verb on a button and an adjective in a sentence. With this line it
     * gives "Abrir" instead of "Abierto" and "Declinar" instead of "Declive". Longer texts carry
     * their own context. {@code ITDROID_LLM_CONTEXT}; "none" turns it off.
     */
    private final String shortTextContext;
    private static final String DEFAULT_CONTEXT = "Text of a button, menu or label in the user interface of a mobile app.";

    /** The languages PLaMo Translate handles, by the code it expects (its model card lists these 53). */
    private static final Set<String> PLAMO_LANGUAGES = Set.of("ja", "en", "zh-hans", "zh-hant", "ko", "ar", "az", "be", "bg", "bn",
            "ca", "ceb", "de", "es", "et", "eu", "fa", "fr", "gu", "he", "hi", "hr", "hu", "hy", "id", "it", "jv", "ka", "kk", "km",
            "lt", "lv", "ml", "mr", "ms", "my", "ne", "nl", "pa", "pl", "ru", "si", "sk", "sl", "sr", "sw", "ta", "te", "th", "tl",
            "ur", "uz", "vi");

    /** Run-wide totals for the performance section of report.json (every instance adds to them). */
    private static final java.util.concurrent.atomic.AtomicLong REQUESTS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong REQUEST_MS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong WALL_MS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong TEXTS_SENT = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong TEXTS_REUSED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong TEXTS_KEPT = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong ANSWERS_REJECTED = new java.util.concurrent.atomic.AtomicLong();

    public LLMTranslator(String directory) {
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
        String style = resolve(env, "ITDROID_LLM_STYLE", "auto").trim().toLowerCase();
        this.templateStyle = style.equals("template")
                || (style.equals("auto") && model.toLowerCase().matches(".*(hy-?mt|hunyuan-?mt).*"));
        this.nativeTranslateGemma = style.equals("translategemma")
                || (style.equals("auto") && model.toLowerCase().contains("translategemma"));
        this.plamoStyle = style.equals("plamo") || (style.equals("auto") && model.toLowerCase().contains("plamo"));
        this.promptTemplate = resolve(env, "ITDROID_LLM_PROMPT_TEMPLATE", DEFAULT_TEMPLATE).replace("\\n", "\n");
        String mask = resolve(env, "ITDROID_LLM_MASK_PLACEHOLDERS", "").trim().toLowerCase();
        this.maskPlaceholders = mask.isEmpty() ? (templateStyle || nativeTranslateGemma || plamoStyle)
                : (mask.equals("true") || mask.equals("yes") || mask.equals("1") || mask.equals("on"));
        String context = resolve(env, "ITDROID_LLM_CONTEXT", DEFAULT_CONTEXT).trim();
        this.shortTextContext = context.equalsIgnoreCase("none") ? "" : context;
        loadProtectedTerms(directory);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    private void loadProtectedTerms(String directory) {
        if (directory == null || directory.isEmpty()) {
            return;
        }
        Path file = Paths.get(directory, PROTECTED_TERMS_FILE);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<String> terms = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String term = line.trim();
                if (!term.isEmpty() && !term.startsWith("#")) {
                    terms.add(term);
                }
            }
            addProtectedTerms(terms);
        } catch (Exception e) {
            System.out.println("WARNING: could not read " + file + " (" + e.getMessage() + "); no term is protected");
        }
    }

    /** Adds terms that must come out of every translation exactly as written (case-sensitive, whole words). */
    public synchronized void addProtectedTerms(java.util.Collection<String> terms) {
        for (String term : terms) {
            String t = term == null ? "" : term.trim();
            if (t.length() >= 2 && !protectedTerms.contains(t)) {
                protectedTerms.add(t);
            }
        }
        if (protectedTerms.isEmpty()) {
            protectedPattern = null;
            return;
        }
        List<String> longestFirst = new ArrayList<>(protectedTerms);
        longestFirst.sort((a, b) -> b.length() - a.length());      // "New York City" before "New York"
        StringBuilder alternatives = new StringBuilder();
        for (String term : longestFirst) {
            alternatives.append(alternatives.length() == 0 ? "" : "|").append(java.util.regex.Pattern.quote(term));
        }
        protectedPattern = java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alternatives + ")(?![\\p{L}\\p{N}])");
    }

    /** The terms protected in this run. */
    public synchronized List<String> protectedTerms() {
        return new ArrayList<>(protectedTerms);
    }

    /**
     * True for a name that is safe to protect without being asked: several words, or a word with a
     * capital, a digit or a symbol inside ("ShiftSwift", "uMath+", "Super Productivity"). A single
     * ordinary word ("Tasks", "Notes") is not: protecting it would leave that word untranslated
     * wherever a sentence starts with it.
     */
    public static boolean looksLikeBrand(String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() < 3) {
            return false;
        }
        return n.matches(".*\\s.*") || n.substring(1).matches(".*[\\p{Lu}\\p{N}+&@#!].*");
    }

    public LLMTranslator(String directory, StringInventory inventory) {
        this(directory);
        this.inventory = inventory;
    }

    /** The engine as configured, for the environment section of report.json. */
    @SuppressWarnings("unchecked")
    public JSONObject settings() {
        JSONObject o = new JSONObject();
        o.put("endpoint", endpoint);
        o.put("model", model);
        o.put("style", plamoStyle ? "plamo" : nativeTranslateGemma ? "translategemma" : templateStyle ? "template" : "json");
        o.put("maskPlaceholders", maskPlaceholders);
        o.put("protectedTerms", (long) protectedTerms().size());
        if (plamoStyle) {
            o.put("shortTextContext", shortTextContext);
        }
        o.put("temperature", temperature);
        o.put("timeoutSeconds", timeoutSeconds);
        return o;
    }

    /** Totals of the calls made so far by every instance, for the performance section of report.json. */
    @SuppressWarnings("unchecked")
    public static JSONObject stats() {
        JSONObject o = new JSONObject();
        o.put("requests", REQUESTS.get());
        // Sum of request latencies (parallel requests overlap) vs. the time the pipeline waited for translations.
        o.put("requestSeconds", Math.round(REQUEST_MS.get() / 100.0) / 10.0);
        o.put("wallSeconds", Math.round(WALL_MS.get() / 100.0) / 10.0);
        o.put("textsSent", TEXTS_SENT.get());
        o.put("textsReused", TEXTS_REUSED.get());
        o.put("textsKeptInSource", TEXTS_KEPT.get());
        o.put("answersRejected", ANSWERS_REJECTED.get());
        return o;
    }

    /**
     * Writes {@code values-<outputLang>/strings.xml} next to the given default {@code strings.xml}
     * with every translatable resource of the inventory (strings, plurals and string-arrays,
     * wherever they lived) translated. Resources the target folder already defines are left alone.
     */
    @Override
    public void translate(String xmlPath, String inputLang, String outputLang) throws Exception {
        translate(xmlPath, inputLang, outputLang, null);
    }

    /**
     * The same, restricted to the resources with the given names (null for all). Used for a
     * language the app already ships: its own translation is evaluated as it is, and only the
     * strings ITDroid added to the app (the literals moved out of the XML) are filled in.
     */
    public void translate(String xmlPath, String inputLang, String outputLang, Set<String> onlyNames) throws Exception {
        File xmlFile = new File(xmlPath).getAbsoluteFile();
        File valuesDir = xmlFile.getParentFile();
        File resDir = valuesDir.getParentFile();
        if (inventory == null) {
            inventory = StringInventory.collect(resDir.getParentFile().getPath(), "",
                    new NotTranslatableStringsDictionary(propertiesDirectory));
        }
        System.out.println("LLM translation: " + inputLang + " -> " + outputLang
                + " using model '" + model + "' at " + endpoint);

        SAXBuilder builder = new SAXBuilder();
        File xmlOutputFolder = new File(resDir, "values-" + outputLang);
        File xmlOutputFile = new File(xmlOutputFolder, "strings.xml");
        if (!xmlOutputFile.exists()) {
            xmlOutputFolder.mkdirs();
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

        // Resources already defined for this language (by the developer, a library or a previous
        // run) win: defining one twice makes aapt2 refuse the build ("conflicting value").
        Set<String> defined = definedNames(xmlOutputFolder, builder);

        LinkedHashMap<String, List<StringInventory.Entry>> groups = new LinkedHashMap<>();
        for (StringInventory.Entry e : inventory.resourceEntries()) {
            if (defined.contains(e.name) || (onlyNames != null && !onlyNames.contains(e.name))) {
                continue;
            }
            groups.computeIfAbsent(e.groupKey(), k -> new ArrayList<>()).add(e);
        }
        LinkedHashSet<String> texts = new LinkedHashSet<>();
        for (List<StringInventory.Entry> g : groups.values()) {
            for (StringInventory.Entry e : g) {
                if (e.translatable) {
                    texts.add(e.text);
                }
            }
        }
        if (texts.isEmpty()) {
            System.out.println("Nothing to translate for " + outputLang + ".");
            return;
        }

        Map<String, String> translated = translateResourceTexts(new ArrayList<>(texts), inputLang, outputLang);
        System.out.println("  translated " + translated.size() + "/" + texts.size());

        int written = 0;
        for (List<StringInventory.Entry> g : groups.values()) {
            StringInventory.Entry first = g.get(0);
            if (StringInventory.KIND_STRING.equals(first.kind)) {
                if (!first.translatable) {
                    continue;   // data/reference: the default value is used in every language
                }
                Element s = new Element("string");
                s.setAttribute("name", first.name);
                if (first.formatted != null) {
                    s.setAttribute("formatted", first.formatted);
                }
                setTranslatedContent(s, translated.getOrDefault(first.text, first.text));
                outputRoot.addContent(s);
                written++;
            } else {
                boolean any = false;
                for (StringInventory.Entry e : g) {
                    any |= e.translatable;
                }
                if (!any) {
                    continue;
                }
                // Containers are all-or-nothing: every item is written, verbatim when not translatable.
                Element c = new Element(first.kind);
                c.setAttribute("name", first.name);
                for (StringInventory.Entry e : g) {
                    Element item = new Element("item");
                    if (e.quantity != null) {
                        item.setAttribute("quantity", e.quantity);
                    }
                    setTranslatedContent(item, e.translatable ? translated.getOrDefault(e.text, e.text) : e.text);
                    c.addContent(item);
                }
                outputRoot.addContent(c);
                written++;
            }
        }

        XMLOutputter output = new XMLOutputter();
        // PRESERVE text mode: keep leading/trailing whitespace in translated values (TRIM would strip it).
        output.setFormat(Format.getPrettyFormat().setTextMode(Format.TextMode.PRESERVE));
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(xmlOutputFile), StandardCharsets.UTF_8)) {
            output.output(outputDocument, writer);
        }
        System.out.println("  " + written + " resources written to " + xmlOutputFile.getPath());
    }

    /** Every resource name defined in the target values-&lt;lang&gt;/ folder (all its xml files). */
    private static Set<String> definedNames(File folder, SAXBuilder builder) {
        Set<String> names = new HashSet<>();
        File[] files = folder.listFiles((d, n) -> n.toLowerCase().endsWith(".xml"));
        if (files == null) {
            return names;
        }
        for (File f : files) {
            try {
                for (Element existing : builder.build(f).getRootElement().getChildren()) {
                    String n = existing.getAttributeValue("name");
                    if (n != null) {
                        names.add(n);
                    }
                }
            } catch (Exception ex) {
                // not a parseable resource file: nothing to protect
            }
        }
        return names;
    }

    /**
     * Sets a translated value as the element's content. The value is the inner XML of the source
     * (entities and inline markup such as {@code <b>} or {@code <xliff:g>}), so it is parsed back;
     * if the model broke the markup, it is stored as plain text instead of failing the build.
     */
    private static void setTranslatedContent(Element target, String value) {
        if (isQuoted(value)) {
            // write what is inside the quotes as usual and put the quotes back around it, unescaped
            setTranslatedContent(target, value.substring(1, value.length() - 1));
            target.addContent(0, new Text("\""));
            target.addContent(new Text("\""));
            return;
        }
        try {
            String wrapped = "<x xmlns:xliff=\"urn:oasis:names:tc:xliff:document:1.2\""
                    + " xmlns:tools=\"http://schemas.android.com/tools\">" + value + "</x>";
            Element parsed = new SAXBuilder().build(new StringReader(wrapped)).getRootElement();
            List<Content> content = parsed.removeContent();
            escapeTextNodes(content);
            target.setContent(content);
        } catch (Exception e) {
            String plain = value.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&quot;", "\"").replace("&apos;", "'");
            target.setText(replaceUnscapedCharacters(plain));
        }
    }

    /** Applies the Android quote escaping to text nodes only (never inside tags/attributes). */
    private static void escapeTextNodes(List<Content> content) {
        for (Content c : content) {
            if (c instanceof Text) {
                Text t = (Text) c;
                t.setText(replaceUnscapedCharacters(t.getText()));
            } else if (c instanceof Element) {
                escapeTextNodes(((Element) c).getContent());
            }
        }
    }

    /**
     * Translates resource texts (strings, plurals and array items) in batches, reusing the inventory
     * memory. Returns source text -> translation for every input (the source itself on failure).
     */
    public Map<String, String> translateResourceTexts(List<String> texts, String inputLang, String outputLang) {
        return translateTexts(texts, inputLang, outputLang, buildSystemPrompt(languageName(inputLang), languageName(outputLang)),
                "Translate the values of this JSON object. Return ONLY a JSON object with the exact same keys and the translated values:\n",
                "resource texts");
    }

    /**
     * Translates hardcoded UI labels, leaving proper names, emails, URLs, codes and data unchanged.
     * Returns a map original -> result (the original verbatim when it should not change).
     */
    public Map<String, String> translateUiLabels(List<String> texts, String inputLang, String outputLang) {
        return translateTexts(texts, inputLang, outputLang, buildLabelSystemPrompt(languageName(inputLang), languageName(outputLang)),
                "Process this JSON object and return ONLY a JSON object with the exact same keys:\n",
                "hardcoded labels");
    }

    /** Source characters per request. Long texts (licenses, help pages) otherwise overflow the model's context and truncate the JSON. */
    private static final int BATCH_CHAR_BUDGET = 2500;

    private Map<String, String> translateTexts(List<String> texts, String inputLang, String outputLang, String systemPrompt,
            String userPrefix, String what) {
        Map<String, String> result = new LinkedHashMap<>();
        LinkedHashSet<String> unique = new LinkedHashSet<>();   // each text once, in the order given
        for (String t : texts) {
            String memory = inventory != null ? inventory.translation(outputLang, t) : null;
            if (memory != null) {
                result.put(t, memory);
            } else {
                unique.add(t);
            }
        }
        List<String> pending = new ArrayList<>(unique);
        long start = System.currentTimeMillis();
        int[] requests = {0};
        List<String> missing;
        if (nativeTranslateGemma || templateStyle || plamoStyle) {
            // One request per text in the model's own prompt; a second attempt for the ones that
            // came back empty or lost a placeholder.
            missing = translateNativePass(pending, inputLang, outputLang, result, requests);
            if (!missing.isEmpty()) {
                System.out.println("  retrying " + missing.size() + " " + what + " whose answer could not be used");
                missing = translateNativePass(missing, inputLang, outputLang, result, requests);
            }
        } else {
            // First pass in normal batches; whatever the model dropped or returned empty is retried once in
            // small batches (a truncated or empty answer usually comes from a long batch), then kept as is.
            missing = translatePass(pending, TRANSLATOR_PACKAGE_SIZE, BATCH_CHAR_BUDGET, outputLang, systemPrompt, userPrefix, result, requests);
            if (!missing.isEmpty()) {
                System.out.println("  retrying " + missing.size() + " " + what + " the model left out, in smaller batches");
                missing = translatePass(missing, 5, 1200, outputLang, systemPrompt, userPrefix, result, requests);
            }
        }
        for (String t : missing) {
            result.put(t, t);
        }
        if (!missing.isEmpty()) {
            System.out.println("  WARNING: " + missing.size() + " " + what + " kept in the source language (no usable answer from the model)");
        }
        System.out.println(String.format(Locale.ROOT, "  LLM: %d %s -> %s in %.1f s (%d requests, %d reused from the inventory)",
                pending.size(), what, outputLang, (System.currentTimeMillis() - start) / 1000.0, requests[0],
                texts.size() - pending.size()));
        WALL_MS.addAndGet(System.currentTimeMillis() - start);
        TEXTS_SENT.addAndGet(pending.size());
        TEXTS_REUSED.addAndGet(texts.size() - pending.size());
        TEXTS_KEPT.addAndGet(missing.size());
        return result;
    }

    /**
     * TranslateGemma pass: every text in its own request, a few in flight at a time. A translation
     * that lost a format placeholder ({@code %1$s}, {@code {count}}, {@code \n}, a tag) is rejected,
     * because that would break the app at runtime; the caller retries and finally keeps the source.
     */
    private List<String> translateNativePass(List<String> texts, String inputLang, String outputLang,
            Map<String, String> result, int[] requests) {
        List<String> missing = new ArrayList<>();
        if (texts.isEmpty()) {
            return missing;
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(NATIVE_PARALLELISM);
        try {
            List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
            for (String t : texts) {
                futures.add(pool.submit(() -> translateOneNative(t, inputLang, outputLang)));
            }
            for (int i = 0; i < texts.size(); i++) {
                String source = texts.get(i);
                String out = null;
                try {
                    out = futures.get(i).get();
                } catch (Exception e) {
                    System.out.println("  WARNING: request failed for '" + preview(source) + "': " + describe(e));
                }
                requests[0]++;
                String rejected = rejectReason(source, out, outputLang);
                if (rejected != null) {
                    if (out != null && !out.trim().isEmpty()) {
                        ANSWERS_REJECTED.incrementAndGet();
                        System.out.println("  WARNING: answer rejected (" + rejected + "): '" + preview(source) + "' -> '" + preview(out) + "'");
                    }
                    missing.add(source);
                } else {
                    result.put(source, out);
                    if (inventory != null) {
                        synchronized (inventory) {
                            inventory.putTranslation(outputLang, source, out);
                        }
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return missing;
    }

    /**
     * One text per request, in the model's own prompt: TranslateGemma's fixed instruction (two
     * blank lines before the text), the configured template, or PLaMo's options as the system
     * message. Placeholders are masked with neutral tokens before the request and restored
     * afterwards when masking is on. The answer keeps the leading and trailing whitespace of the
     * source: a literal is often one piece of a sentence built in the code ("Items: " + n +
     * " of 3"), and without its edge space the pieces run together.
     */
    private String translateOneNative(String wholeText, String inputLang, String outputLang) {
        // The quotes around a whole resource value are Android syntax, not text: the model gets
        // what is inside them and the answer is wrapped again.
        boolean quoted = isQuoted(wholeText);
        String out = translateOneNativeBare(quoted ? wholeText.substring(1, wholeText.length() - 1) : wholeText, inputLang, outputLang);
        return out == null || !quoted ? out : "\"" + out + "\"";
    }

    @SuppressWarnings("unchecked")
    private String translateOneNativeBare(String text, String inputLang, String outputLang) {
        String src = languageName(inputLang);
        String tgt = languageName(outputLang);
        List<String> masked = new ArrayList<>();
        String body = maskTerms(maskPlaceholders ? mask(text, masked) : text, masked);
        if (plamoStyle) {
            // The whole user message is translated, so the text goes alone and without its edge
            // whitespace; the options go in the system message, as the model card specifies. What
            // follows a "---" line there is context for the model, not text to translate.
            String options = "[plamo-translate]\nsource_lang=" + plamoCode(inputLang) + "\ntarget_lang=" + plamoCode(outputLang);
            if (!shortTextContext.isEmpty() && isShortLabel(text)) {
                options += "\n---\n" + shortTextContext;
            }
            JSONArray messages = new JSONArray();
            messages.add(message("system", options));
            messages.add(message("user", body.strip()));
            JSONObject extra = new JSONObject();
            JSONArray stop = new JSONArray();
            stop.add("<|plamo:tag|>");
            stop.add("<|plamo:bos|>");
            extra.put("stop", stop);
            extra.put("max_tokens", 1024L);
            try {
                String out = chatCompletion(messages, false, extra).trim();
                return withEdges(text, restore(out, masked, text));
            } catch (Exception e) {
                System.out.println("  WARNING: translation request failed for '" + preview(text) + "': " + describe(e));
                return null;
            }
        }
        String prompt;
        if (templateStyle) {
            prompt = promptTemplate.replace("{source}", src).replace("{target}", tgt).replace("{text}", body);
        } else {
            prompt = "You are a professional " + src + " (" + isoCode(inputLang) + ") to " + tgt + " (" + isoCode(outputLang)
                    + ") translator. Your goal is to accurately convey the meaning and nuances of the original " + src
                    + " text while adhering to " + tgt + " grammar, vocabulary, and cultural sensitivities.\n"
                    + "Produce only the " + tgt + " translation, without any additional explanations or commentary. "
                    + "Please translate the following " + src + " text into " + tgt + ":\n\n\n" + body;
        }
        JSONArray messages = new JSONArray();
        messages.add(message("user", prompt));
        try {
            String out = chatCompletion(messages, false).trim();
            // The model occasionally wraps the answer in quotes the source did not have.
            if (out.length() >= 2 && out.startsWith("\"") && out.endsWith("\"") && !text.trim().startsWith("\"")) {
                out = out.substring(1, out.length() - 1).trim();
            }
            return withEdges(text, restore(out, masked, text));
        } catch (Exception e) {
            System.out.println("  WARNING: translation request failed for '" + preview(text) + "': " + describe(e));
            return null;
        }
    }

    /** Puts back what was masked before the request (placeholders, protected terms). */
    private String restore(String out, List<String> masked, String source) {
        if (!maskPlaceholders && masked.isEmpty()) {
            return out;
        }
        return unmask(out, masked, maskPlaceholders && source.contains("\\n"));
    }

    /** A text of up to three words: a label, whose meaning depends on where it is shown. */
    static boolean isShortLabel(String text) {
        String t = text.strip();
        return !t.isEmpty() && t.length() <= 30 && t.split("\\s+").length <= 3;
    }

    /**
     * Replaces protected terms, e-mail addresses, URLs and figures with the same numbered tokens used
     * for placeholders ({@code <ph0>}...), adding the originals to {@code originals}.
     */
    String maskTerms(String text, List<String> originals) {
        String out = maskMatches(EMAIL_OR_URL, text, originals);
        java.util.regex.Pattern terms = protectedPattern;
        if (terms != null) {
            out = maskMatches(terms, out, originals);
        }
        return maskMatches(FIGURE, out, originals);
    }

    /** True when every figure of the source is in the translation as it was written, as many times. */
    static boolean figuresKept(String source, String translated) {
        Map<String, Integer> needed = new HashMap<>();
        java.util.regex.Matcher m = FIGURE.matcher(source);
        while (m.find()) {
            needed.merge(m.group(), 1, Integer::sum);
        }
        return allIn(needed, translated);
    }

    private static boolean allIn(Map<String, Integer> needed, String translated) {
        for (Map.Entry<String, Integer> e : needed.entrySet()) {
            int found = 0;
            for (int at = translated.indexOf(e.getKey()); at >= 0; at = translated.indexOf(e.getKey(), at + e.getKey().length())) {
                found++;
            }
            if (found < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static String maskMatches(java.util.regex.Pattern pattern, String text, List<String> originals) {
        java.util.regex.Matcher m = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            originals.add(m.group());
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("<ph" + (originals.size() - 1) + ">"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** True when every protected term, e-mail address and URL of the source is in the translation as many times. */
    boolean termsKept(String source, String translated) {
        Map<String, Integer> needed = new HashMap<>();
        java.util.regex.Matcher m = EMAIL_OR_URL.matcher(source);
        while (m.find()) {
            needed.merge(m.group(), 1, Integer::sum);
        }
        java.util.regex.Pattern terms = protectedPattern;
        if (terms != null) {
            m = terms.matcher(source);
            while (m.find()) {
                needed.merge(m.group(), 1, Integer::sum);
            }
        }
        return allIn(needed, translated);
    }

    // ------------------------------------------------------------------ is the answer a translation?

    /**
     * How a model starts when it comments instead of translating: "Note: ...", "Translation: ...",
     * "Here is ...", "Sure, ...". The labels need their colon, so that "Translation settings" or
     * "Nota nueva" are not taken for a comment.
     */
    private static final java.util.regex.Pattern COMMENTARY = java.util.regex.Pattern.compile(
            "(?is)^\\W{0,3}(?:(?:note|nota|translation|traducci[o\u00f3]n|\u043f\u0435\u0440\u0435\u0432\u043e\u0434|\u043f\u0440\u0438\u043c\u0435\u0447\u0430\u043d\u0438\u0435)\\s*:"
            + "|here is\\b|here's\\b|sure[,!.]|certainly[,!.]|of course[,!.]|aqu[i\u00ed] (?:est[a\u00e1]|tienes)\\b|claro[,!.]).*");

    /** The script a language is written in, for the languages that do not use the Latin alphabet. */
    private static final Map<String, Character.UnicodeScript[]> SCRIPTS = new HashMap<>();
    static {
        for (String code : new String[] {"ru", "uk", "bg", "be", "sr", "mk", "kk", "ky", "mn", "tg"}) {
            SCRIPTS.put(code, new Character.UnicodeScript[] {Character.UnicodeScript.CYRILLIC});
        }
        for (String code : new String[] {"ar", "fa", "ur", "ps"}) {
            SCRIPTS.put(code, new Character.UnicodeScript[] {Character.UnicodeScript.ARABIC});
        }
        for (String code : new String[] {"he", "iw", "yi"}) {
            SCRIPTS.put(code, new Character.UnicodeScript[] {Character.UnicodeScript.HEBREW});
        }
        for (String code : new String[] {"hi", "mr", "ne"}) {
            SCRIPTS.put(code, new Character.UnicodeScript[] {Character.UnicodeScript.DEVANAGARI});
        }
        SCRIPTS.put("el", new Character.UnicodeScript[] {Character.UnicodeScript.GREEK});
        SCRIPTS.put("th", new Character.UnicodeScript[] {Character.UnicodeScript.THAI});
        SCRIPTS.put("ko", new Character.UnicodeScript[] {Character.UnicodeScript.HANGUL, Character.UnicodeScript.HAN});
        SCRIPTS.put("ja", new Character.UnicodeScript[] {Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HAN});
        SCRIPTS.put("zh", new Character.UnicodeScript[] {Character.UnicodeScript.HAN});
        SCRIPTS.put("ka", new Character.UnicodeScript[] {Character.UnicodeScript.GEORGIAN});
        SCRIPTS.put("hy", new Character.UnicodeScript[] {Character.UnicodeScript.ARMENIAN});
        SCRIPTS.put("bn", new Character.UnicodeScript[] {Character.UnicodeScript.BENGALI});
        SCRIPTS.put("ta", new Character.UnicodeScript[] {Character.UnicodeScript.TAMIL});
        SCRIPTS.put("te", new Character.UnicodeScript[] {Character.UnicodeScript.TELUGU});
        SCRIPTS.put("gu", new Character.UnicodeScript[] {Character.UnicodeScript.GUJARATI});
        SCRIPTS.put("pa", new Character.UnicodeScript[] {Character.UnicodeScript.GURMUKHI});
        SCRIPTS.put("ml", new Character.UnicodeScript[] {Character.UnicodeScript.MALAYALAM});
        SCRIPTS.put("si", new Character.UnicodeScript[] {Character.UnicodeScript.SINHALA});
        SCRIPTS.put("km", new Character.UnicodeScript[] {Character.UnicodeScript.KHMER});
        SCRIPTS.put("my", new Character.UnicodeScript[] {Character.UnicodeScript.MYANMAR});
    }

    /**
     * Why an answer cannot be stored as the translation of a text, or null when it can. A model
     * sometimes answers with something that is not a translation at all: nothing, the text with a
     * placeholder or a protected term missing (which breaks the app or the rule), its own
     * instruction repeated, a comment about the task, or the text left in the wrong alphabet. Such
     * an answer is asked for again and, if it comes back the same, the source text is kept.
     */
    String rejectReason(String source, String out, String outputLang) {
        if (out == null || out.trim().isEmpty()) {
            return "empty answer";
        }
        if (!placeholdersKept(source, out)) {
            return "a placeholder was lost";
        }
        if (!termsKept(source, out)) {
            return "a protected term was lost";
        }
        if (!figuresKept(source, out)) {
            return "a figure was rewritten";
        }
        String s = source.trim();
        String o = out.trim();
        if (o.length() > 3 * s.length() + 30) {
            return "far longer than the text";
        }
        if (COMMENTARY.matcher(o).matches() && !COMMENTARY.matcher(s).matches()) {
            return "a comment instead of a translation";
        }
        if (inWrongScript(s, o, outputLang)) {
            return "not written in the script of the language";
        }
        return null;
    }

    /**
     * True when the language is written in a non-Latin script and the answer has letters but none
     * of that script, once placeholders and protected terms are set aside: the model answered in
     * another language. An answer equal to the source is not that: a brand or an acronym
     * ("GitHub", "GPS") is rightly left as it is.
     */
    private boolean inWrongScript(String source, String out, String outputLang) {
        String code = outputLang == null ? "" : outputLang.toLowerCase(Locale.ROOT);
        int dash = code.indexOf('-');
        Character.UnicodeScript[] expected = SCRIPTS.get(dash > 0 ? code.substring(0, dash) : code);
        if (expected == null || !source.matches("(?s).*\\p{L}.*")) {
            return false;
        }
        if (out.trim().equals(source.trim())) {
            return false;   // left as it was (a brand, an acronym): the model's choice, not a wrong answer
        }
        String bare = maskTerms(PLACEHOLDER.matcher(out).replaceAll(" "), new ArrayList<>()).replaceAll("<ph\\d+>", " ");
        boolean letters = false;
        for (int i = 0; i < bare.length(); ) {
            int cp = bare.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) {
                continue;
            }
            letters = true;
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            for (Character.UnicodeScript e : expected) {
                if (script == e) {
                    return false;
                }
            }
        }
        return letters;
    }

    // ------------------------------------------------------------------ PLaMo Translate

    /**
     * The language code PLaMo Translate expects for an Android language code: the bare language
     * ({@code es-rMX} gives {@code es}), Chinese by script, and the old codes Android still uses.
     */
    static String plamoCode(String code) {
        if (code == null || code.isEmpty()) {
            return "en";
        }
        String c = code.toLowerCase(Locale.ROOT).replace("-r", "-").replace('_', '-');
        if (c.startsWith("zh")) {
            return c.contains("tw") || c.contains("hk") || c.contains("mo") || c.contains("hant") ? "zh-hant" : "zh-hans";
        }
        int dash = c.indexOf('-');
        String base = dash > 0 ? c.substring(0, dash) : c;
        switch (base) {
            case "iw":
                return "he";
            case "in":
                return "id";
            case "fil":
                return "tl";
            default:
                return base;
        }
    }

    /**
     * Why this engine cannot translate from or into a language, or null when it can. Only PLaMo
     * Translate has a closed list; asked for a language outside it, it answers in another one
     * (Portuguese came back in French) without saying so.
     */
    public String unsupportedLanguage(String code) {
        if (!plamoStyle || PLAMO_LANGUAGES.contains(plamoCode(code))) {
            return null;
        }
        return "the translation model (PLaMo Translate) does not support the language '" + code + "'";
    }

    /**
     * True when a value is wrapped in double quotes as a whole. In a string resource that means
     * "keep the whitespace inside"; apktool also writes that way every string with an apostrophe.
     * The quotes are not part of the text the user sees.
     */
    static boolean isQuoted(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            return false;
        }
        String inner = value.substring(1, value.length() - 1);
        // an unescaped quote inside, or an escaped closing quote, means these are two separate quoted parts
        return !inner.matches("(?s).*(?<!\\\\)\".*") && !inner.endsWith("\\");
    }

    /**
     * The translation with the leading and trailing whitespace of the source instead of its own.
     * An empty translation stays empty (the caller treats it as "no answer").
     */
    static String withEdges(String source, String translated) {
        if (translated == null) {
            return null;
        }
        String core = translated.strip();
        if (core.isEmpty() || source == null) {
            return core;
        }
        int start = 0;
        while (start < source.length() && Character.isWhitespace(source.charAt(start))) {
            start++;
        }
        if (start == source.length()) {
            return core;
        }
        int end = source.length();
        while (end > start && Character.isWhitespace(source.charAt(end - 1))) {
            end--;
        }
        return source.substring(0, start) + core + source.substring(end);
    }

    /**
     * Replaces every placeholder, tag and entity with a numbered token the model copies through
     * ({@code <ph0>}, {@code <ph1>}, ...), recording the originals in {@code originals}. A literal
     * backslash-n escape goes as a real line break instead: the models reproduce those but drop tokens.
     */
    static String mask(String text, List<String> originals) {
        java.util.regex.Matcher m = PLACEHOLDER.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            if (m.group().equals("\\n")) {
                m.appendReplacement(sb, "\n");
                continue;
            }
            originals.add(m.group());
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("<ph" + (originals.size() - 1) + ">"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Puts the originals back, tolerating the spacing/case drift translation models introduce in the
     * tokens; when the source had literal backslash-n escapes, real line breaks become escapes again.
     */
    static String unmask(String text, List<String> originals, boolean restoreEscapes) {
        String out = restoreEscapes ? text.replace("\r\n", "\n").replace("\n", "\\n") : text;
        for (int i = originals.size() - 1; i >= 0; i--) {
            out = out.replaceAll("(?i)<\\s*ph\\s*" + i + "\\s*/?\\s*>", java.util.regex.Matcher.quoteReplacement(originals.get(i)));
        }
        return out;
    }

    /**
     * What must come out of a translation exactly as it went in: format specifiers, named
     * placeholders, escaped line breaks, tags and entities. A percent sign is a specifier when it
     * is doubled ({@code %%}), carries an argument index ({@code %1$s}), or is followed straight
     * away by a conversion and does not follow a digit: in "50% of users" and "(% of line width)"
     * it is a percentage, and masking "% o" there left stray letters in the translation.
     */
    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile(
            "%%|%\\d+\\$[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z]"
            + "|(?<![0-9%])%[-#+0,(]*\\d*(\\.\\d+)?(?:[bBhHsScCdoxXeEfgGaAn]|[tT][a-zA-Z])"
            + "|\\{[A-Za-z_][\\w.]*\\}|\\\\n|<[^<>]+>|&[a-z]+;");

    /** True when every placeholder, tag and entity of the source appears at least as often in the translation. */
    static boolean placeholdersKept(String source, String translated) {
        Map<String, Integer> needed = new HashMap<>();
        java.util.regex.Matcher m = PLACEHOLDER.matcher(source);
        while (m.find()) {
            needed.merge(m.group(), 1, Integer::sum);
        }
        if (needed.isEmpty()) {
            return true;
        }
        Map<String, Integer> found = new HashMap<>();
        m = PLACEHOLDER.matcher(translated);
        while (m.find()) {
            found.merge(m.group(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : needed.entrySet()) {
            if (found.getOrDefault(e.getKey(), 0) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static String preview(String s) {
        String one = s.replace('\n', ' ');
        return one.length() > 60 ? one.substring(0, 60) + "..." : one;
    }

    /** The code the TranslateGemma prompt shows next to the language name (ISO 639-1, region kept). */
    private static String isoCode(String code) {
        return code == null ? "" : code.replace("-r", "-");
    }

    /** One pass over {@code texts} in batches bounded by item count and characters; returns the texts still untranslated. */
    private List<String> translatePass(List<String> texts, int maxItems, int charBudget, String outputLang,
            String systemPrompt, String userPrefix, Map<String, String> result, int[] requests) {
        List<String> missing = new ArrayList<>();
        int index = 0;
        while (index < texts.size()) {
            int end = index;
            int chars = 0;
            while (end < texts.size() && end - index < maxItems && (end == index || chars + texts.get(end).length() <= charBudget)) {
                chars += texts.get(end).length();
                end++;
            }
            List<String> batch = texts.subList(index, end);
            List<String> out = translateBatch(batch, systemPrompt, userPrefix);
            requests[0]++;
            for (int i = 0; i < batch.size(); i++) {
                String t = out.get(i);
                if (t == null || t.trim().isEmpty()) {
                    missing.add(batch.get(i));
                    continue;
                }
                String source = batch.get(i);
                if (isQuoted(source)) {
                    String inner = source.substring(1, source.length() - 1);
                    t = "\"" + withEdges(inner, isQuoted(t.trim()) ? t.trim().substring(1, t.trim().length() - 1) : t) + "\"";
                } else {
                    t = withEdges(source, t);
                }
                String rejected = rejectReason(source, t, outputLang);
                if (rejected != null) {
                    ANSWERS_REJECTED.incrementAndGet();
                    System.out.println("  WARNING: answer rejected (" + rejected + "): '" + preview(source) + "' -> '" + preview(t) + "'");
                    missing.add(source);
                    continue;
                }
                result.put(source, t);
                if (inventory != null) {
                    inventory.putTranslation(outputLang, batch.get(i), t);
                }
            }
            index = end;
        }
        return missing;
    }

    /**
     * Translates one batch in a single request: JSON keyed by index; placeholders preserved via the
     * prompt. Entries the model dropped come back as null (the caller retries them).
     */
    @SuppressWarnings("unchecked")
    private List<String> translateBatch(List<String> batch, String systemPrompt, String userPrefix) {
        JSONObject items = new JSONObject();
        List<List<String>> maskedTerms = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            String text = batch.get(i);
            List<String> originals = new ArrayList<>();
            items.put(String.valueOf(i), maskTerms(isQuoted(text) ? text.substring(1, text.length() - 1) : text, originals));
            maskedTerms.add(originals);
        }
        JSONArray messages = new JSONArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrefix + items.toJSONString()));

        // A malformed/empty LLM response (parse or HTTP failure) must not abort the whole run: the
        // batch is reported as missing and retried in smaller pieces by the caller.
        Map<String, String> translatedByKey;
        try {
            translatedByKey = parseTranslations(chatCompletion(messages, true));
        } catch (Exception e) {
            System.out.println("  WARNING: batch of " + batch.size() + " failed (" + describe(e) + ")");
            translatedByKey = new HashMap<>();
        }
        List<String> result = new ArrayList<>(batch.size());
        int dropped = 0;
        for (int i = 0; i < batch.size(); i++) {
            String translated = translatedByKey.get(String.valueOf(i));
            if (translated == null) {
                dropped++;
            } else if (!maskedTerms.get(i).isEmpty()) {
                translated = unmask(translated, maskedTerms.get(i), false);
            }
            result.add(translated);
        }
        if (dropped > 0 && dropped < batch.size()) {
            System.out.println("  WARNING: the model left out " + dropped + " of " + batch.size() + " items in a batch");
        }
        return result;
    }

    /**
     * Asks the model for realistic values to type into the text fields of a screen (optional form
     * filling during exploration). One value per description, "" when the model gave none.
     */
    @SuppressWarnings("unchecked")
    public List<String> suggestInputValues(List<String> fieldDescriptions, String language) throws Exception {
        JSONObject items = new JSONObject();
        for (int i = 0; i < fieldDescriptions.size(); i++) {
            items.put(String.valueOf(i), fieldDescriptions.get(i));
        }
        JSONArray messages = new JSONArray();
        messages.add(message("system", "You help test an Android app. Each JSON value describes one text input field of a screen "
                + "(its hint or current text, its description and its id). For each field invent ONE short, realistic value a "
                + "user would type, written in " + languageName(language) + ", using plain ASCII letters, digits, spaces and basic "
                + "punctuation only (no quotes, no emoji, at most 25 characters). Match the field type: an email for email fields, "
                + "digits for numeric fields, a name for name fields. Return ONLY a JSON object with the same keys and the values."));
        messages.add(message("user", items.toJSONString()));
        Map<String, String> byKey = parseTranslations(chatCompletion(messages, true));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < fieldDescriptions.size(); i++) {
            String v = byKey.get(String.valueOf(i));
            out.add(v == null ? "" : v);
        }
        return out;
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

    private static String buildSystemPrompt(String inputLang, String outputLang) {
        return "You are a professional native " + outputLang + " software localizer for Android apps. "
                + "Translate UI string values from " + inputLang + " to " + outputLang + ". "
                + "Strict rules:\n"
                + "1. Translate ONLY the text values; never translate, add or remove the JSON keys.\n"
                + "2. Preserve EXACTLY every format placeholder and markup: %s, %d, %f, positional ones like "
                + "%1$s and %2$d, escape sequences (\\n, \\t, \\', \\\"), HTML/Android tags such as <b>, </b>, "
                + "<xliff:g ...>, named placeholders like {count}, and XML entities such as &amp; or &lt;.\n"
                + "3. Keep leading and trailing whitespace.\n"
                + "4. Write natural, fluent, idiomatic " + outputLang + " as it actually appears in real apps; "
                + "respect grammar, gender, number and agreement, and keep labels short.\n"
                + "5. " + DO_NOT_TRANSLATE + "\n"
                + "6. Do not add explanations, comments or markdown fences.\n"
                + "Return ONLY a valid JSON object mapping each original key to its translated string.";
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

    /** Sends a chat-completion request and returns the assistant message content; every call is counted and timed. */
    private String chatCompletion(JSONArray messages, boolean jsonMode) throws Exception {
        return chatCompletion(messages, jsonMode, null);
    }

    /** The same, with extra fields for the request body (stop sequences, max_tokens). */
    private String chatCompletion(JSONArray messages, boolean jsonMode, JSONObject extra) throws Exception {
        long t0 = System.currentTimeMillis();
        REQUESTS.incrementAndGet();
        try {
            return chatCompletionRaw(messages, jsonMode, extra);
        } finally {
            REQUEST_MS.addAndGet(System.currentTimeMillis() - t0);
        }
    }

    @SuppressWarnings("unchecked")
    private String chatCompletionRaw(JSONArray messages, boolean jsonMode, JSONObject extra) throws Exception {
        JSONObject body = new JSONObject();
        if (extra != null) {
            body.putAll(extra);
        }
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

    /**
     * Exception text for the logs. json-simple's ParseException puts its detail ("Unexpected token X
     * at position N") in toString() and leaves getMessage() null, which printed as "failed (null)".
     */
    private static String describe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    /**
     * Values that are not human-readable UI text and must never reach the translator: vector path
     * data, CSS-like easing functions, fully-qualified class names and font-family tokens. Material
     * Components ships dozens of them in strings.xml (mtrl_*_path*, m3_sys_motion_easing_*,
     * *_behavior, m3_ref_typeface_*). "Translating" them corrupts the app, and batching them also
     * truncated the model's JSON output on real apps. Deliberately conservative: a UI sentence has
     * spaces and letters, none of these shapes do.
     */
    public static boolean looksLikeNonText(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return false;
        }
        return v.matches("(?s)^[MmLlHhVvCcSsQqTtAaZz]\\s*-?\\d+(\\.\\d+)?[\\s,]-?\\d.*")   // "M12,4.5C7,..." path data
                || v.matches("(?is)^(cubic-bezier|path|linear|steps|spline)\\s*\\(.*")   // easing / path(...) functions
                || v.matches("^[a-z]\\w*(\\.[A-Za-z_$][\\w$]*){2,}$")                     // com.example.Foo$Bar, www.x.com
                || v.matches("^[a-z]\\w*\\.[a-zA-Z_$][\\w$]*$")                            // window.decorView, view.resources
                || v.matches("(?i)^(sans-serif(-[a-z]+)?|serif|monospace|casual|cursive)$"); // font families
    }

    /** Parses the model output into a key -> translation map, tolerating markdown fences. */
    private Map<String, String> parseTranslations(String content) throws Exception {
        String json = stripCodeFences(content);
        JSONParser parser = new JSONParser();
        JSONObject obj;
        try {
            obj = (JSONObject) parser.parse(json);
        } catch (Exception pe) {
            // Say WHAT the model returned: small / "thinking" models sometimes answer with prose, a
            // truncated object, an array or nothing at all, and the bare ParseException hides that.
            String preview = json.length() > 160 ? json.substring(0, 160) + "..." : json;
            throw new Exception("model returned invalid JSON (" + describe(pe) + "); content: \"" + preview + "\"", pe);
        }
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

    /** Checks if a string only contains numbers and special characters (not worth translating). */
    public static boolean isOnlyNumbersAndSpecs(String string) {
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

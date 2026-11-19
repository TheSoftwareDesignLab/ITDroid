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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

import uniandes.tsdl.itdroid.helper.LanguageBundle;
import uniandes.tsdl.itdroid.helper.StringInventory;
import uniandes.tsdl.itdroid.helper.Translatability;

/**
 * Translates app strings with a local LLM behind an OpenAI-compatible endpoint (Ollama, llama.cpp,
 * LM Studio), configured by ITDROID_LLM_* system properties or environment variables. The texts come from
 * the {@link StringInventory}, which also works as translation memory. An answer that cannot be used is
 * asked again by parts, and what still fails stays in the source language and is reported per language.
 */
public class LLMTranslator {

    /** Strings per LLM request, small enough for local inference. */
    private static final int TRANSLATOR_PACKAGE_SIZE = 25;
    /** Source characters per request, so long texts do not overflow the context and truncate the JSON. */
    private static final int BATCH_CHAR_BUDGET = 2500;
    /** Items and characters of the second, smaller batches. */
    private static final int SMALL_BATCH_SIZE = 5;
    private static final int SMALL_BATCH_CHAR_BUDGET = 1200;
    /** Entries of each list of the translation section of report.json, the counts say how many there were. */
    private static final int REPORT_LIST_CAP = 200;

    /** Categories the model must copy verbatim, for the chat models that take instructions. */
    private static final String DO_NOT_TRANSLATE =
            "Do NOT translate the following; copy them VERBATIM, exactly as written: "
            + "people's names and proper names; company, product and brand names; usernames and @handles; "
            + "email addresses; URLs, domains and hostnames; postal addresses; countries and cities; ages; "
            + "numbers written in 0-9 form, including phone numbers, card numbers, dates, times and monetary amounts; "
            + "units of measurement (temperature, length, money and currency); ISO, country and language codes; "
            + "code variables, labels, identifiers, enum or constant names, file paths and any code tokens; "
            + "and format placeholders (e.g. %1$s, %d, %.2f, {count}). "
            + "If a value consists ONLY of such data (not a human-facing phrase), return it unchanged.";

    private final String endpoint;
    private final String model;
    private final String apiKey;
    private final int timeoutSeconds;
    private final String temperature;
    private final HttpClient httpClient;

    /** Source of the texts and translation memory, null for a translator used on loose texts. */
    private StringInventory inventory;

    /**
     * Request style from ITDROID_LLM_STYLE (auto by model name, json, translategemma, template or
     * plamo). Chat models get batched JSON, translation models one text per request in their own prompt.
     */
    private final boolean nativeTranslateGemma;
    private final boolean templateStyle;
    private final boolean plamoStyle;
    private final String promptTemplate;
    /**
     * Masks placeholders around a one-text request (ITDROID_LLM_MASK_PLACEHOLDERS), since small
     * translation models mangle them more often than they copy them.
     */
    private final boolean maskPlaceholders;
    private static final String DEFAULT_TEMPLATE = "Translate the following text into {target}. Note that you should "
            + "**only output the translated result without any additional explanation**:\n\n{text}";
    /** Concurrent requests in the one-text mode. */
    private static final int NATIVE_PARALLELISM = 4;

    /** Names of people, places and products of translation.protectedTerms, masked so no model can alter them. */
    private final List<String> protectedTerms = new ArrayList<>();
    private volatile Pattern protectedPattern;

    /**
     * Context PLaMo gets for a text of up to three words, which alone is ambiguous ("Open" as verb or
     * adjective). Set by ITDROID_LLM_CONTEXT, "none" turns it off.
     */
    private final String shortTextContext;
    private static final String DEFAULT_CONTEXT = "Text of a button, menu or label in the user interface of a mobile app.";

    /** Language codes PLaMo Translate supports, from its model card. */
    private static final Set<String> PLAMO_LANGUAGES = Set.of("ja", "en", "zh-hans", "zh-hant", "ko", "ar", "az", "be", "bg", "bn",
            "ca", "ceb", "de", "es", "et", "eu", "fa", "fr", "gu", "he", "hi", "hr", "hu", "hy", "id", "it", "jv", "ka", "kk", "km",
            "lt", "lv", "ml", "mr", "ms", "my", "ne", "nl", "pa", "pl", "ru", "si", "sk", "sl", "sr", "sw", "ta", "te", "th", "tl",
            "ur", "uz", "vi");

    /** Run-wide totals for the performance section of report.json (every instance adds to them). */
    private static final AtomicLong REQUESTS = new AtomicLong();
    private static final AtomicLong REQUEST_MS = new AtomicLong();
    private static final AtomicLong WALL_MS = new AtomicLong();
    private static final AtomicLong TEXTS_SENT = new AtomicLong();
    private static final AtomicLong TEXTS_REUSED = new AtomicLong();
    private static final AtomicLong TEXTS_KEPT = new AtomicLong();
    private static final AtomicLong TEXTS_BY_PARTS = new AtomicLong();
    private static final AtomicLong ANSWERS_REJECTED = new AtomicLong();

    /** What became of the texts of each language, for the translation section of report.json. */
    private static final class Outcome {
        /** Text to the reason it stays in the source language. */
        final Map<String, String> keptInSource = new LinkedHashMap<>();
        /** Text to the translation made piece by piece. */
        final Map<String, String> byParts = new LinkedHashMap<>();
        /** The app's own copies of the source text, name, text and translation. */
        final List<String[]> appCopies = new ArrayList<>();
        /** Text to the resource names that hold it. */
        final Map<String, Set<String>> names = new HashMap<>();
    }

    private final Map<String, Outcome> outcomes = new ConcurrentHashMap<>();

    /** An answer with the masked spans put back, or the reason it cannot be used. */
    record Reply(String text, String problem) {
    }

    public LLMTranslator() {
        this.endpoint = resolve("ITDROID_LLM_ENDPOINT", "http://localhost:11434/v1/chat/completions");
        this.model = resolve("ITDROID_LLM_MODEL", "qwen2.5:3b");
        this.apiKey = resolve("ITDROID_LLM_API_KEY", "");
        this.temperature = resolve("ITDROID_LLM_TEMPERATURE", "0");
        int timeout;
        try {
            timeout = Integer.parseInt(resolve("ITDROID_LLM_TIMEOUT", "180"));
        } catch (NumberFormatException e) {
            timeout = 180;
        }
        this.timeoutSeconds = timeout;
        String style = resolve("ITDROID_LLM_STYLE", "auto").trim().toLowerCase();
        this.templateStyle = style.equals("template")
                || (style.equals("auto") && model.toLowerCase().matches(".*(hy-?mt|hunyuan-?mt).*"));
        this.nativeTranslateGemma = style.equals("translategemma")
                || (style.equals("auto") && model.toLowerCase().contains("translategemma"));
        this.plamoStyle = style.equals("plamo") || (style.equals("auto") && model.toLowerCase().contains("plamo"));
        this.promptTemplate = resolve("ITDROID_LLM_PROMPT_TEMPLATE", DEFAULT_TEMPLATE).replace("\\n", "\n");
        String mask = resolve("ITDROID_LLM_MASK_PLACEHOLDERS", "").trim().toLowerCase();
        this.maskPlaceholders = mask.isEmpty() ? (templateStyle || nativeTranslateGemma || plamoStyle)
                : (mask.equals("true") || mask.equals("yes") || mask.equals("1") || mask.equals("on"));
        String context = resolve("ITDROID_LLM_CONTEXT", DEFAULT_CONTEXT).trim();
        this.shortTextContext = context.equalsIgnoreCase("none") ? "" : context;
        addProtectedTerms(java.util.Arrays.asList(resolve("ITDROID_LLM_PROTECTED_TERMS", "").split(",")));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public LLMTranslator(StringInventory inventory) {
        this();
        this.inventory = inventory;
    }

    /** Adds terms that must come out of every translation exactly as written (case-sensitive, whole words). */
    public synchronized void addProtectedTerms(java.util.Collection<String> terms) {
        for (String term : terms) {
            String t = term == null ? "" : term.trim();
            if (t.length() >= 2 && !protectedTerms.contains(t)) {
                protectedTerms.add(t);
            }
        }
        protectedPattern = Translatability.wholeWords(protectedTerms);
    }

    public synchronized List<String> protectedTerms() {
        return new ArrayList<>(protectedTerms);
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
        // Summed latencies overlap for parallel requests, wall time is what the pipeline waited.
        o.put("requestSeconds", Math.round(REQUEST_MS.get() / 100.0) / 10.0);
        o.put("wallSeconds", Math.round(WALL_MS.get() / 100.0) / 10.0);
        o.put("textsSent", TEXTS_SENT.get());
        o.put("textsReused", TEXTS_REUSED.get());
        o.put("textsKeptInSource", TEXTS_KEPT.get());
        o.put("textsByParts", TEXTS_BY_PARTS.get());
        o.put("answersRejected", ANSWERS_REJECTED.get());
        return o;
    }

    // ------------------------------------------------------------------ values-<lang>/strings.xml

    /**
     * Writes {@code values-<outputLang>/strings.xml} with every translatable resource of the inventory.
     * Resources the target folder already defines are left alone.
     */
    public void translate(String xmlPath, String inputLang, String outputLang) throws Exception {
        translate(xmlPath, inputLang, outputLang, null, null);
    }

    /**
     * The same, limited to the given resource names (null for all). Used for a language the app
     * already ships, where only the strings ITDroid added are filled in.
     */
    public void translate(String xmlPath, String inputLang, String outputLang, Set<String> onlyNames) throws Exception {
        translate(xmlPath, inputLang, outputLang, onlyNames, null);
    }

    /**
     * The same, also translating in place the strings the app ships for this language with the default
     * text itself. Their value is replaced in the app's file, a second definition would stop the build.
     */
    public void translate(String xmlPath, String inputLang, String outputLang, Set<String> onlyNames, Set<String> copies)
            throws Exception {
        if (inventory == null) {
            throw new IllegalStateException("translating the resources of an app needs its string inventory");
        }
        File xmlFile = new File(xmlPath).getAbsoluteFile();
        File resDir = xmlFile.getParentFile().getParentFile();
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

        // Resources already defined for this language win, since a duplicate makes aapt2 refuse the build.
        Set<String> defined = definedNames(xmlOutputFolder, builder);
        Set<String> inPlace = copies == null ? Set.of() : copies;

        LinkedHashMap<String, List<StringInventory.Entry>> groups = new LinkedHashMap<>();
        List<StringInventory.Entry> copyEntries = new ArrayList<>();
        for (StringInventory.Entry e : inventory.resourceEntries()) {
            if (StringInventory.KIND_STRING.equals(e.kind) && e.translatable && inPlace.contains(e.name) && defined.contains(e.name)) {
                copyEntries.add(e);
                continue;
            }
            if (defined.contains(e.name) || (onlyNames != null && !onlyNames.contains(e.name))) {
                continue;
            }
            groups.computeIfAbsent(e.groupKey(), k -> new ArrayList<>()).add(e);
        }
        LinkedHashSet<String> texts = new LinkedHashSet<>();
        Outcome outcome = outcome(outputLang);
        List<StringInventory.Entry> sent = new ArrayList<>(copyEntries);
        groups.values().forEach(sent::addAll);
        for (StringInventory.Entry e : sent) {
            if (e.translatable) {
                texts.add(e.text);
                outcome.names.computeIfAbsent(e.text, k -> new LinkedHashSet<>()).add(e.name);
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
                    continue;   // data or reference, the default value serves every language
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
                // Containers are all or nothing, so untranslatable items are written verbatim.
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

        int replaced = 0;
        for (StringInventory.Entry e : copyEntries) {
            String value = translated.getOrDefault(e.text, e.text);
            if (Translatability.sameLetters(value, e.text)) {
                continue;   // the same in both languages, or kept in the source and listed as such
            }
            outcome.appCopies.add(new String[] {e.name, e.text, value});
            if (replaceInPlace(e.name, value, outputRoot, xmlOutputFolder, xmlOutputFile, builder)) {
                replaced++;
            }
        }

        XMLOutputter output = new XMLOutputter();
        // PRESERVE keeps the edge whitespace of translated values, TRIM would strip it.
        output.setFormat(Format.getPrettyFormat().setTextMode(Format.TextMode.PRESERVE));
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(xmlOutputFile), StandardCharsets.UTF_8)) {
            output.output(outputDocument, writer);
        }
        System.out.println("  " + written + " resources written to " + xmlOutputFile.getPath()
                + (copyEntries.isEmpty() ? "" : ", " + replaced + " of the app's " + copyEntries.size() + " copies of the source text translated in place"));
    }

    /**
     * Replaces the value of a string the language folder already defines, in strings.xml when it is
     * there, else in the other file that holds it. Returns false when no file defines it.
     */
    private static boolean replaceInPlace(String name, String value, Element outputRoot, File folder, File outputFile, SAXBuilder builder) {
        for (Element existing : outputRoot.getChildren("string")) {
            if (name.equals(existing.getAttributeValue("name"))) {
                setTranslatedContent(existing, value);
                return true;
            }
        }
        File[] files = folder.listFiles((d, n) -> n.toLowerCase().endsWith(".xml"));
        if (files == null) {
            return false;
        }
        for (File f : files) {
            if (f.getAbsoluteFile().equals(outputFile.getAbsoluteFile())) {
                continue;
            }
            try {
                Document document = builder.build(f);
                for (Element existing : document.getRootElement().getChildren("string")) {
                    if (name.equals(existing.getAttributeValue("name"))) {
                        setTranslatedContent(existing, value);
                        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
                            new XMLOutputter(Format.getRawFormat()).output(document, writer);
                        }
                        return true;
                    }
                }
            } catch (Exception ex) {
                // not a parseable resource file, it cannot hold the string
            }
        }
        return false;
    }

    /** Every resource name defined in the xml files of the target folder. */
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
                // not a parseable resource file, nothing to protect
            }
        }
        return names;
    }

    /**
     * Sets a translated value, parsed back as inner XML with its entities and inline markup. If the
     * model broke the markup the value is stored as plain text instead of failing the build.
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

    // ------------------------------------------------------------------ what became of each language

    private Outcome outcome(String lang) {
        return outcomes.computeIfAbsent(lang, k -> new Outcome());
    }

    /** Texts kept in the source language for this language, with the reason. */
    public Map<String, String> keptInSource(String lang) {
        return new LinkedHashMap<>(outcome(lang).keptInSource);
    }

    /**
     * The translation section of a language in report.json, what stayed in the source language and why,
     * what was translated by parts and the app's own copies of the source text. Lists are capped.
     */
    @SuppressWarnings("unchecked")
    public JSONObject report(String lang) {
        Outcome o = outcome(lang);
        JSONObject r = new JSONObject();
        JSONArray kept = new JSONArray();
        for (Map.Entry<String, String> e : o.keptInSource.entrySet()) {
            if (kept.size() >= REPORT_LIST_CAP) {
                break;
            }
            JSONObject k = new JSONObject();
            k.put("text", e.getKey());
            k.put("reason", e.getValue());
            JSONArray names = new JSONArray();
            names.addAll(o.names.getOrDefault(e.getKey(), Set.of()));
            k.put("names", names);
            kept.add(k);
        }
        r.put("keptInSource", kept);
        r.put("keptInSourceCount", (long) o.keptInSource.size());
        JSONArray parts = new JSONArray();
        for (Map.Entry<String, String> e : o.byParts.entrySet()) {
            if (parts.size() >= REPORT_LIST_CAP) {
                break;
            }
            JSONObject p = new JSONObject();
            p.put("text", e.getKey());
            p.put("translation", e.getValue());
            parts.add(p);
        }
        r.put("byParts", parts);
        r.put("byPartsCount", (long) o.byParts.size());
        JSONArray copies = new JSONArray();
        for (String[] c : o.appCopies) {
            if (copies.size() >= REPORT_LIST_CAP) {
                break;
            }
            JSONObject p = new JSONObject();
            p.put("name", c[0]);
            p.put("text", c[1]);
            p.put("translation", c[2]);
            copies.add(p);
        }
        r.put("appCopies", copies);
        r.put("appCopiesCount", (long) o.appCopies.size());
        return r;
    }

    // ------------------------------------------------------------------ translating texts

    /**
     * Translates resource texts reusing the inventory memory of this model. Maps every input to its
     * translation, or to itself when it stays in the source language.
     */
    public Map<String, String> translateResourceTexts(List<String> texts, String inputLang, String outputLang) {
        String systemPrompt = buildSystemPrompt(languageName(inputLang), languageName(outputLang));
        Outcome outcome = outcome(outputLang);
        Map<String, String> result = new LinkedHashMap<>();
        if (inventory != null) {
            inventory.useModel(outputLang, model);
        }
        Map<String, Prepared> prepared = new LinkedHashMap<>();   // each text once, in the order given
        int reused = 0;
        for (String t : texts) {
            if (result.containsKey(t) || prepared.containsKey(t)) {
                continue;
            }
            Prepared p = new Prepared(t, false);
            if (!p.hasText()) {
                result.put(t, t);   // only names, figures or placeholders, nothing to ask
                continue;
            }
            String memory = inventory != null ? inventory.translation(outputLang, t) : null;
            if (memory != null && rejectReason(t, memory, outputLang) == null) {
                result.put(t, memory);
                reused++;
                continue;
            }
            if (memory != null) {
                inventory.removeTranslation(outputLang, t);   // stored by an older run, no longer acceptable
            }
            prepared.put(t, p);
        }
        List<String> pending = new ArrayList<>(prepared.keySet());
        long start = System.currentTimeMillis();
        int[] requests = {0};

        Map<String, Reply> replies = ask(pending, prepared, inputLang, outputLang, systemPrompt, TRANSLATOR_PACKAGE_SIZE,
                BATCH_CHAR_BUDGET, requests);
        if (!isOneTextStyle()) {
            // what the model left out is asked again in small batches, truncated answers come from long ones
            List<String> dropped = new ArrayList<>();
            for (String t : pending) {
                if (!replies.containsKey(t)) {
                    dropped.add(t);
                }
            }
            if (!dropped.isEmpty()) {
                System.out.println("  retrying " + dropped.size() + " texts the model left out, in smaller batches");
                replies.putAll(ask(dropped, prepared, inputLang, outputLang, systemPrompt, SMALL_BATCH_SIZE, SMALL_BATCH_CHAR_BUDGET, requests));
            }
        }
        Map<String, String> failed = new LinkedHashMap<>();
        for (String t : pending) {
            String reason = check(t, replies.get(t), outputLang);
            if (reason == null) {
                accept(t, replies.get(t).text(), outputLang, result, outcome);
            } else {
                failed.put(t, reason);
            }
        }
        if (!failed.isEmpty()) {
            translateByParts(failed, inputLang, outputLang, systemPrompt, result, outcome, requests);
        }
        for (Map.Entry<String, String> f : failed.entrySet()) {
            result.put(f.getKey(), f.getKey());
            outcome.keptInSource.put(f.getKey(), f.getValue());
        }
        if (!failed.isEmpty()) {
            System.out.println("  WARNING: " + failed.size() + " texts kept in the source language for " + outputLang
                    + " (no usable answer from the model, as a whole or by parts)");
        }
        System.out.println(String.format(Locale.ROOT, "  LLM: %d texts -> %s in %.1f s (%d requests, %d reused from the inventory)",
                pending.size(), outputLang, (System.currentTimeMillis() - start) / 1000.0, requests[0], reused));
        WALL_MS.addAndGet(System.currentTimeMillis() - start);
        TEXTS_SENT.addAndGet(pending.size());
        TEXTS_REUSED.addAndGet(reused);
        TEXTS_KEPT.addAndGet(failed.size());
        return result;
    }

    private boolean isOneTextStyle() {
        return nativeTranslateGemma || templateStyle || plamoStyle;
    }

    /** Takes an accepted translation as the result and into the memory of this model. */
    private void accept(String text, String translation, String outputLang, Map<String, String> result, Outcome outcome) {
        result.put(text, translation);
        outcome.keptInSource.remove(text);
        if (inventory != null) {
            inventory.putTranslation(outputLang, text, translation);
        }
    }

    /** Why a reply cannot be used, counting and printing the rejected answers, or null. */
    private String check(String source, Reply reply, String outputLang) {
        if (reply == null || reply.text() == null) {
            return reply != null && reply.problem() != null ? reply.problem() : "no answer from the model";
        }
        String reason = reply.problem() != null ? reply.problem() : rejectReason(source, reply.text(), outputLang);
        if (reason != null && !reply.text().trim().isEmpty()) {
            ANSWERS_REJECTED.incrementAndGet();
            System.out.println("  WARNING: answer rejected (" + reason + "): '" + preview(source) + "' -> '" + preview(reply.text()) + "'");
        }
        return reason;
    }

    /**
     * Second attempt for the texts whose answer could not be used. Each one is cut at its placeholders,
     * protected terms and figures, the pieces with letters are translated alone and joined in the order of
     * the source. A text that cannot be cut would be the same request again, so it is not asked.
     */
    private void translateByParts(Map<String, String> failed, String inputLang, String outputLang, String systemPrompt,
            Map<String, String> result, Outcome outcome, int[] requests) {
        Map<String, Parts> partsByText = new LinkedHashMap<>();
        Map<String, Prepared> pieces = new LinkedHashMap<>();
        for (String t : failed.keySet()) {
            Parts parts = new Parts(t);
            if (parts.pieces.isEmpty() || parts.isWhole()) {
                continue;
            }
            partsByText.put(t, parts);
            for (String piece : parts.pieces) {
                pieces.computeIfAbsent(piece, k -> new Prepared(k, false));
            }
        }
        if (pieces.isEmpty()) {
            return;
        }
        System.out.println("  translating " + partsByText.size() + " texts by parts (" + pieces.size() + " pieces)");
        Map<String, Reply> replies = ask(new ArrayList<>(pieces.keySet()), pieces, inputLang, outputLang, systemPrompt,
                SMALL_BATCH_SIZE, SMALL_BATCH_CHAR_BUDGET, requests);
        Map<String, String> done = new HashMap<>();
        Map<String, String> why = new HashMap<>();
        for (String piece : pieces.keySet()) {
            Reply r = withoutAddedPeriod(piece, replies.get(piece));
            String reason = check(piece, r, outputLang);
            if (reason == null) {
                done.put(piece, r.text());
            } else {
                why.put(piece, reason);
            }
        }
        for (Map.Entry<String, Parts> e : partsByText.entrySet()) {
            String text = e.getKey();
            String missing = null;
            for (String piece : e.getValue().pieces) {
                if (!done.containsKey(piece)) {
                    missing = "'" + preview(piece) + "' " + why.get(piece);
                    break;
                }
            }
            if (missing != null) {
                failed.put(text, failed.get(text) + ", and by parts: " + missing);
                continue;
            }
            Reply joined = e.getValue().join(done);
            String reason = check(text, joined, outputLang);
            if (reason != null) {
                failed.put(text, failed.get(text) + ", and by parts: " + reason);
                continue;
            }
            accept(text, joined.text(), outputLang, result, outcome);
            outcome.byParts.put(text, joined.text());
            failed.remove(text);
            TEXTS_BY_PARTS.incrementAndGet();
        }
    }

    /** A piece that did not end a sentence loses the period the model put at its end. */
    private static Reply withoutAddedPeriod(String piece, Reply r) {
        if (r == null || r.text() == null || piece.matches("(?s).*[.!?\u2026\u3002]\\s*$")) {
            return r;
        }
        String t = r.text().strip();
        if (t.endsWith(".") || t.endsWith("\u3002")) {
            return new Reply(t.substring(0, t.length() - 1), r.problem());
        }
        return r;
    }

    /**
     * Asks the model for the given texts in the style of the engine, one text per request with a few in
     * flight or JSON batches bounded by items and characters. A text the model left out has no reply.
     */
    private Map<String, Reply> ask(List<String> texts, Map<String, Prepared> prepared, String inputLang, String outputLang,
            String systemPrompt, int maxItems, int charBudget, int[] requests) {
        Map<String, Reply> out = new HashMap<>();
        if (texts.isEmpty()) {
            return out;
        }
        if (isOneTextStyle()) {
            ExecutorService pool = Executors.newFixedThreadPool(NATIVE_PARALLELISM);
            try {
                List<Future<Reply>> futures = new ArrayList<>();
                for (String t : texts) {
                    Prepared p = prepared.get(t);
                    futures.add(pool.submit(() -> translateOneNative(p, inputLang, outputLang)));
                }
                for (int i = 0; i < texts.size(); i++) {
                    Reply r;
                    try {
                        r = futures.get(i).get();
                    } catch (Exception e) {
                        r = new Reply(null, "the request failed: " + describe(e));
                    }
                    requests[0]++;
                    out.put(texts.get(i), r);
                }
            } finally {
                pool.shutdownNow();
            }
            return out;
        }
        int index = 0;
        while (index < texts.size()) {
            int end = index;
            int chars = 0;
            while (end < texts.size() && end - index < maxItems && (end == index || chars + texts.get(end).length() <= charBudget)) {
                chars += texts.get(end).length();
                end++;
            }
            List<Prepared> batch = new ArrayList<>();
            for (String t : texts.subList(index, end)) {
                batch.add(prepared.get(t));
            }
            List<Reply> replies = translateBatch(batch, systemPrompt);
            requests[0]++;
            for (int i = 0; i < batch.size(); i++) {
                if (replies.get(i) != null) {
                    out.put(batch.get(i).source, replies.get(i));
                }
            }
            index = end;
        }
        return out;
    }

    /**
     * Translates one batch in a single JSON request keyed by index. Entries the model dropped come
     * back as null for the caller to retry.
     */
    @SuppressWarnings("unchecked")
    private List<Reply> translateBatch(List<Prepared> batch, String systemPrompt) {
        JSONObject items = new JSONObject();
        for (int i = 0; i < batch.size(); i++) {
            items.put(String.valueOf(i), batch.get(i).masked);
        }
        JSONArray messages = new JSONArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", "Translate the values of this JSON object. Return ONLY a JSON object with the exact same keys "
                + "and the translated values:\n" + items.toJSONString()));

        // A failed or malformed response must not abort the run, the caller retries the batch smaller.
        Map<String, String> translatedByKey;
        try {
            translatedByKey = parseTranslations(chatCompletion(messages, true));
        } catch (Exception e) {
            System.out.println("  WARNING: batch of " + batch.size() + " failed (" + describe(e) + ")");
            translatedByKey = new HashMap<>();
        }
        List<Reply> result = new ArrayList<>(batch.size());
        int dropped = 0;
        for (int i = 0; i < batch.size(); i++) {
            String translated = translatedByKey.get(String.valueOf(i));
            if (translated == null || translated.trim().isEmpty()) {
                dropped++;
                result.add(null);
            } else {
                result.add(batch.get(i).finish(translated));
            }
        }
        if (dropped > 0 && dropped < batch.size()) {
            System.out.println("  WARNING: the model left out " + dropped + " of " + batch.size() + " items in a batch");
        }
        return result;
    }

    /** Translates one text in the model's own prompt. */
    @SuppressWarnings("unchecked")
    private Reply translateOneNative(Prepared p, String inputLang, String outputLang) {
        String body = p.masked;
        JSONArray messages = new JSONArray();
        JSONObject extra = null;
        if (plamoStyle) {
            // PLaMo translates the whole user message, so the bare text goes there and the options go in
            // the system message, as its model card says. After a "---" line comes context, not text.
            String options = "[plamo-translate]\nsource_lang=" + plamoCode(inputLang) + "\ntarget_lang=" + plamoCode(outputLang);
            if (!shortTextContext.isEmpty() && isShortLabel(p.core)) {
                options += "\n---\n" + shortTextContext;
            }
            messages.add(message("system", options));
            messages.add(message("user", body.strip()));
            extra = new JSONObject();
            JSONArray stop = new JSONArray();
            stop.add("<|plamo:tag|>");
            stop.add("<|plamo:bos|>");
            extra.put("stop", stop);
            extra.put("max_tokens", 1024L);
        } else {
            String src = languageName(inputLang);
            String tgt = languageName(outputLang);
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
            messages.add(message("user", prompt));
        }
        try {
            return p.finish(chatCompletion(messages, false, extra));
        } catch (Exception e) {
            System.out.println("  WARNING: translation request failed for '" + preview(p.core) + "': " + describe(e));
            return new Reply(null, "the request failed: " + describe(e));
        }
    }

    // ------------------------------------------------------------------ masking

    /** A token as models write it back, with the drift in spacing and case they add. */
    private static final Pattern TOKEN = Pattern.compile("(?i)<\\s*ph\\s*(\\d+)\\s*/?\\s*>");
    /** Whitespace and escaped line breaks or tabs at the start and the end of a value. */
    private static final Pattern HEAD = Pattern.compile("^(?:\\s|\\\\[nt])+");
    private static final Pattern TAIL = Pattern.compile("(?:\\s|(?<!\\\\)\\\\[nt])+$");
    /** What surrounds a piece and stays as it is, separators before it and anything but a sentence end after it. */
    private static final Pattern PIECE_LEAD = Pattern.compile("^[^\\p{L}\\p{N}]*");
    private static final Pattern PIECE_TRAIL = Pattern.compile("[^\\p{L}\\p{N}.!?…]*$");

    /**
     * A text ready for the model. The quotes around a whole value and the whitespace and escapes at its
     * edges are set aside and put back, and its placeholders and protected terms travel as tokens.
     */
    final class Prepared {
        final String source;
        final boolean quoted;
        final String head;
        final String tail;
        final String core;
        final String masked;
        final List<String> originals = new ArrayList<>();
        /** True when escaped newlines travel as real line breaks, which models keep more reliably than tokens. */
        private final boolean newlinesAsBreaks;
        /** True when letters are left once placeholders and protected terms are masked. */
        private final boolean textLeft;

        /** @param cutAll masks the placeholders whatever the style, to cut a text into pieces */
        Prepared(String source, boolean cutAll) {
            this.source = source;
            this.quoted = isQuoted(source);
            String inner = quoted ? source.substring(1, source.length() - 1) : source;
            Matcher h = HEAD.matcher(inner);
            this.head = h.lookingAt() ? h.group() : "";
            String rest = inner.substring(head.length());
            Matcher t = TAIL.matcher(rest);
            this.tail = t.find() ? t.group() : "";
            this.core = rest.substring(0, rest.length() - tail.length());
            boolean placeholders = cutAll || maskPlaceholders;
            this.newlinesAsBreaks = !cutAll && maskPlaceholders;
            this.masked = maskSpans(core, maskPatterns(placeholders), originals, newlinesAsBreaks);
            String bare = placeholders ? masked : maskSpans(core, maskPatterns(true), new ArrayList<>(), false);
            this.textLeft = TOKEN.matcher(bare).replaceAll(" ").matches("(?s).*\\p{L}.*");
        }

        /** True when there is something to translate besides placeholders, names and figures. */
        boolean hasText() {
            return textLeft;
        }

        /** The answer with the masked spans, the edges and the quotes put back. */
        Reply finish(String answer) {
            if (answer == null) {
                return new Reply(null, "no answer from the model");
            }
            String out = answer.strip();
            // the model occasionally wraps the answer in quotes the source did not have
            if (out.length() >= 2 && out.startsWith("\"") && out.endsWith("\"") && !core.startsWith("\"")) {
                out = out.substring(1, out.length() - 1).strip();
            }
            if (out.isEmpty()) {
                return new Reply("", "empty answer");
            }
            String problem = tokenProblem(out, originals.size());
            out = unmask(out, originals, newlinesAsBreaks && core.contains("\\n"));
            String full = head + out + tail;
            return new Reply(quoted ? "\"" + full + "\"" : full, problem);
        }
    }

    /** A text cut at its placeholders, protected terms and figures into the pieces with letters. */
    final class Parts {
        final Prepared whole;
        /** The masked text in order, a kept chunk as it is or the index of a piece as an Integer. */
        private final List<Object> layout = new ArrayList<>();
        final List<String> pieces = new ArrayList<>();

        Parts(String text) {
            whole = new Prepared(text, true);
            Matcher m = TOKEN.matcher(whole.masked);
            int at = 0;
            while (m.find()) {
                addChunk(whole.masked.substring(at, m.start()));
                layout.add(m.group());
                at = m.end();
            }
            addChunk(whole.masked.substring(at));
        }

        private void addChunk(String chunk) {
            Matcher lead = PIECE_LEAD.matcher(chunk);
            String head = lead.lookingAt() ? lead.group() : "";
            Matcher trail = PIECE_TRAIL.matcher(chunk.substring(head.length()));
            String tail = trail.find() ? trail.group() : "";
            String body = chunk.substring(head.length(), chunk.length() - tail.length());
            if (!body.matches("(?s).*\\p{L}.*")) {
                layout.add(chunk);
                return;
            }
            layout.add(head);
            layout.add(pieces.size());
            pieces.add(body);
            layout.add(tail);
        }

        /** True when the only piece is the whole text, so asking for it would repeat the first request. */
        boolean isWhole() {
            if (pieces.size() != 1) {
                return false;
            }
            for (Object o : layout) {
                if (o instanceof String && TOKEN.matcher((String) o).find()) {
                    return false;
                }
            }
            return pieces.get(0).strip().equals(whole.core.strip());
        }

        /** The text with each piece replaced by its translation and every masked span put back. */
        Reply join(Map<String, String> translations) {
            StringBuilder sb = new StringBuilder();
            for (Object o : layout) {
                sb.append(o instanceof Integer ? translations.get(pieces.get((Integer) o)) : (String) o);
            }
            return whole.finish(sb.toString());
        }
    }

    /** The patterns a request masks, the placeholders only when asked for, in the order they claim a span. */
    private List<Pattern> maskPatterns(boolean placeholders) {
        List<Pattern> patterns = Translatability.termPatterns(protectedPattern);
        if (placeholders) {
            patterns.add(1, Translatability.PLACEHOLDER);   // after the escaped tags, which stay one unit
        }
        return patterns;
    }

    /**
     * Replaces the spans the patterns claim, in their order, with numbered tokens and records the
     * originals. With newlinesAsBreaks an escaped newline becomes a real line break instead.
     */
    static String maskSpans(String text, List<Pattern> patterns, List<String> originals, boolean newlinesAsBreaks) {
        StringBuilder sb = new StringBuilder();
        int at = 0;
        for (int[] s : claim(text, patterns)) {
            sb.append(text, at, s[0]);
            String original = text.substring(s[0], s[1]);
            if (newlinesAsBreaks && original.equals("\\n")) {
                sb.append('\n');
            } else {
                originals.add(original);
                sb.append("<ph").append(originals.size() - 1).append('>');
            }
            at = s[1];
        }
        return sb.append(text.substring(at)).toString();
    }

    /** The spans the patterns claim in their order, without what overlaps an earlier claim, sorted by start. */
    static List<int[]> claim(String text, List<Pattern> patterns) {
        List<int[]> spans = new ArrayList<>();
        for (int k = 0; k < patterns.size(); k++) {
            Matcher m = patterns.get(k).matcher(text);
            while (m.find()) {
                if (m.end() == m.start()) {
                    continue;
                }
                boolean free = true;
                for (int[] s : spans) {
                    if (m.start() < s[1] && s[0] < m.end()) {
                        free = false;
                        break;
                    }
                }
                if (free) {
                    spans.add(new int[] {m.start(), m.end(), k});
                }
            }
        }
        spans.sort(Comparator.comparingInt(s -> s[0]));
        return spans;
    }

    /**
     * Replaces protected terms, e-mail addresses, URLs, paths, names and figures with numbered tokens,
     * adding the originals to {@code originals}.
     */
    String maskTerms(String text, List<String> originals) {
        return maskSpans(text, Translatability.termPatterns(protectedPattern), originals, false);
    }

    /**
     * Puts the originals back, tolerating the spacing and case drift models add to the tokens. With
     * restoreEscapes, real line breaks become escaped newlines again.
     */
    static String unmask(String text, List<String> originals, boolean restoreEscapes) {
        String out = restoreEscapes ? text.replace("\r\n", "\n").replace("\n", "\\n") : text;
        for (int i = originals.size() - 1; i >= 0; i--) {
            out = out.replaceAll("(?i)<\\s*ph\\s*" + i + "\\s*/?\\s*>", Matcher.quoteReplacement(originals.get(i)));
        }
        return out;
    }

    /** Why the tokens of a masked answer cannot be restored, one repeated or one the text did not have, or null. */
    static String tokenProblem(String maskedAnswer, int tokens) {
        Map<Integer, Integer> count = new HashMap<>();
        Matcher m = TOKEN.matcher(maskedAnswer);
        while (m.find()) {
            int index;
            try {
                index = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                index = Integer.MAX_VALUE;
            }
            if (index >= tokens) {
                return "the answer has a masking token the text did not have";
            }
            if (count.merge(index, 1, Integer::sum) > 1) {
                return "a masking token was repeated";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ is the answer a translation?

    /**
     * How a model starts when it comments instead of translating ("Note", "Here is", "Sure"). The
     * labels require a colon so that "Translation settings" is not taken for a comment.
     */
    private static final Pattern COMMENTARY = Pattern.compile(
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

    /** Languages written in the Latin alphabet, whose answers may not hold letters of another script. */
    private static final Set<String> LATIN_LANGUAGES = Set.of("af", "az", "bs", "ca", "ceb", "cs", "cy", "da", "de", "en", "eo", "es",
            "et", "eu", "fi", "fil", "fo", "fr", "fy", "ga", "gd", "gl", "ha", "haw", "hr", "ht", "hu", "id", "ig", "in", "is", "it",
            "jv", "ku", "la", "lb", "lt", "lv", "mg", "mi", "ms", "mt", "nb", "nl", "nn", "no", "ny", "pl", "pt", "ro", "rw", "sk",
            "sl", "sm", "sn", "so", "sq", "st", "su", "sv", "sw", "tk", "tl", "tr", "uz", "vi", "xh", "yo", "zu");

    /**
     * Why an answer cannot be stored as a translation, or null when it can. Empty answers, leftover
     * tokens, lost, repeated or reordered placeholders, lost terms or figures, commentary, a text of
     * several words left as it was and letters of the wrong script are rejected.
     */
    String rejectReason(String source, String out, String outputLang) {
        if (out == null || out.trim().isEmpty()) {
            return "empty answer";
        }
        if (TOKEN.matcher(out).find()) {
            return "a masking token was left in the answer";
        }
        if (!placeholdersKept(source, out)) {
            return "a placeholder was lost";
        }
        if (placeholderRepeated(source, out)) {
            return "a placeholder was repeated";
        }
        if (!specifierOrderKept(source, out)) {
            return "the order of the placeholders changed";
        }
        if (!termsKept(source, out)) {
            return "a protected term was lost";
        }
        if (!figuresKept(source, out)) {
            return "a figure was rewritten";
        }
        if (figureAdded(source, out)) {
            return "a figure was added";
        }
        String s = source.trim();
        String o = out.trim();
        if (o.length() > 3 * s.length() + 30) {
            return "far longer than the text";
        }
        if (COMMENTARY.matcher(o).matches() && !COMMENTARY.matcher(s).matches()) {
            return "a comment instead of a translation";
        }
        if (Translatability.sameLetters(s, o)) {
            // unchanged, which a name or a word like OK may be but a sentence may not
            return Translatability.wordsToTranslate(s, protectedPattern) >= 2 ? "left in the source language" : null;
        }
        if (inWrongScript(s, o, outputLang)) {
            return "not written in the script of the language";
        }
        return null;
    }

    /**
     * True when the answer has letters of a script the language does not use, outside placeholders and
     * protected terms. A Latin language takes no other script, any other language needs its own and may
     * hold Latin names. A letter the source has is never held against the answer.
     */
    private boolean inWrongScript(String source, String out, String outputLang) {
        Locale locale = LanguageBundle.locale(outputLang);
        String language = locale.getLanguage();
        String script = locale.getScript();
        boolean latin = script.isEmpty() ? LATIN_LANGUAGES.contains(language) : script.equals("Latn");
        Character.UnicodeScript[] expected = latin ? null : script.isEmpty() ? SCRIPTS.get(language) : scriptsOf(script);
        if (!latin && expected == null) {
            return false;
        }
        String bare = maskSpans(out, maskPatterns(true), new ArrayList<>(), false).replaceAll("<ph\\d+>", " ");
        Set<Integer> inSource = new HashSet<>();
        source.codePoints().forEach(inSource::add);
        boolean letters = false;
        boolean ownScript = false;
        for (int i = 0; i < bare.length(); ) {
            int cp = bare.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) {
                continue;
            }
            Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
            if (sc == Character.UnicodeScript.COMMON || sc == Character.UnicodeScript.INHERITED || sc == Character.UnicodeScript.UNKNOWN) {
                continue;
            }
            letters = true;
            boolean expectedScript = latin ? sc == Character.UnicodeScript.LATIN : contains(expected, sc);
            if (expectedScript) {
                ownScript = true;
            } else if ((latin || sc != Character.UnicodeScript.LATIN) && !inSource.contains(cp)) {
                return true;   // a third script, 備份 in an Italian answer
            }
        }
        return !latin && letters && !ownScript;
    }

    private static Character.UnicodeScript[] scriptsOf(String iso15924) {
        if (iso15924.equals("Hans") || iso15924.equals("Hant")) {
            return new Character.UnicodeScript[] {Character.UnicodeScript.HAN};
        }
        try {
            return new Character.UnicodeScript[] {Character.UnicodeScript.forName(iso15924)};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean contains(Character.UnicodeScript[] scripts, Character.UnicodeScript script) {
        for (Character.UnicodeScript s : scripts) {
            if (s == script) {
                return true;
            }
        }
        return false;
    }

    /** True when every placeholder, tag and entity of the source appears at least as often in the translation. */
    static boolean placeholdersKept(String source, String translated) {
        Map<String, Integer> needed = count(Translatability.PLACEHOLDER, source);
        if (needed.isEmpty()) {
            return true;
        }
        Map<String, Integer> found = count(Translatability.PLACEHOLDER, translated);
        for (Map.Entry<String, Integer> e : needed.entrySet()) {
            if (found.getOrDefault(e.getKey(), 0) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    /** True when a format specifier or named placeholder appears more often in the translation than in the source. */
    static boolean placeholderRepeated(String source, String translated) {
        Map<String, Integer> needed = count(Translatability.PLACEHOLDER, source);
        for (Map.Entry<String, Integer> e : count(Translatability.PLACEHOLDER, translated).entrySet()) {
            String p = e.getKey();
            boolean specifier = (p.startsWith("%") && !p.equals("%%")) || p.startsWith("{");
            if (specifier && e.getValue() > needed.getOrDefault(p, 0)) {
                return true;
            }
        }
        return false;
    }

    /** True when the specifiers without a position (%s, %d) keep their order, which String.format depends on. */
    static boolean specifierOrderKept(String source, String translated) {
        List<String> before = unpositioned(source);
        return before.isEmpty() || before.equals(unpositioned(translated));
    }

    private static List<String> unpositioned(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = Translatability.PLACEHOLDER.matcher(text);
        while (m.find()) {
            String p = m.group();
            if (p.startsWith("%") && !p.equals("%%") && !p.equals("%n") && !p.contains("$")) {
                out.add(p);
            }
        }
        return out;
    }

    private static Map<String, Integer> count(Pattern pattern, String text) {
        Map<String, Integer> out = new HashMap<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            out.merge(m.group(), 1, Integer::sum);
        }
        return out;
    }

    /** True when every figure of the source is in the translation as it was written, as many times. */
    static boolean figuresKept(String source, String translated) {
        Map<String, Integer> needed = new HashMap<>();
        for (int[] s : claim(source, List.of(Translatability.FIGURE_WITH_UNIT, Translatability.FIGURE))) {
            needed.merge(source.substring(s[0], s[1]), 1, Integer::sum);
        }
        return allIn(needed, translated);
    }

    /** True when the translation has a figure with separators the source does not have. */
    static boolean figureAdded(String source, String translated) {
        Matcher m = Translatability.FIGURE.matcher(translated);
        while (m.find()) {
            if (!source.contains(m.group())) {
                return true;
            }
        }
        return false;
    }

    /** True when every protected term, e-mail address, URL, path and name of the source is in the translation as many times. */
    boolean termsKept(String source, String translated) {
        List<Pattern> patterns = Translatability.termPatterns(protectedPattern);
        Map<String, Integer> needed = new HashMap<>();
        for (int[] s : claim(source, patterns)) {
            if (!Translatability.isFigurePattern(patterns.get(s[2]))) {
                needed.merge(source.substring(s[0], s[1]), 1, Integer::sum);
            }
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

    // ------------------------------------------------------------------ PLaMo Translate

    /** A text of up to three words, a label whose meaning depends on where it is shown. */
    static boolean isShortLabel(String text) {
        String t = text.strip();
        return !t.isEmpty() && t.length() <= 30 && t.split("\\s+").length <= 3;
    }

    /**
     * The PLaMo Translate code for an Android language code, the bare language (es-rMX gives es),
     * Chinese by script, and the old codes Android still uses.
     */
    static String plamoCode(String code) {
        if (code == null || code.isEmpty()) {
            return "en";
        }
        String c = LanguageBundle.tag(code).toLowerCase(Locale.ROOT);
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
     * Why this engine cannot handle a language, or null when it can. Only PLaMo has a closed list, and
     * for any other language it silently answers in a different one.
     */
    public String unsupportedLanguage(String code) {
        if (!plamoStyle || PLAMO_LANGUAGES.contains(plamoCode(code))) {
            return null;
        }
        return "the translation model (PLaMo Translate) does not support the language '" + code + "'";
    }

    // ------------------------------------------------------------------ values as Android writes them

    /**
     * True when a value is wrapped in double quotes as a whole, Android syntax that keeps inner
     * whitespace and that apktool uses for strings with apostrophes. The quotes are not shown text.
     */
    static boolean isQuoted(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            return false;
        }
        String inner = value.substring(1, value.length() - 1);
        // an unescaped quote inside, or an escaped closing quote, means these are two separate quoted parts
        return !inner.matches("(?s).*(?<!\\\\)\".*") && !inner.endsWith("\\");
    }

    /** Escapes unescaped double and single quotes as required by Android string resources. */
    public static String replaceUnscapedCharacters(String text) {
        String modifier1 = text.replaceAll("(?<!\\\\)\"", "\\\\\"");
        return modifier1.replaceAll("(?<!\\\\)'", "\\\\'");
    }

    private static String preview(String s) {
        String one = s == null ? "" : s.replace('\n', ' ');
        return one.length() > 60 ? one.substring(0, 60) + "..." : one;
    }

    // ------------------------------------------------------------------ other requests

    /**
     * Asks the model for realistic values to type into a screen's text fields during exploration. One
     * value per description, "" when the model gave none.
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

    private static String buildSystemPrompt(String inputLang, String outputLang) {
        return "You are a professional native " + outputLang + " software localizer for Android apps. "
                + "Translate UI string values from " + inputLang + " to " + outputLang + ". "
                + "Strict rules:\n"
                + "1. Translate ONLY the text values; never translate, add or remove the JSON keys.\n"
                + "2. Preserve EXACTLY every format placeholder and markup: %s, %d, %f, positional ones like "
                + "%1$s and %2$d, escape sequences (\\n, \\t, \\', \\\"), HTML/Android tags such as <b>, </b>, "
                + "<xliff:g ...>, named placeholders like {count}, tokens like <ph0>, and XML entities such as &amp; or &lt;.\n"
                + "3. Keep leading and trailing whitespace.\n"
                + "4. Write natural, fluent, idiomatic " + outputLang + " as it actually appears in real apps; "
                + "respect grammar, gender, number and agreement, and keep labels short.\n"
                + "5. " + DO_NOT_TRANSLATE + "\n"
                + "6. Do not add explanations, comments or markdown fences.\n"
                + "Return ONLY a valid JSON object mapping each original key to its translated string.";
    }

    /** Detects the language of the default strings.xml as an ISO 639-1 code, or null on failure. */
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

    // ------------------------------------------------------------------ the endpoint

    /** Sends a chat-completion request and returns the answer, counting and timing every call. */
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

    /** Exception text for logs, since json-simple's ParseException keeps its detail out of getMessage(). */
    private static String describe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    /** Parses the model output into a key -> translation map, tolerating markdown fences. */
    private Map<String, String> parseTranslations(String content) throws Exception {
        String json = stripCodeFences(content);
        JSONParser parser = new JSONParser();
        JSONObject obj;
        try {
            obj = (JSONObject) parser.parse(json);
        } catch (Exception pe) {
            // Show what the model returned, since small models sometimes answer with prose or a cut object.
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

    /** Keeps only the JSON object, dropping markdown fences and prose around it. */
    private static String stripCodeFences(String content) {
        String trimmed = content.trim();
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }

    /** The English name of a language code with its region or script, for the prompts. */
    private static String languageName(String code) {
        return LanguageBundle.name(code);
    }

    /** The code the TranslateGemma prompt shows next to the language name, as a BCP 47 tag. */
    private static String isoCode(String code) {
        return LanguageBundle.tag(code);
    }

    /** The configured LLM endpoint, from system property, env var or default, in that order. */
    public static String resolveEndpoint() {
        return resolve("ITDROID_LLM_ENDPOINT", "http://localhost:11434/v1/chat/completions");
    }

    /** The configured LLM API token, or "" if none. */
    public static String resolveApiKey() {
        return resolve("ITDROID_LLM_API_KEY", "");
    }

    /** Resolves a configuration value from system properties, then the environment, else the default. */
    private static String resolve(String key, String defaultValue) {
        String fromProperty = System.getProperty(key);
        if (fromProperty != null && !fromProperty.isEmpty()) {
            return fromProperty;
        }
        String fromEnvVar = System.getenv(key);
        if (fromEnvVar != null && !fromEnvVar.isEmpty()) {
            return fromEnvVar;
        }
        return defaultValue;
    }
}

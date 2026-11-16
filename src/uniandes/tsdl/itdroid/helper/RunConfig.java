package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * A single run configuration file (KEY=VALUE, {@code .properties}) that replaces the positional CLI
 * arguments: pass it as the only argument (<code>java -jar ITDroid.jar itdroid.config.properties</code>).
 *
 * <p>It carries the run parameters (APK, package, folders, emulator…) and the translation-engine
 * choice. The translation block is pushed into the {@code ITDROID_LLM_*} system properties (which have
 * top precedence in {@link uniandes.tsdl.itdroid.translator.LLMTranslator}), so the developer can pick
 * a local Ollama model, a local llama.cpp server, or a remote OpenAI-compatible endpoint with a token,
 * all from this one file. See {@code itdroid.config.example.properties}.
 */
public class RunConfig {

    private final Properties props;

    private RunConfig(Properties props) {
        this.props = props;
    }

    /** Loads and parses the config file (UTF-8). */
    public static RunConfig load(String path) throws IOException {
        File file = new File(path);
        if (!file.isFile()) {
            throw new IOException("Config file not found: " + path);
        }
        Properties p = new Properties();
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            p.load(reader);
        }
        return new RunConfig(p);
    }

    /** True when a lone CLI argument points at a config file rather than being a positional argument. */
    public static boolean looksLikeConfigFile(String arg) {
        if (arg == null) {
            return false;
        }
        String lower = arg.toLowerCase();
        return lower.endsWith(".properties") || lower.endsWith(".config") || new File(arg).isFile();
    }

    private String required(String key) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required key '" + key + "' in the config file.");
        }
        return value.trim();
    }

    private String optional(String key, String defaultValue) {
        String value = props.getProperty(key);
        return (value == null || value.trim().isEmpty()) ? defaultValue : value.trim();
    }

    public String getApkPath()     { return required("apkPath"); }
    public String getAppPackage()  { return required("appPackage"); }
    public String getExtraFolder() { return optional("extraFolder", "extra"); }
    public String getSettingsDir() { return optional("settingsDir", "."); }
    /** Explicit output folder; empty means "{@code <resultsDir>/<appPackage>}" (the folder the web report lists). */
    public String getOutput()      { return optional("output", ""); }
    /** Root of all evaluations (default {@code results}); {@code --report} serves it. */
    public String getResultsDir()  { return optional("resultsDir", "results"); }
    public String getEmulator()    { return required("emulator"); }

    public int getAlpha() {
        String raw = optional("alpha", "0");
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Config key 'alpha' must be an integer, got '" + raw + "'.");
        }
    }

    /**
     * Pushes the translation settings into the {@code ITDROID_LLM_*} system properties (top precedence).
     * {@code translation.engine} only selects sensible endpoint defaults; explicit keys always win.
     */
    public void applyTranslationSystemProperties() {
        String engine = optional("translation.engine", "ollama").toLowerCase();
        String endpoint = optional("translation.endpoint", "");
        if (engine.equals("api")) {
            // A remote OpenAI-compatible provider has no sensible localhost default; requiring the
            // endpoint up front turns a late ConnectException to localhost:11434 into a clear config error.
            if (endpoint.isEmpty()) {
                throw new IllegalArgumentException(
                        "translation.engine=api requires translation.endpoint to be set.");
            }
        } else if (endpoint.isEmpty()) {
            endpoint = engine.equals("llamacpp")
                    ? "http://localhost:8080/v1/chat/completions"
                    : "http://localhost:11434/v1/chat/completions";
        }

        setIfPresent("ITDROID_LLM_ENDPOINT", endpoint);
        setIfPresent("ITDROID_LLM_MODEL", optional("translation.model", "qwen2.5:3b"));
        setIfPresent("ITDROID_LLM_API_KEY", optional("translation.apiKey", ""));
        setIfPresent("ITDROID_LLM_TIMEOUT", optional("translation.timeoutSeconds", ""));
        setIfPresent("ITDROID_LLM_TEMPERATURE", optional("translation.temperature", ""));
        // auto (by model name) | json (batched, general chat models) | translategemma | template (one text per request)
        setIfPresent("ITDROID_LLM_STYLE", optional("translation.style", ""));
        // Prompt for style=template, with {source}, {target} and {text}; \n escapes are newlines.
        setIfPresent("ITDROID_LLM_PROMPT_TEMPLATE", optional("translation.promptTemplate", ""));
        // true|false: mask %1$s / {name} / tags with <phN> tokens around each single-text request.
        setIfPresent("ITDROID_LLM_MASK_PLACEHOLDERS", optional("translation.maskPlaceholders", ""));
        setIfPresent("ITDROID_LLM_CONTEXT", optional("translation.context", ""));
    }

    private static void setIfPresent(String key, String value) {
        if (value != null && !value.isEmpty()) {
            System.setProperty(key, value);
        }
    }

    /**
     * The autostart spec passed to {@link LLMServerManager}: an Ollama model name (starts/pulls it), a
     * llama.cpp launch command, or empty (the server must already be running / is remote).
     */
    public String getAutoStart() {
        return optional("translation.autoStart", "");
    }

    /** Reuse the translations of a previous {@code strings_inventory.json} in the output folder (default true). */
    public boolean isReuseInventory() {
        return boolOptional("translation.reuseInventory", true);
    }

    /** Run the launch test of the original app before processing it (default true). */
    public boolean isLaunchTest() {
        return boolOptional("preflight.launchTest", true);
    }

    /** UI states captured per app version (default 5); 0 for no limit. */
    public int getMaxStates() {
        return Math.max(0, intOptional("exploration.maxStates", 5));
    }

    /** Clickables tapped per state (default 8); 0 for all of them. */
    public int getMaxClickables() {
        return Math.max(0, intOptional("exploration.maxClickables", 8));
    }

    /** Minutes the exploration of the source language may take (default 0, no limit). */
    public int getMaxMinutes() {
        return Math.max(0, intOptional("exploration.maxMinutes", 0));
    }

    /**
     * A file of adb commands run after every clean start of the app, to leave content on the device
     * before it is explored (config key exploration.setup, default none). See {@link SetupScript}.
     */
    public String getSetupFile() {
        return props.getProperty("exploration.setup", "").trim();
    }

    /** Seconds to wait after each launch before the first capture, on top of the stability check (default 3). */
    public int getSettleSeconds() {
        return intOptional("exploration.settleSeconds", 3);
    }

    /** Ask the LLM for values and type them into text fields during exploration (default false). */
    public boolean isFillForms() {
        return boolOptional("exploration.fillForms", false);
    }

    /**
     * Extra package roots of the app's own code, comma separated (key hardcoded.codePackages). Only
     * needed when the code is split in modules the manifest does not mention; blank otherwise.
     */
    public String getCodePackages() {
        return optional("hardcoded.codePackages", "");
    }

    /**
     * Which hardcoded literals of the code are translated (key hardcoded.translate): {@code ui}
     * (default), {@code ui+unknown} or {@code all}. See {@link HardcodedPolicy}.
     */
    public HardcodedPolicy getHardcodedPolicy() {
        return HardcodedPolicy.parse(optional("hardcoded.translate", ""));
    }

    /**
     * Move the translated literals of the code to string resources, so the single internationalized
     * APK shows them in every language (key hardcoded.extractCode, default true). With false they
     * are only patched into the per-language APKs.
     */
    public boolean isExtractCode() {
        return boolOptional("hardcoded.extractCode", true);
    }

    /**
     * Keep the APK built for each language in the results folder after it was evaluated (key
     * output.keepLanguageApks, default true). With false each one is removed once its language is
     * done: the results keep the source build and the internationalized APK, and take a fraction
     * of the disk.
     */
    public boolean isKeepLanguageApks() {
        return boolOptional("output.keepLanguageApks", true);
    }

    /**
     * For each right-to-left language, also build and evaluate the app with its layouts repaired to
     * follow the direction of the language, next to the app as it is (config key rtl.repair, default
     * false). It adds one build and one replay per right-to-left language.
     */
    public boolean isRtlRepair() {
        return boolOptional("rtl.repair", false);
    }

    /**
     * Also write {@code graph.txt} and {@code graph.json} next to each {@code result.json} (default
     * false). They list every relation of every pair of nodes: useful to debug the comparison by
     * hand, large, and not read by anything else.
     */
    public boolean isGraphDumps() {
        return boolOptional("debug.graphDumps", false);
    }

    /**
     * Share of the elements of a replayed screen, from 0 to 1, that must match those of the source
     * screen for the two to be compared as the same screen (key comparison.minSimilarity, default
     * 0.5). Lower it to compare screens whose content differs more; raise it to be stricter.
     */
    public double getMinSimilarity() {
        String raw = optional("comparison.minSimilarity", "");
        if (raw.isEmpty()) {
            return uniandes.tsdl.itdroid.model.LayoutGraphComparision.DEFAULT_MIN_SIMILARITY;
        }
        double value;
        try {
            value = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            value = Double.NaN;
        }
        if (!(value >= 0 && value <= 1)) {
            throw new IllegalArgumentException("Config key 'comparison.minSimilarity' must be a number between 0 and 1, got '" + raw + "'.");
        }
        return value;
    }

    /**
     * Also look at the screenshots for translated text drawn over a graphic of the app that is not an
     * element of the dump, such as the arrow of a drop-down list (key comparison.textOverGraphic,
     * default true).
     */
    public boolean isTextOverGraphic() {
        return boolOptional("comparison.textOverGraphic", true);
    }

    private int intOptional(String key, int defaultValue) {
        String raw = optional(key, "");
        if (raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Config key '" + key + "' must be an integer, got '" + raw + "'.");
        }
    }

    private boolean boolOptional(String key, boolean defaultValue) {
        String raw = optional(key, "").toLowerCase();
        if (raw.isEmpty()) {
            return defaultValue;
        }
        return raw.equals("true") || raw.equals("yes") || raw.equals("1") || raw.equals("on");
    }
}

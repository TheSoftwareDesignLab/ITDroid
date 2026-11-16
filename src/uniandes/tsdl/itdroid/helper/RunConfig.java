package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * The run configuration file, a .properties file passed as the only argument in place of the
 * positional ones. Its translation keys become the ITDROID_LLM system properties read by
 * {@link uniandes.tsdl.itdroid.translator.LLMTranslator}. See itdroid.config.example.properties.
 */
public class RunConfig {

    private final Properties props;

    private RunConfig(Properties props) {
        this.props = props;
    }

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

    /** True when a lone CLI argument is a config file rather than a positional argument. */
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
    /** Explicit output folder, empty for the app package folder inside the results folder. */
    public String getOutput()      { return optional("output", ""); }
    /** Root of all evaluations, the folder --report serves. */
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
     * Copies the translation keys into the ITDROID_LLM system properties. The engine only picks a
     * default endpoint, explicit keys always win.
     */
    public void applyTranslationSystemProperties() {
        String engine = optional("translation.engine", "ollama").toLowerCase();
        String endpoint = optional("translation.endpoint", "");
        if (engine.equals("api")) {
            // a remote provider has no localhost default, so a missing endpoint fails here and not later
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
        // auto, json, translategemma or template
        setIfPresent("ITDROID_LLM_STYLE", optional("translation.style", ""));
        // prompt for the template style, with {source}, {target} and {text} placeholders
        setIfPresent("ITDROID_LLM_PROMPT_TEMPLATE", optional("translation.promptTemplate", ""));
        // masks format specifiers and tags with tokens around each single-text request
        setIfPresent("ITDROID_LLM_MASK_PLACEHOLDERS", optional("translation.maskPlaceholders", ""));
        setIfPresent("ITDROID_LLM_CONTEXT", optional("translation.context", ""));
    }

    private static void setIfPresent(String key, String value) {
        if (value != null && !value.isEmpty()) {
            System.setProperty(key, value);
        }
    }

    /** The autostart spec for {@link LLMServerManager}, a model name, a launch command or empty. */
    public String getAutoStart() {
        return optional("translation.autoStart", "");
    }

    /** Reuse the translations of a previous strings_inventory.json in the output folder. */
    public boolean isReuseInventory() {
        return boolOptional("translation.reuseInventory", true);
    }

    /** Run the launch test of the original app before processing it. */
    public boolean isLaunchTest() {
        return boolOptional("preflight.launchTest", true);
    }

    /** UI states captured per app version, 0 for no limit. */
    public int getMaxStates() {
        return Math.max(0, intOptional("exploration.maxStates", 5));
    }

    /** Clickables tapped per state, 0 for all of them. */
    public int getMaxClickables() {
        return Math.max(0, intOptional("exploration.maxClickables", 8));
    }

    /** Minutes the source exploration may take, 0 for no limit. */
    public int getMaxMinutes() {
        return Math.max(0, intOptional("exploration.maxMinutes", 0));
    }

    /** The {@link SetupScript} file of adb commands run after every clean start, or empty. */
    public String getSetupFile() {
        return props.getProperty("exploration.setup", "").trim();
    }

    /** Seconds to wait after each launch before the first capture, on top of the stability check. */
    public int getSettleSeconds() {
        return intOptional("exploration.settleSeconds", 3);
    }

    /** Ask the LLM for values to type into text fields during exploration. */
    public boolean isFillForms() {
        return boolOptional("exploration.fillForms", false);
    }

    /** Extra comma-separated package roots of the app code, for modules the manifest does not name. */
    public String getCodePackages() {
        return optional("hardcoded.codePackages", "");
    }

    /** Which hardcoded literals of the code are translated. */
    public HardcodedPolicy getHardcodedPolicy() {
        return HardcodedPolicy.parse(optional("hardcoded.translate", ""));
    }

    /**
     * Move translated code literals to string resources so the internationalized APK shows them in
     * every language, otherwise they are only patched into the per-language APKs.
     */
    public boolean isExtractCode() {
        return boolOptional("hardcoded.extractCode", true);
    }

    /** Keep each per-language APK after its evaluation, false saves most of the disk space. */
    public boolean isKeepLanguageApks() {
        return boolOptional("output.keepLanguageApks", true);
    }

    /** Also evaluate a build with repaired layouts for each right-to-left language, one more replay each. */
    public boolean isRtlRepair() {
        return boolOptional("rtl.repair", false);
    }

    /** Also write the large graph.txt and graph.json debug dumps next to each result.json. */
    public boolean isGraphDumps() {
        return boolOptional("debug.graphDumps", false);
    }

    /** Share of elements, from 0 to 1, a replayed screen must share with the source one to be compared. */
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

    /** Also look in the screenshots for text drawn over graphics that are not in the dump. */
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

package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The configuration file, the only place a run is configured. Its translation keys become the
 * ITDROID_LLM system properties read by {@link uniandes.tsdl.itdroid.translator.LLMTranslator}.
 * See itdroid.config.properties at the root of the repository.
 */
public class RunConfig {

    /** The file ITDroid reads when it is given none, in the folder it runs in. */
    public static final String DEFAULT_FILE = "itdroid.config.properties";

    /** Translation model when translation.model is empty, the one the versioned config file names. */
    public static final String DEFAULT_MODEL = "hy-mt2:1.8b-q8";

    /** Languages evaluated when the languages key is empty. */
    public static final String DEFAULT_LANGUAGES = "es,ru,ar";

    private final Properties props;
    private final String file;

    private RunConfig(Properties props, String file) {
        this.props = props;
        this.file = file;
    }

    public static RunConfig load(String path) throws IOException {
        File f = new File(path);
        if (!f.isFile()) {
            throw new java.io.FileNotFoundException("Config file not found: " + f.getAbsolutePath()
                    + ". ITDroid reads " + DEFAULT_FILE + " from the folder it runs in, or the file given after the mode.");
        }
        Properties p = new Properties();
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            p.load(reader);
        }
        return new RunConfig(p, f.getName());
    }

    /** The config file of the working folder, or null when there is none. */
    public static RunConfig loadDefault() {
        try {
            return new File(DEFAULT_FILE).isFile() ? load(DEFAULT_FILE) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private String required(String key) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Set '" + key + "' in " + file + ".");
        }
        return value.trim();
    }

    private String optional(String key, String defaultValue) {
        String value = props.getProperty(key);
        return (value == null || value.trim().isEmpty()) ? defaultValue : value.trim();
    }

    public String getApkPath()     { return required("apkPath"); }
    /** Empty to take the package of the APK. */
    public String getAppPackage()  { return optional("appPackage", ""); }
    /** AVD name or serial of the device to use, empty for the only one connected. */
    public String getEmulator()    { return optional("emulator", ""); }
    public String getExtraFolder() { return optional("extraFolder", "extra"); }
    /** Root of all evaluations, one folder per app, and the folder --report serves. */
    public String getResultsDir()  { return optional("resultsDir", "results"); }

    /** Language the app is written in, empty to ask the model. */
    public String getSourceLanguage() {
        String value = props.getProperty("sourceLanguage");
        return value == null ? "en" : value.trim();
    }

    /** Codes of the languages to evaluate, as Android names its folders (es, ar, zh-rCN). */
    public List<String> getLanguages() {
        List<String> codes = list("languages", DEFAULT_LANGUAGES);
        for (String code : codes) {
            if (!code.matches("[a-z]{2,3}(-r[A-Z]{2})?")) {
                throw new IllegalArgumentException("'" + code + "' in 'languages' is not a language code such as es, ar or zh-rCN.");
            }
        }
        if (codes.isEmpty()) {
            throw new IllegalArgumentException("Set at least one language in 'languages' of " + file + ".");
        }
        return codes;
    }

    /** Resource names of the app that are never translated, on top of the framework ones ITDroid knows. */
    public List<String> getKeepResources() {
        return list("translation.keepResources", "");
    }

    /** A file of extra rules for what the code does with a text, tried before the built-in ones, or empty. */
    public String getExtraRules() {
        return optional("hardcoded.extraRules", "");
    }

    private List<String> list(String key, String defaultValue) {
        List<String> out = new ArrayList<>();
        for (String part : optional(key, defaultValue).split(",")) {
            if (!part.trim().isEmpty() && !out.contains(part.trim())) {
                out.add(part.trim());
            }
        }
        return out;
    }

    public int getAlpha() {
        return intOptional("alpha", 0);
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
        setIfPresent("ITDROID_LLM_MODEL", getModel());
        setIfPresent("ITDROID_LLM_API_KEY", optional("translation.apiKey", ""));
        setIfPresent("ITDROID_LLM_TIMEOUT", optional("translation.timeoutSeconds", ""));
        setIfPresent("ITDROID_LLM_TEMPERATURE", optional("translation.temperature", ""));
        // auto, json, translategemma, template or plamo
        setIfPresent("ITDROID_LLM_STYLE", optional("translation.style", ""));
        // prompt for the template style, with {source}, {target} and {text} placeholders
        setIfPresent("ITDROID_LLM_PROMPT_TEMPLATE", optional("translation.promptTemplate", ""));
        // masks format specifiers and tags with tokens around each single-text request
        setIfPresent("ITDROID_LLM_MASK_PLACEHOLDERS", optional("translation.maskPlaceholders", ""));
        setIfPresent("ITDROID_LLM_CONTEXT", optional("translation.context", ""));
        // names kept as written in every translation, comma separated
        setIfPresent("ITDROID_LLM_PROTECTED_TERMS", optional("translation.protectedTerms", ""));
    }

    private String getModel() {
        return optional("translation.model", DEFAULT_MODEL);
    }

    private static void setIfPresent(String key, String value) {
        if (value != null && !value.isEmpty()) {
            System.setProperty(key, value);
        }
    }

    /**
     * What {@link LLMServerManager} starts. True starts Ollama with translation.model, a command is run
     * as written, empty or false starts nothing, so the server must be running. A model name is accepted
     * only when it is translation.model, so the two lines cannot name different models.
     */
    public String getAutoStart() {
        String raw = optional("translation.autoStart", "false");
        String lower = raw.toLowerCase();
        if (lower.equals("false") || lower.equals("no") || lower.equals("off") || lower.equals("0")) {
            return "";
        }
        if (lower.equals("true") || lower.equals("yes") || lower.equals("on") || lower.equals("1")) {
            return optional("translation.engine", "ollama").equalsIgnoreCase("ollama") ? getModel() : "";
        }
        if (!raw.contains(" ") && !raw.equals(getModel())) {
            throw new IllegalArgumentException("translation.autoStart names the model '" + raw + "' but translation.model is '"
                    + getModel() + "'. Write translation.autoStart=true to start the model of translation.model.");
        }
        return raw;
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

    /**
     * Grant every runtime permission before each start. False lets the app ask and answers Allow, so
     * the screens that explain a permission are seen as a new user sees them.
     */
    public boolean isGrantPermissions() {
        return boolOptional("exploration.grantPermissions", false);
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
     * Move the code literals shown in the interface to string resources so they are translated like the
     * rest. False leaves them in the source language.
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

    /** Also look in the screenshots for translated texts cut inside a box that did not grow. */
    public boolean isTextCut() {
        return boolOptional("comparison.textCut", true);
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

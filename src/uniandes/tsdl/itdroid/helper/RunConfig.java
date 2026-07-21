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
    public String getOutput()      { return optional("output", "output"); }
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
}

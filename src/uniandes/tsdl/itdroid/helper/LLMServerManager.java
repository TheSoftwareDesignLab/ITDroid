package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import uniandes.tsdl.itdroid.translator.LLMTranslator;

/**
 * Starts (when needed) and stops the local LLM server so a run never fails just because the model
 * server was not up. The optional 8th CLI argument can be:
 * <ul>
 *   <li><b>an Ollama model name</b> (e.g. {@code qwen2.5:3b}) — ITDroid points the translator at
 *       Ollama, starts {@code ollama serve} if needed, and {@code ollama pull}s the model (so it is
 *       downloaded on first use);</li>
 *   <li><b>a launch command</b> (e.g. {@code "ollama serve"} or
 *       {@code "llama-server -m model.gguf --port 8080"}) — ITDroid runs it and waits until the
 *       endpoint from {@code .env} answers;</li>
 *   <li><b>empty</b> — the server must already be running (a pre-flight check fails fast otherwise).</li>
 * </ul>
 * A server ITDroid started is stopped at the end; a server the user already had running is left alone.
 */
public class LLMServerManager {

    private static final int STARTUP_TIMEOUT_SECONDS = 180;
    private static final String OLLAMA_ENDPOINT = "http://localhost:11434/v1/chat/completions";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String settingsDir;
    private String endpoint;         // e.g. http://localhost:8080/v1/chat/completions
    private String origin;           // e.g. http://localhost:8080
    private Process serverProcess;   // non-null ONLY if we started it (so stop() never kills a user-run server)
    private boolean shutdownHookRegistered;

    public LLMServerManager(String settingsDir) {
        this.settingsDir = settingsDir;
    }

    /** Registers the stop() shutdown hook exactly once, even if a start path is reached more than once. */
    private void registerShutdownHook() {
        if (!shutdownHookRegistered) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
            shutdownHookRegistered = true;
        }
    }

    /**
     * Guarantees the LLM is reachable before any translation runs, interpreting the optional spec
     * (the 8th CLI argument) as an Ollama model name, a launch command, or nothing.
     */
    public void ensureRunning(String spec, String logDir) throws Exception {
        String s = spec == null ? "" : spec.trim();

        // A bare token like "qwen2.5:3b" / "mistral:7b" is treated as an Ollama model (not a command).
        boolean looksLikeModel = !s.isEmpty()
                && !s.contains(" ") && !s.contains("\t")
                && !s.contains("/") && !s.contains("\\")
                && (s.contains(":") || s.matches("[A-Za-z0-9._-]+"));

        if (looksLikeModel) {
            ensureOllamaModel(s, logDir);
            return;
        }

        // Command mode (or no command): the endpoint comes from .env / system properties.
        this.endpoint = LLMTranslator.resolveEndpoint(settingsDir);
        this.origin = origin(endpoint);

        // If the autostart command pins a port (e.g. "llama-server --port 8080") that differs from the
        // resolved endpoint's port (which defaults to :11434), probe THAT port. Otherwise the readiness
        // check hits the wrong port, hangs for the whole startup timeout, and then falsely reports failure.
        Integer launchPort = parsePort(s);
        if (launchPort != null) {
            try {
                URI u = URI.create(endpoint);
                String scheme = u.getScheme() == null ? "http" : u.getScheme();
                String host = u.getHost() == null ? "localhost" : u.getHost();
                this.origin = scheme + "://" + host + ":" + launchPort;
            } catch (Exception ignored) {
                this.origin = "http://localhost:" + launchPort;
            }
        }
        System.out.println("LLM readiness probe origin: " + origin + " (resolved endpoint " + endpoint + ")");

        if (ready()) {
            System.out.println("LLM server already running at " + endpoint);
            return;
        }
        if (s.isEmpty()) {
            // Remote OpenAI-compatible endpoint with an API token (translation.engine=api): a third-party
            // provider cannot be reliably health-checked (its /v1/models or /health may need auth or not
            // exist), so trust the configuration. A wrong endpoint/token surfaces as a clear HTTP error on
            // the first translation request instead of failing the readiness probe here.
            String apiKey = LLMTranslator.resolveApiKey(settingsDir);
            if (apiKey != null && !apiKey.isEmpty()) {
                System.out.println("Using remote translation endpoint " + endpoint + " with an API token.");
                return;
            }
            throw new IllegalStateException("No LLM server is reachable at " + endpoint
                    + " and no model/command was provided (8th argument). Pass an Ollama model name "
                    + "(e.g. \"qwen2.5:3b\"), a launch command (e.g. \"llama-server -m model.gguf --port 8080\"), "
                    + "or start your server manually, then retry.");
        }
        System.out.println("Starting LLM server: " + s);
        serverProcess = spawn(s, logDir);
        registerShutdownHook();
        waitUntilReady();
        System.out.println("LLM server is ready at " + endpoint);
    }

    /** Ollama path: point the translator at Ollama, ensure the daemon is up, and pull the model if missing. */
    private void ensureOllamaModel(String model, String logDir) throws Exception {
        this.endpoint = OLLAMA_ENDPOINT;
        this.origin = origin(endpoint);
        // System properties have top precedence in LLMTranslator.resolve(), so this overrides .env.
        System.setProperty("ITDROID_LLM_ENDPOINT", endpoint);
        System.setProperty("ITDROID_LLM_MODEL", model);
        System.out.println("Using Ollama model '" + model + "' at " + endpoint);

        if (!ollamaAvailable()) {
            throw new IllegalStateException("The 8th argument '" + model + "' looks like an Ollama model, but "
                    + "'ollama' is not on PATH. Install Ollama (https://ollama.com) or pass a full launch command "
                    + "instead (e.g. \"llama-server -m model.gguf --port 8080\").");
        }
        // Start the Ollama daemon only if nothing is serving yet (otherwise reuse it, and don't kill it).
        if (ready()) {
            System.out.println("Ollama already running at " + endpoint);
        } else {
            System.out.println("Starting Ollama daemon: ollama serve");
            serverProcess = spawn("ollama serve", logDir);
            registerShutdownHook();
            waitUntilReady();
        }
        // Download the model if it isn't local yet ("up to date" / quick no-op when it already is).
        System.out.println("Ensuring the model is downloaded: ollama pull " + model);
        int code = runToCompletion("ollama", "pull", model);
        if (code != 0) {
            throw new IllegalStateException("'ollama pull " + model + "' failed (exit " + code
                    + "). Check the model name at https://ollama.com/library.");
        }
    }

    /** Stops the server we started (and its children). No-op when the server was started by the user. */
    public void stop() {
        if (serverProcess == null) {
            return;
        }
        try {
            serverProcess.descendants().forEach(ProcessHandle::destroy);
            serverProcess.destroy();
            if (!serverProcess.waitFor(5, TimeUnit.SECONDS)) {
                serverProcess.descendants().forEach(ProcessHandle::destroyForcibly);
                serverProcess.destroyForcibly();
            }
            System.out.println("LLM server stopped.");
        } catch (Exception ignored) {
            // best effort
        } finally {
            serverProcess = null;
        }
    }

    /** True once the server answers 200 on an OpenAI-compatible (or llama.cpp) readiness URL. */
    private boolean ready() {
        for (String url : new String[] { origin + "/v1/models", origin + "/health" }) {
            try {
                HttpResponse<Void> r = http.send(
                        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (r.statusCode() == 200) {
                    return true;
                }
            } catch (Exception ignored) {
                // not up yet
            }
        }
        return false;
    }

    private void waitUntilReady() throws Exception {
        long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (serverProcess != null && !serverProcess.isAlive()) {
                throw new IllegalStateException("The LLM server exited before becoming ready (exit code "
                        + serverProcess.exitValue() + "). Check llm-server.log.");
            }
            if (ready()) {
                return;
            }
            System.out.println("  waiting for the LLM server to be ready at " + origin + "...");
            Thread.sleep(2000);
        }
        throw new IllegalStateException("LLM server did not become ready within " + STARTUP_TIMEOUT_SECONDS
                + "s (probing " + origin + ", resolved endpoint " + endpoint + "). Check llm-server.log.");
    }

    /** Runs the command through the OS shell so quoted paths and arguments are parsed as the user typed them. */
    private Process spawn(String command, String logDir) throws IOException {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        ProcessBuilder pb = windows
                ? new ProcessBuilder("cmd", "/c", command)
                : new ProcessBuilder("sh", "-c", command);
        pb.redirectErrorStream(true);
        File dir = new File(logDir == null || logDir.isEmpty() ? "." : logDir);
        dir.mkdirs();
        File log = new File(dir, "llm-server.log");
        pb.redirectOutput(ProcessBuilder.Redirect.to(log));
        System.out.println("  LLM server output -> " + log.getAbsolutePath());
        return pb.start();
    }

    /** True if the {@code ollama} CLI is on PATH. */
    private boolean ollamaAvailable() {
        Process p = null;
        try {
            p = new ProcessBuilder("ollama", "--version").redirectErrorStream(true).start();
            // Bounded wait so a hung 'ollama' cannot block the whole run; drain+close the stream so the
            // child is not left blocked on a full pipe buffer and the handle is released.
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            try (InputStream in = p.getInputStream()) {
                in.readAllBytes();
            } catch (IOException ignored) {
                // best effort drain
            }
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return true;
        } catch (Exception e) {
            if (p != null) {
                p.destroyForcibly();
            }
            return false;
        }
    }

    /** Runs a command inheriting console IO (so e.g. download progress is visible) and returns its exit code. */
    private int runToCompletion(String... command) throws Exception {
        Process p = new ProcessBuilder(command).inheritIO().start();
        // Bounded wait so a stalled child (e.g. a hung 'ollama pull') cannot block forever. Generous,
        // because a first-time model download can legitimately take several minutes.
        if (!p.waitFor(3600, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("Command timed out after 3600s: " + String.join(" ", command));
        }
        return p.exitValue();
    }

    /** Extracts the port from a launch command's {@code --port} flag (e.g. "llama-server --port 8080"), or null. */
    private static Integer parsePort(String command) {
        if (command == null || command.isEmpty()) {
            return null;
        }
        Matcher m = Pattern.compile("--port[\\s=]+(\\d{2,5})").matcher(command);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return null;
    }

    /** scheme://host[:port] of a URL, used to build readiness probes. */
    private static String origin(String url) {
        try {
            URI u = URI.create(url);
            int port = u.getPort();
            return u.getScheme() + "://" + u.getHost() + (port == -1 ? "" : ":" + port);
        } catch (Exception e) {
            return "http://localhost:8080";
        }
    }
}

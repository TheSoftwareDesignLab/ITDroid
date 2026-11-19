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
 * Starts the local LLM server when needed and stops it at the end. translation.autoStart gives an
 * Ollama model name (served and pulled), a launch command (run until the endpoint answers), or
 * nothing (the server must already run). A server the user started is never stopped.
 */
public class LLMServerManager {

    private static final int STARTUP_TIMEOUT_SECONDS = 180;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private String endpoint;
    private String origin;
    private Process serverProcess;   // set only when ITDroid started it, so stop() never kills a user server
    private boolean shutdownHookRegistered;

    private void registerShutdownHook() {
        if (!shutdownHookRegistered) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
            shutdownHookRegistered = true;
        }
    }

    /** Makes sure the LLM is reachable before any translation, reading spec as a model, a command or nothing. */
    public void ensureRunning(String spec, String logDir) throws Exception {
        String s = spec == null ? "" : spec.trim();

        // a single token is an Ollama model name, a Hugging Face one included, and a script path is a command
        boolean looksLikeModel = !s.isEmpty() && !s.matches(".*\\s.*")
                && !s.contains("\\") && !s.matches(".*\\.(bat|cmd|sh|exe|ps1)$");

        if (looksLikeModel) {
            ensureOllamaModel(s, logDir);
            return;
        }

        this.endpoint = LLMTranslator.resolveEndpoint();
        this.origin = origin(endpoint);

        // a --port in the command wins, or the probe would wait the whole timeout on the wrong port
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
            // a remote API with a token cannot be health-checked reliably, so a wrong one shows up as an
            // HTTP error on the first translation instead
            String apiKey = LLMTranslator.resolveApiKey();
            if (apiKey != null && !apiKey.isEmpty()) {
                System.out.println("Using remote translation endpoint " + endpoint + " with an API token.");
                return;
            }
            throw new IllegalStateException("No LLM server is reachable at " + endpoint
                    + " and translation.autoStart starts nothing. Set translation.autoStart=true for Ollama, "
                    + "or a launch command such as llama-server -m model.gguf --port 8080, or start your server, then retry.");
        }
        System.out.println("Starting LLM server: " + s);
        serverProcess = spawn(s, logDir);
        registerShutdownHook();
        waitUntilReady();
        System.out.println("LLM server is ready at " + endpoint);
    }

    /**
     * Points the translator at Ollama on the configured endpoint, starts the daemon if needed and pulls
     * the model.
     */
    private void ensureOllamaModel(String model, String logDir) throws Exception {
        this.endpoint = LLMTranslator.resolveEndpoint();
        this.origin = origin(endpoint);
        // system properties are what LLMTranslator reads
        System.setProperty("ITDROID_LLM_ENDPOINT", endpoint);
        System.setProperty("ITDROID_LLM_MODEL", model);
        System.out.println("Using Ollama model '" + model + "' at " + endpoint);

        if (!ollamaAvailable()) {
            throw new IllegalStateException("translation.autoStart starts the Ollama model '" + model + "', but "
                    + "'ollama' is not on PATH. Install Ollama (https://ollama.com) or write a launch command "
                    + "in translation.autoStart (e.g. llama-server -m model.gguf --port 8080).");
        }
        if (ready()) {
            System.out.println("Ollama already running at " + endpoint);
        } else {
            System.out.println("Starting Ollama daemon: ollama serve");
            serverProcess = spawn("ollama serve", logDir);
            registerShutdownHook();
            waitUntilReady();
        }
        // quick no-op when the model is already local
        System.out.println("Ensuring the model is downloaded: ollama pull " + model);
        int code = runToCompletion("ollama", "pull", model);
        if (code != 0) {
            throw new IllegalStateException("'ollama pull " + model + "' failed (exit " + code
                    + "). Check the model name at https://ollama.com/library.");
        }
    }

    /** Stops the server ITDroid started and its children, never one the user started. */
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

    /** True once the server answers 200 on an OpenAI-compatible or llama.cpp readiness URL. */
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

    /** Runs the command through the OS shell so quoted arguments parse as the user typed them. */
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

    private boolean ollamaAvailable() {
        Process p = null;
        try {
            p = new ProcessBuilder("ollama", "--version").redirectErrorStream(true).start();
            // bounded wait so a hung ollama cannot block the run, and the drain frees the pipe
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

    /** Runs a command on the console, so download progress shows, and returns its exit code. */
    private int runToCompletion(String... command) throws Exception {
        Process p = new ProcessBuilder(command).inheritIO().start();
        // generous, since a first model download can take several minutes
        if (!p.waitFor(3600, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("Command timed out after 3600s: " + String.join(" ", command));
        }
        return p.exitValue();
    }

    /** The port of the --port flag of a launch command, or null. */
    private static Integer parsePort(String command) {
        if (command == null || command.isEmpty()) {
            return null;
        }
        Matcher m = Pattern.compile("--port[\\s=]+(\\d{2,5})").matcher(command);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /** The scheme, host and port of a URL, the base of the readiness probes. */
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

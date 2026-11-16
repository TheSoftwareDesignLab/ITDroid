package uniandes.tsdl.itdroid.translator;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A local stand-in for an OpenAI-compatible chat-completions endpoint (Ollama, llama.cpp), so the
 * translator can be tested without a model. A test sets {@link #responder}, which receives every
 * request already taken apart and returns the assistant's answer.
 */
final class FakeLlmServer implements AutoCloseable {

    /** What the translator sent in one request. */
    static final class Request {
        JSONObject body;
        String system = "";
        String user = "";
        /** True when the request asked for a JSON object answer (the batched style). */
        boolean jsonMode;
        /** In the batched style, the texts to translate by key, in key order. */
        Map<String, String> items = new TreeMap<>();
        /** In the one-text style, the text after the last blank line of the prompt. */
        String text = "";
    }

    /** Thrown by a responder to answer with an HTTP error instead of a completion. */
    static final class HttpError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;

        HttpError(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }

    interface Responder {
        String reply(Request request) throws Exception;
    }

    final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    volatile Responder responder = r -> "";
    private final HttpServer server;

    FakeLlmServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
    }

    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    /** A JSON object answer for the batched style, from key/value pairs. */
    @SuppressWarnings("unchecked")
    static String jsonAnswer(Map<String, String> values) {
        JSONObject o = new JSONObject();
        o.putAll(values);
        return o.toJSONString();
    }

    /** Applies {@code translate} to every item of a batched request and returns the JSON answer. */
    static String translateItems(Request request, java.util.function.UnaryOperator<String> translate) {
        Map<String, String> out = new LinkedHashMap<>();
        request.items.forEach((k, v) -> out.put(k, translate.apply(v)));
        return jsonAnswer(out);
    }

    @SuppressWarnings("unchecked")
    private void handle(HttpExchange exchange) throws IOException {
        int status = 200;
        String answer;
        try {
            Request request = parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(request);
            String content = responder.reply(request);
            JSONObject message = new JSONObject();
            message.put("role", "assistant");
            message.put("content", content);
            JSONObject choice = new JSONObject();
            choice.put("index", 0L);
            choice.put("message", message);
            JSONArray choices = new JSONArray();
            choices.add(choice);
            JSONObject root = new JSONObject();
            root.put("choices", choices);
            answer = root.toJSONString();
        } catch (HttpError e) {
            status = e.status;
            answer = "{\"error\":\"" + e.getMessage() + "\"}";
        } catch (Exception e) {
            status = 500;
            answer = "{\"error\":\"fake server: " + e + "\"}";
        }
        byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Request parse(String raw) throws Exception {
        Request r = new Request();
        r.body = (JSONObject) new JSONParser().parse(raw);
        r.jsonMode = r.body.get("response_format") != null;
        for (Object m : (JSONArray) r.body.get("messages")) {
            JSONObject message = (JSONObject) m;
            String content = String.valueOf(message.get("content"));
            if ("system".equals(message.get("role"))) {
                r.system = content;
            } else {
                r.user = content;
            }
        }
        if (r.jsonMode) {
            int brace = r.user.indexOf('{');
            if (brace >= 0) {
                JSONObject items = (JSONObject) new JSONParser().parse(r.user.substring(brace));
                for (Object k : items.keySet()) {
                    r.items.put(String.valueOf(k), String.valueOf(items.get(k)));
                }
            }
        } else {
            int blank = r.user.lastIndexOf("\n\n");
            r.text = blank >= 0 ? r.user.substring(blank + 2) : r.user;
        }
        return r;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

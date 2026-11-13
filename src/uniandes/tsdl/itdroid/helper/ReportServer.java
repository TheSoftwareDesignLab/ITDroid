package uniandes.tsdl.itdroid.helper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * {@code java -jar ITDroid.jar --report [resultsFolder] [--port N] [--no-browser]}: serves the web
 * report ({@code webreport/}) together with the results folder on localhost and opens the browser.
 * The page lists every evaluation found in the folder ({@code /api/results}) and reads each app's
 * {@code report.json}, inventory, screenshots and exploration results from {@code /results/...}.
 * A local server is needed because browsers do not let a page opened from disk read other files.
 */
public class ReportServer {

    public static final int DEFAULT_PORT = 8585;

    public static void run(String[] args) throws Exception {
        String root = "results";
        int port = DEFAULT_PORT;
        boolean openBrowser = true;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--no-browser")) {
                openBrowser = false;
            } else {
                root = args[i];
            }
        }
        Path results = Paths.get(root).toAbsolutePath().normalize();
        if (!Files.isDirectory(results)) {
            throw new ITDroidException("Results folder not found: " + results
                    + " (run an evaluation first, or pass the folder: --report <folder>)");
        }
        Path web = findWebReport();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/results", ex -> sendJson(ex, scan(results)));
        server.createContext("/api/files/", ex -> sendJson(ex, listFiles(results,
                URLDecoder.decode(ex.getRequestURI().getRawPath().substring("/api/files/".length()), StandardCharsets.UTF_8))));
        server.createContext("/results/", ex -> serveFile(ex, results, "/results/"));
        server.createContext("/", ex -> serveFile(ex, web, "/"));
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        String url = "http://127.0.0.1:" + port + "/";
        System.out.println("ITDroid report: " + url);
        System.out.println("  results folder: " + results);
        System.out.println("  web report:     " + web);
        System.out.println("  press Ctrl+C to stop");
        if (openBrowser) {
            openBrowser(url);
        }
        Thread.currentThread().join();
    }

    /** {@code webreport/} next to the working directory or next to the jar. */
    static Path findWebReport() throws ITDroidException {
        List<Path> candidates = new ArrayList<>();
        candidates.add(Paths.get("webreport"));
        try {
            Path jar = Paths.get(ReportServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path jarDir = Files.isDirectory(jar) ? jar : jar.getParent();
            if (jarDir != null) {
                candidates.add(jarDir.resolve("webreport"));
                if (jarDir.getParent() != null) {
                    candidates.add(jarDir.getParent().resolve("webreport"));
                }
            }
        } catch (Exception ignored) {
            // not running from a jar/folder we can locate
        }
        for (Path c : candidates) {
            if (Files.isRegularFile(c.resolve("index.html"))) {
                return c.toAbsolutePath().normalize();
            }
        }
        throw new ITDroidException("webreport/index.html not found (looked in " + candidates + "); run from the ITDroid folder.");
    }

    private static void openBrowser(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Throwable ignored) {
            // headless or no browser: the URL is printed anyway
        }
        System.out.println("  (open the address above in your browser)");
    }

    // ------------------------------------------------------------------ the index

    /** One entry per sub-folder of the results folder that holds a report.json. */
    @SuppressWarnings("unchecked")
    static JSONObject scan(Path results) {
        JSONObject out = new JSONObject();
        out.put("root", results.toString());
        out.put("generatedAt", Instant.now().toString());
        JSONArray apps = new JSONArray();
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> s = Files.list(results)) {
            s.filter(Files::isDirectory).forEach(dirs::add);
        } catch (IOException e) {
            out.put("error", "cannot list " + results + ": " + e.getMessage());
        }
        dirs.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        for (Path dir : dirs) {
            Path reportFile = dir.resolve("report.json");
            if (!Files.isRegularFile(reportFile)) {
                continue;
            }
            apps.add(summarize(dir, reportFile, LIVE));
        }
        out.put("apps", apps);
        return out;
    }

    // ------------------------------------------------------------------ runs that never finished

    /**
     * A report that says "running" and carries no process id (it was written by an older ITDroid)
     * is taken for an interrupted run when it has not been rewritten for this long. A run rewrites
     * its report after every step, and no step takes hours.
     */
    static final long STALE_HOURS = 3;

    /** Tells whether the process that was writing a report is still there. */
    interface Processes {
        /** True when a process with that id is alive and, if both start times are known, it started then. */
        boolean alive(long pid, Instant startedAt);
    }

    static final Processes LIVE = (pid, startedAt) -> {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive()) {
            return false;
        }
        // the system gives the id of a dead process to a new one: the run is the one that started then
        Optional<Instant> start = handle.get().info().startInstant();
        return startedAt == null || start.isEmpty() || Math.abs(Duration.between(start.get(), startedAt).toMillis()) < 2000;
    };

    /**
     * The status to show for a run, and why when it is not the one its report states. A run that
     * was killed could not write that it stopped, so its report says "running" for ever: it is
     * shown as "interrupted" when the process that was writing it is gone.
     *
     * @return the status and a note, the note null when the status is the one of the report
     */
    static String[] effectiveStatus(JSONObject itdroid, boolean hasError, Instant reportModified, Instant now, Processes processes) {
        String status = itdroid != null && itdroid.get("status") != null ? String.valueOf(itdroid.get("status"))
                : (hasError ? "aborted" : "finished");
        if (!"running".equals(status)) {
            return new String[] {status, null};
        }
        if (itdroid.get("pid") instanceof Number) {
            Instant started = null;
            try {
                started = itdroid.get("processStartedAt") == null ? null : Instant.parse(String.valueOf(itdroid.get("processStartedAt")));
            } catch (RuntimeException ignored) {
                // an unreadable start time only means the process id is trusted alone
            }
            return processes.alive(((Number) itdroid.get("pid")).longValue(), started) ? new String[] {"running", null}
                    : new String[] {"interrupted", "the process that was running it is gone"};
        }
        if (reportModified != null && Duration.between(reportModified, now).toHours() >= STALE_HOURS) {
            return new String[] {"interrupted", "its report was last written on " + reportModified + " and never said how the run ended"};
        }
        return new String[] {status, null};
    }

    // ------------------------------------------------------------------ the files of one evaluation

    /** Files listed for one evaluation; an evaluation has a few dozen. */
    static final int MAX_LISTED_FILES = 500;

    /**
     * The files of one evaluation, each with its path from the evaluation folder and its size, so
     * the page links only to what is there. Screenshots are counted per folder, not listed.
     */
    @SuppressWarnings("unchecked")
    static JSONObject listFiles(Path results, String dirName) {
        JSONObject out = new JSONObject();
        JSONArray files = new JSONArray();
        JSONObject screenshots = new JSONObject();
        out.put("dir", dirName);
        out.put("files", files);
        out.put("screenshots", screenshots);
        Path base = results.toAbsolutePath().normalize();
        Path dir = base.resolve(dirName).normalize();
        // only a direct sub-folder of the results folder: no "..", no nested path
        if (dirName.isEmpty() || !base.equals(dir.getParent()) || !Files.isDirectory(dir)) {
            out.put("error", "not an evaluation of this results folder");
            return out;
        }
        List<Path> all = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir, 4)) {
            walk.filter(Files::isRegularFile).forEach(all::add);
        } catch (IOException | RuntimeException e) {
            out.put("error", "cannot list the folder: " + e.getMessage());
            return out;
        }
        all.sort((a, b) -> dir.relativize(a).toString().compareToIgnoreCase(dir.relativize(b).toString()));
        for (Path file : all) {
            Path parent = file.getParent();
            if (parent.getFileName().toString().equals("screenshots")) {
                String folder = dir.relativize(parent).toString().replace('\\', '/');
                Object count = screenshots.get(folder);
                screenshots.put(folder, count instanceof Long ? (Long) count + 1 : 1L);
                continue;
            }
            if (files.size() >= MAX_LISTED_FILES) {
                out.put("truncated", true);
                break;
            }
            JSONObject entry = new JSONObject();
            entry.put("path", dir.relativize(file).toString().replace('\\', '/'));
            try {
                entry.put("size", Files.size(file));
            } catch (IOException e) {
                entry.put("size", -1L);
            }
            files.add(entry);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static JSONObject summarize(Path dir, Path reportFile, Processes processes) {
        JSONObject entry = new JSONObject();
        entry.put("dir", dir.getFileName().toString());
        try {
            Instant modified = Instant.ofEpochMilli(Files.getLastModifiedTime(reportFile).toMillis());
            entry.put("reportModified", modified.toString());
            JSONObject r = (JSONObject) new JSONParser().parse(Files.readString(reportFile, StandardCharsets.UTF_8));
            entry.put("appName", r.get("appName"));
            entry.put("apkName", r.get("apkName"));
            entry.put("dfltLang", r.get("dfltLang"));
            entry.put("hardcoded", r.get("hardcoded"));
            entry.put("error", r.get("error"));
            JSONObject it = (JSONObject) r.get("itdroid");
            String[] status = effectiveStatus(it, r.get("error") != null, modified, Instant.now(), processes);
            entry.put("status", status[0]);
            if (status[1] != null) {
                entry.put("statusNote", status[1]);
            }
            if (it != null) {
                entry.put("startedAt", it.get("startedAt"));
                entry.put("finishedAt", it.get("finishedAt"));
                entry.put("durationSec", it.get("durationSec"));
                entry.put("itdroidVersion", it.get("version"));
            }
            JSONObject env = (JSONObject) r.get("environment");
            if (env != null) {
                JSONObject dev = (JSONObject) env.get("device");
                JSONObject llm = (JSONObject) env.get("llm");
                if (dev != null) {
                    entry.put("device", (dev.get("model") != null ? dev.get("model") + " " : "") + "API " + dev.get("sdk"));
                }
                if (llm != null) {
                    entry.put("model", llm.get("model"));
                }
            }
            JSONObject pre = (JSONObject) r.get("preflight");
            if (pre != null && pre.get("app") instanceof JSONObject) {
                JSONObject app = (JSONObject) pre.get("app");
                entry.put("versionName", app.get("versionName"));
            }
            long total = 0;
            JSONArray langs = new JSONArray();
            JSONObject lr = (JSONObject) r.get("langsReport");
            if (lr != null) {
                for (Object k : lr.keySet()) {
                    JSONObject v = (JSONObject) lr.get(k);
                    JSONObject l = new JSONObject();
                    l.put("code", k);
                    l.put("lang", v.get("lang"));
                    l.put("dflt", Boolean.TRUE.equals(v.get("dflt")));
                    l.put("states", v.get("amStates"));
                    l.put("ipfs", v.get("amIPFs"));
                    l.put("paired", v.get("pairedStates"));
                    l.put("error", v.get("error"));
                    // Where the exploration of the language is: under trnsResults when the app shipped
                    // the language, under noTrnsResults when ITDroid translated it. Read from the disk,
                    // so it also holds for reports that do not say it.
                    Path folder = Reanalysis.resultFolder(dir, String.valueOf(k), Boolean.TRUE.equals(v.get("dflt")));
                    if (folder != null) {
                        l.put("folder", folder.getParent().getFileName().toString());
                    }
                    JSONObject rep = v.get("replay") instanceof JSONObject ? (JSONObject) v.get("replay") : null;
                    if (rep != null) {
                        l.put("replayReproduced", rep.get("reproduced"));
                        l.put("replayActions", rep.get("actions"));
                    }
                    langs.add(l);
                    if (v.get("amIPFs") instanceof Number) {
                        total += ((Number) v.get("amIPFs")).longValue();
                    }
                }
            }
            entry.put("languages", langs);
            entry.put("totalIPFs", total);
            Path meta = dir.resolve("meta.json");
            if (Files.isRegularFile(meta)) {
                // Written by the evaluation harness (eval/run-eval.ps1): status and resource telemetry.
                try {
                    JSONObject m = (JSONObject) new JSONParser().parse(stripBom(Files.readString(meta, StandardCharsets.UTF_8)));
                    JSONObject h = new JSONObject();
                    h.put("status", m.get("status"));
                    h.put("wallSec", m.get("wallSec"));
                    h.put("note", m.get("note"));
                    entry.put("harness", h);
                } catch (Exception ignored) {
                    // an unreadable meta.json only hides the harness columns
                }
            }
        } catch (Exception e) {
            entry.put("status", "unreadable");
            entry.put("error", "report.json could not be read: " + e.getMessage());
        }
        return entry;
    }

    static String stripBom(String s) {
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    // ------------------------------------------------------------------ static files

    private static void sendJson(HttpExchange ex, JSONObject body) throws IOException {
        byte[] bytes = body.toJSONString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void serveFile(HttpExchange ex, Path base, String prefix) throws IOException {
        String raw = ex.getRequestURI().getRawPath();
        String rel = raw.startsWith(prefix) ? raw.substring(prefix.length()) : raw;
        rel = URLDecoder.decode(rel, StandardCharsets.UTF_8);
        if (rel.isEmpty() || rel.endsWith("/")) {
            rel = rel + "index.html";
        }
        Path file = base.resolve(rel).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) {
            byte[] msg = ("Not found: " + raw).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(404, msg.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(msg);
            }
            return;
        }
        ex.getResponseHeaders().set("Content-Type", contentType(file.getFileName().toString()));
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, Files.size(file));
        try (OutputStream os = ex.getResponseBody()) {
            Files.copy(file, os);
        }
    }

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"), Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "application/javascript; charset=utf-8"), Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("map", "application/json; charset=utf-8"), Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"), Map.entry("svg", "image/svg+xml"),
            Map.entry("csv", "text/csv; charset=utf-8"), Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("xml", "text/xml; charset=utf-8"), Map.entry("apk", "application/vnd.android.package-archive"),
            Map.entry("log", "text/plain; charset=utf-8"), Map.entry("ico", "image/x-icon"));

    static String contentType(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return TYPES.getOrDefault(ext, "application/octet-stream");
    }
}

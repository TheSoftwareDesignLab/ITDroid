package uniandes.tsdl.itdroid.explorer;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.json.simple.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import uniandes.tsdl.itdroid.helper.ExplorationException;

/**
 * Drives an app through adb to capture its UI states and feed i18n layout-failure analysis.
 * Changes language per-app ({@code cmd locale set-app-locales}, Android 13+): no root, works on
 * emulators and physical devices. In-place DFS; structural {@code resource-id} signatures keep
 * languages comparable. Emits the {@code result.json} consumed by {@code LayoutGraph}.
 */
public class AdbExplorer {

    private final String serial;
    private final String pkg;
    private final String apkPath;
    private final String outputFolder;
    private final String locale;
    private final int maxStates;

    /** A discovered UI state. */
    private static class StateRec {
        int id;
        String signature;
        String activity;
        String rawXML;
        String screenShot;
        List<Selector> clickables;    // clickable elements found in this state
    }

    /** A language-independent way to re-find a clickable element on replay. */
    private static class Selector {
        String resourceId;
        String clazz;
        int occurrence;               // disambiguates repeated resource-id/class
    }

    public AdbExplorer(String serial, String pkg, String apkPath, String outputFolder, String locale, int maxStates) {
        this.serial = serial;
        this.pkg = pkg;
        this.apkPath = apkPath;
        this.outputFolder = outputFolder;
        this.locale = toBcp47(locale);
        this.maxStates = maxStates;
    }

    private final List<StateRec> states = new ArrayList<>();
    private final List<int[]> transitions = new ArrayList<>();
    private final List<String> transitionTypes = new ArrayList<>();
    private final Map<String, Integer> sigToId = new LinkedHashMap<>();
    private File shotsDir;
    /** Clickables explored per state, to bound time on screens with long lists. */
    private static final int MAX_CLICKABLES_PER_STATE = 8;

    /** Runs the exploration and writes {@code result.json}. Returns the output folder path. */
    public String explore() throws Exception {
        new File(outputFolder).mkdirs();
        shotsDir = new File(outputFolder, "screenshots");
        shotsDir.mkdirs();

        install();
        cleanReset();   // pristine state for this language: no leftover data/cache/locale
        launch();
        sleep(1500);
        // Cold start after pm clear can be slow: wait until the app is actually foreground so the
        // initial state is the app, not the launcher.
        for (int i = 0; i < 6 && !inApp(); i++) {
            sleep(1000);
        }
        // If the app never came to the foreground the launch effectively failed (e.g. monkey printed
        // "No activities found to run" and still returned 0). Do NOT capture/explore the launcher or
        // whatever else is on screen: fail so the per-language failure is recorded upstream.
        if (!inApp()) {
            throw new ExplorationException("App '" + pkg + "' did not reach the foreground after launch;"
                    + " refusing to explore off-app screens (launch likely failed).");
        }

        StateRec root = captureCurrent();
        if (root != null) {
            int rootId = register(root);
            dfs(root, rootId, new ArrayList<>());
        } else {
            System.out.println("  [explorer] WARNING: could not capture the initial state");
        }

        writeResultJson(states, transitions, transitionTypes);
        System.out.println("  [explorer] done: " + states.size() + " states, "
                + transitions.size() + " transitions -> " + outputFolder + File.separator + "result.json");
        return outputFolder;
    }

    /** In-place DFS: tap each clickable, recurse into new states, return with BACK (restart+replay if BACK fails). */
    private void dfs(StateRec state, int stateId, List<String[]> path) throws Exception {
        String curXml = state.rawXML;
        int explored = 0;
        for (Selector sel : state.clickables) {
            if (states.size() >= maxStates || explored >= MAX_CLICKABLES_PER_STATE) {
                return;
            }
            int[] center = findElementCenter(curXml, sel.resourceId, sel.clazz, sel.occurrence);
            if (center == null) {
                continue;
            }
            explored++;
            tap(center[0], center[1]);
            sleep(900);

            if (!inApp()) {
                // The tap opened an external app (Play Store, Maps, a browser/chooser, ...). Go back
                // and do NOT record those screens; recover to this state.
                back();
                sleep(700);
                String backXml = stableDump();
                if (backXml == null || !inApp() || !signatureOf(backXml).equals(state.signature)) {
                    backXml = reposition(path, state.signature);
                    if (backXml == null) {
                        return;
                    }
                }
                curXml = backXml;
                continue;
            }

            StateRec child = captureCurrent();
            if (child == null) {
                curXml = reposition(path, state.signature);
                if (curXml == null) {
                    return;
                }
                continue;
            }
            if (child.signature.equals(state.signature)) {
                // Tap did not navigate to a different screen.
                curXml = child.rawXML;
                continue;
            }

            boolean isNew = !sigToId.containsKey(child.signature);
            int childId = register(child);
            transitions.add(new int[]{stateId, childId});
            transitionTypes.add("GUI_CLICK_BUTTON");

            if (isNew && states.size() < maxStates) {
                List<String[]> childPath = new ArrayList<>(path);
                childPath.add(new String[]{sel.resourceId, sel.clazz, String.valueOf(sel.occurrence)});
                dfs(child, childId, childPath); // device is currently at the child
            }

            // Return to this state.
            back();
            sleep(700);
            String backXml = stableDump();
            if (backXml == null || !signatureOf(backXml).equals(state.signature)) {
                backXml = reposition(path, state.signature);
                if (backXml == null) {
                    return;
                }
            }
            curXml = backXml;
        }
    }

    /** Captures the current on-screen state without navigating (waits for the UI to settle first). */
    private StateRec captureCurrent() throws Exception {
        String xml = stableDump();
        if (xml == null) {
            return null;
        }
        StateRec rec = new StateRec();
        rec.rawXML = xml;
        rec.activity = currentActivity();
        rec.clickables = extractClickables(xml);
        rec.signature = signatureOf(xml);
        rec.screenShot = captureScreenshot(shotsDir, sanitize(rec.signature));
        return rec;
    }

    /** Registers a state by signature, returning its id (existing or freshly assigned). */
    private int register(StateRec rec) {
        Integer existing = sigToId.get(rec.signature);
        if (existing != null) {
            return existing;
        }
        int id = states.size() + 1;
        rec.id = id;
        states.add(rec);
        sigToId.put(rec.signature, id);
        System.out.println("  [explorer] new state " + id + " @ " + rec.activity
                + " (" + rec.clickables.size() + " clickables)");
        return id;
    }

    /** Clean-restarts the app and replays {@code path}; returns the final screen XML iff it matches expectedSig. */
    private String reposition(List<String[]> path, String expectedSig) throws Exception {
        cleanReset();
        launch();
        sleep(1000);
        for (int i = 0; i < 6 && !inApp(); i++) {
            sleep(1000);
        }
        // Same failure handling as the initial launch: if the relaunch never foregrounds the app,
        // don't replay taps onto off-app screens.
        if (!inApp()) {
            throw new ExplorationException("App '" + pkg + "' did not return to the foreground on relaunch"
                    + " during reposition (launch likely failed).");
        }
        for (String[] sel : path) {
            String xml = stableDump();
            if (xml == null) {
                return null;
            }
            int[] c = findElementCenter(xml, sel[0], sel[1], Integer.parseInt(sel[2]));
            if (c == null) {
                return null;
            }
            tap(c[0], c[1]);
            sleep(900);
        }
        String xml = stableDump();
        return (xml != null && signatureOf(xml).equals(expectedSig)) ? xml : null;
    }

    // ----------------------------------------------------------------- adb actions

    private void install() throws Exception {
        System.out.println("  [explorer] installing " + apkPath);
        // Remove any prior install first: a leftover build under the same package (possibly with a
        // different signature) would make 'install -r' fail and leave us exploring the wrong app.
        runAdb(15000, "uninstall", pkg);
        String out = runAdb(60000, "install", "-r", "-t", apkPath);
        if (out != null && out.toLowerCase().contains("failure")) {
            System.out.println("  [explorer] WARNING: install reported: " + out.trim());
        }
    }

    private void setAppLocale() throws Exception {
        if (locale == null || locale.isEmpty()) {
            return;
        }
        // Per-app locales: Android 13+ (API 33). No root required; also valid on physical devices.
        String out = runAdb(8000, "shell", "cmd", "locale", "set-app-locales", pkg, "--locales", locale);
        System.out.println("  [explorer] set-app-locales " + pkg + " -> " + locale
                + (out != null && out.toLowerCase().contains("exception") ? " (WARN: " + out.trim() + ")" : ""));
    }

    /** Wipes app data/cache and re-applies the per-app locale, so each (re)start is pristine (no leftover state). */
    private void cleanReset() throws Exception {
        runAdb(15000, "shell", "pm", "clear", pkg);   // clears data + cache and stops the app
        setAppLocale();                               // pm clear also resets the per-app locale, so re-apply it
    }

    private void launch() throws Exception {
        runAdb(10000, "shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1");
    }

    private void tap(int x, int y) throws Exception {
        runAdb(8000, "shell", "input", "tap", String.valueOf(x), String.valueOf(y));
    }

    private void back() throws Exception {
        runAdb(8000, "shell", "input", "keyevent", "KEYCODE_BACK");
    }

    /** True iff the foreground activity belongs to the app under test (not Play Store, Maps, launcher...). */
    private boolean inApp() throws Exception {
        String out = runAdb(8000, "shell", "dumpsys", "activity", "activities");
        if (out == null) {
            return false;
        }
        for (String line : out.split("\n")) {
            if (line.contains("topResumedActivity") || line.contains("mResumedActivity")) {
                return line.contains(pkg + "/");
            }
        }
        return false;
    }

    private String currentActivity() throws Exception {
        String out = runAdb(8000, "shell", "dumpsys", "activity", "activities");
        if (out == null) {
            return pkg;
        }
        for (String line : out.split("\n")) {
            if (line.contains("topResumedActivity") || line.contains("mResumedActivity")) {
                int idx = line.indexOf(pkg + "/");
                if (idx >= 0) {
                    String rest = line.substring(idx + pkg.length() + 1);
                    int end = rest.indexOf(' ');
                    if (end < 0) {
                        end = rest.indexOf('}');
                    }
                    return end > 0 ? rest.substring(0, end) : rest;
                }
            }
        }
        return pkg;
    }

    private String dumpHierarchy() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            runAdb(10000, "shell", "uiautomator", "dump", "/sdcard/itdroid_dump.xml");
            String xml = runAdb(10000, "shell", "cat", "/sdcard/itdroid_dump.xml");
            if (xml != null && xml.contains("<hierarchy") && xml.contains("<node")) {
                return xml.trim();
            }
            sleep(800);
        }
        System.out.println("  [explorer] WARNING: could not dump UI hierarchy");
        return null;
    }

    /**
     * Dumps the UI until it stops changing structurally (same signature on two consecutive dumps), so a
     * screen is captured fully rendered, not mid-render. This is what makes per-language explorations
     * consistent: a cold start can briefly show a partial screen, and capturing that yields a different
     * graph that won't pair across languages. Falls back to the last dump after a few attempts. Keyed on
     * the structural signature, so text/clock animations don't prevent settling.
     */
    private String stableDump() throws Exception {
        String prevSig = null;
        String dump = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            dump = dumpHierarchy();
            if (dump == null) {
                return null;
            }
            String sig = signatureOf(dump);
            if (sig.equals(prevSig)) {
                return dump;   // two consecutive identical structures → UI settled
            }
            prevSig = sig;
            sleep(600);
        }
        return dump;
    }

    private String captureScreenshot(File shotsDir, String name) {
        Process p = null;
        try {
            File png = new File(shotsDir, name + ".png");
            ProcessBuilder pb = new ProcessBuilder("adb", "-s", serial, "exec-out", "screencap", "-p");
            pb.redirectOutput(png);
            // Discard stderr instead of leaving it as an unread pipe: a chatty screencap could otherwise
            // fill the stderr buffer and block the child forever (this runs for every settled state).
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            p = pb.start();
            if (!p.waitFor(15000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                System.out.println("  [explorer] WARNING: screenshot timed out for " + name);
                return "";
            }
            if (p.exitValue() != 0 || !png.isFile() || png.length() == 0) {
                System.out.println("  [explorer] WARNING: screenshot failed for " + name
                        + " (exit=" + p.exitValue() + ", bytes=" + (png.isFile() ? png.length() : -1) + ")");
                return "";
            }
            return png.getAbsolutePath();
        } catch (Exception e) {
            if (p != null) {
                p.destroyForcibly();   // don't leak the adb process on interrupt/IO failure
            }
            System.out.println("  [explorer] WARNING: screenshot error for " + name + ": " + e);
            return "";
        }
    }

    // ----------------------------------------------------------------- xml parsing

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** Clickable elements, in a deterministic, language-independent order. */
    private List<Selector> extractClickables(String xml) throws Exception {
        List<Selector> result = new ArrayList<>();
        Map<String, Integer> occCounter = new LinkedHashMap<>();
        Document doc = parse(xml);
        NodeList nodes = doc.getElementsByTagName("node");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            NamedNodeMap attrs = n.getAttributes();
            if (attrs == null) {
                continue;
            }
            boolean clickable = "true".equals(attrValue(attrs, "clickable"));
            String clazz = attrValue(attrs, "class");
            if (!clickable && (clazz == null || !clazz.contains("Button"))) {
                continue;
            }
            if (boundsCenter(attrValue(attrs, "bounds")) == null) {
                continue;
            }
            Selector s = new Selector();
            s.resourceId = nz(attrValue(attrs, "resource-id"));
            s.clazz = nz(clazz);
            String key = s.resourceId + "|" + s.clazz;
            int occ = occCounter.getOrDefault(key, 0);
            s.occurrence = occ;
            occCounter.put(key, occ + 1);
            result.add(s);
        }
        return result;
    }

    /** Finds the tap center of the element matching (resourceId, class, occurrence). */
    private int[] findElementCenter(String xml, String resourceId, String clazz, int occurrence) throws Exception {
        Map<String, Integer> occCounter = new LinkedHashMap<>();
        Document doc = parse(xml);
        NodeList nodes = doc.getElementsByTagName("node");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            NamedNodeMap attrs = n.getAttributes();
            if (attrs == null) {
                continue;
            }
            boolean clickable = "true".equals(attrValue(attrs, "clickable"));
            String c = attrValue(attrs, "class");
            if (!clickable && (c == null || !c.contains("Button"))) {
                continue;
            }
            // Skip elements with missing/unparseable bounds BEFORE counting occurrences, so this
            // numbering matches extractClickables (which skips them first too). Otherwise a clickable
            // node with bad bounds before the target would shift the occurrence index by one.
            int[] center = boundsCenter(attrValue(attrs, "bounds"));
            if (center == null) {
                continue;
            }
            String rid = nz(attrValue(attrs, "resource-id"));
            String cl = nz(c);
            String key = rid + "|" + cl;
            int occ = occCounter.getOrDefault(key, 0);
            occCounter.put(key, occ + 1);
            if (rid.equals(resourceId) && cl.equals(clazz) && occ == occurrence) {
                return center;
            }
        }
        return null;
    }

    /** State signature from the resource-id/class skeleton; ignores text so it matches across languages. */
    private String signatureOf(String xml) throws Exception {
        StringBuilder sb = new StringBuilder();
        Document doc = parse(xml);
        NodeList nodes = doc.getElementsByTagName("node");
        for (int i = 0; i < nodes.getLength(); i++) {
            NamedNodeMap attrs = nodes.item(i).getAttributes();
            if (attrs == null) {
                continue;
            }
            sb.append(nz(attrValue(attrs, "resource-id"))).append(':')
              .append(lastSegment(nz(attrValue(attrs, "class")))).append(';');
        }
        return sb.toString();
    }

    private static String attrValue(NamedNodeMap attrs, String name) {
        Node a = attrs.getNamedItem(name);
        return a == null ? null : a.getNodeValue();
    }

    private static int[] boundsCenter(String bounds) {
        if (bounds == null) {
            return null;
        }
        try {
            String cleaned = bounds.replace("][", ",").replace("[", "").replace("]", "");
            String[] p = cleaned.split(",");
            int x1 = Integer.parseInt(p[0].trim());
            int y1 = Integer.parseInt(p[1].trim());
            int x2 = Integer.parseInt(p[2].trim());
            int y2 = Integer.parseInt(p[3].trim());
            return new int[]{(x1 + x2) / 2, (y1 + y2) / 2};
        } catch (Exception e) {
            return null;
        }
    }

    // ----------------------------------------------------------------- output

    @SuppressWarnings("unchecked")
    private void writeResultJson(List<StateRec> states, List<int[]> transitions, List<String> transitionTypes) throws Exception {
        JSONObject root = new JSONObject();
        root.put("amountStates", (long) states.size());
        root.put("amountTransitions", (long) transitions.size());

        JSONObject statesJson = new JSONObject();
        for (StateRec s : states) {
            JSONObject sj = new JSONObject();
            sj.put("id", (long) s.id);
            sj.put("activityName", s.activity == null ? "" : s.activity);
            sj.put("rawXML", s.rawXML);
            sj.put("screenShot", s.screenShot == null ? "" : s.screenShot);
            statesJson.put(String.valueOf(s.id), sj);
        }
        root.put("states", statesJson);

        JSONObject transJson = new JSONObject();
        for (int i = 0; i < transitions.size(); i++) {
            int[] t = transitions.get(i);
            JSONObject tj = new JSONObject();
            tj.put("stState", (long) t[0]);
            tj.put("dsState", (long) t[1]);
            tj.put("tranType", transitionTypes.get(i));
            transJson.put(String.valueOf(i + 1), tj);
        }
        root.put("transitions", transJson);

        try (FileWriter fw = new FileWriter(new File(outputFolder, "result.json"))) {
            fw.write(root.toJSONString());
        }
    }

    // ----------------------------------------------------------------- helpers

    private String runAdb(long timeoutMs, String... args) throws Exception {
        return execAdb(serial, timeoutMs, args);
    }

    /** Installs an APK on the device, replacing any current install of its package (handles signature changes). */
    public static void installApk(String serial, String pkg, String apkPath) throws Exception {
        execAdb(serial, 15000, "uninstall", pkg);   // drop the (possibly translated) current build first
        String out = execAdb(serial, 180000, "install", "-t", apkPath);
        if (out != null && out.toLowerCase().contains("failure")) {
            System.out.println("  [explorer] WARNING: reinstall of original reported: " + out.trim());
        } else {
            System.out.println("  [explorer] original app reinstalled on " + serial + ": " + apkPath);
        }
    }

    /** Runs an adb command against a serial with a real timeout (output drained on a daemon thread). */
    private static String execAdb(String serial, long timeoutMs, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("adb");
        cmd.add("-s");
        cmd.add(serial);
        for (String a : args) {
            cmd.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        // Drain the output on a daemon thread so the timeout below is real: if the device hangs
        // (e.g. goes offline), readLine() would otherwise block forever, like the old RIP engine did.
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            } catch (Exception ignored) {
                // stream closed on destroy
            }
        });
        reader.setDaemon(true);
        reader.start();
        if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            // Wait for the drain thread to fully exit before reading the shared (non-thread-safe)
            // StringBuilder: destroyForcibly() closes the stream, so the reader terminates promptly.
            // Reading 'out' while the reader is still appending could throw or yield garbage.
            reader.join();
            System.out.println("  [explorer] WARNING: adb command timed out (" + String.join(" ", args) + ")");
            return out.length() > 0 ? out.toString() : null;
        }
        reader.join(2000);
        return out.toString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String lastSegment(String clazz) {
        int dot = clazz.lastIndexOf('.');
        return dot >= 0 ? clazz.substring(dot + 1) : clazz;
    }

    private static String sanitize(String s) {
        String h = Integer.toHexString(s.hashCode());
        return "state_" + h;
    }

    /** Converts Android values-folder codes (e.g. "zh-rCN") to BCP47 ("zh-CN"). */
    private static String toBcp47(String code) {
        if (code == null) {
            return "";
        }
        return code.replace("-r", "-");
    }

    /** Target device serial: {@code ANDROID_SERIAL} if set, else the first {@code adb devices} entry. */
    public static String resolveSerial() throws Exception {
        String env = System.getenv("ANDROID_SERIAL");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        ProcessBuilder pb = new ProcessBuilder("adb", "devices");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        List<String> devices = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.endsWith("\tdevice") || line.endsWith(" device")) {
                    devices.add(line.split("\\s+")[0]);
                }
            }
        }
        // Bounded wait so a wedged 'adb devices' can't hang startup forever.
        if (!p.waitFor(10000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            System.out.println("  [explorer] WARNING: 'adb devices' timed out");
        }
        if (devices.isEmpty()) {
            throw new IllegalStateException("No adb device connected. Start an emulator or plug in a device.");
        }
        String chosen = devices.get(0);
        if (devices.size() > 1) {
            // Arbitrary pick when several devices are attached and no ANDROID_SERIAL is set: surface it.
            System.out.println("  [explorer] WARNING: " + devices.size() + " adb devices found " + devices
                    + "; no ANDROID_SERIAL set, using " + chosen);
        }
        return chosen;
    }

    /** Standalone entry point for testing: serial pkg apkPath outputFolder locale [maxStates]. */
    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.out.println("Usage: AdbExplorer <serial> <pkg> <apkPath> <outputFolder> <locale> [maxStates]");
            return;
        }
        int maxStates = args.length > 5 ? Integer.parseInt(args[5]) : 8;
        AdbExplorer explorer = new AdbExplorer(args[0], args[1], args[2], args[3], args[4], maxStates);
        explorer.explore();
    }
}

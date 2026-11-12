package uniandes.tsdl.itdroid.explorer;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import uniandes.tsdl.itdroid.helper.ExplorationException;
import uniandes.tsdl.itdroid.translator.LLMTranslator;

/**
 * Drives an app through adb to capture its UI states and feed i18n layout-failure analysis.
 * Changes language per-app ({@code cmd locale set-app-locales}, Android 13+): no root, works on
 * emulators and physical devices.
 *
 * <p>Two modes. {@link #explore()} runs an in-place DFS on the source-language build and RECORDS
 * every action into {@code exploration_script.json}. {@link #replay(String)} installs a translated
 * build and reproduces that script step by step, so each translated version visits the same
 * screens in the same order and its states carry the source state ids; the comparison then pairs
 * states by id instead of hoping two independent explorations took the same path. Both modes emit
 * the {@code result.json} consumed by {@code LayoutGraph}.
 */
public class AdbExplorer {

    /** Tunables, from the {@code exploration.*} config keys. */
    public static class Options {
        public int maxStates = 5;
        public int maxClickables = 8;
        public int settleSeconds = 3;
        public boolean fillForms = false;
        /** Only needed when {@link #fillForms} is on: suggests the values typed into text fields. */
        public LLMTranslator llm;
        /** Language the suggested input values are written in (the app's source language). */
        public String language = "";
    }

    public static final String SCRIPT_FILE = "exploration_script.json";
    public static final String REPLAY_REPORT_FILE = "replay_report.json";

    private static final String TAP = "TAP";
    private static final String BACK = "BACK";
    private static final String RESTART = "RESTART";

    /**
     * Seconds to wait for the app to reach the foreground after a (re)launch. A cold start after
     * {@code pm clear} on an emulator can take well over the 7 s this used to allow.
     */
    private static final int FOREGROUND_WAIT_SECONDS = 15;
    /**
     * Runtime ("dangerous") permissions. {@code pm clear} revokes them, so on every clean start the
     * system permission dialog would cover the app. That dialog belongs to another package, so the
     * explorer saw the app as "not in the foreground" and gave up. They are granted after each reset.
     */
    private static final List<String> RUNTIME_PERMISSIONS = Arrays.asList(
            "POST_NOTIFICATIONS", "CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
            "ACCESS_BACKGROUND_LOCATION", "READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS", "READ_CALENDAR",
            "WRITE_CALENDAR", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES",
            "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "READ_PHONE_STATE", "CALL_PHONE", "READ_CALL_LOG",
            "WRITE_CALL_LOG", "SEND_SMS", "RECEIVE_SMS", "READ_SMS", "BODY_SENSORS", "ACTIVITY_RECOGNITION",
            "NEARBY_WIFI_DEVICES", "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE");

    private final String serial;
    private final String pkg;
    private final String apkPath;
    private final String outputFolder;
    private final String locale;
    private final Options opts;

    /** One node of a uiautomator dump. */
    private static class UiNode {
        String resourceId = "";
        String clazz = "";
        String text = "";
        String contentDesc = "";
        String pkg = "";
        boolean clickable;
        int[] center;
        Selector selector;
    }

    /** A language-independent way to re-find an element: resource-id + class + occurrence. */
    private static class Selector {
        String resourceId = "";
        String clazz = "";
        int occurrence;

        Selector() {
        }

        Selector(String resourceId, String clazz, int occurrence) {
            this.resourceId = nz(resourceId);
            this.clazz = nz(clazz);
            this.occurrence = occurrence;
        }

        @SuppressWarnings("unchecked")
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("resourceId", resourceId);
            o.put("clazz", clazz);
            o.put("occurrence", (long) occurrence);
            return o;
        }

        static Selector fromJson(JSONObject o) {
            return new Selector(str(o, "resourceId"), str(o, "clazz"), toInt(o.get("occurrence")));
        }

        @Override
        public String toString() {
            return (resourceId.isEmpty() ? lastSegment(clazz) : resourceId) + "#" + occurrence;
        }
    }

    /** One tap of the path that leads from the root to a state: tap {@code selector} while at {@code stateId}. */
    private static class PathStep {
        int stateId;
        Selector selector;
    }

    /** A value typed into a text field of a state (recorded so every language types the same). */
    private static class Input {
        Selector field;
        String value;
    }

    /** A discovered UI state. */
    private static class StateRec {
        int id;
        String signature;      // id + class + content-desc skeleton: tells screens apart within a language
        String structure;      // id + class skeleton only: comparable across languages
        String activity;
        String rawXML;
        String screenShot;
        List<Selector> clickables = new ArrayList<>();
        List<PathStep> path = new ArrayList<>();
        List<Input> inputs = new ArrayList<>();
    }

    /** One recorded action of the source exploration. */
    private static class Action {
        String type;
        int from;
        int to;          // TAP: 0 = the tap left the app (BACK was pressed); same as from = no screen change
        Selector selector;

        static Action tap(int from, Selector sel, int to) {
            Action a = new Action();
            a.type = TAP;
            a.from = from;
            a.to = to;
            a.selector = sel;
            return a;
        }

        static Action back(int from, int to) {
            Action a = new Action();
            a.type = BACK;
            a.from = from;
            a.to = to;
            return a;
        }

        static Action restart(int to) {
            Action a = new Action();
            a.type = RESTART;
            a.from = 0;
            a.to = to;
            return a;
        }

        @SuppressWarnings("unchecked")
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("from", (long) from);
            o.put("to", (long) to);
            if (selector != null) {
                o.put("selector", selector.toJson());
            }
            return o;
        }

        static Action fromJson(JSONObject o) {
            Action a = new Action();
            a.type = str(o, "type");
            a.from = toInt(o.get("from"));
            a.to = toInt(o.get("to"));
            Object sel = o.get("selector");
            if (sel instanceof JSONObject) {
                a.selector = Selector.fromJson((JSONObject) sel);
            }
            return a;
        }

        @Override
        public String toString() {
            return type + " " + from + "->" + to + (selector != null ? " via " + selector : "");
        }
    }

    /** The recorded source-language exploration, as replayed on the translated builds. */
    private static class Script {
        final Map<Integer, StateRec> states = new LinkedHashMap<>();
        final List<Action> actions = new ArrayList<>();
        int rootId;

        static Script load(String path) throws Exception {
            JSONObject root = (JSONObject) new JSONParser().parse(Files.readString(Paths.get(path), StandardCharsets.UTF_8));
            Script s = new Script();
            s.rootId = toInt(root.get("rootId"));
            for (Object o : (JSONArray) root.get("states")) {
                JSONObject so = (JSONObject) o;
                StateRec rec = new StateRec();
                rec.id = toInt(so.get("id"));
                rec.activity = str(so, "activity");
                rec.structure = str(so, "structure");
                rec.signature = str(so, "signature");
                Object pathJson = so.get("path");
                if (pathJson instanceof JSONArray) {
                    for (Object p : (JSONArray) pathJson) {
                        PathStep step = new PathStep();
                        step.stateId = toInt(((JSONObject) p).get("stateId"));
                        step.selector = Selector.fromJson((JSONObject) p);
                        rec.path.add(step);
                    }
                }
                Object inputs = so.get("inputs");
                if (inputs instanceof JSONArray) {
                    for (Object i : (JSONArray) inputs) {
                        Input in = new Input();
                        in.field = Selector.fromJson((JSONObject) ((JSONObject) i).get("field"));
                        in.value = str((JSONObject) i, "value");
                        rec.inputs.add(in);
                    }
                }
                s.states.put(rec.id, rec);
            }
            for (Object o : (JSONArray) root.get("actions")) {
                s.actions.add(Action.fromJson((JSONObject) o));
            }
            return s;
        }
    }

    public AdbExplorer(String serial, String pkg, String apkPath, String outputFolder, String locale, Options opts) {
        this.serial = serial;
        this.pkg = pkg;
        this.apkPath = apkPath;
        this.outputFolder = outputFolder;
        this.locale = toBcp47(locale);
        this.opts = opts == null ? new Options() : opts;
    }

    private final List<StateRec> states = new ArrayList<>();
    private final List<int[]> transitions = new ArrayList<>();
    private final List<String> transitionTypes = new ArrayList<>();
    private final Map<String, Integer> sigToId = new LinkedHashMap<>();
    private final List<Action> actions = new ArrayList<>();
    /** Replay: the signature each source state id has in THIS build (learned when first captured). */
    private final Map<Integer, String> replaySig = new HashMap<>();
    private File shotsDir;
    /** Shell command granting this app's runtime permissions; built once, reused on every reset. */
    private String grantCommand;
    /** Set when a relaunch could not bring the app back: further navigation is pointless. */
    private boolean relaunchFailed;

    // ================================================================= explore (record)

    /** Runs the DFS exploration, writes {@code result.json} and the replay script. Returns the output folder. */
    public String explore() throws Exception {
        prepareFolders();
        install();
        runAdb(8000, "logcat", "-b", "crash", "-c");   // so a crash found later belongs to THIS run
        cleanReset();   // pristine state for this language: no leftover data/cache/locale/permissions
        launch();
        sleep(1500);
        // If the app never came to the foreground the launch effectively failed (it crashed, or monkey
        // printed "No activities found to run" and still returned 0). Do NOT capture/explore the
        // launcher or whatever else is on screen: fail so the failure is recorded upstream, and say
        // why when the crash log knows.
        if (!waitForForeground()) {
            String crash = crashReason();
            throw new ExplorationException("App '" + pkg + "' did not reach the foreground after launch"
                    + (crash != null ? "; app crashed on launch: " + crash
                                     : "; refusing to explore off-app screens (launch likely failed)."));
        }
        settleAfterLaunch();

        StateRec root = captureCurrent();
        if (root != null) {
            int rootId = register(root);
            String xml = opts.fillForms ? fillForms(root) : root.rawXML;
            dfs(root, rootId, xml);
        } else {
            System.out.println("  [explorer] WARNING: could not capture the initial state");
        }

        writeResultJson(false);
        writeScript();
        System.out.println("  [explorer] done: " + states.size() + " states, "
                + transitions.size() + " transitions -> " + outputFolder + File.separator + "result.json");
        uninstallQuietly();
        return outputFolder;
    }

    /**
     * In-place DFS: tap each clickable, recurse into new states, return with BACK (restart+replay if
     * BACK fails). Every action is recorded. Returns true when the device is back at {@code state}.
     */
    private boolean dfs(StateRec state, int stateId, String startXml) throws Exception {
        String curXml = startXml;
        int explored = 0;
        for (Selector sel : state.clickables) {
            if (relaunchFailed) {
                return false;
            }
            if (states.size() >= opts.maxStates || explored >= opts.maxClickables) {
                return true;
            }
            int[] center = findElementCenter(curXml, sel);
            if (center == null) {
                continue;
            }
            explored++;
            tap(center[0], center[1]);
            sleep(900);

            if (!inApp()) {
                // The tap opened an external app (Play Store, Maps, a browser/chooser, ...). Go back
                // and do NOT record those screens; recover to this state.
                actions.add(Action.tap(stateId, sel, 0));
                back();
                sleep(700);
                String backXml = stableDump();
                if (backXml == null || !inApp() || !signatureOf(backXml).equals(state.signature)) {
                    backXml = reposition(state);
                    if (backXml == null) {
                        return false;
                    }
                }
                curXml = backXml;
                continue;
            }

            StateRec child = captureCurrent();
            if (child == null) {
                curXml = reposition(state);
                if (curXml == null) {
                    return false;
                }
                continue;
            }
            if (child.signature.equals(state.signature)) {
                // Tap did not navigate to a different screen (it may still have changed data: replayed too).
                actions.add(Action.tap(stateId, sel, stateId));
                curXml = child.rawXML;
                continue;
            }

            boolean isNew = !sigToId.containsKey(child.signature);
            int childId = register(child);
            if (isNew) {
                child.path = new ArrayList<>(state.path);
                PathStep step = new PathStep();
                step.stateId = stateId;
                step.selector = sel;
                child.path.add(step);
            }
            transitions.add(new int[]{stateId, childId});
            transitionTypes.add("GUI_CLICK_BUTTON");
            actions.add(Action.tap(stateId, sel, childId));

            boolean atChild = true;
            if (isNew) {
                String childXml = opts.fillForms ? fillForms(child) : child.rawXML;
                if (states.size() < opts.maxStates) {
                    atChild = dfs(child, childId, childXml); // device is currently at the child
                }
            }

            // Return to this state: BACK when we know where we are, else a clean restart + path.
            String backXml = null;
            if (atChild && !relaunchFailed) {
                back();
                sleep(700);
                backXml = stableDump();
                if (backXml != null && signatureOf(backXml).equals(state.signature)) {
                    actions.add(Action.back(childId, stateId));
                } else {
                    backXml = null;
                }
            }
            if (backXml == null) {
                backXml = reposition(state);
                if (backXml == null) {
                    return false;
                }
            }
            curXml = backXml;
        }
        return true;
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
        rec.structure = structureOf(xml);
        rec.screenShot = captureScreenshot(sanitize(rec.signature));
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

    /**
     * Clean-restarts the app and walks the recorded path to {@code target} (typing its inputs again,
     * since {@code pm clear} wiped them). Returns the screen XML iff the signature matches. Records a
     * RESTART action so the replay does exactly the same. Not fatal when the relaunch fails: the
     * states captured so far are valid, so navigation stops and they are kept.
     */
    private String reposition(StateRec target) throws Exception {
        if (relaunchFailed) {
            return null;
        }
        cleanReset();
        launch();
        sleep(1000);
        if (!waitForForeground()) {
            relaunchFailed = true;
            String crash = crashReason();
            System.out.println("  [explorer] WARNING: app did not return to the foreground on relaunch"
                    + (crash != null ? " (app crashed: " + crash + ")" : "")
                    + "; keeping the " + states.size() + " states captured so far");
            return null;
        }
        settleAfterLaunch();
        for (PathStep step : target.path) {
            StateRec via = stateById(step.stateId);
            if (via != null) {
                applyInputs(via);
            }
            String xml = stableDump();
            if (xml == null) {
                return null;
            }
            int[] c = findElementCenter(xml, step.selector);
            if (c == null) {
                return null;
            }
            tap(c[0], c[1]);
            sleep(900);
        }
        applyInputs(target);
        String xml = stableDump();
        if (xml != null && signatureOf(xml).equals(target.signature)) {
            actions.add(Action.restart(target.id));
            return xml;
        }
        return null;
    }

    private StateRec stateById(int id) {
        for (StateRec s : states) {
            if (s.id == id) {
                return s;
            }
        }
        return null;
    }

    // ================================================================= forms (optional)

    /**
     * Asks the LLM for a value for each text field of a just-captured state and types it, so screens
     * behind a form (login, search, "add item") become reachable. The values are recorded in the
     * script and typed verbatim in every language, which keeps the replay deterministic. Returns the
     * screen XML after typing (fields and buttons may have moved).
     */
    private String fillForms(StateRec state) throws Exception {
        if (opts.llm == null) {
            return state.rawXML;
        }
        List<UiNode> fields = editFields(state.rawXML);
        if (fields.isEmpty()) {
            return state.rawXML;
        }
        List<String> descriptions = new ArrayList<>();
        for (UiNode f : fields) {
            descriptions.add("hint/text: '" + f.text + "', description: '" + f.contentDesc
                    + "', id: '" + lastSegment(f.resourceId.replace('/', '.')) + "'");
        }
        List<String> values;
        try {
            values = opts.llm.suggestInputValues(descriptions, opts.language);
        } catch (Exception e) {
            System.out.println("  [explorer] WARNING: could not get input values from the LLM: " + e.getMessage());
            return state.rawXML;
        }
        String xml = state.rawXML;
        for (int i = 0; i < fields.size() && i < values.size(); i++) {
            String value = safeInput(values.get(i));
            if (value.isEmpty()) {
                continue;
            }
            Selector sel = fields.get(i).selector;
            int[] c = findEditCenter(xml, sel);
            if (c == null) {
                continue;
            }
            typeText(c, value);
            Input in = new Input();
            in.field = sel;
            in.value = value;
            state.inputs.add(in);
            System.out.println("  [explorer] typed '" + value + "' into " + sel);
            String fresh = stableDump();
            if (fresh != null) {
                xml = fresh;
            }
        }
        return xml;
    }

    /** Types the recorded inputs of a state (replay, or after a clean restart). */
    private void applyInputs(StateRec state) throws Exception {
        for (Input in : state.inputs) {
            String xml = stableDump();
            if (xml == null) {
                return;
            }
            int[] c = findEditCenter(xml, in.field);
            if (c != null) {
                typeText(c, in.value);
            }
        }
    }

    private void typeText(int[] center, String value) throws Exception {
        tap(center[0], center[1]);
        sleep(400);
        runAdb(10000, "shell", "input", "text", value.replace(" ", "%s"));
        sleep(300);
        hideKeyboard();
    }

    /** The soft keyboard covers the screen and shows up in dumps; one BACK closes it when it is open. */
    private void hideKeyboard() throws Exception {
        String ime = runAdb(8000, "shell", "dumpsys", "input_method");
        if (ime != null && ime.contains("mInputShown=true")) {
            back();
            sleep(500);
        }
    }

    /** Keeps the value typeable through {@code adb shell input text}: ASCII letters/digits and basic punctuation. */
    private static String safeInput(String value) {
        if (value == null) {
            return "";
        }
        String safe = value.replaceAll("[^A-Za-z0-9 .,@_\\-]", "").trim();
        return safe.length() > 40 ? safe.substring(0, 40).trim() : safe;
    }

    // ================================================================= replay

    /**
     * Installs this (translated) build and reproduces the recorded script. States are stored under
     * their SOURCE ids, so {@code result.json} pairs 1:1 with the source graph. Divergences (an element
     * missing, a different screen, the app leaving the foreground) are logged and reported, never fatal.
     */
    public String replay(String scriptPath) throws Exception {
        prepareFolders();
        Script script = Script.load(scriptPath);
        install();
        runAdb(8000, "logcat", "-b", "crash", "-c");
        cleanReset();
        launch();
        sleep(1500);
        if (!waitForForeground()) {
            String crash = crashReason();
            throw new ExplorationException("App '" + pkg + "' did not reach the foreground after launch"
                    + (crash != null ? "; app crashed on launch: " + crash
                                     : "; refusing to explore off-app screens (launch likely failed)."));
        }
        settleAfterLaunch();

        List<String> divergences = new ArrayList<>();
        int reproduced = 0;
        StateRec root = captureCurrent();
        if (root == null) {
            throw new ExplorationException("could not capture the initial state of the " + locale + " build");
        }
        StateRec recordedRoot = script.states.get(script.rootId);
        if (recordedRoot != null && !root.structure.equals(recordedRoot.structure)) {
            // A transient start screen (first-run dialog, notice) is the usual cause: one BACK
            // dismisses it. If the structure still differs, continue anyway and report it.
            back();
            sleep(700);
            StateRec again = captureCurrent();
            if (again != null && again.structure.equals(recordedRoot.structure)) {
                root = again;
            } else {
                divergences.add("root: the first screen differs from the recorded one (" + root.activity + ")");
            }
        }
        adopt(root, script.rootId, script, true);
        int current = script.rootId;

        for (Action a : script.actions) {
            if (relaunchFailed) {
                divergences.add("stopped: the app did not come back after a relaunch");
                break;
            }
            if (RESTART.equals(a.type)) {
                String xml = replayReposition(script, a.to);
                if (xml != null) {
                    current = a.to;
                    reproduced++;
                } else {
                    divergences.add("RESTART: could not reach state " + a.to);
                    current = -1;
                }
                continue;
            }
            if (current != a.from) {
                String xml = replayReposition(script, a.from);
                if (xml == null) {
                    divergences.add(a + ": could not get to state " + a.from + " first");
                    current = -1;
                    continue;
                }
                current = a.from;
            }
            if (TAP.equals(a.type)) {
                String xml = stableDump();
                if (xml == null) {
                    current = -1;
                    continue;
                }
                int[] c = findElementCenter(xml, a.selector);
                if (c == null) {
                    divergences.add("state " + a.from + ": element " + a.selector + " not found");
                    continue;   // still at 'from'
                }
                tap(c[0], c[1]);
                sleep(900);
                if (!inApp()) {
                    back();
                    sleep(700);
                    if (a.to == 0) {
                        reproduced++;
                    } else {
                        divergences.add("state " + a.from + ": tap " + a.selector + " left the app (expected state " + a.to + ")");
                    }
                    String b = stableDump();
                    current = (b != null && inApp() && matchesReplayed(b, a.from)) ? a.from : -1;
                    continue;
                }
                StateRec rec = captureCurrent();
                if (rec == null) {
                    current = -1;
                    continue;
                }
                if (a.to == 0) {
                    divergences.add("state " + a.from + ": tap " + a.selector + " stayed in the app (it left it in the source language)");
                    current = rec.signature.equals(replaySig.get(a.from)) ? a.from : -1;
                } else if (a.to == a.from) {
                    if (rec.signature.equals(replaySig.get(a.from))) {
                        reproduced++;
                    } else {
                        divergences.add("state " + a.from + ": tap " + a.selector + " changed the screen (it did not in the source language)");
                        current = -1;
                    }
                } else if (!replaySig.containsKey(a.to)) {
                    adopt(rec, a.to, script, true);
                    addTransition(a.from, a.to);
                    reproduced++;
                    current = a.to;
                } else if (rec.signature.equals(replaySig.get(a.to))) {
                    addTransition(a.from, a.to);
                    reproduced++;
                    current = a.to;
                } else {
                    divergences.add("state " + a.from + ": tap " + a.selector + " led to a different screen than recorded state " + a.to);
                    current = -1;
                }
            } else if (BACK.equals(a.type)) {
                back();
                sleep(700);
                String xml = stableDump();
                if (xml == null) {
                    current = -1;
                } else if (replaySig.containsKey(a.to)) {
                    if (signatureOf(xml).equals(replaySig.get(a.to))) {
                        reproduced++;
                        current = a.to;
                    } else {
                        divergences.add("BACK from state " + a.from + " did not return to state " + a.to);
                        current = -1;
                    }
                } else {
                    StateRec rec = captureCurrent();
                    if (rec != null) {
                        adopt(rec, a.to, script, true);
                        reproduced++;
                        current = a.to;
                    } else {
                        current = -1;
                    }
                }
            }
        }

        states.sort((x, y) -> Integer.compare(x.id, y.id));
        writeResultJson(true);
        List<Integer> missing = new ArrayList<>();
        for (Integer id : script.states.keySet()) {
            if (!replaySig.containsKey(id)) {
                missing.add(id);
            }
        }
        writeReplayReport(script.actions.size(), reproduced, divergences, script.states.size(), missing);
        System.out.println("  [explorer] replay: " + reproduced + "/" + script.actions.size() + " actions reproduced, "
                + divergences.size() + " divergences, " + states.size() + "/" + script.states.size() + " states captured"
                + (missing.isEmpty() ? "" : " (missing " + missing + ")"));
        for (String d : divergences) {
            System.out.println("  [explorer] divergence: " + d);
        }
        System.out.println("  [explorer] done: " + states.size() + " states, "
                + transitions.size() + " transitions -> " + outputFolder + File.separator + "result.json");
        uninstallQuietly();
        return outputFolder;
    }

    /** Stores a captured screen as source state {@code id} and (optionally) types that state's recorded inputs. */
    private void adopt(StateRec rec, int id, Script script, boolean typeInputs) throws Exception {
        rec.id = id;
        states.add(rec);
        replaySig.put(id, rec.signature);
        System.out.println("  [explorer] state " + id + " captured @ " + rec.activity);
        StateRec recorded = script.states.get(id);
        if (typeInputs && recorded != null && !recorded.inputs.isEmpty()) {
            applyInputs(recorded);
        }
    }

    /** Same bookkeeping as the DFS (one transition per tap that changed screen, repeats included), so counts match. */
    private void addTransition(int from, int to) {
        transitions.add(new int[]{from, to});
        transitionTypes.add("GUI_CLICK_BUTTON");
    }

    private boolean matchesReplayed(String xml, int id) throws Exception {
        String expected = replaySig.get(id);
        return expected != null && signatureOf(xml).equals(expected);
    }

    /** Replay counterpart of {@link #reposition}: clean restart + recorded path (with inputs) to a source state. */
    private String replayReposition(Script script, int targetId) throws Exception {
        if (relaunchFailed) {
            return null;
        }
        StateRec target = script.states.get(targetId);
        if (target == null) {
            return null;
        }
        cleanReset();
        launch();
        sleep(1000);
        if (!waitForForeground()) {
            relaunchFailed = true;
            String crash = crashReason();
            System.out.println("  [explorer] WARNING: app did not return to the foreground on relaunch"
                    + (crash != null ? " (app crashed: " + crash + ")" : "")
                    + "; keeping the " + states.size() + " states captured so far");
            return null;
        }
        settleAfterLaunch();
        for (PathStep step : target.path) {
            StateRec via = script.states.get(step.stateId);
            if (via != null) {
                applyInputs(via);
            }
            String xml = stableDump();
            if (xml == null) {
                return null;
            }
            int[] c = findElementCenter(xml, step.selector);
            if (c == null) {
                return null;
            }
            tap(c[0], c[1]);
            sleep(900);
        }
        applyInputs(target);
        String xml = stableDump();
        if (xml == null) {
            return null;
        }
        if (replaySig.containsKey(targetId)) {
            return signatureOf(xml).equals(replaySig.get(targetId)) ? xml : null;
        }
        StateRec rec = captureCurrent();
        if (rec == null) {
            return null;
        }
        adopt(rec, targetId, script, false);   // inputs were just typed along the path
        return rec.rawXML;
    }

    // ================================================================= adb actions

    private void prepareFolders() {
        new File(outputFolder).mkdirs();
        shotsDir = new File(outputFolder, "screenshots");
        shotsDir.mkdirs();
    }

    private void install() throws Exception {
        System.out.println("  [explorer] installing " + apkPath);
        // Remove any prior install first: a leftover build under the same package (possibly with a
        // different signature) would make 'install -r' fail and leave us exploring the wrong app.
        runAdb(15000, "uninstall", pkg);
        String out = runAdb(120000, "install", "-r", "-t", "-g", apkPath);
        // Anything but "Success" means the app is NOT on the device (INSTALL_FAILED_*, a path adb could
        // not read, a dead package service...). Exploring would then capture the launcher: fail here.
        if (out == null || !out.contains("Success")) {
            throw new ExplorationException("could not install " + apkPath + ": "
                    + (out == null ? "no adb output" : out.trim().replace('\n', ' ')));
        }
    }

    /** Stops and removes the app: nothing of this build stays on the device while the next one is prepared. */
    private void uninstallQuietly() {
        try {
            uninstall(serial, pkg);
        } catch (Exception e) {
            System.out.println("  [explorer] WARNING: could not uninstall " + pkg + ": " + e.getMessage());
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
        grantRuntimePermissions();                    // ...and revokes the runtime permissions
    }

    /** Grants the runtime permissions the app requests, so no system permission dialog covers it. */
    private void grantRuntimePermissions() throws Exception {
        if (grantCommand == null) {
            String dump = runAdb(15000, "shell", "dumpsys", "package", pkg);
            StringBuilder cmd = new StringBuilder();
            int count = 0;
            for (String perm : RUNTIME_PERMISSIONS) {
                // Android 13 asks for POST_NOTIFICATIONS even on behalf of apps that do not declare it.
                boolean requested = perm.equals("POST_NOTIFICATIONS")
                        || (dump != null && dump.contains("android.permission." + perm));
                if (requested) {
                    cmd.append("pm grant ").append(pkg).append(" android.permission.").append(perm)
                            .append(" 2>/dev/null; ");
                    count++;
                }
            }
            grantCommand = cmd.toString();
            System.out.println("  [explorer] runtime permissions granted after each reset: " + count);
        }
        if (!grantCommand.isEmpty()) {
            runAdb(20000, "shell", grantCommand);
        }
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
        String top = topActivityLine(serial);
        return top != null && top.contains(pkg + "/");
    }

    /** The dumpsys line naming the resumed (foreground) activity, or null when it cannot be read. */
    private static String topActivityLine(String serial) throws Exception {
        String out = execAdb(serial, 8000, "shell", "dumpsys", "activity", "activities");
        if (out == null) {
            return null;
        }
        for (String line : out.split("\n")) {
            if (line.contains("topResumedActivity") || line.contains("mResumedActivity")) {
                return line;
            }
        }
        return null;
    }

    /**
     * Waits until the app is in the foreground. A system permission dialog that slipped past the
     * pre-grant (a permission outside the known list) is accepted instead of being mistaken for a
     * failed launch.
     */
    private boolean waitForForeground() throws Exception {
        for (int i = 0; i < FOREGROUND_WAIT_SECONDS; i++) {
            String top = topActivityLine(serial);
            if (top != null && top.contains(pkg + "/")) {
                return true;
            }
            if (top != null && top.contains("GrantPermissionsActivity")) {
                acceptPermissionDialog(serial);
            }
            sleep(1000);
        }
        return inApp();
    }

    /**
     * Startup settle window: splash screens, loaders and first-run animations make the first dump
     * differ from launch to launch, which was the main source of non-reproducible graphs. Wait a
     * fixed time, then until the structure holds for 1.5 s.
     */
    private void settleAfterLaunch() throws Exception {
        if (opts.settleSeconds > 0) {
            sleep(opts.settleSeconds * 1000L);
        }
        stableDump(1500);
    }

    /** Taps the "allow" button of the system permission dialog currently on screen, if there is one. */
    private static void acceptPermissionDialog(String serial) throws Exception {
        String xml = dumpHierarchy(serial);
        if (xml == null) {
            return;
        }
        Matcher m = Pattern.compile(
                "resource-id=\"[^\"]*:id/permission_allow[a-z_]*button\"[^>]*?bounds=\"(\\[\\d+,\\d+\\]\\[\\d+,\\d+\\])\"")
                .matcher(xml);
        if (m.find()) {
            int[] c = boundsCenter(m.group(1));
            if (c != null) {
                System.out.println("  [explorer] accepting a system permission dialog");
                execAdb(serial, 8000, "shell", "input", "tap", String.valueOf(c[0]), String.valueOf(c[1]));
                sleep(700);
            }
        }
    }

    private String crashReason() throws Exception {
        return crashReason(serial, pkg);
    }

    /** The exception that killed the app, read from the crash log; null when it did not crash. */
    private static String crashReason(String serial, String pkg) throws Exception {
        String out = execAdb(serial, 10000, "logcat", "-b", "crash", "-d", "-t", "300");
        if (out == null) {
            return null;
        }
        boolean ours = false;
        for (String line : out.split("\n")) {
            int at = line.indexOf("AndroidRuntime: ");
            if (at < 0) {
                continue;
            }
            String msg = line.substring(at + "AndroidRuntime: ".length()).trim();
            if (msg.startsWith("Process: ")) {
                ours = msg.contains(pkg);
            } else if (ours && !msg.isEmpty() && !msg.startsWith("FATAL") && !msg.startsWith("at ")) {
                return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
            }
        }
        return null;
    }

    private String currentActivity() throws Exception {
        String line = topActivityLine(serial);
        if (line == null) {
            return pkg;
        }
        int idx = line.indexOf(pkg + "/");
        if (idx >= 0) {
            String rest = line.substring(idx + pkg.length() + 1);
            int end = rest.indexOf(' ');
            if (end < 0) {
                end = rest.indexOf('}');
            }
            return end > 0 ? rest.substring(0, end) : rest;
        }
        return pkg;
    }

    /**
     * Dumps the UI hierarchy through uiautomator. The remote file is removed before every attempt and
     * the tool's own answer is checked, so a failed dump can never be mistaken for the previous screen
     * (batch 4, 2026-10-20: a clock showing seconds never goes idle, the dump timed out and the explorer
     * kept reading the stale file left by the previous app). uiautomator gives up on a UI that stays
     * busy after 10 s, so the command gets more than that.
     */
    private static String dumpHierarchy(String serial) throws Exception {
        boolean neverIdle = false;
        for (int attempt = 0; attempt < 3; attempt++) {
            execAdb(serial, 8000, "shell", "rm", "-f", "/sdcard/itdroid_dump.xml");
            String answer = execAdb(serial, 25000, "shell", "uiautomator", "dump", "/sdcard/itdroid_dump.xml");
            if (answer != null && answer.contains("idle state")) {
                neverIdle = true;
            }
            if (answer == null || !answer.contains("dumped to")) {
                sleep(800);
                continue;
            }
            String xml = execAdb(serial, 10000, "shell", "cat", "/sdcard/itdroid_dump.xml");
            if (xml != null && xml.contains("<hierarchy") && xml.contains("<node")) {
                return xml.trim();
            }
            sleep(800);
        }
        System.out.println("  [explorer] WARNING: could not dump UI hierarchy"
                + (neverIdle ? " (the UI never becomes idle: uiautomator cannot capture a screen that keeps changing)" : ""));
        return null;
    }

    private String stableDump() throws Exception {
        return stableDump(600);
    }

    /**
     * Dumps the UI until it stops changing structurally (same signature on two consecutive dumps
     * {@code intervalMs} apart), so a screen is captured fully rendered, not mid-render. Falls back to
     * the last dump after a few attempts. Keyed on the signature, so clock/text animations don't
     * prevent settling.
     */
    private String stableDump(long intervalMs) throws Exception {
        String prevSig = null;
        String dump = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            dump = dumpHierarchy(serial);
            if (dump == null) {
                return null;
            }
            String sig = signatureOf(dump);
            if (sig.equals(prevSig)) {
                return dump;   // two consecutive identical structures: UI settled
            }
            prevSig = sig;
            sleep(intervalMs);
        }
        return dump;
    }

    private String captureScreenshot(String name) {
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

    // ================================================================= xml parsing

    private static List<UiNode> parseNodes(String xml) throws Exception {
        List<UiNode> out = new ArrayList<>();
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        NodeList nodes = doc.getElementsByTagName("node");
        for (int i = 0; i < nodes.getLength(); i++) {
            NamedNodeMap attrs = nodes.item(i).getAttributes();
            if (attrs == null) {
                continue;
            }
            UiNode n = new UiNode();
            n.resourceId = nz(attrValue(attrs, "resource-id"));
            n.clazz = nz(attrValue(attrs, "class"));
            n.text = nz(attrValue(attrs, "text"));
            n.contentDesc = nz(attrValue(attrs, "content-desc"));
            n.pkg = nz(attrValue(attrs, "package"));
            n.clickable = "true".equals(attrValue(attrs, "clickable"));
            n.center = boundsCenter(attrValue(attrs, "bounds"));
            out.add(n);
        }
        return out;
    }

    private static boolean isEditField(UiNode n) {
        return n.clazz.contains("EditText") || n.clazz.contains("AutoComplete");
    }

    /** Tappable elements. Text fields are excluded: tapping one only opens the keyboard (forms are typed, not clicked). */
    private static boolean isClickableCandidate(UiNode n) {
        return (n.clickable || n.clazz.contains("Button")) && n.center != null && !isEditField(n);
    }

    private static boolean isImeNode(UiNode n) {
        return n.pkg.contains("inputmethod");
    }

    /** Clickable elements, in a deterministic, language-independent order. */
    private List<Selector> extractClickables(String xml) throws Exception {
        List<Selector> result = new ArrayList<>();
        Map<String, Integer> occCounter = new LinkedHashMap<>();
        for (UiNode n : parseNodes(xml)) {
            if (!isClickableCandidate(n) || isImeNode(n)) {
                continue;
            }
            String key = n.resourceId + "|" + n.clazz;
            int occ = occCounter.getOrDefault(key, 0);
            occCounter.put(key, occ + 1);
            result.add(new Selector(n.resourceId, n.clazz, occ));
        }
        return result;
    }

    /** Finds the tap center of the clickable matching the selector (same filtering and numbering as extractClickables). */
    private int[] findElementCenter(String xml, Selector sel) throws Exception {
        Map<String, Integer> occCounter = new LinkedHashMap<>();
        for (UiNode n : parseNodes(xml)) {
            if (!isClickableCandidate(n) || isImeNode(n)) {
                continue;
            }
            String key = n.resourceId + "|" + n.clazz;
            int occ = occCounter.getOrDefault(key, 0);
            occCounter.put(key, occ + 1);
            if (n.resourceId.equals(sel.resourceId) && n.clazz.equals(sel.clazz) && occ == sel.occurrence) {
                return n.center;
            }
        }
        return null;
    }

    /** Text fields of a screen, each with its selector (numbered among text fields). */
    private List<UiNode> editFields(String xml) throws Exception {
        List<UiNode> result = new ArrayList<>();
        Map<String, Integer> occCounter = new LinkedHashMap<>();
        for (UiNode n : parseNodes(xml)) {
            if (!isEditField(n) || n.center == null || isImeNode(n)) {
                continue;
            }
            String key = n.resourceId + "|" + n.clazz;
            int occ = occCounter.getOrDefault(key, 0);
            occCounter.put(key, occ + 1);
            n.selector = new Selector(n.resourceId, n.clazz, occ);
            result.add(n);
        }
        return result;
    }

    private int[] findEditCenter(String xml, Selector sel) throws Exception {
        for (UiNode n : editFields(xml)) {
            if (n.selector.resourceId.equals(sel.resourceId) && n.selector.clazz.equals(sel.clazz)
                    && n.selector.occurrence == sel.occurrence) {
                return n.center;
            }
        }
        return null;
    }

    /**
     * State signature: resource-id + class + content-desc (digits folded) + whether a non-editable
     * node shows text. Ignores the text itself (changes with data and language) and the keyboard.
     * Richer than id+class alone so Compose screens without resource-ids still tell apart.
     */
    private String signatureOf(String xml) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (UiNode n : parseNodes(xml)) {
            if (isImeNode(n)) {
                continue;
            }
            sb.append(n.resourceId).append(':').append(lastSegment(n.clazz));
            if (!n.contentDesc.isEmpty()) {
                sb.append('[').append(n.contentDesc.replaceAll("\\d+", "#")).append(']');
            }
            if (!n.text.isEmpty() && !isEditField(n)) {
                sb.append('t');
            }
            sb.append(';');
        }
        return sb.toString();
    }

    /** Language-independent skeleton (resource-id + class), used to compare screens across builds. */
    private String structureOf(String xml) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (UiNode n : parseNodes(xml)) {
            if (isImeNode(n)) {
                continue;
            }
            sb.append(n.resourceId).append(':').append(lastSegment(n.clazz)).append(';');
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

    // ================================================================= output

    @SuppressWarnings("unchecked")
    private void writeResultJson(boolean withSourceIds) throws Exception {
        JSONObject root = new JSONObject();
        root.put("amountStates", (long) states.size());
        root.put("amountTransitions", (long) transitions.size());

        JSONObject statesJson = new JSONObject();
        for (StateRec s : states) {
            JSONObject sj = new JSONObject();
            sj.put("id", (long) s.id);
            if (withSourceIds) {
                sj.put("sourceStateId", (long) s.id);
            }
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

    /** The replay script: every state (with the path that reaches it and its typed inputs) and every action, in order. */
    @SuppressWarnings("unchecked")
    private void writeScript() throws Exception {
        JSONObject root = new JSONObject();
        root.put("package", pkg);
        root.put("locale", locale);
        root.put("rootId", states.isEmpty() ? 0L : (long) states.get(0).id);
        JSONArray st = new JSONArray();
        for (StateRec s : states) {
            JSONObject o = new JSONObject();
            o.put("id", (long) s.id);
            o.put("activity", nz(s.activity));
            o.put("structure", s.structure);
            o.put("signature", s.signature);
            JSONArray path = new JSONArray();
            for (PathStep p : s.path) {
                JSONObject po = p.selector.toJson();
                po.put("stateId", (long) p.stateId);
                path.add(po);
            }
            o.put("path", path);
            JSONArray inputs = new JSONArray();
            for (Input in : s.inputs) {
                JSONObject io = new JSONObject();
                io.put("field", in.field.toJson());
                io.put("value", in.value);
                inputs.add(io);
            }
            o.put("inputs", inputs);
            st.add(o);
        }
        root.put("states", st);
        JSONArray ac = new JSONArray();
        for (Action a : actions) {
            ac.add(a.toJson());
        }
        root.put("actions", ac);
        Files.writeString(Paths.get(outputFolder, SCRIPT_FILE), root.toJSONString(), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private void writeReplayReport(int total, int reproduced, List<String> divergences, int recordedStates,
            List<Integer> missing) throws Exception {
        JSONObject o = new JSONObject();
        o.put("actions", (long) total);
        o.put("reproduced", (long) reproduced);
        o.put("statesRecorded", (long) recordedStates);
        o.put("statesCaptured", (long) states.size());
        JSONArray miss = new JSONArray();
        for (Integer id : missing) {
            miss.add((long) id);
        }
        o.put("missingStates", miss);
        JSONArray div = new JSONArray();
        div.addAll(divergences);
        o.put("divergences", div);
        Files.writeString(Paths.get(outputFolder, REPLAY_REPORT_FILE), o.toJSONString(), StandardCharsets.UTF_8);
    }

    // ================================================================= static device helpers

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

    /** Force-stops the app (kills every process it created) and uninstalls it, data included. */
    public static void uninstall(String serial, String pkg) throws Exception {
        execAdb(serial, 8000, "shell", "am", "force-stop", pkg);
        String out = execAdb(serial, 15000, "uninstall", pkg);
        System.out.println("  [explorer] " + pkg + " removed from the device"
                + (out != null && out.contains("Success") ? "" : " (" + nz(out).trim() + ")"));
    }

    /**
     * Preflight launch test on the ORIGINAL apk: installs it with its permissions granted, launches
     * it and checks it stays in the foreground. Returns null when it does, else the reason (crash,
     * never foregrounded, or it handed the screen to another package such as a browser). The app is
     * removed afterwards.
     */
    public static String checkLaunch(String serial, String pkg, String apkPath) throws Exception {
        execAdb(serial, 15000, "uninstall", pkg);
        String out = execAdb(serial, 180000, "install", "-r", "-t", "-g", apkPath);
        if (out == null || !out.contains("Success")) {
            return "the APK does not install on " + serial + ": " + (out == null ? "no adb output" : out.trim().replace('\n', ' '));
        }
        execAdb(serial, 8000, "shell", "pm", "grant", pkg, "android.permission.POST_NOTIFICATIONS");
        execAdb(serial, 8000, "logcat", "-b", "crash", "-c");
        execAdb(serial, 10000, "shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1");
        String top = null;
        boolean seen = false;
        for (int i = 0; i < FOREGROUND_WAIT_SECONDS; i++) {
            sleep(1000);
            top = topActivityLine(serial);
            if (top != null && top.contains(pkg + "/")) {
                seen = true;
                break;
            }
            if (top != null && top.contains("GrantPermissionsActivity")) {
                acceptPermissionDialog(serial);
            }
        }
        boolean still = false;
        if (seen) {
            sleep(3000);   // a crash or a hand-over to a browser shows up within a couple of seconds
            String later = topActivityLine(serial);
            still = later != null && later.contains(pkg + "/");
            if (!still) {
                top = later;
            }
        }
        String crash = crashReason(serial, pkg);
        String reason = null;
        if (crash != null) {
            reason = "the original app crashes on launch: " + crash;
        } else if (!seen) {
            reason = "the original app never reached the foreground (" + foregroundOwner(top) + ")";
        } else if (!still) {
            reason = "the original app handed the screen to another package right after launch (" + foregroundOwner(top)
                    + "); Trusted Web Activities and browser-backed apps are out of scope";
        }
        execAdb(serial, 8000, "shell", "am", "force-stop", pkg);
        execAdb(serial, 15000, "uninstall", pkg);
        System.out.println("PREFLIGHT: launch test of the original app: " + (reason == null ? "OK" : reason));
        return reason;
    }

    private static String foregroundOwner(String topLine) {
        if (topLine == null) {
            return "no foreground activity";
        }
        Matcher m = Pattern.compile("([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)").matcher(topLine);
        return m.find() ? m.group(1) + "/" + m.group(2) : topLine.trim();
    }

    /** API level of the device, or -1 when it cannot be read. */
    /** A system property of the device ({@code getprop}); empty when unavailable. */
    public static String deviceProp(String serial, String name) throws Exception {
        return nz(execAdb(serial, 8000, "shell", "getprop", name)).trim();
    }

    public static int deviceSdk(String serial) throws Exception {
        String s = execAdb(serial, 8000, "shell", "getprop", "ro.build.version.sdk");
        try {
            return Integer.parseInt(nz(s).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** ABIs the device supports, most preferred first. */
    public static List<String> deviceAbis(String serial) throws Exception {
        String s = execAdb(serial, 8000, "shell", "getprop", "ro.product.cpu.abilist");
        if (s == null || s.trim().isEmpty()) {
            s = execAdb(serial, 8000, "shell", "getprop", "ro.product.cpu.abi");
        }
        List<String> abis = new ArrayList<>();
        for (String a : nz(s).split(",")) {
            if (!a.trim().isEmpty()) {
                abis.add(a.trim());
            }
        }
        return abis;
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

    private static String str(JSONObject o, String key) {
        Object v = o.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static int toInt(Object o) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String lastSegment(String clazz) {
        int dot = clazz.lastIndexOf('.');
        return dot >= 0 ? clazz.substring(dot + 1) : clazz;
    }

    private static String sanitize(String s) {
        return "state_" + Integer.toHexString(s.hashCode());
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

    /** Standalone entry point for testing: serial pkg apkPath outputFolder locale [maxStates] [scriptToReplay]. */
    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.out.println("Usage: AdbExplorer <serial> <pkg> <apkPath> <outputFolder> <locale> [maxStates] [exploration_script.json]");
            return;
        }
        Options o = new Options();
        o.maxStates = args.length > 5 ? Integer.parseInt(args[5]) : 5;
        AdbExplorer explorer = new AdbExplorer(args[0], args[1], args[2], args[3], args[4], o);
        if (args.length > 6) {
            explorer.replay(args[6]);
        } else {
            explorer.explore();
        }
    }
}

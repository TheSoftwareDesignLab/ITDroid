package uniandes.tsdl.itdroid.explorer;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
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

import org.apache.commons.io.FileUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import uniandes.tsdl.itdroid.helper.Helper;
import uniandes.tsdl.itdroid.helper.PortablePaths;
import uniandes.tsdl.itdroid.translator.LLMTranslator;

/**
 * Drives an app through adb to capture its UI states, setting the language per app (Android 13+, no root).
 * {@link #explore()} records a DFS of the source build into a script and {@link #replay(String)}
 * reproduces it on each translated build, so states pair by id. Both write the {@code result.json} read by {@code LayoutGraph}.
 */
public class AdbExplorer {

    /** Tunables, from the {@code exploration.*} config keys. */
    public static class Options {
        /** Screens captured before stopping, 0 for no limit (then set {@link #maxMinutes}). */
        public int maxStates = 5;
        /** Elements tapped per screen, 0 for all. */
        public int maxClickables = 8;
        /** Time limit of the source exploration, 0 for none. */
        public int maxMinutes = 0;
        /** adb commands run after every clean start, to leave content on the device. */
        public List<String[]> setup = new ArrayList<>();
        public int settleSeconds = 3;
        public boolean fillForms = false;
        /** Suggests the values typed into text fields when {@link #fillForms} is on. */
        public LLMTranslator llm;
        /** Source language of the suggested input values. */
        public String language = "";
    }

    public static final String SCRIPT_FILE = "exploration_script.json";
    public static final String REPLAY_REPORT_FILE = "replay_report.json";

    private static final String TAP = "TAP";
    private static final String BACK = "BACK";
    private static final String RESTART = "RESTART";

    /** Foreground wait after a launch. A cold start after {@code pm clear} on an emulator can exceed 7 s. */
    private static final int FOREGROUND_WAIT_SECONDS = 15;
    /**
     * Longest wait for the focused window to change after a BACK or a tap towards a known screen.
     * Changes inside one window need no wait, since uiautomator dumps only after a quiet second.
     */
    private static final long ACTION_WAIT_MS = 400;
    /** Pause before reading the screen after a tap whose outcome is not known. */
    private static final long TAP_SETTLE_MS = 900;
    /** Pause between the two dumps that must agree for a screen to count as settled. */
    private static final long STABLE_INTERVAL_MS = 600;
    private static final String DUMP_FILE = "/sdcard/itdroid_dump.xml";
    /** Most elements a passing message adds or changes (box, text, icon, button). */
    private static final int MESSAGE_NODES = 5;
    /** Interval and number of looks at a screen that may only be showing a message. */
    private static final long MESSAGE_POLL_MS = 2500;
    private static final int MESSAGE_POLLS = 2;
    /** Why an exploration ended (screen or time limit, per-screen limit, nothing left, or no relaunch). */
    public static final String STOP_STATES = "maxStates";
    public static final String STOP_MINUTES = "maxMinutes";
    public static final String STOP_CLICKABLES = "maxClickables";
    public static final String STOP_EXHAUSTED = "exhausted";
    public static final String STOP_RELAUNCH = "relaunchFailed";
    /**
     * Runtime permissions, granted after each reset because {@code pm clear} revokes them and the
     * system dialog would otherwise cover the app and look like another package in front.
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

    /** Finds an element again in any language by resource id, class and occurrence. */
    private static class Selector {
        String resourceId = "";
        String clazz = "";
        int occurrence;

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

    /** One tap of the path from the root to a state, made on {@code selector} while at {@code stateId}. */
    private static class PathStep {
        int stateId;
        Selector selector;
    }

    /** A value typed into a text field, recorded so every language types the same. */
    private static class Input {
        Selector field;
        String value;
    }

    /** A discovered UI state. */
    private static class StateRec {
        int id;
        String signature;      // id, class and content-desc, tells screens apart within a language
        String structure;      // id and class only, comparable across languages
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
        int to;          // for a TAP, 0 means it left the app and from means no screen change
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

    /** The recorded source exploration, replayed on the translated builds. */
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
    /** During a replay, the signature each source state id has in this build. */
    private final Map<Integer, String> replaySig = new HashMap<>();
    private File shotsDir;
    /** Shell command granting the runtime permissions, built once and reused on every reset. */
    private String grantCommand;
    /** Set when a relaunch could not bring the app back, which ends navigation. */
    private boolean relaunchFailed;
    /** {@code System.nanoTime()} at which the exploration stops, 0 for never. */
    private long deadlineNanos;
    /** The limit that left elements untapped, if any. */
    private String limitReached;
    /** Set when some screen had more elements than the per-screen limit. */
    private boolean clickablesLeft;
    private boolean setupShown;
    private int tapCount;
    private int backCount;
    private int restartCount;
    private int dumpCount;
    /** Clean starts repeated because the app dropped its language while starting. */
    private int languageRestarts;
    /** Set when the app dropped the language on the repeated start too. */
    private boolean languageRefused;
    /** Settled dump of the first screen after the last clean start, null when it did not settle. */
    private String startDump;

    // ================================================================= explore (record)

    /** Runs the DFS, writes {@code result.json} and the replay script, and returns the output folder. */
    public String explore() throws Exception {
        long started = System.nanoTime();
        deadlineNanos = opts.maxMinutes > 0 ? started + opts.maxMinutes * 60_000_000_000L : 0;
        prepareFolders();
        install();
        runAdb(8000, "logcat", "-b", "crash", "-c");   // so a crash found later belongs to this run
        // Monkey can return 0 without launching anything, so an app not in front means a failed
        // launch. Fail instead of exploring the launcher.
        if (!startClean(1500, null)) {
            String crash = crashReason();
            throw new ExplorationException("App '" + pkg + "' did not reach the foreground after launch"
                    + (crash != null ? "; app crashed on launch: " + crash
                                     : "; refusing to explore off-app screens (launch likely failed)."));
        }

        StateRec root = captureCurrent();
        if (root != null) {
            int rootId = register(root);
            String xml = opts.fillForms ? fillForms(root) : root.rawXML;
            dfs(root, rootId, xml);
        } else {
            System.out.println("  [explorer] WARNING: could not capture the initial state");
        }

        // Nothing left to tap means the app showed all it has to a visitor without data or account
        String stop = relaunchFailed ? STOP_RELAUNCH
                : limitReached != null ? limitReached
                : clickablesLeft ? STOP_CLICKABLES : STOP_EXHAUSTED;
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        writeResultJson(false);
        writeScript(stop, seconds);
        System.out.println("  [explorer] done: " + states.size() + " states, "
                + transitions.size() + " transitions -> " + outputFolder + File.separator + "result.json");
        System.out.println("  [explorer] stopped by " + stop + " after " + seconds + " s (" + tapCount + " taps, "
                + backCount + " BACK, " + restartCount + " restarts, " + dumpCount + " dumps)");
        uninstallQuietly();
        return outputFolder;
    }

    /** The limit that ends the exploration now, or null while there is budget left. */
    private String limitNow() {
        if (opts.maxStates > 0 && states.size() >= opts.maxStates) {
            return STOP_STATES;
        }
        if (deadlineNanos != 0 && System.nanoTime() >= deadlineNanos) {
            return STOP_MINUTES;
        }
        return null;
    }

    /**
     * Taps each clickable, recurses into new states and comes back with BACK or a restart, recording
     * every action. Returns true when the device is back at {@code state}.
     */
    private boolean dfs(StateRec state, int stateId, String startXml) throws Exception {
        String curXml = startXml;
        int explored = 0;
        for (Selector sel : state.clickables) {
            if (relaunchFailed) {
                return false;
            }
            String limit = limitNow();
            if (limit != null) {
                limitReached = limit;
                return true;
            }
            if (opts.maxClickables > 0 && explored >= opts.maxClickables) {
                clickablesLeft = true;
                return true;
            }
            int[] center = findElementCenter(curXml, sel);
            if (center == null) {
                continue;
            }
            explored++;
            // The pause decides whether a short status line is caught, so it stays the one earlier
            // evaluations used and the same screens are found.
            tap(center[0], center[1]);
            sleep(TAP_SETTLE_MS);

            // Another app can take the screen at once or seconds later, so check before and after the dump
            boolean outside = !inApp();
            String childXml = outside ? null : stableDump();
            // A new screen that differs by a line may be a message about to go away
            if (childXml != null && !sigToId.containsKey(signatureOf(childXml))) {
                childXml = outlastMessage(childXml, state.signature);
            }
            if (!outside && childXml != null && (!inApp() || !showsApp(childXml))) {
                outside = true;   // another app came to the front, or a foreign dialog covers the app
            }
            if (outside) {
                // The tap opened another app, go back without recording its screens
                actions.add(Action.tap(stateId, sel, 0));
                backAndWait();
                String backXml = stableDump(STABLE_INTERVAL_MS, state.signature);
                if (backXml == null || !inApp() || !signatureOf(backXml).equals(state.signature)) {
                    backXml = reposition(state);
                    if (backXml == null) {
                        return false;
                    }
                }
                curXml = backXml;
                continue;
            }

            StateRec child = childXml == null ? null : capture(childXml);
            if (child == null) {
                curXml = reposition(state);
                if (curXml == null) {
                    return false;
                }
                continue;
            }
            if (child.signature.equals(state.signature)) {
                // No navigation, but it may have changed data, so it is replayed too
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
                String formXml = opts.fillForms ? fillForms(child) : child.rawXML;
                if (limitNow() == null) {
                    atChild = dfs(child, childId, formXml);
                }
            }
            // Budget spent, so skip walking back up (it was a third of a short exploration's time)
            String spent = limitNow();
            if (spent != null) {
                if (limitReached == null) {
                    limitReached = spent;
                }
                return false;
            }

            // Return to this state with BACK, else with a clean restart and the path
            String backXml = null;
            if (atChild && !relaunchFailed) {
                backAndWait();
                backXml = stableDump(STABLE_INTERVAL_MS, state.signature);
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

    /** Captures the current screen once it settles, without navigating. */
    private StateRec captureCurrent() throws Exception {
        String xml = stableDump();
        return xml == null ? null : capture(xml);
    }

    /** Builds the state of a fresh dump, with its activity, clickables and screenshot. */
    private StateRec capture(String xml) throws Exception {
        StateRec rec = new StateRec();
        rec.rawXML = xml;
        rec.activity = currentActivity();
        rec.clickables = extractClickables(xml);
        rec.signature = signatureOf(xml);
        rec.structure = structureOf(xml);
        rec.screenShot = captureScreenshot(sanitize(rec.signature));
        return rec;
    }

    /** Registers a state by signature and returns its existing or new id. */
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
     * Clean-restarts the app and walks the recorded path to {@code target}, typing its inputs again.
     * Returns the screen XML when the signature matches and records a RESTART. A failed relaunch
     * stops navigation but keeps the states captured so far.
     */
    private String reposition(StateRec target) throws Exception {
        if (relaunchFailed) {
            return null;
        }
        restartCount++;
        // Every screen on the way is known, so one dump that shows it is enough
        String rootSignature = states.isEmpty() ? null : states.get(0).signature;
        if (!startClean(1000, rootSignature)) {
            relaunchFailed = true;
            String crash = crashReason();
            System.out.println("  [explorer] WARNING: app did not return to the foreground on relaunch"
                    + (crash != null ? " (app crashed: " + crash + ")" : "")
                    + "; keeping the " + states.size() + " states captured so far");
            return null;
        }
        String xml = startDump;
        if (xml != null && !signatureOf(xml).equals(rootSignature)) {
            xml = null;   // not the first screen yet, the next dump waits for it
        }
        for (PathStep step : target.path) {
            StateRec via = stateById(step.stateId);
            if (via != null && !via.inputs.isEmpty()) {
                applyInputs(via);
                xml = null;   // typing may move things, so look again
            }
            if (xml == null) {
                xml = stableDump(STABLE_INTERVAL_MS, via == null ? null : via.signature);
            }
            if (xml == null) {
                return null;
            }
            int[] c = findElementCenter(xml, step.selector);
            if (c == null) {
                return null;
            }
            tapAndWait(c[0], c[1]);
            xml = null;
        }
        if (!target.inputs.isEmpty()) {
            applyInputs(target);
            xml = null;
        }
        if (xml == null) {
            xml = stableDump(STABLE_INTERVAL_MS, target.signature);
        }
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
     * Types an LLM-suggested value into each text field so screens behind a form become reachable.
     * The values are recorded and typed verbatim in every language. Returns the screen XML after typing.
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

    /** Types the recorded inputs of a state again. */
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

    /** Closes the soft keyboard with one BACK, since it covers the screen and shows up in dumps. */
    private void hideKeyboard() throws Exception {
        String ime = runAdb(8000, "shell", "dumpsys", "input_method");
        if (ime != null && ime.contains("mInputShown=true")) {
            back();
            sleep(500);
        }
    }

    /** Keeps only ASCII letters, digits and basic punctuation, which {@code adb shell input text} can type. */
    private static String safeInput(String value) {
        if (value == null) {
            return "";
        }
        String safe = value.replaceAll("[^A-Za-z0-9 .,@_\\-]", "").trim();
        return safe.length() > 40 ? safe.substring(0, 40).trim() : safe;
    }

    // ================================================================= replay

    /**
     * Installs this build and reproduces the recorded script, storing states under their source ids.
     * Divergences are logged and reported, never fatal.
     */
    public String replay(String scriptPath) throws Exception {
        long started = System.nanoTime();
        prepareFolders();
        Script script = Script.load(scriptPath);
        install();
        runAdb(8000, "logcat", "-b", "crash", "-c");
        if (!startClean(1500, null)) {
            String crash = crashReason();
            throw new ExplorationException("App '" + pkg + "' did not reach the foreground after launch"
                    + (crash != null ? "; app crashed on launch: " + crash
                                     : "; refusing to explore off-app screens (launch likely failed)."));
        }

        List<String> divergences = new ArrayList<>();
        int reproduced = 0;
        StateRec root = captureCurrent();
        if (root == null) {
            throw new ExplorationException("could not capture the initial state of the " + locale + " build");
        }
        StateRec recordedRoot = script.states.get(script.rootId);
        if (recordedRoot != null && !root.structure.equals(recordedRoot.structure)) {
            // Usually a transient start screen that one BACK dismisses, else go on and report it
            backAndWait();
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
                String xml = stableDump(STABLE_INTERVAL_MS, replaySig.get(a.from));
                if (xml == null) {
                    current = -1;
                    continue;
                }
                int[] c = findElementCenter(xml, a.selector);
                if (c == null) {
                    divergences.add("state " + a.from + ": element " + a.selector + " not found");
                    continue;   // still at from
                }
                if (a.to != a.from && replaySig.containsKey(a.to)) {
                    tapAndWait(c[0], c[1]);   // the target screen is known and seen as soon as it shows
                } else {
                    tap(c[0], c[1]);
                    sleep(TAP_SETTLE_MS);     // same pause as the exploration, for the same answer
                }
                // A known target screen needs one dump, any other outcome waits for the screen to settle
                boolean outside = !inApp();
                String after = outside ? null
                        : stableDump(STABLE_INTERVAL_MS, a.to != a.from ? replaySig.get(a.to) : null);
                // A message the source exploration let go by must not count as a screen here
                if (after != null && a.to == a.from) {
                    after = outlastMessage(after, replaySig.get(a.from));
                }
                if (!outside && after != null && (!inApp() || !showsApp(after))) {
                    outside = true;   // the other app came to the front during the dump, or its dialog covers the app
                }
                if (outside) {
                    backAndWait();
                    if (a.to == 0) {
                        reproduced++;
                    } else {
                        divergences.add("state " + a.from + ": tap " + a.selector + " left the app (expected state " + a.to + ")");
                    }
                    String b = stableDump(STABLE_INTERVAL_MS, replaySig.get(a.from));
                    current = (b != null && inApp() && matchesReplayed(b, a.from)) ? a.from : -1;
                    continue;
                }
                StateRec rec = after == null ? null : capture(after);
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
                backAndWait();
                String xml = stableDump(STABLE_INTERVAL_MS, replaySig.get(a.to));
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
                    adopt(capture(xml), a.to, script, true);
                    reproduced++;
                    current = a.to;
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
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        writeReplayReport(script.actions.size(), reproduced, divergences, script.states.size(), missing, seconds);
        System.out.println("  [explorer] replay: " + reproduced + "/" + script.actions.size() + " actions reproduced, "
                + divergences.size() + " divergences, " + states.size() + "/" + script.states.size() + " states captured"
                + (missing.isEmpty() ? "" : " (missing " + missing + ")") + ", " + seconds + " s (" + tapCount + " taps, "
                + backCount + " BACK, " + restartCount + " restarts, " + dumpCount + " dumps)");
        for (String d : divergences) {
            System.out.println("  [explorer] divergence: " + d);
        }
        System.out.println("  [explorer] done: " + states.size() + " states, "
                + transitions.size() + " transitions -> " + outputFolder + File.separator + "result.json");
        uninstallQuietly();
        return outputFolder;
    }

    /** Stores a captured screen as source state {@code id} and optionally types its recorded inputs. */
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

    /** Counts transitions as the DFS does (one per screen-changing tap, repeats included), so counts match. */
    private void addTransition(int from, int to) {
        transitions.add(new int[]{from, to});
        transitionTypes.add("GUI_CLICK_BUTTON");
    }

    private boolean matchesReplayed(String xml, int id) throws Exception {
        String expected = replaySig.get(id);
        return expected != null && signatureOf(xml).equals(expected);
    }

    /** Replay counterpart of {@link #reposition}, a clean restart and the recorded path to a source state. */
    private String replayReposition(Script script, int targetId) throws Exception {
        if (relaunchFailed) {
            return null;
        }
        StateRec target = script.states.get(targetId);
        if (target == null) {
            return null;
        }
        restartCount++;
        // As in reposition, an already captured screen is recognised by one dump
        String rootSignature = replaySig.get(script.rootId);
        if (!startClean(1000, rootSignature)) {
            relaunchFailed = true;
            String crash = crashReason();
            System.out.println("  [explorer] WARNING: app did not return to the foreground on relaunch"
                    + (crash != null ? " (app crashed: " + crash + ")" : "")
                    + "; keeping the " + states.size() + " states captured so far");
            return null;
        }
        String xml = startDump;
        if (xml != null && !signatureOf(xml).equals(rootSignature)) {
            xml = null;
        }
        for (PathStep step : target.path) {
            StateRec via = script.states.get(step.stateId);
            if (via != null && !via.inputs.isEmpty()) {
                applyInputs(via);
                xml = null;
            }
            if (xml == null) {
                xml = stableDump(STABLE_INTERVAL_MS, replaySig.get(step.stateId));
            }
            if (xml == null) {
                return null;
            }
            int[] c = findElementCenter(xml, step.selector);
            if (c == null) {
                return null;
            }
            tapAndWait(c[0], c[1]);
            xml = null;
        }
        if (!target.inputs.isEmpty()) {
            applyInputs(target);
            xml = null;
        }
        if (xml == null) {
            xml = stableDump(STABLE_INTERVAL_MS, replaySig.get(targetId));
        }
        if (xml == null) {
            return null;
        }
        if (replaySig.containsKey(targetId)) {
            return signatureOf(xml).equals(replaySig.get(targetId)) ? xml : null;
        }
        StateRec rec = capture(xml);
        adopt(rec, targetId, script, false);   // inputs were already typed along the path
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
        // A leftover build with another signature would make install -r fail and leave the wrong app
        runAdb(15000, "uninstall", pkg);
        String out = runAdb(120000, "install", "-r", "-t", "-g", apkPath);
        // Without Success the app is not on the device and exploring would capture the launcher
        if (out == null || !out.contains("Success")) {
            throw new ExplorationException("could not install " + apkPath + ": "
                    + (out == null ? "no adb output" : out.trim().replace('\n', ' ')));
        }
    }

    /** Stops and removes the app so nothing of this build stays while the next one is prepared. */
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
        // Per-app locales need Android 13 (API 33) but no root
        String out = runAdb(8000, "shell", "cmd", "locale", "set-app-locales", pkg, "--locales", locale);
        System.out.println("  [explorer] set-app-locales " + pkg + " -> " + locale
                + (out != null && out.toLowerCase().contains("exception") ? " (WARN: " + out.trim() + ")" : ""));
    }

    /**
     * Starts the app from scratch and leaves the settled dump of its first screen in {@link #startDump}.
     * False when the app did not come to the foreground. AppCompat with {@code autoStoreLocales} drops
     * the system language on the first start after install, so the start is repeated once when that happens.
     */
    private boolean startClean(long pauseMs, String expected) throws Exception {
        startDump = null;
        for (int attempt = 0; ; attempt++) {
            cleanReset();
            launch();
            sleep(pauseMs);
            if (!waitForForeground()) {
                return false;
            }
            startDump = settleAfterLaunch(expected);
            if (languageRefused || !languageDropped()) {
                return true;
            }
            if (attempt > 0) {
                languageRefused = true;
                System.out.println("  [explorer] WARNING: the app takes the language '" + locale
                        + "' away every time it starts: its screens are in the language it chooses by itself");
                return true;
            }
            languageRestarts++;
            System.out.println("  [explorer] the app took the language '" + locale
                    + "' away when it started; setting it again and starting over");
        }
    }

    /** True when the system no longer holds the language this run set for the app. */
    private boolean languageDropped() throws Exception {
        if (locale == null || locale.isEmpty()) {
            return false;
        }
        return !holdsLanguage(runAdb(8000, "shell", "cmd", "locale", "get-app-locales", pkg), locale);
    }

    /**
     * Tells whether the first language in the answer of {@code cmd locale get-app-locales} is the one
     * asked for. An unreadable answer counts as holding it.
     */
    static boolean holdsLanguage(String answer, String locale) {
        if (answer == null) {
            return true;
        }
        int open = answer.lastIndexOf('[');
        int close = open < 0 ? -1 : answer.indexOf(']', open);
        if (close < 0) {
            return true;
        }
        String first = answer.substring(open + 1, close).split(",")[0];
        return languageOf(first).equals(languageOf(locale));
    }

    /** The language of a tag such as {@code pt-BR} or {@code pt_BR}, mapping the old codes Android still writes. */
    private static String languageOf(String tag) {
        String language = tag.trim().split("[-_]")[0].toLowerCase(java.util.Locale.ROOT);
        switch (language) {
            case "iw": return "he";
            case "in": return "id";
            case "ji": return "yi";
            default:   return language;
        }
    }

    /** Wipes app data and restores locale, permissions and setup content, so each start is pristine. */
    private void cleanReset() throws Exception {
        runAdb(15000, "shell", "pm", "clear", pkg);   // clears data and cache and stops the app
        setAppLocale();                               // pm clear also resets the per-app locale
        grantRuntimePermissions();                    // and revokes the runtime permissions
        runSetup();                                   // and removes the content the app was given
    }

    /**
     * Runs the {@code exploration.setup} commands that give the app content to show, after every reset
     * and in every language. Their answers are printed the first time.
     */
    private void runSetup() throws Exception {
        for (String[] command : opts.setup) {
            String out = runAdb(120000, command);
            if (!setupShown) {
                String answer = out == null ? "no answer" : out.trim().replace('\n', ' ');
                System.out.println("  [explorer] setup: adb " + String.join(" ", command)
                        + (answer.isEmpty() ? "" : " -> " + (answer.length() > 160 ? answer.substring(0, 160) + "..." : answer)));
            }
        }
        setupShown = true;
    }

    /** Grants the runtime permissions the app requests, so no system permission dialog covers it. */
    private void grantRuntimePermissions() throws Exception {
        if (grantCommand == null) {
            String dump = runAdb(15000, "shell", "dumpsys", "package", pkg);
            StringBuilder cmd = new StringBuilder();
            int count = 0;
            for (String perm : RUNTIME_PERMISSIONS) {
                // Android 13 asks for POST_NOTIFICATIONS even for apps that do not declare it
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
        tapCount++;
        runAdb(8000, "shell", "input", "tap", String.valueOf(x), String.valueOf(y));
    }

    private void back() throws Exception {
        backCount++;
        runAdb(8000, "shell", "input", "keyevent", "KEYCODE_BACK");
    }

    /** Taps and gives a new window the time to come to the front. */
    private void tapAndWait(int x, int y) throws Exception {
        String before = focusedWindow();
        tap(x, y);
        waitForFocusChange(before);
    }

    private void backAndWait() throws Exception {
        String before = focusedWindow();
        back();
        waitForFocusChange(before);
    }

    /** The window with the input focus, empty when it cannot be read. */
    private String focusedWindow() throws Exception {
        String out = runAdb(8000, "shell", "dumpsys window | grep -E 'mCurrentFocus'");
        return out == null ? "" : out.trim();
    }

    /** Returns as soon as the focused window changes, or after {@link #ACTION_WAIT_MS}. */
    private void waitForFocusChange(String before) throws Exception {
        long end = System.nanoTime() + ACTION_WAIT_MS * 1_000_000L;
        while (System.nanoTime() < end) {
            sleep(100);
            if (!focusedWindow().equals(before)) {
                return;
            }
        }
    }

    /** True when the foreground activity belongs to the app under test. */
    private boolean inApp() throws Exception {
        String top = topActivityLine(serial);
        return top != null && top.contains(pkg + "/");
    }

    /** The dumpsys line naming the resumed activity, or null when it cannot be read. */
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

    /** Waits until the app is in the foreground, accepting a permission dialog the pre-grant missed. */
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
     * Waits a fixed time and then until the structure holds for 1.5 s, since splash screens made graphs
     * non-reproducible. A dump showing {@code expected} ends the wait. Returns the settled dump.
     */
    private String settleAfterLaunch(String expected) throws Exception {
        if (opts.settleSeconds > 0) {
            sleep(opts.settleSeconds * 1000L);
        }
        return stableDump(1500, expected);
    }

    /** Taps the allow button of a system permission dialog on screen, if any. */
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

    /** The distinct exceptions of every app crash since this run cleared the crash log. */
    private List<String> crashReasons() {
        List<String> reasons = new ArrayList<>();
        try {
            String out = execAdb(serial, 15000, "logcat", "-b", "crash", "-d", "-t", "3000");
            if (out == null) {
                return reasons;
            }
            boolean ours = false;
            boolean taken = false;
            for (String line : out.split("\n")) {
                int at = line.indexOf("AndroidRuntime: ");
                if (at < 0) {
                    continue;
                }
                String msg = line.substring(at + "AndroidRuntime: ".length()).trim();
                if (msg.startsWith("Process: ")) {
                    ours = msg.contains(pkg);
                    taken = false;
                } else if (ours && !taken && !msg.isEmpty() && !msg.startsWith("FATAL") && !msg.startsWith("at ")) {
                    taken = true;
                    String reason = msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
                    if (!reasons.contains(reason)) {
                        reasons.add(reason);
                    }
                }
            }
        } catch (Exception e) {
            // the crash log is informative only and must not fail the run
        }
        return reasons;
    }

    /** The exception that killed the app, or null when it did not crash. */
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
     * Dumps the UI hierarchy through uiautomator. The remote file is removed first and printed only when
     * the dump succeeded, so a failed dump is never read as the previous screen (a seconds clock never goes idle).
     */
    private static String dumpHierarchy(String serial) throws Exception {
        boolean neverIdle = false;
        for (int attempt = 0; attempt < 3; attempt++) {
            String answer = execAdb(serial, 35000, "shell", "rm -f " + DUMP_FILE + "; uiautomator dump " + DUMP_FILE
                    + " && cat " + DUMP_FILE);
            if (answer != null && answer.contains("idle state")) {
                neverIdle = true;
            }
            // the uiautomator status line comes first, the file after it
            int at = answer == null || !answer.contains("dumped to") ? -1 : answer.indexOf("<?xml");
            if (at < 0 && answer != null && answer.contains("dumped to")) {
                at = answer.indexOf("<hierarchy");
            }
            if (at >= 0) {
                String xml = answer.substring(at).trim();
                if (xml.contains("<hierarchy") && xml.contains("<node")) {
                    return xml;
                }
            }
            sleep(800);
        }
        System.out.println("  [explorer] WARNING: could not dump UI hierarchy"
                + (neverIdle ? " (the UI never becomes idle: uiautomator cannot capture a screen that keeps changing)" : ""));
        return null;
    }

    /**
     * True when signature {@code candidate} differs from {@code base} by only a few added or changed
     * elements, as a snackbar or a short status line does.
     */
    static boolean differsLittle(String base, String candidate) {
        String[] before = base.split(";");
        String[] after = candidate.split(";");
        if (Math.abs(after.length - before.length) > MESSAGE_NODES) {
            return false;
        }
        // longest common subsequence of the two screens
        int[] previous = new int[after.length + 1];
        for (String element : before) {
            int[] current = new int[after.length + 1];
            for (int j = 0; j < after.length; j++) {
                current[j + 1] = element.equals(after[j]) ? previous[j] + 1 : Math.max(previous[j + 1], current[j]);
            }
            previous = current;
        }
        int shared = previous[after.length];
        int added = after.length - shared;
        int gone = before.length - shared;
        return added + gone > 0 && added <= MESSAGE_NODES && gone <= MESSAGE_NODES;
    }

    /**
     * Keeps a passing message from counting as a screen. A dump that differs little from {@code base}
     * is looked at again every {@link #MESSAGE_POLL_MS} up to {@link #MESSAGE_POLLS} times, and it is a
     * screen only if the difference stays. Returns the dump to go on with.
     */
    private String outlastMessage(String xml, String base) throws Exception {
        String candidate = signatureOf(xml);
        if (base == null || candidate.equals(base) || !differsLittle(base, candidate)) {
            return xml;
        }
        for (int look = 0; look < MESSAGE_POLLS; look++) {
            sleep(MESSAGE_POLL_MS);
            String again = dumpHierarchy(serial);
            dumpCount++;
            if (again == null) {
                return xml;
            }
            String now = signatureOf(again);
            if (now.equals(base)) {
                System.out.println("  [explorer] a message shown for a moment over the screen was let go by");
                return again;
            }
            if (!now.equals(candidate)) {
                String settled = stableDump();   // it became something else, read it once settled
                return settled != null ? settled : again;
            }
        }
        return xml;
    }

    private String stableDump() throws Exception {
        return stableDump(STABLE_INTERVAL_MS, null);
    }

    /**
     * Dumps the UI until two dumps {@code intervalMs} apart share a signature, falling back to the last
     * dump after a few attempts. {@code expected} is the signature of a known screen, or null, and a dump
     * showing it is returned at once.
     */
    private String stableDump(long intervalMs, String expected) throws Exception {
        String prevSig = null;
        String dump = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            dump = dumpHierarchy(serial);
            dumpCount++;
            if (dump == null) {
                return null;
            }
            if (dismissNotResponding(dump)) {
                prevSig = null;   // the dump was the system dialog, not the app
                sleep(intervalMs);
                continue;
            }
            String sig = signatureOf(dump);
            if (sig.equals(prevSig) || sig.equals(expected)) {
                return dump;   // two identical structures in a row, or the known one
            }
            prevSig = sig;
            sleep(intervalMs);
        }
        return dump;
    }

    /** The Wait button of the Android dialog shown when an app does not respond. */
    private static final Pattern NOT_RESPONDING_WAIT = Pattern.compile(
            "resource-id=\"android:id/aerr_wait\"[^>]*?bounds=\"(\\[\\d+,\\d+\\]\\[\\d+,\\d+\\])\"");

    /**
     * Taps Wait when the dump is the not-responding dialog a slow emulator shows, and returns true then.
     * The dialog is not an activity, so it would otherwise be stored as a screen of the app.
     */
    private boolean dismissNotResponding(String xml) throws Exception {
        Matcher m = NOT_RESPONDING_WAIT.matcher(xml);
        if (!m.find()) {
            return false;
        }
        int[] c = boundsCenter(m.group(1));
        if (c == null) {
            return false;
        }
        System.out.println("  [explorer] the system's \"not responding\" dialog covered the screen: dismissed");
        runAdb(8000, "shell", "input", "tap", String.valueOf(c[0]), String.valueOf(c[1]));
        return true;
    }

    /** True when the dump has some element of the app, not only a foreign dialog drawn over it. */
    private boolean showsApp(String xml) {
        return xml.contains("package=\"" + pkg + "\"");
    }

    /** True when the device answers a command of its package service within 20 s. */
    public static boolean deviceAnswers(String serial) {
        try {
            String out = execAdb(serial, 20000, "shell", "pm", "path", "android");
            return out != null && out.contains("package:");
        } catch (Exception e) {
            return false;
        }
    }

    /** Waits up to {@code seconds} for the device to answer, true when it does. */
    public static boolean waitForDevice(String serial, int seconds) {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (true) {
            if (deviceAnswers(serial)) {
                return true;
            }
            if (System.nanoTime() >= end) {
                return false;
            }
            sleep(5000);
        }
    }

    private String captureScreenshot(String name) {
        Process p = null;
        try {
            File png = new File(shotsDir, name + ".png");
            ProcessBuilder pb = new ProcessBuilder("adb", "-s", serial, "exec-out", "screencap", "-p");
            pb.redirectOutput(png);
            // An unread stderr pipe could fill up and block screencap forever
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
                p.destroyForcibly();   // do not leak the adb process
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

    /** Tappable elements, excluding text fields since tapping one only opens the keyboard. */
    private static boolean isClickableCandidate(UiNode n) {
        return (n.clickable || n.clazz.contains("Button")) && n.center != null && !isEditField(n);
    }

    private static boolean isImeNode(UiNode n) {
        return n.pkg.contains("inputmethod");
    }

    /** Clickable elements in a deterministic order that does not depend on the language. */
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

    /** Tap center of the clickable matching the selector, numbered as in {@link #extractClickables}. */
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

    /** Text fields of a screen, each with a selector numbered among text fields. */
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
     * State signature of resource id, class, content-desc with digits folded, and whether a node shows
     * text. Ignores the text itself and the keyboard. The extra fields tell apart Compose screens without ids.
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

    /** Resource id and class skeleton, used to compare screens across builds. */
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
        // Screenshot paths are relative to this result.json, so the results can be moved
        PortablePaths portable = new PortablePaths(Paths.get(outputFolder));
        for (StateRec s : states) {
            JSONObject sj = new JSONObject();
            sj.put("id", (long) s.id);
            if (withSourceIds) {
                sj.put("sourceStateId", (long) s.id);
            }
            sj.put("activityName", s.activity == null ? "" : s.activity);
            sj.put("rawXML", s.rawXML);
            sj.put("screenShot", portable.relative(s.screenShot));
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

    /** Writes the replay script with every state, its path and inputs, and every action in order. */
    @SuppressWarnings("unchecked")
    private void writeScript(String stop, long seconds) throws Exception {
        JSONObject root = new JSONObject();
        root.put("package", pkg);
        root.put("locale", locale);
        root.put("rootId", states.isEmpty() ? 0L : (long) states.get(0).id);
        // how far the exploration got and what ended it, for the report
        JSONObject summary = work(seconds);
        summary.put("stop", stop);
        summary.put("states", (long) states.size());
        summary.put("maxStates", (long) opts.maxStates);
        summary.put("maxClickables", (long) opts.maxClickables);
        summary.put("maxMinutes", (long) opts.maxMinutes);
        JSONArray visited = new JSONArray();
        for (StateRec s : states) {
            String activity = nz(s.activity).replace("}", "");
            if (!activity.isEmpty() && !visited.contains(activity)) {
                visited.add(activity);
            }
        }
        summary.put("activities", visited);
        root.put("exploration", summary);
        // crashes of the original app, so a translated build crashing the same way is not blamed on translation
        JSONArray crashes = new JSONArray();
        crashes.addAll(crashReasons());
        root.put("crashes", crashes);
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
            List<Integer> missing, long seconds) throws Exception {
        JSONObject o = work(seconds);
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
        JSONArray crashes = new JSONArray();
        crashes.addAll(crashReasons());
        o.put("crashes", crashes);
        Files.writeString(Paths.get(outputFolder, REPLAY_REPORT_FILE), o.toJSONString(), StandardCharsets.UTF_8);
    }

    /** Time and device actions spent by this exploration or replay. */
    @SuppressWarnings("unchecked")
    private JSONObject work(long seconds) {
        JSONObject o = new JSONObject();
        o.put("seconds", seconds);
        o.put("taps", (long) tapCount);
        o.put("backs", (long) backCount);
        o.put("restarts", (long) restartCount);
        o.put("dumps", (long) dumpCount);
        // starts repeated because the app dropped its language, and whether it always did
        o.put("languageRestarts", (long) languageRestarts);
        o.put("languageRefused", languageRefused);
        return o;
    }

    // ================================================================= static device helpers

    private String runAdb(long timeoutMs, String... args) throws Exception {
        return execAdb(serial, timeoutMs, args);
    }

    /** Installs an APK, first removing any install of its package so a signature change does not fail. */
    public static void installApk(String serial, String pkg, String apkPath) throws Exception {
        execAdb(serial, 15000, "uninstall", pkg);
        String out = execAdb(serial, 180000, "install", "-t", apkPath);
        if (out != null && out.toLowerCase().contains("failure")) {
            System.out.println("  [explorer] WARNING: reinstall of original reported: " + out.trim());
        } else {
            System.out.println("  [explorer] original app reinstalled on " + serial + ": " + apkPath);
        }
    }

    /** Force-stops the app and uninstalls it with its data. */
    public static void uninstall(String serial, String pkg) throws Exception {
        execAdb(serial, 8000, "shell", "am", "force-stop", pkg);
        String out = execAdb(serial, 15000, "uninstall", pkg);
        System.out.println("  [explorer] " + pkg + " removed from the device"
                + (out != null && out.contains("Success") ? "" : " (" + nz(out).trim() + ")"));
    }

    /**
     * Preflight launch test of the original APK. Returns null when it stays in the foreground, else the
     * reason. The app is removed afterwards.
     */
    public static String checkLaunch(String serial, String pkg, String apkPath) throws Exception {
        return checkLaunch(serial, pkg, apkPath, "the original app");
    }

    /** The same launch test on any build, named {@code what} in the messages. */
    public static String checkLaunch(String serial, String pkg, String apkPath, String what) throws Exception {
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
            sleep(3000);   // a crash or a hand-over to a browser shows within seconds
            String later = topActivityLine(serial);
            still = later != null && later.contains(pkg + "/");
            if (!still) {
                top = later;
            }
        }
        String crash = crashReason(serial, pkg);
        String reason = null;
        if (crash != null) {
            reason = what + " crashes on launch: " + crash;
        } else if (!seen) {
            reason = what + " never reached the foreground (" + foregroundOwner(top) + ")";
        } else if (!still) {
            reason = what + " handed the screen to another package right after launch (" + foregroundOwner(top)
                    + "); Trusted Web Activities and browser-backed apps are out of scope";
        }
        execAdb(serial, 8000, "shell", "am", "force-stop", pkg);
        execAdb(serial, 15000, "uninstall", pkg);
        System.out.println("PREFLIGHT: launch test of " + what + ": " + (reason == null ? "OK" : reason));
        return reason;
    }

    private static String foregroundOwner(String topLine) {
        if (topLine == null) {
            return "no foreground activity";
        }
        Matcher m = Pattern.compile("([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)").matcher(topLine);
        return m.find() ? m.group(1) + "/" + m.group(2) : topLine.trim();
    }

    /** A system property of the device, empty when unavailable. */
    public static String deviceProp(String serial, String name) throws Exception {
        return nz(execAdb(serial, 8000, "shell", "getprop", name)).trim();
    }

    /** API level of the device, or -1 when it cannot be read. */
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

    /** Runs an adb command on a device with a real timeout. */
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
        // Drain on a daemon thread so a hung device cannot block readLine() past the timeout
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
            // Wait for the reader before reading the shared StringBuilder, it ends once the stream closes
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

    /** Converts an Android values-folder code such as zh-rCN to BCP47 (zh-CN). */
    private static String toBcp47(String code) {
        if (code == null) {
            return "";
        }
        return code.replace("-r", "-");
    }

    /** Target device serial, {@code ANDROID_SERIAL} if set, else the first {@code adb devices} entry. */
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
        // Bounded wait so a stuck adb devices cannot hang startup
        if (!p.waitFor(10000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            System.out.println("  [explorer] WARNING: 'adb devices' timed out");
        }
        if (devices.isEmpty()) {
            throw new IllegalStateException("No adb device connected. Start an emulator or plug in a device.");
        }
        String chosen = devices.get(0);
        if (devices.size() > 1) {
            // Arbitrary pick when several devices are attached, so warn about it
            System.out.println("  [explorer] WARNING: " + devices.size() + " adb devices found " + devices
                    + "; no ANDROID_SERIAL set, using " + chosen);
        }
        return chosen;
    }

    /** Standalone entry point for testing, see the usage line for the arguments. */
    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.out.println("Usage: AdbExplorer <serial> <pkg> <apkPath> <outputFolder> <locale> [maxStates=N] [maxClickables=N]"
                    + " [maxMinutes=N] [settleSeconds=N] [exploration_script.json]");
            return;
        }
        Options o = new Options();
        String script = null;
        for (int i = 5; i < args.length; i++) {
            String a = args[i];
            if (a.matches("\\d+")) {
                o.maxStates = Integer.parseInt(a);
            } else if (a.matches("maxStates=\\d+")) {
                o.maxStates = Integer.parseInt(a.substring(a.indexOf('=') + 1));
            } else if (a.matches("maxClickables=\\d+")) {
                o.maxClickables = Integer.parseInt(a.substring(a.indexOf('=') + 1));
            } else if (a.matches("maxMinutes=\\d+")) {
                o.maxMinutes = Integer.parseInt(a.substring(a.indexOf('=') + 1));
            } else if (a.matches("settleSeconds=\\d+")) {
                o.settleSeconds = Integer.parseInt(a.substring(a.indexOf('=') + 1));
            } else {
                script = a;
            }
        }
        AdbExplorer explorer = new AdbExplorer(args[0], args[1], args[2], args[3], args[4], o);
        if (script != null) {
            explorer.replay(script);
        } else {
            explorer.explore();
        }
    }

    // ------------------------------------------------------------------ entry points


	/** Explores the source-language build and records the replay script. */
	public static String exploreSource(String language, String outputFolder, boolean translated, String apkLocation,
			String appName, AdbExplorer.Options options) throws ExplorationException {
		try {
			File folder = stateFolder(outputFolder, translated, language);
			String serial = AdbExplorer.resolveSerial();
			System.out.println("Exploring '" + appName + "' on device " + serial + " with locale '" + language + "'");
			new AdbExplorer(serial, appName, apkLocation, folder.getAbsolutePath(), language, options).explore();
			return folder.getCanonicalPath();
		} catch (Exception e) {
			throw wrap(language, e);
		}
	}

	/** Explores a translated build by replaying the source script, so its states pair with the source graph. */
	public static String replayLanguage(String language, String outputFolder, boolean translated, String apkLocation,
			String appName, String scriptPath, AdbExplorer.Options options) throws ExplorationException {
		try {
			File folder = stateFolder(outputFolder, translated, language);
			String serial = AdbExplorer.resolveSerial();
			System.out.println("Exploring '" + appName + "' on device " + serial + " with locale '" + language + "' (replaying the source path)");
			new AdbExplorer(serial, appName, apkLocation, folder.getAbsolutePath(), language, options).replay(scriptPath);
			return folder.getCanonicalPath();
		} catch (Exception e) {
			throw wrap(language, e);
		}
	}

	/**
	 * Replays the source script on another build of a language into its own results folder, such as
	 * {@code rtlResults} for the build with repaired right-to-left layouts.
	 */
	public static String replayInto(String folderName, String language, String outputFolder, String apkLocation,
			String appName, String scriptPath, AdbExplorer.Options options) throws ExplorationException {
		try {
			File folder = stateFolder(outputFolder, folderName, language);
			String serial = AdbExplorer.resolveSerial();
			System.out.println("Exploring '" + appName + "' on device " + serial + " with locale '" + language + "' (replaying the source path, " + folderName + ")");
			new AdbExplorer(serial, appName, apkLocation, folder.getAbsolutePath(), language, options).replay(scriptPath);
			return folder.getCanonicalPath();
		} catch (Exception e) {
			throw wrap(language, e);
		}
	}

	/**
	 * Wraps any failure in an ExplorationException so one language failing does not abort the run.
	 * Uses the exception type when there is no message.
	 */
	private static ExplorationException wrap(String language, Exception e) {
		if (e instanceof ExplorationException) {
			return (ExplorationException) e;
		}
		String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
		return new ExplorationException("Exploration failed for '" + language + "': " + detail, e);
	}

	/** Fresh per-language output folder under the run's results directory. */
	private static File stateFolder(String outputFolder, boolean translated, String language) throws IOException {
		return stateFolder(outputFolder, translated ? "trnsResults" : "noTrnsResults", language);
	}

	private static File stateFolder(String outputFolder, String folderName, String language) throws IOException {
		String decodedPath = Helper.getInstance().getCurrentDirectory();
		// resolve keeps an absolute outputFolder as is, where Paths.get would join it and fail on Windows
		File tempFolder = new File(Paths.get(decodedPath).resolve(outputFolder)
				.resolve(folderName).resolve(language)
				.toAbsolutePath().toString());
		if (tempFolder.exists()) {
			// File.delete cannot remove a non-empty directory
			FileUtils.deleteDirectory(tempFolder);
		}
		tempFolder.mkdirs();
		return tempFolder;
	}

    /** Thrown when a language version cannot be explored on the device. */
    public static class ExplorationException extends Exception {

	private static final long serialVersionUID = 1L;

	public ExplorationException(String errorMessage) {
		super(errorMessage);
	}

	public ExplorationException(String errorMessage, Throwable cause) {
		super(errorMessage, cause);
	}
    }
}

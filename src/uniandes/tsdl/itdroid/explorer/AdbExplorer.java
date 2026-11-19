package uniandes.tsdl.itdroid.explorer;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import uniandes.tsdl.itdroid.explorer.Screen.Selector;
import uniandes.tsdl.itdroid.explorer.Screen.UiNode;
import uniandes.tsdl.itdroid.helper.Helper;
import uniandes.tsdl.itdroid.helper.PortablePaths;
import uniandes.tsdl.itdroid.translator.LLMTranslator;

/**
 * Captures the UI states of an app on a {@link Device}, with the language set per app. {@link #explore()}
 * records a DFS of the source build into a script and {@link #replay(String)} reproduces it on each
 * translated build, so states pair by id. Both write the {@code result.json} read by {@code LayoutGraph}.
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
        /**
         * Grants every runtime permission before each start, so no request shows. False lets the app
         * ask and answers Allow as a new user would, so the screens that explain a permission are seen.
         */
        public boolean grantPermissions = false;
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

    /** Pause before reading the screen after a tap whose outcome is not known. */
    private static final long TAP_SETTLE_MS = 900;
    /** Interval and number of looks at a screen that may only be showing a message. */
    private static final long MESSAGE_POLL_MS = 2500;
    private static final int MESSAGE_POLLS = 2;
    /** Pause before looking again at a replayed screen that did not match, as its content may still be loading. */
    private static final long LOOK_AGAIN_MS = 2000;
    /** Why an exploration ended (screen or time limit, per-screen limit, nothing left, or no relaunch). */
    public static final String STOP_STATES = "maxStates";
    public static final String STOP_MINUTES = "maxMinutes";
    public static final String STOP_CLICKABLES = "maxClickables";
    public static final String STOP_EXHAUSTED = "exhausted";
    public static final String STOP_RELAUNCH = "relaunchFailed";

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
        /** The screen captured, null for a state read from the script. */
        Screen screen;
        String screenShot;
        /** The texts it shows, which tell apart two pages with the same elements. */
        List<String> texts = new ArrayList<>();
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

        static Action of(String type, int from, Selector sel, int to) {
            Action a = new Action();
            a.type = type;
            a.from = from;
            a.to = to;
            a.selector = sel;
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
            Object sel = o.get("selector");
            return of(str(o, "type"), toInt(o.get("from")), sel instanceof JSONObject ? Selector.fromJson((JSONObject) sel) : null,
                    toInt(o.get("to")));
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

    private final String pkg;
    private final String apkPath;
    private final String outputFolder;
    private final String locale;
    private final Options opts;
    private final Device device;

    public AdbExplorer(String serial, String pkg, String apkPath, String outputFolder, String locale, Options opts) {
        this.pkg = pkg;
        this.apkPath = apkPath;
        this.outputFolder = outputFolder;
        this.locale = toBcp47(locale);
        this.opts = opts == null ? new Options() : opts;
        this.device = new Device(serial, pkg, this.locale, this.opts);
    }

    private final List<StateRec> states = new ArrayList<>();
    private final List<int[]> transitions = new ArrayList<>();
    private final List<String> transitionTypes = new ArrayList<>();
    private final List<Action> actions = new ArrayList<>();
    /** The script being replayed, null while the source is explored. */
    private Script script;
    /** During a replay, the signature each source state id has in this build. */
    private final Map<Integer, String> replaySig = new HashMap<>();
    private File shotsDir;
    /** Set when a relaunch could not bring the app back, which ends navigation. */
    private boolean relaunchFailed;
    /** {@code System.nanoTime()} at which the exploration stops, 0 for never. */
    private long deadlineNanos;
    /** The limit that left elements untapped, if any. */
    private String limitReached;
    /** Set when some screen had more elements than the per-screen limit. */
    private boolean clickablesLeft;
    private int restartCount;

    // ================================================================= explore (record)

    /** Runs the DFS, writes {@code result.json} and the replay script, and returns the output folder. */
    public String explore() throws Exception {
        long started = System.nanoTime();
        deadlineNanos = opts.maxMinutes > 0 ? started + opts.maxMinutes * 60_000_000_000L : 0;
        prepareFolders();
        installAndStart();

        StateRec root = captureCurrent();
        if (root != null) {
            int rootId = register(root);
            dfs(root, rootId, opts.fillForms ? fillForms(root) : root.screen);
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
        System.out.println("  [explorer] stopped by " + stop + " after " + seconds + " s (" + counts() + ")");
        device.uninstallQuietly();
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
    private boolean dfs(StateRec state, int stateId, Screen start) throws Exception {
        Screen current = start;
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
            int[] center = current.center(sel);
            if (center == null) {
                continue;
            }
            explored++;
            // The pause decides whether a short status line is caught, so it stays the one earlier
            // evaluations used and the same screens are found.
            device.tap(center[0], center[1]);
            Device.sleep(TAP_SETTLE_MS);

            // Another app can take the screen at once or seconds later, so check before and after the dump.
            // A permission the tap asked for is given first.
            boolean outside = !device.inAppAfterRequests();
            Screen shown = outside ? null : device.stableDump();
            // A new screen that differs by a line may be a message about to go away
            if (shown != null && known(shown.signature, List.of(), false) == null) {
                shown = outlastMessage(shown, state.signature);
            }
            if (!outside && shown != null && (!device.inApp() || !shown.showsPackage(pkg))) {
                outside = true;   // another app came to the front, or a foreign dialog covers the app
            }
            if (outside) {
                // The tap opened another app, go back without recording its screens
                actions.add(Action.of(TAP, stateId, sel, 0));
                device.backAndWait();
                Screen back = device.stableDump(state.signature);
                if (back == null || !device.inApp() || !shows(back, state)) {
                    back = reposition(stateId);
                    if (back == null) {
                        return false;
                    }
                }
                current = back;
                continue;
            }

            if (shown == null) {
                current = reposition(stateId);
                if (current == null) {
                    return false;
                }
                continue;
            }
            StateRec child = capture(shown);
            boolean sameElements = Screen.sameState(child.signature, state.signature);
            if (sameElements && !Screen.otherTexts(state.texts, child.texts)) {
                // No navigation, or a selection that moved, but it may have changed data, so it is replayed too
                actions.add(Action.of(TAP, stateId, sel, stateId));
                current = child.screen;
                continue;
            }

            // The same elements with other texts are another page, as of a presentation, a screen of its own
            Integer known = known(child.signature, child.texts, sameElements);
            boolean isNew = known == null;
            int childId = isNew ? register(child) : known;
            if (isNew) {
                child.path = new ArrayList<>(state.path);
                PathStep step = new PathStep();
                step.stateId = stateId;
                step.selector = sel;
                child.path.add(step);
            }
            addTransition(stateId, childId);
            actions.add(Action.of(TAP, stateId, sel, childId));

            boolean atChild = true;
            if (isNew) {
                Screen form = opts.fillForms ? fillForms(child) : child.screen;
                if (limitNow() == null) {
                    atChild = dfs(child, childId, form);
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
            Screen back = null;
            if (atChild && !relaunchFailed) {
                device.backAndWait();
                back = device.stableDump(state.signature);
                if (back != null && shows(back, state)) {
                    actions.add(Action.of(BACK, childId, null, stateId));
                } else {
                    back = null;
                }
            }
            if (back == null) {
                back = reposition(stateId);
                if (back == null) {
                    return false;
                }
            }
            current = back;
        }
        return true;
    }

    /** Captures the current screen once it settles, without navigating. */
    private StateRec captureCurrent() throws Exception {
        Screen screen = device.stableDump();
        return screen == null ? null : capture(screen);
    }

    /** The state a screen shows, with its activity and clickables. Its screenshot is taken once it is stored. */
    private StateRec capture(Screen screen) throws Exception {
        StateRec rec = new StateRec();
        rec.screen = screen;
        rec.activity = device.currentActivity();
        rec.clickables = screen.clickables();
        rec.signature = screen.signature;
        rec.structure = screen.structure;
        rec.texts = screen.texts;
        return rec;
    }

    /** Stores a new state under the next id, with the screenshot of the screen it shows, and returns the id. */
    private int register(StateRec rec) {
        int id = states.size() + 1;
        rec.id = id;
        rec.screenShot = device.screenshot(shotsDir, sanitize(rec.signature) + "_" + id);
        states.add(rec);
        System.out.println("  [explorer] new state " + id + " @ " + rec.activity
                + " (" + rec.clickables.size() + " clickables)");
        return id;
    }

    /**
     * The id of the known state a screen shows, by its elements and, among states with the same
     * elements, by its texts. With {@code byTexts} only a state whose texts it shows counts, else the
     * first with its elements does. Null when none.
     */
    private Integer known(String signature, List<String> texts, boolean byTexts) {
        StateRec first = null;
        for (StateRec s : states) {
            if (!Screen.sameState(s.signature, signature)) {
                continue;
            }
            if (!Screen.otherTexts(s.texts, texts)) {
                return s.id;
            }
            first = first == null ? s : first;
        }
        return byTexts || first == null ? null : first.id;
    }

    /** True when a screen shows the state, by its elements and, when another state has them too, by its texts. */
    private boolean shows(Screen screen, StateRec rec) {
        if (!Screen.sameState(screen.signature, rec.signature)) {
            return false;
        }
        Integer id = known(screen.signature, screen.texts, false);
        return id == null || id == rec.id;
    }

    private StateRec stateById(int id) {
        for (StateRec s : states) {
            if (s.id == id) {
                return s;
            }
        }
        return null;
    }

    /**
     * A passing message must not count as a screen. A screen that differs little from {@code base} is
     * looked at again every {@link #MESSAGE_POLL_MS} up to {@link #MESSAGE_POLLS} times, and it is a
     * screen only if the difference stays. Returns the screen to go on with.
     */
    private Screen outlastMessage(Screen screen, String base) throws Exception {
        String candidate = screen.signature;
        if (base == null || candidate.equals(base) || !Screen.differsLittle(base, candidate)) {
            return screen;
        }
        for (int look = 0; look < MESSAGE_POLLS; look++) {
            Device.sleep(MESSAGE_POLL_MS);
            Screen again = device.dump();
            if (again == null) {
                return screen;
            }
            if (again.signature.equals(base)) {
                System.out.println("  [explorer] a message shown for a moment over the screen was let go by");
                return again;
            }
            if (!again.signature.equals(candidate)) {
                Screen settled = device.stableDump();   // it became something else, read it once settled
                return settled != null ? settled : again;
            }
        }
        return screen;
    }

    // ================================================================= back to a state

    /** The recorded state of an id, the one of the script in a replay. */
    private StateRec recorded(int id) {
        return script != null ? script.states.get(id) : stateById(id);
    }

    /** The signature state {@code id} has in this build, null while it was not seen. */
    private String signatureIn(int id) {
        if (script != null) {
            return replaySig.get(id);
        }
        StateRec s = stateById(id);
        return s == null ? null : s.signature;
    }

    /** True when a screen shows state {@code id} as this build draws it. */
    private boolean showsState(Screen screen, int id) {
        if (script != null) {
            return Screen.sameState(screen.signature, replaySig.get(id));
        }
        StateRec s = stateById(id);
        return s != null && shows(screen, s);
    }

    /**
     * Clean-restarts the app and walks the recorded path to state {@code targetId}, typing its inputs
     * again. Returns the screen when it shows that state, else null. A replay stores a state it reaches
     * this way for the first time. A failed relaunch stops navigation but keeps the states captured so far.
     */
    private Screen reposition(int targetId) throws Exception {
        StateRec target = recorded(targetId);
        if (relaunchFailed || target == null) {
            return null;
        }
        restartCount++;
        // Every screen on the way is known, so one dump that shows it is enough
        int rootId = script != null ? script.rootId : states.isEmpty() ? 0 : states.get(0).id;
        if (!device.startClean(1000, signatureIn(rootId))) {
            relaunchFailed = true;
            String crash = device.crashReason();
            System.out.println("  [explorer] WARNING: app did not return to the foreground on relaunch"
                    + (crash != null ? " (app crashed: " + crash + ")" : "")
                    + "; keeping the " + states.size() + " states captured so far");
            return null;
        }
        Screen screen = device.startScreen();
        if (screen != null && !showsState(screen, rootId)) {
            screen = null;   // not the first screen yet, the next dump waits for it
        }
        for (PathStep step : target.path) {
            StateRec via = recorded(step.stateId);
            if (via != null && !via.inputs.isEmpty()) {
                applyInputs(via);
                screen = null;   // typing may move things, so look again
            }
            if (screen == null) {
                screen = device.stableDump(signatureIn(step.stateId));
            }
            if (screen == null) {
                return null;
            }
            int[] c = screen.center(step.selector);
            if (c == null) {
                return null;
            }
            device.tapAndWait(c[0], c[1]);
            screen = null;
        }
        if (!target.inputs.isEmpty()) {
            applyInputs(target);
            screen = null;
        }
        if (screen == null) {
            screen = device.stableDump(signatureIn(targetId));
        }
        if (screen == null) {
            return null;
        }
        if (signatureIn(targetId) != null) {
            if (!showsState(screen, targetId)) {
                return null;
            }
            if (script == null) {
                actions.add(Action.of(RESTART, 0, null, targetId));
            }
            return screen;
        }
        StateRec rec = recognised(targetId, capture(screen));
        if (rec == null) {
            return null;
        }
        adopt(rec, targetId, false);   // inputs were already typed along the path
        return rec.screen;
    }

    // ================================================================= forms (optional)

    /**
     * Types an LLM-suggested value into each text field so screens behind a form become reachable.
     * The values are recorded and typed verbatim in every language. Returns the screen after typing.
     */
    private Screen fillForms(StateRec state) throws Exception {
        if (opts.llm == null) {
            return state.screen;
        }
        List<UiNode> fields = state.screen.editFields();
        if (fields.isEmpty()) {
            return state.screen;
        }
        List<String> descriptions = new ArrayList<>();
        for (UiNode f : fields) {
            descriptions.add("hint/text: '" + f.text + "', description: '" + f.contentDesc
                    + "', id: '" + Screen.lastSegment(f.resourceId.replace('/', '.')) + "'");
        }
        List<String> values;
        try {
            values = opts.llm.suggestInputValues(descriptions, opts.language);
        } catch (Exception e) {
            System.out.println("  [explorer] WARNING: could not get input values from the LLM: " + e.getMessage());
            return state.screen;
        }
        Screen screen = state.screen;
        for (int i = 0; i < fields.size() && i < values.size(); i++) {
            String value = safeInput(values.get(i));
            if (value.isEmpty()) {
                continue;
            }
            Selector sel = fields.get(i).selector;
            int[] c = screen.editCenter(sel);
            if (c == null) {
                continue;
            }
            device.typeText(c, value);
            Input in = new Input();
            in.field = sel;
            in.value = value;
            state.inputs.add(in);
            System.out.println("  [explorer] typed '" + value + "' into " + sel);
            Screen fresh = device.stableDump();
            if (fresh != null) {
                screen = fresh;
            }
        }
        return screen;
    }

    /** Types the recorded inputs of a state again. */
    private void applyInputs(StateRec state) throws Exception {
        for (Input in : state.inputs) {
            Screen screen = device.stableDump();
            if (screen == null) {
                return;
            }
            int[] c = screen.editCenter(in.field);
            if (c != null) {
                device.typeText(c, in.value);
            }
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
        script = Script.load(scriptPath);
        installAndStart();

        List<String> divergences = new ArrayList<>();
        int reproduced = 0;
        StateRec root = captureCurrent();
        if (root == null) {
            throw new ExplorationException("could not capture the initial state of the " + locale + " build");
        }
        StateRec recordedRoot = script.states.get(script.rootId);
        if (recordedRoot != null && !Screen.sameScreen(recordedRoot.structure, root.structure)) {
            root = settleRoot(recordedRoot, root);
            if (!Screen.sameScreen(recordedRoot.structure, root.structure)) {
                divergences.add("root: the first screen differs from the recorded one (" + root.activity + ", "
                        + Math.round(100 * Screen.structureSimilarity(recordedRoot.structure, root.structure)) + "% of its elements in common)");
            }
        }
        adopt(root, script.rootId, true);
        int current = script.rootId;

        for (Action a : script.actions) {
            if (relaunchFailed) {
                divergences.add("stopped: the app did not come back after a relaunch");
                break;
            }
            if (RESTART.equals(a.type)) {
                if (reposition(a.to) != null) {
                    current = a.to;
                    reproduced++;
                } else {
                    divergences.add("RESTART: could not reach state " + a.to);
                    current = -1;
                }
                continue;
            }
            if (current != a.from) {
                if (reposition(a.from) == null) {
                    divergences.add(a + ": could not get to state " + a.from + " first");
                    current = -1;
                    continue;
                }
                current = a.from;
            }
            if (TAP.equals(a.type)) {
                Screen screen = device.stableDump(replaySig.get(a.from));
                if (screen == null) {
                    current = -1;
                    continue;
                }
                int[] c = screen.center(a.selector);
                if (c == null) {
                    divergences.add("state " + a.from + ": element " + a.selector + " not found");
                    continue;   // still at from
                }
                if (a.to != a.from && replaySig.containsKey(a.to)) {
                    device.tapAndWait(c[0], c[1]);   // the target screen is known and seen as soon as it shows
                } else {
                    device.tap(c[0], c[1]);
                    Device.sleep(TAP_SETTLE_MS);     // same pause as the exploration, for the same answer
                }
                // A known target screen needs one dump, any other outcome waits for the screen to settle.
                // A permission the tap asked for is given, as in the source exploration.
                boolean outside = !device.inAppAfterRequests();
                Screen after = outside ? null : device.stableDump(a.to != a.from ? replaySig.get(a.to) : null);
                // A message the source exploration let go by must not count as a screen here
                if (after != null && a.to == a.from) {
                    after = outlastMessage(after, replaySig.get(a.from));
                }
                if (!outside && after != null && (!device.inApp() || !after.showsPackage(pkg))) {
                    outside = true;   // the other app came to the front during the dump, or its dialog covers the app
                }
                if (outside) {
                    device.backAndWait();
                    if (a.to == 0) {
                        reproduced++;
                    } else {
                        divergences.add("state " + a.from + ": tap " + a.selector + " left the app (expected state " + a.to + ")");
                    }
                    Screen back = device.stableDump(replaySig.get(a.from));
                    current = (back != null && device.inApp() && showsState(back, a.from)) ? a.from : -1;
                    continue;
                }
                if (after == null) {
                    current = -1;
                    continue;
                }
                StateRec rec = capture(after);
                if (a.to == 0) {
                    divergences.add("state " + a.from + ": tap " + a.selector + " stayed in the app (it left it in the source language)");
                    current = showsState(after, a.from) ? a.from : -1;
                } else if (a.to == a.from) {
                    if (showsState(after, a.from)) {
                        reproduced++;
                    } else {
                        divergences.add("state " + a.from + ": tap " + a.selector + " changed the screen (it did not in the source language)");
                        current = -1;
                    }
                } else if (!replaySig.containsKey(a.to)) {
                    StateRec seen = recognised(a.to, rec);
                    if (seen != null) {
                        adopt(seen, a.to, true);
                        addTransition(a.from, a.to);
                        reproduced++;
                        current = a.to;
                    } else {
                        divergences.add("state " + a.from + ": tap " + a.selector + " led to a screen unlike recorded state " + a.to);
                        current = -1;
                    }
                } else if (showsState(after, a.to)) {
                    addTransition(a.from, a.to);
                    reproduced++;
                    current = a.to;
                } else {
                    divergences.add("state " + a.from + ": tap " + a.selector + " led to a different screen than recorded state " + a.to);
                    current = -1;
                }
            } else if (BACK.equals(a.type)) {
                device.backAndWait();
                Screen screen = device.stableDump(replaySig.get(a.to));
                if (screen == null) {
                    current = -1;
                } else if (replaySig.containsKey(a.to)) {
                    if (showsState(screen, a.to)) {
                        reproduced++;
                        current = a.to;
                    } else {
                        divergences.add("BACK from state " + a.from + " did not return to state " + a.to);
                        current = -1;
                    }
                } else {
                    StateRec seen = recognised(a.to, capture(screen));
                    if (seen != null) {
                        adopt(seen, a.to, true);
                        reproduced++;
                        current = a.to;
                    } else {
                        divergences.add("BACK from state " + a.from + " led to a screen unlike recorded state " + a.to);
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
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        writeReplayReport(script.actions.size(), reproduced, divergences, script.states.size(), missing, seconds);
        System.out.println("  [explorer] replay: " + reproduced + "/" + script.actions.size() + " actions reproduced, "
                + divergences.size() + " divergences, " + states.size() + "/" + script.states.size() + " states captured"
                + (missing.isEmpty() ? "" : " (missing " + missing + ")") + ", " + seconds + " s (" + counts() + ")");
        for (String d : divergences) {
            System.out.println("  [explorer] divergence: " + d);
        }
        System.out.println("  [explorer] done: " + states.size() + " states, "
                + transitions.size() + " transitions -> " + outputFolder + File.separator + "result.json");
        device.uninstallQuietly();
        return outputFolder;
    }

    /**
     * The first screen of a replay when it did not match the recorded one. It may still be loading, or
     * a notice covers it that BACK dismisses. BACK may also close the app, which is then started again,
     * so the replay never goes on from outside the app. Returns the best capture.
     */
    private StateRec settleRoot(StateRec recorded, StateRec first) throws Exception {
        Device.sleep(LOOK_AGAIN_MS);
        StateRec again = captureCurrent();
        if (again != null && Screen.sameScreen(recorded.structure, again.structure)) {
            return again;
        }
        device.backAndWait();
        if (device.inApp()) {
            again = captureCurrent();
            if (again != null && Screen.sameScreen(recorded.structure, again.structure)) {
                return again;
            }
        }
        if (!device.startClean(1500, null)) {
            relaunchFailed = true;
            return first;
        }
        again = captureCurrent();
        return again != null ? again : first;
    }

    /**
     * The capture when it shows recorded state {@code id}, looking once more in case it is still
     * settling, else null. A screen unlike the recorded one is not stored under its id.
     */
    private StateRec recognised(int id, StateRec rec) throws Exception {
        StateRec recorded = script.states.get(id);
        if (rec == null || recorded == null || Screen.sameScreen(recorded.structure, rec.structure)) {
            return rec;
        }
        Device.sleep(LOOK_AGAIN_MS);
        StateRec again = captureCurrent();
        return again != null && Screen.sameScreen(recorded.structure, again.structure) ? again : null;
    }

    /** Stores a captured screen as source state {@code id}, with its screenshot, and optionally types its recorded inputs. */
    private void adopt(StateRec rec, int id, boolean typeInputs) throws Exception {
        rec.id = id;
        rec.screenShot = device.screenshot(shotsDir, sanitize(rec.signature) + "_" + id);
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

    // ================================================================= start and output

    private void prepareFolders() {
        new File(outputFolder).mkdirs();
        shotsDir = new File(outputFolder, "screenshots");
        shotsDir.mkdirs();
    }

    /**
     * Installs the build and starts it clean. Monkey can return 0 without launching anything, so an app
     * not in front means a failed launch, and the launcher is not explored.
     */
    private void installAndStart() throws Exception {
        device.install(apkPath);
        device.clearCrashLog();
        if (!device.startClean(1500, null)) {
            String crash = device.crashReason();
            throw new ExplorationException("App '" + pkg + "' did not reach the foreground after launch"
                    + (crash != null ? "; app crashed on launch: " + crash
                                     : "; refusing to explore off-app screens (launch likely failed)."));
        }
    }

    private String counts() {
        return device.taps + " taps, " + device.backs + " BACK, " + restartCount + " restarts, " + device.dumps + " dumps";
    }

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
            sj.put("rawXML", s.screen.xml);
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
            String activity = Screen.nz(s.activity).replace("}", "");
            if (!activity.isEmpty() && !visited.contains(activity)) {
                visited.add(activity);
            }
        }
        summary.put("activities", visited);
        root.put("exploration", summary);
        // crashes of the original app, so a translated build crashing the same way is not blamed on translation
        JSONArray crashes = new JSONArray();
        crashes.addAll(device.crashReasons());
        root.put("crashes", crashes);
        JSONArray st = new JSONArray();
        for (StateRec s : states) {
            JSONObject o = new JSONObject();
            o.put("id", (long) s.id);
            o.put("activity", Screen.nz(s.activity));
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
        crashes.addAll(device.crashReasons());
        o.put("crashes", crashes);
        Files.writeString(Paths.get(outputFolder, REPLAY_REPORT_FILE), o.toJSONString(), StandardCharsets.UTF_8);
    }

    /** Time and device actions spent by this exploration or replay. */
    @SuppressWarnings("unchecked")
    private JSONObject work(long seconds) {
        JSONObject o = new JSONObject();
        o.put("seconds", seconds);
        o.put("taps", (long) device.taps);
        o.put("backs", (long) device.backs);
        o.put("restarts", (long) restartCount);
        o.put("dumps", (long) device.dumps);
        // starts repeated because the app dropped its language, and whether it always did
        o.put("languageRestarts", (long) device.languageRestarts);
        o.put("languageRefused", device.languageRefused);
        return o;
    }

    // ================================================================= small helpers

    static String str(JSONObject o, String key) {
        Object v = o.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    static int toInt(Object o) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
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

    // ================================================================= entry points

    /** Explores the source-language build and records the replay script. */
    public static String exploreSource(String language, String outputFolder, boolean translated, String apkLocation,
            String appName, Options options) throws ExplorationException {
        return run(stateFolder(translated), language, outputFolder, apkLocation, appName, null, options, "");
    }

    /** Explores a translated build by replaying the source script, so its states pair with the source graph. */
    public static String replayLanguage(String language, String outputFolder, boolean translated, String apkLocation,
            String appName, String scriptPath, Options options) throws ExplorationException {
        return run(stateFolder(translated), language, outputFolder, apkLocation, appName, scriptPath, options,
                " (replaying the source path)");
    }

    /**
     * Replays the source script on another build of a language into its own results folder, such as
     * {@code rtlResults} for the build with repaired right-to-left layouts.
     */
    public static String replayInto(String folderName, String language, String outputFolder, String apkLocation,
            String appName, String scriptPath, Options options) throws ExplorationException {
        return run(folderName, language, outputFolder, apkLocation, appName, scriptPath, options,
                " (replaying the source path, " + folderName + ")");
    }

    /**
     * Explores a build, or replays the script on it when there is one, into a fresh folder of the
     * language. Any failure becomes an ExplorationException, so one language failing does not abort the run.
     */
    private static String run(String folderName, String language, String outputFolder, String apkLocation, String appName,
            String scriptPath, Options options, String how) throws ExplorationException {
        try {
            File folder = stateFolder(outputFolder, folderName, language);
            String serial = Device.resolveSerial();
            System.out.println("Exploring '" + appName + "' on device " + serial + " with locale '" + language + "'" + how);
            AdbExplorer explorer = new AdbExplorer(serial, appName, apkLocation, folder.getAbsolutePath(), language, options);
            if (scriptPath == null) {
                explorer.explore();
            } else {
                explorer.replay(scriptPath);
            }
            return folder.getCanonicalPath();
        } catch (ExplorationException e) {
            throw e;
        } catch (Exception e) {
            // the exception type stands in for a missing message
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new ExplorationException("Exploration failed for '" + language + "': " + detail, e);
        }
    }

    private static String stateFolder(boolean translated) {
        return translated ? "trnsResults" : "noTrnsResults";
    }

    /** Fresh per-language output folder under the run's results directory. */
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

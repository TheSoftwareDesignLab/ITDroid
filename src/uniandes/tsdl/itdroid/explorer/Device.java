package uniandes.tsdl.itdroid.explorer;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import uniandes.tsdl.itdroid.explorer.AdbExplorer.ExplorationException;

/**
 * The app under test on an adb device (Android 13+, no root). It installs the app, starts it clean in
 * the language of the run, taps, goes back and reads the screen once it settles, answering the system
 * dialogs that cover it. The static part checks the device and chooses which one the run uses.
 */
public final class Device {

    /** Foreground wait after a launch. A cold start after {@code pm clear} on an emulator can exceed 7 s. */
    private static final int FOREGROUND_WAIT_SECONDS = 15;
    /**
     * Longest wait for the focused window to change after a BACK or a tap towards a known screen.
     * Changes inside one window need no wait, since uiautomator dumps only after a quiet second.
     */
    private static final long ACTION_WAIT_MS = 400;
    /** Pause between the two dumps that must agree for a screen to count as settled. */
    private static final long STABLE_INTERVAL_MS = 600;
    private static final String DUMP_FILE = "/sdcard/itdroid_dump.xml";
    /** Most permission requests answered in a row after one tap, as an app may ask for several. */
    private static final int MAX_REQUESTS = 3;
    /**
     * Runtime permissions, granted after each reset with {@code exploration.grantPermissions}, because
     * {@code pm clear} revokes them.
     */
    private static final List<String> RUNTIME_PERMISSIONS = Arrays.asList(
            "POST_NOTIFICATIONS", "CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
            "ACCESS_BACKGROUND_LOCATION", "READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS", "READ_CALENDAR",
            "WRITE_CALENDAR", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES",
            "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "READ_PHONE_STATE", "CALL_PHONE", "READ_CALL_LOG",
            "WRITE_CALL_LOG", "SEND_SMS", "RECEIVE_SMS", "READ_SMS", "BODY_SENSORS", "ACTIVITY_RECOGNITION",
            "NEARBY_WIFI_DEVICES", "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE");
    /** The allow button of a system permission request. */
    private static final Pattern PERMISSION_ALLOW = Pattern.compile(
            "resource-id=\"[^\"]*:id/permission_allow[a-z_]*button\"[^>]*?bounds=\"(\\[\\d+,\\d+\\]\\[\\d+,\\d+\\])\"");
    /** The Wait button of the Android dialog shown when an app does not respond. */
    private static final Pattern NOT_RESPONDING_WAIT = Pattern.compile(
            "resource-id=\"android:id/aerr_wait\"[^>]*?bounds=\"(\\[\\d+,\\d+\\]\\[\\d+,\\d+\\])\"");

    private final String serial;
    private final String pkg;
    /** The language of the app as BCP 47, empty to leave the one of the device. */
    private final String locale;
    private final AdbExplorer.Options opts;
    /** Shell command granting the runtime permissions, built once and reused on every reset. */
    private String grantCommand;
    private boolean setupShown;
    /** Settled screen after the last clean start, null when it did not settle. */
    private Screen startScreen;

    /** Device actions so far, for the report of the exploration. */
    int taps;
    int backs;
    int dumps;
    /** Clean starts repeated because the app dropped its language while starting. */
    int languageRestarts;
    /** Set when the app dropped the language on the repeated start too. */
    boolean languageRefused;

    Device(String serial, String pkg, String locale, AdbExplorer.Options opts) {
        this.serial = serial;
        this.pkg = pkg;
        this.locale = locale;
        this.opts = opts;
    }

    // ================================================================= install and start

    void install(String apkPath) throws Exception {
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
    void uninstallQuietly() {
        try {
            uninstall(serial, pkg);
        } catch (Exception e) {
            System.out.println("  [explorer] WARNING: could not uninstall " + pkg + ": " + e.getMessage());
        }
    }

    /** Empties the crash log, so a crash found later belongs to this run. */
    void clearCrashLog() throws Exception {
        runAdb(8000, "logcat", "-b", "crash", "-c");
    }

    /**
     * Starts the app from scratch and keeps the settled screen it shows first, see {@link #startScreen}.
     * False when the app did not come to the foreground. AppCompat with {@code autoStoreLocales} drops
     * the system language on the first start after install, so the start is repeated once when that happens.
     */
    boolean startClean(long pauseMs, String expected) throws Exception {
        startScreen = null;
        for (int attempt = 0; ; attempt++) {
            cleanReset();
            launch();
            sleep(pauseMs);
            if (!waitForForeground()) {
                return false;
            }
            startScreen = settleAfterLaunch(expected);
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

    /** The settled screen after the last clean start, null when it did not settle. */
    Screen startScreen() {
        return startScreen;
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

    /** Wipes app data and restores locale, setup content and, when asked, permissions, so each start is pristine. */
    private void cleanReset() throws Exception {
        runAdb(15000, "shell", "pm", "clear", pkg);   // clears data and cache and stops the app
        setAppLocale();                               // pm clear also resets the per-app locale
        if (opts.grantPermissions) {
            grantRuntimePermissions();                // and revokes the runtime permissions
        }
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
     * non-reproducible. A dump showing {@code expected} ends the wait. Returns the settled screen.
     */
    private Screen settleAfterLaunch(String expected) throws Exception {
        if (opts.settleSeconds > 0) {
            sleep(opts.settleSeconds * 1000L);
        }
        return stableDump(1500, expected);
    }

    // ================================================================= actions

    void tap(int x, int y) throws Exception {
        taps++;
        runAdb(8000, "shell", "input", "tap", String.valueOf(x), String.valueOf(y));
    }

    private void back() throws Exception {
        backs++;
        runAdb(8000, "shell", "input", "keyevent", "KEYCODE_BACK");
    }

    /** Taps and gives a new window the time to come to the front. */
    void tapAndWait(int x, int y) throws Exception {
        String before = focusedWindow();
        tap(x, y);
        waitForFocusChange(before);
    }

    void backAndWait() throws Exception {
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

    /** Types a value into the text field at a point and closes the keyboard. */
    void typeText(int[] center, String value) throws Exception {
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

    /** Writes a screenshot named {@code name} into a folder and returns its path, empty when it failed. */
    String screenshot(File folder, String name) {
        Process p = null;
        try {
            File png = new File(folder, name + ".png");
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

    // ================================================================= what is in front

    /** True when the foreground activity belongs to the app under test. */
    boolean inApp() throws Exception {
        String top = topActivityLine(serial);
        return top != null && top.contains(pkg + "/");
    }

    /**
     * Whether the app is in front once the permission requests a tap made are answered Allow, as a
     * new user would. The screens of the app that explain a permission are then seen before and after.
     */
    boolean inAppAfterRequests() throws Exception {
        for (int answered = 0; ; answered++) {
            String top = topActivityLine(serial);
            if (answered < MAX_REQUESTS && top != null && top.contains("GrantPermissionsActivity")) {
                acceptPermissionDialog(serial);
                continue;
            }
            return top != null && top.contains(pkg + "/");
        }
    }

    String currentActivity() throws Exception {
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

    /** Taps the allow button of a system permission dialog on screen, if any. */
    private static void acceptPermissionDialog(String serial) throws Exception {
        String xml = dumpHierarchy(serial);
        if (xml != null) {
            allow(serial, xml);
        }
    }

    /** Taps the allow button of the permission request a dump shows, true when it shows one. */
    private static boolean allow(String serial, String xml) throws Exception {
        Matcher m = PERMISSION_ALLOW.matcher(xml);
        int[] c = m.find() ? Screen.boundsCenter(m.group(1)) : null;
        if (c == null) {
            return false;
        }
        System.out.println("  [explorer] accepting a system permission dialog");
        execAdb(serial, 8000, "shell", "input", "tap", String.valueOf(c[0]), String.valueOf(c[1]));
        sleep(700);
        return true;
    }

    /**
     * Taps Wait when the dump is the not-responding dialog a slow emulator shows, and returns true then.
     * The dialog is not an activity, so it would otherwise be stored as a screen of the app.
     */
    private boolean dismissNotResponding(String xml) throws Exception {
        Matcher m = NOT_RESPONDING_WAIT.matcher(xml);
        if (!m.find()) {
            return false;
        }
        int[] c = Screen.boundsCenter(m.group(1));
        if (c == null) {
            return false;
        }
        System.out.println("  [explorer] the system's \"not responding\" dialog covered the screen: dismissed");
        runAdb(8000, "shell", "input", "tap", String.valueOf(c[0]), String.valueOf(c[1]));
        return true;
    }

    String crashReason() throws Exception {
        return crashReason(serial, pkg);
    }

    /** The distinct exceptions of every app crash since this run cleared the crash log. */
    List<String> crashReasons() {
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

    // ================================================================= reading the screen

    /** One dump of the screen, null when uiautomator could not write it. */
    Screen dump() throws Exception {
        String xml = dumpHierarchy(serial);
        dumps++;
        return xml == null ? null : Screen.parse(xml);
    }

    Screen stableDump() throws Exception {
        return stableDump(STABLE_INTERVAL_MS, null);
    }

    /** The settled screen, returned at once when it shows {@code expected}, the signature of a known screen. */
    Screen stableDump(String expected) throws Exception {
        return stableDump(STABLE_INTERVAL_MS, expected);
    }

    /**
     * Dumps the UI until two dumps {@code intervalMs} apart share a signature, falling back to the last
     * dump after a few attempts. {@code expected} is the signature of a known screen, or null, and a dump
     * showing it is returned at once. A system dialog over the app is answered and looked past.
     */
    private Screen stableDump(long intervalMs, String expected) throws Exception {
        String previous = null;
        Screen screen = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            String xml = dumpHierarchy(serial);
            dumps++;
            if (xml == null) {
                return null;
            }
            screen = Screen.parse(xml);
            if (dismissNotResponding(xml) || allow(serial, xml)) {
                previous = null;   // the dump was a system dialog, not the app
                sleep(intervalMs);
                continue;
            }
            if (screen.signature.equals(previous) || screen.signature.equals(expected)) {
                return screen;   // two identical structures in a row, or the known one
            }
            previous = screen.signature;
            sleep(intervalMs);
        }
        return screen;
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

    // ================================================================= the device of the run

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
                + (out != null && out.contains("Success") ? "" : " (" + Screen.nz(out).trim() + ")"));
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
        return Screen.nz(execAdb(serial, 8000, "shell", "getprop", name)).trim();
    }

    /** API level of the device, or -1 when it cannot be read. */
    public static int deviceSdk(String serial) throws Exception {
        String s = execAdb(serial, 8000, "shell", "getprop", "ro.build.version.sdk");
        try {
            return Integer.parseInt(Screen.nz(s).trim());
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
        for (String a : Screen.nz(s).split(",")) {
            if (!a.trim().isEmpty()) {
                abis.add(a.trim());
            }
        }
        return abis;
    }

    /** AVD name or serial named in the config, empty for the only device connected. */
    public static volatile String preferredDevice = "";

    /** The device chosen by the first call of {@link #resolveSerial()}, kept for the rest of the run. */
    private static volatile String resolvedSerial;

    /**
     * Target device serial, the connected device whose serial or AVD name is {@link #preferredDevice}
     * (the emulator key of the config), else {@code ANDROID_SERIAL} if set, else the only device connected.
     */
    public static String resolveSerial() throws Exception {
        if (resolvedSerial == null) {
            resolvedSerial = findSerial();
        }
        return resolvedSerial;
    }

    private static String findSerial() throws Exception {
        String wanted = preferredDevice == null ? "" : preferredDevice.trim();
        String env = System.getenv("ANDROID_SERIAL");
        if (wanted.isEmpty() && env != null && !env.isEmpty()) {
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
        if (!wanted.isEmpty()) {
            List<String> seen = new ArrayList<>();
            for (String d : devices) {
                String avd = d.startsWith("emulator-") ? avdName(d) : "";
                if (d.equals(wanted) || avd.equalsIgnoreCase(wanted)) {
                    return d;
                }
                seen.add(avd.isEmpty() ? d : d + " (" + avd + ")");
            }
            throw new IllegalStateException("The device '" + wanted + "' named by emulator in the config is not connected. Connected "
                    + seen + ". Start it, or leave emulator empty to use the only device connected.");
        }
        String chosen = devices.get(0);
        if (devices.size() > 1) {
            // Arbitrary pick when several devices are attached, so warn about it
            System.out.println("  [explorer] WARNING: " + devices.size() + " adb devices found " + devices
                    + ", none named by emulator in the config, using " + chosen);
        }
        return chosen;
    }

    /** The AVD name of an emulator, Test4 for emulator-5554, or empty when it does not say. */
    private static String avdName(String serial) {
        try {
            Process p = new ProcessBuilder("adb", "-s", serial, "emu", "avd", "name").redirectErrorStream(true).start();
            String first;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                first = r.readLine();
            }
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            return first == null ? "" : first.trim();
        } catch (Exception e) {
            return "";
        }
    }

    // ================================================================= adb

    private String runAdb(long timeoutMs, String... args) throws Exception {
        return execAdb(serial, timeoutMs, args);
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

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

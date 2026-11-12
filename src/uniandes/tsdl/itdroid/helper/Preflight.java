package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import uniandes.tsdl.itdroid.explorer.AdbExplorer;

/**
 * Phase 0: decides whether the app can be processed at all BEFORE spending minutes on translation
 * and exploration. Every failure is an {@link ITDroidException} with the concrete reason, so a
 * batch can classify it instead of discovering it half-way as a confusing install/explore error.
 */
public class Preflight {

    /** Per-app locales ({@code cmd locale set-app-locales}) exist since Android 13. */
    public static final int MIN_DEVICE_SDK = 33;

    /** Device checks: Android version and supported ABIs. */
    @SuppressWarnings("unchecked")
    public static JSONObject checkDevice(String serial) throws Exception {
        int sdk = AdbExplorer.deviceSdk(serial);
        List<String> abis = AdbExplorer.deviceAbis(serial);
        System.out.println("PREFLIGHT: device " + serial + ", API " + sdk + ", ABIs " + abis);
        if (sdk < MIN_DEVICE_SDK) {
            throw new ITDroidException("PREFLIGHT: the device runs API " + sdk + "; ITDroid needs Android 13 (API "
                    + MIN_DEVICE_SDK + ") or newer to switch the app language without root.");
        }
        JSONObject o = new JSONObject();
        o.put("serial", serial);
        o.put("sdk", (long) sdk);
        JSONArray a = new JSONArray();
        a.addAll(abis);
        o.put("abis", a);
        // Static facts for the report: which device/emulator image the app was evaluated on.
        o.put("model", AdbExplorer.deviceProp(serial, "ro.product.model"));
        o.put("manufacturer", AdbExplorer.deviceProp(serial, "ro.product.manufacturer"));
        o.put("androidVersion", AdbExplorer.deviceProp(serial, "ro.build.version.release"));
        return o;
    }

    /** ABIs the APK ships native code for (empty when it is pure Java/Kotlin). */
    public static List<String> apkAbis(String apkPath) throws IOException {
        Set<String> abis = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(apkPath)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                String name = en.nextElement().getName();
                if (name.startsWith("lib/") && name.endsWith(".so")) {
                    String[] parts = name.split("/");
                    if (parts.length >= 3) {
                        abis.add(parts[1]);
                    }
                }
            }
        }
        return new ArrayList<>(abis);
    }

    /** An app with native code for ABIs the device lacks cannot even be installed. */
    public static void checkAbis(List<String> apkAbis, List<String> deviceAbis) throws ITDroidException {
        if (apkAbis.isEmpty()) {
            return;
        }
        for (String a : apkAbis) {
            if (deviceAbis.contains(a)) {
                System.out.println("PREFLIGHT: APK native code " + apkAbis + " is compatible with the device (" + a + ")");
                return;
            }
        }
        throw new ITDroidException("PREFLIGHT: the APK only ships native code for " + apkAbis + " but the device supports "
                + deviceAbis + " (e.g. an ARM-only app on an x86_64 emulator); it cannot be installed here.");
    }

    /**
     * Facts of the decoded app, read without a device: its package, minSdk and targetSdk, whether
     * it has a launchable activity, whether it is a Trusted Web Activity, whether it has a default
     * strings.xml. Rejects nothing: the static analysis runs on any app that decodes.
     */
    @SuppressWarnings("unchecked")
    public static JSONObject describeDecodedApp(String decodedPath) throws Exception {
        JSONObject o = new JSONObject();
        o.put("minSdk", (long) readSdkFromApktoolYml(decodedPath, "minSdkVersion"));
        o.put("targetSdk", (long) readSdkFromApktoolYml(decodedPath, "targetSdkVersion"));

        File manifest = new File(decodedPath, "AndroidManifest.xml");
        String manifestText = manifest.isFile() ? Files.readString(manifest.toPath(), StandardCharsets.UTF_8) : "";
        o.put("trustedWebActivity", manifestText.contains("android.support.customtabs.trusted")
                || manifestText.contains("com.google.androidbrowserhelper"));
        o.put("launchable", hasLauncher(manifestText));
        Matcher pkg = Pattern.compile("<manifest[^>]*\\spackage=\"([^\"]+)\"").matcher(manifestText);
        o.put("package", pkg.find() ? pkg.group(1) : "");

        File strings = new File(decodedPath, "res" + File.separator + "values" + File.separator + "strings.xml");
        o.put("hasStrings", strings.isFile());
        return o;
    }

    /**
     * Checks that the app described by {@link #describeDecodedApp} can be explored on the device:
     * minSdk within the device's API level, a launchable activity, and not a Trusted Web Activity
     * (its screens belong to the browser). An app without a default strings.xml is accepted: its
     * hardcoded strings are still detected and translated.
     */
    public static void checkDecodedApp(JSONObject app, int deviceSdk) throws Exception {
        long minSdk = ((Number) app.get("minSdk")).longValue();
        if (minSdk > 0 && deviceSdk > 0 && minSdk > deviceSdk) {
            throw new ITDroidException("PREFLIGHT: the app requires API " + minSdk + " but the device runs API " + deviceSdk + ".");
        }
        if (Boolean.TRUE.equals(app.get("trustedWebActivity"))) {
            throw new ITDroidException("PREFLIGHT: this app is a Trusted Web Activity (a packaged web site). Its screens are "
                    + "rendered by the browser, so there is nothing of the app itself to internationalize: out of scope.");
        }
        if (!Boolean.TRUE.equals(app.get("launchable"))) {
            throw new ITDroidException("PREFLIGHT: the manifest declares no launchable activity (MAIN + LAUNCHER); "
                    + "the app cannot be started for exploration.");
        }
        System.out.println("PREFLIGHT: app minSdk " + minSdk + ", targetSdk " + app.get("targetSdk") + ", launchable activity found, "
                + (Boolean.TRUE.equals(app.get("hasStrings")) ? "strings.xml present"
                        : "no default strings.xml (only its hardcoded strings can be translated)"));
    }

    /** Reads minSdkVersion/targetSdkVersion from apktool.yml (written by apktool when decoding). */
    private static int readSdkFromApktoolYml(String decodedPath, String key) {
        File yml = new File(decodedPath, "apktool.yml");
        if (!yml.isFile()) {
            return -1;
        }
        try {
            String text = Files.readString(yml.toPath(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile(key + ":\\s*'?(\\d+)'?").matcher(text);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        } catch (Exception ignored) {
            // unreadable: not fatal, the install will tell
        }
        return -1;
    }

    /**
     * True when some activity or activity-alias has an intent filter with MAIN + LAUNCHER. A plain
     * text scan of the decoded manifest: both must appear inside the same intent-filter element.
     */
    static boolean hasLauncher(String manifestText) {
        Matcher m = Pattern.compile("(?s)<intent-filter.*?</intent-filter>").matcher(manifestText);
        while (m.find()) {
            String filter = m.group();
            if (filter.contains("android.intent.action.MAIN") && filter.contains("android.intent.category.LAUNCHER")) {
                return true;
            }
        }
        return false;
    }
}

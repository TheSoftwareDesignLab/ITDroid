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

import uniandes.tsdl.itdroid.explorer.Device;

/**
 * Decides whether the app can be processed at all before translation and exploration start. Each
 * failure is an {@link ITDroidException} with the concrete reason, so a batch can classify it.
 */
public class Preflight {

    /** Per-app locales exist since Android 13. */
    public static final int MIN_DEVICE_SDK = 33;

    /**
     * Apps targeting Android 5.1 or older open behind a permission review after every clean start,
     * and Android 14 refuses to install them.
     */
    public static final int MIN_TARGET_SDK = 23;

    /** Checks the Android version of the device and describes it for the report. */
    @SuppressWarnings("unchecked")
    public static JSONObject checkDevice(String serial) throws Exception {
        int sdk = Device.deviceSdk(serial);
        List<String> abis = Device.deviceAbis(serial);
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
        o.put("model", Device.deviceProp(serial, "ro.product.model"));
        o.put("manufacturer", Device.deviceProp(serial, "ro.product.manufacturer"));
        o.put("androidVersion", Device.deviceProp(serial, "ro.build.version.release"));
        return o;
    }

    /** ABIs the APK ships native code for, empty for pure Java or Kotlin. */
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

    /** Facts of the decoded app read without a device. It rejects nothing. */
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
        Matcher activity = Pattern.compile("<activity[\\s>]").matcher(manifestText);
        long activities = 0;
        while (activity.find()) {
            activities++;
        }
        o.put("activities", activities);   // what the exploration is measured against
        // cross-platform frameworks keep their texts where ITDroid does not translate
        org.json.simple.JSONArray frameworks = new org.json.simple.JSONArray();
        frameworks.addAll(AppFramework.detect(decodedPath));
        o.put("frameworks", frameworks);
        Matcher pkg = Pattern.compile("<manifest[^>]*\\spackage=\"([^\"]+)\"").matcher(manifestText);
        o.put("package", pkg.find() ? pkg.group(1) : "");

        File strings = new File(decodedPath, "res" + File.separator + "values" + File.separator + "strings.xml");
        o.put("hasStrings", strings.isFile());
        return o;
    }

    /**
     * Checks that the app from {@link #describeDecodedApp} can be explored on the device. A Trusted
     * Web Activity is rejected since the browser draws its screens.
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
        // an app that does not say its targetSdk targets its minSdk
        long targetSdk = app.get("targetSdk") instanceof Number ? ((Number) app.get("targetSdk")).longValue() : -1;
        long target = targetSdk > 0 ? targetSdk : minSdk;
        if (target > 0 && target < MIN_TARGET_SDK) {
            throw new ITDroidException("PREFLIGHT: the app was built for Android 5.1 or older (targetSdk " + target + "). On Android 13 "
                    + "it opens behind a review of its permissions and a notice that it was made for an older version of Android, "
                    + "which ITDroid does not answer, and Android 14 and later do not install it: it cannot be evaluated.");
        }
        System.out.println("PREFLIGHT: app minSdk " + minSdk + ", targetSdk " + app.get("targetSdk") + ", launchable activity found, "
                + (Boolean.TRUE.equals(app.get("hasStrings")) ? "strings.xml present"
                        : "no default strings.xml (only its hardcoded strings can be translated)"));
    }

    /** Reads an SDK version from the apktool.yml that apktool writes when decoding. */
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
            // not fatal, the install will tell
        }
        return -1;
    }

    /** True when one intent-filter of the manifest text has both MAIN and LAUNCHER. */
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

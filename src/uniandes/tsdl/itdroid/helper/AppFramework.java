package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects the cross-platform framework an app is built with from the decoded APK. Such apps keep
 * their interface texts outside the resources and Dalvik code, so ITDroid does not translate them.
 */
public final class AppFramework {

    /** Each framework ITDroid recognises, with where it keeps the texts of the interface. */
    private static final Map<String, String> WHERE = new LinkedHashMap<>();
    static {
        WHERE.put("flutter", "Flutter: the interface is drawn by its own engine and its texts are in the compiled Dart code (lib/<abi>/libapp.so)");
        WHERE.put("react-native", "React Native: the texts are in the JavaScript bundle (assets/index.android.bundle)");
        WHERE.put("capacitor", "Capacitor: the interface is a web page inside the app (assets/public)");
        WHERE.put("cordova", "Cordova: the interface is a web page inside the app (assets/www)");
        WHERE.put("compose-multiplatform", "Compose Multiplatform resources: some or all of the texts are in assets/composeResources, not in res/values");
        WHERE.put("xamarin", "Xamarin or .NET MAUI: the texts are in the .NET assemblies");
        WHERE.put("unity", "Unity: the interface is drawn by the game engine");
    }

    private AppFramework() {
    }

    /** Ids of the frameworks found in the decoded app, empty for plain Android. */
    public static List<String> detect(String decodedPath) {
        File root = new File(decodedPath);
        File assets = new File(root, "assets");
        List<String> found = new ArrayList<>();
        if (hasLibrary(root, "libflutter.so") || new File(assets, "flutter_assets").isDirectory()) {
            found.add("flutter");
        }
        if (new File(assets, "index.android.bundle").isFile() || hasLibrary(root, "libreactnativejni.so")
                || hasLibrary(root, "libreactnative.so") || hasLibrary(root, "libhermes.so")) {
            found.add("react-native");
        }
        if (new File(assets, "capacitor.config.json").isFile() || new File(assets, "capacitor.plugins.json").isFile()) {
            found.add("capacitor");
        }
        File www = new File(assets, "www");
        if (new File(www, "cordova.js").isFile() || new File(www, "cordova_plugins.js").isFile()) {
            found.add("cordova");
        }
        if (new File(assets, "composeResources").isDirectory()) {
            found.add("compose-multiplatform");
        }
        if (hasLibrary(root, "libmonodroid.so") || hasLibrary(root, "libmonosgen-2.0.so") || new File(root, "assemblies").isDirectory()) {
            found.add("xamarin");
        }
        if (hasLibrary(root, "libunity.so")) {
            found.add("unity");
        }
        return found;
    }

    /** One sentence per framework saying where its texts are. */
    public static String describe(List<String> frameworks) {
        List<String> parts = new ArrayList<>();
        for (String id : frameworks) {
            parts.add(WHERE.getOrDefault(id, id));
        }
        return String.join("; ", parts);
    }

    /** True when some ABI folder under lib has the library. */
    private static boolean hasLibrary(File root, String name) {
        File[] abis = new File(root, "lib").listFiles(File::isDirectory);
        if (abis == null) {
            return false;
        }
        for (File abi : abis) {
            if (new File(abi, name).isFile()) {
                return true;
            }
        }
        return false;
    }
}

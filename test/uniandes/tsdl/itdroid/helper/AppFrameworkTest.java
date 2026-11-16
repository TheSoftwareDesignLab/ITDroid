package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import uniandes.tsdl.itdroid.Fixtures;

/**
 * Which framework an app is built with, from its decoded files. Such apps keep their texts outside
 * Android resources and are not translated on screen.
 */
class AppFrameworkTest {

    @TempDir
    Path tmp;

    private Path app(String name, String... files) throws IOException {
        Path root = Files.createDirectories(tmp.resolve(name));
        for (String file : files) {
            Path target = root.resolve(file);
            if (file.endsWith("/")) {
                Files.createDirectories(target);
            } else {
                Files.createDirectories(target.getParent());
                Files.writeString(target, "");
            }
        }
        return root;
    }

    @Test
    void aPlainAndroidAppHasNone() throws IOException {
        Path plain = app("plain", "AndroidManifest.xml", "res/values/strings.xml", "smali/org/example/Main.smali",
                "lib/x86_64/libsqlite.so", "assets/fonts/roboto.ttf");

        assertEquals(List.of(), AppFramework.detect(plain.toString()));
        assertEquals(List.of(), AppFramework.detect(Fixtures.decodedApp().toString()), "the shop of the fixtures is plain Android");
    }

    @Test
    void eachFrameworkIsKnownByTheFilesItLeavesInTheApk() throws IOException {
        assertEquals(List.of("flutter"), AppFramework.detect(app("flutter", "lib/x86_64/libflutter.so", "lib/x86_64/libapp.so").toString()));
        assertEquals(List.of("flutter"), AppFramework.detect(app("flutter-assets", "assets/flutter_assets/").toString()));
        assertEquals(List.of("react-native"), AppFramework.detect(app("rn", "assets/index.android.bundle").toString()));
        assertEquals(List.of("react-native"), AppFramework.detect(app("rn-hermes", "lib/arm64-v8a/libhermes.so").toString()));
        assertEquals(List.of("capacitor"), AppFramework.detect(app("capacitor", "assets/capacitor.config.json", "assets/public/index.html").toString()));
        assertEquals(List.of("cordova"), AppFramework.detect(app("cordova", "assets/www/cordova.js", "assets/www/index.html").toString()));
        assertEquals(List.of("compose-multiplatform"), AppFramework.detect(app("kmp", "assets/composeResources/app/values/strings.cvr").toString()));
        assertEquals(List.of("xamarin"), AppFramework.detect(app("maui", "lib/x86_64/libmonodroid.so").toString()));
        assertEquals(List.of("unity"), AppFramework.detect(app("unity", "lib/arm64-v8a/libunity.so").toString()));
    }

    @Test
    void anAppCanCarryMoreThanOne() throws IOException {
        Path both = app("both", "assets/capacitor.plugins.json", "assets/composeResources/");

        assertEquals(List.of("capacitor", "compose-multiplatform"), AppFramework.detect(both.toString()));
    }

    @Test
    void theDescriptionSaysWhereTheTextsAre() {
        String text = AppFramework.describe(List.of("capacitor", "flutter"));

        assertTrue(text.contains("Capacitor") && text.contains("assets/public"), text);
        assertTrue(text.contains("Flutter") && text.contains("Dart"), text);
        assertEquals("", AppFramework.describe(List.of()));
    }
}

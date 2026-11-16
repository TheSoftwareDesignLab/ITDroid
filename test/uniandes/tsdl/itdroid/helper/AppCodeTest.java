package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import uniandes.tsdl.itdroid.Fixtures;

/** Where the app's own code is, when the applicationId does not say. */
class AppCodeTest {

    @TempDir
    Path tmp;

    @AfterEach
    void clearTheExtraRoots() {
        AppCode.setExtraRoots(null);
    }

    /** Writes an empty class so that its package exists in the given dex folder. */
    private void smali(String dex, String className) throws IOException {
        Path file = tmp.resolve(dex).resolve(className.replace('.', '/') + ".smali");
        Files.createDirectories(file.getParent());
        Files.writeString(file, ".class public L" + className.replace('.', '/') + ";\n.super Ljava/lang/Object;\n", StandardCharsets.UTF_8);
    }

    private void manifest(String packageName, String application, String launcher, String... others) throws IOException {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"" + packageName + "\">\n"
                + "  <application" + (application == null ? "" : " android:name=\"" + application + "\"") + ">\n"
                + "    <activity android:name=\"" + launcher + "\"><intent-filter>\n"
                + "      <action android:name=\"android.intent.action.MAIN\"/>\n"
                + "      <category android:name=\"android.intent.category.LAUNCHER\"/>\n"
                + "    </intent-filter></activity>\n");
        for (String other : others) {
            sb.append("    <service android:name=\"").append(other).append("\"/>\n");
        }
        sb.append("  </application>\n</manifest>\n");
        Files.writeString(tmp.resolve("AndroidManifest.xml"), sb.toString(), StandardCharsets.UTF_8);
    }

    private List<String> roots(String applicationId) {
        return AppCode.packageRoots(tmp.toString(), applicationId);
    }

    @Test
    void aFlavourSuffixInTheApplicationIdDoesNotHideTheRestOfTheCode() {
        // applicationId com.example.shop.free, application class and launcher under com.example.shop
        assertEquals(List.of("com.example.shop"), AppCode.packageRoots(Fixtures.decodedApp().toString(), "com.example.shop.free"));

        List<File> files = AppCode.smaliFiles(Fixtures.decodedApp().toString(), "com.example.shop.free");
        assertEquals(List.of("CartRepository.smali", "FreeActivity.smali", "MainActivity.smali", "CartScreenKt.smali"),
                files.stream().map(File::getName).toList(), "dex folders in order, then by path");
    }

    @Test
    void theNamespaceIsFoundWhenItIsUnrelatedToTheApplicationId() throws IOException {
        // like org.birthdayadapter, whose code is under fr.heinisch.birthdayadapter
        smali("smali", "fr.heinisch.birthdayadapter.MainActivity");
        smali("smali", "fr.heinisch.birthdayadapter.service.SyncService");
        smali("smali", "androidx.core.app.CoreComponentFactory");
        manifest("org.birthdayadapter", null, "fr.heinisch.birthdayadapter.MainActivity", "fr.heinisch.birthdayadapter.service.SyncService",
                "androidx.work.impl.background.systemjob.SystemJobService");

        assertEquals(List.of("fr.heinisch.birthdayadapter"), roots("org.birthdayadapter"));
        assertEquals(2, AppCode.smaliFiles(tmp.toString(), "org.birthdayadapter").size(), "library code is left out");
    }

    @Test
    void aPackageThatIsNotAReversedDomainBelongsToItsFirstSegment() throws IOException {
        // like net.slions.fulguris.full.fdroid, whose code is under fulguris
        smali("smali", "fulguris.App");
        smali("smali_classes2", "fulguris.activity.MainActivity");
        smali("smali", "fulguris.settings.fragment.RootFragment");
        manifest("net.slions.fulguris.full.fdroid", "fulguris.App", "fulguris.activity.MainActivity");

        assertEquals(List.of("fulguris"), roots("net.slions.fulguris.full.fdroid"));
        assertEquals(3, AppCode.smaliFiles(tmp.toString(), "net.slions.fulguris.full.fdroid").size());
    }

    @Test
    void aBuildConfigThatDeclaresTheApplicationIdGivesTheNamespace() throws IOException {
        smali("smali", "com.vendor.product.core.Engine");
        Path buildConfig = tmp.resolve("smali/com/vendor/product/BuildConfig.smali");
        Files.writeString(buildConfig, ".class public final Lcom/vendor/product/BuildConfig;\n.super Ljava/lang/Object;\n\n"
                + ".field public static final APPLICATION_ID:Ljava/lang/String; = \"com.vendor.product.pro\"\n", StandardCharsets.UTF_8);
        // no manifest, only the BuildConfig says where the code is

        assertEquals(List.of("com.vendor.product"), roots("com.vendor.product.pro"));
    }

    @Test
    void aLibraryThatLaunchesTheAppIsNotTakenForTheApp() throws IOException {
        // Flutter-style, the launcher is the app's and the application class is the framework's
        smali("smali", "com.acme.notes.MainActivity");
        smali("smali", "io.flutter.app.FlutterApplication");
        manifest("com.acme.notes", "io.flutter.app.FlutterApplication", "com.acme.notes.MainActivity");

        assertEquals(List.of("com.acme.notes"), roots("com.acme.notes"));
    }

    @Test
    void authorsThatShareAHostingDomainAreToldApart() throws IOException {
        smali("smali", "io.github.alice.todo.MainActivity");
        smali("smali", "io.github.bob.charts.ChartView");
        manifest("io.github.alice.todo", null, "io.github.alice.todo.MainActivity");

        assertEquals(List.of("io.github.alice.todo"), roots("io.github.alice.todo"));
        assertEquals("io.github.alice", AppCode.familyPrefix("io.github.alice.todo.ui"));
        assertEquals("fr.heinisch", AppCode.familyPrefix("fr.heinisch.birthdayadapter.service"));
        assertEquals("fulguris", AppCode.familyPrefix("fulguris.activity"));
    }

    @Test
    void extraRootsFromTheConfigurationAreAdded() throws IOException {
        smali("smali", "com.acme.notes.MainActivity");
        smali("smali", "org.acmelib.editor.EditorView");
        manifest("com.acme.notes", null, "com.acme.notes.MainActivity");
        assertEquals(List.of("com.acme.notes"), roots("com.acme.notes"));

        AppCode.setExtraRoots("org.acmelib.editor, com.acme.missing");

        assertEquals(List.of("com.acme.notes", "org.acmelib.editor"), roots("com.acme.notes"), "a root without code is dropped");
    }

    @Test
    void whenNothingIsFoundTheApplicationIdIsReturnedAlone() {
        assertEquals(List.of("com.nothing.here"), roots("com.nothing.here"));
        assertTrue(AppCode.smaliFiles(tmp.toString(), "com.nothing.here").isEmpty());
    }

    @Test
    void tellsWhetherAClassBelongsToTheApp() {
        List<String> appRoots = List.of("com.example.shop");
        assertTrue(AppCode.isUnder(appRoots, "com/example/shop/ui/MainActivity"));
        assertFalse(AppCode.isUnder(appRoots, "com/example/shopping/Cart"));
        assertFalse(AppCode.isUnder(appRoots, "android/widget/TextView"));
        assertEquals("com.example", AppCode.commonPackage("com.example.shop.ui", "com.example.pay"));
        assertEquals("", AppCode.commonPackage("com.example", "org.example"));
    }
}

package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How ITDroid fixes the attributes aapt2 rejects and handles the APK files of a build. The build
 * itself needs apktool and is checked on real apps.
 */
class APKToolWrapperTest {

    @TempDir
    Path tmp;

    private Path decoded;
    private Path manifest;

    private Path write(String relative, String content) throws Exception {
        Path file = decoded.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private void decodeAnApp() throws Exception {
        decoded = Files.createDirectories(tmp.resolve("temp"));
        manifest = write("AndroidManifest.xml", "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"org.tasks\">\n"
                + "    <application android:allowBackup=\"true\" android:label=\"@string/app_name\" android:pageSizeCompat=\"enabled\" android:supportsRtl=\"true\">\n"
                + "        <activity android:name=\".Main\" android:pageSizeCompat=\"enabled\"/>\n"
                + "    </application>\n</manifest>\n");
    }

    /** The output of a failed build, as apktool 2.12 prints it (the path is absolute, with a drive letter on Windows). */
    private String output(Path file, int line, String attribute) {
        return "I: Building resources with aapt2...\n"
                + "W: " + file.toAbsolutePath() + ":" + line + ": error: attribute " + attribute + " not found.\n"
                + "W: error: failed processing manifest.\n"
                + "Exception in thread \"main\" brut.androlib.exceptions.AndrolibException: could not exec (exit code = 1)\n";
    }

    @Test
    void removesTheAttributeAaptDoesNotKnowEverywhereInThatFile() throws Exception {
        decodeAnApp();

        List<String> removed = APKToolWrapper.removeUnknownAttributes(output(manifest, 3, "android:pageSizeCompat"), decoded);

        assertEquals(List.of("AndroidManifest.xml: removed android:pageSizeCompat"), removed);
        String text = Files.readString(manifest, StandardCharsets.UTF_8);
        assertFalse(text.contains("pageSizeCompat"));
        assertTrue(text.contains("<application android:allowBackup=\"true\" android:label=\"@string/app_name\" android:supportsRtl=\"true\">"),
                "the other attributes are untouched: " + text);
        assertTrue(text.contains("<activity android:name=\".Main\"/>"));
    }

    @Test
    void worksOnResourceFilesToo() throws Exception {
        decodeAnApp();
        Path layout = write("res/layout/main.xml", "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                + "android:orientation=\"vertical\" android:newThing=\"1\" />\n");

        List<String> removed = APKToolWrapper.removeUnknownAttributes(
                output(layout, 1, "android:newThing") + output(manifest, 3, "android:pageSizeCompat"), decoded);

        assertEquals(List.of("res/layout/main.xml: removed android:newThing", "AndroidManifest.xml: removed android:pageSizeCompat"), removed);
        assertEquals("<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" android:orientation=\"vertical\" />\n",
                Files.readString(layout, StandardCharsets.UTF_8));
    }

    /** Material 3's switch uses state_with_icon, whose declaration some apps lose when decoded. */
    @Test
    void declaresAgainAnAttributeOfTheAppThatAResourceUsesButNothingDeclares() throws Exception {
        decodeAnApp();
        Path thumb = write("res/drawable/mtrl_switch_thumb.xml", "<selector xmlns:app=\"http://schemas.android.com/apk/res-auto\">"
                + "<item app:state_with_icon=\"true\" /></selector>\n");
        Path attrs = write("res/values/attrs.xml", "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <attr name=\"actionBarSize\" format=\"dimension\" />\n</resources>\n");
        String output = "W: " + thumb.toAbsolutePath() + ":6: error: attribute state_with_icon (aka org.tasks:state_with_icon) not found.\n"
                + "W: error: failed linking file resources.\n";

        List<String> declared = APKToolWrapper.declareMissingAttributes(output, decoded);

        assertEquals(List.of("res/values/attrs.xml: declared attr state_with_icon"), declared);
        String text = Files.readString(attrs, StandardCharsets.UTF_8);
        assertTrue(text.contains("    <attr name=\"state_with_icon\" />\n</resources>"), text);
        assertTrue(text.contains("<attr name=\"actionBarSize\" format=\"dimension\" />"), "what was declared stays");
        assertTrue(APKToolWrapper.declareMissingAttributes(output, decoded).isEmpty(), "already declared: not declared twice, so no endless retry");
        assertTrue(Files.readString(thumb).contains("app:state_with_icon=\"true\""), "the resource that uses it is not touched");
    }

    @Test
    void anAttributesFileIsCreatedWhenTheAppHasNone() throws Exception {
        decodeAnApp();
        String output = "W: " + manifest.toAbsolutePath() + ":1: error: attribute fancy (aka org.tasks:fancy) not found.\n";

        assertEquals(1, APKToolWrapper.declareMissingAttributes(output, decoded).size());

        assertTrue(Files.readString(decoded.resolve("res/values/attrs.xml")).contains("<attr name=\"fancy\" />"));
    }

    @Test
    void anyOtherBuildErrorChangesNothing() throws Exception {
        decodeAnApp();
        String before = Files.readString(manifest, StandardCharsets.UTF_8);
        Path outside = tmp.resolve("elsewhere.xml");
        Files.writeString(outside, "<x android:pageSizeCompat=\"enabled\"/>");

        assertTrue(APKToolWrapper.removeUnknownAttributes("W: error: resource string/missing not found.\n", decoded).isEmpty());
        assertTrue(APKToolWrapper.declareMissingAttributes("W: error: resource string/missing not found.\n", decoded).isEmpty());
        assertTrue(APKToolWrapper.removeUnknownAttributes(output(manifest, 3, "android:notInTheFile"), decoded).isEmpty(),
                "nothing to remove means nothing is reported, so the build is not retried for ever");
        assertTrue(APKToolWrapper.removeUnknownAttributes(output(outside, 1, "android:pageSizeCompat"), decoded).isEmpty(),
                "a file outside the decoded app is never touched");

        assertEquals(before, Files.readString(manifest, StandardCharsets.UTF_8));
        assertTrue(Files.readString(outside).contains("pageSizeCompat"));
    }

    // ------------------------------------------------------------------ the APK files of a build

    @Test
    void theUnsignedApkIsRemovedOnceTheSignedOneIsThere() throws Exception {
        Path unsigned = Files.writeString(tmp.resolve("org.foo.apk"), "unsigned");
        Path signed = Files.writeString(tmp.resolve("org.foo-aligned-debugSigned.apk"), "signed");
        Path signature = Files.writeString(tmp.resolve("org.foo-aligned-debugSigned.apk.idsig"), "its v4 signature");

        assertTrue(APKToolWrapper.keepOnlySigned(unsigned.toFile(), signed.toFile()));

        assertFalse(Files.exists(unsigned), "the unsigned APK was as large as the signed one and nothing used it again");
        assertFalse(Files.exists(signature), "only an incremental install reads it");
        assertTrue(Files.exists(signed));
    }

    @Test
    void theUnsignedApkStaysWhenSigningFailed() throws Exception {
        Path unsigned = Files.writeString(tmp.resolve("org.foo.apk"), "unsigned");
        Path signed = tmp.resolve("org.foo-aligned-debugSigned.apk");

        assertFalse(APKToolWrapper.keepOnlySigned(unsigned.toFile(), signed.toFile()));

        assertTrue(Files.exists(unsigned), "it is what there is to look at");
    }

    @Test
    void theSignedApkOfThePreviousBuildCannotBeTakenForTheNextOne() throws Exception {
        // every build is signed into the same name, so a stale English APK could pass for the Spanish one
        Path signed = Files.writeString(tmp.resolve("org.foo-aligned-debugSigned.apk"), "the build before");
        Path signature = Files.writeString(tmp.resolve("org.foo-aligned-debugSigned.apk.idsig"), "its v4 signature");
        Path unsigned = Files.writeString(tmp.resolve("org.foo.apk"), "the new build, not signed");

        APKToolWrapper.forgetPreviousBuild(signed.toFile());

        assertFalse(Files.exists(signed));
        assertFalse(Files.exists(signature));
        assertFalse(APKToolWrapper.keepOnlySigned(unsigned.toFile(), signed.toFile()), "no signed APK: the build failed, and says so");
    }

    @Test
    void forgettingABuildThatIsNotThereIsNotAnError() throws Exception {
        APKToolWrapper.forgetPreviousBuild(tmp.resolve("never-built-aligned-debugSigned.apk").toFile());
    }
}

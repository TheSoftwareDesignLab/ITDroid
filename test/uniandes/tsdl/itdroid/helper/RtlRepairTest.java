package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.json.simple.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The repair that makes an app follow a right-to-left language (port of supportRTL of the
 * repairments branch): what it changes in the manifest and the layouts, what it leaves alone, and
 * that it can be undone.
 */
class RtlRepairTest {

    private static final String NS = "xmlns:android=\"http://schemas.android.com/apk/res/android\" xmlns:app=\"http://schemas.android.com/apk/res-auto\"";

    @TempDir
    Path tmp;

    private Path app(String application) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(root.resolve("AndroidManifest.xml"), "<?xml version=\"1.0\" encoding=\"utf-8\"?><manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"org.example\">\n"
                + "    " + application + "\n        <activity android:name=\".Main\"/>\n    </application>\n</manifest>\n", StandardCharsets.UTF_8);
        return root;
    }

    private static Path layout(Path root, String folder, String name, String xml) throws IOException {
        Path file = Files.createDirectories(root.resolve("res").resolve(folder)).resolve(name);
        Files.writeString(file, "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" + xml, StandardCharsets.UTF_8);
        return file;
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static List<String> files(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().collect(Collectors.toList());
        }
    }

    @Test
    void theApplicationDeclaresThatItSupportsRightToLeft() throws IOException {
        Path missing = app("<application android:label=\"@string/app_name\">");
        RtlRepair repair = RtlRepair.apply(missing.toString(), 24);
        assertTrue(read(missing.resolve("AndroidManifest.xml")).contains("<application android:supportsRtl=\"true\" android:label=\"@string/app_name\">"));
        assertEquals(true, repair.toJson().get("supportsRtlAdded"));
        assertTrue(repair.changedSomething());

        Path refused = app("<application android:supportsRtl=\"false\" android:label=\"@string/app_name\">");
        RtlRepair.apply(refused.toString(), 24);
        assertTrue(read(refused.resolve("AndroidManifest.xml")).contains("<application android:supportsRtl=\"true\" android:label=\"@string/app_name\">"));

        Path already = app("<application android:label=\"@string/app_name\" android:supportsRtl=\"true\">");
        RtlRepair nothing = RtlRepair.apply(already.toString(), 24);
        assertEquals(false, nothing.toJson().get("supportsRtlAdded"));
        assertFalse(nothing.changedSomething(), "an app that supports it and has no layouts is left as it is");
    }

    @Test
    void theSidesOfALayoutBecomeItsStartAndItsEnd() throws IOException {
        Path root = app("<application android:supportsRtl=\"true\">");
        Path main = layout(root, "layout", "main.xml", "<RelativeLayout " + NS + " android:paddingLeft=\"16dp\" android:paddingRight=\"8dp\">\n"
                + "    <ImageView android:id=\"@id/icon\" android:layout_alignParentLeft=\"true\" android:layout_marginLeft=\"4dp\" />\n"
                + "    <TextView android:id=\"@id/title\" android:layout_toRightOf=\"@id/icon\" android:layout_alignRight=\"@id/bar\" android:drawableLeft=\"@drawable/dot\" android:gravity=\"left|center_vertical\" />\n"
                + "    <Button android:layout_gravity=\"right\" app:layout_constraintLeft_toRightOf=\"@id/title\" app:layout_goneMarginRight=\"2dp\" />\n"
                + "</RelativeLayout>\n");

        RtlRepair repair = RtlRepair.apply(root.toString(), 24);
        String xml = read(main);

        assertTrue(xml.contains("android:paddingStart=\"16dp\" android:paddingEnd=\"8dp\""), xml);
        assertTrue(xml.contains("android:layout_alignParentStart=\"true\" android:layout_marginStart=\"4dp\""), xml);
        assertTrue(xml.contains("android:layout_toEndOf=\"@id/icon\" android:layout_alignEnd=\"@id/bar\" android:drawableStart=\"@drawable/dot\" android:gravity=\"start|center_vertical\""), xml);
        assertTrue(xml.contains("android:layout_gravity=\"end\" app:layout_constraintStart_toEndOf=\"@id/title\" app:layout_goneMarginEnd=\"2dp\""), xml);
        assertFalse(xml.contains("Left") || xml.contains("Right"), xml);
        assertEquals(1L, repair.toJson().get("layoutsChanged"));
        assertEquals(11L, repair.toJson().get("attributesRenamed"));
    }

    @Test
    void whatHasNoStartOrEndTwinIsLeftAlone() throws IOException {
        Path root = app("<application android:supportsRtl=\"true\">");
        String body = "<LinearLayout " + NS + ">\n"
                + "    <Button android:nextFocusLeft=\"@id/a\" android:nextFocusRight=\"@id/b\" app:contentPaddingLeft=\"4dp\" android:text=\"Left=Right\" android:gravity=\"center\" />\n"
                + "    <TextView android:paddingLeft=\"4dp\" android:paddingStart=\"6dp\" />\n"
                + "</LinearLayout>\n";
        Path main = layout(root, "layout", "main.xml", body);

        RtlRepair repair = RtlRepair.apply(root.toString(), 24);

        assertEquals("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" + body, read(main),
                "attributes without a twin would not build, a text is not an attribute, and an element that already has the start is not given it twice");
        assertEquals(0L, repair.toJson().get("layoutsChanged"));
    }

    @Test
    void belowApi17BothAreKept() throws IOException {
        Path root = app("<application android:supportsRtl=\"true\">");
        Path main = layout(root, "layout", "main.xml", "<LinearLayout " + NS + " android:paddingLeft=\"16dp\"/>\n");

        RtlRepair.apply(root.toString(), 15);

        assertTrue(read(main).contains("android:paddingLeft=\"16dp\" android:paddingStart=\"16dp\""), read(main));
    }

    @Test
    void aCopyOfEveryLayoutForcesTheDirectionAndKeepsTheQualifiersOfItsFolder() throws IOException {
        Path root = app("<application android:supportsRtl=\"true\">");
        layout(root, "layout", "main.xml", "<LinearLayout " + NS + " android:orientation=\"vertical\">\n    <TextView />\n</LinearLayout>\n");
        layout(root, "layout-land", "main.xml", "<LinearLayout " + NS + " android:orientation=\"horizontal\"/>\n");
        layout(root, "layout-sw600dp-v21", "main.xml", "<FrameLayout\n    " + NS + "\n    android:id=\"@id/root\">\n</FrameLayout>\n");
        layout(root, "layout", "row.xml", "<merge " + NS + ">\n    <TextView />\n</merge>\n");
        layout(root, "layout-ar", "main.xml", "<LinearLayout " + NS + "/>\n");
        layout(root, "layout", "forced.xml", "<LinearLayout " + NS + " android:layoutDirection=\"ltr\"/>\n");

        RtlRepair repair = RtlRepair.apply(root.toString(), 24);

        assertEquals(3L, repair.toJson().get("forcedLayouts"));
        assertTrue(read(root.resolve("res/layout-ldrtl/main.xml")).contains("android:orientation=\"vertical\" android:layoutDirection=\"rtl\">\n    <TextView />"));
        assertTrue(read(root.resolve("res/layout-ldrtl-land/main.xml")).contains("android:orientation=\"horizontal\" android:layoutDirection=\"rtl\"/>"));
        assertTrue(read(root.resolve("res/layout-ldrtl-sw600dp-v21/main.xml")).contains("android:id=\"@id/root\" android:layoutDirection=\"rtl\">"));
        assertFalse(Files.exists(root.resolve("res/layout-ldrtl/row.xml")), "a merge has no direction of its own");
        assertFalse(Files.exists(root.resolve("res/layout-ldrtl-ar")), "a layout made for one language is left to the app");
        assertFalse(Files.exists(root.resolve("res/layout-ldrtl/forced.xml")), "a layout that fixes its direction keeps it");
    }

    @Test
    void theRepairCanBeUndone() throws IOException {
        Path root = app("<application android:label=\"Shop\">");
        layout(root, "layout", "main.xml", "<LinearLayout " + NS + " android:paddingLeft=\"16dp\"/>\n");
        layout(root, "layout-land", "main.xml", "<LinearLayout " + NS + " android:gravity=\"right\"/>\n");
        List<String> before = files(root);
        String manifest = read(root.resolve("AndroidManifest.xml"));
        String main = read(root.resolve("res/layout/main.xml"));

        RtlRepair repair = RtlRepair.apply(root.toString(), 24);
        JSONObject done = repair.toJson();
        assertEquals(true, done.get("supportsRtlAdded"));
        assertEquals(2L, done.get("layoutsChanged"));
        assertEquals(2L, done.get("forcedLayouts"));
        assertEquals(before.size() + 2, files(root).size());

        repair.revert();

        assertEquals(before, files(root));
        assertEquals(manifest, read(root.resolve("AndroidManifest.xml")));
        assertEquals(main, read(root.resolve("res/layout/main.xml")));
        assertFalse(Files.exists(root.resolve("res/layout-ldrtl")));
    }

    @Test
    void anAppWithoutLayoutsOnlyGetsTheDeclaration() throws IOException {
        Path compose = app("<application android:label=\"Shop\">");

        RtlRepair repair = RtlRepair.apply(compose.toString(), 26);

        assertEquals(true, repair.toJson().get("supportsRtlAdded"));
        assertEquals(0L, repair.toJson().get("layoutsChanged"));
        assertEquals(0L, repair.toJson().get("forcedLayouts"));
    }
}

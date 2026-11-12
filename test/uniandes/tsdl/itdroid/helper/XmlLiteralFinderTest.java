package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import uniandes.tsdl.itdroid.Fixtures;

/** Text written directly in layouts, menus, preference screens and the manifest. */
class XmlLiteralFinderTest {

    private static final String ANDROID = "xmlns:android=\"http://schemas.android.com/apk/res/android\"";
    private static final String APP = "xmlns:app=\"http://schemas.android.com/apk/res-auto\"";

    @TempDir
    Path tmp;

    private void write(String relativePath, String xml) throws IOException {
        Path file = tmp.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" + xml, StandardCharsets.UTF_8);
    }

    private static List<String> texts(List<XmlLiteralFinder.Literal> literals) {
        return literals.stream().map(l -> l.text).toList();
    }

    @Test
    void findsTheThreeLiteralsOfTheHandWrittenLayout() {
        List<XmlLiteralFinder.Literal> found = XmlLiteralFinder.find(Fixtures.decodedApp().toString());

        assertEquals(List.of("Add to cart", "Adds the product to the cart", "Coupon code"), texts(found));
        XmlLiteralFinder.Literal button = found.get(0);
        assertEquals("res/layout/activity_main.xml", button.file);
        assertEquals(4, button.line);
        assertEquals("Button", button.element);
        assertEquals("@id/add", button.id);
        assertEquals("android:text", button.attribute);
        assertEquals("res/layout/activity_main.xml:4", button.origin());
        assertEquals("android:contentDescription", found.get(1).attribute);
    }

    @Test
    void readsTheElementAsATreeWhateverLineTheAttributeIsOn() throws IOException {
        write("res/layout/screen.xml", "<LinearLayout " + ANDROID + ">\n"
                + "  <FrameLayout>\n"
                + "    <TextView\n"
                + "        android:id=\"@id/title\"\n"
                + "        android:layout_width=\"wrap_content\"\n"
                + "        android:text=\"Meeep\" />\n"
                + "  </FrameLayout>\n"
                + "</LinearLayout>\n");

        List<XmlLiteralFinder.Literal> found = XmlLiteralFinder.find(tmp.toString());

        assertEquals(List.of("Meeep"), texts(found));
        assertEquals("TextView", found.get(0).element);
        assertEquals("@id/title", found.get(0).id);
        assertEquals(7, found.get(0).line, "the line where the start tag closes");
    }

    @Test
    void coversMenusPreferencesNavigationAndTheManifest() throws IOException {
        write("res/menu/main.xml", "<menu " + ANDROID + " " + APP + ">\n"
                + "  <item android:id=\"@id/sync\" android:title=\"Sync now\" app:showAsAction=\"ifRoom\" />\n"
                + "  <item android:id=\"@id/about\" android:title=\"@string/about\" />\n</menu>\n");
        write("res/xml/preferences.xml", "<PreferenceScreen " + ANDROID + " " + APP + ">\n"
                + "  <SwitchPreference android:key=\"dark\" android:title=\"Dark theme\" android:summary=\"Uses less battery\" android:defaultValue=\"false\" />\n"
                + "  <ListPreference android:key=\"zoom\" app:summary=\"%s\" app:dialogTitle=\"Zoom level\" />\n</PreferenceScreen>\n");
        write("res/navigation/graph.xml", "<navigation " + ANDROID + ">\n"
                + "  <fragment android:id=\"@id/home\" android:name=\"com.acme.HomeFragment\" android:label=\"Home\" />\n</navigation>\n");
        write("res/layout-land/wide.xml", "<LinearLayout " + ANDROID + "><EditText android:hint=\"Color Value\" /></LinearLayout>\n");
        write("AndroidManifest.xml", "<manifest " + ANDROID + " package=\"com.acme\">\n"
                + "  <application android:label=\"Acme Notes\" android:name=\"com.acme.App\">\n"
                + "    <activity android:name=\"com.acme.Main\" android:label=\"@string/app_name\" />\n  </application>\n</manifest>\n");

        List<XmlLiteralFinder.Literal> found = XmlLiteralFinder.find(tmp.toString());

        // folders by name, files by name, the manifest last
        assertEquals(List.of("Color Value", "Sync now", "Home", "Dark theme", "Uses less battery", "Zoom level", "Acme Notes"), texts(found));
        assertEquals("res/layout-land/wide.xml", found.get(0).file);
        assertEquals("dark", found.get(3).id, "a preference is identified by its key");
        assertEquals("app:dialogTitle", found.get(5).attribute, "and its summary \"%s\" is a format, not text");
        assertEquals("AndroidManifest.xml", found.get(6).file);
    }

    @Test
    void leavesOutReferencesTextWithoutLettersAndLibraryLayouts() throws IOException {
        write("res/layout/own.xml", "<LinearLayout " + ANDROID + " xmlns:tools=\"http://schemas.android.com/tools\">\n"
                + "  <TextView android:text=\"@string/title\" />\n"
                + "  <TextView android:text=\"?attr/label\" />\n"
                + "  <TextView android:text=\"0\" />\n"
                + "  <TextView android:text=\"\" />\n"
                + "  <TextView android:text=\"@{viewModel.title}\" />\n"
                + "  <TextView tools:text=\"Preview only\" />\n"
                + "  <TextView android:tag=\"not text\" android:text=\"\\@home\" />\n</LinearLayout>\n");
        write("res/layout/abc_action_menu_item_layout.xml", "<LinearLayout " + ANDROID + "><TextView android:text=\"From AppCompat\" /></LinearLayout>\n");
        write("res/layout/mtrl_picker_header_dialog.xml", "<LinearLayout " + ANDROID + "><TextView android:text=\"From Material\" /></LinearLayout>\n");
        write("res/drawable/shape.xml", "<shape " + ANDROID + " android:label=\"Not a screen\" />\n");
        write("res/layout/broken.xml", "<LinearLayout><TextView android:text=\"unbound prefix\" /></LinearLayout>\n");

        assertEquals(List.of("@home"), texts(XmlLiteralFinder.find(tmp.toString())), "only the escaped @ is literal text");
        assertNull(XmlLiteralFinder.literalOf("@string/x"));
        assertEquals("Cancel", XmlLiteralFinder.literalOf("Cancel"));
        assertTrue(XmlLiteralFinder.isLibraryFile("design_bottom_sheet_dialog.xml"));
    }
}

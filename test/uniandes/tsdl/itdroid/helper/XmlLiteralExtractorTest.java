package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import uniandes.tsdl.itdroid.Fixtures;

/** Moving the text written in the XML to string resources, so it is translated like any other resource. */
class XmlLiteralExtractorTest {

    private static final String ANDROID = "xmlns:android=\"http://schemas.android.com/apk/res/android\"";

    @TempDir
    Path tmp;

    private void write(String relativePath, String xml) throws IOException {
        Path file = tmp.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" + xml, StandardCharsets.UTF_8);
    }

    private static Map<String, String> strings(Path decoded) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (Element e : new SAXBuilder().build(decoded.resolve("res/values/strings.xml").toFile()).getRootElement().getChildren("string")) {
            out.put(e.getAttributeValue("name"), e.getText());
        }
        return out;
    }

    private XmlLiterals.Extraction extract(Set<String> appNames) {
        return XmlLiterals.extract(tmp.toString(), XmlLiterals.find(tmp.toString()), appNames);
    }

    @Test
    void theLiteralsOfTheHandWrittenLayoutBecomeResources() throws Exception {
        Path app = tmp.resolve("shop");
        FileUtils.copyDirectory(Fixtures.decodedApp().toFile(), app.toFile());
        List<XmlLiterals.Literal> literals = XmlLiterals.find(app.toString());

        XmlLiterals.Extraction extraction = XmlLiterals.extract(app.toString(), literals, Set.of("Shop"));

        assertEquals(Map.of("Add to cart", "itdroid_hc_1", "Adds the product to the cart", "itdroid_hc_2", "Coupon code", "itdroid_hc_3"),
                extraction.nameByText);
        assertEquals(3, extraction.attributes);
        assertTrue(extraction.problems.isEmpty());

        String layout = Files.readString(app.resolve("res/layout/activity_main.xml"), StandardCharsets.UTF_8);
        assertTrue(layout.contains("android:text=\"@string/itdroid_hc_1\""));
        assertTrue(layout.contains("android:contentDescription=\"@string/itdroid_hc_2\""));
        assertTrue(layout.contains("android:hint=\"@string/itdroid_hc_3\""));
        assertTrue(layout.contains("android:text=\"@string/cart_title\"") && layout.contains("android:id=\"@id/coupon\""), "the rest is untouched");
        assertTrue(XmlLiterals.find(app.toString()).isEmpty(), "no literal is left");

        Map<String, String> strings = strings(app);
        assertEquals("Add to cart", strings.get("itdroid_hc_1"));
        assertEquals("Coupon code", strings.get("itdroid_hc_3"));
        assertEquals("Hello %1$s", strings.get("greeting"), "the strings the app had are still there");
        assertEquals(10, strings.size());
    }

    @Test
    void anExtractedLiteralIsTranslatedAsAResource() throws Exception {
        Path app = tmp.resolve("shop");
        FileUtils.copyDirectory(Fixtures.decodedApp().toFile(), app.toFile());
        List<XmlLiterals.Literal> literals = XmlLiterals.find(app.toString());
        XmlLiterals.Extraction extraction = XmlLiterals.extract(app.toString(), literals, Set.of("Shop"));

        StringInventory inventory = StringInventory.collect(app.toString(), "com.example.shop.free",
                new NotTranslatableStringsDictionary(List.of()),
                HardcodedStringFinder.hits(Fixtures.scan(app.toString(), "com.example.shop.free")), literals, HardcodedPolicy.UI,
                extraction, null);

        StringInventory.Entry coupon = inventory.entries().stream()
                .filter(e -> e.kind.equals(StringInventory.KIND_HARDCODED_XML) && e.text.equals("Coupon code")).findFirst().get();
        assertTrue(coupon.translatable);
        assertEquals("itdroid_hc_3", coupon.extractedTo);
        assertEquals("Coupon code", coupon.resourceText);
        assertEquals("res/layout/activity_main.xml:5", coupon.origin, "the finding still says where the text was written");

        StringInventory.Entry resource = inventory.resourceEntries().stream().filter(e -> e.name.equals("itdroid_hc_3")).findFirst().get();
        assertTrue(resource.translatable);
        assertEquals("Coupon code", resource.text);
        assertEquals(Set.of("itdroid_hc_1", "itdroid_hc_2", "itdroid_hc_3"), inventory.extractedResources());
        assertEquals(3L, inventory.summary().get("hardcodedXmlExtracted"));
        assertEquals(11L, inventory.summary().get("hardcodedTranslatable"), "8 of the code and the 3 of the XML");
        assertFalse(inventory.notMovedCodeLiterals().containsKey("Coupon code"), "it travels as a resource");

        inventory.putTranslation("es", "Coupon code", "C\u00f3digo de cup\u00f3n");
        Path json = tmp.resolve("inventory.json");
        inventory.save(json);
        org.json.simple.JSONObject root = (org.json.simple.JSONObject) new org.json.simple.parser.JSONParser()
                .parse(Files.readString(json, StandardCharsets.UTF_8));
        org.json.simple.JSONObject saved = null;
        for (Object o : (org.json.simple.JSONArray) root.get("entries")) {
            org.json.simple.JSONObject e = (org.json.simple.JSONObject) o;
            if ("hardcoded-xml".equals(e.get("kind")) && "Coupon code".equals(e.get("text"))) {
                saved = e;
            }
        }
        assertEquals("@string/itdroid_hc_3", saved.get("extractedTo"));
        assertEquals(true, saved.get("translatable"));
        assertEquals("Código de cupón", ((org.json.simple.JSONObject) saved.get("translations")).get("es"),
                "the translation the resource got is shown next to the finding");
    }

    @Test
    void theSameTextInSeveralPlacesIsOneResource() throws Exception {
        write("res/layout/a.xml", "<LinearLayout " + ANDROID + ">\n  <Button android:text=\"Save\" />\n  <Button android:text=\"Cancel\" />\n</LinearLayout>\n");
        write("res/menu/main.xml", "<menu " + ANDROID + ">\n  <item android:id=\"@id/save\" android:title=\"Save\" />\n</menu>\n");
        write("res/values/strings.xml", "<resources>\n    <string name=\"itdroid_hc_1\">taken by the app</string>\n</resources>\n");

        XmlLiterals.Extraction extraction = extract(Set.of());

        assertEquals(Map.of("Save", "itdroid_hc_2", "Cancel", "itdroid_hc_3"), extraction.nameByText, "a name the app already uses is skipped");
        assertEquals(3, extraction.attributes);
        assertTrue(Files.readString(tmp.resolve("res/menu/main.xml")).contains("android:title=\"@string/itdroid_hc_2\""));
        assertEquals("taken by the app", strings(tmp).get("itdroid_hc_1"));
        assertEquals("Save", strings(tmp).get("itdroid_hc_2"));
    }

    @Test
    void writesTheTextWithTheEscapingAResourceNeeds() throws Exception {
        write("res/layout/a.xml", "<LinearLayout " + ANDROID + ">\n"
                + "  <TextView android:text=\"Don't &quot;stop&quot; now\" />\n"
                + "  <TextView android:text=\"Fish &amp; chips &lt;3\" />\n"
                + "  <TextView android:text=\" padded \" />\n"
                + "  <TextView android:text=\"\\@home\" />\n"
                + "  <TextView android:text=\"Line one\\nLine two\" />\n</LinearLayout>\n");

        XmlLiterals.Extraction extraction = extract(Set.of());

        String raw = Files.readString(tmp.resolve("res/values/strings.xml"), StandardCharsets.UTF_8);
        assertTrue(raw.contains(">Don\\'t \\\"stop\\\" now<"), raw);
        assertTrue(raw.contains(">Fish &amp; chips &lt;3<"), raw);
        assertTrue(raw.contains(">\" padded \"<"), "quoted, or the build drops the edge spaces: " + raw);
        assertTrue(raw.contains(">Line one\\nLine two<"), raw);
        assertEquals(4, extraction.names().size());
        assertTrue(Files.readString(tmp.resolve("res/layout/a.xml")).contains("android:text=\"\\@home\""),
                "a text that starts with @ reads as a reference to every other rule, so it is left where it is");
        assertEquals("Fish &amp; chips &lt;3", extraction.valueByName.get(extraction.nameByText.get("Fish & chips <3")),
                "the value as the file holds it: what the inventory reads back");
        assertEquals("Fish & chips <3", strings(tmp).get(extraction.nameByText.get("Fish & chips <3")));
    }

    @Test
    void theNameOfTheAppAndWhatIsNotTextStayWhereTheyAre() throws Exception {
        write("AndroidManifest.xml", "<manifest " + ANDROID + " package=\"com.acme\">\n"
                + "  <application android:label=\"Acme Notes\" android:name=\"com.acme.App\">\n"
                + "    <activity android:name=\"com.acme.Main\" android:label=\"Acme Notes\" />\n"
                + "    <activity android:name=\"com.acme.Settings\" android:label=\"Settings\" />\n  </application>\n</manifest>\n");
        write("res/layout/a.xml", "<LinearLayout " + ANDROID + ">\n  <TextView android:text=\"com.acme.Notes\" />\n</LinearLayout>\n");

        Translatability.AppName name = Translatability.appName(tmp.toString());
        XmlLiterals.Extraction extraction = extract(name.texts);

        assertEquals(Set.of("Acme Notes"), name.texts, "a literal label of the application is the app's name");
        assertEquals(Map.of("Settings", "itdroid_hc_1"), extraction.nameByText);
        String manifest = Files.readString(tmp.resolve("AndroidManifest.xml"), StandardCharsets.UTF_8);
        assertEquals(2, manifest.split("android:label=\"Acme Notes\"", -1).length - 1, "both labels with the app's name are kept");
        assertTrue(manifest.contains("android:label=\"@string/itdroid_hc_1\""));
        assertTrue(Files.readString(tmp.resolve("res/layout/a.xml")).contains("android:text=\"com.acme.Notes\""), "an identifier is not text");
        assertEquals("Settings", strings(tmp).get("itdroid_hc_1"), "strings.xml is created when the app has none");
    }

    @Test
    void withoutLiteralsNothingIsWritten() {
        XmlLiterals.Extraction extraction = extract(Set.of());

        assertTrue(extraction.names().isEmpty());
        assertEquals(0, extraction.attributes);
        assertFalse(Files.exists(tmp.resolve("res/values/strings.xml")));
        assertNull(extraction.nameByText.get("anything"));
    }
}

package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import uniandes.tsdl.itdroid.Fixtures;

/**
 * What the hardcoded-string detection finds in the hand-written app of {@code test/fixtures/decoded/shop}
 * (applicationId {@code com.example.shop.free}, most of its code under {@code com.example.shop}).
 *
 * <p>Tests named {@code knownGap_...} pin behaviour the audit found wrong: they assert what the code
 * does TODAY and name the TODO item that changes it; when that item is done the assertion flips.
 */
class HardcodedDetectionTest {

    private static final String APPLICATION_ID = "com.example.shop.free";

    /** Literals of the code in the inventory, by text. */
    private static final Map<String, StringInventory.Entry> CODE = new LinkedHashMap<>();
    /** Literals of the XML in the inventory, by text. */
    private static final Map<String, StringInventory.Entry> XML = new LinkedHashMap<>();
    private static StringInventory inventory;

    @TempDir
    Path out;

    @BeforeAll
    static void collectTheInventory() throws Exception {
        inventory = StringInventory.collect(Fixtures.decodedApp().toString(), APPLICATION_ID,
                new NotTranslatableStringsDictionary(Fixtures.settingsDir().toString()));
        for (StringInventory.Entry e : inventory.entries()) {
            if (StringInventory.KIND_HARDCODED.equals(e.kind)) {
                CODE.put(e.text, e);
            } else if (StringInventory.KIND_HARDCODED_XML.equals(e.kind)) {
                XML.put(e.text, e);
            }
        }
    }

    private static void assertTranslatable(String literal) {
        StringInventory.Entry e = CODE.get(literal);
        assertNotNull(e, "not detected: \"" + literal + "\"");
        assertTrue(e.translatable, "\"" + literal + "\" is kept verbatim: " + e.skipReason);
    }

    private static void assertKeptVerbatim(String literal, String reason) {
        StringInventory.Entry e = CODE.get(literal);
        assertNotNull(e, "not detected: \"" + literal + "\"");
        assertFalse(e.translatable, "\"" + literal + "\" would be translated");
        assertEquals(reason, e.skipReason);
    }

    // ------------------------------------------------------------------ the code

    @Test
    void findsTheTextShownThroughViewsToastsAndCompose() {
        assertTranslatable("Welcome back");          // TextView.setText
        assertTranslatable("Saved");                 // Toast.makeText
        assertTranslatable("Your cart is empty");    // Compose Text()
        assertTranslatable("Premium");
        assertEquals("ui", CODE.get("Welcome back").consumer);
        assertEquals("ui", CODE.get("Your cart is empty").consumer);
    }

    @Test
    void readsEveryDexFolder() {
        assertTrue(CODE.get("Your cart is empty").origin.startsWith("smali_classes2/"),
                CODE.get("Your cart is empty").origin);
        assertTrue(CODE.get("Welcome back").origin.startsWith("smali/"));
    }

    @Test
    void readsJumboStringInstructions() {
        assertTranslatable("Checkout now");
    }

    /** The launcher activity lives in {@code com.example.shop.ui}, outside the folder of the applicationId. */
    @Test
    void findsTheCodeOutsideTheApplicationIdFolder() {
        assertTranslatable("Shop");
        assertTranslatable("Add to cart");
        assertEquals("smali/com/example/shop/ui/MainActivity.smali:16", CODE.get("Shop").origin);
    }

    @Test
    void keepsTheEdgeSpacesOfConcatenationFragments() {
        assertTranslatable("Items: ");
        assertTranslatable(" of 3 in your cart");
        // both go into a StringBuilder whose toString() is handed to TextView.setText
        assertEquals("ui", CODE.get("Items: ").consumer);
        assertEquals("ui", CODE.get(" of 3 in your cart").consumer);
    }

    @Test
    void recordsEveryPlaceALiteralAppearsIn() {
        assertEquals("smali/com/example/shop/free/FreeActivity.smali:26", CODE.get("Welcome back").origin);
        StringInventory.Entry premium = CODE.get("Premium");
        assertTrue(premium.origin.endsWith("(+2 more)"), premium.origin);
        assertEquals(3, premium.occurrences());
        assertEquals(List.of("planName", "showPlan", "isPremium"),
                premium.codePlaces.stream().map(p -> p.method).toList());
        assertEquals(List.of("return", "ui", "data"),
                premium.codePlaces.stream().map(p -> p.consumer.label).toList());
    }

    @Test
    void keepsSqlIdentifiersAndCompilerStringsVerbatim() {
        assertKeptVerbatim("SELECT * FROM cart WHERE id = ?", "SQL statement");
        assertKeptVerbatim("FreeActivity", "class-like identifier");
        assertKeptVerbatim("view.resources", "not human-readable text");
        assertNull(CODE.get("dark_mode"), "snake_case keys are not even listed");
        assertTrue(CODE.keySet().stream().noneMatch(t -> t.contains("CartScreen.kt#")), "Compose source information is noise");
    }

    /** Text that never reaches the screen is listed with what it is for, and never sent to the LLM. */
    @Test
    void logsExceptionsAndKeysAreNotTranslated() {
        assertKeptVerbatim("onCreate called with saved state", "log message");
        assertKeptVerbatim("Cart must not be empty", "exception message");
        assertKeptVerbatim("Last Sync", "used as data (key, query, pattern, comparison)");
        assertEquals("log", CODE.get("onCreate called with saved state").consumer);
        assertEquals("exception", CODE.get("Cart must not be empty").consumer);
        assertEquals("data", CODE.get("Last Sync").consumer);
        assertFalse(inventory.hardcodedTexts().contains("Last Sync"));
    }

    /**
     * KNOWN GAP (TODO 2.1): a literal whose use the scan cannot tell is still translated. " LIMIT 0"
     * is appended to a StringBuilder the method received; "Bearer " ends in a returned string.
     * Expected after 2.1: neither is translated.
     */
    @Test
    void knownGap_literalsOfUnknownUseAreStillTranslated() {
        assertTranslatable(" LIMIT 0");
        assertTranslatable("Bearer ");
        assertEquals("concatenation", CODE.get(" LIMIT 0").consumer);
        assertEquals("return", CODE.get("Bearer ").consumer);
    }

    // ------------------------------------------------------------------ the XML

    @Test
    void findsTheTextWrittenInLayouts() {
        assertEquals("res/layout/activity_main.xml:4", XML.get("Add to cart").origin);
        assertEquals("res/layout/activity_main.xml:4", XML.get("Adds the product to the cart").origin);
        assertEquals("res/layout/activity_main.xml:5", XML.get("Coupon code").origin);
        assertEquals("android:hint", XML.get("Coupon code").xmlPlaces.get(0).attribute);
        assertEquals("xml", XML.get("Coupon code").consumer);
        assertNull(XML.get("@string/cart_title"), "a reference is not a literal");
    }

    /**
     * KNOWN GAP (TODO 2.4): the literals of the XML are listed but not translated; they will be once
     * they are extracted to resources. Expected after 2.4: translatable.
     */
    @Test
    void knownGap_textWrittenInLayoutsIsListedButNotTranslated() {
        assertFalse(XML.get("Coupon code").translatable);
        assertEquals("XML literal (not extracted to a resource)", XML.get("Coupon code").skipReason);
        assertFalse(inventory.hardcodedTexts().contains("Coupon code"));
    }

    // ------------------------------------------------------------------ one figure, one format

    /** hcs.txt, the inventory and the report give the same number: distinct literals, code plus XML. */
    @Test
    void everyOutputGivesTheSameFigure() throws Exception {
        int reported = HardcodedStringFinder.findHardCodedStrings(Fixtures.decodedApp().toString(), APPLICATION_ID, out.toString());
        JSONObject summary = inventory.summary();

        assertEquals(20, reported);
        assertEquals(20L, summary.get("hardcoded"));
        assertEquals(17L, summary.get("hardcodedCode"));
        assertEquals(3L, summary.get("hardcodedXml"));
        assertEquals(22L, summary.get("hardcodedOccurrences"), "Premium is in three places");
        assertEquals(11L, summary.get("hardcodedTranslatable"));
        assertEquals(CODE.size() + XML.size(), reported);

        JSONObject byConsumer = (JSONObject) summary.get("hardcodedByConsumer");
        assertEquals(9L, byConsumer.get("ui"));
        assertEquals(2L, byConsumer.get("log"));
        assertEquals(1L, byConsumer.get("exception"));
        assertEquals(2L, byConsumer.get("data"));
        assertEquals(1L, byConsumer.get("compiler"));
        assertEquals(1L, byConsumer.get("concatenation"));
        assertEquals(1L, byConsumer.get("return"));
        assertEquals(3L, byConsumer.get("xml"));
    }

    /** As in the original tool: class, then method, then the literals of that method. */
    @Test
    void hcsTxtGroupsTheLiteralsByClassAndMethod() throws Exception {
        HardcodedStringFinder.findHardCodedStrings(Fixtures.decodedApp().toString(), APPLICATION_ID, out.toString());
        List<String> lines = Files.readAllLines(out.resolve("hcs.txt"), StandardCharsets.UTF_8);

        assertEquals(List.of(
                "com.example.shop.free.CartRepository",
                "\tload",
                "\t\tSELECT * FROM cart WHERE id = ?\t[data]",
                "\tlimit",
                "\t\t LIMIT 0\t[concatenation]"), lines.subList(0, 5));
        int activity = lines.indexOf("com.example.shop.free.FreeActivity");
        assertEquals(List.of(
                "\tonCreate",
                "\t\tFreeActivity\t[log]",
                "\t\tonCreate called with saved state\t[log]",
                "\t\tWelcome back\t[ui]",
                "\t\tCheckout now\t[ui]",
                "\tshowSaved",
                "\t\tSaved\t[ui]"), lines.subList(activity + 1, activity + 8));
        assertTrue(lines.indexOf("com.example.shop.ui.MainActivity") > activity, "classes in the order of the scan");
        assertTrue(lines.contains("\t\tPremium\t[ui]") && lines.contains("\t\tPremium\t[data]") && lines.contains("\t\tPremium\t[return]"),
                "each place says what that place does with the literal");

        int layout = lines.indexOf("res/layout/activity_main.xml");
        assertEquals(List.of(
                "\tButton @id/add (line 4)",
                "\t\tAdd to cart\t[android:text]",
                "\t\tAdds the product to the cart\t[android:contentDescription]",
                "\tEditText @id/coupon (line 5)",
                "\t\tCoupon code\t[android:hint]"), lines.subList(layout + 1, layout + 6));
    }

    @Test
    void theInventoryFileSaysWhatEachLiteralIsUsedFor() throws Exception {
        Path file = out.resolve("strings_inventory.json");
        inventory.save(file);
        JSONObject root = (JSONObject) new JSONParser().parse(Files.readString(file, StandardCharsets.UTF_8));

        JSONObject premium = null;
        JSONObject coupon = null;
        for (Object o : (JSONArray) root.get("entries")) {
            JSONObject e = (JSONObject) o;
            if ("Premium".equals(e.get("text"))) {
                premium = e;
            } else if ("Coupon code".equals(e.get("text"))) {
                coupon = e;
            }
        }
        assertEquals("ui", premium.get("consumer"));
        assertEquals(3L, premium.get("occurrences"));
        JSONObject shown = (JSONObject) ((JSONArray) premium.get("places")).get(1);
        assertEquals("smali/com/example/shop/free/FreeActivity.smali:88", shown.get("origin"));
        assertEquals("com.example.shop.free.FreeActivity", shown.get("class"));
        assertEquals("showPlan", shown.get("method"));
        assertEquals("ui", shown.get("consumer"));
        assertEquals("Landroid/widget/TextView;->setText", shown.get("via"));

        assertEquals("hardcoded-xml", coupon.get("kind"));
        JSONObject place = (JSONObject) ((JSONArray) coupon.get("places")).get(0);
        assertEquals("EditText", place.get("element"));
        assertEquals("@id/coupon", place.get("id"));
        assertEquals("android:hint", place.get("attribute"));
    }

    // ------------------------------------------------------------------ the resources

    @Test
    void theInventoryAlsoHoldsTheResourcesWithTheirSkipReasons() {
        Map<String, StringInventory.Entry> byName = new LinkedHashMap<>();
        for (StringInventory.Entry e : inventory.resourceEntries()) {
            byName.putIfAbsent(e.name, e);
        }
        assertTrue(byName.get("greeting").translatable);
        assertEquals("<b>Terms</b> apply", byName.get("terms").text, "inline markup is kept");
        assertEquals("not-translatable dictionary", byName.get("abc_action_bar_home_description").skipReason);
        assertEquals("no letters", byName.get("version_label").skipReason);
        assertEquals("resource reference", byName.get("title_reference").skipReason);
        assertEquals("data container name", byName.get("sort_values").skipReason);
        assertEquals(2, inventory.resourceEntries().stream().filter(e -> e.name.equals("items_count")).count());
    }

    /** An app with all its text in the code has no strings.xml; its hardcoded strings are found the same. */
    @Test
    void anAppWithoutStringsXmlIsAnalysedTheSame() throws Exception {
        Path app = out.resolve("shop");
        FileUtils.copyDirectory(Fixtures.decodedApp().toFile(), app.toFile());
        FileUtils.deleteDirectory(app.resolve("res/values").toFile());
        Path results = Files.createDirectories(out.resolve("results"));

        int reported = HardcodedStringFinder.findHardCodedStrings(app.toString(), APPLICATION_ID, results.toString());
        StringInventory withoutResources = StringInventory.collect(app.toString(), APPLICATION_ID,
                new NotTranslatableStringsDictionary(Fixtures.settingsDir().toString()));

        assertEquals(20, reported);
        assertTrue(Files.size(results.resolve("hcs.txt")) > 0);
        assertEquals(0L, withoutResources.summary().get("resources"));
        assertEquals(20L, withoutResources.summary().get("hardcoded"));
        assertEquals(inventory.hardcodedTexts(), withoutResources.hardcodedTexts());

        JSONObject facts = Preflight.describeDecodedApp(app.toString());
        assertEquals(false, facts.get("hasStrings"));
        assertEquals(true, facts.get("launchable"));
        Preflight.checkDecodedApp(facts, 34);   // accepted: no exception
    }
}

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

import org.json.simple.JSONObject;
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

    /** Hardcoded literals of the inventory, by text. */
    private static final Map<String, StringInventory.Entry> HARDCODED = new LinkedHashMap<>();
    private static StringInventory inventory;

    @TempDir
    Path out;

    @BeforeAll
    static void collectTheInventory() throws Exception {
        inventory = StringInventory.collect(Fixtures.decodedApp().toString(), APPLICATION_ID,
                new NotTranslatableStringsDictionary(Fixtures.settingsDir().toString()));
        for (StringInventory.Entry e : inventory.entries()) {
            if (!e.isResource()) {
                HARDCODED.put(e.text, e);
            }
        }
    }

    private static void assertTranslatable(String literal) {
        StringInventory.Entry e = HARDCODED.get(literal);
        assertNotNull(e, "not detected: \"" + literal + "\"");
        assertTrue(e.translatable, "\"" + literal + "\" is kept verbatim: " + e.skipReason);
    }

    private static void assertKeptVerbatim(String literal, String reason) {
        StringInventory.Entry e = HARDCODED.get(literal);
        assertNotNull(e, "not detected: \"" + literal + "\"");
        assertFalse(e.translatable, "\"" + literal + "\" would be translated");
        assertEquals(reason, e.skipReason);
    }

    // ------------------------------------------------------------------ what works

    @Test
    void findsTheTextShownThroughViewsToastsAndCompose() {
        assertTranslatable("Welcome back");          // TextView.setText
        assertTranslatable("Saved");                 // Toast.makeText
        assertTranslatable("Your cart is empty");    // Compose Text()
        assertTranslatable("Premium");
    }

    @Test
    void readsEveryDexFolder() {
        assertTrue(HARDCODED.get("Your cart is empty").origin.startsWith("smali_classes2/"),
                HARDCODED.get("Your cart is empty").origin);
        assertTrue(HARDCODED.get("Welcome back").origin.startsWith("smali/"));
    }

    @Test
    void readsJumboStringInstructions() {
        assertTranslatable("Checkout now");
    }

    @Test
    void keepsTheEdgeSpacesOfConcatenationFragments() {
        assertTranslatable("Items: ");
        assertTranslatable(" of 3 in your cart");
    }

    @Test
    void recordsWhereEachLiteralFirstAppearsAndHowOften() {
        assertEquals("smali/com/example/shop/free/FreeActivity.smali:26", HARDCODED.get("Welcome back").origin);
        assertTrue(HARDCODED.get("Premium").origin.endsWith("(+2 more)"), HARDCODED.get("Premium").origin);
    }

    @Test
    void keepsSqlIdentifiersAndCompilerStringsVerbatim() {
        assertKeptVerbatim("SELECT * FROM cart WHERE id = ?", "SQL statement");
        assertKeptVerbatim("FreeActivity", "class-like identifier");
        assertKeptVerbatim("view.resources", "not human-readable text");
        assertNull(HARDCODED.get("dark_mode"), "snake_case keys are not even listed");
        assertTrue(HARDCODED.keySet().stream().noneMatch(t -> t.contains("CartScreen.kt#")), "Compose source information is noise");
    }

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

    // ------------------------------------------------------------------ known gaps

    /**
     * KNOWN GAP (TODO 1.1): only the folder of the applicationId is scanned. The launcher activity
     * lives in {@code com.example.shop.ui}, so its two labels are missed. Expected after 1.1: both found.
     */
    @Test
    void knownGap_codeOutsideTheApplicationIdFolderIsNotScanned() {
        assertNull(HARDCODED.get("Shop"));
        assertNull(HARDCODED.get("Add to cart"));
    }

    /**
     * KNOWN GAP (TODO 1.3 and 2.1): what a literal is used for is not looked at, so text that never
     * reaches the screen is marked for translation and patched into the code. Expected after 1.3 and
     * 2.1: none of these is translatable.
     */
    @Test
    void knownGap_logsExceptionsAndDataAreMarkedForTranslation() {
        assertTranslatable("onCreate called with saved state");   // Log.d message
        assertTranslatable("Cart must not be empty");             // IllegalStateException message
        assertTranslatable(" LIMIT 0");                           // SQL fragment appended to a query
        assertTranslatable("Last Sync");                          // SharedPreferences key
        assertTranslatable("Bearer ");                            // HTTP Authorization prefix
    }

    /**
     * KNOWN GAP (TODO 1.5): three different totals for the same app. {@code hcs.txt} counts a literal
     * once per file, the inventory once per app, and a third number counts the translatable ones.
     * Expected after 1.5: one breakdown shared by every output.
     */
    @Test
    void knownGap_theThreeTotalsDisagree() throws Exception {
        int reported = HardcodedStringFinder.findHardCodedStrings(Fixtures.decodedApp().toString(), APPLICATION_ID, out.toString());
        JSONObject summary = inventory.summary();

        assertEquals(16, reported);
        assertEquals(15L, summary.get("hardcoded"));
        assertEquals(12L, summary.get("hardcodedTranslatable"));
    }

    /**
     * KNOWN GAP (TODO 1.5): {@code hcs.txt} groups by class only; the original tool, the paper and
     * the site group by class and method. Expected after 1.5: a method line between class and literal.
     */
    @Test
    void knownGap_hcsTxtDoesNotSayInWhichMethodALiteralIs() throws Exception {
        HardcodedStringFinder.findHardCodedStrings(Fixtures.decodedApp().toString(), APPLICATION_ID, out.toString());
        List<String> lines = Files.readAllLines(out.resolve("hcs.txt"), StandardCharsets.UTF_8);

        assertEquals("CartRepository", lines.get(0));
        assertTrue(lines.contains("\tWelcome back"));
        assertTrue(lines.stream().noneMatch(l -> l.contains("onCreate") && !l.contains("called")), "no method names");
    }

    /**
     * KNOWN GAP (TODO 1.4): text written directly in a layout ({@code android:text="Add to cart"},
     * a hint, a content description) is not looked for at all. Expected after 1.4: three findings
     * from {@code res/layout/activity_main.xml}.
     */
    @Test
    void knownGap_textWrittenInLayoutsIsNotDetected() {
        assertNull(HARDCODED.get("Coupon code"));
        assertNull(HARDCODED.get("Adds the product to the cart"));
        assertTrue(inventory.entries().stream().noneMatch(e -> e.origin != null && e.origin.contains("res/layout")));
    }
}

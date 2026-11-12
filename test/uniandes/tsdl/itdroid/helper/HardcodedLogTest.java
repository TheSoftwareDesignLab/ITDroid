package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import uniandes.tsdl.itdroid.Fixtures;

/** {@code hardcoded_<lang>.csv}: what was done with every place a hardcoded string is written in. */
class HardcodedLogTest {

    private static final String APPLICATION_ID = "com.example.shop.free";

    @TempDir
    Path tmp;

    private StringInventory inventory;
    private SmaliPatcher patcher;
    private Map<String, String> translations;
    private List<SmaliPatcher.Patch> applied;

    @BeforeEach
    void translateTheApp() throws Exception {
        Path app = tmp.resolve("shop");
        FileUtils.copyDirectory(Fixtures.decodedApp().toFile(), app.toFile());
        List<XmlLiteralFinder.Literal> literals = XmlLiteralFinder.find(app.toString());
        List<HardcodedStringFinder.Hit> hits = HardcodedStringFinder.hits(SmaliScan.scan(app.toString(), APPLICATION_ID));
        XmlLiteralExtractor.Extraction extraction = XmlLiteralExtractor.extract(app.toString(), literals, Set.of("Shop"));
        inventory = StringInventory.collect(app.toString(), APPLICATION_ID, new NotTranslatableStringsDictionary(Fixtures.settingsDir().toString()),
                hits, literals, HardcodedPolicy.UI, extraction);

        translations = new LinkedHashMap<>();
        for (String text : inventory.hardcodedTexts()) {
            translations.put(text, text.equals("Saved") ? "Saved" : "ES " + text);   // the model left "Saved" as it was
        }
        inventory.putTranslation("es", "Coupon code", "Código de cupón");   // came through the resources
        patcher = new SmaliPatcher(app.toString());
        applied = patcher.patch(inventory.hardcodedPlaces(), translations);
    }

    private Map<String, String> rows(Path csv) throws Exception {
        // text + method (or element) -> the whole row
        Map<String, String> out = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertEquals("kind;text;translation;action;reason;file;line;class;method;use;via", lines.get(0));
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(";");
            out.put(f[1] + "@" + f[8], line);
        }
        return out;
    }

    @Test
    void everyPlaceHasARowThatSaysWhatWasDoneAndWhy() throws Exception {
        Path csv = tmp.resolve("hardcoded_es.csv");

        Map<String, Integer> counts = HardcodedLog.write(csv, inventory, "es", translations, applied, null);

        Map<String, String> rows = rows(csv);
        assertEquals(19 + 3, rows.size(), "the 19 places of the code and the 3 of the XML");
        assertEquals("code;\"Welcome back\";\"ES Welcome back\";patched;\"\";\"smali/com/example/shop/free/FreeActivity.smali\";26;"
                + "\"com.example.shop.free.FreeActivity\";\"onCreate\";ui;\"Landroid/widget/TextView;->setText\"",
                rows.get("\"Welcome back\"@\"onCreate\""));
        // one literal, three places, three outcomes
        assertTrue(rows.get("\"Premium\"@\"showPlan\"").contains(";\"ES Premium\";patched;"));
        assertTrue(rows.get("\"Premium\"@\"isPremium\"").contains(";\"\";not translated;\"this place: used as data (key, query, pattern, comparison)\";"));
        assertTrue(rows.get("\"Premium\"@\"planName\"").contains(";not translated;\"this place: returned by the method\";"));
        // why the rest was left alone
        assertTrue(rows.get("\"onCreate called with saved state\"@\"onCreate\"").contains(";not translated;\"log message\";"));
        assertTrue(rows.get("\" LIMIT 0\"@\"limit\"").contains(";not translated;\"use not known: part of a text whose destination is not known\";"));
        assertTrue(rows.get("\"Shop\"@\"onCreate\"").contains(";not translated;\"application name\";"));
        assertTrue(rows.get("\"Saved\"@\"showSaved\"").contains(";not translated;\"the translation equals the source\";"));
        // the XML: moved to a resource, with the translation that resource got
        assertEquals("xml;\"Coupon code\";\"Código de cupón\";extracted;\"@string/itdroid_hc_3\";\"res/layout/activity_main.xml\";5;"
                + "\"EditText @id/coupon\";\"\";xml;\"android:hint\"", rows.get("\"Coupon code\"@\"\""));
        assertTrue(rows.get("\"Add to cart\"@\"\"").contains(";extracted;\"@string/itdroid_hc_1 (no translation for this language)\";"));

        assertEquals(Map.of("patched", 7, "not translated", 12, "extracted", 3), counts);
    }

    @Test
    void whenTheBuildWithThePatchesIsDiscardedTheRowsSayReverted() throws Exception {
        Path csv = tmp.resolve("hardcoded_es.csv");

        Map<String, Integer> counts = HardcodedLog.write(csv, inventory, "es", translations, applied,
                "the app crashed during the replay: java.lang.IllegalStateException");

        assertEquals(7, counts.get("reverted"));
        assertTrue(rows(csv).get("\"Welcome back\"@\"onCreate\"")
                .contains(";reverted;\"the app crashed during the replay: java.lang.IllegalStateException\";"));
        assertTrue(rows(csv).get("\"Coupon code\"@\"\"").contains(";extracted;"), "the resources are not reverted");
    }
}

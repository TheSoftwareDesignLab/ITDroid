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

/** What {@code hardcoded_<lang>.csv} says became of every place a hardcoded string is written in. */
class HardcodedLogTest {

    private static final String APPLICATION_ID = "com.example.shop.free";

    @TempDir
    Path tmp;

    private StringInventory inventory;
    private CodeLiteralExtractor.Extraction codeExtraction;
    private Map<String, String> kept;

    @BeforeEach
    void translateTheApp() throws Exception {
        Path app = tmp.resolve("shop");
        FileUtils.copyDirectory(Fixtures.decodedApp().toFile(), app.toFile());
        List<XmlLiterals.Literal> literals = XmlLiterals.find(app.toString());
        List<HardcodedStringFinder.Hit> hits = HardcodedStringFinder.hits(Fixtures.scan(app.toString(), APPLICATION_ID));
        XmlLiterals.Extraction extraction = XmlLiterals.extract(app.toString(), literals, Set.of("Shop"));
        // the literals of the first dex stay in the code, the one of the second is moved
        codeExtraction = CodeLiteralExtractor.extract(app.toString(),
                StringInventory.placesToTranslate(app.toString(), hits, HardcodedPolicy.UI), Set.of("smali"));
        inventory = StringInventory.collect(app.toString(), APPLICATION_ID, new NotTranslatableStringsDictionary(List.of()),
                hits, literals, HardcodedPolicy.UI, extraction, codeExtraction);

        String moved = codeExtraction.valueByName.get(codeExtraction.nameByText.get("Your cart is empty"));
        inventory.putTranslation("es", moved, "Tu carrito está vacío");
        inventory.putTranslation("es", "Coupon code", "Código de cupón");   // came through the resources
        kept = new LinkedHashMap<>();
        kept.put("Add to cart", "a placeholder was lost");
    }

    private Map<String, String> rows(Path csv) throws Exception {
        // text + method (or element) -> the whole row
        Map<String, String> out = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertEquals("kind;text;translation;outcome;reason;file;line;class;method;use;via", lines.get(0));
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(";");
            out.put(f[1] + "@" + f[8], line);
        }
        return out;
    }

    @Test
    void everyPlaceHasARowThatSaysWhatBecameOfItAndWhy() throws Exception {
        Path csv = tmp.resolve("hardcoded_es.csv");

        Map<String, Integer> counts = HardcodedLog.write(csv, inventory, "es", kept, null);

        Map<String, String> rows = rows(csv);
        assertEquals(19 + 3, rows.size(), "the 19 places of the code and the 3 of the XML");
        assertEquals("code;\"Your cart is empty\";\"Tu carrito está vacío\";translated;\"@string/"
                + codeExtraction.nameByText.get("Your cart is empty") + "\";\"smali_classes2/com/example/shop/free/CartScreenKt.smali\";",
                rows.get("\"Your cart is empty\"@\"EmptyCart\"").substring(0, rows.get("\"Your cart is empty\"@\"EmptyCart\"").indexOf(".smali\";") + 8));
        // chosen for translation, but its dex file has no room for the call that reads the resource
        assertTrue(rows.get("\"Welcome back\"@\"onCreate\"").contains(";\"\";not moved;\"its dex file (smali) is at the limit of 65536"),
                rows.get("\"Welcome back\"@\"onCreate\""));
        // one literal, three places, three outcomes
        assertTrue(rows.get("\"Premium\"@\"showPlan\"").contains(";not moved;"));
        assertTrue(rows.get("\"Premium\"@\"isPremium\"").contains(";\"\";not translated;\"this place: used as data (key, query, pattern, comparison)\";"));
        assertTrue(rows.get("\"Premium\"@\"planName\"").contains(";not translated;\"this place: returned by the method\";"));
        // why the rest was left alone
        assertTrue(rows.get("\"onCreate called with saved state\"@\"onCreate\"").contains(";not translated;\"log message\";"));
        assertTrue(rows.get("\" LIMIT 0\"@\"limit\"").contains(";not translated;\"use not known: part of a text whose destination is not known\";"));
        assertTrue(rows.get("\"Shop\"@\"onCreate\"").contains(";not translated;\"application name\";"));
        // the XML is moved to a resource and shows the translation that resource got, or why it has none
        assertEquals("xml;\"Coupon code\";\"Código de cupón\";translated;\"@string/itdroid_hc_3\";\"res/layout/activity_main.xml\";5;"
                + "\"EditText @id/coupon\";\"\";xml;\"android:hint\"", rows.get("\"Coupon code\"@\"\""));
        assertTrue(rows.get("\"Add to cart\"@\"\"").contains(";kept in source;\"@string/itdroid_hc_1: a placeholder was lost\";"));
        assertTrue(rows.get("\"Adds the product to the cart\"@\"\"").contains(";kept in source;\"@string/itdroid_hc_2: no translation for this language\";"));

        assertEquals(Map.of("translated", 2, "not moved", 7, "not translated", 11, "kept in source", 2), counts);
    }

    @Test
    void whenTheTranslatedCodeLiteralsAreDroppedTheRowsSaySo() throws Exception {
        Path csv = tmp.resolve("hardcoded_es.csv");

        Map<String, Integer> counts = HardcodedLog.write(csv, inventory, "es", kept,
                "the app crashed during the replay: java.lang.IllegalStateException");

        assertTrue(rows(csv).get("\"Your cart is empty\"@\"EmptyCart\"")
                .contains(";\"\";kept in source;\"the app crashed during the replay: java.lang.IllegalStateException\";"));
        assertTrue(rows(csv).get("\"Coupon code\"@\"\"").contains(";translated;"), "the resources of the XML are not dropped");
        assertEquals(1, counts.get("translated"));
    }
}

package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import uniandes.tsdl.itdroid.Fixtures;

/** Moving the hardcoded strings of the code to resources, on a COPY of the hand-written app. */
class CodeLiteralExtractorTest {

    private static final String APPLICATION_ID = "com.example.shop.free";
    private static final String CALL = "invoke-static/range {%1$s .. %1$s}, Litdroid/hc/Strings;->t(Ljava/lang/String;)Ljava/lang/String;";

    @TempDir
    Path tmp;

    private Path app;
    private Path freeActivity;
    private Path cartRepository;
    private SmaliScan scan;
    private List<HardcodedStringFinder.Hit> hits;

    @BeforeEach
    void copyTheApp() throws Exception {
        app = tmp.resolve("shop");
        FileUtils.copyDirectory(Fixtures.decodedApp().toFile(), app.toFile());
        freeActivity = app.resolve("smali/com/example/shop/free/FreeActivity.smali");
        cartRepository = app.resolve("smali/com/example/shop/free/CartRepository.smali");
        scan = Fixtures.scan(app.toString(), APPLICATION_ID);
        hits = HardcodedStringFinder.hits(scan);
    }

    private static List<String> lines(Path file) throws Exception {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    private static String name(String text) {
        return "itdroid_code_" + Integer.toHexString(text.hashCode());
    }

    private CodeLiteralExtractor.Extraction extract(HardcodedPolicy policy) throws Exception {
        return CodeLiteralExtractor.extract(app.toString(), StringInventory.placesToTranslate(app.toString(), hits, policy), Set.of());
    }

    private StringInventory inventory(HardcodedPolicy policy, CodeLiteralExtractor.Extraction extraction) throws Exception {
        // the scan made before the extraction, whose places the extraction recorded
        return StringInventory.collect(app.toString(), APPLICATION_ID, new NotTranslatableStringsDictionary(List.of()),
                hits, XmlLiterals.find(app.toString()), policy, null, extraction);
    }

    private Map<String, String> strings() throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (Element e : new SAXBuilder().build(app.resolve("res/values/strings.xml").toFile()).getRootElement().getChildren("string")) {
            out.put(e.getAttributeValue("name"), e.getText());
        }
        return out;
    }

    @Test
    void theLiteralStaysAndItsValueIsReadFromAResource() throws Exception {
        CodeLiteralExtractor.Extraction extraction = extract(HardcodedPolicy.UI);

        assertEquals(8, extraction.places.size(), "the eight places the app shows text in");
        assertEquals(8, extraction.names().size());
        assertTrue(extraction.notMoved.isEmpty());

        List<String> code = lines(freeActivity);
        int at = code.indexOf("    const-string v3, \"Welcome back\"");
        assertEquals(25, at, "the literal is where it was");
        assertEquals("    " + String.format(CALL, "v3"), code.get(at + 1));
        assertEquals("    move-result-object v3", code.get(at + 2));
        int jumbo = code.indexOf("    const-string/jumbo v3, \"Checkout now\"");
        assertEquals("    " + String.format(CALL, "v3"), code.get(jumbo + 1));

        // what is not shown is not touched, like the operand of equals() and the returned value
        int operand = code.indexOf("    const-string v0, \"Premium\"");
        assertFalse(code.get(operand + 1).contains("Litdroid/hc/Strings;"));
        assertEquals(Files.readAllLines(Fixtures.decodedApp().resolve("smali/com/example/shop/free/CartRepository.smali")), lines(cartRepository));

        Map<String, String> strings = strings();
        assertEquals("Welcome back", strings.get(name("Welcome back")));
        assertEquals("Premium", strings.get(name("Premium")));
        assertEquals("\"Items: \"", strings.get(name("Items: ")), "quoted, or the resource would lose its edge space");
        assertTrue(Files.readString(app.resolve("res/values/strings.xml")).contains(
                "<string name=\"" + name("Welcome back") + "\" formatted=\"false\">Welcome back</string>"));
        assertEquals("Hello %1$s", strings.get("greeting"), "the strings the app had are still there");
    }

    @Test
    void theClassTheCallsGoToIsAddedInADexFolderOfItsOwn() throws Exception {
        extract(HardcodedPolicy.UI);

        // the app has smali and smali_classes2, so the class goes to a third dex
        Path helper = app.resolve("smali_classes3/itdroid/hc/Strings.smali");
        String smali = Files.readString(helper, StandardCharsets.UTF_8);
        assertTrue(smali.contains(".class public final Litdroid/hc/Strings;"));
        assertTrue(smali.contains(".method public static t(Ljava/lang/String;)Ljava/lang/String;"));
        assertTrue(smali.contains("const-string v3, \"" + CodeLiteralExtractor.PREFIX + "\""), "it builds the same resource names");
        assertTrue(smali.contains("Ljava/lang/Integer;->toHexString(I)Ljava/lang/String;"));
        assertEquals(CodeLiteralExtractor.PREFIX + Integer.toHexString("Saved".hashCode()), CodeLiteralExtractor.resourceName("Saved"));
    }

    @Test
    void theInventoryTreatsAMovedLiteralAsAResourceAndStillSaysWhereItWas() throws Exception {
        CodeLiteralExtractor.Extraction extraction = extract(HardcodedPolicy.UI);
        StringInventory inventory = inventory(HardcodedPolicy.UI, extraction);

        StringInventory.Entry welcome = inventory.entries().stream()
                .filter(e -> e.kind.equals(StringInventory.KIND_HARDCODED) && e.text.equals("Welcome back")).findFirst().get();
        assertTrue(welcome.translatable);
        assertEquals(name("Welcome back"), welcome.extractedTo);
        assertEquals(1, welcome.extractedPlaces.size());
        assertTrue(welcome.notMovedPlaces.isEmpty());
        assertNull(welcome.notMovedReason);
        assertEquals("smali/com/example/shop/free/FreeActivity.smali:26", welcome.origin);

        StringInventory.Entry premium = inventory.entries().stream()
                .filter(e -> e.kind.equals(StringInventory.KIND_HARDCODED) && e.text.equals("Premium")).findFirst().get();
        assertEquals(List.of("showPlan"), premium.extractedPlaces.stream().map(p -> p.method).toList());

        assertTrue(inventory.resourceEntries().stream().anyMatch(e -> e.name.equals(name("Welcome back")) && e.translatable));
        assertTrue(inventory.notMovedCodeLiterals().isEmpty(), "every chosen place reads a resource");
        assertTrue(inventory.extractedResources().contains(name("Saved")));
        assertEquals(8L, inventory.summary().get("hardcodedCodeExtracted"));
        assertEquals(8L, inventory.summary().get("hardcodedPlacesExtracted"));
        assertEquals(0L, inventory.summary().get("hardcodedPlacesNotMoved"));
        assertEquals(8L, inventory.summary().get("hardcodedTranslatable"));

        // the translation the resource gets is the one shown for the literal
        inventory.putTranslation("es", "\"Items: \"", "\"Art\u00edculos: \"");
        Path csv = tmp.resolve("hardcoded_es.csv");
        Map<String, Integer> counts = HardcodedLog.write(csv, inventory, "es", Map.of(), null);
        assertEquals(1, counts.get("translated"));
        assertEquals(7, counts.get("kept in source"), "the others have no translation for this language yet");
        assertTrue(Files.readString(csv, StandardCharsets.UTF_8).contains(
                "code;\"Items: \";\"\"\"Art\u00edculos: \"\"\";translated;\"@string/" + name("Items: ") + "\";"));
    }

    @Test
    void revertPutsEverythingBack() throws Exception {
        List<String> codeBefore = lines(freeActivity);
        String stringsBefore = Files.readString(app.resolve("res/values/strings.xml"));
        CodeLiteralExtractor.Extraction extraction = extract(HardcodedPolicy.UI);
        Path spanish = Files.createDirectories(app.resolve("res/values-es")).resolve("strings.xml");
        Files.writeString(spanish, "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <string name=\"cart_title\">Tu carrito</string>\n"
                + "    <string name=\"" + name("Saved") + "\">Guardado</string>\n</resources>\n");

        extraction.revert();

        assertEquals(codeBefore, lines(freeActivity));
        assertEquals(stringsBefore, Files.readString(app.resolve("res/values/strings.xml")));
        assertFalse(Files.exists(app.resolve("smali_classes3")));
        assertFalse(extraction.active());
        String es = Files.readString(spanish);
        assertTrue(es.contains("Tu carrito") && !es.contains("itdroid_code_"), "a translation of a resource that no longer exists would stop the build");
    }

    @Test
    void aDexWithNoRoomForOneMoreReferenceKeepsItsLiterals() throws Exception {
        Path apk = tmp.resolve("app.apk");
        try (OutputStream out = Files.newOutputStream(apk); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.write(dexHeader(1200, 65536));     // the first dex is full of method references
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("classes2.dex"));
            zip.write(dexHeader(300, 4100));
            zip.closeEntry();
        }
        Set<String> full = CodeLiteralExtractor.fullDexFolders(apk.toString());
        assertEquals(Set.of("smali"), full);

        CodeLiteralExtractor.Extraction extraction = CodeLiteralExtractor.extract(app.toString(),
                StringInventory.placesToTranslate(app.toString(), hits, HardcodedPolicy.UI), full);

        assertEquals(Set.of("Your cart is empty"), extraction.nameByText.keySet(), "only the literal of the second dex");
        assertTrue(extraction.notMoved.get("Welcome back").startsWith("its dex file (smali) is at the limit of 65536 method references"),
                extraction.notMoved.toString());
        assertEquals(Files.readAllLines(Fixtures.decodedApp().resolve("smali/com/example/shop/free/FreeActivity.smali")), lines(freeActivity));
        // what was not moved stays in the source language, with the reason
        StringInventory inventory = inventory(HardcodedPolicy.UI, extraction);
        assertEquals(7L, inventory.summary().get("hardcodedPlacesNotMoved"));
        assertEquals(extraction.notMoved.get("Welcome back"), inventory.notMovedCodeLiterals().get("Welcome back"));
        assertFalse(inventory.notMovedCodeLiterals().containsKey("Your cart is empty"));
    }

    /** A dex header with only the counters ITDroid reads, type_ids_size at 0x40 and method_ids_size at 0x58. */
    private static byte[] dexHeader(int types, int methods) {
        byte[] header = new byte[0x70];
        writeInt(header, 0x40, types);
        writeInt(header, 0x58, methods);
        return header;
    }

    private static void writeInt(byte[] b, int offset, int value) {
        for (int i = 0; i < 4; i++) {
            b[offset + i] = (byte) (value >>> (8 * i));
        }
    }

    @Test
    void twoTextsWithTheSameHashCodeDoNotShareAResource() throws Exception {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        Path file = tmp.resolve("clash/smali/com/acme/app/Screen.smali");
        Files.createDirectories(file.getParent());
        Files.writeString(file, ".class public Lcom/acme/app/Screen;\n.super Landroid/app/Activity;\n\n"
                + ".method public show(Landroid/widget/TextView;)V\n    .locals 1\n\n"
                + "    const-string v0, \"Aa\"\n\n    invoke-virtual {p1, v0}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V\n\n"
                + "    const-string v0, \"BB\"\n\n    invoke-virtual {p1, v0}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V\n\n"
                + "    return-void\n.end method\n", StandardCharsets.UTF_8);
        String decoded = tmp.resolve("clash").toString();
        List<SmaliScan.Occurrence> places = Fixtures.scan(decoded, "com.acme.app").occurrences();

        CodeLiteralExtractor.Extraction extraction = CodeLiteralExtractor.extract(decoded, places, Set.of());

        assertEquals(Set.of("Aa"), extraction.nameByText.keySet());
        assertEquals(1, extraction.places.size());
        assertEquals("the resource name " + name("BB") + " is already in use", extraction.notMoved.get("BB"),
                "the second one stays in the code");
    }

    @Test
    void theResourceGivesBackTheLiteralCharacterByCharacter() {
        assertEquals("Welcome back", CodeLiteralExtractor.resourceValue("Welcome back"));
        assertEquals("Don\\'t \\\"stop\\\"", CodeLiteralExtractor.resourceValue("Don't \"stop\""));
        assertEquals("\" of 3 in your cart\"", CodeLiteralExtractor.resourceValue(" of 3 in your cart"));
        assertEquals("\"two  spaces\"", CodeLiteralExtractor.resourceValue("two  spaces"));
        assertEquals("Line one\\nLine two\\tend", CodeLiteralExtractor.resourceValue("Line one\nLine two\tend"));
        assertEquals("A &amp; B &lt;b&gt;", CodeLiteralExtractor.resourceValue("A & B <b>"));
        assertEquals("\\@home", CodeLiteralExtractor.resourceValue("@home"));
        assertEquals("C:\\\\temp", CodeLiteralExtractor.resourceValue("C:\\temp"));
        assertEquals("50% of %d", CodeLiteralExtractor.resourceValue("50% of %d"), "kept as is: the resource is written with formatted=false");
    }

    @Test
    void theAddedClassIsPackagedWithItdroid() throws Exception {
        try (InputStream in = CodeLiteralExtractor.class.getResourceAsStream("itdroid-strings.smali")) {
            String smali = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(smali.contains(".catch Ljava/lang/Throwable;"), "whatever fails, the app gets its own text back");
            assertTrue(smali.contains("return-object p0"));
        }
        CodeLiteralExtractor.Extraction off = CodeLiteralExtractor.none("moving code literals to resources is turned off");
        assertNull(off.nameByText.get("anything"));
        assertFalse(off.active());
        assertEquals("moving code literals to resources is turned off", off.notMovedReason("Welcome back"));
    }
}

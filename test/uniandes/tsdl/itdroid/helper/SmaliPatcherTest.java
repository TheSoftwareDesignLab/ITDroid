package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import uniandes.tsdl.itdroid.Fixtures;

/** Tests rewriting hardcoded literals place by place in the smali of a copy of the hand-written app. */
class SmaliPatcherTest {

    private static final String APPLICATION_ID = "com.example.shop.free";

    @TempDir
    Path tmp;

    private Path app;
    private Path freeActivity;
    private Path cartRepository;
    private Path mainActivity;
    private SmaliScan scan;
    private SmaliPatcher patcher;

    @BeforeEach
    void copyTheApp() throws IOException {
        app = tmp.resolve("shop");
        FileUtils.copyDirectory(Fixtures.decodedApp().toFile(), app.toFile());
        freeActivity = app.resolve("smali/com/example/shop/free/FreeActivity.smali");
        cartRepository = app.resolve("smali/com/example/shop/free/CartRepository.smali");
        mainActivity = app.resolve("smali/com/example/shop/ui/MainActivity.smali");
        scan = SmaliScan.scan(app.toString(), APPLICATION_ID);
        patcher = new SmaliPatcher(app.toString());
    }

    private static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    private static boolean hasLine(Path file, String trimmedLine) throws IOException {
        return lines(file).stream().anyMatch(l -> l.trim().equals(trimmedLine));
    }

    /** Every place of the scan that holds one of the given texts. */
    private List<SmaliScan.Occurrence> everyPlaceOf(String... texts) {
        List<SmaliScan.Occurrence> out = new ArrayList<>();
        for (SmaliScan.Occurrence o : scan.occurrences()) {
            if (Arrays.asList(texts).contains(o.text)) {
                out.add(o);
            }
        }
        return out;
    }

    /** The places the inventory chooses by default, only where the text is shown. */
    private List<SmaliScan.Occurrence> chosenPlaces() throws Exception {
        return StringInventory.collect(app.toString(), APPLICATION_ID, new NotTranslatableStringsDictionary(Fixtures.settingsDir().toString()),
                HardcodedStringFinder.hits(scan), XmlLiterals.find(app.toString())).hardcodedPlaces();
    }

    @Test
    void replacesALiteralAndKeepsTheInstructionAndRegister() throws IOException {
        List<SmaliPatcher.Patch> patched = patcher.patch(everyPlaceOf("Welcome back", "Checkout now"),
                Map.of("Welcome back", "Bienvenido de nuevo", "Checkout now", "Pagar ahora"));

        assertEquals(2, patched.size());
        assertEquals("Bienvenido de nuevo", patched.get(0).translation);
        assertEquals("smali/com/example/shop/free/FreeActivity.smali:26", patched.get(0).place.origin());
        assertTrue(hasLine(freeActivity, "const-string v3, \"Bienvenido de nuevo\""));
        assertTrue(hasLine(freeActivity, "const-string/jumbo v3, \"Pagar ahora\""));
        assertFalse(hasLine(freeActivity, "const-string v3, \"Welcome back\""));
    }

    @Test
    void writesNonAsciiTextAsUnicodeEscapes() throws IOException {
        patcher.patch(everyPlaceOf("Saved"), Map.of("Saved", "Guardado \u00f1 \u0645"));

        assertTrue(hasLine(freeActivity, "const-string v0, \"Guardado \\u00f1 \\u0645\""));
    }

    @Test
    void keepsTheEdgeSpacesItIsGiven() throws IOException {
        patcher.patch(everyPlaceOf(" of 3 in your cart", "Items: "),
                Map.of(" of 3 in your cart", " de 3 en tu carrito", "Items: ", "Art\u00edculos: "));

        assertTrue(hasLine(freeActivity, "const-string v1, \" de 3 en tu carrito\""));
        assertTrue(hasLine(freeActivity, "const-string v1, \"Art\\u00edculos: \""));
    }

    @Test
    void aTranslationEqualToTheSourceChangesNothing() throws IOException {
        List<String> before = lines(freeActivity);

        assertEquals(0, patcher.patch(everyPlaceOf("Welcome back"), Map.of("Welcome back", "Welcome back")).size());
        assertEquals(before, lines(freeActivity));
        assertTrue(patcher.patchedFiles().isEmpty());
    }

    /** The same literal is a label in showPlan(), the operand of String.equals in isPremium() and a returned value in planName(). */
    @Test
    void onlyTheChosenPlacesArePatched() throws Exception {
        List<String> cartBefore = lines(cartRepository);

        List<SmaliPatcher.Patch> patched = patcher.patch(chosenPlaces(), Map.of("Premium", "De pago"));

        assertEquals(1, patched.size());
        assertEquals("showPlan", patched.get(0).place.method);
        assertEquals(1, lines(freeActivity).stream().filter(l -> l.contains("\"De pago\"")).count());
        assertTrue(hasLine(freeActivity, "const-string v1, \"De pago\""), "the label");
        assertTrue(hasLine(freeActivity, "const-string v0, \"Premium\""), "the operand of equals() keeps its value");
        assertEquals(cartBefore, lines(cartRepository), "the returned value is not touched");
        assertEquals(List.of(freeActivity), patcher.patchedFiles(), "only the file that changed is remembered");
    }

    @Test
    void aLiteralWithoutAChosenPlaceIsNotPatchedEvenIfItHasATranslation() throws Exception {
        List<String> before = lines(cartRepository);

        List<SmaliPatcher.Patch> patched = patcher.patch(chosenPlaces(),
                Map.of("Cart must not be empty", "El carrito no puede estar vac\u00edo", " LIMIT 0", " L\u00cdMITE 0", "Last Sync", "\u00daltima sincronizaci\u00f3n"));

        assertEquals(0, patched.size());
        assertEquals(before, lines(cartRepository));
    }

    @Test
    void restoreOriginalUndoesEveryPatch() throws IOException {
        List<String> freeBefore = lines(freeActivity);
        List<String> cartBefore = lines(cartRepository);

        patcher.patch(everyPlaceOf("Welcome back", "Cart must not be empty"),
                Map.of("Welcome back", "Bienvenido", "Cart must not be empty", "El carrito no puede estar vac\u00edo"));
        assertEquals(2, patcher.patchedFiles().size());
        patcher.restoreOriginal();

        assertEquals(freeBefore, lines(freeActivity));
        assertEquals(cartBefore, lines(cartRepository));
        assertTrue(patcher.patchedFiles().isEmpty());
    }

    @Test
    void patchingAgainStartsFromTheOriginalNotFromThePreviousLanguage() throws IOException {
        patcher.patch(everyPlaceOf("Welcome back", "Saved"), Map.of("Welcome back", "Bienvenido", "Saved", "Guardado"));
        patcher.patch(everyPlaceOf("Welcome back", "Saved"), Map.of("Welcome back", "Bon retour"));

        assertTrue(hasLine(freeActivity, "const-string v3, \"Bon retour\""));
        assertFalse(hasLine(freeActivity, "const-string v3, \"Bienvenido\""));
        assertTrue(hasLine(freeActivity, "const-string v0, \"Saved\""), "the second language had no translation for it");
    }

    /** The launcher activity is outside the applicationId folder and the Compose screen is in the second dex. */
    @Test
    void patchesTheCodeOutsideTheApplicationIdFolderAndInEveryDex() throws Exception {
        List<SmaliPatcher.Patch> patched = patcher.patch(chosenPlaces(),
                Map.of("Add to cart", "A\u00f1adir al carrito", "Your cart is empty", "Tu carrito est\u00e1 vac\u00edo"));

        assertEquals(2, patched.size());
        assertTrue(hasLine(mainActivity, "const-string v0, \"A\\u00f1adir al carrito\""));
        assertTrue(hasLine(app.resolve("smali_classes2/com/example/shop/free/CartScreenKt.smali"),
                "const-string v1, \"Tu carrito est\\u00e1 vac\\u00edo\""));
    }

    /** If the file changed after the scan, the place is skipped instead of patching the wrong line. */
    @Test
    void aPlaceWhoseLineNoLongerHoldsTheLiteralIsSkipped() throws IOException {
        List<SmaliScan.Occurrence> places = everyPlaceOf("Welcome back", "Saved");
        List<String> changed = lines(freeActivity);
        changed.add(0, "# a line added after the scan");
        Files.write(freeActivity, changed, StandardCharsets.UTF_8);

        List<SmaliPatcher.Patch> patched = patcher.patch(places, Map.of("Welcome back", "Bienvenido", "Saved", "Guardado"));

        assertEquals(0, patched.size());
        assertEquals(changed, lines(freeActivity));
    }
}

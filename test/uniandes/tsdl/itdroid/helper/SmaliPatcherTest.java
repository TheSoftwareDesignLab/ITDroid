package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import uniandes.tsdl.itdroid.Fixtures;

/** Rewriting hardcoded literals in the smali of a COPY of the hand-written app. */
class SmaliPatcherTest {

    private static final String APPLICATION_ID = "com.example.shop.free";

    @TempDir
    Path tmp;

    private Path app;
    private Path freeActivity;
    private Path cartRepository;
    private Path mainActivity;

    @BeforeEach
    void copyTheApp() throws IOException {
        app = tmp.resolve("shop");
        FileUtils.copyDirectory(Fixtures.decodedApp().toFile(), app.toFile());
        freeActivity = app.resolve("smali/com/example/shop/free/FreeActivity.smali");
        cartRepository = app.resolve("smali/com/example/shop/free/CartRepository.smali");
        mainActivity = app.resolve("smali/com/example/shop/ui/MainActivity.smali");
    }

    private static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    private static boolean hasLine(Path file, String trimmedLine) throws IOException {
        return lines(file).stream().anyMatch(l -> l.trim().equals(trimmedLine));
    }

    @Test
    void replacesALiteralAndKeepsTheInstructionAndRegister() throws IOException {
        SmaliPatcher patcher = new SmaliPatcher(app.toString(), APPLICATION_ID);

        int patched = patcher.patch(Map.of("Welcome back", "Bienvenido de nuevo", "Checkout now", "Pagar ahora"));

        assertEquals(2, patched);
        assertTrue(hasLine(freeActivity, "const-string v3, \"Bienvenido de nuevo\""));
        assertTrue(hasLine(freeActivity, "const-string/jumbo v3, \"Pagar ahora\""));
        assertFalse(hasLine(freeActivity, "const-string v3, \"Welcome back\""));
    }

    @Test
    void writesNonAsciiTextAsUnicodeEscapes() throws IOException {
        SmaliPatcher patcher = new SmaliPatcher(app.toString(), APPLICATION_ID);

        patcher.patch(Map.of("Saved", "Guardado \u00f1 \u0645"));

        assertTrue(hasLine(freeActivity, "const-string v0, \"Guardado \\u00f1 \\u0645\""));
    }

    @Test
    void keepsTheEdgeSpacesItIsGiven() throws IOException {
        SmaliPatcher patcher = new SmaliPatcher(app.toString(), APPLICATION_ID);

        patcher.patch(Map.of(" of 3 in your cart", " de 3 en tu carrito", "Items: ", "Art\u00edculos: "));

        assertTrue(hasLine(freeActivity, "const-string v1, \" de 3 en tu carrito\""));
        assertTrue(hasLine(freeActivity, "const-string v1, \"Art\\u00edculos: \""));
    }

    @Test
    void aTranslationEqualToTheSourceChangesNothing() throws IOException {
        SmaliPatcher patcher = new SmaliPatcher(app.toString(), APPLICATION_ID);
        List<String> before = lines(freeActivity);

        assertEquals(0, patcher.patch(Map.of("Welcome back", "Welcome back")));
        assertEquals(before, lines(freeActivity));
    }

    @Test
    void restoreOriginalUndoesEveryPatch() throws IOException {
        SmaliPatcher patcher = new SmaliPatcher(app.toString(), APPLICATION_ID);
        List<String> freeBefore = lines(freeActivity);
        List<String> cartBefore = lines(cartRepository);

        patcher.patch(Map.of("Welcome back", "Bienvenido", "Cart must not be empty", "El carrito no puede estar vac\u00edo"));
        patcher.restoreOriginal();

        assertEquals(freeBefore, lines(freeActivity));
        assertEquals(cartBefore, lines(cartRepository));
    }

    @Test
    void patchingAgainStartsFromTheOriginalNotFromThePreviousLanguage() throws IOException {
        SmaliPatcher patcher = new SmaliPatcher(app.toString(), APPLICATION_ID);

        patcher.patch(Map.of("Welcome back", "Bienvenido"));
        patcher.patch(Map.of("Welcome back", "Bon retour"));

        assertTrue(hasLine(freeActivity, "const-string v3, \"Bon retour\""));
        assertFalse(hasLine(freeActivity, "const-string v3, \"Bienvenido\""));
    }

    /**
     * KNOWN GAP (TODO 2.2): a literal is replaced everywhere it appears. "Premium" is a label in
     * {@code showPlan()}, the operand of {@code String.equals} in {@code isPremium()} and a returned
     * value in {@code CartRepository.planName()}; translating all three changes what the code does.
     * Expected after 2.2: only the occurrence shown to the user is patched (1).
     */
    @Test
    void knownGap_theSameLiteralIsReplacedEverywhereItAppears() throws IOException {
        SmaliPatcher patcher = new SmaliPatcher(app.toString(), APPLICATION_ID);

        int patched = patcher.patch(Map.of("Premium", "De pago"));

        assertEquals(3, patched);
        assertEquals(2, lines(freeActivity).stream().filter(l -> l.contains("\"De pago\"")).count());
        assertTrue(hasLine(cartRepository, "const-string v0, \"De pago\""));
    }

    /** The launcher activity is in {@code com.example.shop.ui}, outside the folder of the applicationId. */
    @Test
    void patchesTheCodeOutsideTheApplicationIdFolder() throws IOException {
        SmaliPatcher patcher = new SmaliPatcher(app.toString(), APPLICATION_ID);

        assertEquals(1, patcher.patch(Map.of("Add to cart", "A\u00f1adir al carrito")));
        assertTrue(hasLine(mainActivity, "const-string v0, \"A\\u00f1adir al carrito\""));
    }

    @Test
    void aPatcherBuiltFromAScanSeesTheSameFiles() throws IOException {
        SmaliPatcher patcher = new SmaliPatcher(SmaliScan.scan(app.toString(), APPLICATION_ID));

        assertEquals(2, patcher.patch(Map.of("Shop", "Tienda", "Your cart is empty", "Tu carrito est\u00e1 vac\u00edo")));
        assertTrue(hasLine(mainActivity, "const-string v0, \"Tienda\""));
        assertTrue(hasLine(app.resolve("smali_classes2/com/example/shop/free/CartScreenKt.smali"),
                "const-string v1, \"Tu carrito est\\u00e1 vac\\u00edo\""));
    }
}

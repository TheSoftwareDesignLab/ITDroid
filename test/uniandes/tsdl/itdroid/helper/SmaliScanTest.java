package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import uniandes.tsdl.itdroid.helper.SmaliScan.Consumer;
import uniandes.tsdl.itdroid.helper.SmaliScan.Occurrence;

/** The single pass over the smali: where every literal is and what the code does with it. */
class SmaliScanTest {

    @TempDir
    Path tmp;

    private static Occurrence of(SmaliScan scan, String text) {
        Occurrence found = null;
        for (Occurrence o : scan.occurrences()) {
            if (o.text.equals(text)) {
                assertNull(found, "more than one \"" + text + "\"");
                found = o;
            }
        }
        assertNotNull(found, "no literal \"" + text + "\"");
        return found;
    }

    /** Scans one class {@code com.acme.app.Screen} whose methods are the given smali. */
    private SmaliScan scanMethods(String methods) throws IOException {
        Path file = tmp.resolve("smali/com/acme/app/Screen.smali");
        Files.createDirectories(file.getParent());
        Files.writeString(file, ".class public Lcom/acme/app/Screen;\n.super Landroid/app/Activity;\n\n" + methods, StandardCharsets.UTF_8);
        return SmaliScan.scan(tmp.toString(), "com.acme.app");
    }

    private static String method(String name, String... instructions) {
        return ".method public " + name + "\n    .locals 6\n\n    " + String.join("\n\n    ", instructions) + "\n.end method\n\n";
    }

    // ------------------------------------------------------------------ the hand-written app

    @Test
    void recordsWhereEachLiteralIs() {
        SmaliScan scan = SmaliScan.scan(Fixtures.decodedApp().toString(), "com.example.shop.free");

        assertEquals(List.of("com.example.shop"), scan.packageRoots());
        assertEquals(4, scan.files().size());
        assertEquals(21, scan.occurrences().size(), "every const-string, noise included");

        Occurrence welcome = of(scan, "Welcome back");
        assertEquals("smali/com/example/shop/free/FreeActivity.smali", welcome.file);
        assertEquals(26, welcome.line);
        assertEquals("com.example.shop.free.FreeActivity", welcome.className);
        assertEquals("onCreate", welcome.method);
        assertEquals("v3", welcome.register);
        assertEquals("smali/com/example/shop/free/FreeActivity.smali:26", welcome.origin());

        Occurrence jumbo = of(scan, "Checkout now");
        assertEquals(32, jumbo.line);
        assertEquals("smali_classes2/com/example/shop/free/CartScreenKt.smali", of(scan, "Your cart is empty").file);
        for (int i = 1; i < scan.occurrences().size(); i++) {
            assertEquals(i, scan.occurrences().get(i).order);
        }
    }

    @Test
    void classifiesEachLiteralOfTheHandWrittenApp() {
        SmaliScan scan = SmaliScan.scan(Fixtures.decodedApp().toString(), "com.example.shop.free");

        assertConsumer(scan, "Welcome back", Consumer.UI, "Landroid/widget/TextView;->setText");
        assertConsumer(scan, "Saved", Consumer.UI, "Landroid/widget/Toast;->makeText");
        assertConsumer(scan, "Shop", Consumer.UI, "Lcom/example/shop/ui/MainActivity;->setTitle");
        assertConsumer(scan, "Your cart is empty", Consumer.UI, "Landroidx/compose/material3/TextKt;->Text--4IGK_g");
        // appended to a StringBuilder whose toString() goes to setText
        assertConsumer(scan, "Items: ", Consumer.UI, "Landroid/widget/TextView;->setText");
        assertConsumer(scan, " of 3 in your cart", Consumer.UI, "Landroid/widget/TextView;->setText");

        assertConsumer(scan, "FreeActivity", Consumer.LOG, "Landroid/util/Log;->d");
        assertConsumer(scan, "onCreate called with saved state", Consumer.LOG, "Landroid/util/Log;->d");
        assertConsumer(scan, "Cart must not be empty", Consumer.EXCEPTION, "Ljava/lang/IllegalStateException;-><init>");
        assertConsumer(scan, "SELECT * FROM cart WHERE id = ?", Consumer.DATA, "Landroid/database/sqlite/SQLiteDatabase;->rawQuery");
        assertConsumer(scan, "Last Sync", Consumer.DATA, "Landroid/content/SharedPreferences;->getLong");
        assertConsumer(scan, "dark_mode", Consumer.DATA, "Landroid/content/SharedPreferences;->getBoolean");
        assertConsumer(scan, "view.resources", Consumer.COMPILER, "Lkotlin/jvm/internal/Intrinsics;->checkNotNullExpressionValue");
        assertConsumer(scan, "C(EmptyCart)42@1337L64:CartScreen.kt#x1y2z3", Consumer.COMPILER, "Landroidx/compose/runtime/ComposerKt;->sourceInformation");

        // the destination is outside the method: the scan says so instead of guessing
        assertConsumer(scan, " LIMIT 0", Consumer.CONCATENATION, "");
        assertConsumer(scan, "Bearer ", Consumer.RETURN, "");
    }

    private static void assertConsumer(SmaliScan scan, String text, Consumer consumer, String via) {
        Occurrence o = of(scan, text);
        assertEquals(consumer, o.consumer, "\"" + text + "\"");
        assertEquals(via, o.via, "\"" + text + "\"");
    }

    // ------------------------------------------------------------------ following the register

    @Test
    void followsAGotoToTheInstructionThatUsesTheLiteral() throws IOException {
        // a ternary: both branches load the label, one jumps over the other
        SmaliScan scan = scanMethods(method("label(ZLandroid/widget/TextView;)V",
                "if-eqz p1, :cond_0",
                "const-string v0, \"Enabled\"",
                "goto :goto_0",
                ":cond_0\n    const-string v0, \"Disabled\"",
                ":goto_0\n    invoke-virtual {p2, v0}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V",
                "return-void"));

        assertEquals(Consumer.UI, of(scan, "Enabled").consumer);
        assertEquals(Consumer.UI, of(scan, "Disabled").consumer);
    }

    @Test
    void followsACopyOfTheRegisterAndTheResultOfAFormat() throws IOException {
        SmaliScan scan = scanMethods(
                method("copy(Landroid/widget/TextView;)V",
                        "const-string v0, \"Copied label\"",
                        "move-object v4, v0",
                        "const/4 v0, 0x0",
                        "invoke-virtual {p1, v4}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V",
                        "return-void")
                + method("format(Landroid/widget/TextView;[Ljava/lang/Object;)V",
                        "const-string v0, \"%d items left\"",
                        "invoke-static {v0, p2}, Ljava/lang/String;->format(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;",
                        "move-result-object v1",
                        "invoke-virtual {p1, v1}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V",
                        "return-void"));

        assertEquals(Consumer.UI, of(scan, "Copied label").consumer);
        assertEquals(Consumer.UI, of(scan, "%d items left").consumer);
    }

    @Test
    void aConcatenationInheritsTheDestinationOfTheWholeText() throws IOException {
        SmaliScan scan = scanMethods(method("fail(I)V",
                "new-instance v0, Ljava/lang/StringBuilder;",
                "invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V",
                "const-string v1, \"Unknown id: \"",
                "invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                "move-result-object v0",
                "invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;",
                "move-result-object v0",
                "invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;",
                "move-result-object v0",
                "new-instance v2, Ljava/lang/IllegalArgumentException;",
                "invoke-direct {v2, v0}, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V",
                "throw v2"));

        Occurrence o = of(scan, "Unknown id: ");
        assertEquals(Consumer.EXCEPTION, o.consumer);
        assertEquals("Ljava/lang/IllegalArgumentException;-><init>", o.via);
    }

    @Test
    void tellsFieldsArraysReturnsAndOverwrittenRegistersApart() throws IOException {
        SmaliScan scan = scanMethods(
                method("store()V",
                        "const-string v0, \"Stored title\"",
                        "iput-object v0, p0, Lcom/acme/app/Screen;->title:Ljava/lang/String;",
                        "return-void")
                + method("items([Ljava/lang/String;)V",
                        "const-string v0, \"First item\"",
                        "const/4 v1, 0x0",
                        "aput-object v0, p1, v1",
                        "return-void")
                + method("name()Ljava/lang/String;",
                        "const-string v0, \"Returned name\"",
                        "return-object v0")
                + method("dropped()V",
                        "const-string v0, \"Never used\"",
                        "const/4 v0, 0x1",
                        "return-void"));

        Occurrence stored = of(scan, "Stored title");
        assertEquals(Consumer.FIELD, stored.consumer);
        assertEquals("Lcom/acme/app/Screen;->title:Ljava/lang/String;", stored.via);
        assertEquals(Consumer.ARRAY, of(scan, "First item").consumer);
        assertEquals(Consumer.RETURN, of(scan, "Returned name").consumer);
        assertEquals(Consumer.UNUSED, of(scan, "Never used").consumer);
    }

    @Test
    void aCallTheRulesDoNotKnowIsMarkedAsOfTheAppOrOfALibrary() throws IOException {
        SmaliScan scan = scanMethods(method("unknown(Lorg/thirdparty/Chart;)V",
                "const-string v0, \"Section header\"",
                "invoke-virtual {p0, v0}, Lcom/acme/app/Screen;->addSection(Ljava/lang/String;)V",
                "const-string v1, \"Axis name\"",
                "invoke-virtual {p1, v1}, Lorg/thirdparty/Chart;->axis(Ljava/lang/String;)V",
                "return-void"));

        assertEquals(Consumer.APP_CALL, of(scan, "Section header").consumer);
        assertEquals("Lcom/acme/app/Screen;->addSection", of(scan, "Section header").via);
        assertEquals(Consumer.LIBRARY_CALL, of(scan, "Axis name").consumer);
    }

    /**
     * A composable function of the app that receives a text most likely draws it, but it can also
     * take the name of an icon: the label is translated, the bare identifier is not even listed.
     */
    @Test
    void aTextGivenToAComposableOfTheAppIsTakenForTextUnlessItIsABareIdentifier() throws Exception {
        SmaliScan scan = scanMethods(method("Toolbar(Landroidx/compose/runtime/Composer;I)V",
                "const-string v0, \"Back to the list\"",
                "invoke-static {v0, p0, p1}, Lcom/acme/app/WidgetsKt;->LabelledButton(Ljava/lang/String;Landroidx/compose/runtime/Composer;I)V",
                "const-string v1, \"arrow_back\"",
                "invoke-static {v1, p0, p1}, Lcom/acme/app/WidgetsKt;->SymbolIcon(Ljava/lang/String;Landroidx/compose/runtime/Composer;I)V",
                "const-string v2, \"search\"",
                "invoke-static {v2, p0, p1}, Lcom/acme/app/WidgetsKt;->SymbolIcon(Ljava/lang/String;Landroidx/compose/runtime/Composer;I)V",
                "return-void"));

        assertEquals(Consumer.COMPOSABLE, of(scan, "Back to the list").consumer);
        assertEquals(Consumer.COMPOSABLE, of(scan, "arrow_back").consumer);

        List<HardcodedStringFinder.Hit> hits = HardcodedStringFinder.hits(scan);
        assertEquals(List.of("Back to the list"), hits.stream().map(h -> h.text).toList(), "the icon names are not strings for a person");
        assertEquals(Consumer.COMPOSABLE, hits.get(0).consumer());
        assertTrue(HardcodedPolicy.UI.translates(Consumer.COMPOSABLE));
        assertNull(StringInventory.codeSkipReason(hits.get(0), java.util.Set.of(), HardcodedPolicy.UI));
    }

    // ------------------------------------------------------------------ the rule table

    @Test
    void anIntentKeyIsDataButAStringValueIsLeftOpen() throws IOException {
        SmaliScan scan = scanMethods(method("share(Landroid/content/Intent;I)V",
                "const-string v0, \"count\"",
                "invoke-virtual {p1, v0, p2}, Landroid/content/Intent;->putExtra(Ljava/lang/String;I)Landroid/content/Intent;",
                "const-string v1, \"android.intent.extra.TEXT\"",
                "const-string v2, \"Look at this app\"",
                "invoke-virtual {p1, v1, v2}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;",
                "const-string v3, \"Share with\"",
                "invoke-static {p1, v3}, Landroid/content/Intent;->createChooser(Landroid/content/Intent;Ljava/lang/CharSequence;)Landroid/content/Intent;",
                "return-void"));

        assertEquals(Consumer.DATA, of(scan, "count").consumer);
        assertEquals(Consumer.LIBRARY_CALL, of(scan, "Look at this app").consumer, "the value may be text: not decided here");
        assertEquals(Consumer.UI, of(scan, "Share with").consumer);
    }

    @Test
    void recognisesLoggersTheTableDoesNotName() throws IOException {
        SmaliScan scan = scanMethods(method("log(Lorg/vendor/alogger/ILogger;)V",
                "const-string v0, \"Screen\"",
                "const-string v1, \"Failed to copy image\"",
                "invoke-interface {p1, v0, v1}, Lorg/vendor/alogger/ILogger;->e(Ljava/lang/String;Ljava/lang/String;)V",
                "const-string v2, \"thread started\"",
                "invoke-static {v2}, Lcom/acme/app/ViewUtils;->Debug(Ljava/lang/String;)V",
                "const-string v3, \"Signed in\"",
                "invoke-virtual {p0, v3}, Lcom/acme/app/LoginScreen;->e(Ljava/lang/String;)V",
                "return-void"));

        assertEquals(Consumer.LOG, of(scan, "Failed to copy image").consumer);
        assertEquals(Consumer.LOG, of(scan, "thread started").consumer);
        assertEquals(Consumer.APP_CALL, of(scan, "Signed in").consumer, "LoginScreen is not a logger");
    }

    @Test
    void aRuleFileInTheSettingsFolderIsTriedBeforeTheBuiltInRules() throws IOException {
        Path settings = Files.createDirectories(tmp.resolve("settings"));
        Files.writeString(settings.resolve(ConsumerRules.FILE_NAME),
                "# the charting library of this app draws its labels\n"
                + "ui   ^Lorg/thirdparty/Chart;->axis\\(\n"
                + "data ^Landroid/widget/Toast;->makeText\\(\n"
                + "nonsense ^Lfoo;\n", StandardCharsets.UTF_8);
        ConsumerRules rules = ConsumerRules.load(settings.toString());

        assertEquals(ConsumerRules.Kind.UI, rules.classify("Lorg/thirdparty/Chart;->axis(Ljava/lang/String;)V"));
        assertEquals(ConsumerRules.Kind.DATA, rules.classify("Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;"));
        assertEquals(ConsumerRules.Kind.LOG, rules.classify("Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I"), "the built-in rules still apply");
        assertNull(rules.classify("Lfoo;->bar()V"), "a line with an unknown category is ignored");
        assertEquals(ConsumerRules.defaults().size() + 2, rules.size());
        assertTrue(ConsumerRules.defaults().size() > 80);
    }
}

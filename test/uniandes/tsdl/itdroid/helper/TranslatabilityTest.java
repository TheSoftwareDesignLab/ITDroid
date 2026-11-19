package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;
import java.util.Set;

import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import org.junit.jupiter.api.Test;

import uniandes.tsdl.itdroid.Fixtures;

/** The one place that decides whether a text of the app is translated. */
class TranslatabilityTest {

    private static Element string(String name, String attributes, String value) throws Exception {
        return new SAXBuilder().build(new StringReader("<string name=\"" + name + "\"" + attributes + ">" + value + "</string>")).getRootElement();
    }

    // ------------------------------------------------------------------ resource names

    @Test
    void theElementAndTheNameDecideFirst() throws Exception {
        NotTranslatableStringsDictionary dictionary = new NotTranslatableStringsDictionary(List.of("build_flavor"));

        assertEquals("translatable=\"false\"", Translatability.stringSkip(string("x", " translatable=\"false\"", "Free build"), "Free build",
                dictionary, Set.of()));
        assertEquals("application name", Translatability.stringSkip(string("app_name", "", "Shop"), "Shop", dictionary, Set.of("app_name")));
        assertEquals("not-translatable dictionary", Translatability.stringSkip(string("build_flavor", "", "Free build"), "Free build",
                dictionary, Set.of()));
        assertEquals("not-translatable dictionary", Translatability.stringSkip(string("abc_action_bar_home_description", "", "Navigate home"),
                "Navigate home", dictionary, Set.of()), "the Android names packaged with ITDroid");
        assertNull(Translatability.stringSkip(string("greeting", "", "Hello"), "Hello", dictionary, Set.of()));
        Element sortValues = new SAXBuilder().build(new StringReader("<string-array name=\"sort_values\"><item>newest</item></string-array>"))
                .getRootElement();
        assertEquals("data container name", Translatability.containerSkip(sortValues, dictionary, Set.of()));
    }

    // ------------------------------------------------------------------ resource values

    /** The keys the audit found translated differently per language, cgeo, currencies and org.tasks among them. */
    @Test
    void identifiersInResourceValuesAreNotTranslated() {
        assertEquals("camelCase identifier", Translatability.resourceValueSkip("pref_autorenameDownloads", "autorenameDownloads"));
        assertEquals("identifier", Translatability.resourceValueSkip("language_key", "KEY_LANGUAGE"));
        assertEquals("identifier", Translatability.resourceValueSkip("p_default_tags", "default_tags"));
        assertEquals("date or time pattern", Translatability.resourceValueSkip("date_format", "yyyy-MM-dd"));
        assertEquals("date or time pattern", Translatability.resourceValueSkip("time_format", "HH:mm"));
        assertEquals("date or time pattern", Translatability.resourceValueSkip("long_date", "MMM d, yyyy"), "with spaces as well");
        assertEquals("class name", Translatability.resourceValueSkip("title_activity_setting", "SettingsFragment"));
        assertEquals("URI or address", Translatability.resourceValueSkip("scheme", "myapp://"));
        assertEquals("URI or address", Translatability.resourceValueSkip("provider", "content://com.example.provider/items"));
        assertEquals("URI or address", Translatability.resourceValueSkip("about_lib", "github.com/caarmen/SunriseSunset"));
        assertEquals("path", Translatability.resourceValueSkip("folder", "/sdcard/Download/MyApp"));
        assertEquals("path", Translatability.resourceValueSkip("help", "more/events/#custom-events"));
        assertEquals("dotted name", Translatability.resourceValueSkip("settings_title_gc", "Geocaching.com"));
        assertEquals("key or token", Translatability.resourceValueSkip("oc_us_okapi_consumer_key", "D8LtXqMQv7pVGZg9Y59N"));
        assertEquals("not human-readable text", Translatability.resourceValueSkip("startup", "androidx.startup"));
        assertEquals("MIME type or color", Translatability.resourceValueSkip("share_type", "application/json"));
        assertEquals("MIME type or color", Translatability.resourceValueSkip("accent", "#FF0000"));
    }

    /** pref_, key_, _id and the like make a value without spaces an identifier, unless it is an ordinary word. */
    @Test
    void aNameHintOnlyCountsForAValueWithoutSpacesThatIsNoOrdinaryWord() {
        assertEquals("identifier (by its resource name)", Translatability.resourceValueSkip("pref_theme_key", "theme"));
        assertEquals("identifier (by its resource name)", Translatability.resourceValueSkip("notification_channel_id", "downloads"));
        assertEquals("identifier (by its resource name)", Translatability.resourceValueSkip("pref_value_theme_dark", "DARK"));
        assertNull(Translatability.resourceValueSkip("pref_title_theme", "Theme"), "a capitalised word");
        assertNull(Translatability.resourceValueSkip("actions_label_action", "Action:"));
        assertNull(Translatability.resourceValueSkip("pref_language_key", "Configuración"));
        assertNull(Translatability.resourceValueSkip("pref_sync", "Sync every hour"), "a value with spaces is text");
        assertNull(Translatability.resourceValueSkip("theme", "theme"), "no hint, a lower case word is still a word");
    }

    @Test
    void ordinaryWordsAndSentencesStayTranslatable() {
        for (String text : new String[] {"Delete", "Settings", "Magnet", "OK", "Wi-Fi", "Ko-fi", "Loading...", "e-mail", "Hello %1$s",
                "Yes/No", "and/or", "Year/Month/Day", "Mass", "Gamma", "add", "Done.", "A.M."}) {
            assertNull(Translatability.resourceValueSkip("label", text), text);
        }
    }

    // ------------------------------------------------------------------ code and layout literals

    /** A code literal goes through the same value rules, since it becomes a resource when it is moved. */
    @Test
    void codeAndLayoutLiteralsShareTheValueRules() throws Exception {
        SmaliScan scan = Fixtures.scan(Fixtures.decodedApp().toString(), "com.example.shop.free");
        List<HardcodedStringFinder.Hit> hits = HardcodedStringFinder.hits(scan);
        HardcodedStringFinder.Hit welcome = hits.stream().filter(h -> h.text.equals("Welcome back")).findFirst().get();
        assertNull(Translatability.codeSkip(welcome, Set.of(), HardcodedPolicy.UI));

        XmlLiterals.Literal date = new XmlLiterals.Literal("res/layout/a.xml", 3, "TextView", "", "android:hint", "yyyy-MM-dd");
        XmlLiterals.Literal label = new XmlLiterals.Literal("AndroidManifest.xml", 2, "application", "", "android:label", "Shop");
        XmlLiterals.Literal save = new XmlLiterals.Literal("res/layout/a.xml", 4, "Button", "", "android:text", "Save");
        assertEquals("date or time pattern", Translatability.layoutSkip(date, Set.of()));
        assertEquals("application name", Translatability.layoutSkip(label, Set.of()));
        assertNull(Translatability.layoutSkip(save, Set.of()));
    }

    @Test
    void theSetterRuleTellsTheAppsOwnMethodsApart() {
        assertTrue(Translatability.setterShowsText(false, "Lorg/thirdparty/Card;->setTitle(Ljava/lang/String;Z)V"));
        assertTrue(Translatability.setterShowsText(true, "Lcom/acme/app/Badge;->setText(Ljava/lang/CharSequence;)V"));
        assertTrue(Translatability.setterShowsText(true, "Lcom/acme/app/Badge;->setLabel(Ljava/lang/String;)V"));
        assertFalse(Translatability.setterShowsText(true, "Lcgeo/geocaching/utils/JsonUtils;->setText(Lcom/fasterxml/jackson/databind/node/ObjectNode;Ljava/lang/String;Ljava/lang/String;)V"));
    }

    @Test
    void theUseDecidesUnderEachPolicy() {
        assertTrue(Translatability.translatesUse(HardcodedPolicy.UI, SmaliScan.Consumer.COMPOSABLE));
        assertFalse(Translatability.translatesUse(HardcodedPolicy.UI, SmaliScan.Consumer.APP_CALL));
        assertTrue(Translatability.translatesUse(HardcodedPolicy.UI_AND_UNKNOWN, SmaliScan.Consumer.APP_CALL));
        assertFalse(Translatability.translatesUse(HardcodedPolicy.UI_AND_UNKNOWN, SmaliScan.Consumer.LOG));
        assertTrue(Translatability.translatesUse(HardcodedPolicy.ALL, SmaliScan.Consumer.DATA));
        assertTrue(Translatability.neverShown(SmaliScan.Consumer.EXCEPTION));
        assertEquals(Translatability.neverShown(SmaliScan.Consumer.DATA), SmaliScan.Consumer.DATA.neverShown());
    }

    // ------------------------------------------------------------------ unchanged answers and copies

    @Test
    void wordsToTranslateLeaveOutPlaceholdersNamesFiguresAndAcronyms() {
        assertEquals(3, Translatability.wordsToTranslate("%1$s · tap to place", null));
        assertEquals(0, Translatability.wordsToTranslate("(GPX, GeoJSON, KML)", null));
        assertEquals(1, Translatability.wordsToTranslate("TabGreater Ticker", null));
        assertEquals(3, Translatability.wordsToTranslate("Check your e-mail", null));
        assertEquals(2, Translatability.wordsToTranslate("Walk 5 km now", null));
        assertTrue(Translatability.sameLetters("You might also like", "You might also like."));
        assertFalse(Translatability.sameLetters("...", "..."), "no letters, nothing to compare");
    }

    @Test
    void aCopyOfTheSourceIsASentenceWithTheSameLetters() {
        assertTrue(Translatability.isCopyOfSource("not_playing", "Not playing", "Not playing"));
        assertTrue(Translatability.isCopyOfSource("following", "Following", "Following"), "a word is translated too");
        assertFalse(Translatability.isCopyOfSource("ok", "OK", "OK"), "an acronym");
        assertFalse(Translatability.isCopyOfSource("newpipe", "NewPipe", "NewPipe"), "a name with an inner capital");
        assertFalse(Translatability.isCopyOfSource("drive", "Google Drive", "Google Drive"), "a name");
        assertFalse(Translatability.isCopyOfSource("pref_key", "dark_mode_on", "dark_mode_on"), "an identifier");
        assertFalse(Translatability.isCopyOfSource("cart", "Your cart", "Tu carrito"));
    }

    /** The built-in names are loaded from the jar, a name of one ordinary word is not among them. */
    @Test
    void theBuiltInNamesAreLoadedFromTheJar() {
        assertTrue(Translatability.brands().matcher("Backup to Google Drive").find());
        assertTrue(Translatability.brands().matcher("Support on Ko-fi").find());
        assertFalse(Translatability.brands().matcher("Zoom in to see the signal").find());
        assertFalse(Translatability.brands().matcher("Googles").find(), "whole words only");
    }

    /** forestpond is Forest Pond Free, and its screens say Forest Pond, which ru translated and ar transliterated. */
    @Test
    void theProductNameInsideTheAppNameIsProtectedToo() {
        assertEquals(List.of("Forest Pond Free", "Forest Pond"), Translatability.nameVariants("Forest Pond Free"));
        assertEquals(List.of("Notes Pro - Simple notepad", "Notes Pro", "Notes"), Translatability.nameVariants("Notes Pro - Simple notepad"));
        assertEquals(List.of("SoundPod"), Translatability.nameVariants("SoundPod"));
        assertEquals(List.of(), Translatability.nameVariants(" "));
        assertTrue(Translatability.looksLikeBrand("Forest Pond"));
        assertFalse(Translatability.looksLikeBrand("Notes"), "a single ordinary word is left to translate");
    }
}

package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.json.simple.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import uniandes.tsdl.itdroid.Fixtures;

/** Tests which languages the app ships and which strings each one lacks (the non-internationalized strings). */
class XMLComparatorTest {

    @TempDir
    Path res;

    private String dflt;
    private String es;
    private String ru;
    private String ar;

    @BeforeEach
    void writeTheStringFiles() throws IOException {
        // abc_action_bar_home_description is in the not-translatable dictionary, so it is never counted
        dflt = strings("values", "app_name", "greeting", "cart_title", "terms", "abc_action_bar_home_description");
        es = strings("values-es", "app_name", "greeting", "cart_title", "terms");
        ru = strings("values-ru", "greeting", "app_name");
        ar = res.resolve("values-ar").resolve("strings.xml").toString();   // the app has no Arabic
    }

    /** Each language writes its own text, the same text as the default would be a copy of it. */
    private String strings(String folder, String... names) throws IOException {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n");
        for (String name : names) {
            sb.append("    <string name=\"").append(name).append("\">").append(folder).append(" text of ").append(name).append("</string>\n");
        }
        Path file = res.resolve(folder).resolve("strings.xml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.append("</resources>\n").toString(), StandardCharsets.UTF_8);
        return file.toString();
    }

    private XMLComparator compare(int alpha) {
        return new XMLComparator(new String[] {dflt, es, ru, ar}, alpha, new NotTranslatableStringsDictionary(List.of()), Set.of());
    }

    @Test
    void aLanguageWithEveryStringIsShippedAndTheOthersAreNot() {
        XMLComparator c = compare(0);

        assertEquals(List.of(es), c.getUsefull());
        assertEquals(List.of(ru, ar), c.getUseLess());
        assertTrue(c.hasDefault());
        assertEquals(4, c.defaultStrings());
    }

    @Test
    void keepsTheStringsEachLanguageLacks() {
        XMLComparator c = compare(0);

        assertTrue(c.status(es).shipped);
        assertEquals(List.of(), c.status(es).missing);

        assertFalse(c.status(ru).shipped);
        assertTrue(c.status(ru).hasFile);
        assertEquals(List.of("cart_title", "terms"), c.status(ru).missing, "sorted by name");

        assertFalse(c.status(ar).shipped);
        assertFalse(c.status(ar).hasFile);
        assertEquals(4, c.status(ar).missing.size(), "without a file every default string is missing");
        assertNull(c.status("values-xx/strings.xml"));
    }

    @Test
    void alphaIsHowManyStringsALanguageMayLack() {
        XMLComparator c = compare(2);

        assertEquals(List.of(es, ru), c.getUsefull());
        assertTrue(c.status(ru).shipped);
        assertEquals(2, c.status(ru).missing.size(), "still listed: shipped, but two strings short");
        assertFalse(c.status(ar).shipped, "no file is never shipped, whatever alpha is");
    }

    @Test
    void theReportListsTheNamesOnlyWhenTheLanguageHasAFile() {
        XMLComparator c = compare(0);

        JSONObject russian = c.status(ru).toJson();
        assertEquals(false, russian.get("shippedByApp"));
        assertEquals(true, russian.get("stringsFile"));
        assertEquals(2L, russian.get("missing"));
        assertEquals(List.of("cart_title", "terms"), russian.get("missingNames"));

        JSONObject arabic = c.status(ar).toJson();
        assertEquals(false, arabic.get("stringsFile"));
        assertEquals(4L, arabic.get("missing"));
        assertFalse(arabic.containsKey("missingNames"));
    }

    /** Strings no language translates are not strings a language lacks. */
    @Test
    void defaultStringsThatAreNotTextAreNotCountedAsMissing() throws IOException {
        Files.writeString(Path.of(dflt), "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n"
                + "    <string name=\"greeting\">Hello</string>\n"
                + "    <string name=\"androidx_startup\">androidx.startup</string>\n"
                + "    <string name=\"version_label\">2.1.0</string>\n"
                + "    <string name=\"title_reference\">@string/greeting</string>\n"
                + "    <string name=\"build_flavor\" translatable=\"false\">Free build</string>\n"
                + "</resources>\n", StandardCharsets.UTF_8);

        XMLComparator c = compare(0);

        assertEquals(1, c.defaultStrings());
        assertEquals(List.of(), c.status(ru).missing, "ru defines greeting: nothing is missing");
        assertTrue(c.status(ru).shipped);
        assertEquals(List.of("greeting"), c.status(ar).missing);
    }

    /**
     * soundpod ships values-es with following, not_playing and others holding the English text, so they
     * showed in English on the Spanish screens. Such a copy counts as missing, one word included, while
     * an acronym, a name or an identifier left as it was does not.
     */
    @Test
    void aStringTheLanguageOnlyCopiesFromTheDefaultCountsAsMissing() throws IOException {
        Files.writeString(Path.of(dflt), "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n"
                + "    <string name=\"not_playing\">Not playing</string>\n"
                + "    <string name=\"you_might_also_like\">You might also like</string>\n"
                + "    <string name=\"following\">Following</string>\n"
                + "    <string name=\"ok\">OK</string>\n"
                + "    <string name=\"newpipe\">NewPipe</string>\n"
                + "    <string name=\"github\">Open GitHub</string>\n"
                + "    <string name=\"greeting\">Hello %1$s, welcome back</string>\n"
                + "</resources>\n", StandardCharsets.UTF_8);
        Files.writeString(Path.of(es), "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n"
                + "    <string name=\"not_playing\">Not playing</string>\n"
                + "    <string name=\"you_might_also_like\">You might also like.</string>\n"
                + "    <string name=\"following\">Following</string>\n"
                + "    <string name=\"ok\">OK</string>\n"
                + "    <string name=\"newpipe\">NewPipe</string>\n"
                + "    <string name=\"github\">Abrir GitHub</string>\n"
                + "    <string name=\"greeting\">Hola %1$s, bienvenido</string>\n"
                + "</resources>\n", StandardCharsets.UTF_8);

        XMLComparator c = compare(0);

        List<String> copies = List.of("following", "not_playing", "you_might_also_like");
        assertEquals(copies, c.status(es).copiesOfSource);
        assertEquals(copies, c.status(es).missing, "they count for alpha");
        assertFalse(c.status(es).shipped);
        assertTrue(compare(3).status(es).shipped, "within alpha the language is still shipped, its copies are translated in place");
        JSONObject json = c.status(es).toJson();
        assertEquals(copies, json.get("copiesOfSource"));
        assertEquals(3L, json.get("missing"));
    }

    /** A default string that is an identifier is not expected in any language. */
    @Test
    void identifiersInTheDefaultStringsAreNotCountedAsMissing() throws IOException {
        Files.writeString(Path.of(dflt), "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n"
                + "    <string name=\"greeting\">Hello</string>\n"
                + "    <string name=\"pref_autorenameDownloads\">autorenameDownloads</string>\n"
                + "    <string name=\"language_key\">KEY_LANGUAGE</string>\n"
                + "    <string name=\"date_format\">yyyy-MM-dd</string>\n"
                + "    <string name=\"settings_fragment\">SettingsFragment</string>\n"
                + "    <string name=\"deep_link\">myapp://</string>\n"
                + "    <string name=\"theme_key\">theme</string>\n"
                + "</resources>\n", StandardCharsets.UTF_8);

        XMLComparator c = compare(0);

        assertEquals(1, c.defaultStrings());
        assertEquals(List.of("greeting"), c.status(ar).missing);
    }

    @Test
    void anAppWithoutDefaultStringsShipsNoLanguageAndLacksNothing() throws IOException {
        Files.delete(Path.of(dflt));

        XMLComparator c = compare(0);

        assertFalse(c.hasDefault());
        assertEquals(0, c.defaultStrings());
        assertEquals(List.of(ar), c.getUseLess());
        assertEquals(List.of(es, ru), c.getUsefull(), "the files it does have lack nothing of an empty default");
        assertEquals(0, c.status(ar).missing.size());
    }
}

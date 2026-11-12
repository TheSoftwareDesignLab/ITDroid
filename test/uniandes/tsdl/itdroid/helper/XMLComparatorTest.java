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

import org.json.simple.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import uniandes.tsdl.itdroid.Fixtures;

/** Which languages the app ships, and which strings each one lacks (the non-internationalized strings). */
class XMLComparatorTest {

    @TempDir
    Path res;

    private String dflt;
    private String es;
    private String ru;
    private String ar;

    @BeforeEach
    void writeTheStringFiles() throws IOException {
        // abc_action_bar_home_description is in the dictionary of not-translatable strings: never counted
        dflt = strings("values", "app_name", "greeting", "cart_title", "terms", "abc_action_bar_home_description");
        es = strings("values-es", "app_name", "greeting", "cart_title", "terms");
        ru = strings("values-ru", "greeting", "app_name");
        ar = res.resolve("values-ar").resolve("strings.xml").toString();   // the app has no Arabic
    }

    private String strings(String folder, String... names) throws IOException {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n");
        for (String name : names) {
            sb.append("    <string name=\"").append(name).append("\">text of ").append(name).append("</string>\n");
        }
        Path file = res.resolve(folder).resolve("strings.xml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.append("</resources>\n").toString(), StandardCharsets.UTF_8);
        return file.toString();
    }

    private XMLComparator compare(int alpha) {
        return new XMLComparator(new String[] {dflt, es, ru, ar}, alpha, Fixtures.settingsDir().toString());
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

    /** Every app carries strings no language translates: they are not strings a language lacks. */
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

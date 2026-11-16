package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What ITDroid writes into a results folder must not say whose machine it ran on. Here the home is
 * "ana", ITDroid runs in ana/work and one app's results go to ana/work/results/org.foo.
 */
class PortablePathsTest {

    @TempDir
    Path tmp;

    private Path home;
    private Path work;
    private Path output;
    private PortablePaths paths;

    @BeforeEach
    void aMachine() throws Exception {
        home = Files.createDirectories(tmp.resolve("Users").resolve("ana"));
        work = Files.createDirectories(home.resolve("work"));
        output = Files.createDirectories(work.resolve("results").resolve("org.foo"));
        paths = new PortablePaths(output, work, home);
    }

    private static String backslashes(Path p) {
        return p.toString().replace('/', '\\');
    }

    private static String slashes(Path p) {
        return p.toString().replace('\\', '/');
    }

    @Test
    void aFileOfTheEvaluationIsNamedFromTheResultsFolder() {
        assertEquals("trnsResults/en/screenshots/state_1.png",
                paths.relative(output.resolve("trnsResults").resolve("en").resolve("screenshots").resolve("state_1.png").toString()));
        assertEquals("org.foo-es-aligned-debugSigned.apk", paths.relative(output.resolve("org.foo-es-aligned-debugSigned.apk").toString()));
    }

    @Test
    void aFileSomewhereElseKeepsOnlyItsName() {
        assertEquals("app-release.apk", paths.relative(home.resolve("Downloads").resolve("app-release.apk").toString()));
        assertEquals("", paths.relative(""));
        assertEquals("", paths.relative(null));
    }

    @Test
    void theStartOfAnAbsolutePathIsReplacedInATextWhateverTheSeparator() {
        assertEquals("apktool could not write <output>/hcs.txt", paths.scrub("apktool could not write " + slashes(output) + "/hcs.txt"));
        assertEquals("apktool could not write <output>\\hcs.txt", paths.scrub("apktool could not write " + backslashes(output) + "\\hcs.txt"));
        assertEquals("APK not found: <itdroid>/apks/app.apk", paths.scrub("APK not found: " + slashes(work) + "/apks/app.apk"));
        assertEquals("the model is in ~/models/m.gguf", paths.scrub("the model is in " + slashes(home) + "/models/m.gguf"));
    }

    @Test
    void theMostSpecificFolderWins() {
        // the results folder is inside the working folder, which is inside the home
        assertEquals("<output>/a and <itdroid>/b and ~/c",
                paths.scrub(slashes(output) + "/a and " + slashes(work) + "/b and " + slashes(home) + "/c"));
    }

    @Test
    void onlyAWholePathIsReplaced() {
        // "anabel" and "ana.old" are other folders, and a full stop ending a sentence is not part of the path
        String anabel = slashes(home) + "bel/file.txt";
        String old = slashes(home) + ".old/file.txt";
        assertEquals(anabel, paths.scrub(anabel));
        assertEquals(old, paths.scrub(old));
        assertEquals("The results are in <output>.", paths.scrub("The results are in " + slashes(output) + "."));
        assertEquals("see \"<output>\"", paths.scrub("see \"" + slashes(output) + "\""));
    }

    @Test
    void aTextWithoutPathsIsLeftAsItIs() {
        assertEquals("the app crashed during the replay", paths.scrub("the app crashed during the replay"));
        assertEquals("", paths.scrub(""));
        assertNull(paths.scrub((String) null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyTextOfAReportIsScrubbedAndTheReportItselfIsNotTouched() {
        JSONObject language = new JSONObject();
        language.put("error", "could not read " + slashes(output) + "/noTrnsResults/es/result.json");
        language.put("amIPFs", 3L);
        JSONArray crashes = new JSONArray();
        crashes.add("FileNotFoundException: " + backslashes(home) + "\\data.db");
        language.put("crashes", crashes);
        JSONObject report = new JSONObject();
        report.put("appName", "org.foo");
        JSONObject languages = new JSONObject();
        languages.put("es", language);
        report.put("langsReport", languages);

        JSONObject clean = (JSONObject) paths.scrub((Object) report);

        JSONObject es = (JSONObject) ((JSONObject) clean.get("langsReport")).get("es");
        assertEquals("could not read <output>/noTrnsResults/es/result.json", es.get("error"));
        assertEquals("FileNotFoundException: ~\\data.db", ((JSONArray) es.get("crashes")).get(0));
        assertEquals(3L, es.get("amIPFs"));
        assertEquals("org.foo", clean.get("appName"));
        assertFalse(clean.toJSONString().contains("ana"), clean.toJSONString());
        // the report the run keeps working with still has its paths
        assertTrue(String.valueOf(language.get("error")).contains(slashes(output)));
    }

    @Test
    void theHashSaysWhichFileItWasWithoutSayingWhereItWas() throws Exception {
        Path file = tmp.resolve("abc.txt");
        Files.write(file, "abc".getBytes(StandardCharsets.US_ASCII));

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", PortablePaths.sha256(file));
    }

    @Test
    void theSectionAboutTheMachineHasNoUserName() {
        JSONObject environment = RunMetrics.environment(null, null, null);

        JSONObject host = (JSONObject) environment.get("host");
        assertFalse(host.containsKey("user"), host.toJSONString());
        assertFalse(host.containsKey("hostname"), host.toJSONString());
        assertTrue(host.containsKey("os"));
    }
}

package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the report server tells the page about a results folder, its runs, their status and their files. */
class ReportServerTest {

    @TempDir
    Path results;

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final ReportServer.Processes NOBODY = (pid, startedAt) -> false;
    private static final ReportServer.Processes EVERYBODY = (pid, startedAt) -> true;

    @SuppressWarnings("unchecked")
    private static JSONObject run(String status, Long pid) {
        JSONObject it = new JSONObject();
        if (status != null) {
            it.put("status", status);
        }
        if (pid != null) {
            it.put("pid", pid);
            it.put("processStartedAt", "2026-11-13T11:00:00Z");
        }
        return it;
    }

    private Path evaluation(String name, String reportJson, String... files) throws Exception {
        Path dir = Files.createDirectories(results.resolve(name));
        Files.writeString(dir.resolve("report.json"), reportJson, StandardCharsets.UTF_8);
        for (String file : files) {
            Path f = dir.resolve(file);
            Files.createDirectories(f.getParent());
            Files.writeString(f, "{}", StandardCharsets.UTF_8);
        }
        return dir;
    }

    private static JSONObject app(JSONObject index, String dir) {
        for (Object o : (JSONArray) index.get("apps")) {
            if (dir.equals(((JSONObject) o).get("dir"))) {
                return (JSONObject) o;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ runs that never finished

    @Test
    void aRunThatSaidHowItEndedKeepsItsStatus() {
        assertArrayEquals(new String[] {"finished", null}, ReportServer.effectiveStatus(run("finished", 1L), false, NOW, NOW, NOBODY));
        assertArrayEquals(new String[] {"aborted", null}, ReportServer.effectiveStatus(run("aborted", 1L), true, NOW, NOW, NOBODY));
        assertArrayEquals(new String[] {"interrupted", null}, ReportServer.effectiveStatus(run("interrupted", 1L), false, NOW, NOW, EVERYBODY));
    }

    @Test
    void aRunningReportWhoseProcessIsAliveIsRunning() {
        // however old the file is, since translating a large app can take long without a step ending
        Instant yesterday = NOW.minus(26, ChronoUnit.HOURS);

        assertArrayEquals(new String[] {"running", null}, ReportServer.effectiveStatus(run("running", 4242L), false, yesterday, NOW, EVERYBODY));
    }

    @Test
    void aRunningReportWhoseProcessIsGoneWasInterrupted() {
        String[] status = ReportServer.effectiveStatus(run("running", 4242L), false, NOW, NOW, NOBODY);

        assertEquals("interrupted", status[0]);
        assertTrue(status[1].contains("process"), status[1]);
    }

    @Test
    void aRunningReportWithoutProcessIdIsJudgedByHowLongAgoItWasWritten() {
        JSONObject older = run("running", null);

        assertArrayEquals(new String[] {"running", null},
                ReportServer.effectiveStatus(older, false, NOW.minus(20, ChronoUnit.MINUTES), NOW, NOBODY));
        String[] stale = ReportServer.effectiveStatus(older, false, NOW.minus(ReportServer.STALE_HOURS, ChronoUnit.HOURS), NOW, EVERYBODY);
        assertEquals("interrupted", stale[0]);
        assertTrue(stale[1].contains("last written"), stale[1]);
    }

    @Test
    void aReportFromBeforeTheStatusExistedIsFinishedOrAborted() {
        assertArrayEquals(new String[] {"finished", null}, ReportServer.effectiveStatus(null, false, NOW, NOW, NOBODY));
        assertArrayEquals(new String[] {"aborted", null}, ReportServer.effectiveStatus(null, true, NOW, NOW, NOBODY));
        assertArrayEquals(new String[] {"finished", null}, ReportServer.effectiveStatus(run(null, null), false, NOW, NOW, NOBODY));
    }

    @Test
    void thisVeryProcessIsAliveAndAnotherOneWithItsIdIsNotTheRun() {
        ProcessHandle me = ProcessHandle.current();
        Instant started = me.info().startInstant().orElse(null);

        assertTrue(ReportServer.LIVE.alive(me.pid(), started));
        assertTrue(ReportServer.LIVE.alive(me.pid(), null), "without a start time the id is trusted alone");
        if (started != null) {
            assertFalse(ReportServer.LIVE.alive(me.pid(), started.minus(1, ChronoUnit.HOURS)), "the id was reused by a later process");
        }
        assertFalse(ReportServer.LIVE.alive(Long.MAX_VALUE, null));
    }

    @Test
    void theIndexShowsAKilledRunAsInterruptedAndSaysWhy() throws Exception {
        evaluation("org.done", "{\"appName\":\"org.done\",\"itdroid\":{\"status\":\"finished\"}}");
        Path killed = evaluation("org.killed", "{\"appName\":\"org.killed\",\"itdroid\":{\"status\":\"running\",\"pid\":4242}}");

        JSONObject done = ReportServer.summarize(results.resolve("org.done"), results.resolve("org.done").resolve("report.json"), NOBODY);
        JSONObject interrupted = ReportServer.summarize(killed, killed.resolve("report.json"), NOBODY);
        JSONObject alive = ReportServer.summarize(killed, killed.resolve("report.json"), EVERYBODY);

        assertEquals("finished", done.get("status"));
        assertNull(done.get("statusNote"));
        assertEquals("interrupted", interrupted.get("status"));
        assertNotNull(interrupted.get("statusNote"));
        assertEquals("running", alive.get("status"));
    }

    @Test
    void aReportThatCannotBeReadIsListedAsUnreadable() throws Exception {
        evaluation("org.broken", "{\"appName\":\"org.brok");

        JSONObject entry = app(ReportServer.scan(results), "org.broken");

        assertEquals("unreadable", entry.get("status"));
    }

    // ------------------------------------------------------------------ where each language was explored

    @Test
    void theIndexSaysInWhichFolderEachLanguageWasExplored() throws Exception {
        // the app shipped Arabic, ITDroid translated Spanish, and Russian failed before it was explored
        evaluation("org.foo", "{\"appName\":\"org.foo\",\"dfltLang\":\"en\",\"langsReport\":{"
                + "\"en\":{\"dflt\":true,\"amStates\":3},\"ar\":{\"amIPFs\":2},\"es\":{\"amIPFs\":5},\"ru\":{\"error\":\"build failed\"}}}",
                "trnsResults/en/result.json", "trnsResults/ar/result.json", "noTrnsResults/es/result.json");

        JSONObject entry = app(ReportServer.scan(results), "org.foo");

        List<String> folders = new ArrayList<>();
        for (Object o : (JSONArray) entry.get("languages")) {
            JSONObject l = (JSONObject) o;
            folders.add(l.get("code") + "=" + l.get("folder"));
        }
        folders.sort(null);
        assertEquals(List.of("ar=trnsResults", "en=trnsResults", "es=noTrnsResults", "ru=null"), folders);
        assertEquals(7L, entry.get("totalIPFs"));
    }

    // ------------------------------------------------------------------ the files of one evaluation

    @Test
    void theFilesOfAnEvaluationAreListedWithTheirPathAndSize() throws Exception {
        evaluation("org.foo", "{}", "hcs.txt", "trnsResults/en/result.json", "noTrnsResults/es/replay_report.json",
                "trnsResults/en/screenshots/state_1.png", "trnsResults/en/screenshots/state_2.png", "noTrnsResults/es/screenshots/state_1.png");

        JSONObject listing = ReportServer.listFiles(results, "org.foo");

        List<String> paths = new ArrayList<>();
        for (Object o : (JSONArray) listing.get("files")) {
            paths.add(String.valueOf(((JSONObject) o).get("path")));
        }
        assertEquals(List.of("hcs.txt", "noTrnsResults/es/replay_report.json", "report.json", "trnsResults/en/result.json"), paths);
        assertEquals(2L, ((JSONObject) ((JSONArray) listing.get("files")).get(0)).get("size"));
        // screenshots are counted, not listed
        JSONObject shots = (JSONObject) listing.get("screenshots");
        assertEquals(2L, shots.get("trnsResults/en/screenshots"));
        assertEquals(1L, shots.get("noTrnsResults/es/screenshots"));
        assertNull(listing.get("error"));
    }

    @Test
    void onlyAnEvaluationOfTheServedFolderCanBeListed() throws Exception {
        evaluation("org.foo", "{}", "trnsResults/en/result.json");

        for (String dir : new String[] {"..", "", "org.foo/trnsResults", "../" + results.getFileName(), "org.missing", "org.foo/../.."}) {
            JSONObject listing = ReportServer.listFiles(results, dir);

            assertNotNull(listing.get("error"), "'" + dir + "' must not be listed");
            assertEquals(0, ((JSONArray) listing.get("files")).size(), dir);
        }
    }
}

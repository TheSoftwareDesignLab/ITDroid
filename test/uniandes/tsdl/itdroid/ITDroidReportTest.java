package uniandes.tsdl.itdroid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What report.json says about the run itself, how it ended and no path of the machine it ran on. */
class ITDroidReportTest {

    @TempDir
    Path out;

    @AfterEach
    void forgetTheRun() {
        ITDroid.report = null;
        ITDroid.outputPath = null;
    }

    @SuppressWarnings("unchecked")
    private void aRunWithStatus(String status) {
        JSONObject info = new JSONObject();
        info.put("status", status);
        info.put("startedAt", "2026-11-13T10:00:00Z");
        ITDroid.report = new JSONObject();
        ITDroid.report.put("appName", "org.foo");
        ITDroid.report.put("itdroid", info);
        ITDroid.outputPath = out.toString();
    }

    private JSONObject written() throws Exception {
        return (JSONObject) new JSONParser().parse(Files.readString(out.resolve("report.json"), StandardCharsets.UTF_8));
    }

    @Test
    void aRunStoppedFromOutsideLeavesItsReportSayingSo() throws Exception {
        aRunWithStatus("running");

        ITDroid.markInterrupted();   // what the JVM runs on Ctrl+C

        JSONObject info = (JSONObject) written().get("itdroid");
        assertEquals("interrupted", info.get("status"));
        assertNotNull(info.get("finishedAt"));
        assertEquals("2026-11-13T10:00:00Z", info.get("startedAt"));
    }

    @Test
    void aRunThatAlreadySaidHowItEndedIsNotTouched() {
        aRunWithStatus("finished");

        ITDroid.markInterrupted();   // the JVM also runs it when the run ends normally

        assertEquals("finished", ((JSONObject) ITDroid.report.get("itdroid")).get("status"));
        assertFalse(Files.exists(out.resolve("report.json")), "nothing to write: the run had already written its last report");
    }

    @Test
    void nothingHappensWhenTheRunNeverGotToBuildAReport() {
        ITDroid.report = null;
        ITDroid.outputPath = out.toString();

        ITDroid.markInterrupted();

        assertFalse(Files.exists(out.resolve("report.json")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theReportOnDiskCarriesNoPathOfThisMachine() throws Exception {
        aRunWithStatus("running");
        String apk = out.resolve("app.apk").toString();
        String elsewhere = System.getProperty("user.home") + File.separator + "private" + File.separator + "notes.txt";
        ITDroid.report.put("error", "APK not found: " + apk);
        ITDroid.report.put("note", "could not read " + elsewhere);

        ITDroid.writeReport();

        JSONObject report = written();
        assertEquals("APK not found: <output>" + File.separator + "app.apk", report.get("error"));
        assertTrue(String.valueOf(report.get("note")).endsWith("private" + File.separator + "notes.txt"), String.valueOf(report.get("note")));
        assertFalse(String.valueOf(report.get("note")).contains(System.getProperty("user.home")), String.valueOf(report.get("note")));
        // the run keeps working with the real paths
        assertEquals("APK not found: " + apk, ITDroid.report.get("error"));
    }
}

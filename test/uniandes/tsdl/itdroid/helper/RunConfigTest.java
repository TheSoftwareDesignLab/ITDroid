package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The keys of the configuration file that have a default: what they are when absent and what they accept. */
class RunConfigTest {

    @TempDir
    Path tmp;

    private RunConfig config(String... lines) throws Exception {
        Path file = tmp.resolve("itdroid.config.properties");
        Files.writeString(file, String.join("\n", lines), StandardCharsets.UTF_8);
        return RunConfig.load(file.toString());
    }

    @Test
    void theDefaultsOfTheOptionalKeys() throws Exception {
        RunConfig cfg = config("apkPath=app.apk");

        assertEquals(0, cfg.getAlpha());
        assertEquals(HardcodedPolicy.UI, cfg.getHardcodedPolicy());
        assertTrue(cfg.isExtractCode());
        assertEquals("", cfg.getCodePackages());
        assertTrue(cfg.isLaunchTest());
        assertTrue(cfg.isReuseInventory());
        assertEquals(false, cfg.isGraphDumps());
        assertTrue(cfg.isKeepLanguageApks());
        assertEquals(0.5, cfg.getMinSimilarity());
        assertEquals(5, cfg.getMaxStates());
        assertEquals(8, cfg.getMaxClickables());
        assertEquals(0, cfg.getMaxMinutes());
        assertEquals("", cfg.getSetupFile());
        assertEquals(false, cfg.isRtlRepair());
    }

    @Test
    void theContentOfTheDeviceAndTheRepairOfRightToLeftLayoutsAreOneLineEach() throws Exception {
        RunConfig cfg = config("exploration.setup = data/setup.txt ", "rtl.repair=true");

        assertEquals("data/setup.txt", cfg.getSetupFile());
        assertTrue(cfg.isRtlRepair());
        assertEquals("", config("exploration.setup=").getSetupFile(), "the empty line of the example file means none");
    }

    @Test
    void theBudgetOfTheExplorationIsThreeLinesAndZeroMeansNoLimit() throws Exception {
        RunConfig cfg = config("exploration.maxStates=20", "exploration.maxClickables=0", "exploration.maxMinutes=30");
        assertEquals(20, cfg.getMaxStates());
        assertEquals(0, cfg.getMaxClickables());
        assertEquals(30, cfg.getMaxMinutes());

        // a negative number is not a smaller limit: it reads as no limit, like 0
        RunConfig none = config("exploration.maxStates=-1", "exploration.maxClickables=-5", "exploration.maxMinutes=-2");
        assertEquals(0, none.getMaxStates());
        assertEquals(0, none.getMaxClickables());
        assertEquals(0, none.getMaxMinutes());
    }

    @Test
    void theShareOfElementsThatMakesTwoScreensTheSameOneIsOneLine() throws Exception {
        assertEquals(0.35, config("comparison.minSimilarity=0.35").getMinSimilarity());
        assertEquals(1.0, config("comparison.minSimilarity = 1").getMinSimilarity());
        assertEquals(0.0, config("comparison.minSimilarity=0").getMinSimilarity());
    }

    @Test
    void theApksOfTheLanguagesCanBeLeftOutOfTheResults() throws Exception {
        assertEquals(false, config("output.keepLanguageApks=false").isKeepLanguageApks());
        assertEquals(false, config("output.keepLanguageApks = no").isKeepLanguageApks());
        assertEquals(true, config("output.keepLanguageApks=true").isKeepLanguageApks());
    }

    @Test
    void aShareOutsideZeroToOneIsRejectedWithTheNameOfTheKey() throws Exception {
        for (String bad : new String[] {"1.5", "-0.1", "half", "50%"}) {
            RunConfig cfg = config("comparison.minSimilarity=" + bad);

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, cfg::getMinSimilarity, bad);

            assertTrue(e.getMessage().contains("comparison.minSimilarity"), e.getMessage());
        }
    }
}

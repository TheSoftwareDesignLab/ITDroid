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
        assertEquals(0.5, cfg.getMinSimilarity());
        assertEquals(5, cfg.getMaxStates());
        assertEquals(8, cfg.getMaxClickables());
    }

    @Test
    void theShareOfElementsThatMakesTwoScreensTheSameOneIsOneLine() throws Exception {
        assertEquals(0.35, config("comparison.minSimilarity=0.35").getMinSimilarity());
        assertEquals(1.0, config("comparison.minSimilarity = 1").getMinSimilarity());
        assertEquals(0.0, config("comparison.minSimilarity=0").getMinSimilarity());
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

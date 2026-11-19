package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The configuration keys that have a default, their value when absent and what they accept. */
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
        assertTrue(cfg.isKeepLanguageApks());
        assertEquals(0.5, cfg.getMinSimilarity());
        assertEquals(5, cfg.getMaxStates());
        assertEquals(8, cfg.getMaxClickables());
        assertEquals(0, cfg.getMaxMinutes());
        assertEquals("", cfg.getSetupFile());
        assertEquals(false, cfg.isRtlRepair());
        assertTrue(cfg.isTextOverGraphic());
        assertTrue(cfg.isTextCut());
        assertEquals(false, cfg.isGrantPermissions(), "the app asks for its permissions as it does with a new user");
    }

    @Test
    void onlyTheApkIsRequiredAndThePackageAndTheDeviceAreOptional() throws Exception {
        RunConfig cfg = config("apkPath=app.apk");

        assertEquals("app.apk", cfg.getApkPath());
        assertEquals("", cfg.getAppPackage(), "empty takes the package of the APK");
        assertEquals("", cfg.getEmulator(), "empty uses the only device connected");
        assertEquals("results", cfg.getResultsDir());
        assertEquals("en", cfg.getSourceLanguage());
        assertEquals(java.util.List.of("es", "ru", "ar"), cfg.getLanguages());
        assertEquals(java.util.List.of(), cfg.getKeepResources());
        assertEquals("", cfg.getExtraRules());
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> config("apkPath=").getApkPath());
        assertTrue(missing.getMessage().contains("apkPath"), missing.getMessage());
    }

    @Test
    void theLanguagesAreOneLineOfAndroidCodes() throws Exception {
        assertEquals(java.util.List.of("fr", "zh-rCN", "hi"), config("languages = fr, zh-rCN ,hi,fr").getLanguages());
        assertEquals("", config("sourceLanguage=").getSourceLanguage(), "empty asks the model");
        assertThrows(IllegalArgumentException.class, () -> config("languages=Spanish").getLanguages());
        assertEquals("Spanish", LanguageBundle.name("es"));
        assertEquals("Chinese (China)", LanguageBundle.name("zh-rCN"));
    }

    @Test
    void autoStartTrueStartsTheModelOfTheConfigAndAnotherModelNameIsRefused() throws Exception {
        String plamo = "hf.co/pfnet/plamo-3-translate-0.8b-GGUF:Q4_K_M";
        assertEquals("", config("apkPath=a.apk").getAutoStart(), "by default nothing is started, the server must be running");
        assertEquals(RunConfig.DEFAULT_MODEL, config("translation.autoStart=true").getAutoStart(), "Ollama with the default model");
        assertEquals("mistral:7b", config("translation.model=mistral:7b", "translation.autoStart=true").getAutoStart());
        assertEquals("", config("translation.autoStart=false").getAutoStart());
        assertEquals("", config("translation.engine=api", "translation.autoStart=true").getAutoStart(), "nothing to start for a remote API");
        assertEquals(plamo, config("translation.model=" + plamo, "translation.autoStart=" + plamo).getAutoStart(), "the same model written twice is fine");
        assertEquals("llama-server -m m.gguf --port 8080", config("translation.engine=llamacpp",
                "translation.autoStart=llama-server -m m.gguf --port 8080").getAutoStart());
        IllegalArgumentException other = assertThrows(IllegalArgumentException.class,
                () -> config("translation.model=" + plamo, "translation.autoStart=mistral:7b").getAutoStart());
        assertTrue(other.getMessage().contains("mistral:7b") && other.getMessage().contains("autoStart=true"), other.getMessage());
    }

    @Test
    void theLookAtThePixelsIsOneLine() throws Exception {
        assertEquals(false, config("comparison.textOverGraphic=false").isTextOverGraphic());
        assertTrue(config("comparison.textOverGraphic = true").isTextOverGraphic());
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

        // a negative number reads as no limit, like 0
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

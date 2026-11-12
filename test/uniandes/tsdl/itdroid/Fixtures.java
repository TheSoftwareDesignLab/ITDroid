package uniandes.tsdl.itdroid;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Locations of the test data under {@code test/fixtures} (copied to the test classpath by Maven). */
public final class Fixtures {

    private Fixtures() {
    }

    /**
     * Real evaluations (batch of 2026-10-19, Hy-MT2, emulator API 33) reduced to what the comparison
     * needs: a trimmed {@code report.json} and the {@code result.json} of the source language and
     * of some target languages. Screenshot paths hold only the file name.
     */
    public static Path evaluations() {
        return resource("/fixtures/evaluations");
    }

    /** One of the stored evaluations, e.g. {@code com.elasticrock.candle_14}. */
    public static Path evaluation(String name) {
        return evaluations().resolve(name);
    }

    /** A small hand-written decoded app (manifest, resources, smali in two dex folders). */
    public static Path decodedApp() {
        return resource("/fixtures/decoded/shop");
    }

    /** A settings folder holding the not-translatable dictionary ({@code strings.xml}). */
    public static Path settingsDir() {
        return resource("/fixtures/settings");
    }

    private static Path resource(String name) {
        URL url = Fixtures.class.getResource(name);
        if (url == null) {
            throw new IllegalStateException("Test fixture not found on the classpath: " + name);
        }
        try {
            return Paths.get(url.toURI());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot resolve the test fixture " + name, e);
        }
    }
}

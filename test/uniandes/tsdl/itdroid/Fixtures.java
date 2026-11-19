package uniandes.tsdl.itdroid;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import uniandes.tsdl.itdroid.helper.CodeLiteralExtractor;
import uniandes.tsdl.itdroid.helper.ConsumerRules;
import uniandes.tsdl.itdroid.helper.HardcodedPolicy;
import uniandes.tsdl.itdroid.helper.HardcodedStringFinder;
import uniandes.tsdl.itdroid.helper.NotTranslatableStringsDictionary;
import uniandes.tsdl.itdroid.helper.SmaliScan;
import uniandes.tsdl.itdroid.helper.StringInventory;
import uniandes.tsdl.itdroid.helper.XmlLiterals;

/** Locations of the test data under {@code test/fixtures} (copied to the test classpath by Maven). */
public final class Fixtures {

    private Fixtures() {
    }

    /**
     * Real evaluations (batch of 2026-10-19, Hy-MT2, API 33) trimmed to what the comparison needs.
     * Screenshot paths hold only the file name.
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

    /** One scan of a decoded app with the packaged consumer rules. */
    public static SmaliScan scan(String decodedPath, String applicationId) {
        return SmaliScan.scan(decodedPath, applicationId, ConsumerRules.load(null));
    }

    /** The inventory of a decoded app as a run builds it, with nothing moved to resources and the default policy. */
    public static StringInventory inventory(String decodedPath, String applicationId) throws IOException {
        return inventory(decodedPath, applicationId, HardcodedPolicy.DEFAULT, null, null);
    }

    /** The same with a policy and what was moved to resources, null for nothing. An empty package scans no code. */
    public static StringInventory inventory(String decodedPath, String applicationId, HardcodedPolicy policy,
            XmlLiterals.Extraction xmlExtraction, CodeLiteralExtractor.Extraction codeExtraction) throws IOException {
        boolean code = applicationId != null && !applicationId.isEmpty();
        return StringInventory.collect(decodedPath, applicationId, new NotTranslatableStringsDictionary(List.of()),
                code ? HardcodedStringFinder.hits(scan(decodedPath, applicationId)) : List.of(),
                code ? XmlLiterals.find(decodedPath) : List.of(), policy, xmlExtraction, codeExtraction);
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

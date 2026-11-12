package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * First test of the suite. Its job is to prove the wiring: the tests under {@code test/} compile
 * against the classes under {@code src/} and run with {@code mvn test}.
 */
class HelperTest {

    @Test
    void levenshteinDistanceOfKnownPairs() {
        assertEquals(3, Helper.levenshteinDistance("kitten", "sitting"));
        assertEquals(4, Helper.levenshteinDistance("", "abcd"));
        assertEquals(4, Helper.levenshteinDistance("abcd", ""));
    }

    @Test
    void levenshteinDistanceIgnoresCase() {
        assertEquals(0, Helper.levenshteinDistance("ITDroid", "itdroid"));
    }
}

package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The Levenshtein distance and the detection of right-to-left languages. */
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

    @Test
    void languagesWrittenFromRightToLeft() {
        for (String code : new String[] {"ar", "he", "iw", "fa", "ur", "ps", "yi", "AR", "ar-rEG", "fa_IR", "he-IL"}) {
            assertTrue(Helper.languageIsRTL(code), code);
        }
        for (String code : new String[] {"en", "es", "ru", "hi", "ja", "zh-rCN", "arn", "", "  "}) {
            assertFalse(Helper.languageIsRTL(code), code);
        }
        assertFalse(Helper.languageIsRTL(null));
    }
}

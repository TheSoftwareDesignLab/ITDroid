package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The detection of right-to-left languages. */
class HelperTest {

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

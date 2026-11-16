package uniandes.tsdl.itdroid.helper;

import java.io.UnsupportedEncodingException;

/** The working directory, which languages are written from right to left, and a Levenshtein distance. */
public class Helper {

	private static Helper instance = null;

	public static Helper getInstance() {
		if (instance == null) {
			instance = new Helper();
		}
		return instance;
	}

	/** ISO codes of right-to-left languages ("iw" is the old Hebrew code Android still uses). */
	private static final java.util.Set<String> RTL_LANGUAGES = java.util.Set.of("ar", "arc", "ckb", "dv", "fa", "ha", "he", "iw",
			"khw", "ks", "ps", "ur", "yi");

	/** True when the language of an Android locale code such as "fa-rIR" is written from right to left. */
	public static boolean languageIsRTL(String code) {
		if (code == null) {
			return false;
		}
		String base = code.toLowerCase().split("[-_]")[0];
		return RTL_LANGUAGES.contains(base);
	}

	public String getCurrentDirectory() throws UnsupportedEncodingException {
		return System.getProperty("user.dir");
	}

	public static int levenshteinDistance(String left, String right) {
		left = left.toLowerCase();
		right = right.toLowerCase();
        int n = left.length();
        int m = right.length();

        if (n == 0) {
            return m;
        } else if (m == 0) {
            return n;
        }

        if (n > m) {
            // the shorter string sizes the row, to use less memory
            final String tmp = left;
            left = right;
            right = tmp;
            n = m;
            m = right.length();
        }

        int[] p = new int[n + 1];

        int i;
        int j;
        int upper_left;
        int upper;

        char rightJ;
        int cost;

        for (i = 0; i <= n; i++) {
            p[i] = i;
        }

        for (j = 1; j <= m; j++) {
            upper_left = p[0];
            rightJ = right.charAt(j - 1);
            p[0] = j;

            for (i = 1; i <= n; i++) {
                upper = p[i];
                cost = left.charAt(i - 1) == rightJ ? 0 : 1;
                p[i] = Math.min(Math.min(p[i - 1] + 1, p[i] + 1), upper_left + cost);
                upper_left = upper;
            }
        }

        return p[n];
    }
}

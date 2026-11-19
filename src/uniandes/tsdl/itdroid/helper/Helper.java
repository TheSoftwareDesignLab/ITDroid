package uniandes.tsdl.itdroid.helper;

import java.io.UnsupportedEncodingException;

/** The working directory and which languages are written from right to left. */
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
}

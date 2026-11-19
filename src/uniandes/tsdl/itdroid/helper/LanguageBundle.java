package uniandes.tsdl.itdroid.helper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The languages of a run, by their Android codes (es, ar, zh-rCN), with their English names. */
public class LanguageBundle {

	private final List<String> codes;

	public LanguageBundle(List<String> codes) {
		this.codes = new ArrayList<>(codes);
	}

	public String[] getSelectedLanguagesAsArray() {
		return codes.toArray(new String[0]);
	}

	/** The English name of a code with its region or script, Chinese (Taiwan) for zh-rTW, or the code itself. */
	public static String name(String code) {
		if (code == null || code.isEmpty()) {
			return "";
		}
		String name = locale(code).getDisplayName(Locale.ENGLISH);
		return name.isEmpty() ? code : name;
	}

	/** The BCP 47 tag of an Android folder code, zh-TW for zh-rTW and sr-Latn for b+sr+Latn. */
	public static String tag(String code) {
		if (code == null) {
			return "";
		}
		if (code.startsWith("b+")) {
			return code.substring(2).replace('+', '-');
		}
		return code.replace("-r", "-").replace('_', '-');
	}

	/** The locale of an Android folder code. */
	public static Locale locale(String code) {
		return Locale.forLanguageTag(tag(code));
	}

	public String printSelectedLanguages() {
		StringBuilder out = new StringBuilder("Selected Languages: " + codes.size() + "\n");
		for (String code : codes) {
			out.append(name(code)).append("\n");
		}
		return out.append("------------\n").toString();
	}
}

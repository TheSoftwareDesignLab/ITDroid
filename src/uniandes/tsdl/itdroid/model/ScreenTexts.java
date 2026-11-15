package uniandes.tsdl.itdroid.model;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.json.simple.JSONObject;

/**
 * Whether a translated version showed other text than the source: of the texts on the source
 * screens, how many are on the translated screens letter for letter.
 *
 * <p>ITDroid translates the string resources of the app and the literals of its Dalvik code. An app
 * whose interface is a web page, a Flutter or a React Native bundle, or that reads its texts from
 * another kind of resource, shows what it does by itself with the language of the device, often the
 * source language. Its translated screens then equal the source and the comparison finds nothing,
 * which reads as "the translation fits" when nothing was translated. This count tells the two apart.
 *
 * <p>It is a fact about the screens, not a verdict: a screen that is mostly data (a list of
 * currencies, a ledger) also keeps most of its texts, and its figures deserve the same caution.
 */
public final class ScreenTexts {

	/** From this share of the source texts found unchanged on, the screens are reported as showing the source text. */
	public static final double SAME_TEXT_SHARE = 0.9;
	/** With fewer source texts than this the share is not judged: two or three texts can be a name and a unit. */
	public static final int MIN_TEXTS = 5;

	/** A text counts when it has a word: two letters of any alphabet in a row. Figures and symbols do not. */
	private static final Pattern WORD = Pattern.compile("\\p{L}{2,}");

	private final Set<String> source = new LinkedHashSet<>();
	private final Set<String> unchanged = new LinkedHashSet<>();

	/**
	 * The distinct texts a screen shows or announces: the text and the content description of every
	 * element that has a word. What the user typed into a field is left out: the exploration types
	 * the same value in every language.
	 */
	public static Set<String> of(State screen) {
		Set<String> out = new LinkedHashSet<>();
		for (AndroidNode node : screen.getStateNodes()) {
			if (node.getpClass() != null && node.getpClass().contains("EditText")) {
				continue;
			}
			add(out, node.getText());
			add(out, node.getContentDesc());
		}
		return out;
	}

	private static void add(Set<String> out, String value) {
		if (value == null) {
			return;
		}
		String text = value.trim();
		if (WORD.matcher(text).find()) {
			out.add(text);
		}
	}

	/** Counts a source screen and the translated screen that reproduces it. */
	public void add(State sourceScreen, State languageScreen) {
		Set<String> shown = of(languageScreen);
		for (String text : of(sourceScreen)) {
			source.add(text);
			if (shown.contains(text)) {
				unchanged.add(text);
			}
		}
	}

	public int sourceTexts() {
		return source.size();
	}

	public int unchanged() {
		return unchanged.size();
	}

	/** Share of the source texts found unchanged, from 0 to 1; 0 when the screens have no text. */
	public double share() {
		return source.isEmpty() ? 0 : (double) unchanged.size() / source.size();
	}

	/** True when the translated screens show, to all effects, the text of the source. */
	public boolean sameAsSource() {
		return source.size() >= MIN_TEXTS && share() >= SAME_TEXT_SHARE;
	}

	/** {@code sourceTexts}, {@code unchanged}, {@code share} (two decimals) and {@code sameAsSource}, for report.json. */
	@SuppressWarnings("unchecked")
	public JSONObject toJson() {
		JSONObject o = new JSONObject();
		o.put("sourceTexts", (long) source.size());
		o.put("unchanged", (long) unchanged.size());
		o.put("share", Math.round(share() * 100) / 100.0);
		o.put("sameAsSource", sameAsSource());
		return o;
	}
}

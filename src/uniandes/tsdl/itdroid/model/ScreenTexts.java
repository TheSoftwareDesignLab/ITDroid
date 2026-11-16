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

	/** How many of the unchanged texts that are not in the APK the report quotes. */
	public static final int EXAMPLES = 5;

	/** The texts of the APK being evaluated, null when they are not known. */
	private static ApkTexts apkTexts;

	/**
	 * The texts of the app being evaluated: its string resources (every item of an array or a
	 * plural) and the literals found in its code and its XML, as the inventory has them. With them,
	 * the texts that stayed the same are told apart: those the APK has, and those it does not, which
	 * came from the network, the device or the app's data (an ad, a list, a date) and that no
	 * translation of the APK can reach. Null forgets them.
	 */
	public static void setApkTexts(java.util.Collection<String> texts) {
		setApkTexts(texts, "");
	}

	/**
	 * The same, with the readable content of the APK's other files too (lower case): its assets and
	 * native libraries, where a web interface, a React Native bundle or Flutter keep their texts. The
	 * texts should then include the strings of all its code, not only those of the app's packages.
	 */
	public static void setApkTexts(java.util.Collection<String> texts, String files) {
		apkTexts = texts == null ? null : new ApkTexts(texts, files);
	}

	private final Set<String> source = new LinkedHashSet<>();
	private final Set<String> unchanged = new LinkedHashSet<>();
	private final Set<String> notInApk = new LinkedHashSet<>();

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
			if (shown.contains(text) && unchanged.add(text) && apkTexts != null && !apkTexts.has(text)) {
				notInApk.add(text);
			}
		}
	}

	/** Of the texts that stayed the same, the ones the APK does not have; empty when its texts are not known. */
	public Set<String> notInApk() {
		return notInApk;
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

	/**
	 * {@code sourceTexts}, {@code unchanged}, {@code share} (two decimals) and {@code sameAsSource}, for
	 * report.json; with the texts of the APK known, also {@code unchangedNotInApk} and up to
	 * {@link #EXAMPLES} of them in {@code notInApkExamples}.
	 */
	@SuppressWarnings("unchecked")
	public JSONObject toJson() {
		JSONObject o = new JSONObject();
		o.put("sourceTexts", (long) source.size());
		o.put("unchanged", (long) unchanged.size());
		o.put("share", Math.round(share() * 100) / 100.0);
		o.put("sameAsSource", sameAsSource());
		if (apkTexts != null) {
			o.put("unchangedNotInApk", (long) notInApk.size());
			org.json.simple.JSONArray examples = new org.json.simple.JSONArray();
			for (String t : notInApk) {
				if (examples.size() == EXAMPLES) {
					break;
				}
				examples.add(t);
			}
			o.put("notInApkExamples", examples);
		}
		return o;
	}

	/**
	 * Whether a text on a screen comes from the APK: it is one of its texts, or one with its
	 * placeholders filled in ({@code %d tasks} for "3 tasks"), or it is made mostly of one of them
	 * ("Presets" in "Presets (PREVIEW)"). Case is ignored (a button may show its text in capitals),
	 * and so are the escapes and the markup of a resource.
	 */
	static final class ApkTexts {
		private static final Pattern FORMAT = Pattern.compile("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z%]");
		private static final Pattern TAG = Pattern.compile("<[^>]{1,200}>");
		private static final Pattern LETTER = Pattern.compile("\\p{L}");
		/** A text of the APK this short is too common to say that a longer one is made of it. */
		private static final int MIN_PART_LETTERS = 4;

		private final Set<String> exact = new java.util.HashSet<>();
		private final java.util.List<Pattern> formats = new java.util.ArrayList<>();
		private final java.util.List<String> parts = new java.util.ArrayList<>();
		private final java.util.List<Integer> partLetters = new java.util.ArrayList<>();
		private final String files;

		ApkTexts(java.util.Collection<String> texts) {
			this(texts, "");
		}

		ApkTexts(java.util.Collection<String> texts, String files) {
			this.files = files == null ? "" : files;
			for (String raw : texts) {
				String text = normalize(raw);
				if (text.isEmpty()) {
					continue;
				}
				java.util.regex.Matcher m = FORMAT.matcher(text);
				if (m.find()) {
					if (letters(FORMAT.matcher(text).replaceAll(" ")) < MIN_PART_LETTERS) {
						continue;   // "%s", "%1$s: %2$s": a format with no words of its own would match any text
					}
					StringBuilder regex = new StringBuilder();
					int last = 0;
					m.reset();
					while (m.find()) {
						regex.append(Pattern.quote(text.substring(last, m.start()))).append(m.group().equals("%%") ? "%" : ".+?");
						last = m.end();
					}
					regex.append(Pattern.quote(text.substring(last)));
					formats.add(Pattern.compile(regex.toString(), Pattern.DOTALL));
					// the fixed words around the placeholders count as a part of their own
					for (String piece : FORMAT.split(text)) {
						addPart(piece.trim());
					}
				} else {
					exact.add(text);
					addPart(text);
				}
			}
		}

		private void addPart(String text) {
			int n = letters(text);
			if (n >= MIN_PART_LETTERS) {
				parts.add(text);
				partLetters.add(n);
			}
		}

		/** A text of several lines is in the APK when each line is: an app often joins lines that it keeps apart. */
		boolean has(String shown) {
			if (hasOne(shown)) {
				return true;
			}
			String[] lines = shown == null ? new String[0] : shown.split("\r?\n");
			if (lines.length < 2) {
				return false;
			}
			for (String line : lines) {
				if (!line.trim().isEmpty() && !hasOne(line)) {
					return false;
				}
			}
			return true;
		}

		private boolean hasOne(String shown) {
			String text = normalize(shown);
			if (text.isEmpty() || exact.contains(text)) {
				return true;
			}
			for (Pattern p : formats) {
				if (p.matcher(text).matches()) {
					return true;
				}
			}
			int total = letters(text);
			for (int i = 0; i < parts.size(); i++) {
				if (2 * partLetters.get(i) >= total && text.contains(parts.get(i))) {
					return true;
				}
			}
			// a web page, a script bundle or a compiled Flutter app carries the text in its files
			return !files.isEmpty() && files.contains(text);
		}

		private static int letters(String text) {
			int n = 0;
			java.util.regex.Matcher m = LETTER.matcher(text);
			while (m.find()) {
				n++;
			}
			return n;
		}

		/** Lower case, without the escapes, quotes and markup of a resource, and with single spaces. */
		static String normalize(String raw) {
			if (raw == null) {
				return "";
			}
			String t = raw.trim();
			if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
				t = t.substring(1, t.length() - 1);
			}
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < t.length(); i++) {
				char c = t.charAt(i);
				if (c == '\\' && i + 1 < t.length()) {
					char n = t.charAt(++i);
					if (n == 'n' || n == 't') {
						sb.append(' ');
					} else if (n == 'u' && i + 4 < t.length()) {
						try {
							sb.append((char) Integer.parseInt(t.substring(i + 1, i + 5), 16));
							i += 4;
						} catch (NumberFormatException e) {
							sb.append(n);
						}
					} else {
						sb.append(n);
					}
				} else {
					sb.append(c);
				}
			}
			t = TAG.matcher(sb.toString()).replaceAll("");
			t = t.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
					.replace("&nbsp;", " ").replace(" ", " ").replace("&amp;", "&");
			return t.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
		}
	}
}

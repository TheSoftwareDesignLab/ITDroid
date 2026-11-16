package uniandes.tsdl.itdroid.helper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites hardcoded const-string literals in the smali so a per-language APK shows translated text.
 * It patches places, not texts, since one literal can be a label in one method and a key in another.
 * {@link #restoreOriginal()} puts everything back.
 */
public class SmaliPatcher {

	/** One literal rewritten, where and with what. */
	public static final class Patch {
		public final SmaliScan.Occurrence place;
		public final String translation;

		Patch(SmaliScan.Occurrence place, String translation) {
			this.place = place;
			this.translation = translation;
		}
	}

	/** Captures the line up to the opening quote, then the raw literal. */
	private static final Pattern CONST_STRING =
			Pattern.compile("^(\\s*const-string(?:/jumbo)?\\s+[vp]\\d+,\\s*\")(.*)\"$");

	private final Path decoded;
	/** Original content of the files patched so far. */
	private final Map<Path, List<String>> originals = new LinkedHashMap<>();

	/** @param decodedFolderPath the decoded app the places of a {@link SmaliScan} refer to */
	public SmaliPatcher(String decodedFolderPath) {
		this.decoded = Paths.get(decodedFolderPath);
	}

	/**
	 * Restores the code, then replaces the literal at each place with its translation and returns the
	 * patches applied. Places without a different translation or whose line changed are left alone.
	 */
	public List<Patch> patch(List<SmaliScan.Occurrence> places, Map<String, String> translations) throws IOException {
		return patch(places, translations, place -> place.line);
	}

	/** The same, with {@code currentLine} giving the line each place is on now after extraction. */
	public List<Patch> patch(List<SmaliScan.Occurrence> places, Map<String, String> translations,
			ToIntFunction<SmaliScan.Occurrence> currentLine) throws IOException {
		restoreOriginal();
		Map<String, List<SmaliScan.Occurrence>> byFile = new LinkedHashMap<>();
		for (SmaliScan.Occurrence place : places) {
			String translated = translations.get(place.text);
			if (translated != null && !translated.equals(place.text)) {
				byFile.computeIfAbsent(place.file, k -> new ArrayList<>()).add(place);
			}
		}
		List<Patch> applied = new ArrayList<>();
		for (Map.Entry<String, List<SmaliScan.Occurrence>> f : byFile.entrySet()) {
			Path file = decoded.resolve(f.getKey());
			List<String> original = Files.readAllLines(file, StandardCharsets.UTF_8);
			List<String> patched = new ArrayList<>(original);
			boolean changed = false;
			for (SmaliScan.Occurrence place : f.getValue()) {
				int index = currentLine.applyAsInt(place) - 1;
				Matcher m = index >= 0 && index < patched.size() ? CONST_STRING.matcher(patched.get(index)) : null;
				if (m == null || !m.matches() || !SmaliScan.unescape(m.group(2)).equals(place.text)) {
					System.out.println("  WARNING: " + place.origin() + " no longer holds the literal found by the scan; not patched");
					continue;
				}
				String translated = translations.get(place.text);
				patched.set(index, m.group(1) + SmaliScan.escape(translated) + "\"");
				applied.add(new Patch(place, translated));
				changed = true;
			}
			if (changed) {
				originals.put(file, original);
				Files.write(file, patched, StandardCharsets.UTF_8);
			}
		}
		return applied;
	}

	/** Puts back every file that was patched. */
	public void restoreOriginal() throws IOException {
		for (Map.Entry<Path, List<String>> entry : originals.entrySet()) {
			Files.write(entry.getKey(), entry.getValue(), StandardCharsets.UTF_8);
		}
		originals.clear();
	}

	/** The files currently patched, empty after {@link #restoreOriginal()}. */
	public List<Path> patchedFiles() {
		return new ArrayList<>(originals.keySet());
	}
}

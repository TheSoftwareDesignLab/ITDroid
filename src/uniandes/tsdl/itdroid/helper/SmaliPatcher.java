package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites hardcoded {@code const-string} literals in the app's smali so a per-language APK can
 * show translated hardcoded text. Caches the original files once and re-patches from that cache,
 * so it can be applied for each language independently. {@link #restoreOriginal()} undoes it.
 */
public class SmaliPatcher {

	/** Captures (indent + const-string + register + opening quote) and the raw literal. */
	private static final Pattern CONST_STRING =
			Pattern.compile("^(\\s*const-string(?:/jumbo)?\\s+[vp]\\d+,\\s*\")(.*)\"$");

	/** Original content of every file that may be patched, in the order of the scan. */
	private final Map<Path, List<String>> originals = new LinkedHashMap<>();

	/** Locates the code of the app (see {@link AppCode}) and caches every smali file that contains a const-string. */
	public SmaliPatcher(String decodedFolderPath, String packageName) throws IOException {
		this(AppCode.smaliFiles(decodedFolderPath, packageName));
	}

	/** The same over the files of a scan already made, so detector and patcher see the same code. */
	public SmaliPatcher(SmaliScan scan) throws IOException {
		this(scan.files());
	}

	private SmaliPatcher(List<File> smaliFiles) throws IOException {
		for (File file : smaliFiles) {
			List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
			for (String l : lines) {
				if (l.contains("const-string")) {
					originals.put(file.toPath(), lines);
					break;
				}
			}
		}
	}

	/** Replaces const-string literals found in {@code translations} (keyed by unescaped value). Returns the count. */
	public int patch(Map<String, String> translations) throws IOException {
		int count = 0;
		for (Map.Entry<Path, List<String>> entry : originals.entrySet()) {
			List<String> original = entry.getValue();
			List<String> patched = new ArrayList<>(original.size());
			boolean changed = false;
			for (String line : original) {
				Matcher m = CONST_STRING.matcher(line);
				if (m.matches()) {
					String literal = SmaliText.unescape(m.group(2));
					String translated = translations.get(literal);
					if (translated != null && !translated.equals(literal)) {
						patched.add(m.group(1) + SmaliText.escape(translated) + "\"");
						changed = true;
						count++;
						continue;
					}
				}
				patched.add(line);
			}
			if (changed) {
				Files.write(entry.getKey(), patched, StandardCharsets.UTF_8);
			}
		}
		return count;
	}

	/** Restores every cached file to its original content. */
	public void restoreOriginal() throws IOException {
		for (Map.Entry<Path, List<String>> entry : originals.entrySet()) {
			Files.write(entry.getKey(), entry.getValue(), StandardCharsets.UTF_8);
		}
	}
}

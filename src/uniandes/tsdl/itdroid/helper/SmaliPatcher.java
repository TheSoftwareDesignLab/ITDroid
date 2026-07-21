package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;

/**
 * Rewrites hardcoded {@code const-string} literals in the app's smali so a per-language APK can
 * show translated hardcoded text. Caches the original files once and re-patches from that cache,
 * so it can be applied for each language independently. {@link #restoreOriginal()} undoes it.
 */
public class SmaliPatcher {

	/** Captures (indent + const-string + register + opening quote) and the raw literal. */
	private static final Pattern CONST_STRING =
			Pattern.compile("^(\\s*const-string(?:/jumbo)?\\s+[vp]\\d+,\\s*\")(.*)\"$");

	private final Map<Path, List<String>> originals = new HashMap<>();

	/** Caches every app smali file that contains a const-string (the ones we might patch). */
	public SmaliPatcher(String decodedFolderPath, String packageName) throws IOException {
		String pkgPath = packageName.replace(".", File.separator);
		File[] smaliDirs = new File(decodedFolderPath).listFiles((dir, name) -> name.equals("smali") || name.startsWith("smali_classes"));
		if (smaliDirs == null) {
			return;
		}
		for (File smaliDir : smaliDirs) {
			File pkgDir = new File(smaliDir, pkgPath);
			if (!pkgDir.isDirectory()) {
				continue;
			}
			for (File file : FileUtils.listFiles(pkgDir, new String[]{"smali"}, true)) {
				List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
				for (String l : lines) {
					if (l.contains("const-string")) {
						originals.put(file.toPath(), lines);
						break;
					}
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
					String literal = unescape(m.group(2));
					String translated = translations.get(literal);
					if (translated != null && !translated.equals(literal)) {
						patched.add(m.group(1) + escape(translated) + "\"");
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

	/** Decodes smali escapes the same way the detector does, so keys match (incl. \\uXXXX; \\n/\\t/\\r -> newline/tab/CR). */
	private static String unescape(String s) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\' && i + 1 < s.length()) {
				char n = s.charAt(i + 1);
				if (n == 'u' && i + 5 < s.length()) {
					try {
						sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
						i += 5;
						continue;
					} catch (NumberFormatException ignored) {
						// emit verbatim
					}
				} else if (n == 'n' || n == 't' || n == 'r') {
					// Faithful round-trip: decode to the real control char so escape() can re-encode it.
					sb.append(n == 'n' ? '\n' : n == 't' ? '\t' : '\r');
					i++;
					continue;
				} else if (n == '"' || n == '\'' || n == '\\') {
					sb.append(n);
					i++;
					continue;
				}
			}
			sb.append(c);
		}
		return sb.toString();
	}

	/** Encodes a string as a smali literal body (non-ASCII -> \\uXXXX), without the surrounding quotes. */
	private static String escape(String s) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"':  sb.append("\\\""); break;
				case '\\': sb.append("\\\\"); break;
				case '\n': sb.append("\\n"); break;
				case '\r': sb.append("\\r"); break;
				case '\t': sb.append("\\t"); break;
				default:
					if (c < 0x20 || c > 0x7e) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
			}
		}
		return sb.toString();
	}
}

package uniandes.tsdl.itdroid.helper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;

/**
 * Detects hardcoded UI strings in an app's smali. Scans every dex dir (apps are multidex:
 * smali, smali_classes2, ...) and keeps {@code const-string} literals that look like
 * human-readable text, so it works for both classic Views and Jetpack Compose.
 */
public class HardcodedStringFinder {

	/** Matches a smali const-string instruction and captures its literal. */
	private static final Pattern CONST_STRING = Pattern.compile("^const-string(?:/jumbo)?\\s+[vp]\\d+,\\s*\"(.*)\"$");

	/** Scans the app package across all dex dirs and writes findings to {@code hcs.txt}. */
	public static int findHardCodedStrings(String decodedFolderPath, String packageName, String outputPath) throws IOException {
		String pkgPath = packageName.replace(".", File.separator);
		int possibleIPFS = 0;
		// try-with-resources: guarantees hcs.txt is closed even if the scan throws mid-way (no leaked/locked handle on Windows).
		try (BufferedWriter bw = new BufferedWriter(new FileWriter(outputPath + File.separator + "hcs.txt"))) {
			File[] smaliDirs = new File(decodedFolderPath).listFiles((dir, name) -> name.equals("smali") || name.startsWith("smali_classes"));
			if (smaliDirs != null) {
				for (File smaliDir : smaliDirs) {
					File pkgDir = new File(smaliDir, pkgPath);
					if (!pkgDir.isDirectory()) {
						continue;
					}
					Collection<File> files = FileUtils.listFiles(pkgDir, new String[]{"smali"}, true);
					for (File file : files) {
						String fileName = file.getName();
						if (fileName.contains("EmmaInstrumentation") || fileName.contains("FinishListener")
								|| fileName.contains("InstrumentedActivity") || fileName.contains("SMSInstrumentedReceiver")) {
							continue;
						}
						List<String> hardcoded = extractUiStrings(file);
						if (!hardcoded.isEmpty()) {
							bw.write(fileName.replace(".smali", ""));
							bw.newLine();
							for (String hardcodedString : hardcoded) {
								bw.write("\t" + hardcodedString);
								bw.newLine();
								possibleIPFS++;
							}
						}
					}
				}
			}
		}
		System.out.println("There are " + possibleIPFS + " hardcoded strings in your app. These strings are shown in the hcs.txt file stored in the output folder.");
		return possibleIPFS;
	}

	/** Unique hardcoded UI strings in the app package (across all dex dirs), for translation. */
	public static Set<String> collectUiStrings(String decodedFolderPath, String packageName) {
		Set<String> all = new LinkedHashSet<>();
		String pkgPath = packageName.replace(".", File.separator);
		File[] smaliDirs = new File(decodedFolderPath).listFiles((dir, name) -> name.equals("smali") || name.startsWith("smali_classes"));
		if (smaliDirs != null) {
			for (File smaliDir : smaliDirs) {
				File pkgDir = new File(smaliDir, pkgPath);
				if (!pkgDir.isDirectory()) {
					continue;
				}
				for (File file : FileUtils.listFiles(pkgDir, new String[]{"smali"}, true)) {
					String n = file.getName();
					if (n.contains("EmmaInstrumentation") || n.contains("FinishListener")
							|| n.contains("InstrumentedActivity") || n.contains("SMSInstrumentedReceiver")) {
						continue;
					}
					all.addAll(extractUiStrings(file));
				}
			}
		}
		return all;
	}

	/** Const-string literals in a file that look like human-readable UI text. */
	private static List<String> extractUiStrings(File smaliFile) {
		List<String> result = new ArrayList<>();
		try {
			for (String line : Files.readAllLines(smaliFile.toPath(), StandardCharsets.UTF_8)) {
				Matcher m = CONST_STRING.matcher(line.trim());
				if (m.matches()) {
					String value = unescapeSmali(m.group(1));
					if (isLikelyUiString(value) && !result.contains(value)) {
						result.add(value);
					}
				}
			}
		} catch (IOException e) {
			// Unreadable file: skip it.
		}
		return result;
	}

	/** Keeps real UI text and drops framework/compiler noise (Compose keys, toString fragments, descriptors). */
	private static boolean isLikelyUiString(String s) {
		String t = s.trim();
		if (t.length() < 2) {
			return false;                                  // single chars
		}
		if (!t.matches(".*\\p{L}.*")) {
			return false;                                  // no letter: numbers, dates, symbols
		}
		if (t.contains(".kt#") || t.contains(".java#") || t.contains(".kt:") || t.contains(".java:")
				|| t.matches(".*@\\d+L\\d+.*") || t.contains("<anonymous>")) {
			return false;                                  // Compose source/group keys and trace strings
		}
		if (t.startsWith("$")) {
			return false;                                  // Kotlin synthetic names ($this$Button)
		}
		if (t.startsWith(", ") || t.endsWith("=")) {
			return false;                                  // generated toString() fragments
		}
		if (t.contains("://")) {
			return false;                                  // URLs
		}
		if (t.startsWith("L") && t.endsWith(";")) {
			return false;                                  // type descriptors
		}
		if (t.contains("/") && !t.contains(" ")) {
			return false;                                  // class/file paths
		}
		if (!t.contains(" ") && t.matches("[a-z][a-zA-Z0-9]*")) {
			return false;                                  // lowercase camelCase identifier (field/param name)
		}
		if (t.contains("(...)")) {
			return false;                                  // method references, e.g. now(...), minusDays(...)
		}
		if (t.matches("[a-z0-9]+(_[a-z0-9]+)+")) {
			return false;                                  // snake_case identifiers / routes, e.g. add_expense
		}
		if (t.contains("yyyy") || t.contains("HH:mm") || t.matches(".*\\bMMM?\\b.*dd.*")) {
			return false;                                  // date/time format patterns, e.g. MMM dd, yyyy
		}
		return true;
	}

	/** Decodes the smali escapes that matter for a readable report (including \\uXXXX). */
	private static String unescapeSmali(String s) {
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
						// not a valid escape; emit verbatim
					}
				} else if (n == 'n' || n == 't' || n == 'r') {
					// Decode to the real control char (not a space) so keys round-trip with SmaliPatcher.escape().
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
}

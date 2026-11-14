package uniandes.tsdl.itdroid.helper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The hardcoded strings of an app: the literals of its code that read like text for a person, and
 * the text written directly in its XML. Works from one {@link SmaliScan} of the app (every dex
 * folder, Views and Jetpack Compose alike) and writes {@code hcs.txt}, grouped by class and method
 * as the original tool did.
 */
public class HardcodedStringFinder {

	/** A distinct literal of the code with every place it appears in. */
	public static class Hit {
		public final String text;
		public final List<SmaliScan.Occurrence> places = new ArrayList<>();

		Hit(String text) {
			this.text = text;
		}

		/** File and line of the first place: smali_classes2/com/x/Foo.smali:123. */
		public String origin() {
			return places.get(0).origin();
		}

		public int occurrences() {
			return places.size();
		}

		/**
		 * True when at least one place hands the text to a call known to show it. A composable
		 * function the rules do not know does not count: it may take the name of an icon or a key.
		 */
		public boolean shown() {
			for (SmaliScan.Occurrence o : places) {
				if (o.consumer == SmaliScan.Consumer.UI) {
					return true;
				}
			}
			return false;
		}

		/** True when every place is a log, an exception, data or compiler plumbing. */
		public boolean neverShown() {
			for (SmaliScan.Occurrence o : places) {
				if (!o.consumer.neverShown()) {
					return false;
				}
			}
			return true;
		}

		/** What the literal is for: the interface when any place shows it, a composable when one takes it, else its most frequent use. */
		public SmaliScan.Consumer consumer() {
			Map<SmaliScan.Consumer, Integer> count = new EnumMap<>(SmaliScan.Consumer.class);
			for (SmaliScan.Occurrence o : places) {
				count.merge(o.consumer, 1, Integer::sum);
			}
			if (count.containsKey(SmaliScan.Consumer.UI)) {
				return SmaliScan.Consumer.UI;
			}
			if (count.containsKey(SmaliScan.Consumer.COMPOSABLE)) {
				return SmaliScan.Consumer.COMPOSABLE;
			}
			SmaliScan.Consumer best = places.get(0).consumer;
			for (Map.Entry<SmaliScan.Consumer, Integer> e : count.entrySet()) {   // enum order: stable on ties
				if (e.getValue() > count.get(best)) {
					best = e.getKey();
				}
			}
			return best;
		}
	}

	/**
	 * The distinct literals of the scan worth reporting, in the order they are found. Compiler and
	 * framework noise is dropped; a bare identifier ({@code cancel}, {@code add_expense}) is kept
	 * only when the code shows it to the user.
	 */
	public static List<Hit> hits(SmaliScan scan) {
		LinkedHashMap<String, Hit> byText = new LinkedHashMap<>();
		for (SmaliScan.Occurrence o : scan.occurrences()) {
			if (isNoise(o.text)) {
				continue;
			}
			byText.computeIfAbsent(o.text, Hit::new).places.add(o);
		}
		List<Hit> out = new ArrayList<>();
		for (Hit h : byText.values()) {
			if (h.shown() || !isIdentifier(h.text)) {
				out.add(h);
			}
		}
		return out;
	}

	/** The literals of the XML grouped by text, in the order they are found. */
	public static LinkedHashMap<String, List<XmlLiteralFinder.Literal>> byText(List<XmlLiteralFinder.Literal> literals) {
		LinkedHashMap<String, List<XmlLiteralFinder.Literal>> out = new LinkedHashMap<>();
		for (XmlLiteralFinder.Literal l : literals) {
			out.computeIfAbsent(l.text, k -> new ArrayList<>()).add(l);
		}
		return out;
	}

	/**
	 * Scans the app, writes {@code hcs.txt} in {@code outputPath} and returns the number of distinct
	 * hardcoded strings (code and XML), the same figure the inventory and the report give.
	 */
	public static int findHardCodedStrings(String decodedFolderPath, String packageName, String outputPath) throws IOException {
		List<Hit> hits = hits(SmaliScan.scan(decodedFolderPath, packageName));
		List<XmlLiteralFinder.Literal> xml = XmlLiteralFinder.find(decodedFolderPath);
		return writeHcs(Paths.get(outputPath, "hcs.txt"), hits, xml);
	}

	/**
	 * Writes the findings as the original tool did: a line with the class, a tab and the method, two
	 * tabs and each literal of that method. The use found for the literal follows it in brackets.
	 * The XML comes after the code: file, element, literal with its attribute. Returns the number of
	 * distinct hardcoded strings.
	 */
	public static int writeHcs(Path file, List<Hit> hits, List<XmlLiteralFinder.Literal> xml) throws IOException {
		// class -> method -> literals of that method, each once, all in the order of the scan
		LinkedHashMap<String, LinkedHashMap<String, LinkedHashSet<String>>> byClass = new LinkedHashMap<>();
		List<SmaliScan.Occurrence> ordered = new ArrayList<>();
		for (Hit h : hits) {
			ordered.addAll(h.places);
		}
		ordered.sort(Comparator.comparingInt(o -> o.order));
		for (SmaliScan.Occurrence o : ordered) {
			byClass.computeIfAbsent(o.className, k -> new LinkedHashMap<>())
					.computeIfAbsent(o.method, k -> new LinkedHashSet<>())
					.add(visible(o.text) + "\t[" + o.consumer.label + "]");
		}
		try (BufferedWriter bw = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			for (Map.Entry<String, LinkedHashMap<String, LinkedHashSet<String>>> c : byClass.entrySet()) {
				bw.write(c.getKey());
				bw.newLine();
				for (Map.Entry<String, LinkedHashSet<String>> m : c.getValue().entrySet()) {
					bw.write("\t" + m.getKey());
					bw.newLine();
					for (String literal : m.getValue()) {
						bw.write("\t\t" + literal);
						bw.newLine();
					}
				}
			}
			String currentFile = null;
			String currentElement = null;
			for (XmlLiteralFinder.Literal l : xml) {
				if (!l.file.equals(currentFile)) {
					bw.write(l.file);
					bw.newLine();
					currentFile = l.file;
					currentElement = null;
				}
				String element = l.element + (l.id.isEmpty() ? "" : " " + l.id) + (l.line > 0 ? " (line " + l.line + ")" : "");
				if (!element.equals(currentElement)) {
					bw.write("\t" + element);
					bw.newLine();
					currentElement = element;
				}
				bw.write("\t\t" + visible(l.text) + "\t[" + l.attribute + "]");
				bw.newLine();
			}
		}
		int distinct = hits.size() + byText(xml).size();
		System.out.println("There are " + distinct + " hardcoded strings in your app (" + hits.size() + " in the code, "
				+ byText(xml).size() + " in the XML). They are listed by class and method in the hcs.txt file stored in the output folder.");
		return distinct;
	}

	/** A literal on one line: line breaks and tabs written as the escapes a programmer would type. */
	private static String visible(String text) {
		return text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
	}

	/** Framework and compiler noise (Compose keys, toString fragments, descriptors, URLs, paths, formats). */
	static boolean isNoise(String s) {
		String t = s.trim();
		if (t.length() < 2) {
			return true;                                   // single chars
		}
		if (!t.matches("(?s).*\\p{L}.*")) {
			return true;                                   // no letter: numbers, dates, symbols
		}
		if (t.contains(".kt#") || t.contains(".java#") || t.contains(".kt:") || t.contains(".java:")
				|| t.matches("(?s).*@\\d+L\\d+.*") || t.contains("<anonymous>")) {
			return true;                                   // Compose source/group keys and trace strings
		}
		if (t.startsWith("$")) {
			return true;                                   // Kotlin synthetic names ($this$Button)
		}
		if (t.startsWith(", ") || t.endsWith("=")) {
			return true;                                   // generated toString() fragments
		}
		if (t.contains("://")) {
			return true;                                   // URLs
		}
		if (t.startsWith("L") && t.endsWith(";")) {
			return true;                                   // type descriptors
		}
		if (t.contains("/") && !t.contains(" ")) {
			return true;                                   // class/file paths
		}
		if (t.contains("(...)")) {
			return true;                                   // method references, e.g. now(...), minusDays(...)
		}
		if (t.contains("yyyy") || t.contains("HH:mm") || t.matches("(?s).*\\bMMM?\\b.*dd.*")) {
			return true;                                   // date/time format patterns, e.g. MMM dd, yyyy
		}
		return false;
	}

	/** A bare identifier: text only when the code is seen showing it. */
	static boolean isIdentifier(String s) {
		String t = s.trim();
		if (!t.contains(" ") && t.matches("[a-z][a-zA-Z0-9]*")) {
			return true;                                   // lowercase camelCase identifier (field/param name)
		}
		return t.matches("[a-z0-9]+(_[a-z0-9]+)+");        // snake_case identifiers / routes, e.g. add_expense
	}
}

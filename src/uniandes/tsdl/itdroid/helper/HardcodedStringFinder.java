package uniandes.tsdl.itdroid.helper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Finds the hardcoded strings of an app, code literals that read like text and text written in its XML.
 * Works from one {@link SmaliScan} and writes {@code hcs.txt} grouped by class and method.
 */
public class HardcodedStringFinder {

	/** A distinct literal of the code with every place it appears in. */
	public static class Hit {
		public final String text;
		public final List<SmaliScan.Occurrence> places = new ArrayList<>();

		Hit(String text) {
			this.text = text;
		}

		/** File and line of the first place. */
		public String origin() {
			return places.get(0).origin();
		}

		public int occurrences() {
			return places.size();
		}

		/** True when some place hands the text to a call known to show it. An unknown composable does not count. */
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

		/** What the literal is for, UI or composable when any place says so, else its most frequent use. */
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
			for (Map.Entry<SmaliScan.Consumer, Integer> e : count.entrySet()) {   // enum order keeps ties stable
				if (e.getValue() > count.get(best)) {
					best = e.getKey();
				}
			}
			return best;
		}
	}

	/** The distinct literals worth reporting, without noise and with bare identifiers only when shown. */
	public static List<Hit> hits(SmaliScan scan) {
		LinkedHashMap<String, Hit> byText = new LinkedHashMap<>();
		for (SmaliScan.Occurrence o : scan.occurrences()) {
			if (Translatability.isNoise(o.text)) {
				continue;
			}
			byText.computeIfAbsent(o.text, Hit::new).places.add(o);
		}
		List<Hit> out = new ArrayList<>();
		for (Hit h : byText.values()) {
			if (h.shown() || !Translatability.isBareIdentifier(h.text)) {
				out.add(h);
			}
		}
		return out;
	}

	/** The literals of the XML grouped by text, in the order they are found. */
	public static LinkedHashMap<String, List<XmlLiterals.Literal>> byText(List<XmlLiterals.Literal> literals) {
		LinkedHashMap<String, List<XmlLiterals.Literal>> out = new LinkedHashMap<>();
		for (XmlLiterals.Literal l : literals) {
			out.computeIfAbsent(l.text, k -> new ArrayList<>()).add(l);
		}
		return out;
	}

	/**
	 * Writes the code literals by class and method as the original tool did, then the XML ones by file
	 * and element. Returns the number of distinct hardcoded strings.
	 */
	public static int writeHcs(Path file, List<Hit> hits, List<XmlLiterals.Literal> xml) throws IOException {
		// class, then method, then its literals once each, in scan order
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
			for (XmlLiterals.Literal l : xml) {
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

	/** A literal on one line, with line breaks and tabs escaped. */
	private static String visible(String text) {
		return text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
	}
}

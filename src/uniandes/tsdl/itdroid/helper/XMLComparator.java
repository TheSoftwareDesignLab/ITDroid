package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/**
 * Compares the default strings.xml with each language's to tell which languages the app already ships.
 * A language is shipped when its file exists and lacks at most {@code alpha} default strings. A string
 * the language file only copies from the default text counts as missing.
 */
public class XMLComparator {

	/** What the app ships for one language. */
	public static class LanguageStatus {
		/** Path of the language's strings.xml, as given to the constructor. */
		public final String path;
		/** True when the app has a strings.xml for this language. */
		public final boolean hasFile;
		/** Default strings this language does not define or only copies, sorted by name. */
		public final List<String> missing;
		/** Strings the language defines with the default text itself, sorted by name. ITDroid translates them in place. */
		public final List<String> copiesOfSource;
		/** True when the app counts as translated to this language. */
		public final boolean shipped;

		LanguageStatus(String path, boolean hasFile, List<String> missing, List<String> copiesOfSource, boolean shipped) {
			this.path = path;
			this.hasFile = hasFile;
			this.missing = missing;
			this.copiesOfSource = copiesOfSource;
			this.shipped = shipped;
		}

		/** The status for report.json, with missing names listed only when the language has a file. */
		@SuppressWarnings("unchecked")
		public JSONObject toJson() {
			JSONObject o = new JSONObject();
			o.put("shippedByApp", shipped);
			o.put("stringsFile", hasFile);
			o.put("missing", (long) missing.size());
			if (hasFile) {
				JSONArray names = new JSONArray();
				names.addAll(missing);
				o.put("missingNames", names);
			}
			JSONArray copies = new JSONArray();
			copies.addAll(copiesOfSource);
			o.put("copiesOfSource", copies);
			return o;
		}
	}

	ArrayList<String> useful = new ArrayList<>();
	ArrayList<String> useless = new ArrayList<>();
	private final Map<String, LanguageStatus> statuses = new LinkedHashMap<>();
	private boolean hasDefault;
	private int defaultStrings;

	/**
	 * @param xmls strings.xml paths, the default one first, then one per language
	 * @param alpha how many default strings a language may lack and still count as translated
	 * @param dictionary resource names that are never translated
	 * @param appNameResources the strings that hold the app name, which no language is expected to define
	 */
	public XMLComparator(String[] xmls, Integer alpha, NotTranslatableStringsDictionary dictionary, Set<String> appNameResources) {
		if (xmls == null || xmls.length == 0) {
			return;
		}
		int tolerance = alpha != null ? alpha.intValue() : 0;   // a null alpha means no tolerance
		try {
			// Without a default strings.xml no language is shipped and nothing is missing.
			File defaultFile = new File(xmls[0]);
			hasDefault = defaultFile.isFile();
			Map<String, String> originalStrings = hasDefault ? translatable(defaultFile, dictionary, appNameResources) : new LinkedHashMap<>();
			defaultStrings = originalStrings.size();
			for (int i = 1; i < xmls.length; i++) {
				File file = new File(xmls[i]);
				boolean exists = file.isFile();
				Set<String> difference = new TreeSet<>(originalStrings.keySet());
				Set<String> copies = new TreeSet<>();
				if (exists) {
					Map<String, String> defined = defined(file);
					difference.removeAll(defined.keySet());
					for (Map.Entry<String, String> d : originalStrings.entrySet()) {
						String value = defined.get(d.getKey());
						if (d.getValue() != null && value != null && Translatability.isCopyOfSource(d.getKey(), d.getValue(), value)) {
							copies.add(d.getKey());
						}
					}
					difference.addAll(copies);
				}
				boolean shipped = exists && difference.size() <= tolerance;
				statuses.put(xmls[i], new LanguageStatus(xmls[i], exists, new ArrayList<>(difference), new ArrayList<>(copies), shipped));
				if (shipped) {
					useful.add(xmls[i]);
				} else {
					useless.add(xmls[i]);
				}
			}
		} catch (Exception e) {
			System.err.println("XMLComparator :: error comparing strings.xml files: " + e.getMessage());
			throw new RuntimeException(e);
		}
	}

	/**
	 * The default resources a language is expected to translate, by name in file order, as the inventory
	 * decides it. A string maps to its value, a plurals or string-array to null.
	 */
	private static Map<String, String> translatable(File stringsXml, NotTranslatableStringsDictionary dictionary,
			Set<String> appNameResources) throws Exception {
		Map<String, String> out = new LinkedHashMap<>();
		Element root = new SAXBuilder().build(stringsXml).getRootElement();
		for (Element e : root.getChildren()) {
			String name = e.getAttributeValue("name");
			if (name == null) {
				continue;
			}
			if ("string".equals(e.getName())) {
				String text = StringInventory.innerXml(e);
				if (Translatability.stringSkip(e, text, dictionary, appNameResources) == null) {
					out.put(name, text);
				}
			} else if (("plurals".equals(e.getName()) || "string-array".equals(e.getName()))
					&& Translatability.containerSkip(e, dictionary, appNameResources) == null) {
				out.put(name, null);
			}
		}
		return out;
	}

	/** Every resource a language file defines, a string with its value and anything else with null. */
	private static Map<String, String> defined(File stringsXml) throws Exception {
		Map<String, String> out = new LinkedHashMap<>();
		for (Element e : new SAXBuilder().build(stringsXml).getRootElement().getChildren()) {
			String name = e.getAttributeValue("name");
			if (name != null) {
				out.put(name, "string".equals(e.getName()) ? StringInventory.innerXml(e) : null);
			}
		}
		return out;
	}

	/** Paths of the languages the app already ships. */
	public ArrayList<String> getUsefull() {
		return useful;
	}

	/** Paths of the languages the app does not ship, which ITDroid translates. */
	public ArrayList<String> getUseLess() {
		return useless;
	}

	/** The status of the language whose strings.xml path is given, or null. */
	public LanguageStatus status(String path) {
		return statuses.get(path);
	}

	/** True when the app has a default strings.xml. */
	public boolean hasDefault() {
		return hasDefault;
	}

	/** Number of translatable default strings, the reference every language is compared with. */
	public int defaultStrings() {
		return defaultStrings;
	}
}

package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/**
 * Compares the default {@code strings.xml} with the one of each language to tell which languages
 * the app already ships. A language counts as shipped when its file exists and lacks at most
 * {@code alpha} of the default strings; for the others ITDroid translates the resources itself.
 * The strings a language lacks are its non-internationalized strings, kept per language for the
 * report.
 */
public class XMLComparator {

	/** What the app ships for one language. */
	public static class LanguageStatus {
		/** Path of the language's strings.xml, as given to the constructor. */
		public final String path;
		/** True when the app has a strings.xml for this language. */
		public final boolean hasFile;
		/** Default strings this language does not define, sorted by name (all of them when there is no file). */
		public final List<String> missing;
		/** True when the app is considered translated to this language (file present, missing within alpha). */
		public final boolean shipped;

		LanguageStatus(String path, boolean hasFile, List<String> missing, boolean shipped) {
			this.path = path;
			this.hasFile = hasFile;
			this.missing = missing;
			this.shipped = shipped;
		}

		/**
		 * The status as it goes into report.json. The names of the missing strings are listed only
		 * when the language has a file: without one, every default string is missing.
		 */
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
			return o;
		}
	}

	ArrayList<String> useful = new ArrayList<>();
	ArrayList<String> useless = new ArrayList<>();
	private final Map<String, LanguageStatus> statuses = new LinkedHashMap<>();
	private boolean hasDefault;
	private int defaultStrings;

	/**
	 * @param xmls absolute paths where the strings.xml files are supposed to be: the default one
	 *        first, then one per language
	 * @param alpha how many default strings a language may lack and still count as translated
	 * @param directory settings folder, where the dictionary of not-translatable strings is
	 */
	public XMLComparator(String[] xmls, Integer alpha, String directory) {
		if (xmls == null || xmls.length == 0) {
			return;
		}
		int tolerance = alpha != null ? alpha.intValue() : 0;   // a null alpha means no tolerance
		try {
			NotTranslatableStringsDictionary dictionary = new NotTranslatableStringsDictionary(directory);
			// An app can have no default strings.xml (all its text hardcoded, or built with Compose
			// and no resources): then no language is shipped and nothing is missing.
			File defaultFile = new File(xmls[0]);
			hasDefault = defaultFile.isFile();
			Set<String> originalStrings = hasDefault ? names(defaultFile, dictionary, true) : new LinkedHashSet<>();
			defaultStrings = originalStrings.size();
			for (int i = 1; i < xmls.length; i++) {
				File file = new File(xmls[i]);
				boolean exists = file.isFile();
				Set<String> difference = new TreeSet<>(originalStrings);
				if (exists) {
					difference.removeAll(names(file, dictionary, false));
				}
				boolean shipped = exists && difference.size() <= tolerance;
				statuses.put(xmls[i], new LanguageStatus(xmls[i], exists, new ArrayList<>(difference), shipped));
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
	 * Names of the translatable resources of a strings.xml, in file order. With {@code textOnly}
	 * (the default file) a {@code <string>} whose value is not text for a person is left out: a
	 * number, a reference, an identifier such as {@code androidx.startup} that a library adds to
	 * every app. No language translates those, so they are not strings a language lacks.
	 */
	private static Set<String> names(File stringsXml, NotTranslatableStringsDictionary dictionary, boolean textOnly) throws Exception {
		Set<String> out = new LinkedHashSet<>();
		Element root = new SAXBuilder().build(stringsXml).getRootElement();
		for (Element e : root.getChildren()) {
			String name = e.getAttributeValue("name");
			if (!dictionary.translatable(name)) {
				continue;
			}
			if (textOnly && "string".equals(e.getName())
					&& ("false".equalsIgnoreCase(e.getAttributeValue("translatable"))
							|| StringInventory.textSkipReason(StringInventory.innerXml(e)) != null)) {
				continue;
			}
			out.add(name);
		}
		return out;
	}

	/** Paths of the languages the app already ships. */
	public ArrayList<String> getUsefull() {
		return useful;
	}

	/** Paths of the languages the app does not ship (ITDroid translates them). */
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

	/** Translatable strings of the default strings.xml: the reference every language is compared with. */
	public int defaultStrings() {
		return defaultStrings;
	}
}

package uniandes.tsdl.itdroid;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.simple.JSONObject;

import uniandes.tsdl.itdroid.helper.APKToolWrapper;
import uniandes.tsdl.itdroid.helper.HardcodedStringFinder;
import uniandes.tsdl.itdroid.helper.SmaliPatcher;
import uniandes.tsdl.itdroid.helper.LLMServerManager;
import uniandes.tsdl.itdroid.helper.Helper;
import uniandes.tsdl.itdroid.helper.RunConfig;
import uniandes.tsdl.itdroid.helper.ITDroidException;
import uniandes.tsdl.itdroid.helper.LanguageBundle;
import uniandes.tsdl.itdroid.helper.ExplorationHelper;
import uniandes.tsdl.itdroid.helper.ExplorationException;
import uniandes.tsdl.itdroid.explorer.AdbExplorer;
import uniandes.tsdl.itdroid.helper.XMLComparator;
import uniandes.tsdl.itdroid.model.LayoutGraph;
import uniandes.tsdl.itdroid.model.LayoutGraphComparision;
import uniandes.tsdl.itdroid.translator.LLMTranslator;
import uniandes.tsdl.itdroid.translator.Translator;

public class ITDroid {

	static HashMap<String, String> pathsMap = new HashMap<>();

	static HashMap<String, LayoutGraph> graphs = new HashMap<String, LayoutGraph>();
	static HashMap<String, LayoutGraphComparision> lgcomparisions = new HashMap<String, LayoutGraphComparision>();
	static JSONObject report;
	static String outputPath;

	public static void main(String[] args) {
		try {
			// long initialTime = System.currentTimeMillis();
			// System.out.println(initialTime);
			runITDroid(args);
			// long finalTime = System.currentTimeMillis();
			// System.out.println(finalTime);
			// System.out.println(finalTime-initialTime);

		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			// print report (report stays null if the run aborts before it is built, e.g. LLM server down)
			if(outputPath!=null && report!=null) {
				try (FileWriter file = new FileWriter(outputPath + File.separator+"report.json")) {

					file.write(report.toJSONString());
					file.flush();
					System.out.println("Internationalization analysis is finished, please check the report.json file for the results");

				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	@SuppressWarnings("unchecked")
	public static void runITDroid(String[] args) throws ExplorationException, Exception {
		// Getting arguments — either a single config file (recommended) or the positional CLI form.
		String apkName;
		String apkPath;
		String appName;
		String extraPath;
		String langsDir;
		int alpha;
		String emulatorName;
		String llmLaunchCommand;

		if (args.length == 1 && RunConfig.looksLikeConfigFile(args[0])) {
			// Config-file mode: java -jar ITDroid.jar itdroid.config.properties
			RunConfig cfg = RunConfig.load(args[0]);
			apkPath = cfg.getApkPath();
			appName = cfg.getAppPackage();
			extraPath = cfg.getExtraFolder();
			langsDir = cfg.getSettingsDir();
			alpha = cfg.getAlpha();
			outputPath = cfg.getOutput();
			emulatorName = cfg.getEmulator();
			// Push the chosen translation engine/model/token into ITDROID_LLM_* (top precedence).
			cfg.applyTranslationSystemProperties();
			String autoStart = cfg.getAutoStart();
			llmLaunchCommand = autoStart.isEmpty() ? null : autoStart;
		} else if (args.length == 7 || args.length == 8) {
			// Positional mode (kept for backward compatibility).
			apkPath = args[0];
			appName = args[1];
			extraPath = args[2];
			langsDir = args[3];
			try {
				alpha = Integer.parseInt(args[4]);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException(
						"Argument 5 (alpha, the amount of untranslatable strings) must be an integer, got '"
								+ args[4] + "'.");
			}
			outputPath = args[5];
			emulatorName = args[6];
			// Optional 8th argument: an Ollama model name or a command that starts the local LLM server.
			// When present, ITDroid boots the server (and shuts it down at the end).
			llmLaunchCommand = args.length >= 8 ? args[7] : null;
		} else {
			printUsage();
			return;
		}

		// Derive the file name in a cross-platform, crash-proof way (File.getName handles
		// both '/' and '\\' and bare file names). Normalize separators with a literal
		// replace: String.replaceAll's replacement treats '\\' as a regex escape and would
		// throw on Windows.
		apkName = new File(apkPath).getName();
		String os = System.getProperty("os.name").toLowerCase();
		if (os.contains("win")) {
			extraPath = extraPath.replace("/", File.separator);
			apkPath = apkPath.replace("/", File.separator);
		}
		if (!extraPath.endsWith(File.separator)) {
			extraPath = extraPath + File.separator;
		}
		Helper.getInstance();
		Helper.setPackageName(appName);

		// Ensure the output directory exists before anything writes into it (hcs.txt, report.json,
		// ipfs.csv, the LLM server log, ...). A fresh run with a non-existent output folder used to
		// crash with FileNotFoundException on the first write. Done here so it runs whether or not the
		// LLM server is spawned below.
		Files.createDirectories(Paths.get(outputPath));

		// Boot the local LLM server (or verify it is up) before any translation. Fails fast with a
		// clear message instead of throwing a mid-run ConnectException. Started early so the model
		// loads while the APK is being decoded.
		LLMServerManager llmServer = new LLMServerManager(langsDir);
		llmServer.ensureRunning(llmLaunchCommand, outputPath);

		// Decode the APK
		String decodedFolderPath = APKToolWrapper.openAPK(apkPath, extraPath);
		report = new JSONObject();
		report.put("apkName", apkName);
		report.put("appName", appName);
		report.put("alpha", alpha);
		report.put("outputFolder", outputPath);
		report.put("emulatorName", emulatorName);

		int possibleIPFS = HardcodedStringFinder.findHardCodedStrings(decodedFolderPath, appName, outputPath);
		report.put("hardcoded", possibleIPFS);

		// Read selected operators
		LanguageBundle lngBundle = new LanguageBundle(langsDir);
		System.out.println(lngBundle.printSelectedLanguages());

		// Identify translated and notTranslated languages
		String[] lngs = lngBundle.getSelectedLanguagesAsArray();
		String[] stringFiles = buildStringPaths(lngs);

		File baseStrings = new File(stringFiles[0]);
		if (!baseStrings.exists()) {
			report.put("error", "Your application do not have a strings.xml file.");
			System.out.println("Your application do not have a strings.xml file.");
			throw new ITDroidException("Your application do not have a strings.xml file.");
		}
		XMLComparator xmlc = new XMLComparator(stringFiles, alpha, langsDir);

		// Notify user about translated and not-translated languages
		ArrayList<String> translatedFiles = xmlc.getUsefull();
		System.out.println("Your application is translated to the following languages:");
		for (int i = 0; i < translatedFiles.size(); i++) {
			System.out.println(lngBundle.getBundle().getObject(pathsMap.get(translatedFiles.get(i))));
		}
		ArrayList<String> notTrnsltdFiles = xmlc.getUseLess();
		System.out.println("Your application is not translated to the following languages:");
		for (int i = 0; i < notTrnsltdFiles.size(); i++) {
			System.out.println(lngBundle.getBundle().getObject(pathsMap.get(notTrnsltdFiles.get(i))));
		}

		// Source language: trust the configured defaultLng (reliable). LLM auto-detection is used
		// only as a fallback when defaultLng is missing — it proved unreliable (it could mislabel an
		// English app as another language, which then wrongly skipped that language as "the source").
		// getObject/getString throw MissingResourceException when the key is absent, so guard with
		// containsKey and let sourceLang stay null to trigger the detection fallback below.
		String sourceLang = lngBundle.getBundle().containsKey("defaultLng")
				? lngBundle.getBundle().getString("defaultLng") : null;
		if (sourceLang == null || sourceLang.isEmpty()) {
			String detected = new LLMTranslator(langsDir).detectSourceLanguage(stringFiles[0]);
			if (detected != null && !detected.isEmpty()) {
				sourceLang = detected;
			}
		}
		System.out.println("Source language: " + sourceLang);

		// Translate the original file into the missing languages (never into the source language).
		System.out.println("We are going to translate your strings...");
		for (int i = 0; i < notTrnsltdFiles.size(); i++) {
			String tLang = pathsMap.get(notTrnsltdFiles.get(i));
			if (tLang.equals(sourceLang)) {
				System.out.println("Skipping " + tLang + ": it is the source language.");
				continue;
			}
			Translator t = new Translator(stringFiles[0], sourceLang, tLang);
			t.translate(new LLMTranslator(langsDir));
		}

		// Hardcoded UI strings (translated per language into a dedicated APK, see buildLanguageApk).
		List<String> hardcoded = new ArrayList<>(HardcodedStringFinder.collectUiStrings(decodedFolderPath, appName));
		SmaliPatcher patcher = new SmaliPatcher(decodedFolderPath, appName);

		// Builds the default APK from the pristine (source-language) smali.
		String newApkPath = APKToolWrapper.buildAPK(extraPath, appName, outputPath);

		if (newApkPath == null || newApkPath.isEmpty()) {
			return;
		}

		// Back up the pristine default APK before any per-language build can overwrite its name.
		// This file is the untouched original and is also used to restore the default APK at the end.
		String originalApkBackup = Paths.get(outputPath, appName + "-original-aligned-debugSigned.apk").toAbsolutePath().toString();
		if (!hardcoded.isEmpty()) {
			Files.copy(Paths.get(newApkPath), Paths.get(originalApkBackup), StandardCopyOption.REPLACE_EXISTING);
		}

		JSONObject lngsResults = new JSONObject();

		// Explore the default version in the detected source language (consistent with translation).
		String deftLanguage = sourceLang;
		report.put("dfltLang", deftLanguage);

		// Explore app using default language
		String resultFolderPath = ExplorationHelper.exploreDefaultLanguage(deftLanguage, outputPath, true, newApkPath, appName);
		System.out.println("The app has been inspected");
		LayoutGraph defltGraph = new LayoutGraph(deftLanguage, resultFolderPath);
		JSONObject dfltLangJSON = new JSONObject();
		// Use the configured default language instead of a hardcoded "English".
		String deftLanguageName = lngBundle.isLanguageSelected(deftLanguage)
				? lngBundle.getBundle().getString(deftLanguage) : deftLanguage;
		dfltLangJSON.put("lang", deftLanguageName);
		dfltLangJSON.put("dflt", true);
		dfltLangJSON.put("amStates", defltGraph.getStates().size());
		dfltLangJSON.put("amTrans", defltGraph.getTransitions().size());
		lngsResults.put(deftLanguage, dfltLangJSON);
		graphs.put(deftLanguage, defltGraph);

		// Truncate any ipfs.csv from a previous run and write a fresh header. The per-language
		// rows are appended later (LayoutGraphComparision opens it in append mode).
		try (BufferedWriter bw = new BufferedWriter(new FileWriter(outputPath + File.separator + "ipfs.csv", false))) {
			bw.write("language;state;nodePos;ipfScore");
			bw.newLine();
		}

		System.out.println("Inspecting translated versions");
		// Generate the graph for all the translated languages
		for (int i = 0; i < translatedFiles.size(); i++) {

			String lang = pathsMap.get(translatedFiles.get(i));
			System.out.println("Processing " + lang + " app version");
			JSONObject dfltLangJSONTrans = new JSONObject();
			try {
				// Build a per-language APK with the hardcoded strings translated, then explore it.
				String apkForLang = buildLanguageApk(lang, sourceLang, hardcoded, patcher, langsDir, extraPath, appName, outputPath);
				String resultFolderPathh = ExplorationHelper.exploreLanguageVersion(lang, outputPath, true, apkForLang != null ? apkForLang : newApkPath, appName);
				System.out.println("The app has been inspected");

				// Builds the graph for given language
				LayoutGraph langGraph = new LayoutGraph(lang, resultFolderPathh);
				dfltLangJSONTrans.put("lang", lngBundle.getBundle().getString(lang));
				dfltLangJSONTrans.put("amStates", langGraph.getStates().size());
				dfltLangJSONTrans.put("amTrans", langGraph.getTransitions().size());
				graphs.put(lang, langGraph);

				// Compares the default graph with the current language graph
				LayoutGraphComparision lgc = new LayoutGraphComparision(deftLanguage, defltGraph,
						lngBundle.getBundle().getString(lang), lang, langGraph, resultFolderPathh, outputPath,
						dfltLangJSONTrans);
				lgcomparisions.put(lang, lgc);				

			} catch (ExplorationException e) {
				dfltLangJSONTrans.put("error", e.getMessage());
				System.out.println("This translated version of the app is not suitable for reproducing the steps recorded over default app version. It is possible that your automated tests might not work over this language version");
			}

			lngsResults.put(lang, dfltLangJSONTrans);

		}

		System.out.println("Inspecting non translated versions");
		// Generate the graph for all the not translated languages
		for (int i = 0; i < notTrnsltdFiles.size(); i++) {

			String lang = pathsMap.get(notTrnsltdFiles.get(i));
			System.out.println("Processing " + lang + " app version");
			JSONObject dfltLangJSONTrans = new JSONObject();
			try {
				// Build a per-language APK with the hardcoded strings translated, then explore it.
				String apkForLang = buildLanguageApk(lang, sourceLang, hardcoded, patcher, langsDir, extraPath, appName, outputPath);
				String resultFolderPathh = ExplorationHelper.exploreLanguageVersion(lang, outputPath, false, apkForLang != null ? apkForLang : newApkPath, appName);
				System.out.println("The app has been inspected");

				// Builds the graph for given language
				LayoutGraph langGraph = new LayoutGraph(lang, resultFolderPathh);
				dfltLangJSONTrans.put("lang", lngBundle.getBundle().getString(lang));
				dfltLangJSONTrans.put("amStates", langGraph.getStates().size());
				dfltLangJSONTrans.put("amTrans", langGraph.getTransitions().size());
				graphs.put(lang, langGraph);

				// Compares the default graph with the current language graph
				LayoutGraphComparision lgc = new LayoutGraphComparision(deftLanguage, defltGraph,
						lngBundle.getBundle().getString(lang), lang, langGraph, resultFolderPathh, outputPath,
						dfltLangJSONTrans);
				lgcomparisions.put(lang, lgc);
			} catch (ExplorationException e) {
				dfltLangJSONTrans.put("error", e.getMessage());
				System.out.println("This translated version of the app is not suitable for reproducing the steps recorded over default app version. It is possible that your automated tests might not work over this language version");
			}
			lngsResults.put(lang, dfltLangJSONTrans);
		}

		// The per-language builds overwrote the default-named APK while patching hardcoded strings.
		// Restore it from the untouched backup (a plain copy — cannot fail like a rebuild). The smali
		// is also restored so the decoded sources are left pristine.
		if (!hardcoded.isEmpty()) {
			patcher.restoreOriginal();
			if (new File(originalApkBackup).exists()) {
				Files.copy(Paths.get(originalApkBackup), Paths.get(newApkPath), StandardCopyOption.REPLACE_EXISTING);
			}
		}

		// Leave the device on the ORIGINAL app (the exact APK passed on the command line), never on a
		// translated build. Exploration installs per-language APKs, so reinstall the original last.
		try {
			String serial = AdbExplorer.resolveSerial();
			String originalApkAbs = new File(apkPath).getAbsolutePath();
			System.out.println("Reinstalling the original app so the device ends clean...");
			AdbExplorer.installApk(serial, appName, originalApkAbs);
		} catch (Exception e) {
			System.out.println("WARNING: could not reinstall the original app: " + e.getMessage());
		}

		report.put("langsReport", lngsResults);
	}

	/** Prints both accepted invocation forms: a single config file, or the positional arguments. */
	private static void printUsage() {
		System.out.println("******* ERROR: INCORRECT USAGE *******");
		System.out.println("Recommended: pass a single config file");
		System.out.println("  java -jar ITDroid.jar itdroid.config.properties");
		System.out.println("  (copy itdroid.config.example.properties and edit it)");
		System.out.println();
		System.out.println("Or the positional form (7 required arguments + 1 optional):");
		System.out.println("1. APK path");
		System.out.println("2. Package Name");
		System.out.println("3. Binaries path (the extra/ folder)");
		System.out.println("4. Directory containing settings.properties and strings.xml");
		System.out.println("5. Amount of untranslatable strings (alpha)");
		System.out.println("6. Path where test output will be stored");
		System.out.println("7. Name of the emulator/AVD (or a connected physical device)");
		System.out.println("8. (Optional) Ollama model name or a command to start the local LLM server, e.g.");
		System.out.println("   \"qwen2.5:3b\" or \"llama-server -m model.gguf --port 8080\".");
		System.out.println("   If omitted, the LLM server must already be running.");
	}

	/**
	 * Builds a per-language APK whose hardcoded UI strings are translated into {@code lang}, signs it
	 * and returns its path. Returns null (caller falls back to the default APK) when there is nothing
	 * to translate, when {@code lang} is the source language, or on any failure.
	 */
	private static String buildLanguageApk(String lang, String sourceLang, List<String> hardcoded, SmaliPatcher patcher,
			String langsDir, String extraPath, String appName, String outputPath) {
		if (hardcoded.isEmpty() || lang.equals(sourceLang)) {
			return null;
		}
		try {
			Map<String, String> translations = new LLMTranslator(langsDir).translateUiLabels(hardcoded, sourceLang, lang);
			writeHardcodedLog(outputPath, lang, translations);
			int patched = patcher.patch(translations);
			System.out.println("Patched " + patched + " hardcoded strings for " + lang);
			String built = APKToolWrapper.buildAPK(extraPath, appName, outputPath);
			patcher.restoreOriginal(); // keep the smali pristine for the next language
			if (built == null || built.isEmpty()) {
				return null;
			}
			Path dst = Paths.get(outputPath, appName + "-" + lang + "-aligned-debugSigned.apk").toAbsolutePath();
			Files.copy(Paths.get(built), dst, StandardCopyOption.REPLACE_EXISTING);
			return dst.toString();
		} catch (Exception e) {
			System.out.println("Could not build a translated APK for " + lang + " (" + e.getMessage() + "); using the default APK.");
			try {
				patcher.restoreOriginal();
			} catch (Exception ignored) {
				// best effort
			}
			return null;
		}
	}

	/** Writes a reviewable CSV of the hardcoded strings and how they were translated. */
	private static void writeHardcodedLog(String outputPath, String lang, Map<String, String> translations) throws IOException {
		try (BufferedWriter w = new BufferedWriter(new FileWriter(outputPath + File.separator + "hardcoded_" + lang + ".csv"))) {
			w.write("original;translated;changed");
			w.newLine();
			for (Map.Entry<String, String> e : translations.entrySet()) {
				w.write(csv(e.getKey()) + ";" + csv(e.getValue()) + ";" + (!e.getKey().equals(e.getValue())));
				w.newLine();
			}
		}
	}

	private static String csv(String s) {
		return "\"" + s.replace("\"", "\"\"") + "\"";
	}

	private static String[] buildStringPaths(String[] lngs) throws UnsupportedEncodingException {
		String decodedPath = Helper.getInstance().getCurrentDirectory();

		String[] paths = new String[lngs.length + 1];

		Path base = Paths.get(decodedPath, "temp", "res");
		paths[0] = base.resolve("values").resolve("strings.xml").toAbsolutePath().toString();
		for (int i = 1; i < paths.length; i++) {
			paths[i] = base.resolve("values-" + lngs[i - 1]).resolve("strings.xml").toAbsolutePath().toString();
			pathsMap.put(base.resolve("values-" + lngs[i - 1]).resolve("strings.xml").toAbsolutePath().toString(),
					lngs[i - 1]);
		}

		return paths;
	}

}

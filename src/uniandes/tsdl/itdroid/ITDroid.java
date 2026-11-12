package uniandes.tsdl.itdroid;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import uniandes.tsdl.itdroid.explorer.AdbExplorer;
import uniandes.tsdl.itdroid.helper.APKToolWrapper;
import uniandes.tsdl.itdroid.helper.ExplorationException;
import uniandes.tsdl.itdroid.helper.ExplorationHelper;
import uniandes.tsdl.itdroid.helper.HardcodedStringFinder;
import uniandes.tsdl.itdroid.helper.Helper;
import uniandes.tsdl.itdroid.helper.ITDroidException;
import uniandes.tsdl.itdroid.helper.LLMServerManager;
import uniandes.tsdl.itdroid.helper.LanguageBundle;
import uniandes.tsdl.itdroid.helper.NotTranslatableStringsDictionary;
import uniandes.tsdl.itdroid.helper.Preflight;
import uniandes.tsdl.itdroid.helper.Reanalysis;
import uniandes.tsdl.itdroid.helper.ReportServer;
import uniandes.tsdl.itdroid.helper.RunConfig;
import uniandes.tsdl.itdroid.helper.RunMetrics;
import uniandes.tsdl.itdroid.helper.SmaliPatcher;
import uniandes.tsdl.itdroid.helper.StringInventory;
import uniandes.tsdl.itdroid.helper.XMLComparator;
import uniandes.tsdl.itdroid.model.LayoutGraph;
import uniandes.tsdl.itdroid.model.LayoutGraphComparision;
import uniandes.tsdl.itdroid.translator.LLMTranslator;
import uniandes.tsdl.itdroid.translator.Translator;

/**
 * Orchestrator. The pipeline, in order:
 * <ol>
 *   <li>Preflight: device (API 33+, ABIs), APK native code, LLM server, decode, manifest (launcher,
 *       not a TWA, minSdk), strings.xml, and a launch test of the ORIGINAL app.</li>
 *   <li>Inventory: every string of the app (resources and smali literals) with its origin, written
 *       to {@code strings_inventory.json}.</li>
 *   <li>Source build + exploration (recorded): the pristine app, explored with the DFS; the path is
 *       saved as a script.</li>
 *   <li>Per target language, with the app OFF the device: translate (resources + hardcoded labels)
 *       from the inventory, patch, rebuild, sign; then install, REPLAY the recorded path, uninstall,
 *       and compare against the source graph (IPFs). {@code report.json} is rewritten after each
 *       language, so a run cut short still leaves the finished languages.</li>
 *   <li>Close: restore the default-named APK, reinstall the original app on the device.</li>
 * </ol>
 */
public class ITDroid {

	static HashMap<String, String> pathsMap = new HashMap<>();

	static HashMap<String, LayoutGraph> graphs = new HashMap<String, LayoutGraph>();
	static HashMap<String, LayoutGraphComparision> lgcomparisions = new HashMap<String, LayoutGraphComparision>();
	static JSONObject report;
	static String outputPath;
	static boolean finishedOk;

	/** Exploration tunables and preflight switches (config keys; defaults in positional mode). */
	static AdbExplorer.Options explorerOptions = new AdbExplorer.Options();
	static boolean launchTest = true;
	static boolean reuseInventory = true;
	/** Write graph.txt / graph.json next to each result.json (config key debug.graphDumps). */
	static boolean graphDumps = false;

	@SuppressWarnings("unchecked")
	public static void main(String[] args) {
		if (args.length >= 1 && args[0].equals("--report")) {
			// java -jar ITDroid.jar --report [resultsFolder] [--port N]: serves the web report, no evaluation.
			try {
				ReportServer.run(Arrays.copyOfRange(args, 1, args.length));
			} catch (Exception e) {
				System.out.println("ERROR: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
				System.exit(1);
			}
			return;
		}
		if (args.length >= 1 && args[0].equals("--reanalyze")) {
			// java -jar ITDroid.jar --reanalyze <folder> [--out <folder>]: recomputes the comparison of
			// evaluations already explored, from their stored result.json. No device, no LLM.
			try {
				Reanalysis.run(Arrays.copyOfRange(args, 1, args.length));
			} catch (Exception e) {
				System.out.println("ERROR: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
				System.exit(1);
			}
			return;
		}
		try {
			runITDroid(args);
			finishedOk = true;
		} catch (Exception e) {
			e.printStackTrace();
			if (report != null) {
				report.put("error", e.getMessage() != null ? e.getMessage() : e.toString());
			}
		} finally {
			// report stays null if the run aborts before it is built (e.g. bad arguments)
			if (outputPath != null && report != null) {
				finishReport();
				writeReport();
				if (finishedOk) {
					System.out.println("Internationalization analysis is finished, please check the report.json file for the results");
				} else {
					System.out.println("ITDroid stopped early; the partial results are in report.json");
				}
			}
		}
		if (!finishedOk) {
			System.exit(1);   // a non-zero exit code so batch tooling can tell a rejected/aborted run apart
		}
	}

	/** Writes report.json with everything known so far (called after each milestone, not only at the end). */
	static synchronized void writeReport() {
		if (outputPath == null || report == null) {
			return;
		}
		try (FileWriter file = new FileWriter(outputPath + File.separator + "report.json")) {
			file.write(report.toJSONString());
			file.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/** Closes the itdroid/performance sections: status, end time, phase times and machine usage. */
	@SuppressWarnings("unchecked")
	static synchronized void finishReport() {
		RunMetrics metrics = RunMetrics.get();
		if (metrics == null) {
			return;
		}
		JSONObject info = report.get("itdroid") instanceof JSONObject ? (JSONObject) report.get("itdroid") : new JSONObject();
		info.put("status", finishedOk ? "finished" : "aborted");
		info.put("finishedAt", Instant.now().toString());
		info.put("durationSec", Math.round(metrics.elapsedSec() * 10.0) / 10.0);
		report.put("itdroid", info);
		report.put("performance", metrics.performance(LLMTranslator.stats()));
	}

	@SuppressWarnings("unchecked")
	public static void runITDroid(String[] args) throws ExplorationException, Exception {
		// Getting arguments: either a single config file (recommended) or the positional CLI form.
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
			// Default: results/<package>, the folder the web report lists (java -jar ITDroid.jar --report).
			outputPath = cfg.getOutput().isEmpty() ? Paths.get(cfg.getResultsDir(), appName).toString() : cfg.getOutput();
			emulatorName = cfg.getEmulator();
			// Push the chosen translation engine/model/token into ITDROID_LLM_* (top precedence).
			cfg.applyTranslationSystemProperties();
			String autoStart = cfg.getAutoStart();
			llmLaunchCommand = autoStart.isEmpty() ? null : autoStart;
			explorerOptions.maxStates = cfg.getMaxStates();
			explorerOptions.maxClickables = cfg.getMaxClickables();
			explorerOptions.settleSeconds = cfg.getSettleSeconds();
			explorerOptions.fillForms = cfg.isFillForms();
			launchTest = cfg.isLaunchTest();
			reuseInventory = cfg.isReuseInventory();
			graphDumps = cfg.isGraphDumps();
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
		// ipfs.csv, the LLM server log, ...).
		Files.createDirectories(Paths.get(outputPath));

		RunMetrics metrics = RunMetrics.start();
		report = new JSONObject();
		report.put("apkName", apkName);
		report.put("apkPath", new File(apkPath).getAbsolutePath());
		report.put("apkSizeKB", Math.round(new File(apkPath).length() / 102.4) / 10.0);
		report.put("appName", appName);
		report.put("alpha", alpha);
		report.put("outputFolder", outputPath);
		report.put("emulatorName", emulatorName);
		JSONObject itdroidInfo = new JSONObject();
		itdroidInfo.put("version", RunMetrics.VERSION);
		itdroidInfo.put("startedAt", metrics.startedAtIso());
		itdroidInfo.put("status", "running");
		report.put("itdroid", itdroidInfo);
		JSONObject lngsResults = new JSONObject();
		report.put("langsReport", lngsResults);

		// ------------------------------------------------------------------ phase 0: preflight
		// Cheap checks first, so an app that cannot be processed is rejected in seconds with the
		// concrete reason, instead of failing after minutes of translation.
		metrics.begin("preflight");
		String serial = AdbExplorer.resolveSerial();
		JSONObject preflight = new JSONObject();
		report.put("preflight", preflight);
		JSONObject device = Preflight.checkDevice(serial);
		preflight.put("device", device);
		int deviceSdk = ((Number) device.get("sdk")).intValue();
		List<String> deviceAbis = new ArrayList<>();
		for (Object a : (List<?>) device.get("abis")) {
			deviceAbis.add(String.valueOf(a));
		}
		String originalApkAbs = new File(apkPath).getAbsolutePath();
		if (!new File(originalApkAbs).isFile()) {
			throw new ITDroidException("APK not found: " + originalApkAbs);
		}
		List<String> apkAbis = Preflight.apkAbis(originalApkAbs);
		preflight.put("apkAbis", String.join(",", apkAbis));
		Preflight.checkAbis(apkAbis, deviceAbis);

		// Boot the local LLM server (or verify it is up) before any translation. Fails fast with a
		// clear message instead of throwing a mid-run ConnectException. Started early so the model
		// loads while the APK is being decoded.
		LLMServerManager llmServer = new LLMServerManager(langsDir);
		llmServer.ensureRunning(llmLaunchCommand, outputPath);

		// Decode the APK
		metrics.end("preflight");
		metrics.begin("decode");
		String decodedFolderPath = APKToolWrapper.openAPK(apkPath, extraPath);
		metrics.end("decode");
		metrics.begin("preflight");
		JSONObject appInfo = Preflight.checkDecodedApp(decodedFolderPath, deviceSdk);
		readVersionInfo(decodedFolderPath, appInfo);
		preflight.put("app", appInfo);

		// The original app must run on this device before anything else is attempted. Separates
		// "the app does not work here" (hardware, crash, TWA) from "ITDroid's rebuild broke it".
		if (launchTest) {
			String reason = AdbExplorer.checkLaunch(serial, appName, originalApkAbs);
			preflight.put("launchTest", reason == null ? "ok" : reason);
			if (reason != null) {
				throw new ITDroidException("PREFLIGHT: " + reason);
			}
		}
		metrics.end("preflight");
		writeReport();

		// ------------------------------------------------------------------ inventory
		metrics.begin("inventory");
		int possibleIPFS = HardcodedStringFinder.findHardCodedStrings(decodedFolderPath, appName, outputPath);
		report.put("hardcoded", possibleIPFS);

		// Read selected languages
		LanguageBundle lngBundle = new LanguageBundle(langsDir);
		System.out.println(lngBundle.printSelectedLanguages());

		// Identify translated and notTranslated languages
		String[] lngs = lngBundle.getSelectedLanguagesAsArray();
		String[] stringFiles = buildStringPaths(lngs);
		XMLComparator xmlc = new XMLComparator(stringFiles, alpha, langsDir);

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
		// only as a fallback when defaultLng is missing.
		String sourceLang = lngBundle.getBundle().containsKey("defaultLng")
				? lngBundle.getBundle().getString("defaultLng") : null;
		if (sourceLang == null || sourceLang.isEmpty()) {
			String detected = new LLMTranslator(langsDir).detectSourceLanguage(stringFiles[0]);
			if (detected != null && !detected.isEmpty()) {
				sourceLang = detected;
			}
		}
		System.out.println("Source language: " + sourceLang);
		report.put("dfltLang", sourceLang);

		// Every string of the app, with where it came from. Translations are recorded into it as
		// they happen; a previous inventory in the same output folder seeds the memory.
		NotTranslatableStringsDictionary dictionary = new NotTranslatableStringsDictionary(langsDir);
		StringInventory inventory = StringInventory.collect(decodedFolderPath, appName, dictionary);
		inventory.setSourceLanguage(sourceLang);
		Path inventoryFile = Paths.get(outputPath, "strings_inventory.json");
		if (reuseInventory) {
			int reused = inventory.loadPreviousTranslations(inventoryFile);
			if (reused > 0) {
				System.out.println("Reusing " + reused + " translations from the previous " + inventoryFile.getFileName());
			}
		}
		inventory.save(inventoryFile);
		report.put("inventory", inventory.summary());
		System.out.println("String inventory written to " + inventoryFile + ": " + inventory.summary().toJSONString());
		metrics.end("inventory");

		LLMTranslator llm = new LLMTranslator(langsDir, inventory);
		explorerOptions.llm = llm;
		explorerOptions.language = sourceLang;
		SmaliPatcher patcher = new SmaliPatcher(decodedFolderPath, appName);

		// Static facts of this run for the report: host, device, translation engine, configuration.
		JSONObject config = new JSONObject();
		config.put("sourceLanguage", sourceLang);
		JSONObject selected = new JSONObject();
		for (String code : lngs) {
			selected.put(code, lngBundle.getBundle().getString(code));
		}
		config.put("languages", selected);
		config.put("alpha", alpha);
		config.put("maxStates", explorerOptions.maxStates);
		config.put("maxClickables", explorerOptions.maxClickables);
		config.put("settleSeconds", explorerOptions.settleSeconds);
		config.put("fillForms", explorerOptions.fillForms);
		config.put("launchTest", launchTest);
		config.put("reuseInventory", reuseInventory);
		config.put("graphDumps", graphDumps);
		config.put("emulator", emulatorName);
		report.put("environment", RunMetrics.environment(device, llm.settings(), config));
		writeReport();

		// ------------------------------------------------------------------ source build + exploration
		// Built from the pristine decode (no translations, no patches). If apktool cannot rebuild the
		// app, nothing else can work: stop here with the reason.
		metrics.begin("build:" + sourceLang);
		String sourceApk = APKToolWrapper.buildAPK(extraPath, appName, outputPath);
		metrics.end("build:" + sourceLang);
		if (sourceApk == null || sourceApk.isEmpty()) {
			throw new ITDroidException("apktool could not rebuild the app, so ITDroid cannot process it "
					+ "(typical of apps whose resources need a newer apktool, e.g. Material 3 components). See the apktool output above.");
		}
		Path sourceApkCopy = Paths.get(outputPath, appName + "-" + sourceLang + "-aligned-debugSigned.apk").toAbsolutePath();
		Files.copy(Paths.get(sourceApk), sourceApkCopy, StandardCopyOption.REPLACE_EXISTING);

		String deftLanguage = sourceLang;
		metrics.begin("explore:" + sourceLang);
		String resultFolderPath = ExplorationHelper.exploreSource(deftLanguage, outputPath, true, sourceApkCopy.toString(), appName, explorerOptions);
		System.out.println("The app has been inspected");
		LayoutGraph defltGraph = new LayoutGraph(deftLanguage, resultFolderPath, graphDumps);
		metrics.end("explore:" + sourceLang);
		JSONObject dfltLangJSON = new JSONObject();
		String deftLanguageName = lngBundle.isLanguageSelected(deftLanguage)
				? lngBundle.getBundle().getString(deftLanguage) : deftLanguage;
		dfltLangJSON.put("lang", deftLanguageName);
		dfltLangJSON.put("dflt", true);
		dfltLangJSON.put("amStates", defltGraph.getStates().size());
		dfltLangJSON.put("amTrans", defltGraph.getTransitions().size());
		lngsResults.put(deftLanguage, dfltLangJSON);
		graphs.put(deftLanguage, defltGraph);
		String scriptPath = resultFolderPath + File.separator + AdbExplorer.SCRIPT_FILE;
		writeReport();

		// Truncate any ipfs.csv from a previous run and write a fresh header. The per-language
		// rows are appended later (LayoutGraphComparision opens it in append mode).
		try (BufferedWriter bw = new BufferedWriter(new FileWriter(outputPath + File.separator + "ipfs.csv", false))) {
			bw.write("language;state;nodePos;ipfScore");
			bw.newLine();
		}

		// ------------------------------------------------------------------ per language
		System.out.println("Inspecting translated versions");
		for (int i = 0; i < translatedFiles.size(); i++) {
			String lang = pathsMap.get(translatedFiles.get(i));
			if (lang.equals(sourceLang)) {
				continue;
			}
			processLanguage(lang, true, sourceLang, stringFiles[0], llm, inventory, inventoryFile, patcher, extraPath, appName,
					lngBundle, lngsResults, deftLanguage, defltGraph, scriptPath);
		}
		System.out.println("Inspecting non translated versions");
		for (int i = 0; i < notTrnsltdFiles.size(); i++) {
			String lang = pathsMap.get(notTrnsltdFiles.get(i));
			if (lang.equals(sourceLang)) {
				System.out.println("Skipping " + lang + ": it is the source language.");
				continue;
			}
			processLanguage(lang, false, sourceLang, stringFiles[0], llm, inventory, inventoryFile, patcher, extraPath, appName,
					lngBundle, lngsResults, deftLanguage, defltGraph, scriptPath);
		}

		// ------------------------------------------------------------------ close
		// The per-language builds overwrote the default-named APK. Restore it from the source build
		// (a plain copy, cannot fail like a rebuild) and leave the decoded smali pristine.
		metrics.begin("close");
		patcher.restoreOriginal();
		Files.copy(sourceApkCopy, Paths.get(sourceApk), StandardCopyOption.REPLACE_EXISTING);

		// Leave the device on the ORIGINAL app (the exact APK passed on the command line), never on a
		// translated build.
		try {
			System.out.println("Reinstalling the original app so the device ends clean...");
			AdbExplorer.installApk(serial, appName, originalApkAbs);
		} catch (Exception e) {
			System.out.println("WARNING: could not reinstall the original app: " + e.getMessage());
		}
		metrics.end("close");
	}

	/**
	 * One target language end to end: translate (while the app is off the device), rebuild, install,
	 * replay the recorded path, uninstall, compare. Failures are recorded for this language only.
	 */
	@SuppressWarnings("unchecked")
	private static void processLanguage(String lang, boolean translated, String sourceLang, String defaultStringsXml,
			LLMTranslator llm, StringInventory inventory, Path inventoryFile, SmaliPatcher patcher, String extraPath,
			String appName, LanguageBundle lngBundle, JSONObject lngsResults, String deftLanguage, LayoutGraph defltGraph,
			String scriptPath) {
		System.out.println("Processing " + lang + " app version");
		JSONObject langJson = new JSONObject();
		langJson.put("lang", lngBundle.isLanguageSelected(lang) ? lngBundle.getBundle().getString(lang) : lang);
		lngsResults.put(lang, langJson);
		int patched = 0;
		RunMetrics metrics = RunMetrics.get();
		try {
			metrics.begin("translate:" + lang);
			// 1. Resources: only when the developer did not ship this language.
			if (!translated) {
				System.out.println("We are going to translate your strings...");
				new Translator(defaultStringsXml, sourceLang, lang).translate(llm);
			} else {
				System.out.println("Resources for " + lang + " already shipped by the app; only hardcoded strings are translated.");
			}
			// 2. Hardcoded literals: translated from the inventory and patched into the smali.
			List<String> hardcoded = inventory.hardcodedTexts();
			if (!hardcoded.isEmpty()) {
				Map<String, String> translations = llm.translateUiLabels(hardcoded, sourceLang, lang);
				writeHardcodedLog(outputPath, lang, translations);
				patched = patcher.patch(translations);
				System.out.println("Patched " + patched + " hardcoded strings for " + lang);
			}
			langJson.put("patchedHardcoded", patched);
			inventory.save(inventoryFile);

			// 3. Rebuild and sign this language's APK; the smali goes back to pristine either way.
			metrics.end("translate:" + lang);
			metrics.begin("build:" + lang);
			String built;
			try {
				built = APKToolWrapper.buildAPK(extraPath, appName, outputPath);
			} finally {
				if (patched > 0) {
					patcher.restoreOriginal();
				}
			}
			if (built == null || built.isEmpty()) {
				throw new ExplorationException("apktool could not rebuild the " + lang + " version of the app");
			}
			Path apkForLang = Paths.get(outputPath, appName + "-" + lang + "-aligned-debugSigned.apk").toAbsolutePath();
			Files.copy(Paths.get(built), apkForLang, StandardCopyOption.REPLACE_EXISTING);

			metrics.end("build:" + lang);
			metrics.begin("replay:" + lang);
			// 4. Install, replay the source path, uninstall. The hardcoded-string detector is heuristic:
			// if the patched build crashes on launch (a translated literal was data after all), rebuild
			// this language from the pristine smali, with the translated resources only, and replay that.
			String resultFolder;
			try {
				resultFolder = ExplorationHelper.replayLanguage(lang, outputPath, translated, apkForLang.toString(), appName, scriptPath, explorerOptions);
			} catch (ExplorationException e) {
				if (patched == 0 || e.getMessage() == null || !e.getMessage().contains("did not reach the foreground")) {
					throw e;
				}
				System.out.println("WARNING: the " + lang + " build with " + patched + " translated hardcoded strings did not start ("
						+ e.getMessage() + "). Rebuilding it with the translated resources only.");
				langJson.put("hardcodedFallback", "the build with translated hardcoded strings crashed on launch; evaluated with translated resources only: " + e.getMessage());
				String plain = APKToolWrapper.buildAPK(extraPath, appName, outputPath);
				if (plain == null || plain.isEmpty()) {
					throw new ExplorationException("apktool could not rebuild the " + lang + " version without hardcoded patches");
				}
				Files.copy(Paths.get(plain), apkForLang, StandardCopyOption.REPLACE_EXISTING);
				resultFolder = ExplorationHelper.replayLanguage(lang, outputPath, translated, apkForLang.toString(), appName, scriptPath, explorerOptions);
			}
			System.out.println("The app has been inspected");
			langJson.put("replay", readJson(Paths.get(resultFolder, AdbExplorer.REPLAY_REPORT_FILE)));

			// 5. Compare against the source graph.
			metrics.end("replay:" + lang);
			metrics.begin("compare:" + lang);
			LayoutGraph langGraph = new LayoutGraph(lang, resultFolder, graphDumps);
			langJson.put("amStates", langGraph.getStates().size());
			langJson.put("amTrans", langGraph.getTransitions().size());
			graphs.put(lang, langGraph);
			LayoutGraphComparision lgc = new LayoutGraphComparision(deftLanguage, defltGraph,
					String.valueOf(langJson.get("lang")), lang, langGraph, resultFolder, outputPath, langJson);
			lgcomparisions.put(lang, lgc);
		} catch (ExplorationException e) {
			langJson.put("error", e.getMessage());
			System.out.println("This translated version of the app is not suitable for reproducing the steps recorded over default app version. It is possible that your automated tests might not work over this language version");
		} catch (Exception e) {
			langJson.put("error", e.getMessage() != null ? e.getMessage() : e.toString());
			System.out.println("ERROR processing " + lang + ": " + (e.getMessage() != null ? e.getMessage() : e.toString()));
		}
		for (String phase : new String[] {"translate:", "build:", "replay:", "compare:"}) {
			metrics.end(phase + lang);   // a phase cut short by an error still gets its time
		}
		writeReport();
	}

	private static Object readJson(Path file) {
		try {
			return new JSONParser().parse(Files.readString(file, StandardCharsets.UTF_8));
		} catch (Exception e) {
			return null;
		}
	}

	/** versionCode/versionName from the apktool.yml of the decoded app (static facts for the report). */
	@SuppressWarnings("unchecked")
	private static void readVersionInfo(String decodedFolderPath, JSONObject appInfo) {
		try {
			for (String line : Files.readAllLines(Paths.get(decodedFolderPath, "apktool.yml"), StandardCharsets.UTF_8)) {
				String t = line.trim();
				if (t.startsWith("versionCode:") || t.startsWith("versionName:")) {
					String key = t.substring(0, t.indexOf(':'));
					String value = t.substring(t.indexOf(':') + 1).trim().replace("'", "").replace("\"", "");
					appInfo.put(key, value);
				}
			}
		} catch (Exception ignored) {
			// apktool.yml is informative only
		}
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
		System.out.println();
		System.out.println("Other modes (no device, no LLM):");
		System.out.println("  java -jar ITDroid.jar --report [resultsFolder] [--port N] [--no-browser]   web report");
		System.out.println("  java -jar ITDroid.jar --reanalyze <folder> [--out <folder>]              recompute the comparison of stored evaluations");
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

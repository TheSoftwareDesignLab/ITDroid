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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import uniandes.tsdl.itdroid.explorer.AdbExplorer;
import uniandes.tsdl.itdroid.helper.APKToolWrapper;
import uniandes.tsdl.itdroid.helper.AppCode;
import uniandes.tsdl.itdroid.helper.CodeLiteralExtractor;
import uniandes.tsdl.itdroid.helper.ConsumerRules;
import uniandes.tsdl.itdroid.helper.ExplorationException;
import uniandes.tsdl.itdroid.helper.ExplorationHelper;
import uniandes.tsdl.itdroid.helper.HardcodedLog;
import uniandes.tsdl.itdroid.helper.HardcodedPolicy;
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
import uniandes.tsdl.itdroid.helper.PortablePaths;
import uniandes.tsdl.itdroid.helper.RunMetrics;
import uniandes.tsdl.itdroid.helper.SmaliPatcher;
import uniandes.tsdl.itdroid.helper.SmaliScan;
import uniandes.tsdl.itdroid.helper.StringInventory;
import uniandes.tsdl.itdroid.helper.XMLComparator;
import uniandes.tsdl.itdroid.helper.XmlLiteralExtractor;
import uniandes.tsdl.itdroid.helper.XmlLiteralFinder;
import uniandes.tsdl.itdroid.model.LayoutGraph;
import uniandes.tsdl.itdroid.model.LayoutGraphComparision;
import uniandes.tsdl.itdroid.translator.LLMTranslator;
import uniandes.tsdl.itdroid.translator.Translator;

/**
 * Orchestrator. The pipeline, in order:
 * <ol>
 *   <li>Preflight: device (API 33+, ABIs), APK native code, LLM server. A failure is reported at once
 *       but only stops the run after the next step, which needs neither.</li>
 *   <li>Decode and static analysis: the hardcoded strings ({@code hcs.txt}), every string of the app
 *       with its origin ({@code strings_inventory.json}) and the strings each language lacks. With
 *       {@code --static} the run ends here.</li>
 *   <li>Preflight of the app: manifest (launcher, not a TWA, minSdk) and a launch test of the
 *       ORIGINAL app.</li>
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
	/** --static: decode and analyse only (hardcoded strings, inventory); no device, no LLM. */
	static boolean staticOnly = false;
	/** Which hardcoded literals of the code are translated (config key hardcoded.translate). */
	static HardcodedPolicy hardcodedPolicy = HardcodedPolicy.DEFAULT;
	/** Move the translated literals of the code to string resources (config key hardcoded.extractCode). */
	static boolean extractCode = true;
	/** Keep the APK built for each language after it was evaluated (config key output.keepLanguageApks). */
	static boolean keepLanguageApks = true;

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
		if (args.length >= 1 && args[0].equals("--static")) {
			// java -jar ITDroid.jar --static <same arguments as a run>: decodes the APK and writes hcs.txt,
			// strings_inventory.json and the static sections of report.json. No device, no LLM.
			staticOnly = true;
			args = Arrays.copyOfRange(args, 1, args.length);
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
				if (finishedOk && staticOnly) {
					System.out.println("Static analysis is finished: see hcs.txt, strings_inventory.json and report.json in " + outputPath);
				} else if (finishedOk) {
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

	/**
	 * Writes report.json with everything known so far (called after each milestone, not only at the
	 * end). What is written carries no path of this machine: see {@link PortablePaths}.
	 */
	static synchronized void writeReport() {
		if (outputPath == null || report == null) {
			return;
		}
		Object portable = new PortablePaths(Paths.get(outputPath)).scrub(report);
		try (FileWriter file = new FileWriter(outputPath + File.separator + "report.json")) {
			file.write(((JSONObject) portable).toJSONString());
			file.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Runs when the JVM is shutting down. A run that was still going (Ctrl+C, the terminal closed,
	 * the process asked to stop) leaves its report saying so, instead of "running" for ever. A
	 * process killed outright never gets here; the report server tells that case from the process
	 * id the report carries.
	 */
	@SuppressWarnings("unchecked")
	static void markInterrupted() {
		for (int attempt = 0; attempt < 3; attempt++) {
			try {
				synchronized (ITDroid.class) {
					if (report == null || outputPath == null || !(report.get("itdroid") instanceof JSONObject)) {
						return;
					}
					JSONObject info = (JSONObject) report.get("itdroid");
					if (!"running".equals(info.get("status"))) {
						return;
					}
					info.put("status", "interrupted");
					info.put("finishedAt", Instant.now().toString());
					RunMetrics metrics = RunMetrics.get();
					if (metrics != null) {
						info.put("durationSec", Math.round(metrics.elapsedSec() * 10.0) / 10.0);
					}
					writeReport();
				}
				return;
			} catch (RuntimeException e) {
				// the run was adding to the report at that very moment: once more
				if (report != null && report.get("itdroid") instanceof JSONObject) {
					((JSONObject) report.get("itdroid")).put("status", "running");
				}
			}
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
			emulatorName = staticOnly ? "" : cfg.getEmulator();
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
			LayoutGraphComparision.setMinSimilarity(cfg.getMinSimilarity());
			AppCode.setExtraRoots(cfg.getCodePackages());
			hardcodedPolicy = cfg.getHardcodedPolicy();
			extractCode = cfg.isExtractCode();
			keepLanguageApks = cfg.isKeepLanguageApks();
		} else if (staticOnly && args.length == 5) {
			// --static <apk> <package> <extraFolder> <settingsDir> <outputFolder>
			apkPath = args[0];
			appName = args[1];
			extraPath = args[2];
			langsDir = args[3];
			alpha = 0;
			outputPath = args[4];
			emulatorName = "";
			llmLaunchCommand = null;
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
		// Paths are written relative to the results folder (the name alone for a file outside it), so
		// the folder can be moved or published; the hash says which APK it was.
		report.put("apkPath", new PortablePaths(Paths.get(outputPath)).relative(apkPath));
		if (new File(apkPath).isFile()) {
			report.put("apkSha256", PortablePaths.sha256(Paths.get(apkPath)));
		}
		report.put("apkSizeKB", Math.round(new File(apkPath).length() / 102.4) / 10.0);
		report.put("appName", appName);
		report.put("alpha", alpha);
		report.put("outputFolder", Paths.get(outputPath).toAbsolutePath().normalize().getFileName().toString());
		report.put("emulatorName", emulatorName);
		JSONObject itdroidInfo = new JSONObject();
		itdroidInfo.put("version", RunMetrics.VERSION);
		itdroidInfo.put("startedAt", metrics.startedAtIso());
		itdroidInfo.put("status", "running");
		// The process that is writing this report: with it, whoever reads a report that still says
		// "running" can tell whether the run is alive or was killed.
		itdroidInfo.put("pid", ProcessHandle.current().pid());
		ProcessHandle.current().info().startInstant().ifPresent(t -> itdroidInfo.put("processStartedAt", t.toString()));
		report.put("itdroid", itdroidInfo);
		Runtime.getRuntime().addShutdownHook(new Thread(ITDroid::markInterrupted, "itdroid-interrupted"));
		// Written at once, so the run shows in the web report from its first second and a run that
		// dies while decoding the APK leaves a trace.
		writeReport();
		JSONObject lngsResults = new JSONObject();
		report.put("langsReport", lngsResults);

		String originalApkAbs = new File(apkPath).getAbsolutePath();
		if (!new File(originalApkAbs).isFile()) {
			throw new ITDroidException("APK not found: " + originalApkAbs);
		}
		JSONObject preflight = new JSONObject();
		report.put("preflight", preflight);

		// ------------------------------------------------------------------ preflight: device and LLM
		// Cheap checks first, so a run that cannot go on says why within seconds. A failure here does not
		// stop the static analysis below, which needs neither the device nor the LLM: the failure is
		// kept and thrown once hcs.txt, the inventory and their sections of report.json are written.
		Exception notReady = null;
		String serial = null;
		JSONObject device = null;
		int deviceSdk = -1;
		if (!staticOnly) {
			metrics.begin("preflight");
			try {
				serial = AdbExplorer.resolveSerial();
				device = Preflight.checkDevice(serial);
				preflight.put("device", device);
				deviceSdk = ((Number) device.get("sdk")).intValue();
				List<String> deviceAbis = new ArrayList<>();
				for (Object a : (List<?>) device.get("abis")) {
					deviceAbis.add(String.valueOf(a));
				}
				List<String> apkAbis = Preflight.apkAbis(originalApkAbs);
				preflight.put("apkAbis", String.join(",", apkAbis));
				Preflight.checkAbis(apkAbis, deviceAbis);

				// Boot the local LLM server (or verify it is up) before any translation. Started early so
				// the model loads while the APK is being decoded.
				new LLMServerManager(langsDir).ensureRunning(llmLaunchCommand, outputPath);
			} catch (Exception e) {
				notReady = e;
				System.out.println("ERROR: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
				System.out.println("The evaluation cannot run. Finishing the static analysis (hardcoded strings and inventory, "
						+ "no device or LLM needed) before stopping.");
			}
			metrics.end("preflight");
		}

		// ------------------------------------------------------------------ decode + static analysis
		metrics.begin("decode");
		String decodedFolderPath = APKToolWrapper.openAPK(apkPath, extraPath);
		metrics.end("decode");
		JSONObject appInfo = Preflight.describeDecodedApp(decodedFolderPath);
		readVersionInfo(decodedFolderPath, appInfo);
		preflight.put("app", appInfo);
		// With another app's package nothing fails at once: the launch finds no activity and the
		// explorer ends up tapping the launcher. Say what is wrong while it is cheap.
		String realPackage = String.valueOf(appInfo.get("package"));
		if (!realPackage.isEmpty() && !realPackage.equals(appName)) {
			throw new ITDroidException("PREFLIGHT: the package given is '" + appName + "' but the package of this APK is '"
					+ realPackage + "'. Pass the APK's own package (appPackage in the config file).");
		}

		// Hardcoded strings: one scan of the app's smali plus its XML, shared by hcs.txt, the inventory
		// and the patcher.
		metrics.begin("inventory");
		SmaliScan scan = SmaliScan.scan(decodedFolderPath, appName, ConsumerRules.load(langsDir));
		List<XmlLiteralFinder.Literal> xmlLiterals = XmlLiteralFinder.find(decodedFolderPath);
		List<HardcodedStringFinder.Hit> hits = HardcodedStringFinder.hits(scan);
		report.put("hardcoded", (long) HardcodedStringFinder.writeHcs(Paths.get(outputPath, "hcs.txt"), hits, xmlLiterals));
		JSONArray codePackages = new JSONArray();
		codePackages.addAll(scan.packageRoots());
		report.put("codePackages", codePackages);
		System.out.println("App code found under " + scan.packageRoots() + " (" + scan.files().size() + " smali files)");

		// Read selected languages
		LanguageBundle lngBundle = new LanguageBundle(langsDir);
		System.out.println(lngBundle.printSelectedLanguages());

		// Identify translated and notTranslated languages
		String[] lngs = lngBundle.getSelectedLanguagesAsArray();
		String[] stringFiles = buildStringPaths(lngs);
		StringInventory.AppName appNameInfo = StringInventory.appName(decodedFolderPath);
		XMLComparator xmlc = new XMLComparator(stringFiles, alpha, langsDir, appNameInfo.resources);

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

		// Non-internationalized strings: per language, whether the app ships it and which of the
		// default strings it lacks.
		JSONObject nis = new JSONObject();
		nis.put("defaultStringsFile", xmlc.hasDefault());
		nis.put("defaultStrings", (long) xmlc.defaultStrings());
		nis.put("alpha", (long) alpha);
		JSONObject nisLanguages = new JSONObject();
		for (int i = 1; i < stringFiles.length; i++) {
			nisLanguages.put(lngs[i - 1], xmlc.status(stringFiles[i]).toJson());
		}
		nis.put("languages", nisLanguages);
		report.put("nonInternationalized", nis);

		// Source language: the configured defaultLng (reliable). LLM auto-detection is only a fallback
		// when defaultLng is missing, and waits until the LLM is known to be up.
		String sourceLang = lngBundle.getBundle().containsKey("defaultLng")
				? lngBundle.getBundle().getString("defaultLng") : null;

		// Every string of the app, with where it came from. Translations are recorded into it as
		// they happen; a previous inventory in the same output folder seeds the memory.
		NotTranslatableStringsDictionary dictionary = new NotTranslatableStringsDictionary(langsDir);
		// The text written in the XML moves to string resources (the app shows the same text in its
		// source language), so it is translated like any other resource. Done after the languages
		// were compared: the new strings are ITDroid's, not strings a language lacks.
		XmlLiteralExtractor.Extraction extraction = XmlLiteralExtractor.extract(decodedFolderPath, xmlLiterals, appNameInfo.texts);
		if (!extraction.names().isEmpty()) {
			System.out.println("Moved " + extraction.names().size() + " literals of the XML (" + extraction.attributes
					+ " attributes) to string resources named " + XmlLiteralExtractor.PREFIX + "N");
		}
		for (String problem : extraction.problems) {
			System.out.println("WARNING: XML literals not extracted from " + problem);
		}
		// The literals of the code that are translated move to resources as well. Each one stays in
		// the code and is handed to a small class added to the app, which returns its resource in
		// the language the app runs in; so the single internationalized APK shows them translated.
		// What cannot be moved is patched per language.
		CodeLiteralExtractor.Extraction codeExtraction = CodeLiteralExtractor.none();
		JSONObject codeExtractionInfo = new JSONObject();
		codeExtractionInfo.put("enabled", extractCode);
		if (extractCode) {
			codeExtraction = CodeLiteralExtractor.extract(decodedFolderPath,
					StringInventory.placesToTranslate(decodedFolderPath, hits, hardcodedPolicy),
					CodeLiteralExtractor.fullDexFolders(apkPath));
			if (codeExtraction.active()) {
				System.out.println("Moved " + codeExtraction.names().size() + " literals of the code (" + codeExtraction.places.size()
						+ " places) to string resources named " + CodeLiteralExtractor.PREFIX + "<hash>");
			}
			for (String problem : codeExtraction.problems) {
				System.out.println("NOTE: code literal not moved to a resource: " + problem);
			}
			codeExtractionInfo.put("literals", (long) codeExtraction.names().size());
			codeExtractionInfo.put("places", (long) codeExtraction.places.size());
		}
		report.put("codeExtraction", codeExtractionInfo);
		Path inventoryFile = Paths.get(outputPath, "strings_inventory.json");
		StringInventory inventory = collectInventory(decodedFolderPath, appName, dictionary, hits, xmlLiterals, extraction,
				codeExtraction, sourceLang, inventoryFile);
		if (sourceLang != null && !sourceLang.isEmpty()) {
			report.put("dfltLang", sourceLang);
		}
		System.out.println("String inventory written to " + inventoryFile + ": " + inventory.summary().toJSONString());
		metrics.end("inventory");
		writeReport();

		if (staticOnly) {
			itdroidInfo.put("mode", "static");
			return;
		}
		if (notReady != null) {
			throw notReady;
		}

		// ------------------------------------------------------------------ preflight: the app on the device
		metrics.begin("preflight");
		Preflight.checkDecodedApp(appInfo, deviceSdk);

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

		if (sourceLang == null || sourceLang.isEmpty()) {
			String detected = xmlc.hasDefault() ? new LLMTranslator(langsDir).detectSourceLanguage(stringFiles[0]) : null;
			if (detected == null || detected.isEmpty()) {
				detected = "en";
				System.out.println("WARNING: no defaultLng in settings.properties and the source language could not be detected; assuming en.");
			}
			sourceLang = detected;
			inventory.setSourceLanguage(sourceLang);
			inventory.save(inventoryFile);
		}
		System.out.println("Source language: " + sourceLang);
		report.put("dfltLang", sourceLang);
		writeReport();

		LLMTranslator llm = new LLMTranslator(langsDir, inventory);
		// The name of the app is not translated where it stands alone (see StringInventory.appName);
		// inside other texts ("Welcome to ShiftSwift") it is protected when it is recognisably a name.
		List<String> ownNames = new ArrayList<>();
		for (String name : appNameInfo.texts) {
			if (LLMTranslator.looksLikeBrand(name)) {
				ownNames.add(name);
			}
		}
		llm.addProtectedTerms(ownNames);
		if (!llm.protectedTerms().isEmpty()) {
			System.out.println("Terms kept as written in every translation: " + llm.protectedTerms());
		}
		String sourceUnsupported = llm.unsupportedLanguage(sourceLang);
		if (sourceUnsupported != null) {
			throw new ITDroidException(sourceUnsupported + " (the source language of the app)");
		}
		explorerOptions.llm = llm;
		explorerOptions.language = sourceLang;
		SmaliPatcher patcher = new SmaliPatcher(decodedFolderPath);

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
		config.put("keepLanguageApks", keepLanguageApks);
		config.put("minSimilarity", LayoutGraphComparision.getMinSimilarity());
		config.put("hardcodedTranslate", hardcodedPolicy.key);
		config.put("emulator", emulatorName);
		report.put("environment", RunMetrics.environment(device, llm.settings(), config));
		writeReport();

		// ------------------------------------------------------------------ source build + exploration
		// Built from the decode with no translation in it: the code as it was, except that the
		// literals moved to resources are read through the class ITDroid added. If apktool cannot
		// rebuild the app, nothing else can work: stop here with the reason.
		metrics.begin("build:" + sourceLang);
		String sourceApk = APKToolWrapper.buildAPK(extraPath, appName, outputPath);
		if (codeExtraction.active()) {
			// The app now runs code ITDroid added to it. If it does not build or does not start like
			// that, the literals go back to the code and are patched per language instead.
			String problem = null;
			if (sourceApk == null || sourceApk.isEmpty()) {
				problem = "apktool could not rebuild the app";
			} else {
				String reason = AdbExplorer.checkLaunch(serial, appName, sourceApk, "the app with its code literals read from resources");
				if (reason != null) {
					problem = reason;
				}
			}
			if (problem != null) {
				System.out.println("WARNING: with the literals of the code moved to resources, " + problem
						+ ". Putting them back in the code; they will be patched per language.");
				codeExtraction.revert();
				codeExtraction = CodeLiteralExtractor.none();
				codeExtractionInfo.put("reverted", problem);
				codeExtractionInfo.put("literals", 0L);
				codeExtractionInfo.put("places", 0L);
				inventory = collectInventory(decodedFolderPath, appName, dictionary, hits, xmlLiterals, extraction,
						codeExtraction, sourceLang, inventoryFile);
				llm = new LLMTranslator(langsDir, inventory);
				llm.addProtectedTerms(ownNames);
				explorerOptions.llm = llm;
				sourceApk = APKToolWrapper.buildAPK(extraPath, appName, outputPath);
			}
		}
		metrics.end("build:" + sourceLang);
		if (!APKToolWrapper.buildAdjustments().isEmpty()) {
			// what apktool could not rebuild as decoded: adjusted so the app builds, and said so
			report.put("buildAdjustments", jsonArray(APKToolWrapper.buildAdjustments()));
		}
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
		List<String> sourceCrashes = crashesIn(Paths.get(scriptPath));
		if (!sourceCrashes.isEmpty()) {
			dfltLangJSON.put("crashes", jsonArray(sourceCrashes));
			System.out.println("NOTE: the original app crashed while it was explored: " + sourceCrashes);
		}
		writeReport();

		// Truncate any ipfs.csv from a previous run and write a fresh header. The per-language
		// rows are appended later (LayoutGraphComparision opens it in append mode).
		try (BufferedWriter bw = new BufferedWriter(new FileWriter(outputPath + File.separator + "ipfs.csv", false))) {
			bw.write("language;state;nodePos;ipfScore;langNodePos;kind");
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
					lngBundle, lngsResults, deftLanguage, defltGraph, scriptPath, sourceCrashes, codeExtraction);
		}
		System.out.println("Inspecting non translated versions");
		for (int i = 0; i < notTrnsltdFiles.size(); i++) {
			String lang = pathsMap.get(notTrnsltdFiles.get(i));
			if (lang.equals(sourceLang)) {
				System.out.println("Skipping " + lang + ": it is the source language.");
				continue;
			}
			processLanguage(lang, false, sourceLang, stringFiles[0], llm, inventory, inventoryFile, patcher, extraPath, appName,
					lngBundle, lngsResults, deftLanguage, defltGraph, scriptPath, sourceCrashes, codeExtraction);
		}

		// ------------------------------------------------------------------ close
		// The per-language builds overwrote the default-named APK. It becomes the internationalized
		// APK: one build of the decoded app as it is now, with the pristine code, the resources of
		// every language processed and the literals moved out of the XML. It follows the language of
		// the device (or the per-app language) like any app; the hardcoded strings of the code are
		// only translated in the per-language APKs. If it cannot be built, the source build is kept.
		metrics.begin("close");
		patcher.restoreOriginal();
		JSONObject i18nApk = new JSONObject();
		JSONArray i18nLanguages = new JSONArray();
		for (String code : lngs) {
			if (Paths.get(decodedFolderPath, "res", "values-" + code, "strings.xml").toFile().isFile()) {
				i18nLanguages.add(code);
			}
		}
		i18nApk.put("languages", i18nLanguages);
		String allLanguagesApk = null;
		metrics.begin("build:all");
		try {
			allLanguagesApk = APKToolWrapper.buildAPK(extraPath, appName, outputPath);
		} catch (Exception e) {
			System.out.println("WARNING: building the internationalized APK failed: " + e.getMessage());
		}
		metrics.end("build:all");
		if (allLanguagesApk == null || allLanguagesApk.isEmpty()) {
			// No second copy of the source build under the default name: there is no internationalized
			// APK, and what is left under that name (the build of the last language) is removed so it
			// is not taken for one.
			System.out.println("WARNING: the APK with every language could not be built; the source build is " + sourceApkCopy.getFileName() + ".");
			Files.deleteIfExists(Paths.get(sourceApk));
			Files.deleteIfExists(Paths.get(sourceApk + ".idsig"));
			i18nApk.put("built", false);
			i18nApk.put("sourceBuild", sourceApkCopy.getFileName().toString());
		} else {
			i18nApk.put("built", true);
			i18nApk.put("file", new File(allLanguagesApk).getName());
			System.out.println("Internationalized APK (" + i18nLanguages + " besides " + sourceLang + "): " + allLanguagesApk);
		}
		report.put("internationalizedApk", i18nApk);

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
			String scriptPath, List<String> sourceCrashes, CodeLiteralExtractor.Extraction codeExtraction) {
		System.out.println("Processing " + lang + " app version");
		JSONObject langJson = new JSONObject();
		langJson.put("lang", lngBundle.isLanguageSelected(lang) ? lngBundle.getBundle().getString(lang) : lang);
		langJson.put("shippedByApp", translated);
		langJson.put("resultsFolder", translated ? "trnsResults" : "noTrnsResults");
		lngsResults.put(lang, langJson);
		int patched = 0;
		RunMetrics metrics = RunMetrics.get();
		Path apkForLang = Paths.get(outputPath, appName + "-" + lang + "-aligned-debugSigned.apk").toAbsolutePath();
		try {
			String unsupported = llm.unsupportedLanguage(lang);
			if (unsupported != null) {
				throw new ITDroidException(unsupported + ": this language was not evaluated");
			}
			metrics.begin("translate:" + lang);
			// 1. Resources. A language the app does not ship is translated whole. One it ships is
			// evaluated as the developer left it: only the strings ITDroid added to the app (the
			// literals moved out of the XML) are filled in.
			if (!new File(defaultStringsXml).isFile()) {
				System.out.println("The app has no default strings.xml: there are no resources to translate, only hardcoded strings.");
			} else if (!translated) {
				System.out.println("We are going to translate your strings...");
				new Translator(defaultStringsXml, sourceLang, lang).translate(llm);
			} else {
				System.out.println("Resources for " + lang + " already shipped by the app; only hardcoded strings are translated.");
				if (!inventory.extractedResources().isEmpty()) {
					llm.translate(defaultStringsXml, sourceLang, lang, inventory.extractedResources());
				}
			}
			// 2. Hardcoded literals of the code: translated from the inventory and patched place by
			// place (only where the policy says the text is shown).
			List<String> hardcoded = inventory.hardcodedTexts();
			Map<String, String> translations = hardcoded.isEmpty() ? new LinkedHashMap<>()
					: llm.translateUiLabels(hardcoded, sourceLang, lang);
			List<SmaliPatcher.Patch> applied = patcher.patch(inventory.hardcodedPlaces(), translations, codeExtraction::currentLine);
			patched = applied.size();
			int moved = codeExtraction.places.size();
			Path hardcodedCsv = Paths.get(outputPath, "hardcoded_" + lang + ".csv");
			HardcodedLog.write(hardcodedCsv, inventory, lang, translations, applied, null);
			System.out.println("Hardcoded strings of the code for " + lang + ": " + moved + " places read from resources, "
					+ patched + " places patched");
			langJson.put("patchedHardcoded", patched);
			langJson.put("extractedCodeLiterals", (long) codeExtraction.names().size());
			langJson.put("extractedXmlLiterals", (long) (inventory.extractedResources().size() - codeExtraction.names().size()));
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
			Files.copy(Paths.get(built), apkForLang, StandardCopyOption.REPLACE_EXISTING);

			metrics.end("build:" + lang);
			metrics.begin("replay:" + lang);
			// 4. Install, replay the source path, uninstall. What is patched is chosen by a static scan:
			// if the patched build does not start, or crashes where the original app did not, a
			// translated literal was data after all. The language is then rebuilt from the pristine
			// smali, with the translated resources only, and replayed again; report.json and the CSV
			// say which literals were reverted and why.
			String resultFolder = null;
			String patchProblem = null;
			try {
				resultFolder = ExplorationHelper.replayLanguage(lang, outputPath, translated, apkForLang.toString(), appName, scriptPath, explorerOptions);
				List<String> crashes = newCrashes(resultFolder, sourceCrashes);
				if (!crashes.isEmpty()) {
					langJson.put("crashes", jsonArray(crashes));
					if (patched > 0 || moved > 0) {
						patchProblem = "the app crashed during the replay: " + crashes.get(0);
					}
				}
			} catch (ExplorationException e) {
				if ((patched == 0 && moved == 0) || e.getMessage() == null || !e.getMessage().contains("did not reach the foreground")) {
					throw e;
				}
				patchProblem = e.getMessage();
			}
			if (patchProblem != null) {
				System.out.println("WARNING: the " + lang + " build with " + (patched + moved) + " translated places of hardcoded strings failed ("
						+ patchProblem + "). Rebuilding it with the translated resources only.");
				langJson.put("hardcodedFallback", "the build with translated hardcoded strings failed and was evaluated with translated resources only: " + patchProblem);
				langJson.put("revertedHardcoded", patched + moved);
				langJson.put("patchedHardcoded", 0);
				HardcodedLog.write(hardcodedCsv, inventory, lang, translations, applied, patchProblem);
				if (moved > 0) {
					// the literals read from resources go back to the source text for this language only
					File languageStrings = new File(new File(defaultStringsXml).getParentFile().getParentFile(),
							"values-" + lang + File.separator + "strings.xml");
					CodeLiteralExtractor.removeTranslations(languageStrings);
				}
				String plain = APKToolWrapper.buildAPK(extraPath, appName, outputPath);
				if (plain == null || plain.isEmpty()) {
					throw new ExplorationException("apktool could not rebuild the " + lang + " version without hardcoded patches");
				}
				Files.copy(Paths.get(plain), apkForLang, StandardCopyOption.REPLACE_EXISTING);
				resultFolder = ExplorationHelper.replayLanguage(lang, outputPath, translated, apkForLang.toString(), appName, scriptPath, explorerOptions);
				List<String> again = newCrashes(resultFolder, sourceCrashes);
				if (again.isEmpty()) {
					langJson.remove("crashes");
				} else {
					// it crashes without the patches too: they were not the cause, and the crash stays on record
					langJson.put("crashes", jsonArray(again));
					langJson.put("hardcodedFallbackNote", "the build without the patches crashed as well: " + again.get(0));
				}
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
		if (!keepLanguageApks) {
			// It was only needed to be installed and replayed. The internationalized APK built at the
			// end has the resources of every language; what only this file had is the hardcoded
			// strings patched into its code, which hardcoded_<lang>.csv lists.
			try {
				if (Files.deleteIfExists(apkForLang)) {
					langJson.put("apkKept", false);
				}
			} catch (IOException e) {
				System.out.println("WARNING: could not remove " + apkForLang.getFileName() + ": " + e.getMessage());
			}
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
		System.out.println("  java -jar ITDroid.jar --static <config file>                              decode and analyse only: hardcoded strings and inventory");
		System.out.println("  java -jar ITDroid.jar --static <apk> <package> <extraFolder> <settingsDir> <outputFolder>");
		System.out.println("  java -jar ITDroid.jar --report [resultsFolder] [--port N] [--no-browser]   web report");
		System.out.println("  java -jar ITDroid.jar --reanalyze <folder> [--out <folder>] [--min-similarity <0..1>]   recompute the comparison of stored evaluations");
	}

	/**
	 * Builds the inventory from the decoded app as it is now, seeds it with the translations of a
	 * previous run, writes it and puts its counts in the report.
	 */
	@SuppressWarnings("unchecked")
	private static StringInventory collectInventory(String decodedFolderPath, String appName, NotTranslatableStringsDictionary dictionary,
			List<HardcodedStringFinder.Hit> hits, List<XmlLiteralFinder.Literal> xmlLiterals, XmlLiteralExtractor.Extraction extraction,
			CodeLiteralExtractor.Extraction codeExtraction, String sourceLang, Path inventoryFile) throws IOException {
		StringInventory inventory = StringInventory.collect(decodedFolderPath, appName, dictionary, hits, xmlLiterals,
				hardcodedPolicy, extraction, codeExtraction);
		inventory.setSourceLanguage(sourceLang);
		if (reuseInventory) {
			int reused = inventory.loadPreviousTranslations(inventoryFile);
			if (reused > 0) {
				System.out.println("Reusing " + reused + " translations from the previous " + inventoryFile.getFileName());
			}
		}
		inventory.save(inventoryFile);
		report.put("inventory", inventory.summary());
		return inventory;
	}

	/** The crashes recorded in an exploration script or a replay report (their "crashes" list); empty when none. */
	private static List<String> crashesIn(Path jsonFile) {
		List<String> out = new ArrayList<>();
		Object parsed = readJson(jsonFile);
		Object list = parsed instanceof JSONObject ? ((JSONObject) parsed).get("crashes") : null;
		if (list instanceof JSONArray) {
			for (Object o : (JSONArray) list) {
				out.add(String.valueOf(o));
			}
		}
		return out;
	}

	/** The crashes of a replay that the original app did not have while it was explored. */
	private static List<String> newCrashes(String resultFolder, List<String> sourceCrashes) {
		List<String> out = crashesIn(Paths.get(resultFolder, AdbExplorer.REPLAY_REPORT_FILE));
		out.removeAll(sourceCrashes);
		return out;
	}

	@SuppressWarnings("unchecked")
	private static JSONArray jsonArray(List<String> values) {
		JSONArray a = new JSONArray();
		a.addAll(values);
		return a;
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

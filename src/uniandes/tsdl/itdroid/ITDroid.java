package uniandes.tsdl.itdroid;

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
import java.util.Set;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import uniandes.tsdl.itdroid.explorer.AdbExplorer;
import uniandes.tsdl.itdroid.explorer.AdbExplorer.ExplorationException;
import uniandes.tsdl.itdroid.explorer.Device;
import uniandes.tsdl.itdroid.helper.APKToolWrapper;
import uniandes.tsdl.itdroid.helper.ApkContent;
import uniandes.tsdl.itdroid.helper.AppCode;
import uniandes.tsdl.itdroid.helper.AppFramework;
import uniandes.tsdl.itdroid.helper.CodeLiteralExtractor;
import uniandes.tsdl.itdroid.helper.ConsumerRules;
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
import uniandes.tsdl.itdroid.helper.RtlRepair;
import uniandes.tsdl.itdroid.helper.RunMetrics;
import uniandes.tsdl.itdroid.helper.SetupScript;
import uniandes.tsdl.itdroid.helper.SmaliScan;
import uniandes.tsdl.itdroid.helper.SourceLanguageCheck;
import uniandes.tsdl.itdroid.helper.StringInventory;
import uniandes.tsdl.itdroid.helper.XMLComparator;
import uniandes.tsdl.itdroid.helper.Translatability;
import uniandes.tsdl.itdroid.helper.XmlLiterals;
import uniandes.tsdl.itdroid.model.LayoutGraph;
import uniandes.tsdl.itdroid.model.LayoutGraphComparision;
import uniandes.tsdl.itdroid.model.ScreenTexts;
import uniandes.tsdl.itdroid.translator.LLMTranslator;

/**
 * Entry point and orchestrator. It runs the preflight, the static analysis, the recorded source
 * exploration, then per target language a translated build replayed and compared against the source,
 * and finally builds the internationalized APK. report.json is rewritten after each step.
 */
public class ITDroid {

	static HashMap<String, String> pathsMap = new HashMap<>();

	static JSONObject report;
	static String outputPath;
	static boolean finishedOk;

	/** The config file of the run, read where each setting is used. */
	static RunConfig config;
	static AdbExplorer.Options explorerOptions = new AdbExplorer.Options();
	/** Decode and analyse only, with no device and no LLM. */
	static boolean staticOnly = false;
	/** The decoded app and its minSdk, for the layout repair. */
	static String decodedApp;
	static int appMinSdk;

	@SuppressWarnings("unchecked")
	public static void main(String[] args) {
		if (args.length >= 1 && args[0].equals("--report")) {
			// serves the web report, no evaluation
			try {
				ReportServer.run(Arrays.copyOfRange(args, 1, args.length));
			} catch (Exception e) {
				System.out.println("ERROR: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
				System.exit(1);
			}
			return;
		}
		if (args.length >= 1 && args[0].equals("--reanalyze")) {
			// recomputes the comparison of stored evaluations, no device and no LLM
			try {
				Reanalysis.run(Arrays.copyOfRange(args, 1, args.length));
			} catch (Exception e) {
				System.out.println("ERROR: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
				System.exit(1);
			}
			return;
		}
		if (args.length >= 1 && args[0].equals("--static")) {
			// static analysis only, with the same config as a run
			staticOnly = true;
			args = Arrays.copyOfRange(args, 1, args.length);
		}
		if (args.length > 1 || (args.length == 1 && args[0].startsWith("-"))) {
			printUsage();
			System.exit(args[0].equals("--help") || args[0].equals("-h") ? 0 : 1);
		}
		try {
			runITDroid(args.length == 1 ? args[0] : RunConfig.DEFAULT_FILE);
			finishedOk = true;
		} catch (Exception e) {
			// a refusal or a wrong config is a message for the user, anything else keeps its stack trace
			if (e instanceof ITDroidException || e instanceof IllegalArgumentException || e instanceof java.io.FileNotFoundException) {
				System.out.println("ERROR: " + e.getMessage());
			} else {
				e.printStackTrace();
			}
			if (report != null) {
				report.put("error", e.getMessage() != null ? e.getMessage() : e.toString());
			}
		} finally {
			// report is still null when the config was wrong
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
			System.exit(1);   // lets batch tooling tell an aborted run apart
		}
	}

	/** Writes report.json with everything known so far, scrubbed by {@link PortablePaths}. */
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
	 * Shutdown hook that marks a run still going as interrupted, so its report does not say "running"
	 * for ever. A process killed outright never gets here.
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
				// the run was writing the report at that moment, so try again
				if (report != null && report.get("itdroid") instanceof JSONObject) {
					((JSONObject) report.get("itdroid")).put("status", "running");
				}
			}
		}
	}

	/** Closes the itdroid and performance sections of the report. */
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
	public static void runITDroid(String configFile) throws ExplorationException, Exception {
		// everything comes from the one config file
		RunConfig cfg = RunConfig.load(configFile);
		config = cfg;
		String apkPath = cfg.getApkPath();
		String extraPath = cfg.getExtraFolder();
		int alpha = cfg.getAlpha();
		String emulatorName = staticOnly ? "" : cfg.getEmulator();
		Device.preferredDevice = emulatorName;
		List<String> languages = cfg.getLanguages();
		String configuredSource = cfg.getSourceLanguage();
		String extraRules = cfg.getExtraRules();
		NotTranslatableStringsDictionary dictionary = new NotTranslatableStringsDictionary(cfg.getKeepResources());
		cfg.applyTranslationSystemProperties();
		String autoStart = cfg.getAutoStart();
		String llmLaunchCommand = autoStart.isEmpty() ? null : autoStart;
		explorerOptions.maxStates = cfg.getMaxStates();
		explorerOptions.maxClickables = cfg.getMaxClickables();
		explorerOptions.maxMinutes = cfg.getMaxMinutes();
		try {
			explorerOptions.setup = SetupScript.load(cfg.getSetupFile());
		} catch (IOException e) {
			throw new ITDroidException(e.getMessage());
		}
		explorerOptions.settleSeconds = cfg.getSettleSeconds();
		explorerOptions.fillForms = cfg.isFillForms();
		explorerOptions.grantPermissions = cfg.isGrantPermissions();
		LayoutGraphComparision.setMinSimilarity(cfg.getMinSimilarity());
		LayoutGraphComparision.setTextOverGraphic(cfg.isTextOverGraphic());
		LayoutGraphComparision.setTextCut(cfg.isTextCut());
		AppCode.setExtraRoots(cfg.getCodePackages());
		// read now so a value it does not know stops the run before any work
		HardcodedPolicy hardcodedPolicy = cfg.getHardcodedPolicy();

		// the package of the APK, unless the config names one, which must then be that one
		if (!new File(apkPath).isFile()) {
			throw new ITDroidException("APK not found: " + new File(apkPath).getAbsolutePath() + " (apkPath in " + configFile + ")");
		}
		String apkPackage = ApkContent.packageName(apkPath);
		String appName = cfg.getAppPackage();
		if (appName.isEmpty()) {
			if (apkPackage.isEmpty()) {
				throw new ITDroidException("The package of the APK could not be read from its manifest. Set appPackage in " + configFile + ".");
			}
			appName = apkPackage;
			System.out.println("Package of the APK: " + appName);
		} else if (!apkPackage.isEmpty() && !apkPackage.equals(appName)) {
			throw new ITDroidException("PREFLIGHT: appPackage is '" + appName + "' but the package of this APK is '" + apkPackage
					+ "'. Leave appPackage empty in " + configFile + " to take it from the APK.");
		}
		// the folder of the app inside the results folder, which the web report lists
		outputPath = Paths.get(cfg.getResultsDir(), appName).toString();
		String apkName;

		// a literal replace, since replaceAll reads the Windows backslash as a regex escape
		apkName = new File(apkPath).getName();
		String os = System.getProperty("os.name").toLowerCase();
		if (os.contains("win")) {
			extraPath = extraPath.replace("/", File.separator);
			apkPath = apkPath.replace("/", File.separator);
		}
		if (!extraPath.endsWith(File.separator)) {
			extraPath = extraPath + File.separator;
		}

		Files.createDirectories(Paths.get(outputPath));

		RunMetrics metrics = RunMetrics.start();
		report = new JSONObject();
		report.put("apkName", apkName);
		// a portable path, with the hash saying which APK it was
		report.put("apkPath", new PortablePaths(Paths.get(outputPath)).relative(apkPath));
		if (new File(apkPath).isFile()) {
			report.put("apkSha256", PortablePaths.sha256(Paths.get(apkPath)));
		}
		report.put("apkSizeKB", Math.round(new File(apkPath).length() / 102.4) / 10.0);
		report.put("appName", appName);
		report.put("alpha", alpha);
		report.put("emulatorName", emulatorName);
		JSONObject itdroidInfo = new JSONObject();
		itdroidInfo.put("version", RunMetrics.VERSION);
		itdroidInfo.put("startedAt", metrics.startedAtIso());
		itdroidInfo.put("status", "running");
		// tells whether a report still saying "running" belongs to a live run
		itdroidInfo.put("pid", ProcessHandle.current().pid());
		ProcessHandle.current().info().startInstant().ifPresent(t -> itdroidInfo.put("processStartedAt", t.toString()));
		report.put("itdroid", itdroidInfo);
		Runtime.getRuntime().addShutdownHook(new Thread(ITDroid::markInterrupted, "itdroid-interrupted"));
		// written at once so the run shows in the web report and an early crash leaves a trace
		writeReport();
		JSONObject lngsResults = new JSONObject();
		report.put("langsReport", lngsResults);

		String originalApkAbs = new File(apkPath).getAbsolutePath();
		if (!new File(originalApkAbs).isFile()) {
			throw new ITDroidException("APK not found: " + originalApkAbs);
		}
		JSONObject preflight = new JSONObject();
		report.put("preflight", preflight);

		// Preflight of the device and the LLM. A failure is kept and thrown after the static analysis,
		// which needs neither.
		Exception notReady = null;
		String serial = null;
		JSONObject device = null;
		int deviceSdk = -1;
		if (!staticOnly) {
			metrics.begin("preflight");
			try {
				serial = Device.resolveSerial();
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

				// started early so the model loads while the APK is decoded
				new LLMServerManager().ensureRunning(llmLaunchCommand, outputPath);
			} catch (Exception e) {
				notReady = e;
				System.out.println("ERROR: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
				System.out.println("The evaluation cannot run. Finishing the static analysis (hardcoded strings and inventory, "
						+ "no device or LLM needed) before stopping.");
			}
			metrics.end("preflight");
		}

		// decode and static analysis
		metrics.begin("decode");
		String decodedFolderPath = APKToolWrapper.openAPK(apkPath, extraPath);
		metrics.end("decode");
		JSONObject appInfo = Preflight.describeDecodedApp(decodedFolderPath);
		readVersionInfo(decodedFolderPath, appInfo);
		preflight.put("app", appInfo);
		decodedApp = decodedFolderPath;
		appMinSdk = ((Number) appInfo.get("minSdk")).intValue();
		// ITDroid does not translate a cross-platform interface, so say it before measuring
		List<String> frameworks = (List<String>) appInfo.get("frameworks");
		if (!frameworks.isEmpty()) {
			System.out.println("NOTE: this app is built with a cross-platform framework. " + AppFramework.describe(frameworks)
					+ ". ITDroid translates Android string resources and the literals of the Dalvik code: the texts of this"
					+ " interface stay as the app ships them, and each language shows what the app does by itself.");
		}

		// one scan of the smali and the XML, shared by hcs.txt, the inventory and the extraction
		metrics.begin("inventory");
		SmaliScan scan = SmaliScan.scan(decodedFolderPath, appName, ConsumerRules.load(extraRules));
		List<XmlLiterals.Literal> xmlLiterals = XmlLiterals.find(decodedFolderPath);
		List<HardcodedStringFinder.Hit> hits = HardcodedStringFinder.hits(scan);
		report.put("hardcoded", (long) HardcodedStringFinder.writeHcs(Paths.get(outputPath, "hcs.txt"), hits, xmlLiterals));
		System.out.println("App code found under " + scan.packageRoots() + " (" + scan.files().size() + " smali files)");

		LanguageBundle lngBundle = new LanguageBundle(languages);
		System.out.println(lngBundle.printSelectedLanguages());

		String[] lngs = lngBundle.getSelectedLanguagesAsArray();
		String[] stringFiles = buildStringPaths(lngs);
		Translatability.AppName appNameInfo = Translatability.appName(decodedFolderPath);
		XMLComparator xmlc = new XMLComparator(stringFiles, alpha, dictionary, appNameInfo.resources);

		ArrayList<String> translatedFiles = xmlc.getUsefull();
		System.out.println("Your application is translated to the following languages:");
		for (int i = 0; i < translatedFiles.size(); i++) {
			System.out.println(LanguageBundle.name(pathsMap.get(translatedFiles.get(i))));
		}
		ArrayList<String> notTrnsltdFiles = xmlc.getUseLess();
		System.out.println("Your application is not translated to the following languages:");
		for (int i = 0; i < notTrnsltdFiles.size(); i++) {
			System.out.println(LanguageBundle.name(pathsMap.get(notTrnsltdFiles.get(i))));
		}

		// per language, whether the app ships it and which default strings it lacks
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

		// the configured sourceLanguage, with LLM detection later only when it is empty
		String sourceLang = configuredSource.isEmpty() ? null : configuredSource;

		// XML literals move to string resources after the language comparison, since the new strings
		// are ITDroid's and not strings a language lacks
		XmlLiterals.Extraction extraction = XmlLiterals.extract(decodedFolderPath, xmlLiterals, appNameInfo.texts);
		if (!extraction.names().isEmpty()) {
			System.out.println("Moved " + extraction.names().size() + " literals of the XML (" + extraction.attributes
					+ " attributes) to string resources named " + XmlLiterals.PREFIX + "N");
		}
		for (String problem : extraction.problems) {
			System.out.println("WARNING: XML literals not extracted from " + problem);
		}
		// Code literals move to resources too, looked up at run time by a class added to the app.
		// What cannot be moved stays in the source language and the report says why.
		CodeLiteralExtractor.Extraction codeExtraction = CodeLiteralExtractor.none(
				"moving code literals to resources is turned off (hardcoded.extractCode=false)");
		JSONObject codeExtractionInfo = new JSONObject();
		codeExtractionInfo.put("enabled", cfg.isExtractCode());
		if (cfg.isExtractCode()) {
			codeExtraction = CodeLiteralExtractor.extract(decodedFolderPath,
					StringInventory.placesToTranslate(decodedFolderPath, hits, hardcodedPolicy),
					CodeLiteralExtractor.fullDexFolders(apkPath));
			if (codeExtraction.active()) {
				System.out.println("Moved " + codeExtraction.names().size() + " literals of the code (" + codeExtraction.places.size()
						+ " places) to string resources named " + CodeLiteralExtractor.PREFIX + "<hash>");
			}
			codeExtractionInfo.put("literals", (long) codeExtraction.names().size());
			codeExtractionInfo.put("places", (long) codeExtraction.places.size());
		}
		report.put("codeExtraction", codeExtractionInfo);
		Path inventoryFile = Paths.get(outputPath, "strings_inventory.json");
		StringInventory inventory = collectInventory(decodedFolderPath, appName, dictionary, hits, xmlLiterals, extraction,
				codeExtraction, sourceLang, inventoryFile);
		putNotMoved(codeExtractionInfo, inventory, true);
		if (sourceLang != null && !sourceLang.isEmpty()) {
			report.put("dfltLang", sourceLang);
		}
		System.out.println("String inventory written to " + inventoryFile + ": " + inventory.summary().toJSONString());
		// an unchanged screen text the APK lacks came from the network or the device, and the report says so
		List<String> apkTexts = new ArrayList<>();
		for (StringInventory.Entry e : inventory.entries()) {
			apkTexts.add(e.text);
		}
		String apkFiles = "";
		try {
			ApkContent content = ApkContent.read(originalApkAbs);
			apkTexts.addAll(content.codeStrings);
			apkFiles = content.files;
		} catch (IOException e) {
			System.out.println("WARNING: the texts of the APK could not be read (" + e.getMessage() + "); only those of the inventory are known");
		}
		ScreenTexts.setApkTexts(apkTexts, apkFiles);
		metrics.end("inventory");
		writeReport();

		if (staticOnly) {
			itdroidInfo.put("mode", "static");
			return;
		}
		if (notReady != null) {
			throw notReady;
		}

		// preflight of the app on the device
		metrics.begin("preflight");
		Preflight.checkDecodedApp(appInfo, deviceSdk);

		// tells an app that does not run here apart from one the rebuild broke
		if (cfg.isLaunchTest()) {
			String reason = Device.checkLaunch(serial, appName, originalApkAbs);
			preflight.put("launchTest", reason == null ? "ok" : reason);
			if (reason != null) {
				throw new ITDroidException("PREFLIGHT: " + reason);
			}
		}
		metrics.end("preflight");

		if (sourceLang == null || sourceLang.isEmpty()) {
			String detected = xmlc.hasDefault() ? new LLMTranslator().detectSourceLanguage(stringFiles[0]) : null;
			if (detected == null || detected.isEmpty()) {
				detected = "en";
				System.out.println("WARNING: sourceLanguage is empty in the config and the source language could not be detected, assuming en.");
			}
			sourceLang = detected;
			inventory.setSourceLanguage(sourceLang);
			inventory.save(inventoryFile);
		}
		System.out.println("Source language: " + sourceLang);
		report.put("dfltLang", sourceLang);
		// warns when the default strings seem to be in another language, without changing it
		SourceLanguageCheck.Result sourceCheck = SourceLanguageCheck.of(stringFiles[0], sourceLang);
		report.put("sourceLanguageCheck", sourceCheck.toJson());
		if (sourceCheck.mismatch) {
			System.out.println("WARNING: " + sourceCheck.message());
		}
		writeReport();

		LLMTranslator llm = new LLMTranslator(inventory);
		// the app name and the product name inside it are also protected in other texts when they look like a name
		List<String> ownNames = new ArrayList<>();
		for (String name : appNameInfo.texts) {
			for (String variant : Translatability.nameVariants(name)) {
				if (Translatability.looksLikeBrand(variant) && !ownNames.contains(variant)) {
					ownNames.add(variant);
				}
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

		JSONObject settings = new JSONObject();
		settings.put("sourceLanguage", sourceLang);
		JSONObject selected = new JSONObject();
		for (String code : lngs) {
			selected.put(code, LanguageBundle.name(code));
		}
		settings.put("languages", selected);
		settings.put("alpha", alpha);
		settings.put("maxStates", explorerOptions.maxStates);
		settings.put("maxClickables", explorerOptions.maxClickables);
		settings.put("maxMinutes", explorerOptions.maxMinutes);
		settings.put("setupCommands", (long) explorerOptions.setup.size());
		settings.put("settleSeconds", explorerOptions.settleSeconds);
		settings.put("fillForms", explorerOptions.fillForms);
		settings.put("grantPermissions", explorerOptions.grantPermissions);
		settings.put("launchTest", cfg.isLaunchTest());
		settings.put("reuseInventory", cfg.isReuseInventory());
		settings.put("keepLanguageApks", cfg.isKeepLanguageApks());
		settings.put("rtlRepair", cfg.isRtlRepair());
		settings.put("minSimilarity", LayoutGraphComparision.getMinSimilarity());
		settings.put("textOverGraphic", LayoutGraphComparision.isTextOverGraphic());
		settings.put("textCut", LayoutGraphComparision.isTextCut());
		settings.put("hardcodedTranslate", hardcodedPolicy.key);
		settings.put("emulator", emulatorName);
		report.put("environment", RunMetrics.environment(device, llm.settings(), settings));
		writeReport();

		// Source build and exploration, with no translation. If apktool cannot rebuild the app,
		// nothing else can work.
		metrics.begin("build:" + sourceLang);
		String sourceApk = APKToolWrapper.buildAPK(extraPath, appName, outputPath);
		if (codeExtraction.active()) {
			// if the app does not build or start with the added class, the literals go back to the code
			String problem = null;
			if (sourceApk == null || sourceApk.isEmpty()) {
				problem = "apktool could not rebuild the app";
			} else {
				String reason = Device.checkLaunch(serial, appName, sourceApk, "the app with its code literals read from resources");
				if (reason != null) {
					problem = reason;
				}
			}
			if (problem != null) {
				System.out.println("WARNING: with the literals of the code moved to resources, " + problem
						+ ". Putting them back in the code, where they stay in the source language.");
				codeExtraction.revert();
				codeExtraction = CodeLiteralExtractor.none("the app did not build or start with its code literals read from resources ("
						+ problem + ")");
				codeExtractionInfo.put("reverted", problem);
				codeExtractionInfo.put("literals", 0L);
				codeExtractionInfo.put("places", 0L);
				inventory = collectInventory(decodedFolderPath, appName, dictionary, hits, xmlLiterals, extraction,
						codeExtraction, sourceLang, inventoryFile);
				putNotMoved(codeExtractionInfo, inventory, false);
				llm = new LLMTranslator(inventory);
				llm.addProtectedTerms(ownNames);
				explorerOptions.llm = llm;
				sourceApk = APKToolWrapper.buildAPK(extraPath, appName, outputPath);
			}
		}
		metrics.end("build:" + sourceLang);
		if (!APKToolWrapper.buildAdjustments().isEmpty()) {
			// what had to be adjusted so apktool could rebuild the app
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
		String resultFolderPath = AdbExplorer.exploreSource(deftLanguage, outputPath, true, sourceApkCopy.toString(), appName, explorerOptions);
		System.out.println("The app has been inspected");
		LayoutGraph defltGraph = new LayoutGraph(resultFolderPath);
		metrics.end("explore:" + sourceLang);
		JSONObject dfltLangJSON = new JSONObject();
		dfltLangJSON.put("lang", LanguageBundle.name(deftLanguage));
		dfltLangJSON.put("dflt", true);
		dfltLangJSON.put("amStates", defltGraph.getStates().size());
		dfltLangJSON.put("amTrans", defltGraph.getTransitionCount());
		lngsResults.put(deftLanguage, dfltLangJSON);
		String scriptPath = resultFolderPath + File.separator + AdbExplorer.SCRIPT_FILE;
		List<String> sourceCrashes = crashesIn(Paths.get(scriptPath));
		if (!sourceCrashes.isEmpty()) {
			dfltLangJSON.put("crashes", jsonArray(sourceCrashes));
			System.out.println("NOTE: the original app crashed while it was explored: " + sourceCrashes);
		}
		// how far the exploration got and what ended it
		Object script = readJson(Paths.get(scriptPath));
		Object summary = script instanceof JSONObject ? ((JSONObject) script).get("exploration") : null;
		if (summary instanceof JSONObject) {
			JSONObject exploration = (JSONObject) summary;
			exploration.put("activitiesDeclared", appInfo.get("activities"));
			dfltLangJSON.put("exploration", exploration);
			if (AdbExplorer.STOP_EXHAUSTED.equals(exploration.get("stop"))) {
				System.out.println("NOTE: the exploration ended at " + defltGraph.getStates().size()
						+ " screens with nothing left to tap. Either the app is that small, or it needs content or an"
						+ " account before it shows more.");
			}
		}
		writeReport();

		// per language
		System.out.println("Inspecting translated versions");
		for (int i = 0; i < translatedFiles.size(); i++) {
			String lang = pathsMap.get(translatedFiles.get(i));
			if (lang.equals(sourceLang)) {
				continue;
			}
			requireDevice(serial, lang);
			processLanguage(lang, true, sourceLang, stringFiles[0], llm, inventory, inventoryFile, extraPath, appName,
					lngsResults, deftLanguage, defltGraph, scriptPath, sourceCrashes, codeExtraction,
					copiesOfSource(xmlc, translatedFiles.get(i)));
		}
		System.out.println("Inspecting non translated versions");
		for (int i = 0; i < notTrnsltdFiles.size(); i++) {
			String lang = pathsMap.get(notTrnsltdFiles.get(i));
			if (lang.equals(sourceLang)) {
				System.out.println("Skipping " + lang + ": it is the source language.");
				continue;
			}
			requireDevice(serial, lang);
			processLanguage(lang, false, sourceLang, stringFiles[0], llm, inventory, inventoryFile, extraPath, appName,
					lngsResults, deftLanguage, defltGraph, scriptPath, sourceCrashes, codeExtraction,
					copiesOfSource(xmlc, notTrnsltdFiles.get(i)));
		}

		// Close with the internationalized APK, one build with the resources of every language, left
		// under the default name.
		metrics.begin("close");
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
		// with rtl.repair the delivered APK carries the repair, or is built without it if it fails
		RtlRepair finalRepair = null;
		if (cfg.isRtlRepair()) {
			try {
				finalRepair = RtlRepair.apply(decodedFolderPath, appMinSdk);
			} catch (Exception e) {
				System.out.println("WARNING: the RTL repair could not be applied to the internationalized APK: " + e.getMessage());
			}
		}
		try {
			allLanguagesApk = APKToolWrapper.buildAPK(extraPath, appName, outputPath);
			if ((allLanguagesApk == null || allLanguagesApk.isEmpty()) && finalRepair != null && finalRepair.changedSomething()) {
				System.out.println("WARNING: the app does not build with the RTL repair; building the internationalized APK without it.");
				finalRepair.revert();
				finalRepair = null;
				i18nApk.put("rtlRepairError", "the app does not build with the repair: built without it");
				allLanguagesApk = APKToolWrapper.buildAPK(extraPath, appName, outputPath);
			}
		} catch (Exception e) {
			System.out.println("WARNING: building the internationalized APK failed: " + e.getMessage());
		}
		if (finalRepair != null) {
			i18nApk.put("rtlRepair", finalRepair.toJson());
		}
		metrics.end("build:all");
		if (allLanguagesApk == null || allLanguagesApk.isEmpty()) {
			// removes the last language build so it is not taken for the internationalized APK
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

		// leaves the device on the original APK, never on a translated build
		try {
			System.out.println("Reinstalling the original app so the device ends clean...");
			Device.installApk(serial, appName, originalApkAbs);
		} catch (Exception e) {
			System.out.println("WARNING: could not reinstall the original app: " + e.getMessage());
		}
		metrics.end("close");
	}

	/** Seconds to wait for a device that stopped answering. */
	static final int DEVICE_WAIT_SECONDS = 120;

	/**
	 * Stops the run when the device no longer answers, since a hung emulator would make every later
	 * language fail as if the language were at fault.
	 */
	private static void requireDevice(String serial, String nextLanguage) throws ITDroidException {
		if (Device.deviceAnswers(serial)) {
			return;
		}
		System.out.println("WARNING: the device " + serial + " does not answer; waiting up to " + DEVICE_WAIT_SECONDS + " s for it");
		if (!Device.waitForDevice(serial, DEVICE_WAIT_SECONDS)) {
			throw new ITDroidException("the device " + serial + " stopped answering before the '" + nextLanguage
					+ "' version could be evaluated (waited " + DEVICE_WAIT_SECONDS + " s). The languages evaluated until then are in the"
					+ " report; this one and the ones after it were not evaluated.");
		}
	}

	/**
	 * Builds this language with the layouts repaired for right-to-left and restores the decoded app.
	 * Returns null when there was nothing to repair or the build failed.
	 */
	@SuppressWarnings("unchecked")
	private static Path buildRtlRepaired(String lang, String extraPath, String appName, JSONObject info) {
		RtlRepair repair = null;
		try {
			repair = RtlRepair.apply(decodedApp, appMinSdk);
			info.put("applied", repair.toJson());
			if (!repair.changedSomething()) {
				info.put("note", "nothing to repair: the app declares supportsRtl and its layouts name no side");
				System.out.println("RTL repair for " + lang + ": nothing to repair");
				return null;
			}
			System.out.println("RTL repair for " + lang + ": " + repair.toJson().toJSONString() + "; building the repaired app");
			String built = APKToolWrapper.buildAPK(extraPath, appName, outputPath);
			if (built == null || built.isEmpty()) {
				info.put("error", "apktool could not rebuild the app with the repair");
				return null;
			}
			Path apk = Paths.get(outputPath, appName + "-" + lang + "-rtl-aligned-debugSigned.apk").toAbsolutePath();
			Files.copy(Paths.get(built), apk, StandardCopyOption.REPLACE_EXISTING);
			info.put("apk", apk.getFileName().toString());
			return apk;
		} catch (Exception e) {
			info.put("error", "the repair failed: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
			return null;
		} finally {
			if (repair != null) {
				try {
					repair.revert();
				} catch (IOException e) {
					System.out.println("WARNING: could not undo the RTL repair in the decoded app: " + e.getMessage());
				}
			}
		}
	}

	/** Replays and compares the repaired build like any language, under the rtlResults folder. */
	@SuppressWarnings("unchecked")
	private static void evaluateRtlRepaired(String lang, Path apk, JSONObject info, String appName, String scriptPath,
			String deftLanguage, LayoutGraph defltGraph, String languageName) {
		try {
			String folder = AdbExplorer.replayInto("rtlResults", lang, outputPath, apk.toString(), appName, scriptPath, explorerOptions);
			info.put("resultsFolder", "rtlResults");
			info.put("replay", readJson(Paths.get(folder, AdbExplorer.REPLAY_REPORT_FILE)));
			LayoutGraph repaired = new LayoutGraph(folder);
			info.put("amStates", repaired.getStates().size());
			info.put("amTrans", repaired.getTransitionCount());
			new LayoutGraphComparision(deftLanguage, defltGraph, languageName + " (RTL repaired)", lang, repaired, info);
		} catch (Exception e) {
			info.put("error", "the repaired build could not be evaluated: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
			System.out.println("WARNING: the " + lang + " build with the RTL repair could not be evaluated: " + e.getMessage());
		}
	}

	/**
	 * One target language end to end, translated, rebuilt, replayed and compared. A failure is
	 * recorded for this language only.
	 *
	 * @param copiesOfSource strings the app ships for this language with the default text, translated in place
	 */
	@SuppressWarnings("unchecked")
	private static void processLanguage(String lang, boolean translated, String sourceLang, String defaultStringsXml,
			LLMTranslator llm, StringInventory inventory, Path inventoryFile, String extraPath,
			String appName, JSONObject lngsResults, String deftLanguage, LayoutGraph defltGraph,
			String scriptPath, List<String> sourceCrashes, CodeLiteralExtractor.Extraction codeExtraction,
			Set<String> copiesOfSource) {
		System.out.println("Processing " + lang + " app version");
		JSONObject langJson = new JSONObject();
		langJson.put("lang", LanguageBundle.name(lang));
		langJson.put("shippedByApp", translated);
		langJson.put("resultsFolder", translated ? "trnsResults" : "noTrnsResults");
		lngsResults.put(lang, langJson);
		RunMetrics metrics = RunMetrics.get();
		Path apkForLang = Paths.get(outputPath, appName + "-" + lang + "-aligned-debugSigned.apk").toAbsolutePath();
		// the repaired right-to-left build and its outcome
		Path repairedApk = null;
		JSONObject repairInfo = null;
		try {
			String unsupported = llm.unsupportedLanguage(lang);
			if (unsupported != null) {
				throw new ITDroidException(unsupported + ": this language was not evaluated");
			}
			metrics.begin("translate:" + lang);
			// Resources, with the hardcoded strings moved to them. A shipped language keeps its strings and
			// only gets the ones ITDroid added, and the app's copies of the default text are translated in place.
			if (!new File(defaultStringsXml).isFile()) {
				System.out.println("The app has no default strings.xml: there are no resources to translate.");
			} else if (!translated) {
				System.out.println("We are going to translate your strings...");
				llm.translate(defaultStringsXml, sourceLang, lang, null, copiesOfSource);
			} else {
				System.out.println("Resources for " + lang + " already shipped by the app; only the strings ITDroid added are translated.");
				if (!inventory.extractedResources().isEmpty() || !copiesOfSource.isEmpty()) {
					llm.translate(defaultStringsXml, sourceLang, lang, inventory.extractedResources(), copiesOfSource);
				}
			}
			langJson.put("translation", llm.report(lang));
			int moved = codeExtraction.places.size();
			Path hardcodedCsv = Paths.get(outputPath, "hardcoded_" + lang + ".csv");
			HardcodedLog.write(hardcodedCsv, inventory, lang, llm.keptInSource(lang), null);
			System.out.println("Hardcoded strings of the code for " + lang + ": " + moved + " places read from resources, "
					+ inventory.summary().get("hardcodedPlacesNotMoved") + " places left in the source language");
			langJson.put("extractedCodeLiterals", (long) codeExtraction.names().size());
			langJson.put("extractedXmlLiterals", (long) (inventory.extractedResources().size() - codeExtraction.names().size()));
			inventory.save(inventoryFile);

			metrics.end("translate:" + lang);
			metrics.begin("build:" + lang);
			String built = APKToolWrapper.buildAPK(extraPath, appName, outputPath);
			if (built != null && !built.isEmpty()) {
				Files.copy(Paths.get(built), apkForLang, StandardCopyOption.REPLACE_EXISTING);
				if (config.isRtlRepair() && Helper.languageIsRTL(lang)) {
					// built now so the two builds differ only in the repair
					metrics.end("build:" + lang);
					metrics.begin("rtlBuild:" + lang);
					repairInfo = new JSONObject();
					repairedApk = buildRtlRepaired(lang, extraPath, appName, repairInfo);
					metrics.end("rtlBuild:" + lang);
				}
			}
			if (built == null || built.isEmpty()) {
				throw new ExplorationException("apktool could not rebuild the " + lang + " version of the app");
			}

			metrics.end("build:" + lang);
			metrics.begin("replay:" + lang);
			// Replay the source path. If the build does not start or crashes where the original did not, a
			// translated code literal was data, so the language is rebuilt with those in the source language.
			String resultFolder = null;
			String hardcodedProblem = null;
			try {
				resultFolder = AdbExplorer.replayLanguage(lang, outputPath, translated, apkForLang.toString(), appName, scriptPath, explorerOptions);
				List<String> crashes = newCrashes(resultFolder, sourceCrashes);
				if (!crashes.isEmpty()) {
					langJson.put("crashes", jsonArray(crashes));
					if (moved > 0) {
						hardcodedProblem = "the app crashed during the replay: " + crashes.get(0);
					}
				}
			} catch (ExplorationException e) {
				if (moved == 0 || e.getMessage() == null || !e.getMessage().contains("did not reach the foreground")) {
					throw e;
				}
				hardcodedProblem = e.getMessage();
			}
			if (hardcodedProblem != null) {
				System.out.println("WARNING: the " + lang + " build with the " + codeExtraction.names().size() + " code literals translated failed ("
						+ hardcodedProblem + "). Rebuilding it with those literals in the source language.");
				// the moved literals fall back to the source text for this language only
				File languageStrings = new File(new File(defaultStringsXml).getParentFile().getParentFile(),
						"values-" + lang + File.separator + "strings.xml");
				int dropped = CodeLiteralExtractor.removeTranslations(languageStrings);
				langJson.put("hardcodedFallback", "the build with the code literals translated failed and was evaluated with them"
						+ " in the source language: " + hardcodedProblem);
				langJson.put("revertedHardcoded", (long) dropped);
				HardcodedLog.write(hardcodedCsv, inventory, lang, llm.keptInSource(lang), hardcodedProblem);
				String plain = APKToolWrapper.buildAPK(extraPath, appName, outputPath);
				if (plain == null || plain.isEmpty()) {
					throw new ExplorationException("apktool could not rebuild the " + lang + " version without its translated code literals");
				}
				Files.copy(Paths.get(plain), apkForLang, StandardCopyOption.REPLACE_EXISTING);
				resultFolder = AdbExplorer.replayLanguage(lang, outputPath, translated, apkForLang.toString(), appName, scriptPath, explorerOptions);
				List<String> again = newCrashes(resultFolder, sourceCrashes);
				if (again.isEmpty()) {
					langJson.remove("crashes");
				} else {
					// it crashes without them too, so they were not the cause
					langJson.put("crashes", jsonArray(again));
					langJson.put("hardcodedFallbackNote", "the build without the translated code literals crashed as well: " + again.get(0));
				}
			}
			System.out.println("The app has been inspected");
			langJson.put("replay", readJson(Paths.get(resultFolder, AdbExplorer.REPLAY_REPORT_FILE)));

			// Compare against the source graph.
			metrics.end("replay:" + lang);
			metrics.begin("compare:" + lang);
			LayoutGraph langGraph = new LayoutGraph(resultFolder);
			langJson.put("amStates", langGraph.getStates().size());
			langJson.put("amTrans", langGraph.getTransitionCount());
			new LayoutGraphComparision(deftLanguage, defltGraph,
					String.valueOf(langJson.get("lang")), lang, langGraph, langJson);

			// The same path on the repaired right-to-left build.
			if (repairInfo != null) {
				langJson.put("rtlRepair", repairInfo);
				if (repairedApk != null && hardcodedProblem != null) {
					repairInfo.put("error", "not evaluated: the build of this language had to be made again without its hardcoded strings");
				} else if (repairedApk != null) {
					metrics.end("compare:" + lang);
					metrics.begin("rtlReplay:" + lang);
					evaluateRtlRepaired(lang, repairedApk, repairInfo, appName, scriptPath, deftLanguage, defltGraph,
							String.valueOf(langJson.get("lang")));
					metrics.end("rtlReplay:" + lang);
				}
			}
		} catch (ExplorationException e) {
			langJson.put("error", e.getMessage());
			System.out.println("This translated version of the app is not suitable for reproducing the steps recorded over default app version. It is possible that your automated tests might not work over this language version");
		} catch (Exception e) {
			langJson.put("error", e.getMessage() != null ? e.getMessage() : e.toString());
			System.out.println("ERROR processing " + lang + ": " + (e.getMessage() != null ? e.getMessage() : e.toString()));
		}
		for (String phase : new String[] {"translate:", "build:", "replay:", "compare:"}) {
			metrics.end(phase + lang);   // phases cut short by an error still get their time
		}
		if (repairInfo != null && !langJson.containsKey("rtlRepair")) {
			langJson.put("rtlRepair", repairInfo);   // the language failed before the repaired build was evaluated
		}
		if (!config.isKeepLanguageApks()) {
			// the internationalized APK has the same resources, so nothing of the language is lost
			try {
				if (repairedApk != null) {
					Files.deleteIfExists(repairedApk);
				}
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

	/** Copies versionCode and versionName from the apktool.yml of the decoded app. */
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

	private static void printUsage() {
		System.out.println("Usage, from the ITDroid folder. Everything is set in itdroid.config.properties.");
		System.out.println("  java -jar target/ITDroid-1.0.0.jar [config file]                evaluate the app of the config, itdroid.config.properties by default");
		System.out.println("  java -jar target/ITDroid-1.0.0.jar --static [config file]       decode and analyse only, no device and no LLM");
		System.out.println("  java -jar target/ITDroid-1.0.0.jar --report [folder] [--port N] [--no-browser]   web report of the results folder");
		System.out.println("  java -jar target/ITDroid-1.0.0.jar --reanalyze <folder> [--out <folder>] [--min-similarity <0..1>]   recompute stored evaluations");
	}

	/** Builds and saves the inventory, seeded with the translations of a previous run. */
	@SuppressWarnings("unchecked")
	private static StringInventory collectInventory(String decodedFolderPath, String appName, NotTranslatableStringsDictionary dictionary,
			List<HardcodedStringFinder.Hit> hits, List<XmlLiterals.Literal> xmlLiterals, XmlLiterals.Extraction extraction,
			CodeLiteralExtractor.Extraction codeExtraction, String sourceLang, Path inventoryFile) throws IOException {
		StringInventory inventory = StringInventory.collect(decodedFolderPath, appName, dictionary, hits, xmlLiterals,
				config.getHardcodedPolicy(), extraction, codeExtraction);
		inventory.setSourceLanguage(sourceLang);
		if (config.isReuseInventory()) {
			int reused = inventory.loadPreviousTranslations(inventoryFile);
			if (reused > 0) {
				System.out.println("Reusing " + reused + " translations from the previous " + inventoryFile.getFileName());
			}
		}
		inventory.save(inventoryFile);
		report.put("inventory", inventory.summary());
		return inventory;
	}

	/** The code literals chosen for translation that stay in the code, deduplicated and capped, for report.json. */
	@SuppressWarnings("unchecked")
	static void putNotMoved(JSONObject codeExtractionInfo, StringInventory inventory, boolean print) {
		Map<String, String> notMoved = inventory.notMovedCodeLiterals();
		JSONArray list = new JSONArray();
		int printed = 0;
		for (Map.Entry<String, String> e : notMoved.entrySet()) {
			if (list.size() < NOT_MOVED_LISTED) {
				JSONObject o = new JSONObject();
				o.put("text", e.getKey());
				o.put("reason", e.getValue());
				list.add(o);
			}
			if (print && printed++ < NOT_MOVED_PRINTED) {
				System.out.println("NOTE: code literal not moved to a resource, it stays in the source language: \"" + e.getKey() + "\" ("
						+ e.getValue() + ")");
			}
		}
		if (print && notMoved.size() > NOT_MOVED_PRINTED) {
			System.out.println("NOTE: and " + (notMoved.size() - NOT_MOVED_PRINTED) + " more code literals not moved, see codeExtraction in report.json");
		}
		codeExtractionInfo.put("notMoved", list);
		codeExtractionInfo.put("notMovedCount", (long) notMoved.size());
	}

	/** Entries of codeExtraction.notMoved in report.json, and lines of them printed. */
	private static final int NOT_MOVED_LISTED = 200;
	private static final int NOT_MOVED_PRINTED = 20;

	/** The strings a language file only copies from the default text, which ITDroid translates in place. */
	private static Set<String> copiesOfSource(XMLComparator xmlc, String languageStringsPath) {
		XMLComparator.LanguageStatus status = xmlc.status(languageStringsPath);
		return status == null ? Set.of() : new java.util.LinkedHashSet<>(status.copiesOfSource);
	}

	/** The crashes listed in an exploration script or a replay report. */
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

	/** The crashes of a replay that the original app did not have. */
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

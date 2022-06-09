package uniandes.tsdl.itdroid;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import io.github.cdimascio.dotenv.Dotenv;
import uniandes.tsdl.itdroid.IBM.IBMTranslator;
import uniandes.tsdl.itdroid.helper.APKToolWrapper;
import uniandes.tsdl.itdroid.helper.ASTHelper;
import uniandes.tsdl.itdroid.helper.EmulatorHelper;
import uniandes.tsdl.itdroid.helper.Helper;
import uniandes.tsdl.itdroid.helper.ITDroidException;
import uniandes.tsdl.itdroid.helper.LanguageBundle;
import uniandes.tsdl.itdroid.helper.RIPHelper;
import uniandes.tsdl.itdroid.helper.RipException;
import uniandes.tsdl.itdroid.helper.XMLComparator;
import uniandes.tsdl.itdroid.model.LayoutGraph;
import uniandes.tsdl.itdroid.model.LayoutGraphComparision;
import uniandes.tsdl.itdroid.translator.Translator;

public class ITDroid {
	// CONSOLE STYLING
	public static final String RESET = "\033[0m";
	public static final String GREEN = "\033[0;32m";
	public static final String CYAN_BOLD = "\033[1;36m";
	public static final String RED_BOLD = "\033[1;31m";
	public static final String PURPLE_BOLD = "\033[1;34m";
	public static final String CHECK_ICON = "\u2713";

	// REPORT ENVIRONMENT VARIABLE NAME
	private static final String REACT_APP_OUTPUT_FOLDER = "REACT_APP_OUTPUT_FOLDER";

	static HashMap<String, String> pathsMap = new HashMap<>();

	static HashMap<String, LayoutGraph> graphs = new HashMap<String, LayoutGraph>();
	static HashMap<String, LayoutGraphComparision> lgcomparisions = new HashMap<String, LayoutGraphComparision>();
	static JSONObject report;
	static String outputPath;

	public static void main(String[] args) {
		try {
//			 long initialTime = System.currentTimeMillis();
//			 System.out.println(initialTime);
			runITDroid(args);
//			 long finalTime = System.currentTimeMillis();
//			 System.out.println(finalTime);
//			 System.out.println(finalTime-initialTime);

		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			// print report
			if (outputPath != null) {
				try (FileWriter file = new FileWriter(outputPath + File.separator + "report.json")) {

					file.write(report.toJSONString());
					file.flush();
					System.out.println(
							"Internationalization analysis is finished, please check the report.json file for the results");

					createReport(args);

				} catch (IOException e) {
					e.printStackTrace();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}

	@SuppressWarnings("unchecked")
	public static void runITDroid(String[] args) throws RipException, Exception {
		// Usage Error
		if (args.length != 7) {
			System.out.println("******* ERROR: INCORRECT USAGE *******");
			System.out.println("Argument List:");
			System.out.println("1. APK path");
			System.out.println("2. Package Name");
			System.out.println("3. Binaries path");
			System.out.println("4. Directory containing the settings.properties file");
			System.out.println("5. Amount of untranslatable strings");
			System.out.println("6. Path where test output will be stored");
			System.out.println("7. Name of the emulator in which the app is going to be executed");

			return;
		}

		// Getting arguments
		String apkName;
		String apkPath = args[0];
		String appName = args[1];
		String extraPath = args[2];
		String langsDir = args[3];
		int alpha = Integer.parseInt(args[4]);
		outputPath = args[5];
		String emulatorName = args[6];

		// Fix params based in OS
		String os = System.getProperty("os.name").toLowerCase();
		if (os.indexOf("win") >= 0) {
			extraPath = extraPath.replaceAll("/", File.separator) + File.separator;
			apkPath = apkPath.replaceAll("/", File.separator);
			apkName = apkPath.substring(apkPath.lastIndexOf("\\"));
		} else {
			apkName = apkPath.substring(apkPath.lastIndexOf("/"));
		}
		Helper.getInstance();
		Helper.setPackageName(appName);

		//Sign the original APK in case it is not
		Process pss = Runtime.getRuntime().exec(new String[]{"java","-jar",Paths.get(extraPath,"uber-apk-signer.jar").toAbsolutePath().toString(),"-a",Paths.get(".\\",appName+".apk").toAbsolutePath().toString(),"-o",Paths.get(File.separator).toAbsolutePath().toString()});
		pss.waitFor();
		
		LanguageBundle lngBundle = new LanguageBundle(langsDir);
		String deftLanguage = lngBundle.getBundle().getObject("defaultLng").toString();

		// Explore app using default language
		File signedAPK = new File(Paths.get(".\\", appName+"-aligned-debugSigned.apk").toAbsolutePath().toString());
		String pathAPK = Paths.get(appName+".apk").toAbsolutePath().toString(); 
		if(signedAPK.exists()) {
			pathAPK = Paths.get(appName+"-aligned-debugSigned.apk").toAbsolutePath().toString();
		}
		String resultFolderPath = RIPHelper.runRIPI18N(deftLanguage, outputPath, true, extraPath, pathAPK, appName,
				"English");
		LayoutGraph defltGraph = new LayoutGraph(deftLanguage, resultFolderPath);
		JSONObject dfltLangJSON = new JSONObject();
		dfltLangJSON.put("lang", "English");
		dfltLangJSON.put("dflt", true);
		dfltLangJSON.put("amStates", defltGraph.getStates().size());
		dfltLangJSON.put("amTrans", defltGraph.getTransitions().size());
		//Check if the layout mirroring of RTL languages can be done
		Boolean hasValidTarget = ASTHelper.checkTargetSDK(appName);

		//call RIP R&R for the unmodified app in arabic, to later compare it with the modified version.
		//Only do it if the target SDK allows support for RTL languages
		if(hasValidTarget){
			try{
				RIPHelper.runRIPRRi18n("ar-original", outputPath, false, extraPath, pathAPK,
				resultFolderPath, appName, "Arabic");
					System.out.println("The app has been inspected in the original Arabic version");		
				}catch (RipException e) {
					System.out.println(
							"This translated version of the app is not suitable for reproducing the steps recorded over default app version. It is possible that your automated tests might not work over this language version");
			}
		}
		//Write the JSON file with information about the repairment of RTL mirroring and its status
		JSONObject infoRTL = new JSONObject();
		infoRTL.put("states", defltGraph.getStates().size());
		String arabOriFolder = "ar-original";
		String arabFolder = "ar";
		JSONObject langsRTL = new JSONObject();
		langsRTL.put("arabicOriginal", arabOriFolder);
		langsRTL.put("arabic", arabFolder);
		langsRTL.put("default", deftLanguage);
		infoRTL.put("languages", langsRTL);
		infoRTL.put("repairedRTL", hasValidTarget);
		FileWriter file = new FileWriter(outputPath + File.separator + "infoRTL.json");
		file.write(infoRTL.toJSONString());
		file.flush();
		file.close();

		// Decode the APK
		String decodedFolderPath = APKToolWrapper.openAPK(apkPath, extraPath);
		JSONParser a = new JSONParser();
		report = new JSONObject();
		report.put("apkName", apkName);
		report.put("appName", appName);
		report.put("alpha", alpha);
		report.put("outputFolder", outputPath);
		report.put("emulatorName", emulatorName);
		int possibleIPFS = ASTHelper.findHardCodedStrings(decodedFolderPath, extraPath, appName, outputPath);
		report.put("hardcoded", possibleIPFS);

		//If targetSDK >=17 then the repairment is made.
		if(hasValidTarget){
			ASTHelper.supportRTL(appName, decodedFolderPath);
		}

		// Read selected operators
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

		// Translate the original file into missing languages
		System.out.println("We are going to translate your strings...");
		for (int i = 0; i < notTrnsltdFiles.size(); i++) {
			String defLang = lngBundle.getBundle().getObject("defaultLng").toString();
			String tLang = pathsMap.get(notTrnsltdFiles.get(i));
			Translator t = new Translator(stringFiles[0], defLang, tLang);
			t.translate(new IBMTranslator(langsDir));
		}
		//After the translation is done, put back the \n chars that were escaped before sending it to translate
		ASTHelper.setOriginalStrings(decodedFolderPath);

		// Builds the APK with all the languages
		String newApkPath = APKToolWrapper.buildAPK(extraPath, appName, outputPath);
		if (newApkPath.equals("")) {
			return;
		}

		//Finalize the process of exploring the app in the default language
		JSONObject lngsResults = new JSONObject();
		report.put("dfltLang", deftLanguage);	
		lngsResults.put(deftLanguage, dfltLangJSON);
		graphs.put(deftLanguage, defltGraph);

		BufferedWriter bw = new BufferedWriter(new FileWriter(outputPath + File.separator + "ipfs.csv", true));
		bw.write("language;state;nodePos;ipfScore");
		bw.newLine();
		bw.close();

		System.out.println("Inspecting translated versions");
		// Generate the graph for all the translated languages
		for (int i = 0; i < translatedFiles.size(); i++) {

			String lang = pathsMap.get(translatedFiles.get(i));
			System.out.println("Processing " + lang + " app version");
			JSONObject dfltLangJSONTrans = new JSONObject();

			try {
				// call RIP R&R
				String resultFolderPathh = RIPHelper.runRIPRRi18n(lang, outputPath, true, extraPath, newApkPath,
						resultFolderPath, appName, lngBundle.getBundle().getString(lang));
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

			} catch (RipException e) {
				dfltLangJSONTrans.put("error", e.getMessage());
				System.out.println(
						"This translated version of the app is not suitable for reproducing the steps recorded over default app version. It is possible that your automated tests might not work over this language version");
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
				// call RIP R&R
				String resultFolderPathh = RIPHelper.runRIPRRi18n(lang, outputPath, false, extraPath, newApkPath,
						resultFolderPath, appName, lngBundle.getBundle().getString(lang));
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
			} catch (RipException e) {
				dfltLangJSONTrans.put("error", e.getMessage());
				System.out.println(
						"This translated version of the app is not suitable for reproducing the steps recorded over default app version. It is possible that your automated tests might not work over this language version");
			}
			lngsResults.put(lang, dfltLangJSONTrans);
		}

		report.put("langsReport", lngsResults);
	}

	public static void createReport(String[] args) throws Exception {
		Dotenv dotenv = Dotenv.load();
		int resultValue = -1;
		ProcessBuilder createDirectoryPB = new ProcessBuilder();
		ProcessBuilder copyResultsPB = new ProcessBuilder();
		ProcessBuilder installPB = new ProcessBuilder();
		ProcessBuilder runPB = new ProcessBuilder();
		Process createDirectoryProcess, copyResultsProcess, installProcess, runProcess;
		File reportFile = new File(dotenv.get("REPORT_PATH")+File.separator);
		File reportEnvFile = new File(dotenv.get("REPORT_PATH")+File.separator+".env");

		// Format output folder path
		outputPath = args[5];
		String outputFolder = outputPath.replace(".", "");
		outputFolder = outputFolder.startsWith(File.separator) ? outputFolder.substring(1) : outputFolder;
		String reportOutputPath = dotenv.get("REPORT_PATH")+File.separator+"public"+File.separator + outputFolder;

		// Create the directory on the report project
		System.out.println("Creating report results directory...");
		createDirectoryPB.command("mkdir","-p", reportOutputPath);
		createDirectoryProcess = createDirectoryPB.start();
		resultValue = createDirectoryProcess.waitFor();
		// Check if it could create the directory
		if (resultValue != 0) {
			System.out.println(GREEN + "Report results directory created successfully " + CHECK_ICON + RESET);
			System.out.println("Deleting previous version...");
			ProcessBuilder removeDirectoryPB = new ProcessBuilder();
			removeDirectoryPB.command("rm", "-r", reportOutputPath);
			Process removeDirectoryProcess = removeDirectoryPB.start();
			resultValue = removeDirectoryProcess.waitFor();
			if (resultValue != 0) {
				printErrors(removeDirectoryProcess);
				return;
			}
			System.out.println(GREEN + "Previous version deleted successfully " + CHECK_ICON + RESET);
			// Try creating directory again
			System.out.println("Creating new version...");
			createDirectoryProcess = createDirectoryPB.start();
			resultValue = createDirectoryProcess.waitFor();
			if (resultValue != 0) {
				printErrors(createDirectoryProcess);
				return;
			}
			System.out.println(GREEN + "New version created successfully " + CHECK_ICON + RESET);
		}

		// Copying results to report project
		System.out.println("Copying results to directory...");
		copyResultsPB.command("cp", "-R", outputFolder, reportOutputPath);
		copyResultsProcess = copyResultsPB.start();
		resultValue = copyResultsProcess.waitFor();
		if (resultValue != 0) {
			printErrors(copyResultsProcess);
			return;
		}
		System.out.println(GREEN + "Results copied to directory successfully " + CHECK_ICON + RESET);

		// Set REACT_APP_OUTPUT_FOLDER environment variable
		System.out.println("Setting environment variables...");
		FileWriter fw = new FileWriter(reportEnvFile);
		BufferedWriter bw = new BufferedWriter(fw);
		bw.write("REACT_APP_OUTPUT_FOLDER=" + outputFolder.replace(File.separator, ""));
		bw.flush();
		bw.close();
		System.out.println(GREEN + "Environment variables set successfully " + CHECK_ICON + RESET);

		// Run npm install
		System.out.println("Executing " + CYAN_BOLD + "npm install" + RESET);
		installPB.command("npm", "install").directory(reportFile);
		installProcess = installPB.start();
		printResults(installProcess);
		resultValue = installProcess.waitFor();
		if (resultValue == 0) {
			System.out.println(CYAN_BOLD + "npm install" + GREEN + " executed successfully " + CHECK_ICON + RESET);

			// Run project
			System.out.println(PURPLE_BOLD + "Running report project..." + RESET);
			runPB.command("npm", "start").directory(reportFile);
			runProcess = runPB.start();
			printResults(runProcess);
			resultValue = runProcess.waitFor();
			if (resultValue != 0) {
				printErrors(runProcess);
				return;
			}
		} else {
			printErrors(installProcess);
			return;
		}
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

	public static void printResults(Process process) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
		String line = "";
		while ((line = reader.readLine()) != null) {
			System.out.println(line);
		}
	}

	public static void printErrors(Process process) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
		String line = "";
		while ((line = reader.readLine()) != null) {
			System.out.println(RED_BOLD + line + RESET);
		}
	}
}

package uniandes.tsdl.itdroid;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;

import uniandes.tsdl.itdroid.IBM.IBMTranslator;
import uniandes.tsdl.itdroid.helper.APKToolWrapper;
import uniandes.tsdl.itdroid.helper.EmulatorHelper;
import uniandes.tsdl.itdroid.helper.Helper;
import uniandes.tsdl.itdroid.helper.LanguageBundle;
import uniandes.tsdl.itdroid.helper.RIPHelper;
import uniandes.tsdl.itdroid.helper.XMLComparator;
import uniandes.tsdl.itdroid.model.LanguageResult;
import uniandes.tsdl.itdroid.translator.Translator;

public class ITDroid {

	static HashMap<String, String> pathsMap = new HashMap<>();
	
	static HashMap<String, LanguageResult> graphs = new HashMap<String, LanguageResult>();
	

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
		}
	}

	public static void runITDroid(String[] args) throws Exception {
		//Usage Error
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

		//Getting arguments
		String apkName;
		String apkPath = args[0];
		String appName = args[1];
		String extraPath = args[2];
		String langsDir = args[3];
		int alpha = Integer.parseInt(args[4]);
		String outputPath = args[5];
		String emulatorName = args[6];

		//Launch the emulator

		String androidHome = System.getenv("ANDROID_HOME");
//		String androidHome = System.getenv("ANDROID_SDK");
		boolean successfullLaunch = EmulatorHelper.launchEmulator(emulatorName, androidHome);
		if (!successfullLaunch){
			return;
		}

		// Fix params based in OS
		String os = System.getProperty("os.name").toLowerCase();
		if (os.indexOf("win") >= 0) {
			extraPath = extraPath.replaceAll("/", File.separator)+File.separator;
			apkPath = apkPath.replaceAll("/", File.separator);
			apkName = apkPath.substring(apkPath.lastIndexOf("\\"));
		} else {
			apkName = apkPath.substring(apkPath.lastIndexOf("/"));
		}
		Helper.getInstance();
		Helper.setPackageName(appName);
		// Decode the APK
		APKToolWrapper.openAPK(apkPath, extraPath);

		//Read selected operators
		LanguageBundle lngBundle = new LanguageBundle(langsDir);
		System.out.println(lngBundle.printSelectedLanguages());

		String[] lngs = lngBundle.getSelectedLanguagesAsArray();

		String[] stringFiles = buildStringPaths(lngs);

		XMLComparator xmlc = new XMLComparator(stringFiles, alpha);

		//Notify user about translated and not-translated languages

		ArrayList<String> translatedFiles = xmlc.getUsefull();
		System.out.println("Your application is translated to the following languages:");
		for (int i = 0; i < translatedFiles.size(); i++) {
			System.out.println(lngBundle.getBundle().getObject(pathsMap.get(translatedFiles.get(i))));
		}
		System.out.println("");

		ArrayList<String> notTrnsltdFiles = xmlc.getUseLess();
		System.out.println("Your application is not translated to the following languages:");
		for (int i = 0; i < notTrnsltdFiles.size(); i++) {
			System.out.println(lngBundle.getBundle().getObject(pathsMap.get(notTrnsltdFiles.get(i))));
		}

		// Translate the original file into missing languages
		System.out.println("We are going to translate your strings...");
//		for (int i = 0; i < notTrnsltdFiles.size(); i++) {
//			//			System.out.println(pathsMap.get(notTrnsltdFiles.get(i)));
//			//			System.out.println(lngBundle.getBundle().getObject("defaultLng"));
//			String defLang = lngBundle.getBundle().getObject("defaultLng").toString();
//			String tLang = pathsMap.get(notTrnsltdFiles.get(i));
//			Translator t = new Translator(stringFiles[0], defLang, tLang);
//			t.translate(new IBMTranslator(langsDir));
//			//			System.out.println(lngBundle.getBundle().getObject(pathsMap.get(notTrnsltdFiles.get(i))));
//		}

		// builds the APK with all the languages
		String newApkPath = APKToolWrapper.buildAPK(extraPath, appName, outputPath);

		if(newApkPath.equals("")) {
			return ;
		}
		
		String deftLanguage = lngBundle.getBundle().getObject("defaultLng").toString();
		EmulatorHelper.changeLanguage(deftLanguage, extraPath);
		String resultFolderPath = RIPHelper.runRIPI18N(deftLanguage, outputPath, true, extraPath, newApkPath);
		LanguageResult defltGraph = new LanguageResult(deftLanguage, resultFolderPath);
		graphs.put(deftLanguage, defltGraph);
		

		System.out.println("Inspecting translated versions");
		// Generate the graph for all the translated languages
		for (int i = 0; i < translatedFiles.size(); i++) {

			String lang = pathsMap.get(translatedFiles.get(i));
			System.out.println("Processing "+ lang +" app version");
			EmulatorHelper.changeLanguage(lang, extraPath);
			//call RIP R&R
			String resultFolderPathh = RIPHelper.runRIPRR(lang, outputPath, true, extraPath, newApkPath, resultFolderPath);
			
			//Builds the graph for given language
			LanguageResult langGraph = new LanguageResult(lang, resultFolderPathh);
			graphs.put(lang, langGraph);

			//Wipes package data
			EmulatorHelper.wipePackageData(appName);

		}

		System.out.println("Inspecting non translated versions");
		// Generate the graph for all the not translated languages
		for (int i = 0; i < notTrnsltdFiles.size(); i++) {

			String lang = pathsMap.get(notTrnsltdFiles.get(i));
			System.out.println("Processing "+ lang +" app version");
			EmulatorHelper.changeLanguage(lang, extraPath);
			//call RIP R&R
			String resultFolderPathh = RIPHelper.runRIPRR(lang, outputPath, false, extraPath, newApkPath, resultFolderPath);

			//Builds the graph for given language
			LanguageResult langGraph = new LanguageResult(lang, resultFolderPathh);
			graphs.put(lang, langGraph);

		}
		
		

	}
	
	private static String[] buildStringPaths(String[] lngs) throws UnsupportedEncodingException {
		String decodedPath = Helper.getInstance().getCurrentDirectory();

		String[] paths = new String[lngs.length+1];

		Path base = Paths.get(decodedPath,"temp","res");
		paths[0] = base.resolve("values").resolve("strings.xml").toAbsolutePath().toString();
		for (int i = 1; i < paths.length; i++) {
			paths[i]=base.resolve("values-"+lngs[i-1]).resolve("strings.xml").toAbsolutePath().toString();
			pathsMap.put(base.resolve("values-"+lngs[i-1]).resolve("strings.xml").toAbsolutePath().toString(), lngs[i-1]);
		}

		return paths;
	}

}

package uniandes.tsdl.itdroid;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;

import uniandes.tsdl.itdroid.helper.APKToolWrapper;
import uniandes.tsdl.itdroid.helper.Helper;
import uniandes.tsdl.itdroid.helper.LanguageBundle;
import uniandes.tsdl.itdroid.helper.XMLComparator;

public class ITDroid {
	
	static HashMap<String, String> pathsMap = new HashMap<>();

	public static void main(String[] args) {
		try {
			// long initialTime = System.currentTimeMillis();
			// System.out.println(initialTime);
			runITDroid(args);
			// long finalTime = System.currentTimeMillis();
			// System.out.println(finalTime);
			// System.out.println(finalTime-initialTime);

		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public static void runITDroid(String[] args) throws Exception {
		//Usage Error
		if (args.length != 6) {
			System.out.println("******* ERROR: INCORRECT USAGE *******");
			System.out.println("Argument List:");
			System.out.println("1. APK path");
			System.out.println("2. Package Name");
			System.out.println("3. Binaries path");
			System.out.println("4. Directory containing the settings.properties file");
			System.out.println("5. Amount of untranslatable strings");
			System.out.println("6. Path where test output will be stored");

			return;
		}
		
		//Getting arguments
		String apkName;
		String apkPath = args[0];
		String appName = args[1];
		String extraPath = args[2];
		String langsDir = args[3];
		int alpha = Integer.parseInt(args[4]);
		String output = args[5];
		

		
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
		System.out.println("");

		

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

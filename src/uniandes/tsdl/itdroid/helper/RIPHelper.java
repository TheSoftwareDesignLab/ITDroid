package uniandes.tsdl.itdroid.helper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;

public class RIPHelper {

	public static void runRIPI18N(String language, String outputFolder, boolean translated, String extraPath, String apkLocation) throws IOException, InterruptedException{
		String decodedPath = Helper.getInstance().getCurrentDirectory();
		// Creates folder for decoded app
		File tempFolder = new File(Paths.get(decodedPath,outputFolder,(translated?"trnsResults":"noTrnsResults"),language).toAbsolutePath().toString());
		if(tempFolder.exists()) {
			tempFolder.delete();
		}
		tempFolder.mkdirs();
		ProcessBuilder pB = new ProcessBuilder(new String[]{"java","-jar",Paths.get(decodedPath,extraPath,"RIPi18n.jar").toAbsolutePath().toString(),apkLocation,tempFolder.getCanonicalPath(),"false"});
		Process ps = pB.start();
		BufferedReader reader = new BufferedReader(new InputStreamReader(ps.getInputStream()));
		String line;
		while ((line = reader.readLine())!=null) {
			System.out.println(line);
		}
		System.out.println("Going through your app");
		ps.waitFor();
		Thread.sleep(5000);
		System.out.println("The app has been inspected");
	}
	
	public static void runRIPRR(String language, String outputFolder, boolean translated, String extraPath, String apkLocation) throws IOException, InterruptedException{
		String decodedPath = Helper.getInstance().getCurrentDirectory();
		// Creates folder for decoded app
//		System.out.println(decodedPath);
		File tempFolder = new File(decodedPath+File.separator+outputFolder+(translated?"trnsResults":"noTrnsResults")+File.separator+language);
		if(tempFolder.exists()) {
			tempFolder.delete();
		}
		tempFolder.mkdirs();
		Process ps = Runtime.getRuntime().exec(new String[]{"java","-jar",Paths.get(decodedPath,extraPath,"RIPRR.jar").toAbsolutePath().toString(),apkLocation,tempFolder.getAbsolutePath()});
		System.out.println("Going through your app");
		ps.waitFor();
		System.out.println("The app has been inspected");
	}
}

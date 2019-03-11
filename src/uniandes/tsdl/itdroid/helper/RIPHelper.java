package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

public class RIPHelper {

	public static void runRIP(String language, String outputFolder, boolean translated, String extraPath, String apkLocation) throws IOException, InterruptedException{
		String decodedPath = Helper.getInstance().getCurrentDirectory();
		// Creates folder for decoded app
//		System.out.println(decodedPath);
		File tempFolder = new File(decodedPath+File.separator+outputFolder+(translated?"trnsResults":"noTrnsResults")+File.pathSeparator+language);
		if(tempFolder.exists()) {
			tempFolder.delete();
		}
		tempFolder.mkdirs();
		Process ps = Runtime.getRuntime().exec(new String[]{"java","-jar",Paths.get(decodedPath,extraPath,"RIP.jar").toAbsolutePath().toString(),apkLocation,tempFolder.getAbsolutePath()});
		System.out.println("Going through your app");
		ps.waitFor();
		System.out.println("The app has been inspected");
	}
}

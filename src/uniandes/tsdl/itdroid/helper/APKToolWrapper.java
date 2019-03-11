package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class APKToolWrapper {

	public static void openAPK(String path, String extraPath) throws IOException, InterruptedException{
		String decodedPath = Helper.getInstance().getCurrentDirectory();
		// Creates folder for decoded app
//		System.out.println(decodedPath);
		File tempFolder = new File(decodedPath+File.separator+"temp");
		if(tempFolder.exists()) {
			tempFolder.delete();
		}
		tempFolder.mkdirs();
		Process ps = Runtime.getRuntime().exec(new String[]{"java","-jar",Paths.get(decodedPath,extraPath,"apktool.jar").toAbsolutePath().toString(),"d",Paths.get(decodedPath,path).toAbsolutePath().toString(),"-o",Paths.get(decodedPath,"temp").toAbsolutePath().toString(),"-f"});
		System.out.println("Processing your APK...");
		ps.waitFor();
		System.out.println("Wow... that was an amazing APK to proccess!!! :D");
		// InputStream es = ps.getErrorStream();
		// byte e[] = new byte[es.available()];
		// es.read(e,0,e.length);
		// System.out.println("ERROR: "+ new String(e));
		// InputStream is = ps.getInputStream();
		// byte b[] = new byte[is.available()];
		// is.read(b,0,b.length);
		// System.out.println("INFO: "+new String(b));
		// System.out.println(decodedPath);
	}

	public static String buildAPK(String extraPath, String appName, String outputPath) throws IOException, InterruptedException{
		String decodedPath = Helper.getInstance().getCurrentDirectory();
		Process ps = Runtime.getRuntime().exec(new String[]{"java","-jar",Paths.get(decodedPath,extraPath,"apktool.jar").toAbsolutePath().toString(),"b",Paths.get(decodedPath,"temp").toAbsolutePath().toString(),"-o",Paths.get(decodedPath,outputPath,appName+".apk").toAbsolutePath().toString(),"-f"});
		System.out.println("Building mutant");
		ps.waitFor();
		Process pss = Runtime.getRuntime().exec(new String[]{"java","-jar",Paths.get(decodedPath,extraPath,"uber-apk-signer.jar").toAbsolutePath().toString(),"-a",Paths.get(decodedPath,outputPath,appName+".apk").toAbsolutePath().toString(),"-o",Paths.get(decodedPath,outputPath).toAbsolutePath().toString()});
		System.out.println("Signing mutant");
		pss.waitFor();
		if(Files.exists(Paths.get(decodedPath,outputPath,appName+"-aligned-debugSigned.apk").toAbsolutePath())) {
			System.out.println("SUCCESS: The mutated APK has been generated.");
			return Paths.get(decodedPath,outputPath,appName+"-aligned-debugSigned.apk").toAbsolutePath().toString();
		} else {
			System.out.println("ERROR: The mutated APK has not been generated.");
			return "";
		}
		//				InputStream es = ps.getErrorStream();
		//				byte e[] = new byte[es.available()];
		//				es.read(e,0,e.length);
		//				System.out.println("ERROR: "+ new String(e));
		//				InputStream is = ps.getInputStream();
		//				byte b[] = new byte[is.available()];
		//				is.read(b,0,b.length);
		//				System.out.println("INFO: "+new String(b));
	}
}
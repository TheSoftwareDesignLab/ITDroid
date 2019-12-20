package uniandes.tsdl.itdroid.helper;

import java.io.*;
import java.nio.file.Paths;

import org.apache.commons.io.IOUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

public class RIPHelper {

	public static String runRIPI18N(String language, String outputFolder, boolean translated, String extraPath, String apkLocation, String expresiveLanguage) throws IOException, InterruptedException{
		String decodedPath = Helper.getInstance().getCurrentDirectory();
		// Creates folder for decoded app
		File tempFolder = new File(Paths.get(decodedPath,outputFolder,(translated?"trnsResults":"noTrnsResults"),language).toAbsolutePath().toString());
		if(tempFolder.exists()) {
			tempFolder.delete();
		}
		tempFolder.mkdirs();
		String ripconfig = buildRIPConfig(apkLocation, outputFolder, tempFolder.getAbsolutePath(), "",language, extraPath,expresiveLanguage);
		ProcessBuilder pB = new ProcessBuilder(new String[]{"java","-jar",Paths.get(decodedPath,extraPath,"RIPi18n.jar").toAbsolutePath().toString(), ripconfig});
		Process ps = pB.start();
		System.out.print("Going through your app");

		BufferedReader reader = new BufferedReader(new InputStreamReader(ps.getInputStream()));
		String line;
		while ((line = reader.readLine())!=null) {
			//			System.out.println(line);
			System.out.print(".");
		}

		ps.waitFor();
		Thread.sleep(5000);
		System.out.println("The app has been inspected");
		return tempFolder.getCanonicalPath();
	}

	public static String runRIPRR(String language, String outputFolder, boolean translated, String extraPath, String apkLocation, String resultPath,String expresiveLanguage) throws IOException, InterruptedException{
		String decodedPath = Helper.getInstance().getCurrentDirectory();
		// Creates folder for decoded app
		//		System.out.println(decodedPath);
		File tempFolder = new File(decodedPath+File.separator+outputFolder+File.separator+(translated?"trnsResults":"noTrnsResults")+File.separator+language);
		if(tempFolder.exists()) {
			tempFolder.delete();
		}
		tempFolder.mkdirs();
		String ripconfig = buildRIPConfig(apkLocation, outputFolder, tempFolder.getAbsolutePath(), resultPath+File.separator+"result.json",language,extraPath,expresiveLanguage);
		ProcessBuilder pB = new ProcessBuilder(
				new String[]{
						"java",
						"-jar",
						Paths.get(decodedPath,extraPath,"RIPRR.jar").toAbsolutePath().toString(),
						ripconfig
				}
				);
		Process ps = pB.start();
		System.out.print("Going through your app");
		BufferedReader reader = new BufferedReader(new InputStreamReader(ps.getInputStream()));
		String line;
		while ((line = reader.readLine())!=null) {
			//						System.out.println(line);
			System.out.print(".");
		}
		ps.waitFor();
		System.out.println("The app has been inspected");
		return tempFolder.getCanonicalPath();
	}
	
	public static String runRIPRRi18n(String language, String outputFolder, boolean translated, String extraPath, String apkLocation, String resultPath) throws IOException, InterruptedException, RipException{
		String decodedPath = Helper.getInstance().getCurrentDirectory();
		// Creates folder for decoded app
		//		System.out.println(decodedPath);
		File tempFolder = new File(decodedPath+File.separator+outputFolder+File.separator+(translated?"trnsResults":"noTrnsResults")+File.separator+language);
		if(tempFolder.exists()) {
			tempFolder.delete();
		}
		tempFolder.mkdirs();
		String ripconfig = buildRIPConfig(apkLocation, outputFolder, tempFolder.getAbsolutePath(), resultPath+File.separator+"result.json",language,extraPath,expresiveLanguage);
		ProcessBuilder pB = new ProcessBuilder(
				new String[]{
						"java",
						"-jar",
						Paths.get(decodedPath,extraPath,"RIPRRi18n.jar").toAbsolutePath().toString(),
						ripconfig
				}
				);
		Process ps = pB.start();
		System.out.print("Going through your app");
		BufferedReader reader = new BufferedReader(new InputStreamReader(ps.getInputStream()));
		BufferedReader errorReader = new BufferedReader(new InputStreamReader(ps.getErrorStream()));
		String line, errorLine = "";
		while ((line = reader.readLine())!=null || (errorLine = errorReader.readLine())!= null) {
			//						System.out.println(line);
			System.out.print(".");
			if(errorLine != null && !errorLine.contains("OLD TRANSITIONS EMPTY")) {
				throw new RipException("New replay failure");
			}
		}

		ps.waitFor();
		System.out.println("The app has been inspected");
		return tempFolder.getCanonicalPath();
	}

	private static String buildRIPConfig(String newApkPath, String ripConfig, String outputPath, String rrScript, String targetLanguage, String extraPath, String expresiveLanguage) {

		try {
			JSONObject ripconfig = new JSONObject();
			ripconfig.put("apkPath", newApkPath);
			ripconfig.put("outputFolder", outputPath);
			ripconfig.put("isHybrid", false);
			ripconfig.put("executionMode", "events");
			ripconfig.put("translateTo", targetLanguage);
			ripconfig.put("expresiveLanguage",expresiveLanguage);
			ripconfig.put("extraPath",extraPath);
			if(!rrScript.equals("")) {
				ripconfig.put("scriptPath", rrScript);
			}
			JSONParser parser = new JSONParser();
			JSONObject execParams = (JSONObject) parser.parse("{\"events\":90}");
			ripconfig.put("executionParams", execParams);
			
			FileWriter file = new FileWriter(ripConfig+File.separator+"rip_config.json");
			file.write(ripconfig.toJSONString());
			file.flush();

			return ripConfig+File.separator+"rip_config.json";
		} catch (ParseException e1) {
			e1.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return "";
	}
}

package uniandes.tsdl.itdroid.helper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

public class EmulatorHelper {

	public static boolean changeLanguage(String language, String expresiveLanguage, String extraPath) throws IOException, InterruptedException{
		// Change emulator language
		ProcessBuilder pB = new ProcessBuilder(new String[]{"adb","shell","setprop persist.sys.locale "+language});
		Process ps = pB.start();
		System.out.println("Emulator language changed to "+expresiveLanguage);
		ps.waitFor();
		// Restart emulator for language change to be taken into account
		pB.command(new String[]{"adb","shell","setprop ctl.restart zygote"});
		ps = pB.start();
		System.out.println("Emulator is being restarted");
		// Running command that waits emulator for idle state
		//		System.out.println(Paths.get(Helper.getInstance().getCurrentDirectory(),extraPath,"./whileCommand").toAbsolutePath().toString());
		ps.waitFor();
		Thread.sleep(5000);
		isIdle();
//		Thread.sleep(15000);
		return true;
	}

	public static boolean launchEmulator(String emulatorName, String pAndroidHome) throws IOException, InterruptedException {
		//Get system properties
		String avdRoute = pAndroidHome+File.separator+"emulator";
		// Set the avd directory as the working directory
		ProcessBuilder pb = new ProcessBuilder();
		pb.directory(new File(avdRoute));
		//Verify if emulator exists
		if(Helper.isWindows()) {
			pb.command("cmd", "/c" ,".\\emulator.exe -list-avds");
		} else {
			pb.command("./emulator","-list-avds");
		}
//        String os = System.getProperty("os.name").toLowerCase();
//		pb.command( ((os.indexOf("win") >= 0) ? "" : "./" ) + "emulator", "-list-avds");
		Process process = pb.start();
		BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
		boolean emulatorExists = false;
		String line;
		while((line = reader.readLine()) != null && !emulatorExists) {
			if(line.equals(emulatorName)) {
				emulatorExists = true;
			}
		}
		if(emulatorExists) {
			boolean valid = isGoogleApis(pAndroidHome, emulatorName);
			//Verify if the emulator can be executed in root mode
			if(valid) {
				//Launch emulator
				if(Helper.isWindows()) {
					pb.command("cmd", "/c" ,".\\emulator -avd "+emulatorName);//+" -no-audio -no-window");
				} else {
					pb.command("./emulator","-avd",emulatorName);//,"-no-audio","-no-window");
				}
//				pb.command( ((os.indexOf("win") >= 0) ? "" : "./" ) +  "emulator", "-avd", emulatorName);
				pb.start().waitFor(1, TimeUnit.SECONDS);
				isIdle();
				//Execute adb root command
				ProcessBuilder pB1 = new ProcessBuilder();
				pB1.command("adb", "root");
				Process root = pB1.start();
				root.waitFor();
				return true;
			}
			else {
				System.out.println("The emulator provided cannot be run in root mode");
				System.out.println("Try installing a emulator with Google APIs target");
				return false;
			}
		}
		else {
			System.out.println("The name of the emulator provided could not be found");
			return false;
		}
	}

	public static boolean isGoogleApis(String pAndroidHome, String emulatorName) throws IOException {
		String avdManagerRoute = pAndroidHome + "/tools/bin";
		ProcessBuilder pb = new ProcessBuilder();
		pb.directory(new File(avdManagerRoute));
		if(Helper.isWindows()) {
			pb.command("cmd", "/c" ,"avdmanager.bat list avd");
		} else {
			pb.command( "./avdmanager","list","avd");
		}
		Process p = pb.start();
		BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
		String line;
		String[] lineSplitted;
		String tag;
		String target;
		while((line = reader.readLine()) != null) {
			lineSplitted = line.split(": ");
			tag = lineSplitted[0];
			tag = tag.replaceAll(" ","");
			if(tag.equals("Name")) {
				if(emulatorName.equals(lineSplitted[1])) {
					while (!(line.contains("Target"))) {
						line = reader.readLine();
					}
					target = line.split(": ")[1];
					if(target.contains("Google APIs")) {
						return true;
					}
					else {
						return false;
					}
				}
			}
		}
		return false;
	}

	public static boolean isIdle() throws IOException, InterruptedException {
		ProcessBuilder pBB = new ProcessBuilder(new String[]{"adb","shell","getprop init.svc.bootanim"});
		Process pss;
		boolean termino = false;
		System.out.println("waiting for emulator's idle state");
		while (!termino) {
			pss = pBB.start();
			BufferedReader reader = new BufferedReader(new InputStreamReader(pss.getInputStream()));
			String line;
			String resp = "";
			while ((line = reader.readLine())!=null) {
				resp += line;
			}
			pss.waitFor();
			if(resp.contains("stopped")) {
				termino = true;
				Thread.sleep(2000);
				System.out.println("Emulator now is in idle state");
			} else {
				Thread.sleep(2000);
			}
		}
		return true;
	}

	public static void wipePackageData(String packageName) throws IOException, InterruptedException {
		// Change emulator language
		ProcessBuilder pB = new ProcessBuilder(new String[]{"adb","shell","pm clear " + packageName});
		Process ps = pB.start();
		System.out.println("Wiping app data");
		ps.waitFor();
	}
}

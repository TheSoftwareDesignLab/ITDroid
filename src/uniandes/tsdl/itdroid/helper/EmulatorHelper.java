package uniandes.tsdl.itdroid.helper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;

public class EmulatorHelper {
	
	public static boolean changeLanguage(String language, String extraPath) throws IOException, InterruptedException{
		// Change emulator language
		ProcessBuilder pB = new ProcessBuilder(new String[]{"adb","shell","setprop persist.sys.locale "+language});
		Process ps = pB.start();
		System.out.println("Emulator language changed to "+language);
		ps.waitFor();
		// Restart emulator for language change to be taken into account
		pB.command(new String[]{"adb","shell","setprop ctl.restart zygote"});
		ps = pB.start();
		System.out.println("Emulator is being restarted");
		// Running command that waits emulator for idle state
//		System.out.println(Paths.get(Helper.getInstance().getCurrentDirectory(),extraPath,"./whileCommand").toAbsolutePath().toString());
//		ProcessBuilder pBB = new ProcessBuilder(new String[]{Paths.get(Helper.getInstance().getCurrentDirectory(),extraPath,"./whileCommand").toAbsolutePath().toString()});
//		Process pss = pBB.start();
//		BufferedReader reader = new BufferedReader(new InputStreamReader(pss.getInputStream()));
//		String line;
//		while ((line = reader.readLine())!=null) {
//			System.out.println(line);
//		}
//		System.out.println("waiting for idle");
//		pss.waitFor();
		ps.waitFor();
		Thread.sleep(15000);
		return true;
	}

}

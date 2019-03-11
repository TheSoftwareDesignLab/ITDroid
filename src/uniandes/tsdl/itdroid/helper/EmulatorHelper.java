package uniandes.tsdl.itdroid.helper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class EmulatorHelper {
	
	public static boolean changeLanguage(String language) throws IOException, InterruptedException{
		String decodedPath = Helper.getInstance().getCurrentDirectory();
		Process ps = Runtime.getRuntime().exec(new String[]{"adb shell \"setprop persist.sys.locale "+language+"; setprop ctl.restart zygote\""});
		System.out.println("Updating Language on emulator");
		ps.waitFor();
		Thread.sleep(6000);
		return true;
	}

}

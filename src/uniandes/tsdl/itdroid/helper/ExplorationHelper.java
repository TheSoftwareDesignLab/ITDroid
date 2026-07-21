package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

import org.apache.commons.io.FileUtils;

import uniandes.tsdl.itdroid.explorer.AdbExplorer;

/** Runs the modern adb explorer per app version and returns its result folder. */
public class ExplorationHelper {

	/** UI states captured per app version. */
	private static final int MAX_STATES = 5;

	/** Installs, sets the per-app locale, explores via adb and writes {@code result.json}. */
	private static void explore(String appName, String apkLocation, String outputFolder, String language) throws ExplorationException {
		try {
			String serial = AdbExplorer.resolveSerial();
			System.out.println("Exploring '" + appName + "' on device " + serial + " with locale '" + language + "'");
			new AdbExplorer(serial, appName, apkLocation, outputFolder, language, MAX_STATES).explore();
		} catch (Exception e) {
			// Wrap every failure in ExplorationException so the caller can record it per language and
			// keep going, instead of letting an IOException abort the whole multi-language run.
			// Fall back to the exception type when there is no message (e.g. a bare NPE) so the log
			// never reads "Exploration failed: null".
			String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
			throw new ExplorationException("Exploration failed: " + detail, e);
		}
	}

	/** Explores the app in its default (source) language. */
	public static String exploreDefaultLanguage(String language, String outputFolder, boolean translated, String apkLocation, String appName) throws ExplorationException {
		return runExploration(language, outputFolder, translated, apkLocation, appName);
	}

	/** Explores a translated language version of the app. */
	public static String exploreLanguageVersion(String language, String outputFolder, boolean translated, String apkLocation, String appName) throws ExplorationException {
		return runExploration(language, outputFolder, translated, apkLocation, appName);
	}

	private static String runExploration(String language, String outputFolder, boolean translated, String apkLocation, String appName) throws ExplorationException {
		try {
			File tempFolder = stateFolder(outputFolder, translated, language);
			explore(appName, apkLocation, tempFolder.getAbsolutePath(), language);
			return tempFolder.getCanonicalPath();
		} catch (IOException e) {
			throw new ExplorationException("Could not prepare the exploration output folder for '" + language + "': " + e.getMessage(), e);
		}
	}

	/** Fresh per-language output folder under the run's results directory. */
	private static File stateFolder(String outputFolder, boolean translated, String language) throws IOException {
		String decodedPath = Helper.getInstance().getCurrentDirectory();
		File tempFolder = new File(Paths.get(decodedPath, outputFolder,
				translated ? "trnsResults" : "noTrnsResults", language).toAbsolutePath().toString());
		if (tempFolder.exists()) {
			// File.delete() cannot remove a non-empty directory; wipe stale results recursively.
			FileUtils.deleteDirectory(tempFolder);
		}
		tempFolder.mkdirs();
		return tempFolder;
	}
}

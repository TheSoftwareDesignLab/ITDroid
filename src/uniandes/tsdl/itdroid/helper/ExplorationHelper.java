package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

import org.apache.commons.io.FileUtils;

import uniandes.tsdl.itdroid.explorer.AdbExplorer;

/** Runs the adb explorer per app version and returns its result folder. */
public class ExplorationHelper {

	/** Explores the SOURCE-language build with the recording DFS; writes result.json and the replay script. */
	public static String exploreSource(String language, String outputFolder, boolean translated, String apkLocation,
			String appName, AdbExplorer.Options options) throws ExplorationException {
		try {
			File folder = stateFolder(outputFolder, translated, language);
			String serial = AdbExplorer.resolveSerial();
			System.out.println("Exploring '" + appName + "' on device " + serial + " with locale '" + language + "'");
			new AdbExplorer(serial, appName, apkLocation, folder.getAbsolutePath(), language, options).explore();
			return folder.getCanonicalPath();
		} catch (Exception e) {
			throw wrap(language, e);
		}
	}

	/** Explores a translated build by replaying the source script, so its states pair 1:1 with the source graph. */
	public static String replayLanguage(String language, String outputFolder, boolean translated, String apkLocation,
			String appName, String scriptPath, AdbExplorer.Options options) throws ExplorationException {
		try {
			File folder = stateFolder(outputFolder, translated, language);
			String serial = AdbExplorer.resolveSerial();
			System.out.println("Exploring '" + appName + "' on device " + serial + " with locale '" + language + "' (replaying the source path)");
			new AdbExplorer(serial, appName, apkLocation, folder.getAbsolutePath(), language, options).replay(scriptPath);
			return folder.getCanonicalPath();
		} catch (Exception e) {
			throw wrap(language, e);
		}
	}

	/**
	 * Wraps every failure in ExplorationException so the caller can record it per language and keep
	 * going, instead of letting an IOException abort the whole multi-language run. Falls back to the
	 * exception type when there is no message (e.g. a bare NPE) so the log never reads "null".
	 */
	private static ExplorationException wrap(String language, Exception e) {
		if (e instanceof ExplorationException) {
			return (ExplorationException) e;
		}
		String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
		return new ExplorationException("Exploration failed for '" + language + "': " + detail, e);
	}

	/** Fresh per-language output folder under the run's results directory. */
	private static File stateFolder(String outputFolder, boolean translated, String language) throws IOException {
		String decodedPath = Helper.getInstance().getCurrentDirectory();
		// resolve(), not Paths.get(a, b): Paths.get JOINS its arguments, so an ABSOLUTE outputFolder
		// would be appended to the working directory ("C:\work\C:/out") and throw InvalidPathException
		// on Windows. Path.resolve returns the operand unchanged when it is already absolute.
		File tempFolder = new File(Paths.get(decodedPath).resolve(outputFolder)
				.resolve(translated ? "trnsResults" : "noTrnsResults").resolve(language)
				.toAbsolutePath().toString());
		if (tempFolder.exists()) {
			// File.delete() cannot remove a non-empty directory; wipe stale results recursively.
			FileUtils.deleteDirectory(tempFolder);
		}
		tempFolder.mkdirs();
		return tempFolder;
	}
}

package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

import org.apache.commons.io.FileUtils;

/** Decodes APKs with apktool and rebuilds + signs them with uber-apk-signer (both in {@code extra/}). */
public class APKToolWrapper {

	private static final String APKTOOL_JAR = "apktool.jar";
	private static final String SIGNER_JAR = "uber-apk-signer.jar";

	/** Decodes the APK into {@code temp/} (cleaned first) and returns that folder's absolute path. */
	public static String openAPK(String path, String extraPath) throws IOException, InterruptedException {
		String decodedPath = Helper.getInstance().getCurrentDirectory();
		File tempFolder = new File(decodedPath + File.separator + "temp");
		if (tempFolder.exists()) {
			// File.delete() only removes an EMPTY directory; a previously decoded app must be
			// wiped recursively so apktool does not reuse stale smali/resources.
			FileUtils.deleteDirectory(tempFolder);
		}
		tempFolder.mkdirs();

		// resolve() (not the joining Paths.get(a, b)) keeps an absolute user path intact and only
		// appends it when relative, so an absolute extraPath/apkPath does not produce an illegal
		// joined path (e.g. ...\ITDroid\C:\APKs\app.apk) that throws InvalidPathException on Windows.
		String apktool = Paths.get(decodedPath).resolve(extraPath).resolve(APKTOOL_JAR).toAbsolutePath().toString();
		String apk = Paths.get(decodedPath).resolve(path).toAbsolutePath().toString();
		String out = Paths.get(decodedPath, "temp").toAbsolutePath().toString();

		System.out.println("Decoding the APK...");
		int exit = run("java", "-jar", apktool, "d", apk, "-o", out, "-f");
		if (exit != 0) {
			throw new IOException("apktool failed to decode the APK (exit code " + exit + ").");
		}
		return out;
	}

	/** Rebuilds and signs the decoded app; returns the signed APK path, or {@code ""} on failure. */
	public static String buildAPK(String extraPath, String appName, String outputPath) throws IOException, InterruptedException {
		String decodedPath = Helper.getInstance().getCurrentDirectory();
		// resolve() (not the joining Paths.get(a, b)) keeps an absolute user path intact and only
		// appends it when relative, so an absolute extraPath/outputPath does not produce an illegal
		// joined path that throws InvalidPathException on Windows.
		String apktool = Paths.get(decodedPath).resolve(extraPath).resolve(APKTOOL_JAR).toAbsolutePath().toString();
		String signer = Paths.get(decodedPath).resolve(extraPath).resolve(SIGNER_JAR).toAbsolutePath().toString();
		String temp = Paths.get(decodedPath, "temp").toAbsolutePath().toString();
		String unsigned = Paths.get(decodedPath).resolve(outputPath).resolve(appName + ".apk").toAbsolutePath().toString();
		String outDir = Paths.get(decodedPath).resolve(outputPath).toAbsolutePath().toString();

		System.out.println("Building the APK...");
		int buildExit = run("java", "-jar", apktool, "b", temp, "-o", unsigned, "-f");
		if (buildExit != 0) {
			System.out.println("ERROR: apktool failed to build the APK (exit code " + buildExit + ").");
			return "";
		}

		System.out.println("Signing the APK...");
		run("java", "-jar", signer, "-a", unsigned, "-o", outDir);

		File signed = Paths.get(decodedPath).resolve(outputPath).resolve(appName + "-aligned-debugSigned.apk").toAbsolutePath().toFile();
		if (signed.exists()) {
			System.out.println("SUCCESS: the APK has been generated.");
			return signed.toString();
		}
		System.out.println("ERROR: the signed APK was not generated.");
		return "";
	}

	/**
	 * Runs an external command with stdout/stderr inherited by this process and returns its exit code.
	 * Inheriting the streams avoids the classic pipe-buffer deadlock of a chatty child that fills an
	 * undrained {@code Process} output stream.
	 */
	private static int run(String... command) throws IOException, InterruptedException {
		return new ProcessBuilder(command).inheritIO().start().waitFor();
	}
}

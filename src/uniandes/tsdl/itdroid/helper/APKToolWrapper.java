package uniandes.tsdl.itdroid.helper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;

/** Decodes APKs with apktool and rebuilds + signs them with uber-apk-signer (both in {@code extra/}). */
public class APKToolWrapper {

	private static final String APKTOOL_JAR = "apktool.jar";
	private static final String SIGNER_JAR = "uber-apk-signer.jar";

	/**
	 * How aapt2 reports an attribute of the android: namespace that the framework bundled with
	 * apktool does not have (the app was built for a newer Android than apktool knows):
	 * {@code W: C:\...\temp\AndroidManifest.xml:76: error: attribute android:pageSizeCompat not found.}
	 */
	private static final Pattern UNKNOWN_ATTRIBUTE =
			Pattern.compile("(?m)^W:\\s+(.+\\.xml):\\d+: error: attribute (android:\\w+) not found\\.");
	/**
	 * How aapt2 reports an attribute of the app (or of a library inside it) that a resource uses
	 * but no resource declares, because its declaration did not survive the decoding:
	 * {@code W: C:\...\temp\res\drawable\mtrl_switch_thumb.xml:6: error: attribute state_with_icon (aka org.tasks:state_with_icon) not found.}
	 */
	private static final Pattern MISSING_ATTRIBUTE =
			Pattern.compile("(?m)^W:\\s+.+\\.xml:\\d+: error: attribute (\\w+) \\(aka [\\w.]+:\\w+\\) not found\\.");
	/** Rounds of "adjust what the build rejected and build again" before giving up. */
	private static final int MAX_BUILD_ATTEMPTS = 6;

	/**
	 * What had to be changed in the decoded app of this run to make it build:
	 * "AndroidManifest.xml: removed android:pageSizeCompat", "res/values/attrs.xml: declared attr state_with_icon".
	 */
	private static final Set<String> buildAdjustments = new LinkedHashSet<>();

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
		buildAdjustments.clear();

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

	/**
	 * Rebuilds and signs the decoded app; returns the signed APK path, or {@code ""} on failure.
	 *
	 * <p>Two things apktool is known to trip on are adjusted in the decoded app, and the build
	 * tried again; {@link #buildAdjustments()} says what was done.
	 * <ul>
	 *   <li>An app built for a newer Android than apktool knows uses attributes of the android:
	 *       namespace that its bundled framework lacks. Such an attribute means nothing to the
	 *       older Android the app is evaluated on: it is removed.</li>
	 *   <li>A resource uses an attribute of the app whose declaration was lost in the decoding
	 *       (seen with Material 3's {@code state_with_icon}). It is declared again, so the
	 *       resources link; the app's code still looks for the id the attribute had, so the state
	 *       it marks never applies and that one visual variant is not drawn.</li>
	 * </ul>
	 */
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
		int buildExit = -1;
		for (int attempt = 1; attempt <= MAX_BUILD_ATTEMPTS; attempt++) {
			StringBuilder output = new StringBuilder();
			buildExit = runCapturing(output, "java", "-jar", apktool, "b", temp, "-o", unsigned, "-f");
			if (buildExit == 0) {
				break;
			}
			List<String> adjusted = removeUnknownAttributes(output.toString(), Paths.get(temp));
			adjusted.addAll(declareMissingAttributes(output.toString(), Paths.get(temp)));
			if (adjusted.isEmpty() || attempt == MAX_BUILD_ATTEMPTS) {
				break;
			}
			buildAdjustments.addAll(adjusted);
			System.out.println("NOTE: the build failed on something apktool is known to trip on; adjusted " + adjusted + ", building again...");
		}
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

	/** What was changed in the decoded app of this run so that it builds, as "file: what"; empty when nothing. */
	public static List<String> buildAdjustments() {
		return new ArrayList<>(buildAdjustments);
	}

	/**
	 * Removes from the decoded app every android: attribute the build output reports as unknown,
	 * and returns what it removed as "file: removed attribute" (file relative to the decoded
	 * folder). Files outside the decoded folder are never touched.
	 */
	static List<String> removeUnknownAttributes(String buildOutput, Path decoded) throws IOException {
		List<String> removed = new ArrayList<>();
		Path root = decoded.toAbsolutePath().normalize();
		Matcher m = UNKNOWN_ATTRIBUTE.matcher(buildOutput);
		while (m.find()) {
			Path file;
			try {
				file = Paths.get(m.group(1).trim()).toAbsolutePath().normalize();
			} catch (RuntimeException e) {
				continue;   // not a path this system understands
			}
			String attribute = m.group(2);
			if (!file.startsWith(root) || !Files.isRegularFile(file)) {
				continue;
			}
			String label = root.relativize(file).toString().replace('\\', '/') + ": removed " + attribute;
			if (removed.contains(label)) {
				continue;
			}
			String text = Files.readString(file, StandardCharsets.UTF_8);
			String stripped = text.replaceAll("\\s+" + Pattern.quote(attribute) + "=\"[^\"]*\"", "");
			if (!stripped.equals(text)) {
				Files.writeString(file, stripped, StandardCharsets.UTF_8);
				removed.add(label);
			}
		}
		return removed;
	}

	/**
	 * Declares in {@code res/values/attrs.xml} every attribute the build output reports as used but
	 * not declared, and returns what it declared as "res/values/attrs.xml: declared attr name".
	 * An attribute already declared somewhere in res/values is left alone (declaring it twice is
	 * an error of its own), so the same failure is never "fixed" twice.
	 */
	static List<String> declareMissingAttributes(String buildOutput, Path decoded) throws IOException {
		List<String> declared = new ArrayList<>();
		Set<String> names = new LinkedHashSet<>();
		Matcher m = MISSING_ATTRIBUTE.matcher(buildOutput);
		while (m.find()) {
			names.add(m.group(1));
		}
		if (names.isEmpty()) {
			return declared;
		}
		Path values = decoded.resolve("res").resolve("values");
		StringBuilder existing = new StringBuilder();
		File[] files = values.toFile().listFiles((d, n) -> n.toLowerCase().endsWith(".xml"));
		if (files != null) {
			for (File f : files) {
				existing.append(Files.readString(f.toPath(), StandardCharsets.UTF_8));
			}
		}
		StringBuilder added = new StringBuilder();
		for (String name : names) {
			if (Pattern.compile("<attr\\s[^>]*name=\"" + Pattern.quote(name) + "\"").matcher(existing).find()) {
				continue;
			}
			added.append("    <attr name=\"").append(name).append("\" />\n");
			declared.add("res/values/attrs.xml: declared attr " + name);
		}
		if (declared.isEmpty()) {
			return declared;
		}
		Path attrs = values.resolve("attrs.xml");
		if (Files.isRegularFile(attrs)) {
			String text = Files.readString(attrs, StandardCharsets.UTF_8);
			int close = text.lastIndexOf("</resources>");
			if (close < 0) {
				return new ArrayList<>();   // not a file this can be added to; leave the build to fail with its own message
			}
			Files.writeString(attrs, text.substring(0, close) + added + text.substring(close), StandardCharsets.UTF_8);
		} else {
			Files.createDirectories(values);
			Files.writeString(attrs, "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n" + added + "</resources>\n", StandardCharsets.UTF_8);
		}
		return declared;
	}

	/**
	 * Runs an external command with stdout/stderr inherited by this process and returns its exit code.
	 * Inheriting the streams avoids the classic pipe-buffer deadlock of a chatty child that fills an
	 * undrained {@code Process} output stream.
	 */
	private static int run(String... command) throws IOException, InterruptedException {
		return new ProcessBuilder(command).inheritIO().start().waitFor();
	}

	/**
	 * Runs a command echoing its output (stdout and stderr together) line by line and keeping a copy
	 * in {@code output}. The stream is read until it closes, so the child never blocks on a full pipe.
	 */
	private static int runCapturing(StringBuilder output, String... command) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				System.out.println(line);
				output.append(line).append('\n');
			}
		}
		return process.waitFor();
	}
}

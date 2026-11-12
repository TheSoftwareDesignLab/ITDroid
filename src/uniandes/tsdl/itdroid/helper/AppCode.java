package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;

/**
 * Where the app's own code lives in a decoded APK.
 *
 * <p>The applicationId is not a reliable guide. A build flavour adds a suffix to it
 * ({@code com.x.app.pro} with the code in {@code com.x.app}) and the Gradle namespace can be
 * unrelated to it ({@code org.birthdayadapter} with the code in {@code fr.heinisch.birthdayadapter}),
 * so looking only under the applicationId folder finds nothing in such apps. The code is located
 * from three sources instead:
 * <ol>
 *   <li>the applicationId folder, when it holds code;</li>
 *   <li>a {@code BuildConfig} class that declares this applicationId (it sits in the namespace);</li>
 *   <li>the classes the manifest declares: the application class and the launcher activity anchor
 *       the search, and the common package of the components that share their prefix is the root.</li>
 * </ol>
 * Extra roots can be given by hand (config key {@code hardcoded.codePackages}) for apps split in
 * modules that the manifest does not mention.
 */
public final class AppCode {

    private static final Namespace ANDROID = Namespace.getNamespace("android", "http://schemas.android.com/apk/res/android");

    /** Packages of platform, library and framework code: never an anchor, never an app root. */
    private static final String[] LIBRARY_PREFIXES = {"android.", "androidx.", "com.android.", "com.google.", "kotlin.",
            "kotlinx.", "java.", "javax.", "dalvik.", "io.flutter.", "org.chromium.", "com.facebook.react.",
            "com.getcapacitor.", "org.apache.cordova.", "com.unity3d.", "org.qtproject.", "org.libsdl.", "expo.modules.",
            "org.jetbrains.", "okhttp3.", "okio.", "retrofit2.", "dagger.", "leakcanary.", "org.acra.", "com.squareup.",
            "io.sentry.", "com.journeyapps.", "org.koin.", "coil.", "com.bumptech."};

    /** Hosting domains shared by many unrelated authors: the vendor is one level deeper. */
    private static final Set<String> SHARED_DOMAINS = Set.of("com.github", "io.github", "com.gitlab", "io.gitlab",
            "org.bitbucket", "com.google", "org.apache", "org.jetbrains", "com.android", "net.sourceforge", "org.codeberg",
            "io.codeberg", "page.codeberg", "com.example");

    /** Extra package roots given by hand (config), added to whatever is found. */
    private static volatile List<String> extraRoots = List.of();

    private AppCode() {
    }

    /** Sets the extra roots of {@code hardcoded.codePackages} (comma separated; blank to clear). */
    public static void setExtraRoots(String commaSeparated) {
        List<String> roots = new ArrayList<>();
        if (commaSeparated != null) {
            for (String r : commaSeparated.split(",")) {
                if (!r.trim().isEmpty()) {
                    roots.add(r.trim());
                }
            }
        }
        extraRoots = roots;
    }

    /**
     * Package roots of the app's own code, e.g. {@code [com.example.shop]}. Only roots that hold
     * smali are returned; when nothing can be located the applicationId is returned alone.
     */
    public static List<String> packageRoots(String decodedPath, String applicationId) {
        File decoded = new File(decodedPath);
        Set<String> found = new LinkedHashSet<>();
        if (applicationId != null && !applicationId.isEmpty() && hasSmali(decoded, applicationId)) {
            found.add(applicationId);
        }
        found.addAll(buildConfigNamespaces(decoded, applicationId));
        found.addAll(manifestRoots(decoded));
        found.addAll(extraRoots);

        List<String> roots = new ArrayList<>();
        for (String candidate : found) {
            if (candidate.isEmpty() || isLibrary(candidate + ".") || !hasSmali(decoded, candidate)) {
                continue;
            }
            roots.add(candidate);
        }
        // A root inside another root adds nothing: keep the outermost.
        List<String> outer = new ArrayList<>();
        for (String r : roots) {
            boolean nested = false;
            for (String other : roots) {
                if (!other.equals(r) && r.startsWith(other + ".")) {
                    nested = true;
                    break;
                }
            }
            if (!nested && !outer.contains(r)) {
                outer.add(r);
            }
        }
        outer.sort(Comparator.naturalOrder());
        if (outer.isEmpty() && applicationId != null && !applicationId.isEmpty()) {
            outer.add(applicationId);
        }
        return outer;
    }

    /**
     * The app's smali files across every dex folder, in a stable order ({@code smali},
     * {@code smali_classes2}, ...; then by path), without the test-instrumentation classes the
     * original tool skipped.
     */
    public static List<File> smaliFiles(String decodedPath, String applicationId) {
        return smaliFiles(decodedPath, packageRoots(decodedPath, applicationId));
    }

    public static List<File> smaliFiles(String decodedPath, List<String> packageRoots) {
        List<File> result = new ArrayList<>();
        for (File smaliDir : smaliDirs(new File(decodedPath))) {
            for (String root : packageRoots) {
                File pkgDir = new File(smaliDir, root.replace('.', File.separatorChar));
                if (!pkgDir.isDirectory()) {
                    continue;
                }
                Collection<File> files = FileUtils.listFiles(pkgDir, new String[] {"smali"}, true);
                List<File> sorted = new ArrayList<>(files);
                sorted.sort(Comparator.comparing(File::getPath));
                for (File file : sorted) {
                    String n = file.getName();
                    if (n.contains("EmmaInstrumentation") || n.contains("FinishListener")
                            || n.contains("InstrumentedActivity") || n.contains("SMSInstrumentedReceiver")) {
                        continue;
                    }
                    result.add(file);
                }
            }
        }
        return result;
    }

    /** The dex folders of a decoded app: smali, smali_classes2, smali_classes3, ... in that order. */
    public static List<File> smaliDirs(File decoded) {
        File[] dirs = decoded.listFiles((dir, name) -> name.equals("smali") || name.startsWith("smali_classes"));
        if (dirs == null) {
            return new ArrayList<>();
        }
        List<File> list = new ArrayList<>(Arrays.asList(dirs));
        list.sort(Comparator.comparingInt(AppCode::dexNumber));
        return list;
    }

    private static int dexNumber(File smaliDir) {
        String digits = smaliDir.getName().replaceAll("\\D", "");
        try {
            return digits.isEmpty() ? 1 : Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** True when the internal class name ({@code com/x/Foo}) or package belongs to one of the roots. */
    public static boolean isUnder(List<String> packageRoots, String internalClassName) {
        String dotted = internalClassName.replace('/', '.');
        for (String root : packageRoots) {
            if (dotted.equals(root) || dotted.startsWith(root + ".")) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ sources of roots

    private static boolean hasSmali(File decoded, String packageName) {
        String path = packageName.replace('.', File.separatorChar);
        for (File smaliDir : smaliDirs(decoded)) {
            if (new File(smaliDir, path).isDirectory()) {
                return true;
            }
        }
        return false;
    }

    /** Packages of the BuildConfig classes whose APPLICATION_ID is this app's: the Gradle namespace. */
    private static List<String> buildConfigNamespaces(File decoded, String applicationId) {
        List<String> namespaces = new ArrayList<>();
        if (applicationId == null || applicationId.isEmpty()) {
            return namespaces;
        }
        String marker = "APPLICATION_ID:Ljava/lang/String; = \"" + applicationId + "\"";
        for (File smaliDir : smaliDirs(decoded)) {
            Path base = smaliDir.toPath();
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(p -> p.getFileName() != null && p.getFileName().toString().equals("BuildConfig.smali")).forEach(p -> {
                    try {
                        if (Files.readString(p, StandardCharsets.UTF_8).contains(marker)) {
                            Path pkg = base.relativize(p.getParent());
                            String name = pkg.toString().replace(File.separatorChar, '.').replace('/', '.');
                            if (!name.isEmpty() && !namespaces.contains(name)) {
                                namespaces.add(name);
                            }
                        }
                    } catch (IOException e) {
                        // unreadable: this source simply adds nothing
                    }
                });
            } catch (IOException e) {
                // unreadable dex folder: this source simply adds nothing
            }
        }
        return namespaces;
    }

    /** Roots derived from the application class and the launcher activities of the manifest. */
    private static List<String> manifestRoots(File decoded) {
        List<String> roots = new ArrayList<>();
        File manifest = new File(decoded, "AndroidManifest.xml");
        if (!manifest.isFile()) {
            return roots;
        }
        Element root;
        try {
            root = new SAXBuilder().build(manifest).getRootElement();
        } catch (Exception e) {
            return roots;
        }
        String manifestPackage = root.getAttributeValue("package", "");
        Element application = root.getChild("application");
        if (application == null) {
            return roots;
        }
        List<String> anchors = new ArrayList<>();
        List<String> components = new ArrayList<>();
        String appClass = qualify(application.getAttributeValue("name", ANDROID), manifestPackage);
        if (appClass != null) {
            anchors.add(appClass);
            components.add(appClass);
        }
        for (Element component : application.getChildren()) {
            String tag = component.getName();
            if (!tag.equals("activity") && !tag.equals("activity-alias") && !tag.equals("service")
                    && !tag.equals("receiver") && !tag.equals("provider")) {
                continue;
            }
            String name = tag.equals("activity-alias")
                    ? qualify(component.getAttributeValue("targetActivity", ANDROID), manifestPackage)
                    : qualify(component.getAttributeValue("name", ANDROID), manifestPackage);
            if (name == null) {
                continue;
            }
            components.add(name);
            if ((tag.equals("activity") || tag.equals("activity-alias")) && isLauncher(component)) {
                anchors.add(name);
            }
        }
        for (String anchor : anchors) {
            if (isLibrary(anchor)) {
                continue;
            }
            String anchorPackage = packageOf(anchor);
            if (anchorPackage.isEmpty()) {
                continue;
            }
            String family = familyPrefix(anchorPackage);
            String common = anchorPackage;
            for (String component : components) {
                String pkg = packageOf(component);
                if (!isLibrary(component) && (pkg.equals(family) || pkg.startsWith(family + "."))) {
                    common = commonPackage(common, pkg);
                }
            }
            if (!common.isEmpty() && !roots.contains(common)) {
                roots.add(common);
            }
        }
        return roots;
    }

    private static boolean isLauncher(Element activity) {
        for (Element filter : activity.getChildren("intent-filter")) {
            boolean main = false;
            boolean launcher = false;
            for (Element e : filter.getChildren()) {
                String name = e.getAttributeValue("name", ANDROID);
                if ("android.intent.action.MAIN".equals(name)) {
                    main = true;
                } else if ("android.intent.category.LAUNCHER".equals(name) || "android.intent.category.LEANBACK_LAUNCHER".equals(name)) {
                    launcher = true;
                }
            }
            if (main && launcher) {
                return true;
            }
        }
        return false;
    }

    /** Fully qualified class name of a manifest {@code android:name} (".Foo" and "Foo" are relative to the package). */
    private static String qualify(String name, String manifestPackage) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String n = name.trim();
        if (n.startsWith(".")) {
            return manifestPackage + n;
        }
        return n.contains(".") ? n : manifestPackage + "." + n;
    }

    private static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }

    private static boolean isLibrary(String className) {
        for (String prefix : LIBRARY_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The prefix that identifies whose code a package is. Reverse-domain packages
     * ({@code fr.heinisch.birthdayadapter.ui}) belong to their first two segments, three under a
     * domain shared by many authors ({@code io.github.user}). A package that does not start with a
     * domain suffix ({@code fulguris.activity}) belongs to its first segment.
     */
    static String familyPrefix(String packageName) {
        String[] s = packageName.split("\\.");
        if (s.length == 1) {
            return packageName;
        }
        if (!looksLikeDomainSuffix(s[0])) {
            return s[0];
        }
        int depth = SHARED_DOMAINS.contains(s[0] + "." + s[1]) ? 3 : 2;
        return String.join(".", Arrays.copyOfRange(s, 0, Math.min(depth, s.length)));
    }

    private static boolean looksLikeDomainSuffix(String segment) {
        return segment.length() <= 3 || Set.of("info", "tech", "name", "mobi", "page", "site", "blog", "asia", "coop",
                "club", "online", "cloud", "software", "digital", "studio", "world", "network", "systems").contains(segment);
    }

    /** Longest common package of two packages, by whole segments ("" when they share none). */
    static String commonPackage(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(x.length, y.length) && x[i].equals(y[i]); i++) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(x[i]);
        }
        return sb.toString();
    }
}

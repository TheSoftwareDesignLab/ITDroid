package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.simple.JSONObject;

/**
 * Makes a decoded app follow the direction of a right-to-left language, so the repaired app can be
 * built and compared with the one that did not mirror. Port of {@code supportRTL} and
 * {@code createLayoutsRTL} of the {@code repairments} branch:
 *
 * <ol>
 * <li>the application declares {@code android:supportsRtl="true"}, without which Android keeps every
 * layout left to right whatever the language;</li>
 * <li>in the layouts, the attributes that name a side ({@code paddingLeft}, {@code layout_marginRight},
 * {@code layout_alignParentLeft}, {@code drawableRight}...) become the ones that name the start or the
 * end of the line, which swap sides with the direction;</li>
 * <li>a copy of every layout forces the right-to-left direction on its root element.</li>
 * </ol>
 *
 * <p>What differs from the original. It rewrites tags, not lines, so an attribute is renamed only
 * when its own element lacks the start/end one (the original skipped a whole line that had any).
 * It also renames {@code layout_toLeftOf}/{@code layout_toRightOf}, the sides of ConstraintLayout
 * and the {@code left}/{@code right} values of {@code gravity}, which the original left alone. The
 * forced copies go to {@code layout-ldrtl}, the qualifier of the direction, instead of
 * {@code layout-ar}: they serve every right-to-left language and keep the other qualifiers of the
 * folder ({@code layout-ldrtl-land}), where one {@code layout-ar} folder hid the landscape and
 * tablet variants. And everything it changes can be put back ({@link #revert()}).
 *
 * <p>An app that draws its interface with Compose has no layouts to rewrite; for it the repair is
 * the first step alone, which is also what makes Compose mirror.
 */
public final class RtlRepair {

    /**
     * The attributes that name a side and have a start/end twin. A closed list on purpose: the
     * original renamed anything ending in {@code Left=} or {@code Right=}, which turns
     * {@code nextFocusLeft} or CardView's {@code contentPaddingLeft} into attributes that do not
     * exist, and the app no longer builds.
     */
    private static final Pattern SIDE_ATTRIBUTE = Pattern.compile("(?<=\\s)((?:\\w+:)?(?:padding|layout_margin|layout_alignParent|layout_align"
            + "|drawable|layout_goneMargin|contentInset))(Left|Right)(\\s*=\\s*\"[^\"]*\")");
    /** RelativeLayout's "to the left of" and ConstraintLayout's sides, which do not end in the side. */
    private static final String[][] NAMED = {
            {"layout_toLeftOf", "layout_toStartOf"}, {"layout_toRightOf", "layout_toEndOf"},
            {"layout_constraintLeft_toLeftOf", "layout_constraintStart_toStartOf"},
            {"layout_constraintLeft_toRightOf", "layout_constraintStart_toEndOf"},
            {"layout_constraintRight_toLeftOf", "layout_constraintEnd_toStartOf"},
            {"layout_constraintRight_toRightOf", "layout_constraintEnd_toEndOf"}};
    private static final Pattern GRAVITY = Pattern.compile("(?<=\\s)((?:\\w+:)?(?:layout_)?gravity\\s*=\\s*\")([^\"]*)(\")");
    /** A layout folder for one language or one carrier ({@code layout-ar}, {@code layout-b+sr+Latn}, {@code layout-mcc310}): those qualifiers go before the direction. */
    private static final Pattern LOCALE_FOLDER = Pattern.compile("layout-(?:[a-z]{2,3}|b\\+[^-]+|mcc\\d+|mnc\\d+)(?:-.*)?");
    private static final Pattern START_TAG = Pattern.compile("<[A-Za-z_][^<>]*>");
    private static final Pattern APPLICATION_TAG = Pattern.compile("<application\\b[^>]*>");
    private static final Pattern SUPPORTS_RTL = Pattern.compile("android:supportsRtl\\s*=\\s*\"([^\"]*)\"");

    private final Path decoded;
    /** The files that were rewritten, with what they had. */
    private final Map<Path, byte[]> backups = new LinkedHashMap<>();
    /** The files and folders that did not exist before. */
    private final List<Path> created = new ArrayList<>();

    private boolean supportsRtlAdded;
    private int layoutsChanged;
    private int attributesRenamed;
    private int forcedLayouts;

    private RtlRepair(Path decoded) {
        this.decoded = decoded;
    }

    /**
     * Applies the repair to the decoded app.
     *
     * @param minSdk the minSdkVersion of the app; the start/end attributes exist from API 17 on, so
     *               below it they are added next to the left/right ones instead of replacing them
     */
    public static RtlRepair apply(String decodedPath, int minSdk) throws IOException {
        RtlRepair repair = new RtlRepair(new File(decodedPath).toPath());
        repair.repairManifest();
        repair.repairLayouts(minSdk >= 17);
        repair.forceDirection();
        return repair;
    }

    /** True when the app was changed at all. */
    public boolean changedSomething() {
        return supportsRtlAdded || layoutsChanged > 0 || forcedLayouts > 0;
    }

    /** What was done, for report.json. */
    @SuppressWarnings("unchecked")
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("supportsRtlAdded", supportsRtlAdded);
        o.put("layoutsChanged", (long) layoutsChanged);
        o.put("attributesRenamed", (long) attributesRenamed);
        o.put("forcedLayouts", (long) forcedLayouts);
        return o;
    }

    /** Puts the decoded app back as it was before {@link #apply}. */
    public void revert() throws IOException {
        for (Map.Entry<Path, byte[]> e : backups.entrySet()) {
            Files.write(e.getKey(), e.getValue());
        }
        for (int i = created.size() - 1; i >= 0; i--) {
            Files.deleteIfExists(created.get(i));
        }
        backups.clear();
        created.clear();
    }

    // ------------------------------------------------------------------ 1. the manifest

    private void repairManifest() throws IOException {
        Path manifest = decoded.resolve("AndroidManifest.xml");
        if (!Files.isRegularFile(manifest)) {
            return;
        }
        String text = Files.readString(manifest, StandardCharsets.UTF_8);
        Matcher tag = APPLICATION_TAG.matcher(text);
        if (!tag.find()) {
            return;
        }
        String application = tag.group();
        Matcher declared = SUPPORTS_RTL.matcher(application);
        String repaired;
        if (declared.find()) {
            if ("true".equals(declared.group(1))) {
                return;
            }
            repaired = declared.replaceFirst("android:supportsRtl=\"true\"");
        } else {
            repaired = application.replaceFirst("^<application", "<application android:supportsRtl=\"true\"");
        }
        rewrite(manifest, text.substring(0, tag.start()) + repaired + text.substring(tag.end()));
        supportsRtlAdded = true;
    }

    // ------------------------------------------------------------------ 2. sides become start and end

    private void repairLayouts(boolean replace) throws IOException {
        for (Path layout : layoutFiles()) {
            String text = Files.readString(layout, StandardCharsets.UTF_8);
            int[] renamed = {0};
            String repaired = rewriteTags(text, tag -> renameSides(tag, replace, renamed));
            if (renamed[0] > 0) {
                rewrite(layout, repaired);
                layoutsChanged++;
                attributesRenamed += renamed[0];
            }
        }
    }

    /** The start tag with its side attributes renamed (or, below API 17, doubled); counts them in {@code renamed}. */
    static String renameSides(String tag, boolean replace, int[] renamed) {
        String out = tag;
        for (String[] pair : NAMED) {
            out = rename(out, Pattern.compile("(?<=\\s)((?:\\w+:)?)" + pair[0] + "(\\s*=\\s*\"[^\"]*\")"), m -> m.group(1) + pair[1], replace, renamed);
        }
        out = rename(out, SIDE_ATTRIBUTE, m -> m.group(1) + ("Left".equals(m.group(2)) ? "Start" : "End"), replace, renamed);
        Matcher g = GRAVITY.matcher(out);
        StringBuffer sb = new StringBuffer();
        while (g.find()) {
            String value = g.group(2);
            String mirrored = value.replaceAll("\\bleft\\b", "start").replaceAll("\\bright\\b", "end");
            if (!mirrored.equals(value)) {
                renamed[0]++;
            }
            g.appendReplacement(sb, Matcher.quoteReplacement(g.group(1) + mirrored + g.group(3)));
        }
        g.appendTail(sb);
        return sb.toString();
    }

    private interface NewName {
        String of(Matcher m);
    }

    /**
     * Renames the attributes the pattern finds in a tag. Its last group is the {@code ="value"}
     * part. An attribute whose start/end counterpart the element already has is left as it is.
     */
    private static String rename(String tag, Pattern pattern, NewName newName, boolean replace, int[] renamed) {
        Matcher m = pattern.matcher(tag);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = newName.of(m);
            String value = m.group(m.groupCount());
            String attribute = name + value;
            if (Pattern.compile("\\s" + Pattern.quote(name) + "\\s*=").matcher(tag).find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            renamed[0]++;
            m.appendReplacement(sb, Matcher.quoteReplacement(replace ? attribute : m.group() + " " + attribute));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private interface TagRewrite {
        String of(String tag);
    }

    private static String rewriteTags(String xml, TagRewrite rewrite) {
        Matcher m = START_TAG.matcher(xml);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(rewrite.of(m.group())));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------ 3. a copy that forces the direction

    private void forceDirection() throws IOException {
        for (Path layout : layoutFiles()) {
            String folder = layout.getParent().getFileName().toString();
            String qualifiers = folder.substring("layout".length());      // "" or "-land", "-sw600dp-v21"...
            if (qualifiers.contains("-ldrtl") || qualifiers.contains("-ldltr") || LOCALE_FOLDER.matcher(folder).matches()) {
                continue;
            }
            String text = Files.readString(layout, StandardCharsets.UTF_8);
            Matcher root = START_TAG.matcher(text);
            if (!root.find() || root.group().contains("android:layoutDirection")) {
                continue;
            }
            String tag = root.group();
            // <merge> and <layout> (data binding) have no direction of their own
            if (tag.startsWith("<merge") || tag.startsWith("<layout") || !tag.contains("xmlns:android")) {
                continue;
            }
            String forced = tag.endsWith("/>") ? tag.substring(0, tag.length() - 2).stripTrailing() + " android:layoutDirection=\"rtl\"/>"
                    : tag.substring(0, tag.length() - 1).stripTrailing() + " android:layoutDirection=\"rtl\">";
            Path target = layout.getParent().resolveSibling("layout-ldrtl" + qualifiers);
            if (!Files.isDirectory(target)) {
                Files.createDirectories(target);
                created.add(target);
            }
            Path copy = target.resolve(layout.getFileName());
            if (Files.exists(copy)) {
                continue;       // the app already ships a right-to-left version of this layout
            }
            Files.writeString(copy, text.substring(0, root.start()) + forced + text.substring(root.end()), StandardCharsets.UTF_8);
            created.add(copy);
            forcedLayouts++;
        }
    }

    // ------------------------------------------------------------------ files

    /** Every XML file of the layout folders of the app, whatever their qualifiers, in a fixed order. */
    private List<Path> layoutFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        File[] folders = decoded.resolve("res").toFile().listFiles(f -> f.isDirectory()
                && (f.getName().equals("layout") || f.getName().startsWith("layout-")));
        if (folders == null) {
            return out;
        }
        java.util.Arrays.sort(folders);
        for (File folder : folders) {
            File[] files = folder.listFiles(f -> f.isFile() && f.getName().endsWith(".xml"));
            if (files == null) {
                continue;
            }
            java.util.Arrays.sort(files);
            for (File f : files) {
                out.add(f.toPath());
            }
        }
        return out;
    }

    private void rewrite(Path file, String text) throws IOException {
        if (!backups.containsKey(file)) {
            backups.put(file, Files.readAllBytes(file));
        }
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}

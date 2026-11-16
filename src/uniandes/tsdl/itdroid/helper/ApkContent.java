package uniandes.tsdl.itdroid.helper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Every text an APK carries, read from the file itself, without decoding it: the strings of its
 * code (every {@code classes*.dex}, all packages, libraries included) and the readable runs of its
 * other files ({@code assets/}, where a web interface or a React Native bundle keeps its texts, and
 * the native libraries of one ABI, where Flutter keeps them).
 *
 * <p>It answers whether a text shown on a screen could have come from the APK at all. A text the
 * APK does not carry anywhere came from the network, the device or what the app stored at run
 * time, and no translation of the APK can reach it.
 */
public final class ApkContent {

    /** Files that are pictures, sound or fonts carry no text to look for. */
    private static final String[] MEDIA = { ".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".mp3", ".ogg", ".wav", ".m4a", ".mp4",
            ".ttf", ".otf", ".woff", ".woff2", ".9.png", ".dex", ".arsc" };
    /** A run of readable bytes shorter than this is noise of a binary file. */
    private static final int MIN_RUN = 4;
    /** At most this many characters of file content are kept (a web app can bring tens of megabytes). */
    static final int MAX_FILE_CHARS = 64 * 1024 * 1024;

    /** The strings of the code. */
    public final List<String> codeStrings;
    /** The readable runs of the other files, lower case, one per line. */
    public final String files;

    private ApkContent(List<String> codeStrings, String files) {
        this.codeStrings = codeStrings;
        this.files = files;
    }

    /** Reads an APK; throws when it is not a readable zip. */
    public static ApkContent read(String apkPath) throws IOException {
        List<String> strings = new ArrayList<>();
        StringBuilder files = new StringBuilder();
        String abi = null;
        try (ZipFile zip = new ZipFile(apkPath)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (e.isDirectory()) {
                    continue;
                }
                if (name.matches("classes\\d*\\.dex")) {
                    strings.addAll(dexStrings(bytes(zip, e)));
                    continue;
                }
                boolean asset = name.startsWith("assets/");
                boolean lib = name.startsWith("lib/") && name.endsWith(".so");
                if (asset) {
                    // the names of its files too: an app can show a file name as a label (a preset, a sound)
                    files.append(name.toLowerCase(Locale.ROOT)).append('\n');
                    if (name.endsWith(".cvr")) {
                        // Compose Multiplatform: one line per text, its value in base64; the default ones are enough
                        if (name.contains("/values/")) {
                            composeResources(bytes(zip, e), files);
                        }
                        continue;
                    }
                }
                if (lib) {
                    // the same texts are in every ABI: one is enough
                    String[] parts = name.split("/");
                    if (parts.length < 3) {
                        continue;
                    }
                    if (abi == null) {
                        abi = parts[1];
                    } else if (!abi.equals(parts[1])) {
                        continue;
                    }
                }
                if ((asset || lib) && !isMedia(name) && files.length() < MAX_FILE_CHARS) {
                    readableRuns(bytes(zip, e), files);
                }
            }
        }
        if (files.length() > MAX_FILE_CHARS) {
            files.setLength(MAX_FILE_CHARS);
        }
        return new ApkContent(strings, files.toString().toLowerCase(Locale.ROOT));
    }

    private static boolean isMedia(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String ext : MEDIA) {
            if (n.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] bytes(ZipFile zip, ZipEntry e) throws IOException {
        try (InputStream in = zip.getInputStream(e)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(e.getSize() > 0 && e.getSize() < Integer.MAX_VALUE ? (int) e.getSize() : 8192);
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    /**
     * The string pool of a dex file: {@code string_ids_size} at 0x38 and {@code string_ids_off} at
     * 0x3C of the header; each id is the offset of a ULEB128 length followed by the text in modified
     * UTF-8, ended by a zero byte.
     */
    static List<String> dexStrings(byte[] dex) {
        List<String> out = new ArrayList<>();
        if (dex.length < 0x70 || dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x') {
            return out;
        }
        int size = u32(dex, 0x38);
        int offset = u32(dex, 0x3C);
        for (int i = 0; i < size; i++) {
            int at = offset + 4 * i;
            if (at < 0 || at + 4 > dex.length) {
                break;
            }
            int p = u32(dex, at);
            if (p < 0 || p >= dex.length) {
                continue;
            }
            while (p < dex.length && (dex[p] & 0x80) != 0) {   // the ULEB128 length, in UTF-16 units: not needed
                p++;
            }
            p++;
            int end = p;
            while (end < dex.length && dex[end] != 0) {
                end++;
            }
            if (end > p) {
                out.add(new String(dex, p, end - p, StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static int u32(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
    }

    /**
     * The texts of a Compose Multiplatform resource file ({@code .cvr}): lines such as
     * {@code string|key|<base64>}, or several values separated by commas, each maybe after a
     * {@code quantity:} prefix. Each value is decoded and appended on its own line.
     */
    static void composeResources(byte[] data, StringBuilder out) {
        for (String line : new String(data, StandardCharsets.UTF_8).split("\\r?\\n")) {
            String[] fields = line.split("\\|");
            for (int f = 2; f < fields.length; f++) {
                for (String value : fields[f].split(",")) {
                    String v = value.contains(":") ? value.substring(value.lastIndexOf(':') + 1) : value;
                    try {
                        out.append(new String(java.util.Base64.getDecoder().decode(v.trim()), StandardCharsets.UTF_8)).append('\n');
                    } catch (IllegalArgumentException ignored) {
                        // not base64: not a text
                    }
                }
            }
        }
    }

    /** Appends the runs of readable UTF-8 (printable ASCII or multibyte letters), one per line. */
    static void readableRuns(byte[] data, StringBuilder out) {
        int start = -1;
        for (int i = 0; i <= data.length; i++) {
            int b = i < data.length ? data[i] & 0xff : 0;
            boolean readable = (b >= 0x20 && b < 0x7f) || b >= 0x80 || b == '\t';
            if (readable) {
                if (start < 0) {
                    start = i;
                }
            } else if (start >= 0) {
                if (i - start >= MIN_RUN) {
                    out.append(new String(data, start, i - start, StandardCharsets.UTF_8)).append('\n');
                }
                start = -1;
            }
        }
    }
}

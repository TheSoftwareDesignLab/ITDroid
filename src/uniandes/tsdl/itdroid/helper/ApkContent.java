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
 * Every text an APK carries, read without decoding it, from the dex string pools, the assets and the
 * native libraries of one ABI. It tells whether a text on screen could come from the APK at all,
 * since a text it does not carry came from the network or the device and no translation reaches it.
 */
public final class ApkContent {

    /** Extensions of files with no text to look for. */
    private static final String[] MEDIA = { ".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".mp3", ".ogg", ".wav", ".m4a", ".mp4",
            ".ttf", ".otf", ".woff", ".woff2", ".9.png", ".dex", ".arsc" };
    /** A run of readable bytes shorter than this is noise of a binary file. */
    private static final int MIN_RUN = 4;
    /** Cap on the file content kept, since a web app can bring tens of megabytes. */
    static final int MAX_FILE_CHARS = 64 * 1024 * 1024;

    public final List<String> codeStrings;
    /** The readable runs of the other files, lower case, one per line. */
    public final String files;

    private ApkContent(List<String> codeStrings, String files) {
        this.codeStrings = codeStrings;
        this.files = files;
    }

    /** Reads an APK, throwing when it is not a readable zip. */
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
                    // file names too, since an app can show one as a label
                    files.append(name.toLowerCase(Locale.ROOT)).append('\n');
                    if (name.endsWith(".cvr")) {
                        // Compose Multiplatform resources, only the default values are needed
                        if (name.contains("/values/")) {
                            composeResources(bytes(zip, e), files);
                        }
                        continue;
                    }
                }
                if (lib) {
                    // every ABI has the same texts, so one is enough
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

    /** The package of an APK, read from its binary manifest without decoding it, or empty when it cannot be read. */
    public static String packageName(String apkPath) {
        try (ZipFile zip = new ZipFile(apkPath)) {
            ZipEntry e = zip.getEntry("AndroidManifest.xml");
            return e == null ? "" : manifestPackage(bytes(zip, e));
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /**
     * The package attribute of the manifest element of a binary XML file. The file is a chain of chunks,
     * a string pool first and then one chunk per element, whose attributes point into the pool.
     */
    static String manifestPackage(byte[] x) {
        if (x.length < 8 || u16(x, 0) != 0x0003) {
            return "";
        }
        List<String> pool = null;
        int at = u16(x, 2);
        while (at + 8 <= x.length) {
            int type = u16(x, at);
            int size = u32(x, at + 4);
            if (size <= 0 || at + size > x.length) {
                break;
            }
            if (type == 0x0001) {
                pool = stringPool(x, at);
            } else if (type == 0x0102 && pool != null) {
                // the element starts after its node header with namespace, name and where its attributes are
                int ext = at + u16(x, at + 2);
                if (!entry(pool, u32(x, ext + 4)).equals("manifest")) {
                    return "";
                }
                int start = u16(x, ext + 8);
                int step = u16(x, ext + 10);
                for (int i = 0; i < u16(x, ext + 12); i++) {
                    int a = ext + start + i * step;
                    if (entry(pool, u32(x, a + 4)).equals("package")) {
                        // the raw text, or the typed value when it is a string
                        int raw = u32(x, a + 8);
                        return raw != -1 ? entry(pool, raw) : (x[a + 15] == 0x03 ? entry(pool, u32(x, a + 16)) : "");
                    }
                }
                return "";
            }
            at += size;
        }
        return "";
    }

    /** The strings of a string pool chunk, in UTF-8 or UTF-16 as its flags say. */
    private static List<String> stringPool(byte[] x, int at) {
        int count = u32(x, at + 8);
        boolean utf8 = (u32(x, at + 16) & 0x100) != 0;
        int data = at + u32(x, at + 20);
        int offsets = at + u16(x, at + 2);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int p = data + u32(x, offsets + 4 * i);
            if (utf8) {
                p += (x[p] & 0x80) != 0 ? 2 : 1;   // length in UTF-16 units, not needed
                int len = x[p] & 0xff;
                if ((len & 0x80) != 0) {
                    len = ((len & 0x7f) << 8) | (x[p + 1] & 0xff);
                    p++;
                }
                out.add(new String(x, p + 1, len, StandardCharsets.UTF_8));
            } else {
                int len = u16(x, p);
                if ((len & 0x8000) != 0) {
                    len = ((len & 0x7fff) << 16) | u16(x, p + 2);
                    p += 2;
                }
                out.add(new String(x, p + 2, len * 2, StandardCharsets.UTF_16LE));
            }
        }
        return out;
    }

    private static String entry(List<String> pool, int index) {
        return index >= 0 && index < pool.size() ? pool.get(index) : "";
    }

    private static int u16(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8;
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
     * The string pool of a dex file. The header holds its size at 0x38 and its offset at 0x3C, and
     * each entry is a ULEB128 length then zero-ended modified UTF-8.
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
            while (p < dex.length && (dex[p] & 0x80) != 0) {   // skips the ULEB128 length
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
     * Appends the base64 values of a Compose Multiplatform {@code .cvr} resource file, decoded, one
     * per line.
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
                        // not base64, so not a text
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

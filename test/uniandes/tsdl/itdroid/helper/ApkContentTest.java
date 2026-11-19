package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The texts read from an APK file, the strings of its code and the readable runs of its files. */
class ApkContentTest {

    @TempDir
    Path tmp;

    /** A dex with just a header and a string pool holding the given strings. */
    private static byte[] dex(String... strings) throws Exception {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int base = 0x70 + 4 * strings.length;
        int[] offsets = new int[strings.length];
        for (int i = 0; i < strings.length; i++) {
            offsets[i] = base + data.size();
            byte[] b = strings[i].getBytes(StandardCharsets.UTF_8);
            data.write(strings[i].length());   // ULEB128 length, one byte for these short strings
            data.write(b);
            data.write(0);
        }
        byte[] out = new byte[base + data.size()];
        byte[] magic = "dex\n035\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, out, 0, magic.length);
        put(out, 0x38, strings.length);
        put(out, 0x3C, 0x70);
        for (int i = 0; i < strings.length; i++) {
            put(out, 0x70 + 4 * i, offsets[i]);
        }
        System.arraycopy(data.toByteArray(), 0, out, base, data.size());
        return out;
    }

    private static void put(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
        b[at + 2] = (byte) (v >> 16);
        b[at + 3] = (byte) (v >> 24);
    }

    private Path apk() throws Exception {
        Path apk = tmp.resolve("app.apk");
        try (OutputStream file = Files.newOutputStream(apk); ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.write(dex("Select Location", "Lcom/example/Main;", "Enter Promo Code"));
            zip.putNextEntry(new ZipEntry("classes2.dex"));
            zip.write(dex("Wallpaper Priority: %d"));
            zip.putNextEntry(new ZipEntry("assets/public/index.js"));
            zip.write("const t={songs:\"Songs\",playlists:\"Playlists\",openMenu:\"Open menu\"};".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("assets/images/logo.png"));
            zip.write("Not a text, a picture".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("lib/arm64-v8a/libapp.so"));
            zip.write(new byte[] { 0, 1, 2, 'G', 'o', 'o', 'd', ' ', 'm', 'o', 'r', 'n', 'i', 'n', 'g', 0, 5 });
            zip.putNextEntry(new ZipEntry("lib/x86_64/libapp.so"));
            zip.write(new byte[] { 0, 'S', 'a', 'm', 'e', ' ', 't', 'e', 'x', 't', 's', 0 });
            zip.closeEntry();
        }
        return apk;
    }

    @Test
    void theStringsOfEveryDexOfTheApp() throws Exception {
        ApkContent content = ApkContent.read(apk().toString());

        assertEquals(List.of("Select Location", "Lcom/example/Main;", "Enter Promo Code", "Wallpaper Priority: %d"), content.codeStrings);
    }

    @Test
    void theReadableContentOfItsFilesInLowerCaseButNotOfItsPictures() throws Exception {
        ApkContent content = ApkContent.read(apk().toString());

        assertTrue(content.files.contains("open menu"), "the texts of a web interface in the assets");
        assertTrue(content.files.contains("good morning"), "a native library, where Flutter keeps its texts");
        assertFalse(content.files.contains("same texts"), "the libraries of one ABI are enough");
        assertFalse(content.files.contains("a picture"));
    }

    @Test
    void theTextsOfComposeMultiplatformAndTheNamesOfTheFiles() throws Exception {
        Path apk = tmp.resolve("compose.apk");
        String sign = java.util.Base64.getEncoder().encodeToString("Sign in with Apple".getBytes(StandardCharsets.UTF_8));
        String arabic = java.util.Base64.getEncoder().encodeToString("Only a translation".getBytes(StandardCharsets.UTF_8));
        try (OutputStream file = Files.newOutputStream(apk); ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry("assets/composeResources/app/values/strings.commonMain.cvr"));
            zip.write(("version:0\nstring|sign_in_apple|" + sign + "\n").getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("assets/composeResources/app/values-ar/strings.commonMain.cvr"));
            zip.write(("version:0\nstring|sign_in_apple|" + arabic + "\n").getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("assets/presets/Midnight.lwp"));
            zip.write(new byte[] { 0, 1, 2 });
            zip.closeEntry();
        }

        ApkContent content = ApkContent.read(apk.toString());

        assertTrue(content.files.contains("sign in with apple"), "the default texts, decoded from base64");
        assertFalse(content.files.contains("only a translation"), "the translations are not needed");
        assertTrue(content.files.contains("midnight"), "the name of a file, which an app can show as a label");
    }

    @Test
    void notADexIsNoStrings() {
        assertTrue(ApkContent.dexStrings("hello".getBytes(StandardCharsets.UTF_8)).isEmpty());
    }

    private static void u16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
    }

    private static void u32(ByteArrayOutputStream out, int v) {
        u16(out, v & 0xffff);
        u16(out, (v >>> 16) & 0xffff);
    }

    /**
     * A binary manifest as aapt writes it, a string pool in UTF-16 or UTF-8, a resource map and the
     * manifest element with versionCode and package.
     */
    private static byte[] manifest(String pkg, boolean utf8) {
        List<String> strings = List.of("versionCode", "package", "manifest", pkg);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[strings.size()];
        for (int i = 0; i < strings.size(); i++) {
            offsets[i] = data.size();
            String s = strings.get(i);
            if (utf8) {
                byte[] b = s.getBytes(StandardCharsets.UTF_8);
                data.write(s.length());
                data.write(b.length);
                data.writeBytes(b);
                data.write(0);
            } else {
                u16(data, s.length());
                data.writeBytes(s.getBytes(StandardCharsets.UTF_16LE));
                u16(data, 0);
            }
        }
        while (data.size() % 4 != 0) {
            data.write(0);
        }
        ByteArrayOutputStream pool = new ByteArrayOutputStream();
        int poolHeader = 28;
        u16(pool, 0x0001);
        u16(pool, poolHeader);
        u32(pool, poolHeader + 4 * strings.size() + data.size());
        u32(pool, strings.size());
        u32(pool, 0);
        u32(pool, utf8 ? 0x100 : 0);
        u32(pool, poolHeader + 4 * strings.size());
        u32(pool, 0);
        for (int o : offsets) {
            u32(pool, o);
        }
        pool.writeBytes(data.toByteArray());

        ByteArrayOutputStream element = new ByteArrayOutputStream();
        u16(element, 0x0102);
        u16(element, 16);
        u32(element, 16 + 20 + 2 * 20);
        u32(element, 1);
        u32(element, -1);
        u32(element, -1);
        u32(element, 2);          // manifest
        u16(element, 20);
        u16(element, 20);
        u16(element, 2);
        u16(element, 0);
        u16(element, 0);
        u16(element, 0);
        int[][] attributes = { {0, -1, 0x10, 7}, {1, 3, 0x03, 3} };   // name, raw value, type, data
        for (int[] a : attributes) {
            u32(element, -1);
            u32(element, a[0]);
            u32(element, a[1]);
            u16(element, 8);
            element.write(0);
            element.write(a[2]);
            u32(element, a[3]);
        }

        ByteArrayOutputStream file = new ByteArrayOutputStream();
        u16(file, 0x0003);
        u16(file, 8);
        u32(file, 8 + pool.size() + 12 + element.size());
        file.writeBytes(pool.toByteArray());
        u16(file, 0x0180);
        u16(file, 8);
        u32(file, 12);
        u32(file, 0x0101021b);
        file.writeBytes(element.toByteArray());
        return file.toByteArray();
    }

    @Test
    void thePackageIsReadFromTheBinaryManifestInBothEncodings() {
        assertEquals("org.example.shop", ApkContent.manifestPackage(manifest("org.example.shop", false)));
        assertEquals("org.example.shop", ApkContent.manifestPackage(manifest("org.example.shop", true)));
        assertEquals("", ApkContent.manifestPackage("<manifest package=\"a.b\"/>".getBytes(StandardCharsets.UTF_8)), "not a binary XML");
    }

    @Test
    void thePackageOfAnApkFileOrEmptyWhenItCannotBeRead() throws Exception {
        Path apk = tmp.resolve("app.apk");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(apk))) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write(manifest("com.adermark.forestpondfree", false));
            zip.closeEntry();
        }
        assertEquals("com.adermark.forestpondfree", ApkContent.packageName(apk.toString()));
        assertEquals("", ApkContent.packageName(tmp.resolve("missing.apk").toString()));
    }
}

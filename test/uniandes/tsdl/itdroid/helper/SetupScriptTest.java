package uniandes.tsdl.itdroid.helper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The file of adb commands that leaves content on the device before an app is explored (exploration.setup). */
class SetupScriptTest {

    @TempDir
    Path tmp;

    @Test
    void oneCommandPerLineWithoutCommentsOrBlankLines() throws IOException {
        Path file = tmp.resolve("setup.txt");
        Files.writeString(file, "# a sample for the player\n\npush data/sample.mp3 /sdcard/Music/sample.mp3\n"
                + "   shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/sample.mp3  \n"
                + "adb shell settings put system font_scale 1.0\n", StandardCharsets.UTF_8);

        List<String[]> commands = SetupScript.load(file.toString());

        assertEquals(3, commands.size());
        assertArrayEquals(new String[] {"push", "data/sample.mp3", "/sdcard/Music/sample.mp3"}, commands.get(0));
        assertEquals("shell", commands.get(1)[0]);
        assertEquals(7, commands.get(1).length);
        assertArrayEquals(new String[] {"shell", "settings", "put", "system", "font_scale", "1.0"}, commands.get(2),
                "a line copied with its adb in front works the same");
    }

    @Test
    void anArgumentWithSpacesGoesBetweenQuotes() {
        assertArrayEquals(new String[] {"push", "C:\\My Data\\book one.mp3", "/sdcard/Download/book.mp3"},
                SetupScript.split("push \"C:\\My Data\\book one.mp3\" /sdcard/Download/book.mp3"));
        assertArrayEquals(new String[] {"shell", "content insert --uri content://settings/system --bind name:s:a"},
                SetupScript.split("shell \"content insert --uri content://settings/system --bind name:s:a\""));
        assertArrayEquals(new String[] {"shell", "echo", ""}, SetupScript.split("shell echo \"\""));
        assertEquals(0, SetupScript.split("   # nothing").length);
    }

    @Test
    void noFileIsNoSetupAndAMissingFileIsAnError() throws IOException {
        assertTrue(SetupScript.load("").isEmpty());
        assertTrue(SetupScript.load(null).isEmpty());
        IOException e = assertThrows(IOException.class, () -> SetupScript.load(tmp.resolve("missing.txt").toString()));
        assertTrue(e.getMessage().contains("exploration.setup"), e.getMessage());
    }
}

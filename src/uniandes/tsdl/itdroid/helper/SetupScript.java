package uniandes.tsdl.itdroid.helper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The commands that prepare the device for an app that shows little without content: a file with one
 * {@code adb} command per line (config key {@code exploration.setup}), run after every clean start
 * of the app, in the source language and in every translated one, so all of them start from the
 * same content.
 *
 * <pre>
 * # an audio file where the player looks for music, and the scan that makes Android list it
 * push data/sample.mp3 /sdcard/Music/sample.mp3
 * shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/sample.mp3
 * </pre>
 *
 * <p>Each line is what follows {@code adb -s <device>}. A blank line or one starting with {@code #}
 * is skipped; an argument with spaces goes between double quotes. It reaches what adb reaches:
 * files in shared storage, content providers, intents, settings. It does not sign in to an account
 * and it does not answer a screen of the app or of the system (a folder picker): that still needs a
 * person.
 */
public final class SetupScript {

    private SetupScript() {
    }

    /** The commands of the file, each as its list of arguments; empty when no file is given. */
    public static List<String[]> load(String file) throws IOException {
        List<String[]> commands = new ArrayList<>();
        if (file == null || file.trim().isEmpty()) {
            return commands;
        }
        Path path = Paths.get(file.trim());
        if (!Files.isRegularFile(path)) {
            throw new IOException("the setup file of exploration.setup does not exist: " + path.toAbsolutePath());
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String[] args = split(line);
            if (args.length > 0) {
                commands.add(args);
            }
        }
        return commands;
    }

    /** The arguments of one line; none for a blank line or a comment. A leading "adb" is dropped. */
    static String[] split(String line) {
        String text = line.trim();
        if (text.isEmpty() || text.startsWith("#")) {
            return new String[0];
        }
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any) {
                    args.add(current.toString());
                    current.setLength(0);
                    any = false;
                }
            } else {
                current.append(c);
                any = true;
            }
        }
        if (any) {
            args.add(current.toString());
        }
        if (!args.isEmpty() && args.get(0).equals("adb")) {
            args.remove(0);
        }
        return args.toArray(new String[0]);
    }
}

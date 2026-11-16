package uniandes.tsdl.itdroid.helper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the setup file of {@code exploration.setup}, one adb command per line, run after every clean
 * start of the app in every language so all runs start from the same content. Blank lines and lines
 * starting with # are skipped, and quotes group an argument with spaces.
 */
public final class SetupScript {

    private SetupScript() {
    }

    /** The commands of the file as argument lists, empty when no file is given. */
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

    /** The arguments of one line, none for a blank line or a comment, without a leading "adb". */
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

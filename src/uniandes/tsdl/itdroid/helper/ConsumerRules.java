package uniandes.tsdl.itdroid.helper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The table that says what a method does with a string it receives: shows it, logs it, throws it,
 * uses it as data, or passes it on. Read from {@code hardcoded-consumers.txt} (packaged next to
 * this class); a file with the same name in the settings folder adds rules that are tried first.
 */
public final class ConsumerRules {

    public static final String FILE_NAME = "hardcoded-consumers.txt";

    /** What a rule says about a callee. */
    public enum Kind {
        UI, COMPOSABLE, LOG, EXCEPTION, DATA, COMPILER, BUILDER, PASSTHROUGH
    }

    private static final class Rule {
        final Kind kind;
        final Pattern pattern;

        Rule(Kind kind, Pattern pattern) {
            this.kind = kind;
            this.pattern = pattern;
        }
    }

    private final List<Rule> rules = new ArrayList<>();

    private ConsumerRules() {
    }

    /** The packaged rules alone. */
    public static ConsumerRules defaults() {
        return load(null);
    }

    /** The rules of {@code <settingsDir>/hardcoded-consumers.txt}, if that file exists, then the packaged ones. */
    public static ConsumerRules load(String settingsDir) {
        ConsumerRules r = new ConsumerRules();
        if (settingsDir != null && !settingsDir.isEmpty()) {
            Path custom = Paths.get(settingsDir, FILE_NAME);
            if (Files.isRegularFile(custom)) {
                try (Reader in = Files.newBufferedReader(custom, StandardCharsets.UTF_8)) {
                    r.read(in, custom.toString());
                } catch (IOException e) {
                    System.out.println("WARNING: could not read " + custom + " (" + e.getMessage() + "); using the built-in rules only");
                }
            }
        }
        try (InputStream in = ConsumerRules.class.getResourceAsStream(FILE_NAME)) {
            if (in == null) {
                throw new IllegalStateException(FILE_NAME + " is missing from the ITDroid jar");
            }
            r.read(new InputStreamReader(in, StandardCharsets.UTF_8), FILE_NAME);
        } catch (IOException e) {
            throw new IllegalStateException("could not read the packaged " + FILE_NAME, e);
        }
        return r;
    }

    private void read(Reader reader, String source) throws IOException {
        BufferedReader in = new BufferedReader(reader);
        String line;
        int n = 0;
        while ((line = in.readLine()) != null) {
            n++;
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String[] parts = t.split("\\s+", 2);
            if (parts.length < 2) {
                System.out.println("WARNING: " + source + ":" + n + " ignored (expected '<category> <regular expression>')");
                continue;
            }
            try {
                rules.add(new Rule(Kind.valueOf(parts[0].toUpperCase()), Pattern.compile(parts[1].trim())));
            } catch (IllegalArgumentException e) {
                // unknown category, or a PatternSyntaxException (which is an IllegalArgumentException)
                String why = e instanceof PatternSyntaxException ? "invalid regular expression" : "unknown category '" + parts[0] + "'";
                System.out.println("WARNING: " + source + ":" + n + " ignored (" + why + ")");
            }
        }
    }

    /** What the callee (as written in smali: {@code Lpkg/Class;->method(args)ret}) does with a string, or null when no rule knows it. */
    public Kind classify(String callee) {
        for (Rule rule : rules) {
            if (rule.pattern.matcher(callee).find()) {
                return rule.kind;
            }
        }
        return null;
    }

    public int size() {
        return rules.size();
    }
}

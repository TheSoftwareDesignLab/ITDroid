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
 * Rules saying what a method does with a string it receives, read from {@code hardcoded-consumers.txt}.
 * The file named by hardcoded.extraRules adds rules that are tried first.
 */
public final class ConsumerRules {

    public static final String FILE_NAME = "hardcoded-consumers.txt";

    /** What a rule says about a callee. A setter shows the text unless it is a method of the app with other arguments. */
    public enum Kind {
        UI, SETTER, COMPOSABLE, LOG, EXCEPTION, DATA, COMPILER, BUILDER, PASSTHROUGH
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

    /** The rules of the hardcoded.extraRules file when one is given, then the packaged ones. */
    public static ConsumerRules load(String extraRulesFile) {
        ConsumerRules r = new ConsumerRules();
        if (extraRulesFile != null && !extraRulesFile.isEmpty()) {
            Path custom = Paths.get(extraRulesFile);
            if (!Files.isRegularFile(custom)) {
                throw new IllegalArgumentException("hardcoded.extraRules names a file that does not exist: " + custom.toAbsolutePath());
            }
            try (Reader in = Files.newBufferedReader(custom, StandardCharsets.UTF_8)) {
                r.read(in, custom.toString());
            } catch (IOException e) {
                System.out.println("WARNING: could not read " + custom + " (" + e.getMessage() + "); using the built-in rules only");
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
                // unknown category or invalid pattern, both are IllegalArgumentException
                String why = e instanceof PatternSyntaxException ? "invalid regular expression" : "unknown category '" + parts[0] + "'";
                System.out.println("WARNING: " + source + ":" + n + " ignored (" + why + ")");
            }
        }
    }

    /** What the callee, written as in smali, does with a string, or null when no rule knows it. */
    public Kind classify(String callee) {
        for (Rule rule : rules) {
            if (rule.pattern.matcher(callee).find()) {
                return rule.kind;
            }
        }
        return null;
    }
}

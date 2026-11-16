package uniandes.tsdl.itdroid.helper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/**
 * Keeps the paths of the machine out of a results folder so it can be moved or published. Files are
 * named relative to the folder, and other absolute paths start with {@code <output>},
 * {@code <itdroid>} or {@code ~} instead.
 */
public final class PortablePaths {

    private final Path output;
    /** Pattern and replacement pairs, longest path first so the most specific one wins. */
    private final List<Object[]> replacements = new ArrayList<>();

    public PortablePaths(Path outputFolder) {
        this(outputFolder, property("user.dir"), property("user.home"));
    }

    PortablePaths(Path outputFolder, Path workingFolder, Path home) {
        output = outputFolder.toAbsolutePath().normalize();
        List<Object[]> named = new ArrayList<>();
        named.add(new Object[] {output, "<output>"});
        if (workingFolder != null) {
            named.add(new Object[] {workingFolder.toAbsolutePath().normalize(), "<itdroid>"});
        }
        if (home != null) {
            named.add(new Object[] {home.toAbsolutePath().normalize(), "~"});
        }
        named.sort((a, b) -> Integer.compare(b[0].toString().length(), a[0].toString().length()));
        for (Object[] entry : named) {
            Pattern pattern = pattern((Path) entry[0]);
            if (pattern != null) {
                replacements.add(new Object[] {pattern, entry[1]});
            }
        }
    }

    private static Path property(String name) {
        String value = System.getProperty(name);
        return value == null || value.isEmpty() ? null : Paths.get(value);
    }

    /**
     * Matches the whole path with either separator, ignoring case on Windows, so "ana" never matches
     * inside "anabel" or "ana.old". A bare root is left out since it would match every path.
     */
    private static Pattern pattern(Path path) {
        int names = path.getNameCount();
        if (names < 1) {
            return null;
        }
        StringBuilder regex = new StringBuilder();
        if (path.getRoot() != null) {
            String root = path.getRoot().toString().replace('\\', '/');
            while (root.endsWith("/")) {
                root = root.substring(0, root.length() - 1);
            }
            regex.append(Pattern.quote(root));
        }
        for (int i = 0; i < names; i++) {
            regex.append("[\\\\/]+").append(Pattern.quote(path.getName(i).toString()));
        }
        regex.append("(?!\\w|[.-]\\w)");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Pattern.compile(regex.toString(), Pattern.UNICODE_CHARACTER_CLASS
                | (windows ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0));
    }

    /** The path from the results folder with forward slashes, or the bare file name when outside it. */
    public String relative(String file) {
        if (file == null || file.isEmpty()) {
            return "";
        }
        Path path = Paths.get(file).toAbsolutePath().normalize();
        if (path.startsWith(output) && !path.equals(output)) {
            return output.relativize(path).toString().replace('\\', '/');
        }
        return path.getFileName() == null ? "" : path.getFileName().toString();
    }

    /** The text with the start of every known absolute path replaced. */
    public String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (Object[] replacement : replacements) {
            out = ((Pattern) replacement[0]).matcher(out).replaceAll(Matcher.quoteReplacement((String) replacement[1]));
        }
        return out;
    }

    /** A scrubbed copy of a JSON value, leaving the original untouched. */
    @SuppressWarnings("unchecked")
    public Object scrub(Object json) {
        if (json instanceof String) {
            return scrub((String) json);
        }
        if (json instanceof Map) {
            JSONObject copy = new JSONObject();
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) json).entrySet()) {
                copy.put(e.getKey(), scrub(e.getValue()));
            }
            return copy;
        }
        if (json instanceof List) {
            JSONArray copy = new JSONArray();
            for (Object item : (List<Object>) json) {
                copy.add(scrub(item));
            }
            return copy;
        }
        return json;
    }

    /** Hex SHA-256 of a file, which identifies it without saying where it was. */
    public static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
    }
}

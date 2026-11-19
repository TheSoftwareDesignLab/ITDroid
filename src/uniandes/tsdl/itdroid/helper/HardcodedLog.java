package uniandes.tsdl.itdroid.helper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Writes {@code hardcoded_<lang>.csv}, the record of what became of every hardcoded string for one
 * language, one row per smali line or XML attribute.
 */
public final class HardcodedLog {

    /** Moved to a resource and translated. */
    public static final String TRANSLATED = "translated";
    /** Moved to a resource, but the text stays in the source language for this language. */
    public static final String KEPT_IN_SOURCE = "kept in source";
    /** Chosen for translation, but it could not be moved out of the code. */
    public static final String NOT_MOVED = "not moved";
    /** Not text for the user, or a use the policy does not translate. */
    public static final String NOT_TRANSLATED = "not translated";

    private static final String HEADER = "kind;text;translation;outcome;reason;file;line;class;method;use;via";
    private static final String USE_NOT_KNOWN = "use not known: ";

    private HardcodedLog() {
    }

    /**
     * Writes the record and returns the rows written by outcome.
     *
     * @param keptInSource resource text to the reason it stayed in the source language for this language
     * @param revertReason why the translations of the code literals were dropped for this language, or null
     */
    public static Map<String, Integer> write(Path file, StringInventory inventory, String lang, Map<String, String> keptInSource,
            String revertReason) throws IOException {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(HEADER);
            w.newLine();
            for (StringInventory.Entry e : inventory.entries()) {
                if (StringInventory.KIND_HARDCODED.equals(e.kind)) {
                    for (SmaliScan.Occurrence p : e.codePlaces) {
                        String[] outcome;
                        if (e.extractedPlaces.contains(p)) {
                            outcome = revertReason != null ? new String[] {"", KEPT_IN_SOURCE, revertReason}
                                    : asResource(inventory, lang, e, keptInSource);
                        } else if (e.notMovedPlaces.contains(p)) {
                            outcome = new String[] {"", NOT_MOVED, e.notMovedReason};
                        } else if (!e.translatable) {
                            // when kept for its use, each row says what its place does
                            outcome = new String[] {"", NOT_TRANSLATED, keptForItsUse(e.skipReason) ? useReason(p.consumer) : e.skipReason};
                        } else {
                            outcome = new String[] {"", NOT_TRANSLATED, "this place: " + p.consumer.description};
                        }
                        row(w, "code", e.text, outcome, p.file, p.line, p.className, p.method, p.consumer.label, p.via);
                        counts.merge(outcome[1], 1, Integer::sum);
                    }
                } else if (StringInventory.KIND_HARDCODED_XML.equals(e.kind)) {
                    for (XmlLiterals.Literal p : e.xmlPlaces) {
                        String[] outcome = e.extractedTo != null ? asResource(inventory, lang, e, keptInSource)
                                : new String[] {"", NOT_TRANSLATED, e.skipReason};
                        row(w, "xml", e.text, outcome, p.file, p.line, p.element + (p.id.isEmpty() ? "" : " " + p.id), "",
                                StringInventory.CONSUMER_XML, p.attribute);
                        counts.merge(outcome[1], 1, Integer::sum);
                    }
                }
            }
        }
        return counts;
    }

    /** Translation, outcome and reason of a literal moved to a resource, from the translation that resource got. */
    private static String[] asResource(StringInventory inventory, String lang, StringInventory.Entry e, Map<String, String> keptInSource) {
        String key = e.resourceText != null ? e.resourceText : e.text;
        String translation = inventory.translation(lang, key);
        if (translation != null) {
            return new String[] {translation, TRANSLATED, "@string/" + e.extractedTo};
        }
        String why = keptInSource == null ? null : keptInSource.get(key);
        return new String[] {"", KEPT_IN_SOURCE, "@string/" + e.extractedTo + ": " + (why != null ? why : "no translation for this language")};
    }

    /** True when a literal is kept because of what the code does with it, not because of what it is. */
    private static boolean keptForItsUse(String skipReason) {
        if (skipReason == null) {
            return false;
        }
        if (skipReason.startsWith(USE_NOT_KNOWN)) {
            return true;
        }
        for (SmaliScan.Consumer c : SmaliScan.Consumer.values()) {
            if (c.description.equals(skipReason)) {
                return true;
            }
        }
        return false;
    }

    private static String useReason(SmaliScan.Consumer consumer) {
        return consumer.neverShown() ? consumer.description : USE_NOT_KNOWN + consumer.description;
    }

    private static void row(BufferedWriter w, String kind, String text, String[] outcome, String file, int line, String className,
            String method, String use, String via) throws IOException {
        w.write(String.join(";", kind, csv(text), csv(outcome[0] == null ? "" : outcome[0]), outcome[1],
                csv(outcome[2] == null ? "" : outcome[2]), csv(file), String.valueOf(line), csv(className), csv(method), use, csv(via)));
        w.newLine();
    }

    /** A quoted CSV field with quotes doubled and line breaks written as backslash n. */
    private static String csv(String s) {
        return "\"" + s.replace("\"", "\"\"").replace("\r", "").replace("\n", "\\n") + "\"";
    }
}

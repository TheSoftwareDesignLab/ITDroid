package uniandes.tsdl.itdroid.helper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The reviewable record of what was done with every hardcoded string for one language:
 * {@code hardcoded_<lang>.csv}, one row per place (a line of smali or an attribute of the XML), so
 * each row can be traced back to where the text is written.
 */
public final class HardcodedLog {

    /** What happened to a place. */
    public static final String PATCHED = "patched";
    public static final String EXTRACTED = "extracted";
    public static final String REVERTED = "reverted";
    public static final String NOT_TRANSLATED = "not translated";

    private static final String HEADER = "kind;text;translation;action;reason;file;line;class;method;use;via";
    private static final String USE_NOT_KNOWN = "use not known: ";

    private HardcodedLog() {
    }

    /**
     * Writes the record.
     *
     * @param translations source text to translation, for the literals of the code that were sent to the translator
     * @param applied the patches made in the smali for this language
     * @param revertReason when the build with the translated literals of the code had to be
     *        discarded, why; else null. The patched and extracted rows of the code are then written
     *        as reverted.
     * @return rows written, by action
     */
    public static Map<String, Integer> write(Path file, StringInventory inventory, String lang, Map<String, String> translations,
            List<SmaliPatcher.Patch> applied, String revertReason) throws IOException {
        Set<SmaliScan.Occurrence> patched = new HashSet<>();
        for (SmaliPatcher.Patch p : applied) {
            patched.add(p.place);
        }
        Map<String, Integer> counts = new java.util.TreeMap<>();
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(HEADER);
            w.newLine();
            for (StringInventory.Entry e : inventory.entries()) {
                if (StringInventory.KIND_HARDCODED.equals(e.kind)) {
                    String translation = translations.get(e.text);
                    String asResource = e.resourceText == null ? null : inventory.translation(lang, e.resourceText);
                    for (SmaliScan.Occurrence p : e.codePlaces) {
                        String action;
                        String reason;
                        if (e.extractedPlaces.contains(p)) {
                            // moved to a resource: translated with the resources, in every APK
                            action = revertReason == null ? EXTRACTED : REVERTED;
                            reason = revertReason != null ? revertReason
                                    : "@string/" + e.extractedTo + (asResource == null ? " (no translation for this language)" : "");
                            row(w, "code", e.text, asResource == null ? "" : asResource, action, reason, p.file, p.line, p.className,
                                    p.method, p.consumer.label, p.via);
                            counts.merge(action, 1, Integer::sum);
                            continue;
                        }
                        if (patched.contains(p)) {
                            action = revertReason == null ? PATCHED : REVERTED;
                            reason = revertReason == null ? "" : revertReason;
                        } else {
                            action = NOT_TRANSLATED;
                            if (!e.translatable) {
                                // kept for what it is (not text, the shape of data, the app's name), or
                                // for what the code does with it: then each row says what its place does
                                reason = keptForItsUse(e.skipReason) ? useReason(p.consumer) : e.skipReason;
                            } else if (!e.patchPlaces.contains(p)) {
                                reason = "this place: " + p.consumer.description;
                            } else if (translation == null) {
                                reason = "no translation obtained";
                            } else if (translation.equals(e.text)) {
                                reason = "the translation equals the source";
                            } else {
                                reason = "the line no longer held the literal";
                            }
                        }
                        row(w, "code", e.text, patched.contains(p) ? translation : "", action, reason, p.file, p.line, p.className,
                                p.method, p.consumer.label, p.via);
                        counts.merge(action, 1, Integer::sum);
                    }
                } else if (StringInventory.KIND_HARDCODED_XML.equals(e.kind)) {
                    String translation = e.resourceText == null ? null : inventory.translation(lang, e.resourceText);
                    for (XmlLiteralFinder.Literal p : e.xmlPlaces) {
                        boolean extracted = e.extractedTo != null;
                        String action = extracted ? EXTRACTED : NOT_TRANSLATED;
                        String reason = extracted ? "@string/" + e.extractedTo + (translation == null ? " (no translation for this language)" : "")
                                : e.skipReason;
                        row(w, "xml", e.text, extracted && translation != null ? translation : "", action, reason, p.file, p.line,
                                p.element + (p.id.isEmpty() ? "" : " " + p.id), "", StringInventory.CONSUMER_XML, p.attribute);
                        counts.merge(action, 1, Integer::sum);
                    }
                }
            }
        }
        return counts;
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

    private static void row(BufferedWriter w, String kind, String text, String translation, String action, String reason, String file,
            int line, String className, String method, String use, String via) throws IOException {
        w.write(String.join(";", kind, csv(text), csv(translation == null ? "" : translation), action, csv(reason == null ? "" : reason),
                csv(file), String.valueOf(line), csv(className), csv(method), use, csv(via)));
        w.newLine();
    }

    /** A CSV field: quoted, quotes doubled, line breaks written as the two characters backslash and n. */
    private static String csv(String s) {
        return "\"" + s.replace("\"", "\"\"").replace("\r", "").replace("\n", "\\n") + "\"";
    }
}

package uniandes.tsdl.itdroid.helper;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import org.json.simple.JSONObject;

/**
 * Do the default strings of the app look like they are written in the source language of the run?
 *
 * <p>The source language is the same for every app ({@code defaultLng} of settings.properties). An
 * app whose default strings are in another language (a dictionary of Esperanto, an app made for one
 * country) is then translated from the wrong language: the model is told the text is English and
 * what comes back is a transliteration. This check does not use the model, which may be one that
 * only translates: it counts, among the sentences of the app, how many have one of the commonest
 * little words of the language (the, and, of, to...). Sentences of that language nearly always do.
 *
 * <p>It warns; it does not change the source language, and it does not say which language the strings
 * are in: related languages share their little words (Esperanto has the "la", "de" and "por" of
 * Spanish), so a guess would be wrong as often as right. It only knows the languages listed here.
 */
public final class SourceLanguageCheck {

    /** A sentence, for this check: a text of at least this many words. */
    static final int MIN_WORDS = 3;
    /** With fewer sentences than this the share says nothing. */
    static final int MIN_SENTENCES = 8;
    /** Below this share of sentences with a little word of the language, the strings do not look like it. */
    static final double EXPECTED_SHARE = 0.3;

    private static final Map<String, Set<String>> LITTLE_WORDS = new LinkedHashMap<>();
    static {
        put("en", "the and to of in is for with your you this that on not are be it from or an as at by can will");
        put("es", "el la los las de que y en un una para con por no se su es al del tu este esta como");
        put("fr", "le la les de des et en un une pour avec pas vous votre est sur ce cette dans au du");
        put("de", "der die das und zu den nicht mit für ist ein eine auf von sie ihr oder im dem wird");
        put("pt", "o os as de que e em um uma para com não por seu sua é do da no na");
        put("it", "il la le di che e in un una per con non è del della su tuo questo");
    }

    private static void put(String language, String words) {
        LITTLE_WORDS.put(language, new HashSet<>(Arrays.asList(words.split(" "))));
    }

    /** What the check found. */
    public static final class Result {
        /** The language the run takes the strings to be in. */
        public final String configured;
        /** Sentences looked at. */
        public final int sentences;
        /** Share of them with a little word of the configured language, 0 to 1. */
        public final double share;
        /** True when the strings do not look like the configured language. */
        public final boolean mismatch;

        Result(String configured, int sentences, double share, boolean mismatch) {
            this.configured = configured;
            this.sentences = sentences;
            this.share = share;
            this.mismatch = mismatch;
        }

        @SuppressWarnings("unchecked")
        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("configured", configured);
            o.put("sentences", (long) sentences);
            o.put("share", Math.round(share * 100) / 100.0);
            o.put("mismatch", mismatch);
            return o;
        }

        /** One sentence for the console and the report; empty when there is nothing to say. */
        public String message() {
            if (!mismatch) {
                return "";
            }
            return "the default strings of the app do not look like '" + configured + "' (" + Math.round(share * 100) + " % of its "
                    + sentences + " sentences have a common word of that language)"
                    + ". They are translated as '" + configured + "' all the same: set defaultLng in settings.properties to the"
                    + " language of the app if it is another one.";
        }
    }

    private SourceLanguageCheck() {
    }

    /** Checks the default {@code strings.xml} of the app against the configured source language. */
    public static Result of(String defaultStringsXml, String configured) {
        List<String> texts = new ArrayList<>();
        try {
            Document document = new SAXBuilder().build(new File(defaultStringsXml));
            for (Element e : document.getRootElement().getChildren("string")) {
                if ("false".equals(e.getAttributeValue("translatable"))) {
                    continue;
                }
                texts.add(e.getValue());
            }
        } catch (Exception e) {
            // no readable strings.xml: nothing to check
        }
        return of(texts, configured);
    }

    static Result of(List<String> texts, String configured) {
        String language = configured == null ? "" : configured.toLowerCase().split("[-_]")[0];
        List<Set<String>> sentences = new ArrayList<>();
        for (String text : texts) {
            Set<String> words = new HashSet<>();
            int count = 0;
            for (String w : text.toLowerCase().split("[^\\p{L}']+")) {
                if (!w.isEmpty()) {
                    words.add(w);
                    count++;
                }
            }
            if (count >= MIN_WORDS) {
                sentences.add(words);
            }
        }
        Set<String> own = LITTLE_WORDS.get(language);
        if (own == null || sentences.size() < MIN_SENTENCES) {
            return new Result(configured, sentences.size(), 0, false);
        }
        double share = share(sentences, own);
        return new Result(configured, sentences.size(), share, share < EXPECTED_SHARE);
    }

    private static double share(List<Set<String>> sentences, Set<String> littleWords) {
        int with = 0;
        for (Set<String> words : sentences) {
            for (String w : words) {
                if (littleWords.contains(w)) {
                    with++;
                    break;
                }
            }
        }
        return (double) with / sentences.size();
    }
}

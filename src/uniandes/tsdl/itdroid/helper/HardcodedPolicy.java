package uniandes.tsdl.itdroid.helper;

import java.util.Locale;

/**
 * Which hardcoded literals of the code are translated, by what the code does with them (config key
 * {@code hardcoded.translate}). A translation model rewrites anything it is given, and a translated
 * preference key, SQL fragment or HTTP header changes what the app does, so the choice is made per
 * place, from the use the scan found there.
 */
public enum HardcodedPolicy {

    /**
     * Only the places where the code is seen handing the text to the interface: a framework call
     * that shows it, or a composable function. The default.
     */
    UI("ui"),
    /**
     * Also the places whose use the scan cannot tell (the text goes to a method of the app, a
     * field, an array, a returned value). Reaches text that is shown indirectly, at the price of
     * translating some data. Never logs, exception messages, data APIs or compiler strings.
     */
    UI_AND_UNKNOWN("ui+unknown"),
    /** Every place, logs and data included: what ITDroid did before uses were looked at. For comparison only. */
    ALL("all");

    public static final HardcodedPolicy DEFAULT = UI;

    /** The value as written in the configuration. */
    public final String key;

    HardcodedPolicy(String key) {
        this.key = key;
    }

    /** True when a literal used this way is translated. */
    public boolean translates(SmaliScan.Consumer consumer) {
        switch (this) {
            case UI:
                return consumer == SmaliScan.Consumer.UI || consumer == SmaliScan.Consumer.COMPOSABLE;
            case UI_AND_UNKNOWN:
                return !consumer.neverShown();
            default:
                return true;
        }
    }

    /** Reads the configuration value; blank gives the default. */
    public static HardcodedPolicy parse(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return DEFAULT;
        }
        for (HardcodedPolicy p : values()) {
            if (p.key.equals(v)) {
                return p;
            }
        }
        throw new IllegalArgumentException("hardcoded.translate must be ui, ui+unknown or all; got '" + value + "'");
    }
}

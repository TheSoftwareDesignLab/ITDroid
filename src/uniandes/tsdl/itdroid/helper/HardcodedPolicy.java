package uniandes.tsdl.itdroid.helper;

import java.util.Locale;

/**
 * Which hardcoded literals are translated, by how the code uses them (config key {@code hardcoded.translate}).
 * A translated preference key, SQL fragment or HTTP header would change what the app does.
 */
public enum HardcodedPolicy {

    /** Only text handed to the interface by a framework call or a composable. The default. */
    UI("ui"),
    /** Also text whose use the scan cannot tell, at the price of translating some data. */
    UI_AND_UNKNOWN("ui+unknown"),
    /** Every literal, logs and data included, as ITDroid did before. For comparison only. */
    ALL("all");

    public static final HardcodedPolicy DEFAULT = UI;

    /** The value as written in the configuration. */
    public final String key;

    HardcodedPolicy(String key) {
        this.key = key;
    }

    /** True when a literal used this way is translated, as {@link Translatability} decides. */
    public boolean translates(SmaliScan.Consumer consumer) {
        return Translatability.translatesUse(this, consumer);
    }

    /** Reads the configuration value, blank gives the default. */
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

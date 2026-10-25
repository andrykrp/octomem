package ru.octomem.stats;

/**
 * Nonnegative current statistics that survive counter resets.
 * Time values are supplied by the caller, not sampled by the registry.
 *
 * @since 1.0
 */
public enum Gauge {
    /** Currently open connections. */
    CURR_CONNECTIONS("curr_connections"),
    /** Allocated connection structures. */
    CONNECTION_STRUCTURES("connection_structures"),
    /** Currently stored items. */
    CURR_ITEMS("curr_items"),
    /** Current item bytes. */
    BYTES("bytes"),
    /** Caller-supplied process uptime in seconds. */
    UPTIME("uptime"),
    /** Caller-supplied Unix time in seconds. */
    TIME("time");

    private final String wireName;

    Gauge(String wireName) {
        this.wireName = wireName;
    }

    /**
     * Returns the protocol field name.
     *
     * @return lowercase ASCII field name
     */
    public String wireName() {
        return wireName;
    }
}

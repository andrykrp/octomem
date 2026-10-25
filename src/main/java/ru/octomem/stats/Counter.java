package ru.octomem.stats;

/**
 * Monotonic general statistics, reset together and saturated at {@link Long#MAX_VALUE}.
 *
 * @since 1.0
 */
public enum Counter {
    /** Total accepted connections. */
    TOTAL_CONNECTIONS("total_connections"),
    /** Connection scheduling yields. */
    CONN_YIELDS("conn_yields"),
    /** Retrieval commands. */
    CMD_GET("cmd_get"),
    /** Storage commands. */
    CMD_SET("cmd_set"),
    /** Flush commands. */
    CMD_FLUSH("cmd_flush"),
    /** Touch commands. */
    CMD_TOUCH("cmd_touch"),
    /** Retrieval hits. */
    GET_HITS("get_hits"),
    /** Retrieval misses. */
    GET_MISSES("get_misses"),
    /** Successful deletions. */
    DELETE_HITS("delete_hits"),
    /** Missing deletion targets. */
    DELETE_MISSES("delete_misses"),
    /** Increment hits. */
    INCR_HITS("incr_hits"),
    /** Increment misses. */
    INCR_MISSES("incr_misses"),
    /** Decrement hits. */
    DECR_HITS("decr_hits"),
    /** Decrement misses. */
    DECR_MISSES("decr_misses"),
    /** Successful compare-and-swap operations. */
    CAS_HITS("cas_hits"),
    /** Missing compare-and-swap targets. */
    CAS_MISSES("cas_misses"),
    /** Compare-and-swap token mismatches. */
    CAS_BADVAL("cas_badval"),
    /** Touch hits. */
    TOUCH_HITS("touch_hits"),
    /** Touch misses. */
    TOUCH_MISSES("touch_misses"),
    /** Total stored items. */
    TOTAL_ITEMS("total_items"),
    /** Reclaimed expired items. */
    RECLAIMED("reclaimed"),
    /** Items expired before being fetched. */
    EXPIRED_UNFETCHED("expired_unfetched"),
    /** Items evicted before being fetched. */
    EVICTED_UNFETCHED("evicted_unfetched"),
    /** Evicted items. */
    EVICTIONS("evictions"),
    /** Bytes read from connections. */
    BYTES_READ("bytes_read"),
    /** Bytes written to connections. */
    BYTES_WRITTEN("bytes_written");

    private final String wireName;

    Counter(String wireName) {
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

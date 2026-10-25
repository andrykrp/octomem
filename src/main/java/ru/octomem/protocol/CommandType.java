package ru.octomem.protocol;

/** Supported text-protocol operations.
 * @since 1.0
 */
public enum CommandType {
    /** Read values. */
    GET,
    /** Read values with CAS. */
    GETS,
    /** Atomically read and touch all keys. */
    GAT,
    /** Atomically read and touch all keys with CAS output. */
    GATS,
    /** Unconditional store. */
    SET,
    /** Store if absent. */
    ADD,
    /** Store if present. */
    REPLACE,
    /** Append preserving flags and TTL. */
    APPEND,
    /** Prepend preserving flags and TTL. */
    PREPEND,
    /** Compare-and-store. */
    CAS,
    /** Delete a key. */
    DELETE,
    /** Unsigned wrapping increment. */
    INCR,
    /** Unsigned decrement saturating at zero. */
    DECR,
    /** Replace a key's TTL. */
    TOUCH,
    /** Establish a flush boundary. */
    FLUSH_ALL,
    /** General statistics. */
    STATS,
    /** Item statistics. */
    STATS_ITEMS,
    /** Allocator statistics. */
    STATS_SLABS,
    /** Reset counters. */
    STATS_RESET,
    /** Report server version. */
    VERSION,
    /** Change logging verbosity. */
    VERBOSITY,
    /** Close without a response. */
    QUIT
}

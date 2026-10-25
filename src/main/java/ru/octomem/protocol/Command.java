package ru.octomem.protocol;

import ru.octomem.engine.KeySequence;

/**
 * Parsed command confined to its connection's event loop.
 * Raw line, keys and body remain immutable and borrowed until command release
 * by its lifecycle owner. Decoder progress must not invalidate outstanding
 * commands. Release on completion, cancellation, error, disconnect or shutdown
 * returns line/body reservations exactly once; this interface does not expose
 * an allocator or a second lifecycle mechanism.
 *
 * <p>Only type-appropriate arguments are meaningful. APPEND/PREPEND ignore wire
 * flags and expiry; DELETE consumes but does not retain obsolete time. FLUSH_ALL
 * uses exptime as delay with protocol expiry interpretation. Unused numeric
 * fields are unspecified. Body transfer preserves admission without a full copy.
 *
 * @since 1.0
 */
public interface Command {
    /** Returns the operation.
     * @return non-null command type
     */
    CommandType type();

    /**
     * Returns borrowed input keys without per-key materialization.
     * @return non-null sequence: 1+ for GET/GETS/GAT/GATS, exactly one for
     * keyed mutations, zero for FLUSH_ALL, STATS variants, VERSION, VERBOSITY, QUIT
     */
    KeySequence keys();

    /** Returns opaque store flags.
     * @return unsigned 32-bit bits for SET/ADD/REPLACE/CAS
     */
    int flags();

    /** Returns expiry or flush delay.
     * @return signed protocol seconds: 0 none/immediate flush, negative expired,
     * 1..2592000 relative, larger absolute Unix seconds
     */
    long exptime();

    /** Returns borrowed payload, never a permanent heap cache value.
     * @return non-null array (possibly empty) for stores, otherwise null
     */
    byte[] value();

    /** Returns expected CAS token.
     * @return unsigned 64-bit bits for CAS; unused for GETS/GATS
     */
    long casId();

    /** Returns counter delta.
     * @return unsigned 64-bit bits for INCR/DECR
     */
    long delta();

    /** Returns verbosity level.
     * @return parsed level for VERBOSITY
     */
    int level();

    /**
     * Reports an explicit valid noreply suffix on a supporting command.
     * Suppresses success and application/server errors, not unrecoverable framing
     * handling. GET/GETS/GAT/GATS always answer; "noreply" is an ordinary key there.
     * @return true only when a supported suffix was explicitly supplied
     */
    boolean noreply();
}

package ru.octomem.engine;

/**
 * Thread-safe, network-independent atomic cache operations.
 * All commands observe a consistent flush boundary. Published key, flags, TTL,
 * CAS and value bytes are immutable: mutations allocate, publish, then retire.
 * Failed preparation preserves original values, TTLs and CAS tokens, except
 * an initial SET record-size/native-allocation rejection invalidates its key.
 * TTL-only versions preserve the external CAS token. Retired
 * blocks remain charged until the last pin is released; writes never wait on a
 * reader's socket. Lock order is sorted segments, allocator, then LRU metadata.
 *
 * <p>Keys are borrowed non-null arrays of 1..250 valid protocol bytes; values
 * are non-null borrowed arrays, possibly empty. Callers must not mutate inputs
 * during a call; implementations must not retain mutable arrays afterwards.
 * Null inputs throw NullPointerException. Invalid store arguments return
 * StoreResult.INVALID; invalid keys for other operations throw
 * IllegalArgumentException. Results are never null.
 *
 * <p>Flags are unsigned 32-bit bits in int; CAS and delta are unsigned 64-bit
 * bits in long. Exptime is signed protocol seconds: zero means no expiry,
 * negative means already expired, 1..2592000 is relative, larger is Unix time.
 * Time is read through ru.octomem.util.Clock, not a duplicate expiry clock.
 * Lifecycle calls are externally serialized; operations require a started,
 * not stopped engine. Calls outside that lifetime throw IllegalStateException.
 *
 * @since 1.0
 */
public interface CacheEngine {
    /**
     * Captures one admitted atomic command, not a loop of single-key reads.
     * @param keys non-null borrowed sequence with at least one occurrence
     * @return owned snapshot of distinct hits in first-occurrence order
     * @throws CacheCapacityException if admission/preparation fails before output
     */
    RetrievalSnapshot snapshot(KeySequence keys) throws CacheCapacityException;

    /**
     * Prepares every unique hit's replacement and result pin before publishing.
     * Publication allocates nothing; failure releases all temporary resources
     * without changing any target. Duplicate keys are touched exactly once;
     * successful TTL-only replacements preserve their external CAS tokens.
     * @param keys non-null borrowed sequence with at least one occurrence
     * @param exptime protocol expiry seconds
     * @return owned snapshot of the newly published versions
     * @throws CacheCapacityException if any reservation or preparation fails
     */
    RetrievalSnapshot snapshotAndTouch(KeySequence keys, long exptime) throws CacheCapacityException;

    /** Reads without requesting wire CAS output.
     * @param key borrowed key
     * @return owned hit or miss
     */
    GetResult get(byte[] key);

    /** Reads with CAS available for output.
     * @param key borrowed key
     * @return owned hit or miss
     */
    GetResult getWithCas(byte[] key);

    /**
     * Reads and atomically replaces the TTL of a live key.
     * @param key borrowed key
     * @param exptime protocol expiry seconds
     * @return owned updated hit or explicit miss
     * @throws CacheCapacityException on preparation failure, never a miss
     */
    GetResult getAndTouch(byte[] key, long exptime) throws CacheCapacityException;

    /**
     * Stores unconditionally. Initial record-size/native-allocation rejection
     * invalidates the old mapping without revoking already held reader leases.
     * @param key borrowed key
     * @param value borrowed value
     * @param flags unsigned flags bits
     * @param exptime protocol expiry seconds
     * @return store outcome
     */
    StoreResult set(byte[] key, byte[] value, int flags, long exptime);

    /**
     * Stores only if the key is absent or expired.
     * @param key borrowed key
     * @param value borrowed value
     * @param flags unsigned flags bits
     * @param exptime protocol expiry seconds
     * @return store outcome
     */
    StoreResult add(byte[] key, byte[] value, int flags, long exptime);

    /**
     * Stores only if a live key exists.
     * @param key borrowed key
     * @param value borrowed value
     * @param flags unsigned flags bits
     * @param exptime protocol expiry seconds
     * @return store outcome
     */
    StoreResult replace(byte[] key, byte[] value, int flags, long exptime);

    /**
     * Appends bytes while preserving existing flags and TTL.
     * @param key borrowed key
     * @param value borrowed suffix, possibly empty
     * @return store outcome
     */
    StoreResult append(byte[] key, byte[] value);

    /**
     * Prepends bytes while preserving existing flags and TTL.
     * @param key borrowed key
     * @param value borrowed prefix, possibly empty
     * @return store outcome
     */
    StoreResult prepend(byte[] key, byte[] value);

    /**
     * Stores only if the live version matches the expected unsigned token.
     * @param key borrowed key
     * @param value borrowed value
     * @param flags unsigned flags bits
     * @param exptime protocol expiry seconds
     * @param casId expected unsigned CAS bits
     * @return store outcome distinguishing EXISTS from NOT_FOUND
     */
    StoreResult cas(byte[] key, byte[] value, int flags, long exptime, long casId);

    /** Deletes a live key.
     * @param key borrowed key
     * @return whether a live key was deleted
     */
    boolean delete(byte[] key);

    /**
     * Increments modulo 2^64, publishing a new version.
     * @param key borrowed key
     * @param delta unsigned delta bits
     * @return value or failure, preserving the original version on failure
     */
    CounterResult increment(byte[] key, long delta);

    /**
     * Decrements with saturation at zero, never unsigned wraparound.
     * @param key borrowed key
     * @param delta unsigned delta bits
     * @return value or failure, preserving the original version on failure
     */
    CounterResult decrement(byte[] key, long delta);

    /**
     * Publishes a new TTL version with the same wire CAS, without returning a lease.
     * @param key borrowed key
     * @param exptime protocol expiry seconds
     * @return true for a hit, false only for a miss
     * @throws CacheCapacityException if replacement preparation fails
     */
    boolean touch(byte[] key, long exptime) throws CacheCapacityException;

    /**
     * Establishes an atomic oldest-live invalidation boundary.
     * Captured snapshots remain valid; later-created records survive the cutoff.
     * @param delaySeconds zero for immediate flush, otherwise interpreted like exptime
     */
    void flushAll(long delaySeconds);

    /** Starts the engine before commands are admitted. */
    void start();

    /**
     * Stops admission and background work after owners cancel/drain their leases.
     * Native pages must not close while readers retain versions; shutdown must
     * arrange event-loop cancellation rather than concurrently closing cursors.
     */
    void stop();
}

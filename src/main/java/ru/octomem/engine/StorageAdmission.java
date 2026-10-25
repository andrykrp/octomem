package ru.octomem.engine;

/** Optional native header admission. Retained tokens require caller-owned bounded
 * metadata admission; the server supplies its pre-admitted connection control pool.
 * @since 1.0
 */
public interface StorageAdmission {
    /** Storage operation identity. */
    enum Mode { SET, ADD, REPLACE, APPEND, PREPEND, CAS }
    /** Exclusive unpublished ownership; close cancels, or is a no-op after transfer. */
    interface Token extends AutoCloseable { @Override void close(); }
    /** Admission outcome; only STORED carries a token, not yet a published item. */
    record Result(StoreResult status, Token token) {
        public Result {
            if (status == null || (status == StoreResult.STORED) != (token != null))
                throw new IllegalArgumentException("Invalid admission result");
        }
    }
    /** Admits one complete incoming native record without retaining caller key bytes. */
    Result admit(Mode mode, byte[] key, int flags, long exptime, long cas, int valueLength);
    /**
     * Invalidates a valid SET rejected by the codec's full-record size check,
     * before its body is drained. No native or payload admission is requested.
     * Production admission owners must implement this; the default keeps isolated
     * codec adapters without a cache source-compatible.
     * @param key borrowed validated SET key, never retained
     */
    default void rejectOversizedSet(byte[] key) { }
    /** Validates issuer/header and consumes once; mismatch leaves token caller-owned. */
    StoreResult storePrepared(Token token, Mode mode, byte[] key, byte[] value, int flags, long exptime, long cas);
    /** Counts recognized malformed flush handler entry without changing cache state. */
    void malformedFlush();
    /** Reports a safe bounded capacity warning, without implying native OOM. */
    void capacityDenied();
}

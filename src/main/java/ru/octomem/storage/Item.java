package ru.octomem.storage;

import java.nio.ByteBuffer;

/**
 * Immutable published record, borrowed under an ItemLease, GetResult or snapshot.
 * Key, flags, expiry, CAS and value bytes never change after publication.
 * Independent leased readers may read concurrently using their own offsets and
 * destination buffers. Every method requires a live owner; use after release is
 * invalid and must not access reclaimed memory. No raw writable storage escapes.
 *
 * @since 1.0
 */
public interface Item {
    /**
     * Copies the key defensively, never exposing stored mutable bytes.
     * The caller accounts this bounded copy in the line/control budget; internal
     * snapshot lookup uses a borrowed storage key view, not this allocating API.
     * @return new independent array containing 1..250 key bytes
     */
    byte[] key();

    /**
     * Returns the opaque unsigned 32-bit flags.
     * @return raw flags bits
     */
    int flags();

    /**
     * Returns the immutable normalized deadline.
     * @return signed Unix deadline; hasExpiry distinguishes epoch zero from no expiry
     */
    long expiryTime();

    /**
     * Returns whether this version has an expiry deadline.
     * Implementations supporting an epoch-zero deadline override this default.
     * @return true when the version has a deadline, including epoch zero
     */
    default boolean hasExpiry() { return expiryTime() != 0; }

    /**
     * Returns the version token, wrapping modulo 2^64 on version generation.
     * @return unsigned 64-bit CAS bits
     */
    long casId();

    /**
     * Returns the value size, excluding key, header and alignment.
     * @return nonnegative value byte count
     */
    int valueLength();

    /**
     * Copies min(dst.remaining(), valueLength() - offset) value bytes.
     * Advances dst.position() by the returned count, leaves its limit unchanged,
     * and has no shared cursor. Empty dst or offset at end returns zero.
     * @param offset byte offset in [0, valueLength()]
     * @param dst non-null writable destination owned by this reader
     * @return number copied, zero at end or with no destination capacity
     * @throws NullPointerException if dst is null
     * @throws IndexOutOfBoundsException if offset is outside the value
     * @throws java.nio.ReadOnlyBufferException if dst is read-only
     */
    int readValue(long offset, ByteBuffer dst);

    /**
     * Tests the immutable TTL, not flush visibility or retirement.
     * @param now Unix seconds obtained from the configured util Clock
     * @return hasExpiry() and expiryTime() is at most now
     */
    boolean isExpired(long now);

    /**
     * Reads separately synchronized LRU activity metadata.
     * @return whether the service activity flag is set, not record liveness
     */
    boolean isActive();

    /** Marks separate synchronized LRU metadata without changing the version. */
    void markActive();
}

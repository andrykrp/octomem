package ru.octomem.engine;

import java.util.Iterator;
import ru.octomem.storage.Item;

/**
 * Owns all unique immutable hits and metadata admission for one atomic command.
 * Capture uses sorted unique segment locks, one clock reading and consistent
 * flush visibility; all pins precede unlock. Misses remain misses and expiry
 * after capture does not revoke a hit. No network or callbacks run under locks.
 *
 * <p>The caller owns a new snapshot until successful transfer to a response,
 * then its writer; failed transfer requires the current owner to close it.
 * The snapshot, its cursor and close are event-loop confined. Shutdown requests
 * cancellation on that loop rather than racing borrowed reads.
 *
 * @since 1.0
 */
public interface RetrievalSnapshot extends AutoCloseable {
    /**
     * Returns the single observation time used for every key and TTL.
     * @return Unix epoch seconds from the configured util Clock
     * @throws IllegalStateException if closed
     */
    long capturedClock();

    /**
     * Obtains the sole forward cursor, without lookups or additional leases.
     * Hits appear once in first-input-occurrence order; misses are omitted.
     * Items are borrowed until snapshot close, not until cursor advancement.
     * Removal is unsupported and exhaustion follows Iterator's contract.
     * @return non-null cursor containing only non-null borrowed items
     * @throws IllegalStateException if closed or a cursor was already obtained
     */
    Iterator<Item> values();

    /**
     * Releases all pins, including unvisited hits, and metadata exactly once.
     * Idempotent; afterwards the cursor and every borrowed item are invalid.
     */
    @Override
    void close();
}

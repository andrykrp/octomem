package ru.octomem.engine;

import ru.octomem.storage.Item;

/**
 * Owns a single-key read lease, including an explicitly represented miss.
 * The result and close are caller-thread confined; the immutable item permits
 * concurrent readers with separate leases and offsets. This is not a substitute
 * for a whole-command retrieval snapshot. Close even a miss.
 *
 * @since 1.0
 */
public interface GetResult extends AutoCloseable {
    /**
     * Reports whether a live version was captured.
     * @return true exactly when item() is non-null
     * @throws IllegalStateException if closed
     */
    boolean found();

    /**
     * Returns the borrowed immutable version.
     * @return item valid until close, or null for a miss
     * @throws IllegalStateException if closed
     */
    Item item();

    /** Releases this result's pin exactly once; repeated calls have no effect. */
    @Override
    void close();
}

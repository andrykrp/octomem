package ru.octomem.protocol;

import java.nio.ByteBuffer;

/**
 * Same-event-loop, nonblocking receiver of admitted, bounded response copies.
 * Total capacity must be at least two bytes, validated before output. Temporary
 * remaining capacity zero/one is valid. The sink never retains native-backed
 * borrowed data after accept returns, so snapshot close cannot invalidate queues.
 *
 * @since 1.0
 */
public interface ChunkSink {
    /**
     * Reports immediately available capacity under local and global budgets.
     * @return nonnegative bytes; zero means backpressure, not capacity failure
     */
    int remainingCapacity();

    /**
     * Copies every remaining chunk byte synchronously to owned admitted storage.
     * Advances chunk position to its limit; the limit is unchanged. Capacity is
     * reserved before copying, and no borrowed buffer/array/native reference is
     * retained. CRLF is never split across accepts. Empty chunks have no effect.
     * @param chunk non-null borrowed source no larger than remainingCapacity()
     * @throws NullPointerException if chunk is null
     * @throws IllegalArgumentException if the chunk exceeds available capacity
     */
    void accept(ByteBuffer chunk);
}

package ru.octomem.protocol;

/**
 * Resumable response owner confined, with cursor and close, to one event loop.
 * Reads the snapshot's sole borrowed cursor and independent value offsets.
 * Snapshot admission cannot fail after output starts; unavailable queue capacity
 * is backpressure, not a late OOM response. Shutdown schedules cancellation on
 * this owner rather than racing reads. No whole-value or whole-response copy.
 *
 * @since 1.0
 */
public interface ResponseWriter {
    /**
     * Emits bounded chunks while the sink can accept them, preserving CRLF pairs.
     * Each chunk is at most min(sink capacity, protocol.maxChunkBytes). At zero
     * capacity returns IN_PROGRESS without output; at one it may send an ordinary
     * byte but must not send half a CRLF. Scheduler waits for drain/OP_WRITE, never
     * spins, and uses fair finite work budgets. COMPLETE means the last bytes
     * have been copied into transport-owned admitted parts and this writer has
     * closed the response, including unvisited snapshot entries.
     * @param sink non-null same-loop receiver with total capacity at least two
     * @return non-null progress status
     * @throws NullPointerException if sink is null
     * @throws IllegalStateException if called after COMPLETE or close
     */
    WriteStatus writeMore(ChunkSink sink);

    /**
     * Cancels and releases the response and all snapshot resources exactly once.
     * Idempotent after COMPLETE, cancellation or error. Disconnect/shutdown must
     * invoke this even if no value was emitted; writer errors must also clean up.
     */
    void close();
}

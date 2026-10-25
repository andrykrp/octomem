package ru.octomem.transport;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Loop-confined ingress reservations with shared atomic global body accounting.
 * This transport admits one command at a time, within any configured inflight ceiling.
 * It owns the line slot from before socket read through response drain; a decoder
 * adapter reserves its one exact body before allocation, and releases it after use.
 * No method allocates body storage. Normal mismatched/double releases are rejected.
 * Close is a fallback releasing outstanding reservations once, not a decoder substitute.
 */
public final class ConnectionResources implements AutoCloseable {
    private final AtomicLong globalBody;
    private final long bodyLimit;
    private final int maxBody;
    private final SharedBudget globalLines;
    private final long lineCharge;
    private final long lineLimit;
    private volatile long lineUsed;
    private final Thread owner = Thread.currentThread();
    private volatile long bodyCharge;
    private volatile int lines;
    private boolean closed;

    ConnectionResources(AtomicLong globalBody, long bodyLimit, int maxBody) {
        this(globalBody, bodyLimit, maxBody, new SharedBudget(16640, 0), 16640, 16640);
    }

    ConnectionResources(AtomicLong globalBody, long bodyLimit, int maxBody,
            SharedBudget globalLines, long lineCharge, long perConnectionLineLimit) {
        if (lineCharge < 1 || perConnectionLineLimit < 0) {
            throw new IllegalArgumentException("Invalid line budget");
        }
        this.globalBody = globalBody;
        this.bodyLimit = bodyLimit;
        this.maxBody = maxBody;
        this.globalLines = java.util.Objects.requireNonNull(globalLines, "globalLines");
        this.lineCharge = lineCharge;
        lineLimit = perConnectionLineLimit;
    }

    /** Acquires the single lazy line slot; transport calls this before socket reads.
     * @return false when occupied or a local/global logical budget is exhausted
     */
    public boolean acquireLine() {
        checkOwner();
        if (lines != 0) { return false; }
        if (lineCharge > lineLimit - lineUsed || !globalLines.reserve(lineCharge)) { return false; }
        lineUsed += lineCharge;
        lines = 1;
        return true;
    }

    /** Releases the line slot; an unmatched release throws IllegalStateException. */
    public void releaseLine() {
        checkOwner();
        if (lines != 1) { throw new IllegalStateException("No line reservation"); }
        globalLines.release(lineCharge);
        lineUsed -= lineCharge;
        lines = 0;
    }

    /** Reserves payload plus 32 globally before allocation; denial must drain, never wait.
     * @param payload exact nonnegative payload length bounded by itemMaxSize
     * @return false on exhausted global capacity
     */
    public boolean tryReserveBody(int payload) {
        checkOwner();
        if (payload < 0 || payload > maxBody) { throw new IllegalArgumentException("Invalid body length"); }
        if (bodyCharge != 0) { throw new IllegalStateException("Body already reserved"); }
        long charge = (long) payload + 32;
        for (;;) {
            long used = globalBody.get();
            if (charge > bodyLimit - used) { return false; }
            if (globalBody.compareAndSet(used, used + charge)) {
                bodyCharge = charge;
                return true;
            }
        }
    }

    /** Releases the exact outstanding body reservation.
     * @param payload original admitted payload length
     */
    public void releaseBody(int payload) {
        checkOwner();
        if (payload < 0 || bodyCharge != (long) payload + 32) {
            throw new IllegalStateException("Mismatched body release");
        }
        globalBody.addAndGet(-bodyCharge);
        bodyCharge = 0;
    }

    /** Returns this connection's reserved payload and overhead, safe for observation.
     * @return nonnegative logical body charge
     */
    public long usedBodyBytes() { return bodyCharge; }
    /** Returns this connection's live line/descriptor logical charge.
     * @return nonnegative reserved bytes, not allocated heap or RSS
     */
    public long usedLineBytes() { return lineUsed; }
    /** Returns occupied line slots, safe for observation.
     * @return zero or one
     */
    public int lineSlots() { return lines; }

    /** Idempotently releases all remaining reservations on the owner loop. */
    @Override public void close() {
        if (Thread.currentThread() != owner) { throw new IllegalStateException("Wrong event loop"); }
        if (closed) { return; }
        closed = true;
        globalBody.addAndGet(-bodyCharge);
        bodyCharge = 0;
        globalLines.release(lineUsed);
        lineUsed = 0;
        lines = 0;
    }

    private void checkOwner() {
        if (closed || Thread.currentThread() != owner) { throw new IllegalStateException("Closed or wrong event loop"); }
    }
}

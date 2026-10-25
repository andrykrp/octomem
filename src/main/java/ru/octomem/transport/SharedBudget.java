package ru.octomem.transport;

import java.util.concurrent.atomic.AtomicLong;

/** Checked logical reservations; only derived ceilings, never live counts, saturate. */
final class SharedBudget {
    private final long limit;
    private final AtomicLong used;

    /** Initial usage is a package-private boundary-test seam, not production allocation. */
    SharedBudget(long limit, long initialUsed) {
        if (limit < 0 || initialUsed < 0 || initialUsed > limit) {
            throw new IllegalArgumentException("Invalid budget state");
        }
        this.limit = limit;
        used = new AtomicLong(initialUsed);
    }

    boolean reserve(long charge) {
        if (charge < 0) { throw new IllegalArgumentException("Negative charge"); }
        for (;;) {
            long current = used.get();
            if (charge > limit - current) { return false; }
            if (used.compareAndSet(current, current + charge)) { return true; }
        }
    }

    void release(long charge) {
        if (charge < 0) { throw new IllegalArgumentException("Negative charge"); }
        for (;;) {
            long current = used.get();
            if (charge > current) { throw new IllegalStateException("Unmatched release"); }
            if (used.compareAndSet(current, current - charge)) { return; }
        }
    }

    long usedBytes() { return used.get(); }

    static long saturatedMultiply(long first, long second) {
        if (first < 1 || second < 1) { throw new IllegalArgumentException("Nonpositive ceiling factor"); }
        return first > Long.MAX_VALUE / second ? Long.MAX_VALUE : first * second;
    }
}

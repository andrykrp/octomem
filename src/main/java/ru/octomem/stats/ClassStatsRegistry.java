package ru.octomem.stats;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

/** Real per-class statistics capability, independent of storage and transport types.
 * @since 1.0
 */
public interface ClassStatsRegistry extends StatsRegistry {
    /** Linked state, including expired entries not yet unlinked; empty oldest time is zero. */
    record ClassState(long number, long requestedBytes, long oldestActivityTime) { }
    /** Physical unlink route; lazy unlinks do not contribute reclamation counters. */
    enum Removal { LAZY, TAIL, CRAWLER, EVICTION }
    /** Resettable item event fields; tail repair is structurally unsupported. */
    enum Event { EVICTED, EVICTED_NONZERO, EVICTED_TIME, OUTOFMEMORY, RECLAIMED,
        EXPIRED_UNFETCHED, EVICTED_UNFETCHED, CRAWLER_RECLAIMED }

    /** Initializes fixed classes once before serving, borrowing the engine snapshot pool. */
    void initializeClasses(int count, IntFunction<ClassState> stateSource,
            AtomicLong snapshotUsed, long snapshotLimit);
    /** Adds general and class operation counts to the same captured reset epoch. */
    void incrementClass(int classId, Counter counter, long delta);
    /** Records one real removal event; crawler unfetched excludes flushed victims. */
    void recordRemoval(int classId, Removal route, boolean unfetched, boolean expiring, long age);
    /** Records a terminal valid-class native admission denial, not a metadata failure. */
    void outOfMemory(int classId);
    /** Captures bounded detached counters before output; denial throws IllegalStateException. */
    ClassSnapshot captureClasses();
    /** Returns the per-server safe logger. */
    SafeLogger logger();

    /** One admitted detached counter image; close releases its shared pool charge exactly once. */
    interface ClassSnapshot extends AutoCloseable {
        /** Returns the fixed class count. */
        int classCount();
        /** Samples one class's linked gauges without retaining locks or item owners. */
        ClassState state(int classId);
        /** Returns a supported per-class operation counter captured from one reset epoch. */
        long counter(int classId, Counter counter);
        /** Returns a per-class event count or saved eviction age. */
        long event(int classId, Event event);
        /** Releases the bounded image; idempotent. */
        @Override void close();
    }
}

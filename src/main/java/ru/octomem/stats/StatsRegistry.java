package ru.octomem.stats;

/**
 * Thread-safe general statistics with resettable counters and persistent gauges.
 * Numeric fields start at zero. No clock is read: callers set uptime and time.
 *
 * @since 1.0
 */
public interface StatsRegistry {
    /**
     * Adds a nonnegative delta, saturating at {@link Long#MAX_VALUE}.
     * Updates are not lost without reset, except amounts beyond saturation.
     * An invocation captures exactly one counter epoch. If it overlaps reset,
     * its update may belong to the old epoch and be discarded by that reset,
     * even if the invocation completes afterward.
     *
     * @param counter the counter to update
     * @param delta the nonnegative amount to add
     * @throws NullPointerException if counter is null
     * @throws IllegalArgumentException if delta is negative
     */
    void increment(Counter counter, long delta);

    /**
     * Atomically replaces a gauge value independently of counter resets.
     *
     * @param gauge the gauge to update
     * @param value the nonnegative replacement value
     * @throws NullPointerException if gauge is null
     * @throws IllegalArgumentException if value is negative
     */
    void setGauge(Gauge gauge, long value);

    /**
     * Copies all fields without explicit read locks, capturing one counter epoch.
     * Reads of individual numeric fields are atomic, but different fields can
     * reflect different instants. Concurrent reset never mixes counter epochs;
     * a snapshot overlapping reset may represent the old epoch.
     *
     * @return immutable detached snapshot, weakly consistent across fields
     */
    Snapshot snapshot();

    /**
     * Atomically replaces the counter epoch with a new zero-valued epoch.
     * Gauges, constants and existing snapshots are unchanged. All increments
     * completed before reset begins are removed. Increments starting after
     * reset returns target the new epoch (unless another reset intervenes).
     * Overlapping increments can target either epoch, never both.
     */
    void reset();
}

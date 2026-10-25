package ru.octomem.stats;

import java.util.Map;

/**
 * Immutable detached general statistics, including zero-valued fields.
 * Every numeric value is read atomically, but the snapshot is weakly consistent
 * across fields, not a linearizable view of the entire registry. All counters
 * come from one captured reset epoch; gauges are independent of that epoch.
 * Subsequent updates or resets cannot change any returned map.
 *
 * @since 1.0
 */
public interface Snapshot {
    /**
     * Returns all monotonic counters in enum declaration order.
     *
     * @return immutable complete counter map with nonnegative values
     */
    Map<Counter, Long> counters();

    /**
     * Returns all current gauges in enum declaration order.
     *
     * @return immutable complete gauge map with nonnegative values
     */
    Map<Gauge, Long> gauges();

    /**
     * Returns the process constants in registry-defined order.
     *
     * @return immutable map containing pid, version, pointer_size, threads and limit_maxbytes
     */
    Map<String, String> constants();
}

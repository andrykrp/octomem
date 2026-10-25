package ru.octomem.stats;

import java.util.Map;
import java.util.Set;

final class StatsOracle {
    static final Map<String, String> COUNTERS = Map.ofEntries(
            Map.entry("TOTAL_CONNECTIONS", "total_connections"),
            Map.entry("CONN_YIELDS", "conn_yields"),
            Map.entry("CMD_GET", "cmd_get"), Map.entry("CMD_SET", "cmd_set"),
            Map.entry("CMD_FLUSH", "cmd_flush"), Map.entry("CMD_TOUCH", "cmd_touch"),
            Map.entry("GET_HITS", "get_hits"), Map.entry("GET_MISSES", "get_misses"),
            Map.entry("DELETE_HITS", "delete_hits"), Map.entry("DELETE_MISSES", "delete_misses"),
            Map.entry("INCR_HITS", "incr_hits"), Map.entry("INCR_MISSES", "incr_misses"),
            Map.entry("DECR_HITS", "decr_hits"), Map.entry("DECR_MISSES", "decr_misses"),
            Map.entry("CAS_HITS", "cas_hits"), Map.entry("CAS_MISSES", "cas_misses"),
            Map.entry("CAS_BADVAL", "cas_badval"), Map.entry("TOUCH_HITS", "touch_hits"),
            Map.entry("TOUCH_MISSES", "touch_misses"), Map.entry("TOTAL_ITEMS", "total_items"),
            Map.entry("RECLAIMED", "reclaimed"), Map.entry("EXPIRED_UNFETCHED", "expired_unfetched"),
            Map.entry("EVICTED_UNFETCHED", "evicted_unfetched"), Map.entry("EVICTIONS", "evictions"),
            Map.entry("BYTES_READ", "bytes_read"), Map.entry("BYTES_WRITTEN", "bytes_written"));
    static final Map<String, String> GAUGES = Map.of(
            "CURR_CONNECTIONS", "curr_connections", "CONNECTION_STRUCTURES", "connection_structures",
            "CURR_ITEMS", "curr_items", "BYTES", "bytes", "UPTIME", "uptime", "TIME", "time");
    static final Map<String, String> CONSTANTS = Map.of(
            "pid", "123", "version", "1.0.0(octomem)", "pointer_size", "64",
            "threads", "4", "limit_maxbytes", "67108864");
    static final Set<String> CONSTANT_KEYS = Set.of("pid", "version", "pointer_size", "threads", "limit_maxbytes");

    private StatsOracle() { }

    static StatsRegistry registry() {
        return new AtomicStatsRegistry(CONSTANTS);
    }
}

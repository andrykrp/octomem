package ru.octomem.config;

import java.util.Map;

/** Immutable, validated configuration; safe to share between threads and owns no resources. */
public final class ServerConfig {
    private final Map<String, String> values;

    ServerConfig(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    /**
     * Returns the complete configuration as an immutable key-to-value map.
     *
     * @return immutable complete configuration, with sizes in decimal bytes and numbers in decimal
     */
    public Map<String, String> values() { return values; }

    /**
     * Returns the numeric bind address without resolving DNS or touching the network.
     *
     * @return numeric bind address; retrieving it performs no DNS lookup
     */
    public String listenAddress() { return values.get("listen.address"); }

    /**
     * Returns the TCP port the server listens on.
     *
     * @return TCP port in the range 1 through 65535
     */
    public int listenPort() { return Integer.parseInt(values.get("listen.port")); }

    /**
     * Returns the cache budget in bytes, which is not a process RSS limit.
     *
     * @return positive cache budget in bytes, not a process RSS limit
     */
    public long memoryLimit() { return Long.parseLong(values.get("memory.limit")); }

    /**
     * Returns the maximum size of a complete record in bytes.
     *
     * @return maximum complete record size in bytes, including key and metadata
     */
    public int itemMaxSize() { return Integer.parseInt(values.get("item.maxSize")); }

    /**
     * Returns the maximum number of accepted connections.
     *
     * @return positive maximum connection count
     */
    public int connectionsMax() { return Integer.parseInt(values.get("connections.max")); }

    /**
     * Returns the number of event-loop worker threads.
     *
     * @return positive event-loop worker count
     */
    public int transportWorkers() { return Integer.parseInt(values.get("transport.workers")); }

    /**
     * Returns the configured eviction policy.
     *
     * @return supported eviction policy, {@code lru}
     */
    public String evictionPolicy() { return values.get("eviction.policy"); }

    /**
     * Returns the background reclaim interval in seconds, which is safe to convert to nanoseconds.
     *
     * @return positive background reclaim interval in seconds, safe to convert to nanoseconds
     */
    public long expiryReclaimIntervalSeconds() { return Long.parseLong(values.get("expiry.reclaimIntervalSeconds")); }

    /**
     * Returns the maximum command line size in bytes.
     *
     * @return positive maximum command line size in bytes
     */
    public int protocolMaxLineBytes() { return Integer.parseInt(values.get("protocol.maxLineBytes")); }

    /**
     * Returns the maximum response chunk size in bytes.
     *
     * @return maximum response chunk size in bytes, at least two
     */
    public int protocolMaxChunkBytes() { return Integer.parseInt(values.get("protocol.maxChunkBytes")); }

    /**
     * Returns the prepared output budget per connection in bytes.
     *
     * @return prepared output budget per connection in bytes, at least two
     */
    public int connectionsPendingWriteBytes() { return Integer.parseInt(values.get("connections.pendingWriteBytes")); }

    /**
     * Returns the maximum number of unfinished commands per connection.
     *
     * @return positive maximum unfinished command count per connection
     */
    public int connectionsMaxInflightCommands() { return Integer.parseInt(values.get("connections.maxInflightCommands")); }

    /**
     * Returns the idle timeout in seconds, where zero disables the timeout.
     *
     * @return idle timeout in seconds, safe to convert to nanoseconds; zero disables it
     */
    public long connectionsIdleTimeoutSeconds() { return Long.parseLong(values.get("connections.idleTimeoutSeconds")); }

    /**
     * Returns the configured log level.
     *
     * @return one of {@code error}, {@code warn}, {@code info}, {@code debug}
     */
    public String logLevel() { return values.get("log.level"); }
}

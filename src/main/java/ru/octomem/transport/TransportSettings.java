package ru.octomem.transport;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Immutable internal transport settings, without a dependency on configuration parsing.
 * Port zero is permitted for test composition only; public configuration still forbids it.
 * Limits are ceilings, not eager allocation sizes or process RSS guarantees.
 * @param address resolved numeric bind address
 * @param workers positive fixed event-loop count
 * @param maxConnections positive admission ceiling
 * @param pendingWriteBytes prepared output ceiling, at least two
 * @param maxInflightCommands positive outstanding command ceiling
 * @param idleTimeoutSeconds idle timeout, zero disables it
 * @param maxLineBytes positive decoder line bound
 * @param maxChunkBytes encoder chunk ceiling, at least two
 * @param memoryLimit positive native cache budget used to derive ingress budget
 * @param itemMaxSize positive complete record bound, no greater than memoryLimit
 */
public record TransportSettings(InetSocketAddress address, int workers, int maxConnections,
        int pendingWriteBytes, int maxInflightCommands, long idleTimeoutSeconds,
        int maxLineBytes, int maxChunkBytes, long memoryLimit, int itemMaxSize) {
    /** Validates accepted configuration bounds without allocating their aggregate ceilings. */
    public TransportSettings {
        Objects.requireNonNull(address, "address");
        if (address.isUnresolved() || workers < 1 || maxConnections < 1
                || pendingWriteBytes < 2 || maxInflightCommands < 1 || maxLineBytes < 1
                || maxChunkBytes < 2 || memoryLimit < 1 || itemMaxSize < 1
                || itemMaxSize > memoryLimit || idleTimeoutSeconds < 0
                || idleTimeoutSeconds > Long.MAX_VALUE / 1_000_000_000L) {
            throw new IllegalArgumentException("Invalid transport settings");
        }
    }
}

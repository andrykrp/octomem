package ru.octomem.protocol;

/**
 * Body admission supplied by the transport without a protocol-to-transport edge.
 * The implementation atomically accounts for payload plus 32 bytes globally.
 * Callbacks must not throw and are invoked on the connection event loop.
 * Line/descriptor admission is separate and precedes any decoder input.
 *
 * @since 1.0
 */
public interface ProtocolBudget {
    /**
     * Reserves the entire body before allocation or copying, without waiting.
     * @param payloadBytes nonnegative payload size, excluding the 32-byte charge
     * @return true if ownership of one full reservation passes to the decoder
     */
    boolean tryReserveBody(int payloadBytes);

    /**
     * Returns a previously acquired full reservation, including its overhead.
     * @param payloadBytes original payload size, including zero for empty bodies
     */
    void releaseBody(int payloadBytes);
}

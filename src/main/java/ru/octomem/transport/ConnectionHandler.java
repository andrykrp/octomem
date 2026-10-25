package ru.octomem.transport;

import ru.octomem.protocol.Command;
import ru.octomem.protocol.ProtocolDecoder;
import ru.octomem.protocol.ProtocolEncoder;
import ru.octomem.protocol.ProtocolException;
import ru.octomem.protocol.Response;

/** Nonblocking per-connection operations and cleanup, confined to one event loop. */
public interface ConnectionHandler extends AutoCloseable {
    /** Returns the same bounded incremental decoder for this connection.
     * @return non-null decoder
     */
    ProtocolDecoder decoder();
    /** Executes one FIFO command; body ownership remains with the handler/response.
     * Release the body reservation when no longer needed, possibly from writer close.
     * The transport does not release body reservations on normal command completion.
     * Do not acquire or release the transport-owned line slot.
     * @param command decoded command
     * @return owned response, or null for a deliberately suppressed response
     * @throws Exception on failure; only this connection is closed
     */
    Response handle(Command command) throws Exception;
    /** Returns the encoder used once per non-null response.
     * @return non-null encoder
     */
    ProtocolEncoder encoder();
    /**
     * Selects a safe response using decoder framing context, not exception diagnostics.
     * Production protocol integration MUST override this fallback for wire compatibility.
     * @param failure classified decoder failure
     * @return owned error response, or null to close without a response
     */
    default Response protocolError(ProtocolException failure) { return null; }
    /** Whether a protocol-specific quit requires closing after its response drains. */
    default boolean closeAfterResponse() { return false; }
    /** Releases decoder/command resources; called exactly once by the owning loop. */
    @Override void close();
}

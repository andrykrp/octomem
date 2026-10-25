package ru.octomem.protocol;

import java.nio.ByteBuffer;

/**
 * Incremental, connection-event-loop-confined decoder with bounded admission.
 * Only an unfinished command is parser state; no eager decoded-command queue.
 * Line slots precede reads, full pipelines disable OP_READ, and body admission
 * reserves the entire payload plus 32 before allocation/body copying. One exact
 * payload array is used, with no growth or hidden old/new body copy.
 *
 * <p>Body denial immediately selects OOM and drains the declared frame through
 * the connection's receive/discard lane, then reports the error and continues
 * the pipeline with correct noreply. Oversize uses the same bounded drain with
 * its distinct error; malformed terminators make the stream unrecoverable.
 * No waiting for another connection's incomplete body is allowed.
 *
 * @since 1.0
 */
public interface ProtocolDecoder {
    /**
     * Consumes available admitted input from its position towards its limit.
     * Advances position by bytes consumed; limit is unchanged. May leave bytes
     * unconsumed when admission/backpressure prevents progress; the caller keeps
     * those bytes. No reference to input is retained after return, and this call
     * does not eagerly decode commands beyond nextCommand demand.
     * @param input non-null borrowed input, including read-only buffers
     * @throws NullPointerException if input is null
     */
    void append(ByteBuffer input);

    /**
     * Decodes exactly one requested command, never additional pipeline commands.
     * The transport bounds outstanding commands by maxInflightCommands and stops
     * input when that limit or outbound backpressure is reached.
     * @return one ready command, or null if more input is needed
     * @throws ProtocolException on a classified framing/command failure
     */
    Command nextCommand() throws ProtocolException;
}

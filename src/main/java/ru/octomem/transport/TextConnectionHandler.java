package ru.octomem.transport;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import ru.octomem.engine.RetrievalSnapshot;
import ru.octomem.protocol.ChunkSink;
import ru.octomem.protocol.Command;
import ru.octomem.protocol.ProtocolEncoder;
import ru.octomem.protocol.ProtocolException;
import ru.octomem.protocol.Response;
import ru.octomem.protocol.ResponseKind;
import ru.octomem.protocol.ResponseWriter;
import ru.octomem.protocol.TextProtocolDecoder;
import ru.octomem.protocol.WriteStatus;

/**
 * Owner-loop bridge retaining borrowed command keys/body through response cleanup.
 * One response and writer may be outstanding. The writer closes its delegate
 * before releasing the exact command; response-close and connection-close are
 * idempotent fallback paths, never a second body reservation owner.
 */
public final class TextConnectionHandler implements ConnectionHandler, ProtocolEncoder {
    private final TextProtocolDecoder decoder;
    private final Function<Command, Response> dispatcher;
    private final ProtocolEncoder encoder;
    private OwnedResponse response;
    private ResponseWriter writer;
    private boolean closed;

    TextConnectionHandler(TextProtocolDecoder decoder, Function<Command, Response> dispatcher, ProtocolEncoder encoder) {
        this.decoder = decoder;
        this.dispatcher = dispatcher;
        this.encoder = encoder;
    }

    @Override public TextProtocolDecoder decoder() { return decoder; }
    @Override public ProtocolEncoder encoder() { return this; }

    @Override public Response handle(Command command) {
        requireIdle();
        Objects.requireNonNull(command, "command");
        Response result = dispatcher.apply(command);
        if (result == null || command.noreply()) {
            try { if (result != null) { result.close(); } }
            finally { decoder.release(command); }
            return null;
        }
        response = new OwnedResponse(command, result);
        return response;
    }

    @Override public Response protocolError(ProtocolException failure) {
        requireIdle();
        Objects.requireNonNull(failure, "failure");
        FixedError error = new FixedError(decoder.errorCode(),
                !failure.closeConnection() && decoder.errorNoreply());
        writer = error;
        return error;
    }

    /** Wraps normal writers; fixed protocol errors never reach the shared encoder.
     * @param value exact response returned by this handler
     * @return writer owning response and command cleanup
     */
    @Override public ResponseWriter encode(Response value) {
        if (closed) { throw new IllegalStateException("Handler closed"); }
        if (value == writer && value instanceof FixedError) { return writer; }
        if (value != response || response == null || writer != null) {
            throw new IllegalArgumentException("Not the outstanding response");
        }
        ResponseWriter delegate = Objects.requireNonNull(encoder.encode(value), "writer");
        writer = new OwnedWriter(delegate, response);
        return writer;
    }

    @Override public void close() {
        if (closed) { return; }
        closed = true;
        try {
            try { if (writer != null) { writer.close(); } }
            finally { if (response != null) { response.close(); } }
        } finally { decoder.close(); }
    }

    private void requireIdle() {
        if (closed || response != null || writer != null) {
            throw new IllegalStateException("Handler closed or response outstanding");
        }
    }

    private final class OwnedResponse implements Response {
        private final Command command;
        private final Response delegate;
        private boolean released;

        OwnedResponse(Command command, Response delegate) {
            this.command = command;
            this.delegate = delegate;
        }

        @Override public ResponseKind kind() { return delegate.kind(); }
        @Override public RetrievalSnapshot snapshot() { return delegate.snapshot(); }
        @Override public boolean includeCas() { return delegate.includeCas(); }
        @Override public long number() { return delegate.number(); }
        @Override public String text() { return delegate.text(); }
        @Override public List<String> statsLines() { return delegate.statsLines(); }
        @Override public void close() {
            if (released) { return; }
            released = true;
            try { delegate.close(); }
            finally {
                response = null;
                decoder.release(command);
            }
        }
    }

    private final class OwnedWriter implements ResponseWriter {
        private final ResponseWriter delegate;
        private final OwnedResponse owned;
        private boolean released;

        OwnedWriter(ResponseWriter delegate, OwnedResponse owned) {
            this.delegate = delegate;
            this.owned = owned;
        }

        @Override public WriteStatus writeMore(ChunkSink sink) {
            if (released) { throw new IllegalStateException("Writer closed"); }
            try {
                WriteStatus status = Objects.requireNonNull(delegate.writeMore(sink), "write status");
                if (status == WriteStatus.COMPLETE) { close(); }
                return status;
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        @Override public void close() {
            if (released) { return; }
            released = true;
            try { delegate.close(); }
            finally {
                writer = null;
                owned.close();
            }
        }
    }

    private final class FixedError implements Response, ResponseWriter {
        private final TextProtocolDecoder.ErrorCode code;
        private final ByteBuffer bytes;
        private boolean released;

        FixedError(TextProtocolDecoder.ErrorCode code, boolean suppressed) {
            this.code = code == null ? TextProtocolDecoder.ErrorCode.BAD_COMMAND_LINE : code;
            bytes = ByteBuffer.wrap(suppressed ? EMPTY : switch (this.code) {
                case UNKNOWN_COMMAND -> UNKNOWN;
                case BAD_COMMAND_LINE -> BAD_LINE;
                case INVALID_DELTA -> BAD_DELTA;
                case INVALID_EXPTIME -> BAD_EXPTIME;
                case BAD_DATA_CHUNK -> BAD_DATA;
                case OBJECT_TOO_LARGE -> TOO_LARGE;
                case OUT_OF_MEMORY -> NO_MEMORY;
            });
        }

        @Override public ResponseKind kind() { return ResponseKind.valueOf(code.kind().name()); }
        @Override public RetrievalSnapshot snapshot() { return null; }
        @Override public boolean includeCas() { return false; }
        @Override public long number() { return 0; }
        @Override public String text() { return code.text(); }
        @Override public List<String> statsLines() { return null; }

        @Override public WriteStatus writeMore(ChunkSink sink) {
            if (released) { throw new IllegalStateException("Writer closed"); }
            Objects.requireNonNull(sink, "sink");
            // Two-byte maximum also obeys the smallest legal configured chunk size.
            while (bytes.hasRemaining()) {
                int count = bytes.get(bytes.position()) == '\r' ? 2 : 1;
                if (sink.remainingCapacity() < count) { return WriteStatus.IN_PROGRESS; }
                int end = bytes.limit();
                bytes.limit(bytes.position() + count);
                try { sink.accept(bytes); }
                finally { bytes.limit(end); }
            }
            close();
            return WriteStatus.COMPLETE;
        }

        @Override public void close() {
            if (released) { return; }
            released = true;
            writer = null;
        }
    }

    private static final byte[] EMPTY = new byte[0];
    private static final byte[] UNKNOWN = "ERROR\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BAD_LINE = "CLIENT_ERROR bad command line format\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BAD_DELTA = "CLIENT_ERROR invalid numeric delta argument\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BAD_EXPTIME = "CLIENT_ERROR invalid exptime argument\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BAD_DATA = "CLIENT_ERROR bad data chunk\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TOO_LARGE = "SERVER_ERROR object too large for cache\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NO_MEMORY = "SERVER_ERROR out of memory storing object\r\n".getBytes(StandardCharsets.US_ASCII);
}

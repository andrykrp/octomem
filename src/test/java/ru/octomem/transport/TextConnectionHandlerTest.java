package ru.octomem.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import ru.octomem.engine.RetrievalSnapshot;
import ru.octomem.protocol.*;

/** Supplemental owner-loop tests exercise exact callback and chunk boundaries. */
class TextConnectionHandlerTest {
    @Test void factoryRejectsInvalidArgumentsBeforeCodecAllocation() {
        assertThrows(IllegalArgumentException.class, () -> new TextConnectionFactory(0, 1, c -> null, r -> null));
        assertThrows(IllegalArgumentException.class, () -> new TextConnectionFactory(1, 0, c -> null, r -> null));
        assertThrows(NullPointerException.class, () -> new TextConnectionFactory(1, 1, null, r -> null));
        assertThrows(NullPointerException.class, () -> new TextConnectionFactory(1, 1, c -> null, null));
        assertThrows(NullPointerException.class, () -> new TextConnectionFactory(1, 1, c -> null, r -> null).create(null));
    }

    @Test void responseCancelBeforeEncodeReleasesExactCommandOnceAndAllowsNext() throws Exception {
        try (Local l = new Local(false)) {
            Command command = l.parse("set borrowed 0 0 3\r\nabc\r\n");
            Response response = l.handler.handle(command);
            assertEquals(35, l.resources.usedBodyBytes());
            response.close(); response.close();
            assertEquals(1, l.responseCloses.get());
            assertEquals(0, l.writerCloses.get());
            assertThrows(IllegalStateException.class, command::keys);
            assertEquals(0, l.resources.usedBodyBytes());
            assertThrows(IllegalArgumentException.class, () -> l.handler.decoder().release(command));
            Command next = l.parse("version\r\n");
            l.handler.handle(next).close();
            assertEquals(2, l.responseCloses.get());
        }
    }

    @Test void writerCancelAndCompleteAreIdempotentAndForbidPostReleaseCallback() throws Exception {
        for (boolean complete : List.of(false, true)) {
            try (Local l = new Local(complete)) {
                Command command = l.parse("set borrowed 0 0 3\r\nabc\r\n");
                Response response = l.handler.handle(command);
                ResponseWriter writer = l.handler.encode(response);
                assertEquals(complete ? WriteStatus.COMPLETE : WriteStatus.IN_PROGRESS, writer.writeMore(new Sink(2)));
                if (!complete) {
                    assertEquals(1, command.keys().size());
                    assertEquals(35, l.global.get());
                }
                writer.close(); writer.close(); response.close(); l.handler.close(); l.handler.close();
                assertEquals(1, l.writerCloses.get()); assertEquals(1, l.responseCloses.get());
                assertEquals(0, l.global.get());
                assertThrows(IllegalStateException.class, command::type);
                assertThrows(IllegalStateException.class, () -> writer.writeMore(new Sink(2)));
                assertEquals(1, l.writerCalls.get());
            }
        }
    }

    @Test void handlerRejectsForeignDuplicateAndClosedOperations() throws Exception {
        try (Local l = new Local(false)) {
            assertThrows(NullPointerException.class, () -> l.handler.handle(null));
            assertThrows(NullPointerException.class, () -> l.handler.protocolError(null));
            assertThrows(IllegalArgumentException.class, () -> l.handler.encode(null));
            Command command = l.parse("version\r\n");
            Response response = l.handler.handle(command);
            assertThrows(IllegalStateException.class, () -> l.handler.handle(command));
            assertThrows(IllegalStateException.class, () -> l.handler.protocolError(new ProtocolException(ProtocolException.Kind.ERROR, false)));
            assertThrows(IllegalArgumentException.class, () -> l.handler.encode(l.delegate));
            l.handler.encode(response);
            assertThrows(IllegalArgumentException.class, () -> l.handler.encode(response));
            l.handler.close();
            assertThrows(IllegalStateException.class, () -> l.handler.encode(response));
            assertThrows(IllegalStateException.class, () -> l.handler.handle(command));
        }
    }

    @Test void fixedErrorsHaveExactSafeMetadataBoundedChunksAtomicCrlfAndBackpressure() throws Exception {
        for (String request : List.of("unknown\r\n", "set k x 0 0\r\n", "set k 0 0 0\r\nX\n",
                "set k 0 0 40\r\n" + "x".repeat(40) + "\r\n")) {
            try (Local l = new Local(false)) {
                ProtocolException failure = assertThrows(ProtocolException.class, () -> l.parse(request));
                Response error = l.handler.protocolError(failure);
                assertNull(error.snapshot()); assertFalse(error.includeCas()); assertEquals(0, error.number()); assertNull(error.statsLines());
                String expected;
                if (request.startsWith("unknown")) { expected = "ERROR\r\n"; assertEquals(ResponseKind.ERROR, error.kind()); assertEquals("ERROR", error.text()); }
                else if (request.contains(" x ")) { expected = "CLIENT_ERROR bad command line format\r\n"; assertEquals(ResponseKind.CLIENT_ERROR, error.kind()); }
                else if (request.contains("X\n")) { expected = "CLIENT_ERROR bad data chunk\r\n"; assertEquals("bad data chunk", error.text()); }
                else { expected = "SERVER_ERROR object too large for cache\r\n"; assertEquals(ResponseKind.SERVER_ERROR, error.kind()); }
                ResponseWriter writer = l.handler.encode(error);
                Sink sink = new Sink(0);
                assertEquals(WriteStatus.IN_PROGRESS, writer.writeMore(sink));
                assertThrows(NullPointerException.class, () -> writer.writeMore(null));
                while (sink.output.size() < expected.length() - 2) {
                    sink.remaining = 1;
                    assertEquals(WriteStatus.IN_PROGRESS, writer.writeMore(sink));
                }
                sink.remaining = 1;
                assertEquals(WriteStatus.IN_PROGRESS, writer.writeMore(sink));
                assertEquals(expected.length() - 2, sink.output.size(), "CRLF must wait for two bytes");
                sink.remaining = 2;
                assertEquals(WriteStatus.COMPLETE, writer.writeMore(sink));
                assertEquals(expected, sink.output.toString(StandardCharsets.US_ASCII));
                writer.close(); error.close();
                assertThrows(IllegalStateException.class, () -> writer.writeMore(sink));
            }
        }
    }

    @Test void fixedErrorSafeFallbackCancelAndSinkFailureCleanup() {
        try (Local l = new Local(false)) {
            Response error = l.handler.protocolError(new ProtocolException(ProtocolException.Kind.CLIENT_ERROR, true));
            assertEquals("bad command line format", error.text());
            ResponseWriter writer = l.handler.encode(error);
            assertThrows(IllegalStateException.class, () -> l.handler.handle(null));
            ChunkSink failing = new ChunkSink() {
                public int remainingCapacity() { return 2; }
                public void accept(ByteBuffer chunk) { throw new IllegalStateException("injected sink"); }
            };
            assertThrows(IllegalStateException.class, () -> writer.writeMore(failing));
            l.handler.close(); l.handler.close(); error.close();
            assertThrows(IllegalStateException.class, () -> writer.writeMore(new Sink(2)));
        }
    }

    static final class Sink implements ChunkSink {
        int remaining;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Sink(int capacity) { remaining = capacity; }
        public int remainingCapacity() { return remaining; }
        public void accept(ByteBuffer chunk) {
            int size = chunk.remaining(); assertTrue(size <= remaining); assertTrue(size <= 2);
            byte[] bytes = new byte[size]; chunk.get(bytes);
            assertNotEquals((byte) '\r', bytes[size - 1], "CR cannot end a chunk");
            if (bytes[0] == '\n') fail("LF cannot start a chunk");
            output.writeBytes(bytes); remaining -= size;
        }
    }

    static final class Local implements AutoCloseable {
        final AtomicLong global = new AtomicLong();
        final ConnectionResources resources = new ConnectionResources(global, 1056, 1024);
        final AtomicInteger responseCloses = new AtomicInteger();
        final AtomicInteger writerCloses = new AtomicInteger();
        final AtomicInteger writerCalls = new AtomicInteger();
        Command current;
        final Response delegate = new Response() {
            public ResponseKind kind() { return ResponseKind.VERSION; }
            public RetrievalSnapshot snapshot() { return null; }
            public boolean includeCas() { return false; }
            public long number() { return 0; }
            public String text() { return "test"; }
            public List<String> statsLines() { return null; }
            public void close() { current.keys().size(); responseCloses.incrementAndGet(); }
        };
        final TextConnectionHandler handler;
        Local(boolean complete) {
            resources.acquireLine();
            handler = new TextConnectionFactory(1024, 64, c -> delegate, r -> new ResponseWriter() {
                public WriteStatus writeMore(ChunkSink sink) { current.keys().size(); writerCalls.incrementAndGet(); return complete ? WriteStatus.COMPLETE : WriteStatus.IN_PROGRESS; }
                public void close() { current.keys().size(); writerCloses.incrementAndGet(); }
            }).create(resources);
        }
        Command parse(String input) throws ProtocolException {
            ByteBuffer bytes = ByteBuffer.wrap(input.getBytes(StandardCharsets.US_ASCII));
            while (bytes.hasRemaining()) {
                handler.decoder().append(bytes);
                Command parsed = handler.decoder().nextCommand();
                if (parsed != null) { current = parsed; return parsed; }
            }
            current = handler.decoder().nextCommand(); return current;
        }
        public void close() { handler.close(); resources.close(); assertEquals(0, global.get()); }
    }
}

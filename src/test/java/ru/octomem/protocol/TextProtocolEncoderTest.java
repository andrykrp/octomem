package ru.octomem.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.octomem.engine.RetrievalSnapshot;
import ru.octomem.storage.Item;

/** Independent literal oracle: tests/reports/T007-encoder/oracle.md. */
class TextProtocolEncoderTest {
    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.ISO_8859_1); }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 7, 19, 256, 4096, Integer.MAX_VALUE})
    void allKindsHaveExactFraming(int max) {
        for (ResponseKind kind : List.of(ResponseKind.STORED, ResponseKind.NOT_STORED,
                ResponseKind.EXISTS, ResponseKind.NOT_FOUND, ResponseKind.DELETED,
                ResponseKind.TOUCHED, ResponseKind.OK, ResponseKind.RESET, ResponseKind.ERROR)) {
            verify(new Reply(kind), bytes(kind.name() + "\r\n"), max);
        }
        for (ResponseKind kind : List.of(ResponseKind.VERSION, ResponseKind.CLIENT_ERROR,
                ResponseKind.SERVER_ERROR)) {
            Reply reply = new Reply(kind);
            reply.text = "1.0.0 (octomem) ~!";
            verify(reply, bytes(kind.name() + " 1.0.0 (octomem) ~!\r\n"), max);
        }
        String[] numbers = {"0", "1", "9", "10", "9223372036854775807",
                "9223372036854775808", "18446744073709551615"};
        for (String number : numbers) {
            Reply reply = new Reply(ResponseKind.NUMBER);
            reply.number = Long.parseUnsignedLong(number);
            verify(reply, bytes(number + "\r\n"), max);
        }
        verify(retrieval(false), bytes("END\r\n"), max);
        Reply stats = new Reply(ResponseKind.STATS);
        stats.stats = List.of("STAT pid 123", "STAT version 1.0 (octomem)", "STAT x ~");
        verify(stats, bytes("STAT pid 123\r\nSTAT version 1.0 (octomem)\r\nSTAT x ~\r\nEND\r\n"), max);
        stats = new Reply(ResponseKind.STATS);
        stats.stats = List.of();
        verify(stats, bytes("END\r\n"), max);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 5, 17, 255, 256, 257, 4096})
    void binaryRetrievalUnsignedHeadersAndZeroLength(int max) {
        for (boolean cas : new boolean[]{false, true}) {
            Generated item = new Generated(new byte[]{(byte) 0x80, (byte) 0xff, 'k'}, 513);
            item.flags = -1;
            item.cas = -1;
            Generated empty = new Generated(bytes("empty"), 0);
            empty.flags = Integer.MIN_VALUE;
            empty.cas = Long.MIN_VALUE;
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            expected.writeBytes(bytes("VALUE "));
            expected.writeBytes(item.key);
            expected.writeBytes(bytes(" 4294967295 513" + (cas ? " 18446744073709551615" : "") + "\r\n"));
            for (int i = 0; i < 513; i++) expected.write(Generated.at(i));
            expected.writeBytes(bytes("\r\nVALUE empty 2147483648 0"
                    + (cas ? " 9223372036854775808" : "") + "\r\n\r\nEND\r\n"));
            verify(retrieval(cas, item, empty), expected.toByteArray(), max);
            assertEquals(513, item.readBytes);
            assertEquals(0, empty.readBytes);
            assertEquals(1, item.keyCalls);
        }
    }

    @Test
    void maximumRawKeyAndLongTextStreamBeyondScratch() {
        byte[] key = new byte[250];
        Arrays.fill(key, (byte) 0xfe);
        Generated item = new Generated(key, 0);
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes(bytes("VALUE "));
        expected.writeBytes(key);
        expected.writeBytes(bytes(" 0 0\r\n\r\nEND\r\n"));
        verify(retrieval(false, item), expected.toByteArray(), 11);
        Reply reply = new Reply(ResponseKind.VERSION);
        reply.text = "x".repeat(10001);
        verify(reply, bytes("VERSION " + reply.text + "\r\n"), 23);
    }

    @Test
    void generatedCrlfWaitsForTwoButBinaryCrlfCanSplit() {
        Reply reply = new Reply(ResponseKind.OK);
        ResponseWriter writer = new TextProtocolEncoder(2).encode(reply);
        CopySink sink = new CopySink(1);
        assertEquals(WriteStatus.IN_PROGRESS, writer.writeMore(sink));
        assertArrayEquals(bytes("O"), sink.out.toByteArray());
        sink.capacity = 1;
        writer.writeMore(sink);
        assertArrayEquals(bytes("OK"), sink.out.toByteArray());
        for (int i = 0; i < 3; i++) {
            sink.capacity = 1;
            assertEquals(WriteStatus.IN_PROGRESS, writer.writeMore(sink));
            assertEquals(2, sink.out.size());
            assertEquals(0, reply.closes);
        }
        sink.capacity = 2;
        assertEquals(WriteStatus.COMPLETE, writer.writeMore(sink));
        assertArrayEquals(bytes("OK\r\n"), sink.out.toByteArray());
        assertEquals(1, reply.closes);
        writer.close();
        assertEquals(1, reply.closes);
        ResponseWriter finishedWriter = writer;
        CopySink finishedSink = sink;
        assertThrows(IllegalStateException.class, () -> finishedWriter.writeMore(finishedSink));

        Generated item = new Generated(bytes("k"), 2) {
            @Override int byteAt(long offset) { return offset == 0 ? '\r' : '\n'; }
        };
        reply = retrieval(false, item);
        writer = new TextProtocolEncoder(2).encode(reply);
        sink = new CopySink(2);
        while (sink.out.size() < bytes("VALUE k 0 2\r\n").length) {
            sink.capacity = 2;
            writer.writeMore(sink);
        }
        assertEquals(0, item.readBytes);
        sink.capacity = 1;
        writer.writeMore(sink);
        assertEquals(1, item.readBytes);
        sink.capacity = 1;
        writer.writeMore(sink);
        assertEquals(2, item.readBytes);
        writer.close();
    }

    @Test
    void lazyStatsNeverReadAheadAndValidateIncrementally() {
        List<Integer> fetched = new ArrayList<>();
        Reply reply = new Reply(ResponseKind.STATS);
        reply.stats = new AbstractList<>() {
            @Override public int size() { return Integer.MAX_VALUE; }
            @Override public String get(int index) {
                fetched.add(index);
                if (index != 0) fail("read ahead before first line drained");
                return "STAT x " + "a".repeat(1000) + "\n";
            }
        };
        ResponseWriter writer = new TextProtocolEncoder(2).encode(reply);
        assertTrue(fetched.isEmpty());
        CopySink sink = new CopySink(0);
        assertEquals(WriteStatus.IN_PROGRESS, writer.writeMore(sink));
        assertTrue(fetched.isEmpty());
        for (int i = 0; i < 100; i++) {
            sink.capacity = 2;
            assertEquals(WriteStatus.IN_PROGRESS, writer.writeMore(sink));
        }
        assertEquals(List.of(0), fetched);
        assertEquals(200, sink.out.size());
        assertEquals(0, reply.closes);
        assertThrows(IllegalArgumentException.class, () -> {
            for (int i = 0; i < 1000; i++) { sink.capacity = 2; writer.writeMore(sink); }
        });
        assertEquals(1, reply.closes);
        writer.close();
        assertEquals(1, reply.closes);
    }

    @Test
    void rejectsMalformedTextBeforeOwnershipTransfer() {
        for (int max : new int[]{Integer.MIN_VALUE, -1, 0, 1}) {
            assertThrows(IllegalArgumentException.class, () -> new TextProtocolEncoder(max));
        }
        TextProtocolEncoder encoder = new TextProtocolEncoder(8);
        assertThrows(NullPointerException.class, () -> encoder.encode(null));
        for (ResponseKind kind : List.of(ResponseKind.VERSION, ResponseKind.CLIENT_ERROR, ResponseKind.SERVER_ERROR)) {
            for (String text : Arrays.asList(null, "", "x\r", "x\n", "\0", "\t", "\u007f", "\u0080", "\u20ac")) {
                Reply reply = new Reply(kind);
                reply.text = text;
                Class<? extends RuntimeException> expected = text == null ? NullPointerException.class : IllegalArgumentException.class;
                assertThrows(expected, () -> encoder.encode(reply));
                assertEquals(0, reply.closes);
                reply.close();
            }
        }
    }

    @Test
    void rejectsMalformedStatsDuringWriteAndCloses() {
        for (String text : Arrays.asList(null, "", "STAT", "stat x 1", "STAT x", "STAT  1", "STAT x ",
                "STAT x\t1", "STAT x \n", "STAT x \r", "STAT x \u007f", "STAT x \u0080", "STAT abc")) {
            Reply reply = new Reply(ResponseKind.STATS);
            reply.stats = Arrays.asList("STAT valid 1", text);
            ResponseWriter writer = new TextProtocolEncoder(3).encode(reply);
            CopySink sink = new CopySink(10000);
            Class<? extends RuntimeException> expected = text == null ? NullPointerException.class : IllegalArgumentException.class;
            assertThrows(expected,
                    () -> { for (int i = 0; i < 100; i++) writer.writeMore(sink); });
            assertTrue(new String(sink.out.toByteArray(), StandardCharsets.US_ASCII).startsWith("STAT valid 1\r\n"));
            assertEquals(1, reply.closes);
            writer.close();
            assertEquals(1, reply.closes);
            assertThrows(IllegalStateException.class, () -> writer.writeMore(sink));
        }
    }

    @Test
    void cancellationReleasesVisitedAndUnvisitedItemsAtEveryOffset() {
        for (int stop = 0; stop < 65; stop++) {
            Generated a = new Generated(bytes("a"), 41);
            Generated b = new Generated(bytes("b"), 100);
            Reply reply = retrieval(true, a, b);
            ResponseWriter writer = new TextProtocolEncoder(2).encode(reply);
            CopySink sink = new CopySink(2);
            for (int i = 0; i < stop; i++) { sink.capacity = 2; writer.writeMore(sink); }
            writer.close();
            writer.close();
            assertEquals(1, reply.closes);
            assertEquals(1, reply.snapshot.closes);
            assertTrue(a.closed);
            assertTrue(b.closed);
            assertThrows(IllegalStateException.class, () -> writer.writeMore(sink));
        }
    }

    @Test
    void callbacksFailuresAndCleanupExceptionsPreserveOriginalFailure() {
        for (boolean capacityFailure : new boolean[]{false, true}) {
            for (boolean error : new boolean[]{false, true}) {
                Reply reply = retrieval(false, new Generated(bytes("a"), 20), new Generated(bytes("b"), 20));
                Throwable original = error ? new AssertionError("callback") : new IllegalArgumentException("callback");
                RuntimeException cleanup = new RuntimeException("cleanup");
                reply.cleanup = cleanup;
                ResponseWriter writer = new TextProtocolEncoder(8).encode(reply);
                ChunkSink sink = new ChunkSink() {
                    void explode() { if (original instanceof Error e) throw e; throw (RuntimeException) original; }
                    public int remainingCapacity() { if (capacityFailure) explode(); return 20; }
                    public void accept(ByteBuffer chunk) { explode(); }
                };
                assertSame(original, assertThrows(Throwable.class, () -> writer.writeMore(sink)));
                assertArrayEquals(new Throwable[]{cleanup}, original.getSuppressed());
                assertEquals(1, reply.closes);
                assertTrue(reply.snapshot.items.stream().allMatch(item -> item.closed));
                writer.close();
                assertEquals(1, reply.closes);
            }
        }
    }

    @Test
    void inputSourcesAndSinksWithInvalidProgressFailClosed() {
        for (int mode = 0; mode < 6; mode++) {
            final int failureMode = mode;
            Generated broken = new Generated(bytes("a"), 20) {
                @Override public int readValue(long offset, ByteBuffer dst) {
                    return switch (failureMode) {
                        case 0 -> 0;
                        case 1 -> -1;
                        case 2 -> dst.remaining() + 1;
                        case 3 -> 1;
                        case 4 -> { dst.put((byte) 1); dst.limit(dst.limit() - 1); yield 1; }
                        default -> throw new IllegalStateException("source failed");
                    };
                }
            };
            Reply reply = retrieval(false, broken, new Generated(bytes("unvisited"), 10));
            ResponseWriter writer = new TextProtocolEncoder(8).encode(reply);
            CopySink sink = new CopySink(10000);
            assertThrows(IllegalStateException.class, () -> { for (int i = 0; i < 20; i++) writer.writeMore(sink); });
            assertEquals(1, reply.closes);
            assertTrue(reply.snapshot.items.get(1).closed);
        }
        for (int mode = 0; mode < 4; mode++) {
            int failureMode = mode;
            Reply reply = new Reply(ResponseKind.OK);
            ResponseWriter writer = new TextProtocolEncoder(8).encode(reply);
            ChunkSink sink = new ChunkSink() {
                public int remainingCapacity() { return failureMode == 0 ? -1 : 100; }
                public void accept(ByteBuffer chunk) {
                    if (failureMode == 1) return;
                    chunk.position(chunk.limit());
                    if (failureMode == 2) chunk.limit(chunk.limit() + 1);
                }
            };
            assertThrows(RuntimeException.class, () -> writer.writeMore(failureMode == 3 ? null : sink));
            assertEquals(1, reply.closes);
        }
    }

    @Test
    void writersHaveIndependentCursorsAndShortReads() {
        TextProtocolEncoder encoder = new TextProtocolEncoder(7);
        Generated item = new Generated(bytes("k"), 67);
        item.shortReads = true;
        Reply a = retrieval(false, item);
        Reply b = new Reply(ResponseKind.NUMBER);
        b.number = -1;
        ResponseWriter wa = encoder.encode(a), wb = encoder.encode(b);
        CopySink sa = new CopySink(7), sb = new CopySink(7);
        boolean doneA = false, doneB = false;
        for (int i = 0; i < 100 && (!doneA || !doneB); i++) {
            if (!doneA) { sa.capacity = 7; doneA = wa.writeMore(sa) == WriteStatus.COMPLETE; }
            if (!doneB) { sb.capacity = 7; doneB = wb.writeMore(sb) == WriteStatus.COMPLETE; }
        }
        assertTrue(doneA && doneB);
        assertArrayEquals(bytes("18446744073709551615\r\n"), sb.out.toByteArray());
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes(bytes("VALUE k 0 67\r\n"));
        for (int i = 0; i < 67; i++) expected.write(Generated.at(i));
        expected.writeBytes(bytes("\r\nEND\r\n"));
        assertArrayEquals(expected.toByteArray(), sa.out.toByteArray());
        assertEquals(1, a.closes);
        assertEquals(1, b.closes);
    }

    @Test
    void invalidItemMetadataAndCursorCallbacksReleaseAllPins() {
        for (byte[] key : new byte[][]{new byte[0], new byte[251], bytes("a b"), bytes("a\0"), bytes("a\u007f")}) {
            Reply reply = retrieval(false, new Generated(key, 0), new Generated(bytes("unvisited"), 1));
            ResponseWriter writer = new TextProtocolEncoder(8).encode(reply);
            CopySink sink = new CopySink(1000);
            assertThrows(IllegalArgumentException.class, () -> { for (int i = 0; i < 50; i++) writer.writeMore(sink); });
            assertEquals(1, reply.closes);
            assertTrue(reply.snapshot.items.get(1).closed);
        }
        Reply negative = retrieval(false, new Generated(bytes("k"), -1));
        ResponseWriter writer = new TextProtocolEncoder(8).encode(negative);
        assertThrows(IllegalArgumentException.class, () -> writer.writeMore(new CopySink(10)));
        assertEquals(1, negative.closes);
        for (boolean next : new boolean[]{false, true}) {
            Reply reply = new Reply(ResponseKind.RETRIEVAL);
            reply.snapshot = new Snapshot(List.of(new Generated(bytes("unvisited"), 1))) {
                @Override public Iterator<Item> values() {
                    return new Iterator<>() {
                        public boolean hasNext() { if (!next) throw new IllegalArgumentException("hasNext"); return true; }
                        public Item next() { throw new IllegalArgumentException("next"); }
                    };
                }
            };
            ResponseWriter failed = new TextProtocolEncoder(8).encode(reply);
            assertThrows(IllegalArgumentException.class, () -> failed.writeMore(new CopySink(10)));
            assertEquals(1, reply.closes);
            assertTrue(reply.snapshot.items.getFirst().closed);
        }
    }

    @Test
    void requiredPayloadFailuresLeaveCallerOwnershipAndSameCleanupIsNotSuppressed() {
        for (ResponseKind kind : Arrays.asList(null, ResponseKind.RETRIEVAL, ResponseKind.STATS)) {
            Reply reply = new Reply(kind);
            assertThrows(NullPointerException.class, () -> new TextProtocolEncoder(2).encode(reply));
            assertEquals(0, reply.closes);
        }
        Reply reply = new Reply(ResponseKind.OK);
        RuntimeException same = new RuntimeException("same");
        reply.cleanup = same;
        ResponseWriter writer = new TextProtocolEncoder(2).encode(reply);
        ChunkSink sink = new ChunkSink() {
            public int remainingCapacity() { throw same; }
            public void accept(ByteBuffer source) { fail("not called"); }
        };
        assertSame(same, assertThrows(RuntimeException.class, () -> writer.writeMore(sink)));
        assertEquals(0, same.getSuppressed().length);
        assertEquals(1, reply.closes);
        writer.close();
    }

    @Test
    void hugeGeneratedValueUsesBoundedReadsWithoutFullCopy() {
        assertTrue(Runtime.getRuntime().maxMemory() < Integer.MAX_VALUE,
                "Run encoder tests with -Xmx128m so generated value exceeds heap");
        Generated item = new Generated(bytes("huge"), Integer.MAX_VALUE);
        Reply reply = retrieval(false, item, new Generated(bytes("unvisited"), 1));
        ResponseWriter writer = new TextProtocolEncoder(31).encode(reply);
        byte[] header = bytes("VALUE huge 0 2147483647\r\n");
        long[] consumed = {0};
        ChunkSink discard = new ChunkSink() {
            public int remainingCapacity() { return 31; }
            public void accept(ByteBuffer source) {
                while (source.hasRemaining()) {
                    long offset = consumed[0]++;
                    int expected = offset < header.length ? header[(int) offset] & 255 : Generated.at(offset - header.length);
                    assertEquals(expected, source.get() & 255);
                }
            }
        };
        for (int i = 0; i < 10000; i++) assertEquals(WriteStatus.IN_PROGRESS, writer.writeMore(discard));
        assertTrue(item.readBytes > 300000);
        assertEquals(header.length + item.readBytes, consumed[0]);
        assertTrue(item.maxRequest <= 31);
        assertEquals(1, item.keyCalls);
        assertEquals(0, reply.closes);
        writer.close();
        assertTrue(reply.snapshot.items.get(1).closed);
        assertEquals(1, reply.closes);
    }

    static void verify(Reply reply, byte[] expected, int max) {
        ResponseWriter writer = new TextProtocolEncoder(max).encode(reply);
        CopySink sink = new CopySink(0);
        for (int calls = 0; calls < expected.length * 4 + 100; calls++) {
            int capacity = switch (calls % 5) { case 0 -> 0; case 1 -> 1; case 2 -> 2; case 3 -> 17; default -> 10000; };
            sink.capacity = capacity;
            int before = sink.out.size();
            WriteStatus status = writer.writeMore(sink);
            int emitted = sink.out.size() - before;
            assertTrue(emitted <= max && emitted <= capacity);
            byte[] actual = sink.out.toByteArray();
            assertTrue(actual.length <= expected.length);
            assertArrayEquals(Arrays.copyOf(expected, actual.length), actual);
            assertEquals(actual.length == expected.length ? WriteStatus.COMPLETE : WriteStatus.IN_PROGRESS, status);
            assertEquals(status == WriteStatus.COMPLETE ? 1 : 0, reply.closes);
            if (status == WriteStatus.COMPLETE) {
                writer.close();
                writer.close();
                assertEquals(1, reply.closes);
                assertThrows(IllegalStateException.class, () -> writer.writeMore(sink));
                return;
            }
        }
        fail("writer did not finish");
    }

    static Reply retrieval(boolean includeCas, Generated... items) {
        Reply reply = new Reply(ResponseKind.RETRIEVAL);
        reply.snapshot = new Snapshot(List.of(items));
        reply.cas = includeCas;
        return reply;
    }

    static class CopySink implements ChunkSink {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int capacity;
        CopySink(int capacity) { this.capacity = capacity; }
        public int remainingCapacity() { return capacity; }
        public void accept(ByteBuffer source) {
            int size = source.remaining();
            assertTrue(size > 0 && size <= capacity);
            int end = source.limit();
            while (source.hasRemaining()) out.write(source.get());
            // Poison consumed storage, never retaining a borrowed reference.
            if (!source.isReadOnly()) for (int i = end - size; i < end; i++) source.put(i, (byte) 0xa5);
            assertEquals(end, source.position());
            assertEquals(end, source.limit());
            capacity -= size;
        }
    }

    static class Reply implements Response {
        final ResponseKind kind;
        Snapshot snapshot;
        boolean cas;
        long number;
        String text;
        List<String> stats;
        int closes;
        RuntimeException cleanup;
        Reply(ResponseKind kind) { this.kind = kind; }
        public ResponseKind kind() { return kind; }
        public RetrievalSnapshot snapshot() { return snapshot; }
        public boolean includeCas() { return cas; }
        public long number() { return number; }
        public String text() { return text; }
        public List<String> statsLines() { return stats; }
        public void close() {
            closes++;
            if (snapshot != null) snapshot.close();
            if (cleanup != null) throw cleanup;
        }
    }

    static class Snapshot implements RetrievalSnapshot {
        final List<Generated> items;
        int closes;
        boolean cursor;
        Snapshot(List<Generated> items) { this.items = items; }
        public long capturedClock() { return 123; }
        public Iterator<Item> values() {
            assertFalse(cursor, "only one cursor permitted");
            cursor = true;
            return new Iterator<>() {
                int index;
                public boolean hasNext() { assertEquals(0, closes); return index < items.size(); }
                public Item next() { if (!hasNext()) throw new NoSuchElementException(); return items.get(index++); }
            };
        }
        public void close() { closes++; items.forEach(item -> item.closed = true); }
    }

    static class Generated implements Item {
        final byte[] key;
        final int length;
        int flags;
        long cas;
        int keyCalls;
        long readBytes;
        int maxRequest;
        boolean closed;
        boolean shortReads;
        Generated(byte[] key, int length) { this.key = key; this.length = length; }
        static int at(long offset) { return (int) (offset & 255); }
        int byteAt(long offset) { return at(offset); }
        public byte[] key() { assertFalse(closed); keyCalls++; return key.clone(); }
        public int flags() { assertFalse(closed); return flags; }
        public long casId() { assertFalse(closed); return cas; }
        public long expiryTime() { return 0; }
        public int valueLength() { assertFalse(closed); return length; }
        public int readValue(long offset, ByteBuffer dst) {
            assertFalse(closed);
            assertEquals(readBytes, offset, "writer must maintain its own offset across consumed buffers");
            maxRequest = Math.max(maxRequest, dst.remaining());
            assertTrue(dst.remaining() <= 256, "no value-sized read buffer");
            int count = (int) Math.min(dst.remaining(), length - offset);
            if (shortReads) count = Math.min(count, 1);
            for (int i = 0; i < count; i++) dst.put((byte) byteAt(offset + i));
            readBytes += count;
            return count;
        }
        public boolean isExpired(long now) { return false; }
        public boolean isActive() { return false; }
        public void markActive() { }
    }
}

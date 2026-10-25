package ru.octomem.contracts;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import ru.octomem.engine.GetResult;
import ru.octomem.engine.CacheEngine;
import ru.octomem.engine.CacheCapacityException;
import ru.octomem.engine.KeySequence;
import ru.octomem.engine.KeyVisitor;
import ru.octomem.engine.RetrievalSnapshot;
import ru.octomem.protocol.ChunkSink;
import ru.octomem.protocol.ProtocolEncoder;
import ru.octomem.protocol.Response;
import ru.octomem.protocol.ResponseKind;
import ru.octomem.protocol.ResponseWriter;
import ru.octomem.protocol.WriteStatus;
import ru.octomem.storage.Item;
import ru.octomem.storage.ItemLease;

/** API client examples only: fixtures do not verify a production backend or encoder. */
class OwnershipClientContractTest {
    @Test
    void borrowedVisitorCountsOccurrencesAndUsesOriginalBackingSlices() {
        byte[] line = "a missing a b".getBytes(StandardCharsets.US_ASCII);
        KeySequence keys = new LineKeys(line);
        assertEquals(4, keys.size());
        List<String> seen = new ArrayList<>();
        keys.forEachKey((backing, offset, length) -> {
            assertSame(line, backing);
            assertTrue(offset >= 0 && length > 0 && length <= backing.length - offset);
            seen.add(new String(backing, offset, length, StandardCharsets.US_ASCII));
        });
        assertEquals(List.of("a", "missing", "a", "b"), seen);
        assertEquals(0, new LineKeys(new byte[0]).size());
        new LineKeys(new byte[0]).forEachKey((b, o, n) -> fail("empty sequence has no keys"));
    }

    @Test
    void snapshotOwnsBorrowedItemsIncludingUnvisitedOnClose() {
        FixedSnapshot snapshot = new FixedSnapshot(new TestItem("a", "123"), new TestItem("b", "456"));
        Iterator<Item> cursor = snapshot.values();
        assertEquals(1234L, snapshot.capturedClock());
        assertSame(snapshot.items.get(0), cursor.next());
        assertFalse(AutoCloseable.class.isAssignableFrom(Item.class));
        assertEquals(2, snapshot.pins);
        snapshot.close();
        snapshot.close();
        assertEquals(0, snapshot.pins);
        assertEquals(1, snapshot.releases);
        assertEquals(0, snapshot.metadataReservations);
    }

    @Test
    void readersHaveIndependentOffsetsAndCannotMutatePublishedKey() {
        TestItem item = new TestItem("key", "abcdef");
        byte[] exposed = item.key();
        exposed[0] = 'X';
        assertArrayEquals("key".getBytes(StandardCharsets.US_ASCII), item.key());
        ByteBuffer first = ByteBuffer.allocate(2);
        ByteBuffer second = ByteBuffer.allocate(3);
        assertEquals(2, item.readValue(0, first));
        assertEquals(3, item.readValue(0, second));
        assertArrayEquals(new byte[] {'a', 'b'}, first.array());
        assertArrayEquals(new byte[] {'a', 'b', 'c'}, second.array());
        first.clear();
        assertEquals(2, item.readValue(2, first));
        assertArrayEquals(new byte[] {'c', 'd'}, first.array());
        assertEquals(0, item.readValue(6, ByteBuffer.allocate(2)));
        assertEquals(0, item.readValue(0, ByteBuffer.allocate(0)));
        assertThrows(IndexOutOfBoundsException.class, () -> item.readValue(-1, ByteBuffer.allocate(1)));
        assertThrows(IndexOutOfBoundsException.class, () -> item.readValue(Long.MAX_VALUE, ByteBuffer.allocate(1)));
        assertThrows(NullPointerException.class, () -> item.readValue(0, null));
        assertThrows(java.nio.ReadOnlyBufferException.class,
                () -> item.readValue(0, ByteBuffer.allocate(1).asReadOnlyBuffer()));
        long cas = item.casId();
        item.markActive();
        assertTrue(item.isActive());
        assertEquals(cas, item.casId());
        assertEquals("18446744073709551615", Long.toUnsignedString(cas));
        assertEquals("4294967295", Integer.toUnsignedString(item.flags()));
    }

    @Test
    void singleResultAndLeaseCanBeScopedWithoutCheckedCloseExceptions() {
        TestItem item = new TestItem("a", "v");
        AtomicInteger pins = new AtomicInteger(1);
        ItemLease lease = new ItemLease() {
            private boolean closed;
            public Item item() { return item; }
            public void close() { if (!closed) { closed = true; pins.decrementAndGet(); } }
        };
        GetResult hit = new GetResult() {
            public boolean found() { return true; }
            public Item item() { return lease.item(); }
            public void close() { lease.close(); }
        };
        try (hit) {
            assertTrue(hit.found());
            assertSame(item, hit.item());
            assertEquals(1, pins.get());
        }
        hit.close();
        assertEquals(0, pins.get());
        try (GetResult miss = new GetResult() {
            public boolean found() { return false; }
            public Item item() { return null; }
            public void close() { }
        }) {
            assertFalse(miss.found());
            assertNull(miss.item());
        }
    }

    @Test
    void writerProgressHonorsZeroTemporaryOneAndAtomicCrlfTwo() {
        FixedSnapshot snapshot = new FixedSnapshot(new TestItem("a", "v"), new TestItem("b", "w"));
        Response response = new SnapshotResponse(snapshot);
        ProtocolEncoder example = TokenWriter::new;
        ResponseWriter writer = example.encode(response);
        CopySink sink = new CopySink(2);
        sink.remaining = 0;
        assertEquals(WriteStatus.IN_PROGRESS, writer.writeMore(sink));
        assertEquals(0, sink.chunks.size());
        sink.remaining = 1;
        assertEquals(WriteStatus.IN_PROGRESS, writer.writeMore(sink));
        assertArrayEquals(new byte[] {'X'}, sink.chunks.get(0));
        sink.remaining = 1;
        assertEquals(WriteStatus.IN_PROGRESS, writer.writeMore(sink));
        assertEquals(1, sink.chunks.size());
        assertEquals(2, snapshot.pins);
        sink.remaining = 2;
        assertEquals(WriteStatus.COMPLETE, writer.writeMore(sink));
        assertArrayEquals(new byte[] {'\r', '\n'}, sink.chunks.get(1));
        assertEquals(0, snapshot.pins);
        writer.close();
        response.close();
        assertEquals(1, snapshot.releases);
        assertThrows(IllegalStateException.class, () -> writer.writeMore(sink));
        assertThrows(IllegalArgumentException.class, () -> new CopySink(1));
        assertThrows(IllegalArgumentException.class, () -> new CopySink(0));
    }

    @Test
    void cancellationDisconnectShutdownAndSinkFailureCloseUnvisitedItems() {
        for (String reason : List.of("cancel", "disconnect", "shutdown", "sink failure")) {
            FixedSnapshot snapshot = new FixedSnapshot(new TestItem("a", "v"), new TestItem("b", "w"));
            ResponseWriter writer = new TokenWriter(new SnapshotResponse(snapshot));
            if (reason.equals("sink failure")) {
                assertThrows(IllegalStateException.class, () -> writer.writeMore(new ChunkSink() {
                    public int remainingCapacity() { return 2; }
                    public void accept(ByteBuffer chunk) { throw new IllegalStateException("test failure"); }
                }));
            } else {
                writer.close();
            }
            writer.close();
            assertEquals(1, snapshot.releases, reason);
            assertEquals(0, snapshot.pins, reason);
            assertEquals(0, snapshot.metadataReservations, reason);
        }
    }

    @Test
    void failedOwnershipHandoffLeavesCallerResponsibleForClose() {
        FixedSnapshot snapshot = new FixedSnapshot(new TestItem("a", "v"));
        assertThrows(IllegalStateException.class, () -> {
            boolean transferred = false;
            try {
                throw new IllegalStateException("response factory refused handoff");
            } finally {
                if (!transferred) { snapshot.close(); }
            }
        });
        assertEquals(1, snapshot.releases);
        assertEquals(0, snapshot.pins);
    }

    @Test
    void engineClientRequestsOneCommandSnapshotNeverSingleKeyLookups() throws Exception {
        KeySequence keys = new LineKeys("a missing a b".getBytes(StandardCharsets.US_ASCII));
        FixedSnapshot captured = new FixedSnapshot(new TestItem("a", "A"), new TestItem("b", "B"));
        AtomicInteger snapshotCalls = new AtomicInteger();
        CacheEngine engine = (CacheEngine) java.lang.reflect.Proxy.newProxyInstance(
                CacheEngine.class.getClassLoader(), new Class<?>[] {CacheEngine.class}, (proxy, method, arguments) -> {
                    assertEquals("snapshot", method.getName(), "retrieval must not loop over single-key get");
                    assertSame(keys, arguments[0]);
                    snapshotCalls.incrementAndGet();
                    return captured;
                });
        RetrievalSnapshot result = engine.snapshot(keys);
        Response response = new SnapshotResponse(result);
        Iterator<Item> values = response.snapshot().values();
        assertEquals("a", new String(values.next().key(), StandardCharsets.US_ASCII));
        assertEquals("b", new String(values.next().key(), StandardCharsets.US_ASCII));
        assertFalse(values.hasNext());
        assertEquals(1, snapshotCalls.get());
        response.close();
        assertEquals(0, captured.pins);
    }

    @Test
    void capacityFailureCannotBeConfusedWithSnapshotMissOrEmitPartialValue() {
        AtomicInteger outputCalls = new AtomicInteger();
        CacheEngine engine = (CacheEngine) java.lang.reflect.Proxy.newProxyInstance(
                CacheEngine.class.getClassLoader(), new Class<?>[] {CacheEngine.class}, (proxy, method, arguments) -> {
                    assertEquals("snapshotAndTouch", method.getName());
                    throw new CacheCapacityException();
                });
        assertThrows(CacheCapacityException.class, () -> {
            try (RetrievalSnapshot ignored = engine.snapshotAndTouch(
                    new LineKeys("a b a".getBytes(StandardCharsets.US_ASCII)), 60)) {
                outputCalls.incrementAndGet();
            }
        });
        assertEquals(0, outputCalls.get(), "admission happens before output");
    }

    /** A linear borrowed pass over a fixed test command, not a protocol parser. */
    private record LineKeys(byte[] line) implements KeySequence {
        public int size() {
            if (line.length == 0) { return 0; }
            int count = 1;
            for (byte value : line) { if (value == ' ') { count++; } }
            return count;
        }
        public void forEachKey(KeyVisitor visitor) {
            int start = 0;
            for (int end = 0; end <= line.length; end++) {
                if (end == line.length || line[end] == ' ') {
                    if (end > start) { visitor.accept(line, start, end - start); }
                    start = end + 1;
                }
            }
        }
    }

    /** Fixed immutable bytes suffice to exercise independent reader clients. */
    private static final class TestItem implements Item {
        private final byte[] key;
        private final byte[] value;
        private boolean active;
        TestItem(String key, String value) {
            this.key = key.getBytes(StandardCharsets.US_ASCII);
            this.value = value.getBytes(StandardCharsets.US_ASCII);
        }
        public byte[] key() { return key.clone(); }
        public int flags() { return -1; }
        public long expiryTime() { return 0; }
        public long casId() { return -1L; }
        public int valueLength() { return value.length; }
        public int readValue(long offset, ByteBuffer dst) {
            Objects.checkFromIndexSize(offset, 0L, (long) value.length);
            int count = Math.min(dst.remaining(), value.length - (int) offset);
            dst.put(value, (int) offset, count);
            return count;
        }
        public boolean isExpired(long now) { return false; }
        public boolean isActive() { return active; }
        public void markActive() { active = true; }
    }

    /** Already captured fixture, intentionally no table, locks or capture algorithm. */
    private static final class FixedSnapshot implements RetrievalSnapshot {
        final List<Item> items;
        int pins;
        int releases;
        int metadataReservations = 1;
        private boolean cursorTaken;
        FixedSnapshot(Item... items) { this.items = List.of(items); pins = items.length; }
        public long capturedClock() { return 1234; }
        public Iterator<Item> values() {
            if (cursorTaken) { throw new IllegalStateException("one cursor"); }
            cursorTaken = true;
            return items.iterator();
        }
        public void close() {
            if (metadataReservations != 0) { pins = 0; metadataReservations = 0; releases++; }
        }
    }

    private record SnapshotResponse(RetrievalSnapshot snapshot) implements Response {
        public ResponseKind kind() { return ResponseKind.RETRIEVAL; }
        public boolean includeCas() { return true; }
        public long number() { return 0; }
        public String text() { return null; }
        public List<String> statsLines() { return null; }
        public void close() { snapshot.close(); }
    }

    /** Deliberately emits only X + CRLF, not an implementation of wire encoding. */
    private static final class TokenWriter implements ResponseWriter {
        private final Response response;
        private boolean sentX;
        private boolean closed;
        TokenWriter(Response response) { this.response = response; }
        public WriteStatus writeMore(ChunkSink sink) {
            if (closed) { throw new IllegalStateException("closed"); }
            try {
                if (!sentX && sink.remainingCapacity() > 0) {
                    sink.accept(ByteBuffer.wrap(new byte[] {'X'}));
                    sentX = true;
                }
                if (sentX && sink.remainingCapacity() >= 2) {
                    sink.accept(ByteBuffer.wrap(new byte[] {'\r', '\n'}));
                    close();
                    return WriteStatus.COMPLETE;
                }
                return WriteStatus.IN_PROGRESS;
            } catch (RuntimeException failure) {
                close();
                throw failure;
            }
        }
        public void close() { if (!closed) { closed = true; response.close(); } }
    }

    private static final class CopySink implements ChunkSink {
        final List<byte[]> chunks = new ArrayList<>();
        int remaining;
        CopySink(int capacity) {
            if (capacity < 2) { throw new IllegalArgumentException("capacity below CRLF"); }
            remaining = capacity;
        }
        public int remainingCapacity() { return remaining; }
        public void accept(ByteBuffer chunk) {
            int size = chunk.remaining();
            if (size > remaining) { throw new IllegalArgumentException("overflow"); }
            byte[] copy = new byte[size];
            chunk.get(copy);
            chunks.add(copy);
            remaining -= size;
        }
    }
}

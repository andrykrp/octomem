package ru.octomem.engine;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.storage.FfmSlabAllocator;
import ru.octomem.storage.Item;
import ru.octomem.storage.SegmentedItemTable;
import ru.octomem.util.Clock;

import static org.junit.jupiter.api.Assertions.*;

/** Independent T-018 oracle; see the pre-inspection frozen oracle-plan.md. */
@Timeout(15)
class CompatibilityRecheckTest {
    private static final long NOW = 1_700_000_000L;
    private static final byte[] KEY = bytes("k");

    static Stream<Arguments> ttlCases() {
        return Stream.of("touch", "getAndTouch", "snapshotAndTouch")
                .flatMap(operation -> Stream.of(0L, 30L, NOW + 30, -1L)
                        .map(ttl -> Arguments.of(operation, ttl)));
    }

    @ParameterizedTest(name = "{0} ttl={1} preserves wire CAS and pinned data")
    @MethodSource("ttlCases")
    void ttlOnlyVersionsPreserveCas(String operation, long ttl) throws Exception {
        try (Rig r = new Rig(65536, 4096)) {
            assertEquals(StoreResult.STORED, r.engine.set(KEY, bytes("old"), 7, 5));
            try (GetResult held = r.engine.getWithCas(KEY)) {
                long token = held.item().casId();
                long updatedToken = update(r, operation, ttl);
                boolean live;
                long currentToken;
                try (GetResult current = r.engine.getWithCas(KEY)) {
                    live = current.found();
                    currentToken = live ? current.item().casId() : token;
                    if (live) {
                        assertEquals("old", value(current.item()));
                        assertEquals(7, current.item().flags());
                        assertEquals(ttl == 0 ? 0 : ttl > 2592000 ? ttl : NOW + ttl,
                                current.item().expiryTime());
                    }
                }
                StoreResult cas = r.engine.cas(KEY, bytes("new"), 9, 0, token);
                assertAll(
                        () -> assertEquals(ttl >= 0, live, "negative TTL expires after update"),
                        () -> { if (!operation.equals("touch")) assertEquals(token, updatedToken, "returned CAS"); },
                        () -> assertEquals(token, currentToken, "later gets CAS"),
                        () -> assertEquals(ttl < 0 ? StoreResult.NOT_FOUND : StoreResult.STORED, cas,
                                "original CAS remains usable unless expired"),
                        () -> assertEquals("old", value(held.item())),
                        () -> assertEquals(NOW + 5, held.item().expiryTime()),
                        () -> assertEquals(token, held.item().casId()));
                if (ttl >= 0) {
                    try (GetResult changed = r.engine.getWithCas(KEY)) {
                        assertNotEquals(token, changed.item().casId(), "data CAS must still advance");
                        assertEquals("new", value(changed.item()));
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("ttlCases")
    void ttlDeadlineActuallyChanges(String operation, long ttl) throws Exception {
        try (Rig r = new Rig(65536, 4096)) {
            assertEquals(StoreResult.STORED, r.engine.set(KEY, bytes("old"), 7, 5));
            update(r, operation, ttl);
            r.clock.seconds = NOW + 6;
            try (GetResult result = r.engine.get(KEY)) { assertEquals(ttl >= 0, result.found()); }
            r.clock.seconds = NOW + 30;
            try (GetResult result = r.engine.get(KEY)) { assertEquals(ttl == 0, result.found()); }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"touch", "getAndTouch", "snapshotAndTouch"})
    void missingTtlUpdateAndInterveningDataWriter(String operation) throws Exception {
        try (Rig r = new Rig(65536, 4096)) {
            if (operation.equals("touch")) assertFalse(r.engine.touch(KEY, 0));
            else if (operation.equals("getAndTouch")) {
                try (GetResult missing = r.engine.getAndTouch(KEY, 0)) { assertFalse(missing.found()); }
            } else {
                try (RetrievalSnapshot missing = r.engine.snapshotAndTouch(keys(), 0)) {
                    assertFalse(missing.values().hasNext());
                }
            }
            assertEquals(StoreResult.STORED, r.engine.set(KEY, bytes("old"), 7, 0));
            long token;
            try (GetResult old = r.engine.getWithCas(KEY)) { token = old.item().casId(); }
            update(r, operation, 10);
            assertEquals(StoreResult.STORED, r.engine.set(KEY, bytes("writer"), 8, 0));
            assertEquals(StoreResult.EXISTS, r.engine.cas(KEY, bytes("stale"), 0, 0, token));
            try (GetResult current = r.engine.get(KEY)) {
                assertEquals("writer", value(current.item()));
                assertNotEquals(token, current.item().casId());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedSetInvalidatesVisibilityButNotHeldReader(boolean oversized) {
        try (Rig r = new Rig(4096, 4096)) {
            byte[] original = bytes("o".repeat(3000));
            assertEquals(StoreResult.STORED, r.engine.set(KEY, original, 7, 0));
            try (GetResult first = r.engine.getWithCas(KEY); GetResult second = r.engine.get(KEY)) {
                long token = first.item().casId();
                long charged = r.allocator.usedMemory();
                StoreResult result = r.engine.set(KEY, new byte[oversized ? 4097 : 3000], 9, 0);
                boolean visible;
                try (GetResult current = r.engine.get(KEY)) { visible = current.found(); }
                assertAll(
                        () -> assertEquals(oversized ? StoreResult.TOO_LARGE : StoreResult.OUT_OF_MEMORY, result),
                        () -> assertFalse(visible, "failed SET must remove stale cache value"),
                        () -> assertEquals(new String(original, StandardCharsets.US_ASCII), value(first.item())),
                        () -> assertEquals(token, first.item().casId()),
                        () -> assertEquals(charged, r.allocator.usedMemory(), "held retired record remains charged"));
                first.close();
                assertEquals(charged, r.allocator.usedMemory(), "second lease still owns memory");
                assertEquals(new String(original, StandardCharsets.US_ASCII), value(second.item()));
            }
            assertEquals(0, r.allocator.usedMemory(), "last lease frees invalidated record");
            assertEquals(0, r.engine.serviceMetadataUsedBytes());
            assertEquals(StoreResult.STORED, r.engine.set(KEY, bytes("fresh"), 0, 0));
        }
    }

    static Stream<Arguments> failedOtherStores() {
        return Stream.of("replace", "append", "prepend", "cas")
                .flatMap(op -> Stream.of(false, true).map(size -> Arguments.of(op, size)));
    }

    @ParameterizedTest
    @MethodSource("failedOtherStores")
    void otherStorageResourceFailuresRetainOriginal(String operation, boolean oversized) {
        try (Rig r = new Rig(4096, 4096)) {
            String original = "o".repeat(3000);
            assertEquals(StoreResult.STORED, r.engine.set(KEY, bytes(original), 7, 30));
            try (GetResult held = r.engine.getWithCas(KEY)) {
                long token = held.item().casId();
                byte[] proposed = new byte[oversized ? 4097 : operation.equals("append")
                        || operation.equals("prepend") ? 1 : 3000];
                StoreResult result = switch (operation) {
                    case "replace" -> r.engine.replace(KEY, proposed, 9, 0);
                    case "append" -> r.engine.append(KEY, proposed);
                    case "prepend" -> r.engine.prepend(KEY, proposed);
                    case "cas" -> r.engine.cas(KEY, proposed, 9, 0, token);
                    default -> throw new AssertionError(operation);
                };
                assertEquals(oversized ? StoreResult.TOO_LARGE : StoreResult.OUT_OF_MEMORY, result);
                try (GetResult current = r.engine.getWithCas(KEY)) {
                    assertTrue(current.found());
                    assertEquals(original, value(current.item()));
                    assertEquals(token, current.item().casId());
                    assertEquals(NOW + 30, current.item().expiryTime());
                    assertEquals(7, current.item().flags());
                }
            }
        }
    }

    @Test
    void conditionalNegativesDoNotInvalidate() {
        try (Rig r = new Rig(4096, 4096)) {
            assertEquals(StoreResult.STORED, r.engine.set(KEY, bytes("old"), 7, 30));
            try (GetResult held = r.engine.getWithCas(KEY)) {
                long token = held.item().casId();
                assertEquals(StoreResult.NOT_STORED, r.engine.add(KEY, bytes("new"), 0, 0));
                assertEquals(StoreResult.EXISTS, r.engine.cas(KEY, bytes("new"), 0, 0, token + 1));
                assertEquals(StoreResult.NOT_FOUND, r.engine.cas(bytes("absent"), bytes("new"), 0, 0, token));
                try (GetResult current = r.engine.getWithCas(KEY)) {
                    assertEquals("old", value(current.item()));
                    assertEquals(token, current.item().casId());
                }
            }
        }
    }

    static Stream<Arguments> combinedFailures() {
        return Stream.of("append", "prepend").flatMap(op -> Stream.of(false, true)
                .map(size -> Arguments.of(op, size)));
    }

    @ParameterizedTest
    @MethodSource("combinedFailures")
    void admittedFragmentCombinedFailureIsNotStored(String operation, boolean oversized) {
        try (Rig r = new Rig(8192, 4096)) {
            String original = "o".repeat(3000);
            assertEquals(StoreResult.STORED, r.engine.set(KEY, bytes(original), 7, 30));
            try (GetResult held = r.engine.getWithCas(KEY)) {
                long token = held.item().casId();
                byte[] fragment = new byte[oversized ? 3000 : 1];
                StoreResult result = operation.equals("append") ? r.engine.append(KEY, fragment)
                        : r.engine.prepend(KEY, fragment);
                try (GetResult current = r.engine.getWithCas(KEY)) {
                    assertAll(() -> assertEquals(StoreResult.NOT_STORED, result, "combined allocation phase"),
                            () -> assertTrue(current.found()),
                            () -> assertEquals(original, value(current.item())),
                            () -> assertEquals(token, current.item().casId()),
                            () -> assertEquals(NOW + 30, current.item().expiryTime()),
                            () -> assertEquals(7, current.item().flags()));
                }
            }
        }
    }

    private static long update(Rig r, String operation, long ttl) throws Exception {
        if (operation.equals("touch")) {
            assertTrue(r.engine.touch(KEY, ttl));
            return 0;
        }
        if (operation.equals("getAndTouch")) {
            try (GetResult result = r.engine.getAndTouch(KEY, ttl)) {
                assertTrue(result.found());
                assertEquals("old", value(result.item()));
                return result.item().casId();
            }
        }
        try (RetrievalSnapshot result = r.engine.snapshotAndTouch(keys(), ttl)) {
            var cursor = result.values();
            assertTrue(cursor.hasNext());
            Item item = cursor.next();
            assertEquals("old", value(item));
            assertFalse(cursor.hasNext());
            return item.casId();
        }
    }

    private static KeySequence keys() {
        return new KeySequence() {
            public int size() { return 1; }
            public void forEachKey(KeyVisitor visitor) { visitor.accept(KEY, 0, KEY.length); }
        };
    }

    private static String value(Item item) {
        ByteBuffer buffer = ByteBuffer.allocate(item.valueLength());
        assertEquals(buffer.capacity(), item.readValue(0, buffer));
        return new String(buffer.array(), StandardCharsets.US_ASCII);
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }

    private static final class TestClock implements Clock {
        volatile long seconds = NOW;
        public long currentTimeSeconds() { return seconds; }
        public long nanoTime() { return 0; }
    }

    /** Real native pressure; separate ample metadata budget avoids synthetic allocator failures. */
    private static final class Rig implements AutoCloseable {
        final TestClock clock = new TestClock();
        final FfmSlabAllocator allocator;
        final SegmentedItemTable table;
        final DefaultCacheEngine engine;

        Rig(long nativeBytes, int maxItem) {
            allocator = new FfmSlabAllocator(nativeBytes, 4096, maxItem);
            table = new SegmentedItemTable(1, 2, 65536);
            engine = new DefaultCacheEngine(allocator, table, clock,
                    new AtomicStatsRegistry(Map.of("pid", "1", "version", "recheck", "pointer_size", "64",
                            "threads", "1", "limit_maxbytes", "65536")),
                    new DefaultCacheEngine.Settings(65536, maxItem, "noeviction", 86400));
            engine.start();
        }

        public void close() {
            try { engine.stop(); }
            finally { try { table.close(); } finally { allocator.close(); } }
            assertEquals(0, allocator.usedMemory());
        }
    }
}

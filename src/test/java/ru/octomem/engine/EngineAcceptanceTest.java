package ru.octomem.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.octomem.storage.FfmSlabAllocator;
import ru.octomem.storage.Item;
import ru.octomem.storage.MemoryAllocator;
import ru.octomem.storage.SegmentedItemTable;
import ru.octomem.storage.SlabHandle;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.stats.Counter;
import ru.octomem.stats.SlabClassStats;
import ru.octomem.util.Clock;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Independent T-010/011/012 acceptance; oracle provenance is in tests/reports/engine. */
@Timeout(30)
class EngineAcceptanceTest {
    static final long NOW = 1_700_000_000L;
    static final long MEMORY = 1 << 20;

    @org.junit.jupiter.api.BeforeAll
    static void recordAndVerifyActualForkLimits() {
        var arguments = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
        System.out.println("ENGINE_ACCEPTANCE_VM_ARGS=" + arguments);
        System.out.println("ENGINE_ACCEPTANCE_MAX_HEAP=" + Runtime.getRuntime().maxMemory());
        System.out.println("ENGINE_ACCEPTANCE_CPUS=" + Runtime.getRuntime().availableProcessors());
        assertTrue(Runtime.getRuntime().maxMemory() <= 512L * 1024 * 1024, "Test fork must be bounded");
        assertEquals(2, Runtime.getRuntime().availableProcessors());
        assertTrue(arguments.contains("-XX:MaxDirectMemorySize=128m"));
        assertTrue(arguments.contains("-XX:+UseSerialGC"));
    }

    @Test
    void ttlLiteralBoundariesAndExpiredConditionalStores() {
        long[] inputs = {0, 1, 2_592_000, 2_592_001, -1, Long.MIN_VALUE, Long.MAX_VALUE};
        long[] expiry = {0, 1_700_000_001L, 1_702_592_000L, 2_592_001, -1, -1, Long.MAX_VALUE};
        for (int i = 0; i < inputs.length; i++) {
            try (Rig r = new Rig()) {
                assertEquals(StoreResult.STORED, r.engine.set(b("k"), b("v"), -1, inputs[i]));
                boolean live = i < 3 || i == 6;
                try (GetResult result = r.engine.get(b("k"))) {
                    assertEquals(live, result.found(), "exptime=" + inputs[i]);
                    if (live) {
                        assertEquals(expiry[i], result.item().expiryTime());
                        assertEquals(-1, result.item().flags());
                    } else {
                        assertNull(result.item());
                    }
                }
                if (i == 1 || i == 2 || i == 6) {
                    r.clock.now = expiry[i];
                    assertFalse(found(r, "k"));
                }
                if (!live || i == 1 || i == 2 || i == 6) {
                    assertEquals(StoreResult.NOT_STORED, r.engine.replace(b("k"), b("x"), 0, 0));
                    assertEquals(StoreResult.STORED, r.engine.add(b("k"), b("fresh"), 0, 0));
                }
            }
        }
    }

    @Test
    void allStoreOutcomesAndImmutablePinnedVersions() throws Exception {
        try (Rig r = new Rig()) {
            assertEquals(StoreResult.NOT_STORED, r.engine.replace(b("k"), b("x"), 0, 0));
            assertEquals(StoreResult.NOT_STORED, r.engine.append(b("k"), b("x")));
            assertEquals(StoreResult.NOT_STORED, r.engine.prepend(b("k"), b("x")));
            assertEquals(StoreResult.NOT_FOUND, r.engine.cas(b("k"), b("x"), 0, 0, -1));
            byte[] key = b("k");
            byte[] value = b("middle");
            assertEquals(StoreResult.STORED, r.engine.add(key, value, -1, 100));
            key[0] = 'z';
            value[0] = 'X';
            try (GetResult old = r.engine.getWithCas(b("k"))) {
                Version original = version(old.item());
                assertEquals("middle", original.value);
                assertEquals(StoreResult.NOT_STORED, r.engine.add(b("k"), b("bad"), 2, 1));
                assertEquals(StoreResult.EXISTS, r.engine.cas(b("k"), b("bad"), 2, 1, original.cas + 1));
                assertEquals(original, current(r, "k"));
                assertEquals(StoreResult.STORED, r.engine.append(b("k"), b("-right")));
                Version appended = current(r, "k");
                assertEquals("middle-right", appended.value);
                assertEquals(original.flags, appended.flags);
                assertEquals(original.expiry, appended.expiry);
                assertNotEquals(original.cas, appended.cas);
                assertEquals(StoreResult.STORED, r.engine.prepend(b("k"), b("left-")));
                Version prepended = current(r, "k");
                assertEquals("left-middle-right", prepended.value);
                assertEquals(original.flags, prepended.flags);
                assertEquals(original.expiry, prepended.expiry);
                assertNotEquals(appended.cas, prepended.cas);
                assertTrue(r.engine.touch(b("k"), 10));
                Version touched = current(r, "k");
                assertEquals(NOW + 10, touched.expiry);
                assertEquals(prepended.cas, touched.cas); // T-018: TTL-only keeps wire CAS.
                try (GetResult gat = r.engine.getAndTouch(b("k"), 20)) {
                    assertTrue(gat.found());
                    assertEquals(NOW + 20, gat.item().expiryTime());
                    assertEquals(touched.cas, gat.item().casId());
                }
                Version beforeCas = current(r, "k");
                assertEquals(StoreResult.STORED, r.engine.cas(b("k"), b("cas"), 42, 0, beforeCas.cas));
                assertEquals(StoreResult.STORED, r.engine.replace(b("k"), b("replace"), 7, 0));
                assertEquals(StoreResult.STORED, r.engine.set(b("k"), b("set"), 8, 0));
                assertEquals(original, version(old.item()));
                byte[] defensive = old.item().key();
                defensive[0] = 'x';
                assertArrayEquals(b("k"), old.item().key());
                assertTrue(r.engine.delete(b("k")));
                assertFalse(r.engine.delete(b("k")));
                assertEquals(original, version(old.item()));
                assertTrue(r.allocator.usedMemory() > 0);
            }
            assertEquals(0, r.allocator.usedMemory());
            assertFalse(r.engine.touch(b("k"), 1));
            try (GetResult missing = r.engine.getAndTouch(b("k"), 1)) {
                assertFalse(missing.found());
            }
        }
    }

    @Test
    void unsignedCountersAndNonnumericFailures() {
        try (Rig r = new Rig()) {
            assertEquals(CounterResult.Status.NOT_FOUND, r.engine.increment(b("missing"), -1).status());
            assertEquals(CounterResult.Status.NOT_FOUND, r.engine.decrement(b("missing"), -1).status());
            put(r, "n", "18446744073709551615", -1, 100);
            Version original = current(r, "n");
            assertCounter(r.engine.increment(b("n"), 1), 0);
            assertCounter(r.engine.increment(b("n"), Long.MIN_VALUE), Long.MIN_VALUE);
            assertEquals("9223372036854775808 ", current(r, "n").value);
            assertCounter(r.engine.decrement(b("n"), -1), 0);
            assertCounter(r.engine.increment(b("n"), -1), -1);
            assertCounter(r.engine.decrement(b("n"), Long.MIN_VALUE), Long.MAX_VALUE);
            Version after = current(r, "n");
            assertEquals(original.expiry, after.expiry);
            assertEquals(original.flags, after.flags);
            assertNotEquals(original.cas, after.cas);
            for (String invalid : List.of("", "abc", "12x", "18446744073709551616", "-1", "1.5")) {
                put(r, "n", invalid, 4, 50);
                Version before = current(r, "n");
                assertEquals(CounterResult.Status.NON_NUMERIC, r.engine.increment(b("n"), 1).status(), invalid);
                assertEquals(CounterResult.Status.NON_NUMERIC, r.engine.decrement(b("n"), 1).status(), invalid);
                assertEquals(before, current(r, "n"));
            }
        }
    }

    @Test
    void unsignedCasWrapUsesCoordinatorApprovedSeed() {
        for (long seed : new long[]{Long.MAX_VALUE, -2, -1}) {
            try (Rig r = new Rig(MEMORY, 4096, "noeviction", seed)) {
                put(r, "a", "old", 0, 0);
                assertEquals(seed + 1, current(r, "a").cas);
                assertEquals(StoreResult.STORED, r.engine.cas(b("a"), b("new"), 0, 0, seed + 1));
                assertEquals(seed + 2, current(r, "a").cas);
                put(r, "b", "other", 0, 0);
                assertEquals(seed + 3, current(r, "b").cas);
            }
        }
    }

    @Test
    void snapshotDeduplicatesAndPinsAllHitsBeforeCursorAdvances() throws Exception {
        try (Rig r = new Rig()) {
            put(r, "a", "A0", 11, 1);
            put(r, "b", "B0", 12, 1);
            put(r, "c", "C0", 13, 1);
            RetrievalSnapshot snapshot = r.engine.snapshot(keys("b", "missing", "a", "b", "c"));
            assertEquals(NOW, snapshot.capturedClock());
            assertEquals(3072, r.engine.snapshotMetadataUsedBytes());
            try (snapshot) {
                var cursor = snapshot.values();
                Item first = cursor.next();
                assertArrayEquals(b("b"), first.key());
                ByteBuffer head = ByteBuffer.allocate(1);
                assertEquals(1, first.readValue(0, head));
                assertEquals((byte) 'B', head.get(0));
                put(r, "a", "A1", 99, 0);
                assertTrue(r.engine.delete(b("b")));
                r.clock.now++;
                assertFalse(found(r, "c"));
                put(r, "missing", "late", 0, 0);
                assertEquals("B0", text(first));
                assertEquals("A0", text(cursor.next()));
                assertEquals("C0", text(cursor.next()));
                assertFalse(cursor.hasNext());
            }
            snapshot.close();
            assertEquals(0, r.engine.snapshotMetadataUsedBytes());
        }
    }

    @Test
    void multiGatTouchesDuplicatesExactlyOnceAndReturnsNewVersions() throws Exception {
        try (Rig r = new Rig()) {
            put(r, "a", "A", 1, 1);
            put(r, "b", "B", 2, 1);
            Version a = current(r, "a");
            Version b = current(r, "b");
            int before = r.allocator.allocations;
            try (RetrievalSnapshot snapshot = r.engine.snapshotAndTouch(keys("b", "a", "b", "missing"), 100)) {
                assertEquals(before + 2, r.allocator.allocations);
                List<Item> items = items(snapshot);
                assertEquals(List.of("b", "a"), items.stream().map(i -> new String(i.key(), StandardCharsets.US_ASCII)).toList());
                assertEquals(NOW + 100, items.get(0).expiryTime());
                assertEquals(NOW + 100, items.get(1).expiryTime());
                assertEquals(a.cas, items.get(1).casId());
                assertEquals(b.cas, items.get(0).casId());
                assertEquals(version(items.get(0)), current(r, "b"));
                assertEquals(version(items.get(1)), current(r, "a"));
            }
        }
    }

    @Test
    void failureAtEveryMultiGatAllocationRollsBackEveryTargetAndReservation() throws Exception {
        for (int failurePosition = 1; failurePosition <= 3; failurePosition++) {
            try (Rig r = new Rig()) {
                put(r, "a", "A0", 11, 50);
                put(r, "b", "B0", 12, 60);
                put(r, "c", "C0", 13, 70);
                Map<String, Version> originals = Map.of("a", current(r, "a"), "b", current(r, "b"), "c", current(r, "c"));
                long nativeBefore = r.allocator.usedMemory();
                r.allocator.failAt = r.allocator.allocations + failurePosition;
                assertThrows(CacheCapacityException.class,
                    () -> r.engine.snapshotAndTouch(keys("c", "a", "b", "a", "missing"), 999));
                r.allocator.failAt = Integer.MAX_VALUE;
                for (var entry : originals.entrySet()) assertEquals(entry.getValue(), current(r, entry.getKey()));
                assertEquals(nativeBefore, r.allocator.usedMemory(), "failure position=" + failurePosition);
                assertEquals(0, r.engine.snapshotMetadataUsedBytes());
                try (RetrievalSnapshot recovery = r.engine.snapshotAndTouch(keys("a", "b", "c"), 999)) {
                    assertEquals(3, items(recovery).size());
                }
            }
        }
    }

    @Test
    void allocationDenialInvalidatesSetAndPreservesOtherMutations() throws Exception {
        for (int operation = 0; operation < 8; operation++) {
            try (Rig r = new Rig()) {
                put(r, "n", "123", 27, 100);
                Version before = current(r, "n");
                long bytes = r.allocator.usedMemory();
                r.allocator.failAt = r.allocator.allocations + 1;
                switch (operation) {
                    case 0 -> assertEquals(StoreResult.OUT_OF_MEMORY, r.engine.set(b("n"), b("456"), 9, 0));
                    case 1 -> assertEquals(StoreResult.OUT_OF_MEMORY, r.engine.replace(b("n"), b("456"), 9, 0));
                    case 2 -> assertEquals(StoreResult.OUT_OF_MEMORY, r.engine.append(b("n"), b("4")));
                    case 3 -> assertEquals(StoreResult.OUT_OF_MEMORY, r.engine.prepend(b("n"), b("4")));
                    case 4 -> assertEquals(StoreResult.OUT_OF_MEMORY, r.engine.cas(b("n"), b("456"), 9, 0, before.cas));
                    case 5 -> assertEquals(CounterResult.Status.OUT_OF_MEMORY, r.engine.increment(b("n"), 1).status());
                    case 6 -> assertThrows(CacheCapacityException.class, () -> r.engine.touch(b("n"), 1));
                    case 7 -> assertThrows(CacheCapacityException.class, () -> r.engine.getAndTouch(b("n"), 1));
                    default -> fail();
                }
                if (operation == 0) {
                    assertFalse(found(r, "n")); // Initial native SET failure invalidates.
                    assertEquals(0, r.allocator.usedMemory());
                } else {
                    assertEquals(before, current(r, "n"));
                    assertEquals(bytes, r.allocator.usedMemory());
                }
                assertEquals(0, r.engine.snapshotMetadataUsedBytes());
            }
        }
    }

    @Test
    void snapshotBudgetChargesOccurrencesAndRejectsBeforeTraversal() throws Exception {
        try (Rig r = new Rig(32768, 4096, "noeviction", 0)) {
            put(r, "a", "original", 2, 10);
            Version before = current(r, "a");
            RetrievalSnapshot held = r.engine.snapshot(keys("a", "a", "missing"));
            assertEquals(2048, r.engine.snapshotMetadataUsedBytes());
            try (held) {
                KeySequence rejected = new KeySequence() {
                    public int size() { return 1; }
                    public void forEachKey(KeyVisitor visitor) { fail("Admission must precede traversal"); }
                };
                assertThrows(CacheCapacityException.class, () -> r.engine.snapshot(rejected));
                assertThrows(CacheCapacityException.class, () -> r.engine.snapshotAndTouch(rejected, 999));
                assertEquals(before, current(r, "a"));
                assertEquals(2048, r.engine.snapshotMetadataUsedBytes());
            }
            held.close();
            assertEquals(0, r.engine.snapshotMetadataUsedBytes());
            try (RetrievalSnapshot recovered = r.engine.snapshot(keys("missing"))) {
                assertEquals(1024, r.engine.snapshotMetadataUsedBytes());
                assertTrue(items(recovered).isEmpty());
            }
            KeySequence huge = new KeySequence() {
                public int size() { return Integer.MAX_VALUE; }
                public void forEachKey(KeyVisitor visitor) { fail("Checked admission before allocation"); }
            };
            assertThrows(CacheCapacityException.class, () -> r.engine.snapshot(huge));
            assertEquals(0, r.engine.snapshotMetadataUsedBytes());
        }
    }

    @Test
    void flushImmediateDelayedAndAbsolutePreservesAlreadyCapturedViews() throws Exception {
        for (long delay : new long[]{0, 10, NOW + 10}) {
            try (Rig r = new Rig()) {
                put(r, "old", "old", 0, 0);
                try (RetrievalSnapshot old = r.engine.snapshot(keys("old"))) {
                    r.engine.flushAll(delay);
                    if (delay != 0) {
                        r.clock.now = NOW + 9;
                        assertTrue(found(r, "old"));
                        put(r, "during", "during", 0, 0);
                        r.clock.now = NOW + 10;
                        assertFalse(found(r, "during"));
                    }
                    assertFalse(found(r, "old"));
                    r.clock.now++;
                    put(r, "new", "new", 0, 0);
                    assertTrue(found(r, "new"));
                    assertEquals("old", text(old.values().next()));
                }
            }
        }
        try (Rig r = new Rig()) {
            r.clock.now = 2_592_000;
            put(r, "old", "old", 0, 0);
            r.clock.now = NOW;
            put(r, "x", "x", 0, 0);
            r.engine.flushAll(2_592_001);
            assertFalse(found(r, "old"), "Records created before absolute cutoff are invalidated immediately");
            assertTrue(found(r, "x"), "docs04: creation after oldest_live survives even when cutoff is in past");
        }
    }

    @Test
    void reclaimIsBoundedProgressiveAndDoesNotInvalidatePins() {
        try (Rig r = new Rig()) {
            for (int i = 0; i < 40; i++) put(r, "e" + i, "expired", 0, 1);
            put(r, "live", "live", 0, 0);
            try (GetResult pinned = r.engine.get(b("e0"))) {
                r.clock.now++;
                int removed = 0;
                for (int i = 0; i < 2048 && removed < 40; i++) {
                    int count = r.engine.reclaimExpired(1);
                    assertTrue(count >= 0 && count <= 1);
                    removed += count;
                }
                assertEquals(40, removed);
                assertTrue(found(r, "live"));
                assertEquals("expired", text(pinned.item()));
                assertThrows(IllegalArgumentException.class, () -> r.engine.reclaimExpired(0));
                assertThrows(IllegalArgumentException.class, () -> r.engine.reclaimExpired(-1));
            }
            assertTrue(r.engine.delete(b("live")));
            assertEquals(0, r.allocator.usedMemory());
        }
    }

    @Test
    void lifecycleStopsAdmissionButLeavesAllocatorAndOldPinsOwnedByCaller() throws Exception {
        try (Rig r = new Rig()) {
            r.engine.start();
            put(r, "a", "retained", 2, 50);
            GetResult lease = r.engine.get(b("a"));
            RetrievalSnapshot snapshot = r.engine.snapshot(keys("a"));
            r.engine.stop();
            r.engine.stop();
            assertTrue(r.table.isClosed());
            assertFalse(r.nativeAllocator.isClosed());
            assertEquals("retained", text(lease.item()));
            assertEquals("retained", text(snapshot.values().next()));
            assertTrue(r.allocator.usedMemory() > 0);
            lease.close();
            lease.close();
            snapshot.close();
            snapshot.close();
            assertEquals(0, r.allocator.usedMemory());
            assertEquals(0, r.engine.snapshotMetadataUsedBytes());
            assertThrows(IllegalStateException.class, r.engine::start);
            assertOutsideLifetime(r.engine);
        }
    }

    @Test
    void invalidInputsAndEmptyBinaryValues() throws Exception {
        try (Rig r = new Rig()) {
            for (byte[] key : new byte[][]{new byte[0], b("bad key"), b("a\n"), new byte[]{127}, new byte[251]}) {
                assertEquals(StoreResult.INVALID, r.engine.set(key, b("v"), 0, 0));
                assertEquals(StoreResult.INVALID, r.engine.add(key, b("v"), 0, 0));
                assertEquals(StoreResult.INVALID, r.engine.replace(key, b("v"), 0, 0));
                assertEquals(StoreResult.INVALID, r.engine.append(key, b("v")));
                assertEquals(StoreResult.INVALID, r.engine.prepend(key, b("v")));
                assertEquals(StoreResult.INVALID, r.engine.cas(key, b("v"), 0, 0, 0));
                assertThrows(IllegalArgumentException.class, () -> r.engine.get(key));
                assertThrows(IllegalArgumentException.class, () -> r.engine.getWithCas(key));
                assertThrows(IllegalArgumentException.class, () -> r.engine.delete(key));
                assertThrows(IllegalArgumentException.class, () -> r.engine.increment(key, 0));
                assertThrows(IllegalArgumentException.class, () -> r.engine.decrement(key, 0));
                assertThrows(IllegalArgumentException.class, () -> r.engine.touch(key, 0));
                assertThrows(IllegalArgumentException.class, () -> r.engine.getAndTouch(key, 0));
            }
            assertThrows(NullPointerException.class, () -> r.engine.set(null, b("v"), 0, 0));
            assertThrows(NullPointerException.class, () -> r.engine.set(b("a"), null, 0, 0));
            assertThrows(NullPointerException.class, () -> r.engine.get(null));
            assertThrows(NullPointerException.class, () -> r.engine.snapshot(null));
            assertThrows(IllegalArgumentException.class, () -> r.engine.snapshot(keys()));
            byte[] longest = new byte[250];
            Arrays.fill(longest, (byte) 'k');
            assertEquals(StoreResult.STORED, r.engine.set(longest, new byte[0], -1, 0));
            try (GetResult empty = r.engine.get(longest)) {
                assertTrue(empty.found());
                assertEquals(0, empty.item().valueLength());
                assertEquals(0, empty.item().readValue(0, ByteBuffer.allocate(4)));
            }
            byte[] binary = {0, 13, 10, -1, 65};
            assertEquals(StoreResult.STORED, r.engine.set(b("bin"), binary, 0, 0));
            try (GetResult result = r.engine.get(b("bin"))) {
                ByteBuffer target = ByteBuffer.allocate(5);
                assertEquals(5, result.item().readValue(0, target));
                assertArrayEquals(binary, target.array());
            }
        }
    }

    @Test
    void seededMapOracleCoversMixedConditionalMutationAndTime() {
        Random random = new Random(0x710012L);
        Map<String, Model> model = new HashMap<>();
        try (Rig r = new Rig()) {
            for (int step = 0; step < 1000; step++) {
                String key = "k" + random.nextInt(12);
                String value = "v" + step;
                int flags = random.nextInt();
                int ttl = random.nextInt(5);
                Model old = model.get(key);
                if (old != null && old.expiry != 0 && old.expiry <= r.clock.now) {
                    model.remove(key);
                    old = null;
                }
                int op = random.nextInt(7);
                switch (op) {
                    case 0 -> {
                        assertEquals(StoreResult.STORED, r.engine.set(b(key), b(value), flags, ttl));
                        model.put(key, new Model(value, flags, ttl == 0 ? 0 : r.clock.now + ttl));
                    }
                    case 1 -> {
                        assertEquals(old == null ? StoreResult.STORED : StoreResult.NOT_STORED,
                            r.engine.add(b(key), b(value), flags, ttl));
                        if (old == null) model.put(key, new Model(value, flags, ttl == 0 ? 0 : r.clock.now + ttl));
                    }
                    case 2 -> {
                        assertEquals(old == null ? StoreResult.NOT_STORED : StoreResult.STORED,
                            r.engine.replace(b(key), b(value), flags, ttl));
                        if (old != null) model.put(key, new Model(value, flags, ttl == 0 ? 0 : r.clock.now + ttl));
                    }
                    case 3 -> {
                        assertEquals(old != null, r.engine.delete(b(key)));
                        model.remove(key);
                    }
                    case 4 -> {
                        assertEquals(old == null ? StoreResult.NOT_STORED : StoreResult.STORED, r.engine.append(b(key), b("+")));
                        if (old != null) model.put(key, new Model(old.value + "+", old.flags, old.expiry));
                    }
                    case 5 -> r.clock.now++;
                    case 6 -> {
                        assertEquals(old == null ? StoreResult.NOT_STORED : StoreResult.STORED, r.engine.prepend(b(key), b("-")));
                        if (old != null) model.put(key, new Model("-" + old.value, old.flags, old.expiry));
                    }
                    default -> fail();
                }
                for (int index = 0; index < 12; index++) {
                    String candidate = "k" + index;
                    Model expected = model.get(candidate);
                    if (expected != null && expected.expiry != 0 && expected.expiry <= r.clock.now) {
                        model.remove(candidate);
                        expected = null;
                    }
                    try (GetResult actual = r.engine.get(b(candidate))) {
                        assertEquals(expected != null, actual.found(), "seed=0x710012 step=" + step + " key=" + candidate);
                        if (expected != null) {
                            assertEquals(expected.value, text(actual.item()));
                            assertEquals(expected.flags, actual.item().flags());
                            assertEquals(expected.expiry, actual.item().expiryTime());
                        }
                    }
                }
            }
        }
    }

    @Test
    void barrierCasRaceHasExactlyOneWinnerAndCountersHaveExactSum() throws Exception {
        try (Rig r = new Rig(); var pool = Executors.newFixedThreadPool(8)) {
            put(r, "cas", "initial", 0, 0);
            long token = current(r, "cas").cas;
            CyclicBarrier barrier = new CyclicBarrier(8);
            var futures = new ArrayList<java.util.concurrent.Future<StoreResult>>();
            for (int i = 0; i < 8; i++) {
                int id = i;
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return r.engine.cas(b("cas"), b("winner" + id), 0, 0, token);
                }));
            }
            int stored = 0;
            for (var future : futures) {
                StoreResult result = future.get(15, TimeUnit.SECONDS);
                if (result == StoreResult.STORED) stored++;
                else assertEquals(StoreResult.EXISTS, result);
            }
            assertEquals(1, stored);
            put(r, "n", "0", 0, 0);
            var counts = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) counts.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                for (int n = 0; n < 250; n++) assertEquals(CounterResult.Status.VALUE, r.engine.increment(b("n"), 1).status());
                return null;
            }));
            for (var future : counts) future.get(15, TimeUnit.SECONDS);
            assertEquals("2000", current(r, "n").value);
        }
    }

    @Test
    void oppositeOrderMultiGatSnapshotsNeverObservePartialPublication() throws Exception {
        try (Rig r = new Rig(); var pool = Executors.newFixedThreadPool(2)) {
            for (String key : List.of("a", "b", "c")) put(r, key, key, 0, 100);
            CyclicBarrier barrier = new CyclicBarrier(2);
            var writer = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                for (int i = 0; i < 500; i++) {
                    try (RetrievalSnapshot ignored = r.engine.snapshotAndTouch(keys("c", "b", "a", "c"), i % 2 == 0 ? 200 : 100)) { }
                }
                return null;
            });
            var reader = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                for (int i = 0; i < 500; i++) {
                    try (RetrievalSnapshot snapshot = r.engine.snapshot(keys("a", "b", "c"))) {
                        List<Item> values = items(snapshot);
                        assertEquals(3, values.size());
                        long expiry = values.get(0).expiryTime();
                        assertTrue(expiry == NOW + 100 || expiry == NOW + 200);
                        for (Item item : values) assertEquals(expiry, item.expiryTime());
                    }
                }
                return null;
            });
            writer.get(20, TimeUnit.SECONDS);
            reader.get(20, TimeUnit.SECONDS);
            assertEquals(0, r.engine.snapshotMetadataUsedBytes());
        }
    }

    @Test
    void noEvictionExhaustionAndTooLargeInvalidateOnlySetTarget() {
        try (Rig r = new Rig(32768, 4096, "noeviction", 0)) {
            String payload = "x".repeat(3000);
            for (int i = 0; i < 8; i++) put(r, "k" + i, payload, i, 0);
            Version original = current(r, "k0");
            // Retain the old block so failed SET cannot make room for extra.
            try (GetResult held = r.engine.get(b("k0"))) {
            assertEquals(StoreResult.OUT_OF_MEMORY, r.engine.set(b("k0"), b(payload), 99, 10));
            assertEquals(StoreResult.OUT_OF_MEMORY, r.engine.add(b("extra"), b(payload), 0, 0));
            assertFalse(found(r, "k0"));
            assertEquals(original, version(held.item()));
            }
            put(r, "k0", payload, 0, 0);
            assertEquals(StoreResult.TOO_LARGE, r.engine.set(b("k0"), new byte[4096], 0, 0));
            assertFalse(found(r, "k0"));
            put(r, "k0", payload, 0, 0);
            Version restored = current(r, "k0");
            assertEquals(StoreResult.TOO_LARGE, r.engine.append(b("k0"), new byte[4096]));
            assertEquals(StoreResult.TOO_LARGE, r.engine.prepend(b("k0"), new byte[4096]));
            assertEquals(restored, current(r, "k0"));
            for (int i = 0; i < 8; i++) assertTrue(found(r, "k" + i));
            assertEquals(0, r.stats.snapshot().counters().get(Counter.EVICTIONS));
            assertTrue(r.allocator.totalMemory() <= 32768);
        }
    }

    @Test
    void lruEvictionUsesActivityAndPinnedVictimBytesStayValid() {
        try (Rig r = new Rig(32768, 4096, "lru", 0)) {
            for (int i = 0; i < 8; i++) put(r, "k" + i, Integer.toString(i).repeat(3000), i, 0);
            try (GetResult pinned = r.engine.get(b("k0"))) {
                for (int i = 1; i < 8; i++) assertTrue(found(r, "k" + i));
                assertEquals(StoreResult.STORED, r.engine.set(b("new"), b("z".repeat(3000)), 0, 0));
                assertTrue(found(r, "k0"), "Pinned victim cannot yield capacity and is excluded");
                assertFalse(found(r, "k1"), "Oldest eligible unpinned victim is evicted");
                assertEquals("0".repeat(3000), text(pinned.item()));
                assertTrue(found(r, "new"));
                assertEquals(1L, r.stats.snapshot().counters().get(Counter.EVICTIONS));
                assertTrue(r.allocator.totalMemory() <= 32768);
            }
        }
    }

    @Test
    void evictionPreflightCannotDestroyAnyMultiGatTargetOnFailure() throws Exception {
        try (Rig r = new Rig(32768, 4096, "lru", 0)) {
            for (int i = 0; i < 8; i++) put(r, "k" + i, "x".repeat(3000), i, 100);
            Version first = current(r, "k0");
            Version second = current(r, "k1");
            r.allocator.failAt = r.allocator.allocations + 2;
            assertThrows(CacheCapacityException.class, () -> r.engine.snapshotAndTouch(keys("k0", "k1"), 900));
            r.allocator.failAt = Integer.MAX_VALUE;
            assertEquals(first, current(r, "k0"));
            assertEquals(second, current(r, "k1"));
            assertEquals(0, r.engine.snapshotMetadataUsedBytes());
        }
    }

    @Test
    void classRelevantEvictionPreservesUnrelatedSmallClass() {
        try (Rig r = new Rig(8192, 4096, "lru", 0)) {
            put(r, "small", "s", 0, 0);
            put(r, "large", "l".repeat(3000), 0, 0);
            put(r, "next", "n".repeat(3000), 0, 0);
            assertTrue(found(r, "small"), "Freeing an unrelated-class chunk cannot satisfy the large allocation");
            assertFalse(found(r, "large"));
            assertTrue(found(r, "next"));
        }
    }

    @Test
    void constructorSettingsAndBeforeStartLifetime() {
        for (long memory : new long[]{0, -1}) assertThrows(IllegalArgumentException.class,
            () -> new DefaultCacheEngine.Settings(memory, 1, "lru", 1));
        for (int size : new int[]{0, -1, 101}) assertThrows(IllegalArgumentException.class,
            () -> new DefaultCacheEngine.Settings(100, size, "lru", 1));
        for (String policy : List.of("", "LRU", "none", " lru")) assertThrows(IllegalArgumentException.class,
            () -> new DefaultCacheEngine.Settings(100, 1, policy, 1));
        for (long interval : new long[]{0, -1, Long.MAX_VALUE / 1_000_000_000L + 1}) assertThrows(IllegalArgumentException.class,
            () -> new DefaultCacheEngine.Settings(100, 1, "lru", interval));
        assertThrows(NullPointerException.class, () -> new DefaultCacheEngine.Settings(100, 1, null, 1));
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(MEMORY, 4096, 4096);
             SegmentedItemTable table = new SegmentedItemTable(16, 2, MEMORY)) {
            AtomicStatsRegistry stats = new AtomicStatsRegistry(Map.of("pid", "1", "version", "octomem-test", "pointer_size", "64", "threads", "1", "limit_maxbytes", Long.toString(MEMORY)));
            var settings = new DefaultCacheEngine.Settings(MEMORY, 4096, "lru", 86400);
            assertThrows(NullPointerException.class, () -> new DefaultCacheEngine(null, table, new FakeClock(), stats, settings));
            assertThrows(NullPointerException.class, () -> new DefaultCacheEngine(allocator, null, new FakeClock(), stats, settings));
            assertThrows(NullPointerException.class, () -> new DefaultCacheEngine(allocator, table, null, stats, settings));
            assertThrows(NullPointerException.class, () -> new DefaultCacheEngine(allocator, table, new FakeClock(), null, settings));
            assertThrows(NullPointerException.class, () -> new DefaultCacheEngine(allocator, table, new FakeClock(), stats, null));
            DefaultCacheEngine engine = new DefaultCacheEngine(allocator, table, new FakeClock(), stats, settings);
            assertOutsideLifetime(engine);
            engine.stop();
            engine.stop();
            assertThrows(IllegalStateException.class, engine::start);
            assertOutsideLifetime(engine);
            assertFalse(allocator.isClosed());
        }
    }

    @Test
    void exactRetrievalTouchAndMutationStatisticsFromIndependentReferenceMapping() throws Exception {
        try (Rig r = new Rig()) {
            put(r, "a", "1", 0, 0);
            put(r, "b", "2", 0, 0);
            assertEquals(2L, r.stats.snapshot().counters().get(Counter.CMD_SET));
            try (RetrievalSnapshot snapshot = r.engine.snapshot(keys("a", "missing", "b", "a"))) {
                assertEquals(2, items(snapshot).size());
            }
            assertEquals(4L, r.stats.snapshot().counters().get(Counter.CMD_GET));
            assertEquals(3L, r.stats.snapshot().counters().get(Counter.GET_HITS));
            assertEquals(1L, r.stats.snapshot().counters().get(Counter.GET_MISSES));
            try (RetrievalSnapshot touched = r.engine.snapshotAndTouch(keys("b", "absent", "a", "b"), 50)) {
                assertEquals(2, items(touched).size());
            }
            assertEquals(4L, r.stats.snapshot().counters().get(Counter.CMD_GET));
            assertEquals(4L, r.stats.snapshot().counters().get(Counter.CMD_TOUCH));
            assertEquals(3L, r.stats.snapshot().counters().get(Counter.TOUCH_HITS));
            assertEquals(1L, r.stats.snapshot().counters().get(Counter.TOUCH_MISSES));
            try (GetResult result = r.engine.getAndTouch(b("a"), 60)) { assertTrue(result.found()); }
            assertFalse(r.engine.touch(b("absent"), 60));
            assertEquals(6L, r.stats.snapshot().counters().get(Counter.CMD_TOUCH));
            assertEquals(4L, r.stats.snapshot().counters().get(Counter.TOUCH_HITS));
            assertEquals(2L, r.stats.snapshot().counters().get(Counter.TOUCH_MISSES));
            assertCounter(r.engine.increment(b("a"), 2), 3);
            assertCounter(r.engine.decrement(b("a"), 1), 2);
            assertEquals(CounterResult.Status.NOT_FOUND, r.engine.increment(b("missing"), 1).status());
            assertEquals(CounterResult.Status.NOT_FOUND, r.engine.decrement(b("missing"), 1).status());
            long token = current(r, "a").cas;
            assertEquals(StoreResult.EXISTS, r.engine.cas(b("a"), b("4"), 0, 0, token + 1));
            assertEquals(StoreResult.NOT_FOUND, r.engine.cas(b("missing"), b("4"), 0, 0, token));
            assertEquals(StoreResult.STORED, r.engine.cas(b("a"), b("4"), 0, 0, token));
            assertTrue(r.engine.delete(b("b")));
            assertFalse(r.engine.delete(b("b")));
            for (Counter counter : List.of(Counter.INCR_HITS, Counter.INCR_MISSES, Counter.DECR_HITS,
                    Counter.DECR_MISSES, Counter.CAS_HITS, Counter.CAS_MISSES, Counter.CAS_BADVAL,
                    Counter.DELETE_HITS, Counter.DELETE_MISSES)) {
                assertEquals(1L, r.stats.snapshot().counters().get(counter), counter.name());
            }
            r.engine.flushAll(0);
            assertEquals(1L, r.stats.snapshot().counters().get(Counter.CMD_FLUSH));
        }
    }

    @Test
    void snapshotCapturesClockOnceAndIncludesBoundaryHitsTogether() throws Exception {
        try (Rig r = new Rig()) {
            put(r, "a", "A", 0, 1);
            put(r, "b", "B", 0, 1);
            put(r, "c", "C", 0, 1);
            r.clock.advanceOnRead = true;
            int reads = r.clock.reads;
            try (RetrievalSnapshot snapshot = r.engine.snapshot(keys("c", "b", "a"))) {
                assertEquals(NOW, snapshot.capturedClock());
                assertEquals(reads + 1, r.clock.reads, "One clock capture for whole command");
                assertEquals(3, items(snapshot).size());
            }
            r.clock.advanceOnRead = false;
            assertFalse(found(r, "a"));
        }
    }

    @Test
    void malformedKeySequenceReleasesMetadataWithoutChangingRecords() {
        try (Rig r = new Rig()) {
            put(r, "a", "original", 2, 100);
            Version before = current(r, "a");
            for (int mismatch : new int[]{-1, 1}) {
                KeySequence sequence = new KeySequence() {
                    public int size() { return 2; }
                    public void forEachKey(KeyVisitor visitor) {
                        for (int i = 0; i < 2 + mismatch; i++) visitor.accept(b("a"), 0, 1);
                    }
                };
                assertThrows(IllegalArgumentException.class, () -> r.engine.snapshotAndTouch(sequence, 1));
                assertEquals(0, r.engine.snapshotMetadataUsedBytes());
                assertEquals(before, current(r, "a"));
            }
        }
    }

    @Test
    void snapshotCapStaysSixteenMiBEvenForLargerNativeLimit() throws Exception {
        try (Rig r = new Rig(1L << 30, 4096, "noeviction", 0)) {
            KeySequence repeated = new KeySequence() {
                public int size() { return 32767; }
                public void forEachKey(KeyVisitor visitor) {
                    byte[] key = b("missing");
                    for (int i = 0; i < 32767; i++) visitor.accept(key, 0, key.length);
                }
            };
            try (RetrievalSnapshot held = r.engine.snapshot(repeated)) {
                assertEquals(16L * 1024 * 1024, r.engine.snapshotMetadataUsedBytes());
                assertTrue(items(held).isEmpty());
                assertThrows(CacheCapacityException.class, () -> r.engine.snapshot(keys("missing")));
                assertEquals(0, r.allocator.totalMemory(), "Native ceiling must not allocate pages eagerly");
            }
            assertEquals(0, r.engine.snapshotMetadataUsedBytes());
        }
    }

    @Test
    void subsequentFlushDoesNotResurrectAlreadyInvalidatedRecords() {
        try (Rig r = new Rig()) {
            put(r, "old", "old", 0, 0);
            r.engine.flushAll(10);
            r.clock.now += 11;
            put(r, "middle", "middle", 0, 0);
            r.engine.flushAll(20);
            assertFalse(found(r, "old"));
            assertTrue(found(r, "middle"));
            r.clock.now += 21;
            put(r, "new", "new", 0, 0);
            r.engine.flushAll(30);
            assertFalse(found(r, "middle"));
            assertTrue(found(r, "new"));
            r.engine.flushAll(-1);
            assertFalse(found(r, "new"));
        }
    }

    @Test
    void relativeDeadlineNearLongMaximumDoesNotWrapToPast() {
        try (Rig r = new Rig()) {
            r.clock.now = Long.MAX_VALUE - 10;
            put(r, "a", "a", 0, 11);
            Version value = current(r, "a");
            assertEquals(Long.MAX_VALUE, value.expiry);
            r.clock.now = Long.MAX_VALUE;
            assertFalse(found(r, "a"));
        }
    }

    @Test
    void largeConcatenationsAndCounterScansCrossScratchBoundaries() {
        try (Rig r = new Rig()) {
            String middle = "m".repeat(700);
            String prefix = "p".repeat(600);
            String suffix = "s".repeat(800);
            put(r, "a", middle, 9, 100);
            assertEquals(StoreResult.STORED, r.engine.append(b("a"), b(suffix)));
            assertEquals(middle + suffix, current(r, "a").value);
            assertEquals(StoreResult.STORED, r.engine.prepend(b("a"), b(prefix)));
            assertEquals(prefix + middle + suffix, current(r, "a").value);
            put(r, "n", "0".repeat(600) + "123", 0, 0);
            assertCounter(r.engine.increment(b("n"), 1), 124);
            put(r, "n", "99999999999999999999", 0, 0);
            assertEquals(CounterResult.Status.NON_NUMERIC, r.engine.increment(b("n"), 1).status());
        }
    }

    @Test
    void expiredSnapshotEntriesAreReclaimedAndCursorLifetimeIsEnforced() throws Exception {
        try (Rig r = new Rig()) {
            put(r, "expired", "x", 0, 1);
            r.clock.now++;
            RetrievalSnapshot snapshot = r.engine.snapshot(keys("expired", "missing"));
            var cursor = snapshot.values();
            assertFalse(cursor.hasNext());
            assertThrows(java.util.NoSuchElementException.class, cursor::next);
            assertThrows(IllegalStateException.class, snapshot::values);
            assertEquals(0, r.allocator.usedMemory());
            snapshot.close();
            assertThrows(IllegalStateException.class, snapshot::capturedClock);
            assertThrows(IllegalStateException.class, cursor::hasNext);
            GetResult result = r.engine.get(b("missing"));
            result.close();
            assertThrows(IllegalStateException.class, result::found);
        }
    }

    @Test
    void directFfmAllocatorPreflightReclaimsExpiredChunksWithoutEviction() {
        long memory = 8192;
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(memory, 4096, 4096);
             SegmentedItemTable table = new SegmentedItemTable(1, 2, MEMORY)) {
            FakeClock clock = new FakeClock();
            AtomicStatsRegistry stats = new AtomicStatsRegistry(Map.of("pid", "1", "version", "test", "pointer_size", "64", "threads", "1", "limit_maxbytes", "8192"));
            DefaultCacheEngine engine = new DefaultCacheEngine(allocator, table, clock, stats,
                new DefaultCacheEngine.Settings(memory, 4096, "noeviction", 86400));
            engine.start();
            try {
                assertEquals(StoreResult.STORED, engine.set(b("a"), new byte[3000], 0, 1));
                assertEquals(StoreResult.STORED, engine.set(b("b"), new byte[3000], 0, 1));
                clock.now++;
                assertEquals(StoreResult.STORED, engine.set(b("c"), new byte[3000], 0, 0));
                assertEquals(1L, stats.snapshot().counters().get(Counter.RECLAIMED));
                assertEquals(1, engine.reclaimExpired(100));
                assertEquals(1L, stats.snapshot().counters().get(Counter.RECLAIMED));
                assertEquals(2L, stats.snapshot().counters().get(Counter.EXPIRED_UNFETCHED));
                assertEquals(0L, stats.snapshot().counters().get(Counter.EVICTIONS));
            } finally { engine.stop(); }
        }
    }

    @Test
    void constructorRejectsClosedNonemptyAndUnfundedIndex() {
        try (Rig r = new Rig()) {
            var settings = new DefaultCacheEngine.Settings(MEMORY, 4096, "lru", 86400);
            put(r, "present", "value", 0, 0);
            assertThrows(IllegalArgumentException.class,
                () -> new DefaultCacheEngine(r.allocator, r.table, r.clock, r.stats, settings));
            try (SegmentedItemTable closed = new SegmentedItemTable(1, 2, 1024)) {
                closed.close();
                assertThrows(IllegalArgumentException.class,
                    () -> new DefaultCacheEngine(r.allocator, closed, r.clock, r.stats, settings));
            }
            // Documented initial metadata charge: 128 + 8*S + S*(256 + 8*B).
            try (SegmentedItemTable exactInitial = new SegmentedItemTable(1, 2, 408)) {
                assertThrows(IllegalArgumentException.class,
                    () -> new DefaultCacheEngine(r.allocator, exactInitial, r.clock, r.stats, settings));
            }
        }
    }

    @Test
    void indexAdmissionFailureReturnsOomWithoutNativeLeak() {
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(MEMORY, 4096, 4096);
             SegmentedItemTable table = new SegmentedItemTable(1, 2,
                     408 + (192 + 24L * allocator.classCount()) + 512 + 128L * allocator.classCount()
                             + (2048 - 408 - (64 + 8L * allocator.classCount())))) {
            FakeClock clock = new FakeClock();
            AtomicStatsRegistry stats = new AtomicStatsRegistry(Map.of("pid", "1", "version", "test", "pointer_size", "64", "threads", "1", "limit_maxbytes", Long.toString(MEMORY)));
            DefaultCacheEngine engine = new DefaultCacheEngine(allocator, table, clock, stats,
                new DefaultCacheEngine.Settings(MEMORY, 4096, "noeviction", 86400));
            engine.start();
            try {
                int successful = 0;
                for (int i = 0; i < 100; i++) {
                    long before = allocator.usedMemory();
                    StoreResult result = engine.set(b("k" + i), b("value"), 0, 0);
                    if (result == StoreResult.OUT_OF_MEMORY) {
                        assertEquals(before, allocator.usedMemory());
                        break;
                    }
                    assertEquals(StoreResult.STORED, result);
                    successful++;
                }
                assertTrue(successful > 0 && successful < 100, "Bounded index must deny admission");
                for (int i = 0; i < successful; i++) {
                    try (GetResult result = engine.get(b("k" + i))) { assertEquals("value", text(result.item())); }
                }
            } finally { engine.stop(); }
            assertEquals(0, allocator.usedMemory());
        }
    }

    @Test
    void snapshotManyUniqueKeysWithSameSegmentRetainsInputOrder() throws Exception {
        try (Rig r = new Rig()) {
            List<String> same = new ArrayList<>();
            for (int i = 0; same.size() < 8; i++) {
                String key = "collision" + i;
                byte[] bytes = b(key);
                if (r.table.segmentId(bytes, 0, bytes.length) == 0) same.add(key);
            }
            for (String key : same) put(r, key, key, 0, 0);
            java.util.Collections.reverse(same);
            try (RetrievalSnapshot snapshot = r.engine.snapshot(keys(same.toArray(String[]::new)))) {
                assertEquals(same, items(snapshot).stream().map(EngineAcceptanceTest::text).toList());
            }
        }
    }

    @Test
    void reclamationScansLiveRecordsAndExpiredSnapshotTouchRemovesOnlyExpired() throws Exception {
        try (Rig r = new Rig()) {
            for (int i = 0; i < 40; i++) put(r, "live" + i, "v", 0, 0);
            assertEquals(0, r.engine.reclaimExpired(1000));
            put(r, "expired", "expired", 0, 1);
            r.clock.now++;
            try (RetrievalSnapshot snapshot = r.engine.snapshotAndTouch(keys("expired", "missing", "live0"), 100)) {
                assertEquals(1, items(snapshot).size());
            }
            assertFalse(found(r, "expired"));
            assertTrue(found(r, "live0"));
        }
    }

    @Test
    void evictingForCounterAndTouchProtectsCurrentTarget() throws Exception {
        for (int operation = 0; operation < 3; operation++) {
            try (Rig r = new Rig(32768, 4096, "lru", 0)) {
                for (int i = 0; i < 8; i++) put(r, "k" + i, "0".repeat(2999) + "1", 3, 100);
                if (operation == 0) {
                    Version before = current(r, "k0");
                    long total = r.stats.snapshot().counters().get(Counter.TOTAL_ITEMS);
                    assertCounter(r.engine.decrement(b("k0"), 1), 0);
                    Version after = current(r, "k0");
                    assertEquals("0" + " ".repeat(2999), after.value);
                    assertEquals(before.flags, after.flags);
                    assertEquals(before.expiry, after.expiry);
                    assertEquals(total, r.stats.snapshot().counters().get(Counter.TOTAL_ITEMS));
                }
                if (operation == 1) assertTrue(r.engine.touch(b("k0"), 200));
                if (operation == 2) try (GetResult result = r.engine.getAndTouch(b("k0"), 200)) { assertTrue(result.found()); }
                assertTrue(found(r, "k0"));
            }
        }
    }

    @Test
    void directFfmCrossClassCounterReclaimsPhysicalPage() {
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(32768, 4096, 4096);
             SegmentedItemTable table = new SegmentedItemTable(16, 2, MEMORY)) {
            AtomicStatsRegistry stats = new AtomicStatsRegistry(Map.of("pid", "1", "version", "test", "pointer_size", "64", "threads", "1", "limit_maxbytes", "32768"));
            DefaultCacheEngine engine = new DefaultCacheEngine(allocator, table, new FakeClock(), stats,
                new DefaultCacheEngine.Settings(32768, 4096, "lru", 86400));
            engine.start();
            try {
                for (int i = 0; i < 8; i++) assertEquals(StoreResult.STORED,
                    engine.set(b("k" + i), b("0".repeat(2999) + "1"), 3, 100));
                assertCounter(engine.decrement(b("k0"), 1), 0);
                try (GetResult result = engine.get(b("k0"))) {
                    assertTrue(result.found());
                    assertEquals("0" + " ".repeat(2999), text(result.item()));
                }
            } finally { engine.stop(); }
        }
    }

    static void assertOutsideLifetime(DefaultCacheEngine e) {
        assertThrows(IllegalStateException.class, () -> e.get(b("a")));
        assertThrows(IllegalStateException.class, () -> e.getWithCas(b("a")));
        assertThrows(IllegalStateException.class, () -> e.getAndTouch(b("a"), 0));
        assertThrows(IllegalStateException.class, () -> e.snapshot(keys("a")));
        assertThrows(IllegalStateException.class, () -> e.snapshotAndTouch(keys("a"), 0));
        assertThrows(IllegalStateException.class, () -> e.set(b("a"), b("b"), 0, 0));
        assertThrows(IllegalStateException.class, () -> e.add(b("a"), b("b"), 0, 0));
        assertThrows(IllegalStateException.class, () -> e.replace(b("a"), b("b"), 0, 0));
        assertThrows(IllegalStateException.class, () -> e.append(b("a"), b("b")));
        assertThrows(IllegalStateException.class, () -> e.prepend(b("a"), b("b")));
        assertThrows(IllegalStateException.class, () -> e.cas(b("a"), b("b"), 0, 0, 0));
        assertThrows(IllegalStateException.class, () -> e.delete(b("a")));
        assertThrows(IllegalStateException.class, () -> e.increment(b("a"), 1));
        assertThrows(IllegalStateException.class, () -> e.decrement(b("a"), 1));
        assertThrows(IllegalStateException.class, () -> e.touch(b("a"), 0));
        assertThrows(IllegalStateException.class, () -> e.flushAll(0));
        assertThrows(IllegalStateException.class, () -> e.reclaimExpired(1));
    }

    static void assertCounter(CounterResult result, long expected) {
        assertEquals(CounterResult.Status.VALUE, result.status());
        assertEquals(expected, result.value());
    }

    static void put(Rig r, String key, String value, int flags, long ttl) {
        assertEquals(StoreResult.STORED, r.engine.set(b(key), b(value), flags, ttl));
    }

    static boolean found(Rig r, String key) {
        try (GetResult result = r.engine.get(b(key))) { return result.found(); }
    }

    static Version current(Rig r, String key) {
        try (GetResult result = r.engine.getWithCas(b(key))) {
            assertTrue(result.found(), key);
            return version(result.item());
        }
    }

    static Version version(Item item) {
        return new Version(text(item), item.flags(), item.expiryTime(), item.casId());
    }

    static String text(Item item) {
        ByteBuffer bytes = ByteBuffer.allocate(item.valueLength());
        int offset = 0;
        while (bytes.hasRemaining()) {
            int count = item.readValue(offset, bytes);
            assertTrue(count > 0);
            offset += count;
        }
        return new String(bytes.array(), StandardCharsets.US_ASCII);
    }

    static List<Item> items(RetrievalSnapshot snapshot) {
        List<Item> result = new ArrayList<>();
        snapshot.values().forEachRemaining(result::add);
        return result;
    }

    static byte[] b(String text) { return text.getBytes(StandardCharsets.US_ASCII); }

    static KeySequence keys(String... names) {
        byte[][] bytes = Arrays.stream(names).map(EngineAcceptanceTest::b).toArray(byte[][]::new);
        return new KeySequence() {
            public int size() { return bytes.length; }
            public void forEachKey(KeyVisitor visitor) {
                for (byte[] key : bytes) visitor.accept(key, 0, key.length);
            }
        };
    }

    record Version(String value, int flags, long expiry, long cas) { }
    record Model(String value, int flags, long expiry) { }

    static final class FakeClock implements Clock {
        volatile long now = NOW;
        int reads;
        boolean advanceOnRead;
        public synchronized long currentTimeSeconds() {
            reads++;
            return advanceOnRead ? now++ : now;
        }
        public long nanoTime() { return 0; }
    }

    static final class FailingAllocator implements MemoryAllocator {
        final FfmSlabAllocator delegate;
        int allocations;
        int failAt = Integer.MAX_VALUE;
        FailingAllocator(FfmSlabAllocator delegate) { this.delegate = delegate; }
        public synchronized SlabHandle allocate(int size) {
            if (++allocations >= failAt) return null;
            return delegate.allocate(size);
        }
        public void free(SlabHandle handle) { delegate.free(handle); }
        public long totalMemory() { return delegate.totalMemory(); }
        public long usedMemory() { return delegate.usedMemory(); }
        public int classCount() { return delegate.classCount(); }
        public SlabClassStats classStats(int id) { return delegate.classStats(id); }
    }

    static final class Rig implements AutoCloseable {
        final FakeClock clock = new FakeClock();
        final FfmSlabAllocator nativeAllocator;
        final FailingAllocator allocator;
        final SegmentedItemTable table = new SegmentedItemTable(16, 2, 1 << 20);
        final AtomicStatsRegistry stats;
        final DefaultCacheEngine engine;
        Rig() { this(MEMORY, 4096, "noeviction", 0); }
        Rig(long memory, int itemMax, String policy, long seed) {
            nativeAllocator = new FfmSlabAllocator(memory, 4096, itemMax);
            allocator = new FailingAllocator(nativeAllocator);
            stats = new AtomicStatsRegistry(Map.of("pid", "1", "version", "octomem-test", "pointer_size", "64", "threads", "1", "limit_maxbytes", Long.toString(memory)));
            engine = new DefaultCacheEngine(allocator, table, clock, stats,
                new DefaultCacheEngine.Settings(memory, itemMax, policy, 86400), seed);
            engine.start();
        }
        public void close() {
            engine.stop();
            assertEquals(0, allocator.usedMemory(), "All test-owned leases and temporary blocks must be released");
            nativeAllocator.close();
        }
    }
}

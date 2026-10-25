package ru.octomem.opt01acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.octomem.engine.DefaultCacheEngine;
import ru.octomem.engine.GetResult;
import ru.octomem.engine.StoreResult;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.stats.ClassStatsRegistry;
import ru.octomem.stats.Counter;
import ru.octomem.storage.FfmSlabAllocator;
import ru.octomem.storage.SegmentedItemTable;

/**
 * Independent OPT-01 behavioural regression through the public engine API: the
 * class actually chosen for a stored item still matches the allocator's own
 * reported geometry for the same request size, and oversized requests still
 * fail with the documented {@link StoreResult#TOO_LARGE}.
 *
 * <p>The class is observed two independent ways, neither of which reads the
 * engine's private lookup: the allocator's occupied-bytes delta equals the
 * reported chunk size of the expected class, and the engine's admitted per-class
 * {@code CMD_SET} counter advances for exactly that class.
 */
@Timeout(60)
class Opt01BehaviouralRegressionAcceptanceTest {

    @Test
    void storedItemClassMatchesReportedGeometryForEveryRepresentativeSize() {
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(1L << 20, 4096, 4096);
             SegmentedItemTable table = new SegmentedItemTable(1, 2, 1L << 20)) {
            int[] geometry = Opt01Fixtures.reportedGeometry(allocator);
            AtomicStatsRegistry stats = Opt01Fixtures.registry(1L << 20);
            DefaultCacheEngine engine = Opt01Fixtures.engine(allocator, table, stats, 1L << 20, 4096, "noeviction");
            engine.start();
            try {
                byte[] key = Opt01Fixtures.bytes("k");
                int[] valueLengths = {0, 1, 63, 64, 100, 300, 1000, 3376, 4000, 4063};
                for (int valueLength : valueLengths) {
                    long record = Opt01Fixtures.align(
                            (long) Opt01Fixtures.RECORD_HEADER_BYTES + key.length + valueLength);
                    int expected = Opt01Fixtures.lowerBound(geometry, record);
                    assertTrue(expected >= 0, "value length " + valueLength + " must fit the ceiling");

                    long usedBefore = allocator.usedMemory();
                    long admittedBefore = cmdSet(stats, expected);
                    assertEquals(StoreResult.STORED, engine.set(key, new byte[valueLength], 7, 0));

                    assertEquals(geometry[expected], allocator.usedMemory() - usedBefore,
                            "reserved chunk of the class expected for record " + record);
                    assertEquals(admittedBefore + 1, cmdSet(stats, expected),
                            "engine must admit the store into class " + expected);

                    try (GetResult result = engine.get(key)) {
                        assertTrue(result.found());
                        assertEquals(valueLength, result.item().valueLength());
                        assertEquals(7, result.item().flags());
                    }
                    assertTrue(engine.delete(key));
                    assertEquals(usedBefore, allocator.usedMemory(), "block must be released after delete");
                }
            } finally {
                engine.stop();
            }
        }
    }

    @Test
    void oversizedRequestsStillFailWithTooLargeAndSelectNoClass() {
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(1L << 20, 4096, 4096);
             SegmentedItemTable table = new SegmentedItemTable(1, 2, 1L << 20)) {
            int[] geometry = Opt01Fixtures.reportedGeometry(allocator);
            AtomicStatsRegistry stats = Opt01Fixtures.registry(1L << 20);
            DefaultCacheEngine engine = Opt01Fixtures.engine(allocator, table, stats, 1L << 20, 4096, "noeviction");
            engine.start();
            try {
                byte[] key = Opt01Fixtures.bytes("k");
                long usedBefore = allocator.usedMemory();
                long admittedBefore = totalCmdSet(stats);

                // Key length 1: record 4096 (value 4063) is the largest admitted size.
                assertEquals(StoreResult.STORED, engine.set(key, new byte[4063], 0, 0));
                long admittedAfterBoundary = totalCmdSet(stats);
                assertEquals(admittedBefore + 1, admittedAfterBoundary,
                        "the boundary store must select exactly one class");
                assertEquals(geometry[Opt01Fixtures.lowerBound(geometry, 4096)],
                        allocator.usedMemory() - usedBefore);
                assertTrue(engine.delete(key));

                assertEquals(StoreResult.TOO_LARGE, engine.set(key, new byte[4064], 0, 0));
                assertEquals(StoreResult.TOO_LARGE, engine.set(key, new byte[100_000], 0, 0));
                assertEquals(StoreResult.TOO_LARGE,
                        engine.set(Opt01Fixtures.bytes("longer"), new byte[4063], 0, 0));
                assertEquals(admittedAfterBoundary, totalCmdSet(stats),
                        "oversized requests must not select any class");
                assertEquals(usedBefore, allocator.usedMemory());
            } finally {
                engine.stop();
            }
        }
    }

    private static long cmdSet(AtomicStatsRegistry stats, int classId) {
        try (ClassStatsRegistry.ClassSnapshot snapshot = stats.captureClasses()) {
            return snapshot.counter(classId, Counter.CMD_SET);
        }
    }

    private static long totalCmdSet(AtomicStatsRegistry stats) {
        long total = 0;
        try (ClassStatsRegistry.ClassSnapshot snapshot = stats.captureClasses()) {
            for (int i = 0; i < snapshot.classCount(); i++) total += snapshot.counter(i, Counter.CMD_SET);
        }
        return total;
    }
}

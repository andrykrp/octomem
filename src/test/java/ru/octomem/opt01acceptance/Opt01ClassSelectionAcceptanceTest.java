package ru.octomem.opt01acceptance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.octomem.engine.DefaultCacheEngine;
import ru.octomem.storage.FfmSlabAllocator;
import ru.octomem.storage.SegmentedItemTable;

/**
 * Independent OPT-01 acceptance: size-class selection is exactly an independent
 * lower bound over the allocator's reported geometry, with the documented
 * alignment and the over-ceiling {@code -1}, swept densely for two geometries.
 */
@Timeout(180)
class Opt01ClassSelectionAcceptanceTest {

    /** Unaligned ceiling: the terminal class must round up to 1008. */
    @Test
    void selectionMatchesIndependentLowerBoundForUnalignedMaximum1001() throws Exception {
        sweepAndBoundaries(1001, 1L << 20, 1001);
    }

    /** Documented default ceiling: the 1 MiB {@code item.maxSize}. */
    @Test
    void selectionMatchesIndependentLowerBoundForDefaultMebibyteCeiling() throws Exception {
        sweepAndBoundaries(Opt01Fixtures.DEFAULT_ITEM_MAX, 64L << 20, Opt01Fixtures.DEFAULT_ITEM_MAX);
    }

    /**
     * Sweeps every request size from 1 through the terminal class plus two, and
     * probes every class boundary under the contract's eight-byte alignment.
     */
    private static void sweepAndBoundaries(int maxBlockSize, long memoryLimit, int itemMaxSize) {
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(memoryLimit, 4096, maxBlockSize);
             SegmentedItemTable table = new SegmentedItemTable(1, 2, 1L << 22)) {
            int[] contract = Opt01Fixtures.contractGeometry(maxBlockSize);
            int[] reported = Opt01Fixtures.reportedGeometry(allocator);
            assertArrayEquals(contract, reported,
                    "the allocator's reported geometry must follow the documented growth contract");
            int terminal = reported[reported.length - 1];
            assertEquals(Opt01Fixtures.align(maxBlockSize), terminal,
                    "the terminal class must be the eight-byte-rounded ceiling");

            DefaultCacheEngine engine = Opt01Fixtures.engine(allocator, table, memoryLimit, itemMaxSize, "noeviction");
            try {
                // (2) Alignment at every class boundary: the eight sizes that round
                // up to a chunk all select that chunk's class; chunk+1 rounds to the
                // next multiple of eight and therefore selects the next class.
                for (int i = 0; i < reported.length; i++) {
                    int chunk = reported[i];
                    int upper = i + 1 < reported.length ? i + 1 : -1;
                    for (int delta = 0; delta < 8; delta++) {
                        int below = chunk - delta;
                        if (below >= 1) {
                            assertEquals(i, Opt01Fixtures.classFor(engine, below),
                                    "aligned-below size " + below + " of class " + i);
                        }
                        assertEquals(upper, Opt01Fixtures.classFor(engine, chunk + 1L + delta),
                                "aligned-above size " + (chunk + 1 + delta) + " of class " + i);
                    }
                    assertEquals(i, Opt01Fixtures.classFor(engine, chunk), "exact chunk " + chunk);
                    assertEquals(i, Opt01Fixtures.classFor(engine, chunk - 1L), "chunk-1 " + chunk);
                }

                // (1) Dense sweep over the allocator's reported chunk sizes.
                for (long size = 1; size <= terminal + 2L; size++) {
                    assertEquals(Opt01Fixtures.lowerBound(reported, size),
                            Opt01Fixtures.classFor(engine, size), "dense size " + size);
                }

                // (1) Above the ceiling: -1, including values far beyond int range.
                assertEquals(-1, Opt01Fixtures.classFor(engine, terminal + 1L), "first size above terminal");
                assertEquals(-1, Opt01Fixtures.classFor(engine, Integer.MAX_VALUE), "Integer.MAX_VALUE");
                assertEquals(-1, Opt01Fixtures.classFor(engine, Integer.MAX_VALUE + 1L), "Integer.MAX_VALUE+1");
                assertEquals(-1, Opt01Fixtures.classFor(engine, 3_000_000_000L), "3e9");
                assertEquals(-1, Opt01Fixtures.classFor(engine, 4L * Integer.MAX_VALUE), "4*Integer.MAX_VALUE");
                assertEquals(-1, Opt01Fixtures.classFor(engine, 1L << 40), "2^40");
            } finally {
                engine.stop();
            }
        }
    }
}

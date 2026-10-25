package ru.octomem.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.octomem.cli.CacheServer;
import ru.octomem.config.ConfigLoader;

/**
 * Independent regression pin for the release correction that made per-page
 * free-slot heap metadata truthfully accounted: {@link FfmSlabAllocator}
 * exposes the exact per-page size and the bounded worst case, and
 * {@link CacheServer} includes that bound in its fixed heap metadata baseline.
 *
 * <p>Expectations are derived from the approved correction decision and the
 * measured evidence in {@code tasks/reports/release-corrections/evidence.md}
 * and {@code tests/reports/release/instrumentation/raw/P1Layout.*}, never from
 * production internals or observed output. The composition page geometry
 * ({@code pageSize = min(1 MiB, memory.limit)}, smallest class 64 bytes) follows
 * {@code docs/04-protocol-compatibility-specification.md} Q07 and
 * {@code tasks/reports/T013/coordinator-decisions.md} Q07.
 *
 * <p><b>Bound semantics.</b> The documented {@code memory.limit/16} bound
 * applies to the <em>free-slot data term</em>
 * ({@code 4 * slotsPerPage * maximumPageCount}). The accepted accounted total
 * adds a bounded per-page allowance of 280 bytes = a conservative 24-byte
 * {@code int[]} header plus a 256-byte {@code Page}/memory-segment node
 * allowance (provenance: {@code tasks/reports/release-corrections/evidence.md}
 * section "F-2" and "Values not reconciled", and the release review residual).
 * No later reader may read {@code memory.limit/16} as covering the total.
 */
@Timeout(30)
class PageMetadataAccountingTest {
    /** Release default native budget in bytes. */
    private static final long DEFAULT_LIMIT = 64L * 1024 * 1024;
    /** Page size cap used by the composition (docs/04 Q07). */
    private static final long PAGE_SIZE_CAP = 1024 * 1024;
    /** Smallest allocator size-class chunk, class 0 (coordinator Q07). */
    private static final long SMALLEST_CLASS_BYTES = 64;
    /** Conservative HotSpot {@code int[]} header term, measured 16/24. */
    private static final long CONSERVATIVE_HEADER_BYTES = 24;
    /** Backed {@code Page} node plus {@code MemorySegment} wrapper allowance. */
    private static final long NODE_ALLOWANCE_BYTES = 256;
    /** Documented accepted per-page allowance: 24 + 256 = 280 bytes. */
    private static final long PER_PAGE_ALLOWANCE_BYTES = CONSERVATIVE_HEADER_BYTES + NODE_ALLOWANCE_BYTES;
    /** Frozen measured retained size of one 176-class 1 MiB page, compressed. */
    private static final long MEASURED_PAGE_COMPRESSED = 23960;
    /** Frozen measured retained size of one 176-class 1 MiB page, uncompressed. */
    private static final long MEASURED_PAGE_UNCOMPRESSED = 23992;

    @Test
    void perPageFormulaCoversTheMeasuredPageGraph() {
        FfmSlabAllocator allocator = allocator(DEFAULT_LIMIT);
        try {
            assertEquals(44, allocator.classCount(),
                    "documented class geometry for a 1 MiB item ceiling");
            long pageSize = Math.min(PAGE_SIZE_CAP, DEFAULT_LIMIT);
            for (int classId = 0; classId < allocator.classCount(); classId++) {
                long chunk = allocator.classStats(classId).chunkSize();
                long expected = CONSERVATIVE_HEADER_BYTES
                        + 4L * (Math.max(pageSize, chunk) / chunk)
                        + NODE_ALLOWANCE_BYTES;
                assertEquals(expected, allocator.pageMetadataHeapBytes(classId),
                        "documented per-page formula for class " + classId);
            }
            int measuredClass = classFor(allocator, 176);
            long accounted = allocator.pageMetadataHeapBytes(measuredClass);
            assertEquals(CONSERVATIVE_HEADER_BYTES + 4L * (PAGE_SIZE_CAP / 176)
                            + NODE_ALLOWANCE_BYTES, accounted,
                    "the 176-byte class is the independently measured representative");
            assertTrue(accounted >= MEASURED_PAGE_COMPRESSED,
                    "accounting must cover the compressed measured page graph");
            assertTrue(accounted >= MEASURED_PAGE_UNCOMPRESSED,
                    "accounting must cover the uncompressed measured page graph");
            assertThrows(IllegalArgumentException.class,
                    () -> allocator.pageMetadataHeapBytes(-1));
            assertThrows(IllegalArgumentException.class,
                    () -> allocator.pageMetadataHeapBytes(allocator.classCount()));
        } finally {
            allocator.close();
        }
    }

    @Test
    void maximumPageCountAndBoundsMatchTheDocumentedRelations() {
        long[] limits = {DEFAULT_LIMIT, 4L * 1024 * 1024, 1024L, 1L};
        for (long limit : limits) {
            FfmSlabAllocator allocator = allocator(limit);
            try {
                long pageSize = Math.min(PAGE_SIZE_CAP, limit);
                long minimumPageBytes = Math.max(pageSize, SMALLEST_CLASS_BYTES);
                long expectedPages = limit / minimumPageBytes;
                long slotsPerPage = minimumPageBytes / SMALLEST_CLASS_BYTES;
                assertEquals(expectedPages, allocator.maximumPageCount(),
                        "documented page count at memory.limit=" + limit);
                assertEquals(4L * slotsPerPage + PER_PAGE_ALLOWANCE_BYTES,
                        allocator.pageMetadataHeapBytes(0),
                        "class 0 must carry the largest free-slot stack");
                long dataTerm = 4L * slotsPerPage * expectedPages;
                assertTrue(dataTerm <= limit / 16,
                        "free-slot data term must respect the documented memory.limit/16 bound");
                assertEquals(dataTerm + expectedPages * PER_PAGE_ALLOWANCE_BYTES,
                        allocator.maximumPageMetadataHeapBytes(),
                        "total must be the documented data term plus the accepted allowance");
            } finally {
                allocator.close();
            }
        }
        FfmSlabAllocator pinned = allocator(DEFAULT_LIMIT);
        try {
            assertEquals(64, pinned.maximumPageCount());
            long dataTerm = 4L * (PAGE_SIZE_CAP / SMALLEST_CLASS_BYTES) * pinned.maximumPageCount();
            assertEquals(DEFAULT_LIMIT / 16, dataTerm, "data term is exactly memory.limit/16 at 64 MiB");
            assertEquals(4194304L, dataTerm, "frozen data term at the 64 MiB default");
            assertEquals(4212224L, pinned.maximumPageMetadataHeapBytes(),
                    "frozen accepted total at the 64 MiB default");
            assertTrue(pinned.maximumPageMetadataHeapBytes() < DEFAULT_LIMIT / 8,
                    "the accepted total stays a small bounded fraction of the native limit");
        } finally {
            pinned.close();
        }
    }

    @Test
    void composedBaselineIncludesTheBoundWithoutDoubleCounting() throws Exception {
        CacheServer server = null;
        try {
            server = new CacheServer(ConfigLoader.load(null,
                    config("67108864", "1048576", "2", "65536")),
                    new InetSocketAddress("127.0.0.1", 0));
            FfmSlabAllocator allocator = (FfmSlabAllocator) field(server, "allocator");
            SegmentedItemTable table = (SegmentedItemTable) field(field(server, "engine"), "table");
            long bound = allocator.maximumPageMetadataHeapBytes();
            assertEquals(4212224L, bound, "composed allocator reports the frozen bound");
            long usedAtRest = table.metadataUsedBytes();
            assertEquals(128 + 8L * 16 + 16 * (256L + 8 * 2)
                            + 16 * (192L + 24L * 44) + 512L + 128L * 44
                            + bound + (2L + 3) * (512L + 128L * 44)
                            + 200L, usedAtRest,
                    "fixed baseline plus align8(24+4*44) geometry term exactly once");
            assertTrue(usedAtRest >= bound,
                    "composed metadataUsed at rest must include the accounted page-metadata bound");
            long netIndexBudget = metadataLimit(table) - usedAtRest;
            assertEquals(Math.min(DEFAULT_LIMIT, 256L * 1024 * 1024), netIndexBudget,
                    "index budget must remain min(memory.limit, 256 MiB), not double counting the bound");
            server.start();
            assertTrue(server.localAddress().getPort() > 0);
            assertEquals(netIndexBudget, metadataLimit(table) - table.metadataUsedBytes(),
                    "starting maintenance must not consume the index budget");
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }

    @Test
    void tinyValidConfigurationStillStarts() throws Exception {
        CacheServer server = null;
        try {
            server = new CacheServer(ConfigLoader.load(null,
                    config("1", "1", "1", "2")),
                    new InetSocketAddress("127.0.0.1", 0));
            FfmSlabAllocator allocator = (FfmSlabAllocator) field(server, "allocator");
            SegmentedItemTable table = (SegmentedItemTable) field(field(server, "engine"), "table");
            assertEquals(0, allocator.maximumPageCount(), "no native page fits memory.limit=1");
            assertEquals(0, allocator.maximumPageMetadataHeapBytes(),
                    "zero pages means zero per-page metadata");
            assertEquals(128 + 8L * 16 + 16 * (256L + 8 * 2)
                            + 16 * (192L + 24L) + 512L + 128L
                            + (1L + 3) * (512L + 128L) + 32L,
                    table.metadataUsedBytes(),
                    "tiny fixed baseline plus align8(24+4*1) geometry term exactly once");
            assertEquals(1L, metadataLimit(table) - table.metadataUsedBytes(),
                    "tiny index budget is min(1, 256 MiB)");
            server.start();
            assertTrue(server.localAddress().getPort() > 0, "tiny valid configuration must start");
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }

    private static FfmSlabAllocator allocator(long memoryLimit) {
        int pageSize = (int) Math.min(PAGE_SIZE_CAP, memoryLimit);
        int maximumBlock = (int) Math.max(SMALLEST_CLASS_BYTES, Math.min(PAGE_SIZE_CAP, memoryLimit));
        return new FfmSlabAllocator(memoryLimit, pageSize, maximumBlock);
    }

    private static int classFor(FfmSlabAllocator allocator, int size) {
        for (int classId = 0; classId < allocator.classCount(); classId++) {
            if (allocator.classStats(classId).chunkSize() >= size) {
                return classId;
            }
        }
        throw new IllegalArgumentException("No size class for " + size);
    }

    private static long metadataLimit(SegmentedItemTable table) throws Exception {
        return ((Number) field(table, "metadataLimit")).longValue();
    }

    private static Object field(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // continue up the hierarchy
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Map<String, String> config(String memoryLimit, String itemMaxSize,
            String workers, String maxChunkBytes) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("memory.limit", memoryLimit);
        values.put("item.maxSize", itemMaxSize);
        values.put("transport.workers", workers);
        values.put("connections.max", "8");
        values.put("expiry.reclaimIntervalSeconds", "3600");
        values.put("protocol.maxChunkBytes", maxChunkBytes);
        return values;
    }
}

package ru.octomem.instrumentation;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.stats.ClassStatsRegistry;
import ru.octomem.storage.FfmSlabAllocator;
import ru.octomem.storage.MemoryAllocator;
import ru.octomem.storage.SegmentedItemTable;
import ru.octomem.storage.StoredItem;

/**
 * P1: measured heap layout of representative engine/storage/stats objects, and
 * reconciliation of the modelled logical charges against those measurements.
 *
 * <p>Every size is measured with {@code java.lang.instrument} via
 * {@link HeapAgent}. The "charge" rows are read from the frozen source
 * constants and printed for comparison only; they are not observations of the
 * running composition. The allocator back-reference is excluded from per-record
 * measurements because its page metadata is shared by every record in a class.
 */
public final class P1Layout {
    private P1Layout() { }

    public static void main(String[] args) throws Exception {
        ProbeSupport.printEnvironment();
        ProbeSupport.section("baseline");
        ProbeSupport.measure("baseline.heapUsed", ProbeSupport.settledHeapUsed());
        ProbeSupport.measure("baseline.rss", ProbeSupport.residentSetBytes());

        ProbeSupport.section("allocator.layout");
        int memoryLimit = 64 * 1024 * 1024;
        int pageSize = 1024 * 1024;
        int maxBlock = 1024 * 1024;
        FfmSlabAllocator allocator = new FfmSlabAllocator(memoryLimit, pageSize, maxBlock);
        int classes = allocator.classCount();
        int derivedClasses = ProbeSupport.derivedClassCount(maxBlock);
        ProbeSupport.measure("allocator.classCount", classes);
        ProbeSupport.measure("allocator.derivedClassCount", derivedClasses);
        ProbeSupport.note("allocator.derivedMatchesMeasured", Boolean.toString(classes == derivedClasses));
        report("allocator.retainedHeap", HeapAgent.retained(allocator));
        ProbeSupport.measure("allocator.totalMemory.logical", allocator.totalMemory());
        ProbeSupport.measure("allocator.usedMemory.logical", allocator.usedMemory());
        int[] sizes = (int[]) field(allocator, "sizes");
        StringBuilder classSizes = new StringBuilder();
        for (int i = 0; i < sizes.length; i++) {
            if (i > 0) {
                classSizes.append(',');
            }
            classSizes.append(sizes[i]);
        }
        ProbeSupport.note("allocator.classSizes", classSizes.toString());
        Class<?> pageType = Class.forName("ru.octomem.storage.FfmSlabAllocator$Page");

        ProbeSupport.section("storedItem.single");
        byte[] key = "layout-key".getBytes("US-ASCII");
        byte[] value = new byte[100];
        StoredItem item = StoredItem.create(allocator, key, value, 0, 0, 7L, maxBlock);
        if (item == null) {
            throw new IllegalStateException("unexpected allocator denial");
        }
        int requested = item.requestedBytes();
        ProbeSupport.measure("storedItem.requestedBytes", requested);
        ProbeSupport.measure("storedItem.allocatedBytes", item.allocatedBytes());
        ProbeSupport.measure("storedItem.shallowHeap", HeapAgent.shallow(item));
        report("storedItem.exclusiveHeap",
                HeapAgent.retained(item, MemoryAllocator.class, pageType));
        ProbeSupport.note("storedItem.exclusiveScope",
                "StoredItem graph excluding the shared allocator and its page metadata");
        report("storedItem.retainedIncludingAllocator", HeapAgent.retained(item));
        ProbeSupport.note("storedItem.requestedFormula",
                "align8(32+" + key.length + "+" + value.length + ")=" + requested);
        ProbeSupport.measure("allocator.usedMemory.afterOne", allocator.usedMemory());
        ProbeSupport.measure("allocator.totalMemory.afterOne", allocator.totalMemory());

        ProbeSupport.section("table.layout");
        SegmentedItemTable table = new SegmentedItemTable(16, 2, 64L * 1024 * 1024);
        ProbeSupport.measure("table.metadataUsed.logical", table.metadataUsedBytes());
        report("table.retainedHeap.empty", HeapAgent.retained(table));
        table.put(key, item);
        ProbeSupport.measure("table.metadataUsed.afterOne", table.metadataUsedBytes());
        report("table.retainedHeap.afterOne", HeapAgent.retained(table));
        Object entry = firstEntry(table);
        ProbeSupport.measure("table.entry.shallowHeap", HeapAgent.shallow(entry));
        report("table.entry.exclusiveHeap",
                HeapAgent.retained(entry, MemoryAllocator.class, pageType));
        ProbeSupport.measure("table.chargePerEntry.logical", 64L);
        Object firstPage = null;
        for (Object page : (Object[]) field(allocator, "pages")) {
            if (page != null) {
                firstPage = page;
                break;
            }
        }
        report("allocator.page.exclusiveHeap", HeapAgent.retained(firstPage));
        ProbeSupport.note("allocator.page.scope",
                "one native Page including its per-page free-slot stack, excluding the allocator");

        ProbeSupport.section("stats.epoch.layout");
        AtomicStatsRegistry registry = new AtomicStatsRegistry(PlausibleValues.constants());
        registry.initializeClasses(classes, id -> new ClassStatsRegistry.ClassState(0, 0, 0),
                new AtomicLong(), 16L * 1024 * 1024);
        AtomicReference<?> epochRef = (AtomicReference<?>) field(registry, "epoch");
        Object epoch = epochRef.get();
        report("epoch.retainedHeap", HeapAgent.retained(epoch));
        report("epoch.counters.retainedHeap", HeapAgent.retained(field(epoch, "counters")));
        report("epoch.classes.retainedHeap", HeapAgent.retained(field(epoch, "classes")));
        report("registry.retainedHeap", HeapAgent.retained(registry));
        long epochCharge = 512L + 128L * classes;
        ProbeSupport.measure("epoch.charge.logical", epochCharge);
        ProbeSupport.note("epoch.chargeFormula", "512+128*" + classes + "=" + epochCharge);

        ProbeSupport.section("classSnapshot.layout");
        ClassStatsRegistry.ClassSnapshot snapshot = registry.captureClasses();
        report("classSnapshot.exclusiveHeap",
                HeapAgent.retained(snapshot, AtomicStatsRegistry.class));
        ProbeSupport.note("classSnapshot.exclusiveScope",
                "detached snapshot excluding its shared outer-registry reference");
        report("classSnapshot.retainedIncludingRegistry", HeapAgent.retained(snapshot));
        report("classSnapshot.values.retainedHeap", HeapAgent.retained(field(snapshot, "values")));
        ProbeSupport.measure("classSnapshot.charge.logical", 512L + 128L * classes);
        snapshot.close();

        ProbeSupport.section("commandSnapshot.layout");
        int keyCount = 16;
        Class<?> snapshotType = Class.forName("ru.octomem.engine.CommandSnapshot");
        var constructor = snapshotType.getDeclaredConstructor(int.class, AtomicLong.class, long.class);
        constructor.setAccessible(true);
        long charge = 512L + 512L * keyCount;
        Object commandSnapshot = constructor.newInstance(keyCount, new AtomicLong(), charge);
        report("commandSnapshot.retainedHeap", HeapAgent.retained(commandSnapshot));
        ProbeSupport.measure("commandSnapshot.charge.logical", charge);
        ProbeSupport.note("commandSnapshot.chargeFormula", "512+512*" + keyCount + "=" + charge);

        ProbeSupport.section("transport.controlRepresentatives");
        report("transport.laneBuffer.retainedHeap", HeapAgent.retained(ByteBuffer.allocate(512)));
        ProbeSupport.measure("transport.twoLaneBuffers.retainedHeap",
                2 * HeapAgent.retained(ByteBuffer.allocate(512)).bytes());
        report("transport.writerScratch.retainedHeap", HeapAgent.retained(new byte[256]));
        ProbeSupport.measure("transport.controlCharge.logical", 1088L + 512 + 1024 + 8192);
        ProbeSupport.note("transport.controlFormula", "1088+512+1024+8192=10816");
        ProbeSupport.measure("transport.lineCharge.logical", 2L * 8192 + 256);
        ProbeSupport.note("transport.lineFormula", "2*maxLineBytes+256 (maxLineBytes=8192)");

        ProbeSupport.section("nativeAndRss");
        ProbeSupport.measure("allocator.totalMemory.final", allocator.totalMemory());
        ProbeSupport.measure("allocator.usedMemory.final", allocator.usedMemory());
        ProbeSupport.measure("rss.beforeSettle", ProbeSupport.residentSetBytes());
        ProbeSupport.measure("heapUsed.afterSettle", ProbeSupport.settledHeapUsed());
        ProbeSupport.measure("rss.afterSettle", ProbeSupport.residentSetBytes());
        ProbeSupport.note("rss.scope", "process working set; includes JVM code/metaclass/GC overhead");

        table.close();
        allocator.close();
    }

    static void report(String key, HeapAgent.Result result) {
        ProbeSupport.measure(key, result.bytes());
        ProbeSupport.measure(key + ".objects", result.objects());
        ProbeSupport.measure(key + ".inaccessibleRefs", result.inaccessibleReferences());
        ProbeSupport.note(key + ".complete", Boolean.toString(result.complete()));
    }

    static Object firstEntry(SegmentedItemTable table) throws Exception {
        Object[] segments = (Object[]) field(table, "segments");
        for (Object segment : segments) {
            if (segment == null) {
                continue;
            }
            Object[] buckets = (Object[]) field(segment, "buckets");
            if (buckets == null) {
                continue;
            }
            for (Object bucket : buckets) {
                if (bucket != null) {
                    return bucket;
                }
            }
        }
        throw new IllegalStateException("no entry");
    }

    static Object field(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // continue up the hierarchy
            }
        }
        throw new NoSuchFieldException(name + " on " + target.getClass().getName());
    }

    /** Constant registry values with the exact required keys; not observed server output. */
    static final class PlausibleValues {
        private PlausibleValues() { }

        static java.util.Map<String, String> constants() {
            return java.util.Map.of(
                    "pid", Long.toString(ProcessHandle.current().pid()),
                    "version", "1.0.0 (octomem)",
                    "pointer_size", Long.toString(java.lang.foreign.ValueLayout.ADDRESS.byteSize() * 8),
                    "threads", "2",
                    "limit_maxbytes", Long.toString(64 * 1024 * 1024));
        }
    }
}

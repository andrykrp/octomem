package ru.octomem.engine;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.storage.FfmSlabAllocator;
import ru.octomem.storage.Item;
import ru.octomem.storage.ItemLease;
import ru.octomem.storage.SegmentedItemTable;
import ru.octomem.util.Clock;

/** Test-only standard Instrumentation probe: snapshot arrays and stable hit leases/views. */
public final class EngineSnapshotLayoutProbe {
    private static Instrumentation instrumentation;

    /** Installs standard shallow-size instrumentation, without internal JVM APIs. */
    public static void premain(String arguments, Instrumentation agent) {
        instrumentation = agent;
    }

    /** Measures actual engine objects on the executing VM, excluding preexisting cache state. */
    public static void main(String[] arguments) throws Exception {
        if (instrumentation == null) throw new IllegalStateException("Run with this class as javaagent");
        System.out.println("java=" + System.getProperty("java.runtime.version"));
        System.out.println("vendor=" + System.getProperty("java.vendor"));
        System.out.println("vm=" + System.getProperty("java.vm.name"));
        System.out.println("os=" + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println("N,arrayCount,snapshotBytes,arrayBytes,leaseAndViewBytes,totalBytes,logicalCharge");
        for (int count : new int[]{1, 2, 16, 128, 1024}) measure(count);
    }

    private static void measure(int count) throws Exception {
        long memory = 16L * 1024 * 1024;
        Clock clock = new Clock() {
            public long currentTimeSeconds() { return 1_700_000_000L; }
            public long nanoTime() { return 0; }
        };
        AtomicStatsRegistry stats = new AtomicStatsRegistry(Map.of("pid", "1", "version", "layout-test",
            "pointer_size", "64", "threads", "1", "limit_maxbytes", Long.toString(memory)));
        try (FfmSlabAllocator allocator = new FfmSlabAllocator(memory, 4096, 4096);
             SegmentedItemTable table = new SegmentedItemTable(16, 2, memory)) {
            DefaultCacheEngine engine = new DefaultCacheEngine(allocator, table, clock, stats,
                new DefaultCacheEngine.Settings(memory, 4096, "noeviction", 86400));
            byte[][] keys = new byte[count][];
            engine.start();
            try {
                for (int i = 0; i < count; i++) {
                    keys[i] = ("key" + i).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                    if (engine.set(keys[i], new byte[]{65}, 0, 0) != StoreResult.STORED) throw new AssertionError("fixture set");
                }
                KeySequence sequence = new KeySequence() {
                    public int size() { return count; }
                    public void forEachKey(KeyVisitor visitor) {
                        for (byte[] key : keys) visitor.accept(key, 0, key.length);
                    }
                };
                try (RetrievalSnapshot snapshot = engine.snapshot(sequence)) {
                    long shell = instrumentation.getObjectSize(snapshot);
                    long arrays = 0;
                    long hits = 0;
                    int arrayCount = 0;
                    Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
                    for (Field field : snapshot.getClass().getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers()) || !field.getType().isArray()) continue;
                        field.setAccessible(true);
                        Object array = field.get(snapshot);
                        arrayCount++;
                        arrays += instrumentation.getObjectSize(array);
                        int expectedLength = field.getName().equals("keys") ? 250 * count : count;
                        if (Array.getLength(array) != expectedLength) throw new AssertionError("Unexpected array length: " + field);
                        if (array instanceof Object[] references) {
                            for (Object reference : references) {
                                if (reference == null || !visited.add(reference)) continue;
                                if (reference instanceof ItemLease lease) {
                                    hits += instrumentation.getObjectSize(lease);
                                    Item view = lease.item();
                                    if (view != lease.item()) throw new AssertionError("Unstable per-hit view");
                                    if (visited.add(view)) hits += instrumentation.getObjectSize(view);
                                } else if (reference instanceof Item) {
                                    hits += instrumentation.getObjectSize(reference);
                                } else throw new AssertionError("Unrecognized snapshot reference: " + reference.getClass());
                            }
                        }
                    }
                    Object cursor = snapshot.values();
                    if (cursor != snapshot) shell += instrumentation.getObjectSize(cursor);
                    long total = shell + arrays + hits;
                    long charge = 512L + 512L * count;
                    System.out.println(count + "," + arrayCount + "," + shell + "," + arrays + "," + hits + "," + total + "," + charge);
                    if (arrayCount != 6) throw new AssertionError("Expected six arrays");
                    if (total > charge) throw new AssertionError("Snapshot allocation exceeds unchanged logical ceiling");
                    if (engine.snapshotMetadataUsedBytes() != charge) throw new AssertionError("Unexpected logical charge");
                }
                if (engine.snapshotMetadataUsedBytes() != 0) throw new AssertionError("Leaked metadata budget");
            } finally {
                engine.stop();
            }
            if (allocator.usedMemory() != 0) throw new AssertionError("Leaked native block");
        }
    }
}

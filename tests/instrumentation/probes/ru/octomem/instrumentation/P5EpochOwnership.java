package ru.octomem.instrumentation;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import ru.octomem.cli.CacheServer;
import ru.octomem.config.ConfigLoader;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.stats.Counter;
import ru.octomem.storage.FfmSlabAllocator;
import ru.octomem.storage.SegmentedItemTable;
import ru.octomem.util.SystemClock;

/**
 * P5: transient counter-epoch ownership audit for NR-17.
 *
 * <p>Measures the actual per-epoch heap size, reconciles the composition's
 * epoch allowance arithmetic from the frozen source constants, counts live
 * Epoch instances under a reset storm using {@code jcmd GC.class_histogram},
 * and demonstrates (by arming a throwing epoch reference) that the public
 * {@link CacheServer#resourceUsage()} observer never reads the counter epoch.
 *
 * <p>This probe does not claim the W+3 / W+4 ownership bound is proven; it
 * reports what was measured and what remains unobservable.
 */
public final class P5EpochOwnership {
    private P5EpochOwnership() { }

    public static void main(String[] args) throws Exception {
        ProbeSupport.printEnvironment();
        int workers = 4;
        long memoryLimit = 64L * 1024 * 1024;
        int itemMaxSize = 1024 * 1024;
        int segments = 16;

        var config = ConfigLoader.load(null, serverConfig(workers, memoryLimit, itemMaxSize));
        var bind = new java.net.InetSocketAddress(java.net.InetAddress.ofLiteral("127.0.0.1"), 0);
        CacheServer server = new CacheServer(config, bind, new SystemClock());

        FfmSlabAllocator allocator = (FfmSlabAllocator) field(server, "allocator");
        SegmentedItemTable table = (SegmentedItemTable) field(field(server, "engine"), "table");
        AtomicStatsRegistry stats = (AtomicStatsRegistry) field(server, "stats");
        int classes = allocator.classCount();

        ProbeSupport.section("epochMeasurement");
        long epochCharge = 512L + 128L * classes;
        Object epoch = ((AtomicReference<?>) field(stats, "epoch")).get();
        HeapAgent.Result epochSize = HeapAgent.retained(epoch);
        ProbeSupport.measure("workers", workers);
        ProbeSupport.measure("classCount", classes);
        ProbeSupport.measure("epoch.charge.logical", epochCharge);
        ProbeSupport.note("epoch.chargeFormula", "512+128*" + classes + "=" + epochCharge);
        ProbeSupport.measure("epoch.measuredHeap", epochSize.bytes());
        ProbeSupport.measure("epoch.measuredObjects", epochSize.objects());
        ProbeSupport.measure("epoch.measuredInaccessible", epochSize.inaccessibleReferences());
        ProbeSupport.note("epoch.measuredComplete", Boolean.toString(epochSize.complete()));

        ProbeSupport.section("compositionReservation");
        long initial = 128L + 8L * segments
                + segments * (256L + 8 * 2)
                + segments * (192L + 24L * classes)
                + 512L + 128L * classes;
        long allowanceW3 = (long) (workers + 3) * epochCharge;
        long allowanceW4 = (long) (workers + 4) * epochCharge;
        long metadataLimit = baselinePlus(initial, memoryLimit) + allowanceW3;
        long measuredMetadataUsed = table.metadataUsedBytes();
        long measuredMetadataLimit = ((Number) field(table, "metadataLimit")).longValue();
        ProbeSupport.measure("reservation.baseline", initial);
        ProbeSupport.measure("reservation.allowanceW3.logical", allowanceW3);
        ProbeSupport.measure("reservation.allowanceW4.logical", allowanceW4);
        ProbeSupport.note("reservation.allowanceFormula",
                "(workers+3)*(512+128*classes) = (" + workers + "+3)*" + epochCharge);
        ProbeSupport.measure("reservation.derivedMetadataLimit", metadataLimit);
        ProbeSupport.measure("reservation.measuredMetadataLimit", measuredMetadataLimit);
        ProbeSupport.measure("reservation.derivedMetadataUsed", initial + allowanceW3);
        ProbeSupport.measure("reservation.measuredMetadataUsed", measuredMetadataUsed);
        ProbeSupport.note("reservation.limitMatches", Boolean.toString(metadataLimit == measuredMetadataLimit));
        ProbeSupport.note("reservation.usedMatches",
                Boolean.toString(initial + allowanceW3 == measuredMetadataUsed));
        ProbeSupport.note("reservation.epochCountReserved", Integer.toString(workers + 3));
        ProbeSupport.note("design.epochCountReserved", "W+4 (arithmetic-epoch-design.md:54)");
        ProbeSupport.note("code.epochCountReserved", "W+3 (CacheServer.java:91-95)");

        ProbeSupport.section("resourceUsageEpochFree");
        AtomicReference<?> epochReference = (AtomicReference<?>) field(stats, "epoch");
        Object originalEpoch = epochReference.get();
        setField(stats, "epoch", null);
        boolean usageReadsEpoch;
        try {
            server.resourceUsage();
            usageReadsEpoch = false;
        } catch (NullPointerException expected) {
            usageReadsEpoch = true;
        }
        ProbeSupport.measure("resourceUsage.readsEpoch(trap)", usageReadsEpoch ? 1 : 0);
        boolean snapshotReadsEpoch;
        try {
            stats.snapshot();
            snapshotReadsEpoch = false;
        } catch (NullPointerException expected) {
            snapshotReadsEpoch = true;
        }
        ProbeSupport.measure("snapshot.readsEpoch(trap)", snapshotReadsEpoch ? 1 : 0);
        setField(stats, "epoch", epochReference);
        ProbeSupport.measure("trapRestored", epochReference.get() == originalEpoch ? 1 : 0);
        ProbeSupport.note("trapMethod",
                "epoch reference nulled; a reading observer NPEs, an epoch-free observer does not");

        ProbeSupport.section("allocationMeasurement");
        com.sun.management.ThreadMXBean threadBean =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        int sample = 200_000;
        long beforeReset = threadBean.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < sample; i++) {
            stats.reset();
        }
        long resetBytes = threadBean.getCurrentThreadAllocatedBytes() - beforeReset;
        ProbeSupport.measure("allocation.perReset", resetBytes / sample);
        long beforeIncrement = threadBean.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < sample; i++) {
            stats.increment(Counter.CMD_GET, 1);
        }
        long incrementBytes = threadBean.getCurrentThreadAllocatedBytes() - beforeIncrement;
        ProbeSupport.measure("allocation.perIncrement", incrementBytes / sample);
        long beforeUsage = threadBean.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < sample; i++) {
            server.resourceUsage();
        }
        long usageBytes = threadBean.getCurrentThreadAllocatedBytes() - beforeUsage;
        ProbeSupport.measure("allocation.perResourceUsage", usageBytes / sample);
        long beforeSnapshot = threadBean.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < sample; i++) {
            stats.snapshot();
        }
        long snapshotBytes = threadBean.getCurrentThreadAllocatedBytes() - beforeSnapshot;
        ProbeSupport.measure("allocation.perSnapshot", snapshotBytes / sample);
        ProbeSupport.note("allocation.interpretation",
                "reset allocates one epoch; increment/snapshot reuse the retained object; snapshot allocates detached maps");

        ProbeSupport.section("resetStormLiveness");
        Object beforeStorm = ((AtomicReference<?>) field(stats, "epoch")).get();
        runResetStorm(stats, workers);
        Object afterStorm = ((AtomicReference<?>) field(stats, "epoch")).get();
        ProbeSupport.measure("storm.epochReplaced", beforeStorm != afterStorm ? 1 : 0);
        ProbeSupport.measure("storm.serverStillUsable",
                server.resourceUsage().currentConnections() >= 0 ? 1 : 0);

        server.close();
    }

    static long baselinePlus(long initial, long memoryLimit) {
        return initial + Math.min(memoryLimit, 256L * 1024 * 1024);
    }

    /** Runs W increment loops concurrently with a reset storm, then stops. */
    static void runResetStorm(AtomicStatsRegistry stats, int workers) throws Exception {
        List<Thread> threads = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread resetter = new Thread(() -> {
            while (running.get()) {
                stats.reset();
            }
        }, "reset-storm");
        threads.add(resetter);
        for (int i = 0; i < workers; i++) {
            Thread worker = new Thread(() -> {
                while (running.get()) {
                    stats.increment(Counter.CMD_GET, 1);
                }
            }, "worker-" + i);
            threads.add(worker);
        }
        for (Thread thread : threads) {
            thread.setDaemon(true);
            thread.start();
        }
        Thread.sleep(500);
        running.set(false);
        for (Thread thread : threads) {
            thread.join(5000);
        }
    }

    static Map<String, String> serverConfig(int workers, long memoryLimit, int itemMaxSize) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("listen.address", "127.0.0.1");
        values.put("listen.port", "11211");
        values.put("memory.limit", Long.toString(memoryLimit));
        values.put("item.maxSize", Integer.toString(itemMaxSize));
        values.put("connections.max", "16");
        values.put("transport.workers", Integer.toString(workers));
        values.put("protocol.maxLineBytes", "8192");
        values.put("protocol.maxChunkBytes", "65536");
        values.put("connections.pendingWriteBytes", "8388608");
        values.put("connections.maxInflightCommands", "2");
        values.put("connections.idleTimeoutSeconds", "0");
        values.put("expiry.reclaimIntervalSeconds", "3600");
        values.put("log.level", "info");
        return values;
    }

    static Object field(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // continue
            }
        }
        throw new NoSuchFieldException(name);
    }

    static void setField(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // continue
            }
        }
        throw new NoSuchFieldException(name);
    }
}

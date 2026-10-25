package ru.octomem.instrumentation;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import ru.octomem.engine.DefaultCacheEngine;
import ru.octomem.engine.GetResult;
import ru.octomem.engine.StorageAdmission;
import ru.octomem.engine.StoreResult;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.stats.Counter;
import ru.octomem.storage.FfmSlabAllocator;
import ru.octomem.storage.SegmentedItemTable;
import ru.octomem.util.Clock;
import ru.octomem.util.SystemClock;

/**
 * P4: failure-cancellation accounting for native admission tokens.
 *
 * <p>Every path is exercised through the public engine/admission seams of the
 * composed engine (no production test is added). Native and service charges are
 * read from the public accounting accessors before and after each path so a
 * leaked reservation is visible as a non-restored counter.
 */
public final class P4Cancellation {
    private P4Cancellation() { }

    public static void main(String[] args) throws Exception {
        ProbeSupport.printEnvironment();

        ProbeSupport.section("nativeRefusalDespiteFreeServiceBudget");
        Env filled = Env.create(65536, 1024, "noeviction");
        filled.engine.start();
        int stored = 0;
        StoreResult last = StoreResult.INVALID;
        for (int i = 0; i < 2000; i++) {
            last = filled.engine.set(key("fill-" + i), new byte[100], 0, 0);
            if (last == StoreResult.STORED) {
                stored++;
            } else {
                break;
            }
        }
        ProbeSupport.measure("fill.stored", stored);
        ProbeSupport.note("fill.lastResult", last.name());
        Snapshot before = filled.snapshot();
        StorageAdmission.Result denied = filled.engine.admit(StorageAdmission.Mode.SET,
                key("new-key-after-full"), 0, 0, 0, 100);
        ProbeSupport.note("denied.status", denied.status().name());
        ProbeSupport.measure("denied.tokenNull", denied.token() == null ? 1 : 0);
        Snapshot after = filled.snapshot();
        ProbeSupport.measure("denied.nativeUsed.delta", after.nativeUsed - before.nativeUsed);
        ProbeSupport.measure("denied.serviceUsed.delta", after.serviceUsed - before.serviceUsed);
        ProbeSupport.measure("denied.tableMetadata.delta", after.tableMetadata - before.tableMetadata);
        ProbeSupport.measure("denied.serviceHeadroom",
                filled.serviceLimit() - after.serviceUsed);
        ProbeSupport.note("denied.interpretation",
                "native denial with positive service headroom; no token, no charge change");
        filled.close();

        ProbeSupport.section("oversizePrecedesAdmission");
        Env env2 = Env.create(4L * 1024 * 1024, 1024, "lru");
        env2.engine.start();
        Snapshot ovBefore = env2.snapshot();
        StorageAdmission.Result tooLarge = env2.engine.admit(StorageAdmission.Mode.SET,
                key("oversize"), 0, 0, 0, 4096);
        ProbeSupport.note("oversize.status", tooLarge.status().name());
        ProbeSupport.measure("oversize.tokenNull", tooLarge.token() == null ? 1 : 0);
        Snapshot ovAfter = env2.snapshot();
        ProbeSupport.measure("oversize.nativeUsed.delta", ovAfter.nativeUsed - ovBefore.nativeUsed);
        ProbeSupport.measure("oversize.serviceUsed.delta", ovAfter.serviceUsed - ovBefore.serviceUsed);
        ProbeSupport.measure("oversize.cmdSet.delta", ovAfter.cmdSet - ovBefore.cmdSet);

        ProbeSupport.section("abandonedBodyCancelsExactlyOnce");
        byte[] abandonedKey = key("abandoned");
        Snapshot abBefore = env2.snapshot();
        StorageAdmission.Result admitted = env2.engine.admit(StorageAdmission.Mode.SET,
                abandonedKey, 0, 0, 0, 50);
        ProbeSupport.note("abandoned.status", admitted.status().name());
        Snapshot abHeld = env2.snapshot();
        ProbeSupport.measure("abandoned.heldNative.delta", abHeld.nativeUsed - abBefore.nativeUsed);
        ProbeSupport.measure("abandoned.heldService.delta", abHeld.serviceUsed - abBefore.serviceUsed);
        ProbeSupport.measure("abandoned.cmdSet.delta", abHeld.cmdSet - abBefore.cmdSet);
        admitted.token().close();
        Snapshot abAfter = env2.snapshot();
        ProbeSupport.measure("abandoned.nativeUsed.deltaAfterClose", abAfter.nativeUsed - abBefore.nativeUsed);
        ProbeSupport.measure("abandoned.serviceUsed.deltaAfterClose", abAfter.serviceUsed - abBefore.serviceUsed);
        ProbeSupport.measure("abandoned.cmdSet.deltaAfterClose", abAfter.cmdSet - abBefore.cmdSet);

        ProbeSupport.section("conditionalRejectionReleasesToken");
        byte[] condKey = key("cond-key");
        byte[] value100 = new byte[100];
        ProbeSupport.note("cond.seed", env2.engine.set(condKey, value100, 0, 0).name());
        GetResult seedView = env2.engine.get(condKey);
        long seedCas = seedView.found() ? seedView.item().casId() : -1;
        int seedLength = seedView.found() ? seedView.item().valueLength() : -1;
        seedView.close();
        Snapshot condBefore = env2.snapshot();
        StorageAdmission.Result addAdmit = env2.engine.admit(StorageAdmission.Mode.ADD, condKey, 0, 0, 0, 50);
        ProbeSupport.note("cond.addAdmit", addAdmit.status().name());
        StoreResult addResult = env2.engine.storePrepared(addAdmit.token(), StorageAdmission.Mode.ADD,
                condKey, new byte[50], 0, 0, 0);
        ProbeSupport.note("cond.addResult", addResult.name());
        StorageAdmission.Result casAdmit = env2.engine.admit(StorageAdmission.Mode.CAS, condKey, 0, 0,
                seedCas + 1, 50);
        StoreResult casResult = env2.engine.storePrepared(casAdmit.token(), StorageAdmission.Mode.CAS,
                condKey, new byte[50], 0, 0, seedCas + 1);
        ProbeSupport.note("cond.casResult", casResult.name());
        Snapshot condAfter = env2.snapshot();
        ProbeSupport.measure("cond.nativeUsed.delta", condAfter.nativeUsed - condBefore.nativeUsed);
        ProbeSupport.measure("cond.serviceUsed.delta", condAfter.serviceUsed - condBefore.serviceUsed);
        ProbeSupport.measure("cond.cmdSet.delta", condAfter.cmdSet - condBefore.cmdSet);
        GetResult condView = env2.engine.get(condKey);
        ProbeSupport.measure("cond.valueLengthPreserved", condView.item().valueLength());
        ProbeSupport.measure("cond.casPreserved", condView.item().casId() == seedCas ? 1 : 0);
        ProbeSupport.note("cond.seedValueLength", Integer.toString(seedLength));
        condView.close();

        ProbeSupport.section("combinedAppendFailurePreservesOldRecord");
        Env env3 = Env.create(2L * 1024 * 1024, 1000000, "noeviction");
        env3.engine.start();
        byte[] appendKey = key("append-key");
        byte[] oldValue = new byte[500000];
        ProbeSupport.note("append.seed", env3.engine.set(appendKey, oldValue, 0, 0).name());
        GetResult appendSeed = env3.engine.get(appendKey);
        long appendCas = appendSeed.item().casId();
        int appendLength = appendSeed.item().valueLength();
        appendSeed.close();
        Snapshot appendBefore = env3.snapshot();
        byte[] suffix = new byte[400000];
        StorageAdmission.Result fragmentAdmit = env3.engine.admit(StorageAdmission.Mode.APPEND,
                appendKey, 0, 0, 0, suffix.length);
        ProbeSupport.note("append.fragmentAdmit", fragmentAdmit.status().name());
        ProbeSupport.measure("append.fragmentVerdict(1=token,0=null)", fragmentAdmit.token() != null ? 1 : 0);
        Snapshot fragmentHeld = env3.snapshot();
        ProbeSupport.measure("append.fragment.heldNative.delta", fragmentHeld.nativeUsed - appendBefore.nativeUsed);
        ProbeSupport.measure("append.fragment.heldService.delta", fragmentHeld.serviceUsed - appendBefore.serviceUsed);
        StoreResult appendResult = env3.engine.storePrepared(fragmentAdmit.token(), StorageAdmission.Mode.APPEND,
                appendKey, suffix, 0, 0, 0);
        ProbeSupport.note("append.result", appendResult.name());
        Snapshot appendAfter = env3.snapshot();
        ProbeSupport.measure("append.nativeUsed.delta", appendAfter.nativeUsed - appendBefore.nativeUsed);
        ProbeSupport.measure("append.serviceUsed.delta", appendAfter.serviceUsed - appendBefore.serviceUsed);
        ProbeSupport.measure("append.totalItems.delta", appendAfter.totalItems - appendBefore.totalItems);
        GetResult appendView = env3.engine.get(appendKey);
        ProbeSupport.measure("append.valueLengthPreserved", appendView.item().valueLength());
        ProbeSupport.note("append.originalValueLength", Integer.toString(appendLength));
        ProbeSupport.measure("append.casPreserved", appendView.item().casId() == appendCas ? 1 : 0);
        appendView.close();
        env3.close();

        ProbeSupport.section("tokenMisuse");
        Env envA = Env.create(1024L * 1024, 1024, "lru");
        Env envB = Env.create(1024L * 1024, 1024, "lru");
        envA.engine.start();
        envB.engine.start();
        byte[] misuseKey = key("misuse-key");
        StorageAdmission.Result fromB = envB.engine.admit(StorageAdmission.Mode.SET, misuseKey, 0, 0, 0, 40);
        boolean crossRejected;
        try {
            envA.engine.storePrepared(fromB.token(), StorageAdmission.Mode.SET, misuseKey, new byte[40], 0, 0, 0);
            crossRejected = false;
        } catch (IllegalArgumentException expected) {
            crossRejected = true;
        }
        ProbeSupport.measure("misuse.crossServerRejected", crossRejected ? 1 : 0);
        fromB.token().close();
        Snapshot misuseAfterCross = envB.snapshot();
        ProbeSupport.measure("misuse.afterCrossRelease.serviceUsed", misuseAfterCross.serviceUsed);

        StorageAdmission.Result once = envA.engine.admit(StorageAdmission.Mode.SET, misuseKey, 0, 0, 0, 40);
        StoreResult first = envA.engine.storePrepared(once.token(), StorageAdmission.Mode.SET, misuseKey,
                new byte[40], 0, 0, 0);
        ProbeSupport.note("misuse.firstStore", first.name());
        boolean doubleRejected;
        try {
            envA.engine.storePrepared(once.token(), StorageAdmission.Mode.SET, misuseKey, new byte[40], 0, 0, 0);
            doubleRejected = false;
        } catch (IllegalStateException expected) {
            doubleRejected = true;
        }
        ProbeSupport.measure("misuse.doubleConsumeRejected", doubleRejected ? 1 : 0);
        Snapshot misuseFinal = envA.snapshot();
        ProbeSupport.measure("misuse.finalServiceUsed", misuseFinal.serviceUsed);
        ProbeSupport.measure("misuse.expectedSingleRecordService", 512L);
        envA.close();
        envB.close();

        ProbeSupport.section("shutdownCancelsAdmittedIncomplete");
        Env env4 = Env.create(1024L * 1024, 1024, "lru");
        env4.engine.start();
        Snapshot stopBefore = env4.snapshot();
        StorageAdmission.Result stopAdmit = env4.engine.admit(StorageAdmission.Mode.SET,
                key("shutdown-key"), 0, 0, 0, 64);
        Snapshot stopHeld = env4.snapshot();
        ProbeSupport.measure("shutdown.heldNative.delta", stopHeld.nativeUsed - stopBefore.nativeUsed);
        env4.engine.stop();
        stopAdmit.token().close();
        Snapshot stopAfter = env4.snapshot();
        ProbeSupport.measure("shutdown.nativeUsed.deltaAfterCancel",
                stopAfter.nativeUsed - stopBefore.nativeUsed);
        ProbeSupport.measure("shutdown.serviceUsed.deltaAfterCancel",
                stopAfter.serviceUsed - stopBefore.serviceUsed);
        env4.close();

        ProbeSupport.section("flushed");
        ProbeSupport.measure("rss.final", ProbeSupport.residentSetBytes());
    }

    static byte[] key(String base) {
        return base.getBytes(StandardCharsets.US_ASCII);
    }

    /** Immutable accounting sample of one engine composition. */
    static final class Snapshot {
        final long nativeUsed;
        final long serviceUsed;
        final long tableMetadata;
        final long cmdSet;
        final long totalItems;

        Snapshot(long nativeUsed, long serviceUsed, long tableMetadata, long cmdSet, long totalItems) {
            this.nativeUsed = nativeUsed;
            this.serviceUsed = serviceUsed;
            this.tableMetadata = tableMetadata;
            this.cmdSet = cmdSet;
            this.totalItems = totalItems;
        }
    }

    /** One engine composition with its own table, allocator and registry. */
    static final class Env implements AutoCloseable {
        final FfmSlabAllocator allocator;
        final SegmentedItemTable table;
        final AtomicStatsRegistry stats;
        final DefaultCacheEngine engine;
        final long memoryLimit;
        final int itemMaxSize;

        private Env(FfmSlabAllocator allocator, SegmentedItemTable table, AtomicStatsRegistry stats,
                DefaultCacheEngine engine, long memoryLimit, int itemMaxSize) {
            this.allocator = allocator;
            this.table = table;
            this.stats = stats;
            this.engine = engine;
            this.memoryLimit = memoryLimit;
            this.itemMaxSize = itemMaxSize;
        }

        static Env create(long memoryLimit, int itemMaxSize, String policy) {
            int pageSize = (int) Math.min(1_048_576L, memoryLimit);
            FfmSlabAllocator allocator = new FfmSlabAllocator(memoryLimit, pageSize,
                    Math.clamp(itemMaxSize, 64, Integer.MAX_VALUE - 7));
            AtomicStatsRegistry stats = new AtomicStatsRegistry(constants(memoryLimit));
            long metadataLimit = 4L * 1024 * 1024;
            SegmentedItemTable table = new SegmentedItemTable(16, 2, metadataLimit);
            Clock clock = new SystemClock();
            DefaultCacheEngine engine = new DefaultCacheEngine(allocator, table, clock, stats,
                    new DefaultCacheEngine.Settings(memoryLimit, itemMaxSize, policy, 3600));
            return new Env(allocator, table, stats, engine, memoryLimit, itemMaxSize);
        }

        Snapshot snapshot() {
            Map<Counter, Long> counters = stats.snapshot().counters();
            return new Snapshot(allocator.usedMemory(), engine.serviceMetadataUsedBytes(),
                    table.metadataUsedBytes(), counters.getOrDefault(Counter.CMD_SET, 0L),
                    counters.getOrDefault(Counter.TOTAL_ITEMS, 0L));
        }

        long serviceLimit() {
            long versions = memoryLimit / 64;
            return versions > Long.MAX_VALUE / 512 ? Long.MAX_VALUE : versions * 512;
        }

        @Override public void close() {
            try {
                table.close();
            } finally {
                allocator.close();
            }
        }

        static Map<String, String> constants(long memoryLimit) {
            return Map.of(
                    "pid", Long.toString(ProcessHandle.current().pid()),
                    "version", "1.0.0 (octomem)",
                    "pointer_size", Long.toString(java.lang.foreign.ValueLayout.ADDRESS.byteSize() * 8),
                    "threads", "2",
                    "limit_maxbytes", Long.toString(memoryLimit));
        }
    }
}

package ru.octomem.stats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
class StatsConcurrencyTest {
    @Test
    void realConcurrentIncrementsAreExactAcrossDeterministicResetPhases() throws Exception {
        StatsRegistry registry = StatsOracle.registry();
        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            for (int phase = 0; phase < 3; phase++) {
                registry.reset();
                for (Gauge gauge : Gauge.values()) registry.setGauge(gauge, 900 + gauge.ordinal());
                List<Callable<Void>> writers = new ArrayList<>();
                for (int worker = 0; worker < 6; worker++) {
                    long delta = worker + 1;
                    writers.add(() -> {
                        for (int i = 0; i < 1000; i++) {
                            for (Counter counter : Counter.values()) registry.increment(counter, delta);
                        }
                        return null;
                    });
                }
                together(pool, writers);
                Snapshot completed = registry.snapshot();
                completed.counters().values().forEach(value -> assertEquals(21000L, value.longValue()));
                registry.reset();
                Snapshot reset = registry.snapshot();
                reset.counters().values().forEach(value -> assertEquals(0L, value.longValue()));
                assertEquals(completed.gauges(), reset.gauges());
                assertEquals(completed.constants(), reset.constants());
                completed.counters().values().forEach(value -> assertEquals(21000L, value.longValue()));
            }
        }
    }

    @Test
    void concurrentNearOverflowUpdatesSaturateWithoutWrapOrLostPostResetWrites() throws Exception {
        StatsRegistry registry = StatsOracle.registry();
        long delta = Long.MAX_VALUE / 5;
        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            for (int phase = 0; phase < 3; phase++) {
                registry.reset();
                List<Callable<Void>> writers = new ArrayList<>();
                for (int worker = 0; worker < 6; worker++) {
                    writers.add(() -> {
                        for (Counter counter : Counter.values()) registry.increment(counter, delta);
                        return null;
                    });
                }
                together(pool, writers);
                long expected = BigInteger.valueOf(delta).multiply(BigInteger.valueOf(6))
                        .min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
                registry.snapshot().counters().values().forEach(value -> assertEquals(expected, value.longValue()));
            }
        }
    }

    @Test
    void resetAndOneIncrementOverlapOnlyAdmitsOldOrNewEpochOutcome() throws Exception {
        StatsRegistry registry = StatsOracle.registry();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int trial = 0; trial < 300; trial++) {
                registry.reset();
                registry.increment(Counter.CMD_GET, 1000);
                registry.setGauge(Gauge.BYTES, 777);
                together(pool, List.of(
                        () -> { registry.increment(Counter.CMD_GET, 7); return null; },
                        () -> { registry.reset(); return null; }));
                long raced = registry.snapshot().counters().get(Counter.CMD_GET);
                assertTrue(Set.of(0L, 7L).contains(raced), "Forbidden raced value: " + raced);
                registry.increment(Counter.CMD_GET, 11);
                assertEquals(raced + 11, registry.snapshot().counters().get(Counter.CMD_GET).longValue());
                assertEquals(777L, registry.snapshot().gauges().get(Gauge.BYTES).longValue());
            }
        }
    }

    @Test
    void snapshotRacingResetCannotMixPrefilledCounterEpochWithZeroEpoch() throws Exception {
        StatsRegistry registry = StatsOracle.registry();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int trial = 0; trial < 300; trial++) {
                registry.reset();
                for (Counter counter : Counter.values()) registry.increment(counter, 100 + counter.ordinal());
                Snapshot before = registry.snapshot();
                List<Snapshot> results = together(pool, List.of(
                        registry::snapshot,
                        () -> { registry.reset(); return registry.snapshot(); }));
                Map<Counter, Long> zeroEpoch = registry.snapshot().counters();
                assertTrue(zeroEpoch.values().stream().allMatch(value -> value == 0));
                for (Snapshot snapshot : results) {
                    assertTrue(snapshot.counters().equals(before.counters()) || snapshot.counters().equals(zeroEpoch),
                            "Snapshot mixed distinct epochs: " + snapshot.counters());
                }
                for (Counter counter : Counter.values()) registry.increment(counter, 1);
                for (Snapshot snapshot : results) {
                    assertTrue(snapshot.counters().equals(before.counters()) || snapshot.counters().equals(zeroEpoch));
                }
            }
        }
    }

    @Test
    void concurrentGaugeWritesResetAndSnapshotsReadOnlyWholeLongValues() throws Exception {
        StatsRegistry registry = StatsOracle.registry();
        long first = 0x0123456789ABCDEFL;
        long second = 0x7EDCBA9876543210L;
        for (Gauge gauge : Gauge.values()) registry.setGauge(gauge, first);
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            together(pool, List.of(
                    () -> {
                        for (int i = 0; i < 2000; i++) {
                            for (Gauge gauge : Gauge.values()) registry.setGauge(gauge, (i & 1) == 0 ? second : first);
                        }
                        return null;
                    },
                    () -> { for (int i = 0; i < 2000; i++) registry.reset(); return null; },
                    () -> {
                        for (int i = 0; i < 2000; i++) {
                            Snapshot snapshot = registry.snapshot();
                            snapshot.gauges().values().forEach(value -> assertTrue(value == first || value == second,
                                    "Torn or reset gauge: " + value));
                            assertEquals(StatsOracle.CONSTANTS, snapshot.constants());
                        }
                        return null;
                    }));
        }
        registry.snapshot().gauges().values().forEach(value -> assertEquals(first, value.longValue()));
    }

    @Test
    void weakSnapshotsUnderIncrementsAreBoundedAndMonotonicPerCounter() throws Exception {
        StatsRegistry registry = StatsOracle.registry();
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            Callable<Void> writer = () -> {
                for (int i = 0; i < 2000; i++) {
                    for (Counter counter : Counter.values()) registry.increment(counter, 1);
                }
                return null;
            };
            together(pool, List.of(writer, writer, () -> {
                Snapshot previous = registry.snapshot();
                for (int i = 0; i < 2000; i++) {
                    Snapshot current = registry.snapshot();
                    for (Counter counter : Counter.values()) {
                        long value = current.counters().get(counter);
                        assertTrue(value >= previous.counters().get(counter));
                        assertTrue(value <= 4000);
                    }
                    previous = current;
                }
                return null;
            }));
        }
        registry.snapshot().counters().values().forEach(value -> assertEquals(4000L, value.longValue()));
    }

    private static <T> List<T> together(ExecutorService pool, List<Callable<T>> actions) throws Exception {
        CyclicBarrier start = new CyclicBarrier(actions.size());
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> action : actions) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return action.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        } finally {
            for (Future<T> future : futures) future.cancel(true);
        }
    }
}

package ru.octomem.stats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class StatsRegistryTest {
    @Test
    void literalNamesAndCompleteDisjointWireKeySetMatchRequiredProfile() {
        assertEquals(StatsOracle.COUNTERS, Arrays.stream(Counter.values())
                .collect(Collectors.toMap(Enum::name, Counter::wireName)));
        assertEquals(StatsOracle.GAUGES, Arrays.stream(Gauge.values())
                .collect(Collectors.toMap(Enum::name, Gauge::wireName)));
        var wireKeys = new HashSet<>(StatsOracle.CONSTANT_KEYS);
        for (Counter counter : Counter.values()) {
            assertTrue(wireKeys.add(counter.wireName()));
            assertTrue(counter.wireName().matches("[a-z]+(?:_[a-z]+)*"));
        }
        for (Gauge gauge : Gauge.values()) {
            assertTrue(wireKeys.add(gauge.wireName()));
            assertTrue(gauge.wireName().matches("[a-z]+(?:_[a-z]+)*"));
        }
        assertEquals(37, wireKeys.size());
        assertEquals(Counter.CMD_GET, Counter.valueOf("CMD_GET"));
        assertEquals(Gauge.BYTES, Gauge.valueOf("BYTES"));
    }

    @Test
    void initialSnapshotHasAllFieldsIncludingZerosAndExactConstants() {
        Snapshot snapshot = StatsOracle.registry().snapshot();
        assertEquals(StatsOracle.COUNTERS.keySet(), snapshot.counters().keySet().stream()
                .map(Enum::name).collect(Collectors.toSet()));
        assertEquals(StatsOracle.GAUGES.keySet(), snapshot.gauges().keySet().stream()
                .map(Enum::name).collect(Collectors.toSet()));
        snapshot.counters().values().forEach(value -> assertEquals(0L, value.longValue()));
        snapshot.gauges().values().forEach(value -> assertEquals(0L, value.longValue()));
        assertEquals(StatsOracle.CONSTANTS, snapshot.constants());
    }

    @ParameterizedTest
    @EnumSource(Counter.class)
    void eachCounterIsIndependentAndUsesSaturatingBigIntegerArithmetic(Counter counter) {
        StatsRegistry registry = StatsOracle.registry();
        BigInteger expected = BigInteger.ZERO;
        BigInteger maximum = BigInteger.valueOf(Long.MAX_VALUE);
        long[] deltas = {0, 1, 7, Long.MAX_VALUE - 10, 1, 1, 1, Long.MAX_VALUE, 0};
        for (long delta : deltas) {
            registry.increment(counter, delta);
            expected = expected.add(BigInteger.valueOf(delta)).min(maximum);
            assertEquals(expected.longValueExact(), registry.snapshot().counters().get(counter).longValue());
        }
        for (Counter other : Counter.values()) {
            if (other != counter) {
                assertEquals(0L, registry.snapshot().counters().get(other).longValue());
            }
        }
        registry.reset();
        registry.increment(counter, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, registry.snapshot().counters().get(counter).longValue());
    }

    @Test
    void seededMixedOperationsMatchIndependentArithmeticAndResetModel() {
        StatsRegistry registry = StatsOracle.registry();
        var counters = new HashMap<Counter, BigInteger>();
        var gauges = new HashMap<Gauge, Long>();
        Arrays.stream(Counter.values()).forEach(counter -> counters.put(counter, BigInteger.ZERO));
        Arrays.stream(Gauge.values()).forEach(gauge -> gauges.put(gauge, 0L));
        Random random = new Random(0x57005L);
        for (int i = 0; i < 2000; i++) {
            switch (random.nextInt(10)) {
                case 0 -> {
                    registry.reset();
                    counters.replaceAll((counter, value) -> BigInteger.ZERO);
                }
                case 1, 2 -> {
                    Gauge gauge = Gauge.values()[random.nextInt(Gauge.values().length)];
                    long value = random.nextLong() & Long.MAX_VALUE;
                    registry.setGauge(gauge, value);
                    gauges.put(gauge, value);
                }
                default -> {
                    Counter counter = Counter.values()[random.nextInt(Counter.values().length)];
                    long delta = random.nextBoolean() ? random.nextInt(100) : random.nextLong() & Long.MAX_VALUE;
                    registry.increment(counter, delta);
                    counters.compute(counter, (key, value) -> value.add(BigInteger.valueOf(delta))
                            .min(BigInteger.valueOf(Long.MAX_VALUE)));
                }
            }
            Snapshot snapshot = registry.snapshot();
            counters.forEach((counter, expected) ->
                    assertEquals(expected.longValueExact(), snapshot.counters().get(counter).longValue()));
            assertEquals(gauges, snapshot.gauges());
            assertEquals(StatsOracle.CONSTANTS, snapshot.constants());
        }
    }

    @ParameterizedTest
    @EnumSource(Gauge.class)
    void eachGaugeCanDecreaseAndPreservesFullNonnegativeLongRange(Gauge gauge) {
        StatsRegistry registry = StatsOracle.registry();
        for (long value : new long[]{0, 1, Long.MAX_VALUE, 3, 0}) {
            registry.setGauge(gauge, value);
            assertEquals(value, registry.snapshot().gauges().get(gauge).longValue());
            for (Gauge other : Gauge.values()) {
                if (other != gauge) assertEquals(0L, registry.snapshot().gauges().get(other).longValue());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Counter.class)
    void negativeDeltasFailWithoutMutatingAnyField(Counter counter) {
        StatsRegistry registry = StatsOracle.registry();
        registry.increment(counter, 19);
        for (long delta : new long[]{-1, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> registry.increment(counter, delta));
            assertEquals(19L, registry.snapshot().counters().get(counter).longValue());
        }
    }

    @ParameterizedTest
    @EnumSource(Gauge.class)
    void negativeGaugeAssignmentsFailWithoutChangingValue(Gauge gauge) {
        StatsRegistry registry = StatsOracle.registry();
        registry.setGauge(gauge, 19);
        for (long value : new long[]{-1, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> registry.setGauge(gauge, value));
            assertEquals(19L, registry.snapshot().gauges().get(gauge).longValue());
        }
    }

    @Test
    void nullMutationArgumentsFailWithoutChangingSnapshot() {
        StatsRegistry registry = StatsOracle.registry();
        Snapshot before = registry.snapshot();
        assertThrows(NullPointerException.class, () -> registry.increment(null, 0));
        assertThrows(NullPointerException.class, () -> registry.setGauge(null, 0));
        assertEquals(before.counters(), registry.snapshot().counters());
        assertEquals(before.gauges(), registry.snapshot().gauges());
    }

    @Test
    void resetClearsAllCountersOnlyAndOldSnapshotNeverChanges() {
        StatsRegistry registry = StatsOracle.registry();
        for (Counter counter : Counter.values()) registry.increment(counter, 41 + counter.ordinal());
        for (Gauge gauge : Gauge.values()) registry.setGauge(gauge, Long.MAX_VALUE - gauge.ordinal());
        Snapshot before = registry.snapshot();
        Map<Counter, Long> counters = new HashMap<>(before.counters());
        Map<Gauge, Long> gauges = new HashMap<>(before.gauges());
        for (int i = 0; i < 3; i++) {
            registry.reset();
            Snapshot after = registry.snapshot();
            after.counters().values().forEach(value -> assertEquals(0L, value.longValue()));
            assertEquals(gauges, after.gauges());
            assertEquals(StatsOracle.CONSTANTS, after.constants());
            for (Counter counter : Counter.values()) registry.increment(counter, 1);
        }
        registry.setGauge(Gauge.TIME, 0);
        assertEquals(counters, before.counters());
        assertEquals(gauges, before.gauges());
        assertEquals(StatsOracle.CONSTANTS, before.constants());
    }

    @Test
    void allSnapshotMapsAndTheirViewsAreImmutable() {
        Snapshot snapshot = StatsOracle.registry().snapshot();
        assertImmutable(snapshot.counters(), Counter.CMD_GET, 1L);
        assertImmutable(snapshot.gauges(), Gauge.BYTES, 1L);
        assertImmutable(snapshot.constants(), "pid", "999");
    }

    private static <K, V> void assertImmutable(Map<K, V> map, K key, V value) {
        Map<K, V> before = new HashMap<>(map);
        assertThrows(UnsupportedOperationException.class, () -> map.put(key, value));
        assertThrows(UnsupportedOperationException.class, () -> map.remove(key));
        assertThrows(UnsupportedOperationException.class, map::clear);
        assertThrows(UnsupportedOperationException.class, () -> map.keySet().remove(key));
        assertThrows(UnsupportedOperationException.class, () -> map.values().remove(map.get(key)));
        assertThrows(UnsupportedOperationException.class, () -> map.entrySet().iterator().next().setValue(value));
        assertThrows(UnsupportedOperationException.class, () -> map.replaceAll((k, v) -> value));
        assertEquals(before, map);
    }
}

package ru.octomem.stats;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

/**
 * JDK-only statistics registry using CAS increments and atomic epoch replacement.
 * Counter updates capture the epoch once and retry CAS only within that epoch;
 * reset does not wait for old-epoch writers. Those overlapping writers may
 * complete against the retired epoch and are never replayed into the new one.
 * This is the reset ordering described by {@link StatsRegistry}, not a
 * linearizable multi-field snapshot. Gauges reside outside the epoch.
 * Snapshots use atomic reads without explicit locks and never mix epochs.
 *
 * @since 1.0
 */
public final class AtomicStatsRegistry implements ClassStatsRegistry {
    private static final Counter[] COUNTERS = Counter.values();
    private static final Gauge[] GAUGES = Gauge.values();
    private static final String[] CONSTANT_KEYS = {
        "pid", "version", "pointer_size", "threads", "limit_maxbytes"
    };

    private final Map<String, String> constants;
    private final AtomicReference<Epoch> epoch = new AtomicReference<>(new Epoch());
    private final AtomicLongArray gauges = new AtomicLongArray(GAUGES.length);
    private final SafeLogger logger;
    private int classCount;
    private IntFunction<ClassState> stateSource;
    private AtomicLong snapshotUsed;
    private long snapshotLimit;

    /**
     * Copies and validates the five required process constants before publication.
     * The input must not be concurrently modified during construction. Exactly
     * {@code pid}, {@code version}, {@code pointer_size}, {@code threads} and
     * {@code limit_maxbytes} are required; no other keys are accepted.
     * Numeric constants use canonical positive ASCII decimal up to
     * {@link Long#MAX_VALUE}, with no leading zeros, sign or whitespace.
     * {@code pointer_size} must be {@code 32} or {@code 64}. The version is
     * 1 to 128 printable ASCII characters in the range {@code 0x20..0x7e}.
     * Validation failures do not echo rejected input or retain parse causes.
     *
     * @param immutableFields the required process constants, defensively copied
     * @throws NullPointerException if the map, any key or any value is null
     * @throws IllegalArgumentException if keys or values violate the stated contract
     */
    public AtomicStatsRegistry(Map<String, String> immutableFields) {
        this(immutableFields, new SafeLogger("info"));
    }

    /** Creates a registry sharing an explicitly owned server logger. */
    public AtomicStatsRegistry(Map<String, String> immutableFields, SafeLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(immutableFields, "Constants map is required");
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : immutableFields.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "Constant key is required");
            String value = Objects.requireNonNull(entry.getValue(), "Constant value is required");
            fields.put(key, value);
        }
        if (fields.size() != CONSTANT_KEYS.length) {
            throw new IllegalArgumentException("Invalid constant keys");
        }
        Map<String, String> validated = new LinkedHashMap<>();
        for (String key : CONSTANT_KEYS) {
            String value = fields.get(key);
            if (value == null) {
                throw new IllegalArgumentException("Invalid constant keys");
            }
            if (key.equals("version")) {
                if (value.isEmpty() || value.length() > 128 || value.charAt(0)==' '
                        || value.charAt(value.length()-1)==' ') {
                    throw new IllegalArgumentException("Invalid constant value");
                }
                for (int i = 0; i < value.length(); i++) {
                    char character = value.charAt(i);
                    if (character < 0x20 || character > 0x7e) {
                        throw new IllegalArgumentException("Invalid constant value");
                    }
                }
            } else {
                if (value.isEmpty() || value.length() > 19 || value.charAt(0) == '0') {
                    throw new IllegalArgumentException("Invalid constant value");
                }
                long number = 0;
                for (int i = 0; i < value.length(); i++) {
                    int digit = value.charAt(i) - '0';
                    if (digit < 0 || digit > 9 || number > (Long.MAX_VALUE - digit) / 10) {
                        throw new IllegalArgumentException("Invalid constant value");
                    }
                    number = number * 10 + digit;
                }
                if (key.equals("pointer_size") && number != 32 && number != 64) {
                    throw new IllegalArgumentException("Invalid constant value");
                }
            }
            validated.put(key, value);
        }
        constants = Collections.unmodifiableMap(validated);
    }

    /** {@inheritDoc} */
    @Override
    public void increment(Counter counter, long delta) {
        Objects.requireNonNull(counter, "Counter is required");
        if (delta < 0) {
            throw new IllegalArgumentException("Counter delta must be nonnegative");
        }
        add(epoch.get().counters, counter.ordinal(), delta);
    }

    private static void add(AtomicLongArray counters, int index, long delta) {
        long current;
        long updated;
        do {
            current = counters.get(index);
            updated = delta > Long.MAX_VALUE - current ? Long.MAX_VALUE : current + delta;
        } while (!counters.compareAndSet(index, current, updated));
    }

    /** {@inheritDoc} */
    @Override
    public void setGauge(Gauge gauge, long value) {
        Objects.requireNonNull(gauge, "Gauge is required");
        if (value < 0) {
            throw new IllegalArgumentException("Gauge value must be nonnegative");
        }
        gauges.set(gauge.ordinal(), value);
    }

    /** Reads one persistent gauge without capturing or retaining a counter epoch. */
    public long gauge(Gauge gauge) { return gauges.get(Objects.requireNonNull(gauge).ordinal()); }

    /** {@inheritDoc} */
    @Override
    public Snapshot snapshot() {
        AtomicLongArray counters = epoch.get().counters;
        Map<Counter, Long> counterValues = new EnumMap<>(Counter.class);
        for (Counter counter : COUNTERS) {
            counterValues.put(counter, counters.get(counter.ordinal()));
        }
        Map<Gauge, Long> gaugeValues = new EnumMap<>(Gauge.class);
        for (Gauge gauge : GAUGES) {
            gaugeValues.put(gauge, gauges.get(gauge.ordinal()));
        }
        return new DefaultSnapshot(Collections.unmodifiableMap(counterValues),
                Collections.unmodifiableMap(gaugeValues), constants);
    }

    /** {@inheritDoc} */
    @Override
    public void reset() {
        epoch.set(new Epoch(classCount));
    }

    @Override public SafeLogger logger() { return logger; }

    @Override public synchronized void initializeClasses(int count, IntFunction<ClassState> source,
            AtomicLong used, long limit) {
        if (stateSource != null || count <= 0 || count > 128 || limit < 0) {
            throw new IllegalArgumentException("Invalid class initialization");
        }
        Objects.requireNonNull(source); Objects.requireNonNull(used);
        Epoch replacement = new Epoch(count);
        AtomicLongArray previous = epoch.get().counters;
        for (Counter counter : COUNTERS) replacement.counters.set(counter.ordinal(), previous.get(counter.ordinal()));
        classCount = count; stateSource = source; snapshotUsed = used; snapshotLimit = limit;
        epoch.set(replacement);
    }

    private static int operation(Counter counter) {
        return switch (counter) {
            case GET_HITS -> 0; case CMD_SET -> 1; case DELETE_HITS -> 2;
            case INCR_HITS -> 3; case DECR_HITS -> 4; case CAS_HITS -> 5;
            case CAS_BADVAL -> 6; case TOUCH_HITS -> 7;
            default -> throw new IllegalArgumentException("Not a class counter");
        };
    }

    @Override public void incrementClass(int id, Counter counter, long delta) {
        Objects.checkIndex(id, classCount);
        if (delta < 0) throw new IllegalArgumentException("Negative delta");
        int field = operation(counter);
        Epoch current = epoch.get();
        add(current.classes, id * 16 + field, delta);
        add(current.counters, counter.ordinal(), delta);
    }

    @Override public void outOfMemory(int id) {
        Objects.checkIndex(id, classCount);
        // The epoch is borrowed only by this expression, never across a reentrant sink.
        add(epoch.get().classes, id * 16 + 8 + Event.OUTOFMEMORY.ordinal(), 1);
        logger.log(SafeLogger.Event.CAPACITY);
    }

    @Override public void recordRemoval(int id, Removal route, boolean unfetched, boolean expiring, long age) {
        Objects.checkIndex(id, classCount);
        Epoch current = epoch.get();
        int base = id * 16 + 8;
        if (route == Removal.EVICTION) {
            add(current.classes, base + Event.EVICTED.ordinal(), 1);
            add(current.counters, Counter.EVICTIONS.ordinal(), 1);
            if (expiring) add(current.classes, base + Event.EVICTED_NONZERO.ordinal(), 1);
            current.classes.set(base + Event.EVICTED_TIME.ordinal(), Math.max(0, age));
            if (unfetched) {
                add(current.classes, base + Event.EVICTED_UNFETCHED.ordinal(), 1);
                add(current.counters, Counter.EVICTED_UNFETCHED.ordinal(), 1);
            }
        } else if (route == Removal.TAIL || route == Removal.CRAWLER) {
            add(current.classes, base + (route == Removal.TAIL ? Event.RECLAIMED : Event.CRAWLER_RECLAIMED).ordinal(), 1);
            if (route == Removal.TAIL) add(current.counters, Counter.RECLAIMED.ordinal(), 1);
            if (unfetched) {
                add(current.classes, base + Event.EXPIRED_UNFETCHED.ordinal(), 1);
                add(current.counters, Counter.EXPIRED_UNFETCHED.ordinal(), 1);
            }
        }
    }

    @Override public ClassSnapshot captureClasses() {
        if (stateSource == null) throw new IllegalStateException("Class statistics unavailable");
        long charge = 512L + 128L * classCount;
        long used = snapshotUsed.get();
        for (;;) {
            if (charge > snapshotLimit - used) throw new IllegalStateException("Statistics capacity exhausted");
            if (snapshotUsed.compareAndSet(used, used + charge)) break;
            used = snapshotUsed.get();
        }
        try {
            long[] values = new long[classCount * 16];
            Epoch captured = epoch.get();
            for (int i = 0; i < values.length; i++) values[i] = captured.classes.get(i);
            return new Classes(values, charge);
        } catch (RuntimeException | Error failure) {
            snapshotUsed.addAndGet(-charge); throw failure;
        }
    }

    private final class Classes implements ClassSnapshot {
        private long[] values;
        private final long charge;
        Classes(long[] values, long charge) { this.values = values; this.charge = charge; }
        @Override public int classCount() { return classCount; }
        private int base(int id) {
            if (values == null) throw new IllegalStateException("Closed statistics snapshot");
            return Objects.checkIndex(id, classCount) * 16;
        }
        @Override public ClassState state(int id) { base(id); return stateSource.apply(id); }
        @Override public long counter(int id, Counter counter) { return values[base(id) + operation(counter)]; }
        @Override public long event(int id, Event event) { return values[base(id) + 8 + event.ordinal()]; }
        @Override public void close() {
            if (values != null) { values = null; snapshotUsed.addAndGet(-charge); }
        }
    }

    /** Counter storage retained by any in-flight operations across reset. */
    private static final class Epoch {
        private final AtomicLongArray counters = new AtomicLongArray(COUNTERS.length);
        private final AtomicLongArray classes;
        Epoch() { this(0); }
        Epoch(int count) { classes = new AtomicLongArray(count * 16); }
    }

    /** Maps are immutable and privately owned before this record is constructed. */
    private record DefaultSnapshot(Map<Counter, Long> counters,
                                   Map<Gauge, Long> gauges,
                                   Map<String, String> constants) implements Snapshot {
    }
}

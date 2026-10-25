package ru.octomem.protocol;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import ru.octomem.engine.CacheCapacityException;
import ru.octomem.engine.CacheEngine;
import ru.octomem.engine.CounterResult;
import ru.octomem.engine.KeyVisitor;
import ru.octomem.engine.RetrievalSnapshot;
import ru.octomem.engine.StoreResult;
import ru.octomem.engine.StorageAdmission;
import ru.octomem.stats.Counter;
import ru.octomem.stats.Gauge;
import ru.octomem.stats.SlabClassStats;
import ru.octomem.stats.Snapshot;
import ru.octomem.stats.StatsRegistry;
import ru.octomem.stats.ClassStatsRegistry;
import ru.octomem.stats.ClassStatsRegistry.ClassSnapshot;
import ru.octomem.stats.ClassStatsRegistry.ClassState;
import ru.octomem.stats.ClassStatsRegistry.Event;
import ru.octomem.stats.SafeLogger;
import ru.octomem.storage.MemoryAllocator;
import ru.octomem.util.Clock;

/**
 * Shared synchronous command dispatcher; no networking and no retained command
 * arrays. Callers admit 8192 control bytes per connection before invocation and
 * allow at most one outstanding response on each connection. Retrieval uses one
 * admitted engine snapshot; only single-key mutations copy at most 250 key bytes.
 * Protocol syntax and unknown commands are handled by the decoder.
 *
 * <p>General statistics use a bounded detached snapshot. Class counters reserve
 * a detached primitive image from the engine snapshot pool, while linked gauges
 * and allocator geometry are sampled one class at a time, without network locks.
 * Native storage-header admission is consumed through an additive capability;
 * field availability alone is not a compatibility claim.
 * @since 1.0
 */
public final class CacheCommandDispatcher implements Function<Command, Response> {
    private static final String OOM = "out of memory storing object";
    private static final Counter[] COUNTERS = Counter.values();
    private static final Gauge[] GAUGES = Gauge.values();
    private static final String[] CONSTANTS = {"pid", "version", "pointer_size", "threads", "limit_maxbytes"};
    private final CacheEngine engine;
    private final StatsRegistry stats;
    private final MemoryAllocator allocator;
    private final Clock clock;
    private final String version;
    private final long startedNanos;
    private final SafeLogger logger;

    /**
     * Creates a dispatcher borrowing shared services for its lifetime.
     * @param engine started cache engine
     * @param stats shared statistics registry
     * @param allocator borrowed allocator with stable zero-based class IDs
     * @param clock shared clock
     * @param version safe printable ASCII version, 1..128 characters
     */
    public CacheCommandDispatcher(CacheEngine engine, StatsRegistry stats,
            MemoryAllocator allocator, Clock clock, String version) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.stats = Objects.requireNonNull(stats, "stats");
        logger = stats instanceof ClassStatsRegistry capability ? capability.logger() : new SafeLogger("info");
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(version, "version");
        if (version.isEmpty() || version.length() > 128) throw new IllegalArgumentException("Invalid version");
        for (int i = 0; i < version.length(); i++) {
            if (version.charAt(i) < 32 || version.charAt(i) > 126) throw new IllegalArgumentException("Invalid version");
        }
        this.version = version;
        startedNanos = clock.nanoTime();
    }

    /** Returns the shared protocol logging threshold.
     * @return current level, 0..2
     */
    public int verbosity() { return logger.verbosity(); }

    /** Sets the shared logging threshold, clamping to the reference range.
     * @param level requested verbosity
     */
    public void setVerbosity(int level) { logger.setVerbosity(level); }

    /**
     * Executes before applying noreply suppression; suppressed responses are
     * closed immediately. QUIT returns null: the transport closes that command
     * before calling the dispatcher, as required by the existing transport API.
     * @param command validated borrowed command
     * @return owned response, or null for suppression/QUIT
     */
    @Override public Response apply(Command command) {
        Objects.requireNonNull(command, "command");
        Response response;
        try {
            logger.log(SafeLogger.Event.COMMAND);
            response = execute(command);
        } catch (CacheCapacityException failure) {
            logger.log(SafeLogger.Event.CAPACITY);
            response = basic(ResponseKind.SERVER_ERROR, OOM);
        } catch (RuntimeException failure) {
            // Never include exception text, keys, payload, or peer addresses.
            logger.log(SafeLogger.Event.INTERNAL_FAILURE);
            response = basic(ResponseKind.SERVER_ERROR, "internal error");
        }
        if (command.noreply() && response != null) {
            response.close();
            return null;
        }
        return response;
    }

    private Response execute(Command command) throws CacheCapacityException {
        return switch (command.type()) {
            case GET, GETS, GAT, GATS -> {
                RetrievalSnapshot snapshot = switch (command.type()) {
                    case GAT, GATS -> engine.snapshotAndTouch(command.keys(), command.exptime());
                    default -> engine.snapshot(command.keys());
                };
                try {
                    yield new CacheResponse(ResponseKind.RETRIEVAL, snapshot,
                            command.type() == CommandType.GETS || command.type() == CommandType.GATS, 0, null, null);
                } catch (RuntimeException | Error failure) {
                    snapshot.close();
                    throw failure;
                }
            }
            case SET, ADD, REPLACE, APPEND, PREPEND, CAS, DELETE, INCR, DECR, TOUCH -> {
                SingleKey key = new SingleKey();
                command.keys().forEachKey(key);
                if (key.bytes == null) throw new IllegalArgumentException("Missing key");
                if(command instanceof AdmittedCommand admitted && admitted.admission()!=null) {
                    if(!(engine instanceof StorageAdmission prepared)) throw new IllegalStateException("Native admission engine unavailable");
                    yield store(prepared.storePrepared(admitted.admission(),StorageAdmission.Mode.valueOf(command.type().name()),
                            key.bytes,command.value(),command.flags(),command.exptime(),command.casId()));
                }
                yield switch (command.type()) {
                    case SET -> store(engine.set(key.bytes, command.value(), command.flags(), command.exptime()));
                    case ADD -> store(engine.add(key.bytes, command.value(), command.flags(), command.exptime()));
                    case REPLACE -> store(engine.replace(key.bytes, command.value(), command.flags(), command.exptime()));
                    case APPEND -> store(engine.append(key.bytes, command.value()));
                    case PREPEND -> store(engine.prepend(key.bytes, command.value()));
                    case CAS -> store(engine.cas(key.bytes, command.value(), command.flags(), command.exptime(), command.casId()));
                    case DELETE -> basic(engine.delete(key.bytes) ? ResponseKind.DELETED : ResponseKind.NOT_FOUND, null);
                    case TOUCH -> basic(engine.touch(key.bytes, command.exptime()) ? ResponseKind.TOUCHED : ResponseKind.NOT_FOUND, null);
                    case INCR -> counter(engine.increment(key.bytes, command.delta()));
                    case DECR -> counter(engine.decrement(key.bytes, command.delta()));
                    default -> throw new AssertionError();
                };
            }
            case FLUSH_ALL -> { engine.flushAll(command.exptime()); yield basic(ResponseKind.OK, null); }
            case STATS_RESET -> { stats.reset(); yield basic(ResponseKind.RESET, null); }
            case VERSION -> basic(ResponseKind.VERSION, version);
            case VERBOSITY -> { setVerbosity(command.level()); yield basic(ResponseKind.OK, null); }
            case STATS -> {
                stats.setGauge(Gauge.TIME, Math.max(0, clock.currentTimeSeconds()));
                stats.setGauge(Gauge.UPTIME, Math.max(0, (clock.nanoTime() - startedNanos) / 1_000_000_000L));
                yield new CacheResponse(ResponseKind.STATS, null, false, 0, null, new GeneralLines(stats.snapshot()));
            }
            case STATS_SLABS, STATS_ITEMS -> classResponse(command.type() == CommandType.STATS_ITEMS);
            case QUIT -> null;
        };
    }

    private Response classResponse(boolean items) {
        if (!(stats instanceof ClassStatsRegistry capability)) return basic(ResponseKind.SERVER_ERROR, "statistics unavailable");
        ClassSnapshot snapshot;
        try { snapshot = capability.captureClasses(); }
        catch (IllegalStateException unavailable) {
            logger.log(SafeLogger.Event.CAPACITY);
            return basic(ResponseKind.SERVER_ERROR, "statistics unavailable");
        }
        try {
            return new CacheResponse(ResponseKind.STATS, null, false, 0, null,
                    new ClassLines(allocator, snapshot, items, clock.currentTimeSeconds()));
        } catch (RuntimeException | Error failure) { snapshot.close(); throw failure; }
    }

    /** Returns owned stats for binary framing without materializing the full response. */
    public Response statistics(String argument) {
        if(argument==null || argument.isEmpty()) {
            stats.setGauge(Gauge.TIME,Math.max(0,clock.currentTimeSeconds()));
            stats.setGauge(Gauge.UPTIME,Math.max(0,(clock.nanoTime()-startedNanos)/1_000_000_000L));
            return new CacheResponse(ResponseKind.STATS,null,false,0,null,new GeneralLines(stats.snapshot()));
        }
        return switch(argument){case "items"->classResponse(true);case "slabs"->classResponse(false);
            case "reset"->{stats.reset();yield new CacheResponse(ResponseKind.STATS,null,false,0,null,List.of());}
            default->basic(ResponseKind.ERROR,null);};
    }

    private static Response basic(ResponseKind kind, String text) {
        return new CacheResponse(kind, null, false, 0, text, null);
    }

    private Response store(StoreResult result) {
        if (result == StoreResult.OUT_OF_MEMORY) logger.log(SafeLogger.Event.CAPACITY);
        return switch (result) {
            case STORED -> basic(ResponseKind.STORED, null);
            case NOT_STORED -> basic(ResponseKind.NOT_STORED, null);
            case EXISTS -> basic(ResponseKind.EXISTS, null);
            case NOT_FOUND -> basic(ResponseKind.NOT_FOUND, null);
            case TOO_LARGE -> basic(ResponseKind.SERVER_ERROR, "object too large for cache");
            case OUT_OF_MEMORY -> basic(ResponseKind.SERVER_ERROR, OOM);
            case INVALID -> basic(ResponseKind.CLIENT_ERROR, "bad command line format");
        };
    }

    private Response counter(CounterResult result) {
        if (result.status() == CounterResult.Status.OUT_OF_MEMORY) logger.log(SafeLogger.Event.CAPACITY);
        return switch (result.status()) {
            case VALUE -> new CacheResponse(ResponseKind.NUMBER, null, false, result.value(), null, null);
            case NOT_FOUND -> basic(ResponseKind.NOT_FOUND, null);
            case NON_NUMERIC -> basic(ResponseKind.CLIENT_ERROR, "cannot increment or decrement non-numeric value");
            case OUT_OF_MEMORY -> basic(ResponseKind.SERVER_ERROR, "out of memory");
        };
    }

    private static final class SingleKey implements KeyVisitor {
        private byte[] bytes;
        @Override public void accept(byte[] backing, int offset, int length) {
            if (bytes != null || length < 1 || length > 250) throw new IllegalArgumentException("Invalid key sequence");
            Objects.checkFromIndexSize(offset, length, backing.length);
            bytes = Arrays.copyOfRange(backing, offset, offset + length);
        }
    }

    private static final class GeneralLines extends AbstractList<String> {
        private final Snapshot snapshot;
        GeneralLines(Snapshot snapshot) { this.snapshot = snapshot; }
        @Override public int size() { return CONSTANTS.length + COUNTERS.length + GAUGES.length; }
        @Override public String get(int index) {
            Objects.checkIndex(index, size());
            if (index < CONSTANTS.length) {
                String key = CONSTANTS[index];
                return "STAT " + key + " " + snapshot.constants().get(key);
            }
            index -= CONSTANTS.length;
            if (index < COUNTERS.length) {
                Counter counter = COUNTERS[index];
                return "STAT " + counter.wireName() + " " + snapshot.counters().get(counter);
            }
            Gauge gauge = GAUGES[index - COUNTERS.length];
            return "STAT " + gauge.wireName() + " " + snapshot.gauges().get(gauge);
        }
    }

    static final class ClassLines extends AbstractList<String> implements AutoCloseable {
        private static final String[] ITEM_FIELDS = {"number", "age", "evicted", "evicted_nonzero",
            "evicted_time", "outofmemory", "tailrepairs", "reclaimed", "expired_unfetched", "evicted_unfetched", "crawler_reclaimed"};
        private static final String[] SLAB_FIELDS = {"chunk_size", "chunks_per_page", "total_pages", "total_chunks",
            "used_chunks", "free_chunks", "free_chunks_end", "get_hits", "cmd_set", "delete_hits", "incr_hits",
            "decr_hits", "cas_hits", "cas_badval", "mem_requested"};
        private final MemoryAllocator allocator;
        private final ClassSnapshot snapshot;
        private final boolean items;
        private final long now;
        private final long selectedLow;
        private final long selectedHigh;
        private final int count;
        private SlabClassStats current;
        private ClassState state;
        private int currentId = -1;
        ClassLines(MemoryAllocator allocator, ClassSnapshot snapshot, boolean items, long now) {
            this.allocator = allocator;
            this.snapshot = snapshot; this.items = items; this.now = now;
            long low = 0, high = 0;
            int emitted = 0;
            for (int id = 0; id < snapshot.classCount(); id++) {
                boolean present = items ? snapshot.state(id).number() != 0 : allocator.classStats(id).totalPages() != 0;
                if (present) {
                    if (id < 64) low |= 1L << id; else high |= 1L << (id - 64);
                    emitted++;
                }
            }
            selectedLow = low; selectedHigh = high;
            count = emitted * (items ? ITEM_FIELDS.length : SLAB_FIELDS.length);
        }
        @Override public int size() { return count; }
        @Override public String get(int index) {
            Objects.checkIndex(index, count);
            String[] fields = items ? ITEM_FIELDS : SLAB_FIELDS;
            int ordinal = index / fields.length;
            int id = 0;
            for (; id < snapshot.classCount(); id++) {
                long selected = id < 64 ? selectedLow : selectedHigh;
                if ((selected & (1L << (id & 63))) != 0 && ordinal-- == 0) break;
            }
            if (currentId != id) {
                current = items ? null : allocator.classStats(id);
                state = snapshot.state(id); currentId = id;
            }
            int field = index % fields.length;
            long value = items ? switch (field) {
                case 0 -> state.number();
                case 1 -> state.number() == 0 || now <= state.oldestActivityTime() ? 0
                        : now - state.oldestActivityTime() < 0 ? Long.MAX_VALUE : now - state.oldestActivityTime();
                case 2 -> snapshot.event(id, Event.EVICTED);
                case 3 -> snapshot.event(id, Event.EVICTED_NONZERO);
                case 4 -> snapshot.event(id, Event.EVICTED_TIME);
                case 5 -> snapshot.event(id, Event.OUTOFMEMORY);
                case 6 -> 0; // No destructive stuck-refcount repair exists.
                case 7 -> snapshot.event(id, Event.RECLAIMED);
                case 8 -> snapshot.event(id, Event.EXPIRED_UNFETCHED);
                case 9 -> snapshot.event(id, Event.EVICTED_UNFETCHED);
                default -> snapshot.event(id, Event.CRAWLER_RECLAIMED);
            } : switch (field) {
                case 0 -> current.chunkSize();
                case 1 -> current.totalPages() == 0 ? 0 : current.totalChunks() / current.totalPages();
                case 2 -> current.totalPages();
                case 3 -> current.totalChunks();
                case 4 -> current.usedChunks();
                case 5 -> current.freeChunks();
                case 6 -> 0; // Pinned reference protocol compatibility field.
                case 7 -> snapshot.counter(id, Counter.GET_HITS);
                case 8 -> snapshot.counter(id, Counter.CMD_SET);
                case 9 -> snapshot.counter(id, Counter.DELETE_HITS);
                case 10 -> snapshot.counter(id, Counter.INCR_HITS);
                case 11 -> snapshot.counter(id, Counter.DECR_HITS);
                case 12 -> snapshot.counter(id, Counter.CAS_HITS);
                case 13 -> snapshot.counter(id, Counter.CAS_BADVAL);
                default -> state.requestedBytes();
            };
            return "STAT " + (items ? "items:" : "") + (id + 1) + ":" + fields[field] + " " + value;
        }
        @Override public void close() { snapshot.close(); }
    }
}

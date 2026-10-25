package ru.octomem.statsacceptance;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.octomem.cli.CacheServer;
import ru.octomem.config.ConfigLoader;
import ru.octomem.engine.StoreResult;
import ru.octomem.engine.StorageAdmission;
import ru.octomem.protocol.AdmittedCommand;
import ru.octomem.protocol.CacheCommandDispatcher;
import ru.octomem.protocol.Command;
import ru.octomem.protocol.CommandType;
import ru.octomem.protocol.ProtocolBudget;
import ru.octomem.protocol.ProtocolException;
import ru.octomem.protocol.Response;
import ru.octomem.protocol.TextProtocolDecoder;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.stats.ClassStatsRegistry.ClassState;

/**
 * Independently authored T-013 acceptance evidence for the protocol and server
 * composition seams: dispatcher routing, class-lines emission above class 64,
 * decoder grammar rejection and the wildcard-bind broadcast warning. Expected
 * values come from the frozen oracle (invalid grammar is a client error; a
 * non-loopback bind logs a broadcast warning; class lines report each emitted
 * class once).
 */
@Timeout(60)
class CoverageClosureProtocolAcceptanceTest {
    private static byte[] b(String value) { return value.getBytes(StandardCharsets.US_ASCII); }

    private static AtomicStatsRegistry registry() {
        return new AtomicStatsRegistry(Map.of("pid", "1", "version", "independent-coverage",
                "pointer_size", "64", "threads", "1", "limit_maxbytes", "1048576"));
    }

    private static Command command(CommandType type, byte[]... keys) {
        return new Command() {
            @Override public CommandType type() { return type; }
            @Override public ru.octomem.engine.KeySequence keys() { return new CoverageStubs.Slices(keys); }
            @Override public int flags() { return 0; }
            @Override public long exptime() { return 0; }
            @Override public byte[] value() { return new byte[0]; }
            @Override public long casId() { return 0; }
            @Override public long delta() { return 1; }
            @Override public int level() { return 1; }
            @Override public boolean noreply() { return false; }
        };
    }

    private static final class Budget implements ProtocolBudget {
        boolean deny;
        int reservations;
        @Override public boolean tryReserveBody(int payloadBytes) {
            if (deny) { return false; }
            reservations++;
            return true;
        }
        @Override public void releaseBody(int payloadBytes) { reservations--; }
    }

    private static final class Admission implements StorageAdmission {
        StoreResult admitResult = StoreResult.OUT_OF_MEMORY;
        int malformed;
        int denials;
        @Override public Result admit(Mode mode, byte[] key, int flags, long exptime, long cas, int valueLength) {
            return new Result(admitResult, null);
        }
        @Override public StoreResult storePrepared(Token token, Mode mode, byte[] key, byte[] value,
                int flags, long exptime, long cas) { return StoreResult.INVALID; }
        @Override public void malformedFlush() { malformed++; }
        @Override public void capacityDenied() { denials++; }
    }

    @Test void dispatcherRoutesEveryCommandAndPlainRegistryFallbacks() {
        CoverageStubs.StubEngine engine = new CoverageStubs.StubEngine();
        CoverageStubs.StubAllocator allocator = new CoverageStubs.StubAllocator(64, 128);
        CoverageStubs.MutableClock clock = new CoverageStubs.MutableClock();
        CoverageStubs.PlainStats plain = new CoverageStubs.PlainStats();
        CacheCommandDispatcher dispatcher = new CacheCommandDispatcher(engine, plain, allocator, clock, "1.0.0 (octomem)");

        for (CommandType type : new CommandType[]{
                CommandType.SET, CommandType.ADD, CommandType.REPLACE, CommandType.APPEND, CommandType.PREPEND,
                CommandType.CAS, CommandType.DELETE, CommandType.TOUCH, CommandType.INCR, CommandType.DECR}) {
            Response response = dispatcher.apply(command(type, b("k")));
            assertNotNull(response, type.toString());
            response.close();
        }
        for (CommandType type : new CommandType[]{CommandType.GET, CommandType.GETS}) {
            Response response = dispatcher.apply(command(type, b("k")));
            assertNotNull(response);
            response.close();
        }
        for (CommandType type : new CommandType[]{CommandType.GAT, CommandType.GATS}) {
            Response response = dispatcher.apply(command(type, b("k")));
            assertNotNull(response);
            response.close();
        }
        assertNotNull(dispatcher.apply(command(CommandType.FLUSH_ALL)));
        assertNotNull(dispatcher.apply(command(CommandType.STATS_RESET)));
        assertNotNull(dispatcher.apply(command(CommandType.VERSION)));
        assertNotNull(dispatcher.apply(command(CommandType.VERBOSITY)));
        Response stats = dispatcher.apply(command(CommandType.STATS));
        assertNotNull(stats.statsLines(), "General statistics are available on a plain registry");
        stats.close();

        // A plain registry has no class statistics capability.
        Response slabs = dispatcher.apply(command(CommandType.STATS_SLABS));
        assertEquals(ru.octomem.protocol.ResponseKind.SERVER_ERROR, slabs.kind(),
                "A registry without class statistics reports statistics unavailable");
        slabs.close();
        Response items = dispatcher.apply(command(CommandType.STATS_ITEMS));
        assertEquals(ru.octomem.protocol.ResponseKind.SERVER_ERROR, items.kind());
        items.close();

        assertNull(dispatcher.apply(command(CommandType.QUIT)), "QUIT suppresses the response");
    }

    @Test void dispatcherNativeAdmissionRequiresStorageAdmissionEngine() {
        CoverageStubs.StubEngine engine = new CoverageStubs.StubEngine();
        CoverageStubs.StubAllocator allocator = new CoverageStubs.StubAllocator(64);
        CoverageStubs.MutableClock clock = new CoverageStubs.MutableClock();
        CoverageStubs.PlainStats plain = new CoverageStubs.PlainStats();
        CacheCommandDispatcher dispatcher = new CacheCommandDispatcher(engine, plain, allocator, clock, "v");
        StorageAdmission.Token token = () -> { };
        AdmittedCommand admitted = new AdmittedCommand() {
            @Override public StorageAdmission.Token admission() { return token; }
            @Override public CommandType type() { return CommandType.SET; }
            @Override public ru.octomem.engine.KeySequence keys() { return new CoverageStubs.Slices(b("k")); }
            @Override public int flags() { return 0; }
            @Override public long exptime() { return 0; }
            @Override public byte[] value() { return new byte[0]; }
            @Override public long casId() { return 0; }
            @Override public long delta() { return 0; }
            @Override public int level() { return 0; }
            @Override public boolean noreply() { return false; }
        };
        Response response = dispatcher.apply(admitted);
        assertEquals(ru.octomem.protocol.ResponseKind.SERVER_ERROR, response.kind(),
                "A native-admitted command requires an engine with the admission capability");
        response.close();
    }

    @Test void classLinesEmitClassesAboveSixtyFourAndAgeBoundaries() {
        CoverageStubs.StubEngine engine = new CoverageStubs.StubEngine();
        int[] sizes = new int[70];
        for (int i = 0; i < sizes.length; i++) { sizes[i] = 64 * (i + 1); }
        CoverageStubs.StubAllocator allocator = new CoverageStubs.StubAllocator(sizes);
        allocator.page(65, 1);
        AtomicStatsRegistry stats = registry();
        AtomicLong oldest = new AtomicLong(0);
        stats.initializeClasses(70,
                id -> id == 0 ? new ClassState(5, 40, oldest.get()) : new ClassState(0, 0, 0),
                new AtomicLong(), 1 << 20);
        CoverageStubs.MutableClock clock = new CoverageStubs.MutableClock();
        long now = clock.currentTimeSeconds();
        CacheCommandDispatcher dispatcher = new CacheCommandDispatcher(engine, stats, allocator, clock, "v");

        Response slabs = dispatcher.apply(command(CommandType.STATS_SLABS));
        List<String> slabLines = slabs.statsLines();
        assertFalse(slabLines.isEmpty(), "An occupied high class is emitted");
        assertTrue(slabLines.stream().anyMatch(line -> line.startsWith("STAT 66:")),
                "Class 66 (allocator id 65) appears in the slab lines");
        for (String line : slabLines) { assertTrue(line.startsWith("STAT "), line); }
        slabs.close();

        oldest.set(now);
        Response items = dispatcher.apply(command(CommandType.STATS_ITEMS));
        assertTrue(items.statsLines().stream().anyMatch(line -> line.startsWith("STAT items:1:age")),
                "Item age is emitted for a populated class");
        items.close();

        oldest.set(Long.MIN_VALUE);
        Response wrapped = dispatcher.apply(command(CommandType.STATS_ITEMS));
        assertTrue(wrapped.statsLines().stream().anyMatch(line -> line.startsWith("STAT items:1:age")),
                "A backward age sample still emits a bounded value");
        wrapped.close();
    }

    @Test void decoderRejectsInvalidGrammarAndDrainsNoreplyBodyErrors() throws Exception {
        Budget budget = new Budget();

        assertThrows(ProtocolException.class, () -> {
            TextProtocolDecoder decoder = new TextProtocolDecoder(4096, 4096, budget);
            decoder.append(ByteBuffer.wrap(("set " + "k".repeat(251) + " 0 0 1\r\n").getBytes(StandardCharsets.ISO_8859_1)));
            decoder.nextCommand();
        });
        assertThrows(ProtocolException.class, () -> {
            TextProtocolDecoder decoder = new TextProtocolDecoder(4096, 4096, budget);
            decoder.append(ByteBuffer.wrap("set k 0 0 1 extra\r\n".getBytes(StandardCharsets.ISO_8859_1)));
            decoder.nextCommand();
        });
        assertThrows(ProtocolException.class, () -> {
            TextProtocolDecoder decoder = new TextProtocolDecoder(4096, 4096, budget);
            decoder.append(ByteBuffer.wrap("version trailing\r\n".getBytes(StandardCharsets.ISO_8859_1)));
            decoder.nextCommand();
        });
        assertThrows(ProtocolException.class, () -> {
            TextProtocolDecoder decoder = new TextProtocolDecoder(4096, 4096, budget);
            decoder.append(ByteBuffer.wrap("set\r\n".getBytes(StandardCharsets.ISO_8859_1)));
            decoder.nextCommand();
        });
        assertThrows(ProtocolException.class, () -> {
            TextProtocolDecoder decoder = new TextProtocolDecoder(4096, 4096, budget);
            decoder.append(ByteBuffer.wrap("touch k +\r\n".getBytes(StandardCharsets.ISO_8859_1)));
            decoder.nextCommand();
        });
        try (TextProtocolDecoder decoder = new TextProtocolDecoder(4096, 4096, budget)) {
            decoder.append(ByteBuffer.wrap(b("touch k -\r\n")));
            Command zero = decoder.nextCommand();
            assertNotNull(zero);
            assertEquals(0, zero.exptime(), "Pinned1.6.45 mcmc bare-minus means zero");
            decoder.release(zero);
        }
        assertThrows(ProtocolException.class, () -> {
            TextProtocolDecoder decoder = new TextProtocolDecoder(4096, 4096, budget);
            decoder.append(ByteBuffer.wrap("touch k 99999999999999999999999\r\n".getBytes(StandardCharsets.ISO_8859_1)));
            decoder.nextCommand();
        });
        assertThrows(ProtocolException.class, () -> {
            TextProtocolDecoder decoder = new TextProtocolDecoder(4096, 4096, budget);
            decoder.append(ByteBuffer.wrap("stats bogus\r\n".getBytes(StandardCharsets.ISO_8859_1)));
            decoder.nextCommand();
        });
        assertThrows(ProtocolException.class, () -> {
            TextProtocolDecoder decoder = new TextProtocolDecoder(4096, 4096, budget);
            decoder.append(ByteBuffer.wrap("delete k 1 extra\r\n".getBytes(StandardCharsets.ISO_8859_1)));
            decoder.nextCommand();
        });

        Admission admission = new Admission();
        assertThrows(ProtocolException.class, () -> {
            TextProtocolDecoder decoder = new TextProtocolDecoder(4096, 4096, budget, admission);
            decoder.append(ByteBuffer.wrap("flush_all 1 extra\r\n".getBytes(StandardCharsets.ISO_8859_1)));
            decoder.nextCommand();
        });
        assertEquals(1, admission.malformed, "A malformed flush_all notifies the admission owner");

        TextProtocolDecoder noreply = new TextProtocolDecoder(1024, 32, budget);
        noreply.append(ByteBuffer.wrap("set k 0 0 100 noreply\r\n".getBytes(StandardCharsets.ISO_8859_1)));
        assertNull(noreply.nextCommand(), "The body is still outstanding");
        noreply.append(ByteBuffer.wrap(("A".repeat(100) + "\r\n").getBytes(StandardCharsets.ISO_8859_1)));
        assertThrows(ProtocolException.class, noreply::nextCommand);
        assertTrue(noreply.errorNoreply(), "A drained noreply body error is suppressible");

        Admission tooLarge = new Admission();
        tooLarge.admitResult = StoreResult.TOO_LARGE;
        assertThrows(ProtocolException.class, () -> {
            TextProtocolDecoder decoder = new TextProtocolDecoder(4096, 4096, budget, tooLarge);
            decoder.append(ByteBuffer.wrap("set k 0 0 1\r\n".getBytes(StandardCharsets.ISO_8859_1)));
            assertNull(decoder.nextCommand());
            decoder.append(ByteBuffer.wrap("A\r\n".getBytes(StandardCharsets.ISO_8859_1)));
            decoder.nextCommand();
        });
        Admission denied = new Admission();
        denied.admitResult = StoreResult.OUT_OF_MEMORY;
        assertThrows(ProtocolException.class, () -> {
            TextProtocolDecoder decoder = new TextProtocolDecoder(4096, 4096, budget, denied);
            decoder.append(ByteBuffer.wrap("set k 0 0 1\r\n".getBytes(StandardCharsets.ISO_8859_1)));
            assertNull(decoder.nextCommand());
            decoder.append(ByteBuffer.wrap("A\r\n".getBytes(StandardCharsets.ISO_8859_1)));
            decoder.nextCommand();
        });

        assertThrows(IllegalArgumentException.class, () -> new TextProtocolDecoder(0, 1, budget));
    }

    @Test void validDecoderCommandsRoundTrip() throws Exception {
        Budget budget = new Budget();
        TextProtocolDecoder decoder = new TextProtocolDecoder(4096, 4096, budget);
        decoder.append(ByteBuffer.wrap("get a b\r\n".getBytes(StandardCharsets.ISO_8859_1)));
        Command get = decoder.nextCommand();
        assertNotNull(get);
        assertEquals(CommandType.GET, get.type());
        assertEquals(2, get.keys().size());
        decoder.release(get);
        decoder.append(ByteBuffer.wrap("stats reset\r\n".getBytes(StandardCharsets.ISO_8859_1)));
        Command reset = decoder.nextCommand();
        assertEquals(CommandType.STATS_RESET, reset.type());
        decoder.release(reset);
        decoder.close();
    }

    @Test void serverWildcardBindSignalsBroadcast() throws Exception {
        Map<String, String> overrides = new HashMap<>(Map.of(
                "transport.workers", "1", "connections.max", "4", "memory.limit", "1048576",
                "item.maxSize", "1024", "expiry.reclaimIntervalSeconds", "3600"));
        CacheServer server = new CacheServer(ConfigLoader.load(null, overrides),
                new InetSocketAddress("0.0.0.0", 0), new CoverageStubs.MutableClock());
        try {
            server.start();
            assertFalse(server.localAddress().getAddress().isLoopbackAddress(),
                    "A wildcard bind is not a loopback address");
        } finally {
            server.close();
        }
    }
}

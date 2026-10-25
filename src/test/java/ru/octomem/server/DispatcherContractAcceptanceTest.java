package ru.octomem.server;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.octomem.engine.*;
import ru.octomem.protocol.*;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.storage.*;
import ru.octomem.util.SystemClock;

/** Direct public command-contract checks still use the real engine and FFM storage. */
class DispatcherContractAcceptanceTest {
    private static final class Core implements AutoCloseable {
        final FfmSlabAllocator allocator = new FfmSlabAllocator(4096, 4096, 128);
        final AtomicStatsRegistry stats = new AtomicStatsRegistry(Map.of("pid", "1", "version", "test", "pointer_size", "64", "threads", "1", "limit_maxbytes", "4096"));
        final DefaultCacheEngine engine = new DefaultCacheEngine(allocator,
                new SegmentedItemTable(1, 2, 65536), new SystemClock(), stats,
                new DefaultCacheEngine.Settings(4096, 128, "noeviction", 3600));
        final CacheCommandDispatcher dispatcher = new CacheCommandDispatcher(engine, stats, allocator, new SystemClock(), "1.0.0 (octomem)");
        Core() { engine.start(); }
        @Override public void close() { engine.stop(); allocator.close(); }
    }

    private record Input(CommandType type, KeySequence keys, byte[] value, boolean noreply) implements Command {
        public int flags() { return 0; }
        public long exptime() { return 0; }
        public long casId() { return 0; }
        public long delta() { return 1; }
        public int level() { return 2; }
    }

    private static KeySequence keys(String... values) {
        return new KeySequence() {
            public int size() { return values.length; }
            public void forEachKey(KeyVisitor visitor) {
                for (String value : values) {
                    byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
                    visitor.accept(bytes, 0, bytes.length);
                }
            }
        };
    }

    private static Input input(CommandType type, String key, int length) {
        return new Input(type, keys(key), new byte[length], false);
    }

    @Test void versionValidationAndClosedResponseAccessAreExplicit() {
        try (Core core = new Core()) {
            for (String version : new String[]{"", "x".repeat(129), "bad\n", "bad\u007f"}) {
                assertThrows(IllegalArgumentException.class, () -> new CacheCommandDispatcher(core.engine,
                        core.stats, core.allocator, new SystemClock(), version));
            }
            assertThrows(NullPointerException.class, () -> core.dispatcher.apply(null));
            assertNull(core.dispatcher.apply(new Input(CommandType.QUIT, keys(), null, true)));
            Response response = core.dispatcher.apply(new Input(CommandType.VERSION, keys(), null, false));
            assertEquals("1.0.0 (octomem)", response.text());
            response.close(); response.close();
            assertAll(() -> assertThrows(IllegalStateException.class, response::kind),
                    () -> assertThrows(IllegalStateException.class, response::snapshot),
                    () -> assertThrows(IllegalStateException.class, response::includeCas),
                    () -> assertThrows(IllegalStateException.class, response::number),
                    () -> assertThrows(IllegalStateException.class, response::text),
                    () -> assertThrows(IllegalStateException.class, response::statsLines));
        }
    }

    @Test void invalidBorrowedKeySequencesFailSafelyWithoutLeakingDetails() {
        try (Core core = new Core()) {
            for (KeySequence sequence : new KeySequence[]{keys(), keys("a", "b"), keys(""), keys("x".repeat(251)),
                    new KeySequence() {
                        public int size() { return 1; }
                        public void forEachKey(KeyVisitor visitor) { visitor.accept(new byte[1], 2, 1); }
                    }}) {
                Response response = core.dispatcher.apply(new Input(CommandType.SET, sequence, new byte[0], false));
                try {
                    assertEquals(ResponseKind.SERVER_ERROR, response.kind());
                    assertEquals("internal error", response.text());
                } finally { response.close(); }
            }
            Response response = core.dispatcher.apply(input(CommandType.SET, "bad\u0001key", 1));
            try {
                assertEquals(ResponseKind.CLIENT_ERROR, response.kind());
                assertEquals("bad command line format", response.text());
            } finally { response.close(); }
        }
    }

    @Test void realEngineTooLargeAndOutOfMemoryMapToExactErrors() {
        try (Core core = new Core()) {
            Response oversized = core.dispatcher.apply(input(CommandType.SET, "large", 129));
            try {
                assertEquals(ResponseKind.SERVER_ERROR, oversized.kind());
                assertEquals("object too large for cache", oversized.text());
            } finally { oversized.close(); }
            int stored = 0;
            for (int i = 0; i < 100; i++) {
                Response response = core.dispatcher.apply(input(CommandType.SET, "k" + i, 70));
                try {
                    if (response.kind() == ResponseKind.STORED) { stored++; continue; }
                    assertEquals(ResponseKind.SERVER_ERROR, response.kind());
                    assertEquals("out of memory storing object", response.text());
                    break;
                } finally { response.close(); }
            }
            assertTrue(stored > 0 && stored < 100);
            assertNull(core.dispatcher.apply(new Input(CommandType.SET, keys("full"), new byte[70], true)));
        }
    }

    @Test void realCounterCapacityFailurePreservesPinnedNumericValue() {
        try (Core core = new Core()) {
            int stored = 0;
            for (int i = 0; i < 200; i++) {
                Response response = core.dispatcher.apply(new Input(CommandType.SET, keys("n" + i), new byte[]{'1'}, false));
                try {
                    if (response.kind() != ResponseKind.STORED) break;
                    stored++;
                } finally { response.close(); }
            }
            assertTrue(stored > 0 && stored < 200);
            try (GetResult pin = core.engine.get("n0".getBytes(StandardCharsets.US_ASCII))) {
                assertTrue(pin.found());
                Response response = core.dispatcher.apply(input(CommandType.INCR, "n0", 0));
                try {
                    assertEquals(ResponseKind.SERVER_ERROR, response.kind());
                    assertEquals("out of memory", response.text());
                } finally { response.close(); }
                assertEquals(1, pin.item().valueLength());
            }
        }
    }
}

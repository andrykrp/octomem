package ru.octomem.statsacceptance;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Literal T013 source-oracle cases against the real FFM server, never implementation-derived answers. */
@Timeout(30)
class StatsWireAcceptanceTest extends StatsWireFixture {
    @Test void a01MandatoryManifestInventoryAndRealClassFields() throws Exception {
        var manifest = JsonParser.parseString(Files.readString(Path.of("compatibility-manifest.json"))).getAsJsonObject();
        for (String name : new String[]{"statsFields", "statsItemsFields", "statsSlabsFields"}) {
            Set<String> fields = new HashSet<>();
            manifest.getAsJsonArray(name).forEach(v -> fields.add(v.getAsString().replace("items:N:", "").replace("N:", "")));
            assertEquals(name.equals("statsFields") ? GENERAL : name.equals("statsItemsFields") ? ITEMS : SLABS, fields);
        }
        start();
        assertTrue(wire.stats("stats items").isEmpty());
        wire.expect("set a 0 0 1\r\nx\r\nset b 7 0 100\r\n" + "y".repeat(100) + "\r\n", "STORED\r\nSTORED\r\n");
        var general = wire.stats("stats");
        assertTrue(general.keySet().containsAll(GENERAL));
        GENERAL.stream().filter(f -> !f.equals("version")).forEach(f -> number(general, f));
        var items = classes(wire.stats("stats items"), true);
        var slabs = classes(wire.stats("stats slabs"), false);
        assertEquals(2, items.size());
        assertEquals(2, slabs.size());
        items.values().forEach(v -> { assertTrue(v.keySet().containsAll(ITEMS)); ITEMS.forEach(f -> number(v, f)); });
        slabs.values().forEach(v -> { assertTrue(v.keySet().containsAll(SLABS)); SLABS.forEach(f -> number(v, f)); });
        count(general, "bytes", 176);
        count(general, "curr_items", 2);
        count(general, "total_items", 2);
        count(slabs.get(classFor(slabs, 40)), "mem_requested", 40);
        count(slabs.get(classFor(slabs, 40)), "chunk_size", 64);
        count(slabs.get(classFor(slabs, 136)), "mem_requested", 136);
        count(slabs.get(classFor(slabs, 136)), "chunk_size", 136);
    }

    @Test void a02ProcessIdentityClockAndResetDoNotRestartTime() throws Exception {
        start();
        var first = wire.stats("stats");
        count(first, "pid", ProcessHandle.current().pid());
        assertEquals("1.0.0 (octomem)", first.get("version"));
        count(first, "pointer_size", ValueLayout.ADDRESS.byteSize() * 8);
        count(first, "threads", 2);
        count(first, "limit_maxbytes", 8_388_608);
        count(first, "time", 1_700_000_000L);
        clock.advance(11);
        wire.expect("stats reset\r\n", "RESET\r\n");
        var later = wire.stats("stats");
        count(later, "time", 1_700_000_011L);
        assertEquals(11, number(later, "uptime") - number(first, "uptime"));
    }

    @Test void a04DuplicatesCountOccurrencesWhileWireDeduplicates() throws Exception {
        start();
        wire.expect("set a 0 0 1\r\nx\r\nget a a z z\r\n", "STORED\r\nVALUE a 0 1\r\nx\r\nEND\r\n");
        var values = wire.stats("stats");
        count(values, "cmd_get", 4);
        count(values, "get_hits", 2);
        count(values, "get_misses", 2);
        var cls = classes(wire.stats("stats slabs"), false).values().iterator().next();
        count(cls, "get_hits", 2);
        count(cls, "cmd_set", 1);
    }

    @Test void a03ReleasedConnectionStructuresAreNotLifetimeConnections() throws Exception {
        start();
        wire.expect("version\r\n", "VERSION 1.0.0 (octomem)\r\n");
        var initial = wire.stats("stats");
        count(initial, "curr_connections", 1);
        count(initial, "connection_structures", 1);
        Wire extra = connect();
        extra.expect("version\r\n", "VERSION 1.0.0 (octomem)\r\n");
        var opened = wire.stats("stats");
        count(opened, "curr_connections", 2);
        count(opened, "connection_structures", 2);
        assertEquals(1, number(opened, "total_connections") - number(initial, "total_connections"));
        extra.close();
        Map<String, String> released;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            released = wire.stats("stats");
            if (number(released, "curr_connections") == 1 && number(released, "connection_structures") == 1) break;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        count(released, "curr_connections", 1);
        count(released, "connection_structures", 1);
        wire.expect("stats reset\r\n", "RESET\r\n");
        var reset = wire.stats("stats");
        count(reset, "total_connections", 0);
        count(reset, "curr_connections", 1);
        count(reset, "connection_structures", 1);
    }

    @Test void a05GatDuplicatesAreTouchesAndDoNotPublishLogicalItems() throws Exception {
        start();
        wire.expect("set a 0 0 1\r\nx\r\ngat 60 a a z z\r\ntouch a 120\r\ntouch z 120\r\n",
                "STORED\r\nVALUE a 0 1\r\nx\r\nEND\r\nTOUCHED\r\nNOT_FOUND\r\n");
        var values = wire.stats("stats");
        count(values, "cmd_touch", 6);
        count(values, "touch_hits", 3);
        count(values, "touch_misses", 3);
        count(values, "cmd_get", 0);
        count(values, "get_hits", 0);
        count(values, "get_misses", 0);
        count(values, "total_items", 1);
        count(values, "bytes", 40);
        count(values, "cmd_set", 1);
        wire.send("gats 60 a a z z\r\n");
        assertTrue(wire.line().matches("VALUE a 0 1 [0-9]+\r\n"));
        assertEquals("x\r\n", wire.line());
        assertEquals("END\r\n", wire.line());
        var after = wire.stats("stats");
        count(after, "cmd_touch", 10);
        count(after, "touch_hits", 5);
        count(after, "touch_misses", 5);
        count(after, "total_items", 1);
    }

    @Test void a06ConditionalStorageFailuresStillCountAdmittedAttempts() throws Exception {
        start();
        wire.expect("set a 0 0 1\r\nx\r\nadd a 0 0 1\r\ny\r\nreplace z 0 0 1\r\ny\r\n"
                + "append z 0 0 1\r\ny\r\nprepend z 0 0 1\r\ny\r\ncas z 0 0 1 9\r\ny\r\n"
                + "cas a 0 0 1 0\r\ny\r\n", "STORED\r\nNOT_STORED\r\nNOT_STORED\r\nNOT_STORED\r\nNOT_STORED\r\nNOT_FOUND\r\nEXISTS\r\n");
        var values = wire.stats("stats");
        count(values, "cmd_set", 7);
        count(values, "total_items", 1);
        count(values, "cas_misses", 1);
        count(values, "cas_badval", 1);
        count(values, "get_hits", 0);
        count(values, "bytes", 40);
    }

    @Test void a06NativeAdmissionCountsBeforeBodyAndDisconnectDoesNotPublish() throws Exception {
        start();
        Wire partial = connect();
        partial.send("set p 0 0 100\r\n");
        Map<String, String> observed = Map.of();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            observed = wire.stats("stats");
            if (number(observed, "cmd_set") == 1) break;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        count(observed, "cmd_set", 1);
        count(observed, "curr_items", 0);
        count(observed, "total_items", 0);
        assertTrue(server.resourceUsage().nativeUsedBytes() >= 136, "Q04 requires actual native admission, not ingress-only accounting");
        partial.close();
        wire.expect("version\r\n", "VERSION 1.0.0 (octomem)\r\n");
        count(wire.stats("stats"), "cmd_set", 1);
        wire.expect("get p\r\n", "END\r\n");
    }

    @Test void a06BadBodyCountsButOversizeAndInvalidHeaderDoNot() throws Exception {
        start("item.maxSize", "128");
        wire.expect("set a 0 0 1\r\nxZZ", "CLIENT_ERROR bad data chunk\r\n");
        // Malformed bodies may close the transport; use a fresh control connection.
        wire = connect();
        wire.expect("set b 0 0 129\r\n" + "x".repeat(129) + "\r\n", "SERVER_ERROR object too large for cache\r\n");
        wire.expect("set c nope 0 1\r\n", "CLIENT_ERROR bad command line format\r\n");
        wire = connect();
        var values = wire.stats("stats");
        count(values, "cmd_set", 1);
        count(values, "total_items", 0);
        count(values, "curr_items", 0);
    }

    @Test void a07CasOldClassAndAppendFragmentClassAreNotFinalClass() throws Exception {
        start();
        wire.expect("set a 0 0 1\r\nx\r\n", "STORED\r\n");
        String token = wire.cas("a", "x");
        wire.expect("cas a 0 0 100 " + token + "\r\n" + "y".repeat(100) + "\r\n", "STORED\r\n");
        wire.expect("cas a 0 0 1 0\r\nz\r\nappend a 0 0 1\r\nz\r\n", "EXISTS\r\nSTORED\r\n");
        var slabs = classes(wire.stats("stats slabs"), false);
        int small = classFor(slabs, 40), large = classFor(slabs, 136);
        assertNotEquals(small, large);
        count(slabs.get(small), "cmd_set", 3);
        count(slabs.get(large), "cmd_set", 1);
        count(slabs.get(small), "cas_hits", 1);
        count(slabs.get(large), "cas_badval", 1);
        var values = wire.stats("stats");
        count(values, "cmd_set", 4);
        count(values, "total_items", 3);
        count(values, "bytes", 136);
    }

    @Test void a08ArithmeticShrinkPreservesCapacityGrowthPublishesOnce() throws Exception {
        start();
        wire.expect("set n 0 0 8\r\n10000000\r\ndecr n 10000000\r\n", "STORED\r\n0\r\n");
        var shrink = wire.stats("stats");
        count(shrink, "total_items", 1);
        count(shrink, "bytes", 48);
        count(shrink, "decr_hits", 1);
        wire.expect("incr n 100000000\r\n", "100000000\r\n");
        var growth = wire.stats("stats");
        count(growth, "total_items", 2);
        count(growth, "cmd_set", 1);
        count(growth, "incr_hits", 1);
        count(growth, "bytes", 48);
        wire.expect("set t 0 0 1\r\nx\r\nincr t 1\r\ndecr t 1\r\nincr z 1\r\ndecr z 1\r\n",
                "STORED\r\nCLIENT_ERROR cannot increment or decrement non-numeric value\r\n"
                + "CLIENT_ERROR cannot increment or decrement non-numeric value\r\nNOT_FOUND\r\nNOT_FOUND\r\n");
        var after = wire.stats("stats");
        count(after, "incr_hits", 1);
        count(after, "decr_hits", 1);
        count(after, "incr_misses", 1);
        count(after, "decr_misses", 1);
    }

    @Test void a08GeneratedPaddingSurvivesTouchButUserPaddingStaysNonnumeric() throws Exception {
        start();
        wire.expect("set n 0 100 8\r\n10000000\r\ndecr n 10000000\r\nget n\r\n",
                "STORED\r\n0\r\nVALUE n 0 8\r\n0       \r\nEND\r\n");
        wire.expect("touch n 90\r\nincr n 2\r\nget n\r\n", "TOUCHED\r\n2\r\nVALUE n 0 8\r\n2       \r\nEND\r\n");
        count(wire.stats("stats"), "total_items", 1);
        wire.expect("set u 0 0 8\r\n2       \r\nincr u 1\r\n",
                "STORED\r\nCLIENT_ERROR cannot increment or decrement non-numeric value\r\n");
        wire.expect("append n 0 0 1\r\n3\r\nget n\r\nincr n 1\r\n",
                "STORED\r\nVALUE n 0 9\r\n2       3\r\nEND\r\nCLIENT_ERROR cannot increment or decrement non-numeric value\r\n");
        var after = wire.stats("stats");
        count(after, "total_items", 3);
        count(after, "incr_hits", 1);
        count(after, "decr_hits", 1);
        count(after, "incr_misses", 0);
    }

    @Test void a06NativeSetRefusalDoesNotCountAndInvalidatesOldRecord() throws Exception {
        start("memory.limit", "1048576", "item.maxSize", "1048576");
        String value = "x".repeat(900000);
        wire.expect("set a 7 60 900000\r\n" + value + "\r\n", "STORED\r\n");
        wire.send("gets a\r\n");
        String header = wire.line();
        assertTrue(header.matches("VALUE a 7 900000 [0-9]+\r\n"));
        byte[] original = wire.socket.getInputStream().readNBytes(900002);
        wire.received += original.length;
        wire.record("RECV", original);
        assertArrayEquals((value + "\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII), original);
        assertEquals("END\r\n", wire.line());
        long occupied = server.resourceUsage().nativeUsedBytes();
        assertEquals(0, server.resourceUsage().bodyBytes());
        wire.expect("set a 0 0 900000\r\n" + "y".repeat(900000) + "\r\n",
                "SERVER_ERROR out of memory storing object\r\n");
        var values = wire.stats("stats");
        count(values, "cmd_set", 1);
        count(values, "total_items", 1);
        count(values, "curr_items", 0);
        count(values, "bytes", 0);
        assertTrue(occupied > 0);
        assertEquals(0, server.resourceUsage().nativeUsedBytes());
        wire.send("gets a\r\n");
        assertEquals("END\r\n", wire.line());
        clock.advance(61);
        wire.expect("get a\r\n", "END\r\n");
        count(wire.stats("stats"), "curr_items", 0);
    }

    @Test void a06PartialUploadCancellationReturnsNativeAndBodyReservations() throws Exception {
        start();
        Wire partial = connect();
        partial.send("set p 0 0 100\r\nx");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (server.resourceUsage().nativeUsedBytes() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(server.resourceUsage().nativeUsedBytes() >= 136);
        assertTrue(server.resourceUsage().bodyBytes() >= 132);
        partial.close();
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while ((server.resourceUsage().nativeUsedBytes() != 0 || server.resourceUsage().bodyBytes() != 0)
                && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(0, server.resourceUsage().nativeUsedBytes());
        assertEquals(0, server.resourceUsage().bodyBytes());
        var values = wire.stats("stats");
        count(values, "cmd_set", 1);
        count(values, "curr_items", 0);
        count(values, "total_items", 0);
    }

    @Test void a06ShutdownCancelsAdmittedIncompleteStorageExactlyOnce() throws Exception {
        start();
        Wire partial = connect();
        partial.send("set p 0 0 100\r\nx");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (server.resourceUsage().nativeUsedBytes() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(server.resourceUsage().nativeUsedBytes() >= 136);
        server.close();
        server.close();
        assertEquals(0, server.resourceUsage().nativeUsedBytes());
        assertEquals(0, server.resourceUsage().nativeTotalBytes());
        assertEquals(0, server.resourceUsage().bodyBytes());
        assertEquals(0, server.resourceUsage().serviceBytes());
        assertEquals(0, server.resourceUsage().snapshotBytes());
        assertEquals(0, server.resourceUsage().lineBytes());
        assertEquals(0, server.resourceUsage().controlBytes());
    }

    @Test void a11GeometryUsesPhysicalPagesAndLinkedRequestedBytes() throws Exception {
        start("memory.limit", "8388608", "item.maxSize", "2097152");
        String huge = "h".repeat(1_100_000);
        wire.expect("set a 0 0 1\r\nx\r\nset b 0 0 " + huge.length() + "\r\n" + huge + "\r\n", "STORED\r\nSTORED\r\n");
        var slabs = classes(wire.stats("stats slabs"), false);
        assertEquals(2, slabs.size());
        long pageBytes = 0;
        for (var cls : slabs.values()) {
            long chunk = number(cls, "chunk_size"), page = Math.max(1_048_576L, chunk);
            count(cls, "chunks_per_page", page / chunk);
            count(cls, "total_pages", 1);
            count(cls, "total_chunks", page / chunk);
            count(cls, "used_chunks", 1);
            count(cls, "free_chunks", page / chunk - 1);
            count(cls, "free_chunks_end", 0);
            pageBytes += page;
        }
        assertEquals(pageBytes, server.resourceUsage().nativeTotalBytes());
        count(wire.stats("stats"), "bytes", 1_100_080);
        count(slabs.get(classFor(slabs, 1_100_040)), "mem_requested", 1_100_040);
        wire.expect("delete a\r\n", "DELETED\r\n");
        assertEquals(1, classes(wire.stats("stats items"), true).size());
        var after = classes(wire.stats("stats slabs"), false);
        count(after.get(classFor(slabs, 40)), "mem_requested", 0);
        count(after.get(classFor(slabs, 40)), "used_chunks", 0);
    }

    @Test void a13LazyExpiryIsNotTailOrCrawlerReclaim() throws Exception {
        start();
        wire.expect("set a 0 2 1\r\nx\r\nset b 0 0 1\r\ny\r\n", "STORED\r\nSTORED\r\n");
        clock.advance(3);
        count(wire.stats("stats"), "curr_items", 2);
        wire.expect("get a\r\n", "END\r\n");
        var general = wire.stats("stats");
        count(general, "curr_items", 1);
        count(general, "bytes", 40);
        count(general, "reclaimed", 0);
        count(general, "expired_unfetched", 0);
        var cls = classes(wire.stats("stats items"), true).values().iterator().next();
        count(cls, "crawler_reclaimed", 0);
        count(cls, "reclaimed", 0);
        count(cls, "tailrepairs", 0);
    }

    @Test void a09IdenticalReplacementAndNoreplyTouchHaveDifferentLogicalTotals() throws Exception {
        start();
        wire.expect("set a 0 0 1\r\nx\r\nset a 0 0 1\r\nx\r\ntouch a 60 noreply\r\nversion\r\n",
                "STORED\r\nSTORED\r\nVERSION 1.0.0 (octomem)\r\n");
        var values = wire.stats("stats");
        count(values, "total_items", 2);
        count(values, "curr_items", 1);
        count(values, "bytes", 40);
        count(values, "cmd_set", 2);
        count(values, "cmd_touch", 1);
        count(values, "touch_hits", 1);
        wire.expect("delete a\r\n", "DELETED\r\n");
        var empty = wire.stats("stats");
        count(empty, "total_items", 2);
        count(empty, "curr_items", 0);
        count(empty, "bytes", 0);
        count(empty, "delete_hits", 1);
    }

    @Test void a17DelayedNoreplyFlushCountsOnceAndPreservesLaterRecords() throws Exception {
        start();
        wire.expect("set a 0 0 1\r\nx\r\nflush_all 2 noreply\r\nversion\r\n",
                "STORED\r\nVERSION 1.0.0 (octomem)\r\n");
        count(wire.stats("stats"), "cmd_flush", 1);
        clock.advance(3);
        wire.expect("set b 0 0 1\r\ny\r\nget a b\r\n", "STORED\r\nVALUE b 0 1\r\ny\r\nEND\r\n");
        var values = wire.stats("stats");
        count(values, "cmd_flush", 1);
        count(values, "total_items", 2);
        count(values, "curr_items", 1);
        count(values, "get_hits", 1);
        count(values, "get_misses", 1);
        count(values, "evictions", 0);
    }

    @Test void a15ResetPreservesRecordsCasAgeGeometryAndClearsCounters() throws Exception {
        start();
        wire.expect("set a 0 100 1\r\nx\r\nget a z\r\nadd a 0 0 1\r\ny\r\ncas a 0 0 1 0\r\ny\r\n"
                + "delete z\r\ntouch z 9\r\nincr z 1\r\ndecr z 1\r\n",
                "STORED\r\nVALUE a 0 1\r\nx\r\nEND\r\nNOT_STORED\r\nEXISTS\r\nNOT_FOUND\r\nNOT_FOUND\r\nNOT_FOUND\r\nNOT_FOUND\r\n");
        String token = wire.cas("a", "x");
        clock.advance(5);
        var before = wire.stats("stats");
        var slabs = classes(wire.stats("stats slabs"), false);
        for (int attempt = 0; attempt < 2; attempt++) {
            wire.expect("stats reset\r\n", "RESET\r\n");
            var after = wire.stats("stats");
            RESET_GENERAL.forEach(f -> count(after, f, 0));
            for (String field : Set.of("pid", "version", "pointer_size", "threads", "limit_maxbytes", "curr_connections", "connection_structures", "curr_items", "bytes", "uptime", "time")) {
                assertEquals(before.get(field), after.get(field), field);
            }
            var current = classes(wire.stats("stats slabs"), false);
            slabs.forEach((id, cls) -> {
                for (String f : Set.of("chunk_size", "chunks_per_page", "total_pages", "total_chunks", "used_chunks", "free_chunks", "free_chunks_end", "mem_requested")) assertEquals(cls.get(f), current.get(id).get(f), f);
                for (String f : Set.of("get_hits", "cmd_set", "delete_hits", "incr_hits", "decr_hits", "cas_hits", "cas_badval")) count(current.get(id), f, 0);
            });
            classes(wire.stats("stats items"), true).values().forEach(cls -> {
                ITEMS.stream().filter(f -> !f.equals("number") && !f.equals("age")).forEach(f -> count(cls, f, 0));
                count(cls, "number", 1);
                count(cls, "age", 5);
            });
        }
        assertEquals(token, wire.cas("a", "x"));
        clock.advance(96);
        wire.expect("get a\r\n", "END\r\n");
    }

    @Test void a16WireBytesCountFramingAndActualPreviousStatsResponse() throws Exception {
        start();
        var first = wire.stats("stats");
        long sentAtFirst = wire.sent;
        long receivedAfterFirst = wire.received;
        wire.expect("set a 0 0 1 noreply\r\nx\r\nversion\r\n", "VERSION 1.0.0 (octomem)\r\n");
        long receivedBeforeSecond = wire.received;
        var second = wire.stats("stats");
        assertEquals(wire.sent - sentAtFirst, number(second, "bytes_read") - number(first, "bytes_read"));
        assertEquals(receivedBeforeSecond, number(second, "bytes_written") - number(first, "bytes_written"));
        assertTrue(receivedAfterFirst > 0);
        wire.expect("stats reset\r\n", "RESET\r\n");
        var reset = wire.stats("stats");
        count(reset, "bytes_read", 7);
        count(reset, "bytes_written", 7);
    }

    @Test void a17FlushIsNotDeleteOrEviction() throws Exception {
        start();
        wire.expect("set a 0 0 1\r\nx\r\nflush_all\r\nget a\r\n", "STORED\r\nOK\r\nEND\r\n");
        var values = wire.stats("stats");
        count(values, "cmd_flush", 1);
        count(values, "delete_hits", 0);
        count(values, "evictions", 0);
        count(values, "total_items", 1);
        wire.send("flush_all invalid\r\n");
        assertEquals("CLIENT_ERROR invalid exptime argument\r\n", wire.line());
        count(wire.stats("stats"), "cmd_flush", 2);
    }

    @Test void a18ConcurrentIndependentOperationsHaveExactQuiescentTotals() throws Exception {
        start();
        Wire[] writers = {connect(), connect(), connect(), connect()};
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < 4; t++) {
                final int id = t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 20; i++) {
                        String key = "k" + id + "_" + i;
                        writers[id].expect("set " + key + " 0 0 1\r\nx\r\nget " + key + " absent\r\n",
                                "STORED\r\nVALUE " + key + " 0 1\r\nx\r\nEND\r\n");
                    }
                    return null;
                }));
            }
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
        }
        var values = wire.stats("stats");
        count(values, "cmd_set", 80);
        count(values, "total_items", 80);
        count(values, "curr_items", 80);
        count(values, "cmd_get", 160);
        count(values, "get_hits", 80);
        count(values, "get_misses", 80);
    }

    @Test void a21TinyLegalChunksStreamCompleteMandatoryStats() throws Exception {
        start("protocol.maxChunkBytes", "2", "connections.pendingWriteBytes", "2");
        wire.expect("set a 0 0 1\r\nx\r\n", "STORED\r\n");
        assertTrue(wire.stats("stats").keySet().containsAll(GENERAL));
        classes(wire.stats("stats slabs"), false).values().forEach(v -> assertTrue(v.keySet().containsAll(SLABS)));
        classes(wire.stats("stats items"), true).values().forEach(v -> assertTrue(v.keySet().containsAll(ITEMS)));
    }

    @Test void a21ClassSnapshotDenialHasNoPartialStatAndDoesNotChangeData() throws Exception {
        start("memory.limit", "1024", "item.maxSize", "64");
        wire.expect("set a 0 0 1\r\nx\r\n", "STORED\r\n");
        wire.expect("stats items\r\nstats slabs\r\nversion\r\n",
                "SERVER_ERROR statistics unavailable\r\nSERVER_ERROR statistics unavailable\r\nVERSION 1.0.0 (octomem)\r\n");
        var general = wire.stats("stats");
        count(general, "curr_items", 1);
        count(general, "bytes", 40);
        count(general, "total_items", 1);
        count(general, "cmd_set", 1);
        assertEquals(0, server.resourceUsage().snapshotBytes());
    }

    @Test void a21TinyValidBudgetStillStartsAndGeneralStatsRemainAvailable() throws Exception {
        start("memory.limit", "1", "item.maxSize", "1", "protocol.maxChunkBytes", "2");
        wire.expect("version\r\n", "VERSION 1.0.0 (octomem)\r\n");
        var values = wire.stats("stats");
        assertTrue(values.keySet().containsAll(GENERAL));
        count(values, "limit_maxbytes", 1);
        count(values, "curr_items", 0);
        count(values, "bytes", 0);
        assertEquals(0, server.resourceUsage().nativeTotalBytes());
    }

    @Test void a21StalledStatsReaderCannotBlockHealthyConnection() throws Exception {
        start("protocol.maxChunkBytes", "2", "connections.pendingWriteBytes", "2", "connections.maxInflightCommands", "4");
        wire.expect("set a 0 0 1\r\nx\r\n", "STORED\r\n");
        Wire stalled = connect();
        stalled.socket.setReceiveBufferSize(1024);
        stalled.send("stats slabs\r\n".repeat(200));
        try (var pool = Executors.newSingleThreadExecutor()) {
            pool.submit(() -> {
                wire.expect("version\r\nget a\r\n", "VERSION 1.0.0 (octomem)\r\nVALUE a 0 1\r\nx\r\nEND\r\n");
                assertTrue(wire.stats("stats").keySet().containsAll(GENERAL));
                return null;
            }).get(5, TimeUnit.SECONDS);
        } finally {
            stalled.close();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (server.resourceUsage().snapshotBytes() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(0, server.resourceUsage().snapshotBytes(), "Disconnect returns every class snapshot reservation");
    }

    @Test void a04CrossClassDuplicatesWithExpiryAndFlushMisses() throws Exception {
        start();
        wire.expect("set a 0 0 1\r\nx\r\nset bb 0 0 100\r\n" + "y".repeat(100) + "\r\nset e 0 2 1\r\nx\r\n",
                "STORED\r\nSTORED\r\nSTORED\r\n");
        wire.expect("get a a bb z z\r\n",
                "VALUE a 0 1\r\nx\r\nVALUE bb 0 100\r\n" + "y".repeat(100) + "\r\nEND\r\n");
        var slab = classes(wire.stats("stats slabs"), false);
        assertEquals(2, slab.size());
        count(slab.get(classFor(slab, 40)), "get_hits", 2);
        count(slab.get(classFor(slab, 136)), "get_hits", 1);
        var afterGet = wire.stats("stats");
        count(afterGet, "cmd_get", 5);
        count(afterGet, "get_hits", 3);
        count(afterGet, "get_misses", 2);
        clock.advance(3);
        wire.expect("get e\r\n", "END\r\n");
        var expired = wire.stats("stats");
        count(expired, "cmd_get", 6);
        count(expired, "get_misses", 3);
        wire.expect("flush_all\r\nget a bb\r\n", "OK\r\nEND\r\n");
        var flushed = wire.stats("stats");
        count(flushed, "cmd_get", 8);
        count(flushed, "get_misses", 5);
        count(flushed, "cmd_flush", 1);
        count(flushed, "evictions", 0);
        count(flushed, "curr_items", 0);
    }

    @Test void a07PrependFragmentClassIsNotOldOrCombinedClass() throws Exception {
        start();
        wire.expect("set a 0 0 100\r\n" + "y".repeat(100) + "\r\n", "STORED\r\n");
        wire.expect("prepend a 0 0 1\r\nz\r\n", "STORED\r\n");
        var slabs = classes(wire.stats("stats slabs"), false);
        int small = classFor(slabs, 40), large = classFor(slabs, 136);
        assertNotEquals(small, large);
        count(slabs.get(large), "cmd_set", 1);
        count(slabs.get(small), "cmd_set", 1);
        count(slabs.get(large), "cas_hits", 0);
        count(slabs.get(large), "cas_badval", 0);
        var values = wire.stats("stats");
        count(values, "cmd_set", 2);
        count(values, "total_items", 2);
        count(values, "curr_items", 1);
        count(values, "bytes", 136);
        wire.send("get a\r\n");
        assertEquals("VALUE a 0 101\r\n", wire.line());
        byte[] body = wire.socket.getInputStream().readNBytes(103);
        wire.received += body.length;
        wire.record("RECV", body);
        assertEquals("z" + "y".repeat(100) + "\r\n", new String(body, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals("END\r\n", wire.line());
    }

    @Test void a15RealEvictionAndReclaimThenEndToEndReset() throws Exception {
        start("memory.limit", "16384", "item.maxSize", "2048");
        String value = "x".repeat(1000);
        StringBuilder request = new StringBuilder();
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            request.append("set k").append(i).append(" 0 0 1000\r\n").append(value).append("\r\n");
            expected.append("STORED\r\n");
        }
        wire.expect(request.toString(), expected.toString());
        var evicted = wire.stats("stats");
        count(evicted, "curr_items", 14);
        count(evicted, "total_items", 20);
        count(evicted, "evictions", 6);
        count(evicted, "evicted_unfetched", 6);
        count(evicted, "bytes", 14560);
        wire.expect("set e 0 2 1000\r\n" + value + "\r\n", "STORED\r\n");
        var afterNewest = wire.stats("stats");
        count(afterNewest, "evictions", 7);
        count(afterNewest, "evicted_unfetched", 7);
        count(afterNewest, "curr_items", 14);
        count(afterNewest, "total_items", 21);
        count(afterNewest, "bytes", 14560);
        clock.advance(3);
        wire.expect("set n 0 0 1000\r\n" + value + "\r\n", "STORED\r\n");
        var reclaimed = wire.stats("stats");
        count(reclaimed, "evictions", 7);
        count(reclaimed, "reclaimed", 1);
        count(reclaimed, "expired_unfetched", 1);
        count(reclaimed, "curr_items", 14);
        count(reclaimed, "total_items", 22);
        count(reclaimed, "bytes", 14560);
        wire.expect("stats reset\r\n", "RESET\r\n");
        var after = wire.stats("stats");
        count(after, "evictions", 0);
        count(after, "reclaimed", 0);
        count(after, "expired_unfetched", 0);
        count(after, "evicted_unfetched", 0);
        count(after, "total_items", 0);
        count(after, "curr_items", 14);
        count(after, "bytes", 14560);
        wire.send("get n\r\n");
        assertEquals("VALUE n 0 1000\r\n", wire.line());
        byte[] body = wire.socket.getInputStream().readNBytes(1002);
        wire.received += body.length;
        wire.record("RECV", body);
        assertEquals(value + "\r\n", new String(body, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals("END\r\n", wire.line());
    }

    @Test void a17AbsoluteRelativeAndImmediateFlushMatrix() throws Exception {
        start();
        wire.expect("set a 0 0 1\r\nx\r\nset b 0 0 1\r\ny\r\n", "STORED\r\nSTORED\r\n");
        wire.expect("flush_all 1700000005\r\n", "OK\r\n");
        wire.expect("get a b\r\n", "VALUE a 0 1\r\nx\r\nVALUE b 0 1\r\ny\r\nEND\r\n");
        var beforeDeadline = wire.stats("stats");
        count(beforeDeadline, "cmd_flush", 1);
        count(beforeDeadline, "get_hits", 2);
        clock.advance(6);
        wire.expect("get a b\r\n", "END\r\n");
        wire.expect("set c 0 0 1\r\nz\r\n", "STORED\r\n");
        wire.expect("flush_all 1\r\n", "OK\r\n");
        clock.advance(2);
        wire.expect("get c\r\n", "END\r\n");
        wire.expect("set d 0 0 1\r\nw\r\n", "STORED\r\n");
        wire.expect("flush_all 0\r\n", "OK\r\n");
        wire.expect("get d\r\n", "END\r\n");
        var after = wire.stats("stats");
        count(after, "cmd_flush", 3);
        count(after, "total_items", 4);
        count(after, "curr_items", 0);
        count(after, "get_hits", 2);
        count(after, "get_misses", 4);
        count(after, "evictions", 0);
        count(after, "delete_hits", 0);
    }
}

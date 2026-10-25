package ru.octomem.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Resource admission arithmetic and lifetime assertions independent of socket timing. */
class ConnectionResourcesAcceptanceTest {
    @Test void sharedLineBudgetChargesBeforeUseAndDenialDoesNotPartiallyReserve() {
        SharedBudget budget = new SharedBudget(4608, 0);
        AtomicLong body = new AtomicLong();
        try (ConnectionResources a = new ConnectionResources(body, 128, 96, budget, 2304, 4608);
             ConnectionResources b = new ConnectionResources(body, 128, 96, budget, 2304, 4608);
             ConnectionResources c = new ConnectionResources(body, 128, 96, budget, 2304, 4608)) {
            assertTrue(a.acquireLine()); assertEquals(2304, a.usedLineBytes());
            assertTrue(b.acquireLine()); assertEquals(4608, budget.usedBytes());
            assertFalse(c.acquireLine()); assertEquals(0, c.usedLineBytes());
            assertEquals(0, body.get());
            a.releaseLine(); assertTrue(c.acquireLine());
            b.close(); b.close(); assertEquals(2304, budget.usedBytes());
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void sharedBudgetNearMaximumRejectsOverflowAndValidatesExactRelease() {
        assertEquals(Long.MAX_VALUE, SharedBudget.saturatedMultiply(Long.MAX_VALUE, 2));
        assertEquals(42, SharedBudget.saturatedMultiply(6, 7));
        assertThrows(IllegalArgumentException.class, () -> SharedBudget.saturatedMultiply(0, 1));
        assertThrows(IllegalArgumentException.class, () -> SharedBudget.saturatedMultiply(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionResources(new AtomicLong(), 32, 0,
                new SharedBudget(100, 0), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionResources(new AtomicLong(), 32, 0,
                new SharedBudget(100, 0), 1, -1));
        assertThrows(IllegalArgumentException.class, () -> new SharedBudget(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new SharedBudget(0, -1));
        assertThrows(IllegalArgumentException.class, () -> new SharedBudget(1, 2));
        SharedBudget budget = new SharedBudget(Long.MAX_VALUE, Long.MAX_VALUE - 10);
        assertFalse(budget.reserve(11)); assertEquals(Long.MAX_VALUE - 10, budget.usedBytes());
        assertTrue(budget.reserve(10)); assertEquals(Long.MAX_VALUE, budget.usedBytes());
        assertTrue(budget.reserve(0)); budget.release(0);
        assertThrows(IllegalArgumentException.class, () -> budget.reserve(-1));
        assertThrows(IllegalArgumentException.class, () -> budget.release(-1));
        budget.release(Long.MAX_VALUE); assertEquals(0, budget.usedBytes());
        assertThrows(IllegalStateException.class, () -> budget.release(1));
        SharedBudget empty = new SharedBudget(0, 0);
        assertFalse(empty.reserve(1)); assertTrue(empty.reserve(0));
        try (ConnectionResources r = new ConnectionResources(new AtomicLong(), 32, 0,
                new SharedBudget(100, 0), 50, 49)) {
            assertFalse(r.acquireLine()); assertEquals(0, r.usedLineBytes());
        }
    }

    @Test void concurrentReservationsNeverExceedSharedCeilingAndReturnToZero() throws Exception {
        SharedBudget lines = new SharedBudget(512, 0);
        AtomicLong body = new AtomicLong();
        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(8);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            java.util.List<java.util.concurrent.Future<?>> jobs = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) jobs.add(pool.submit(() -> {
                start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                try (ConnectionResources r = new ConnectionResources(body, 512, 32, lines, 64, 64)) {
                    for (int n = 0; n < 10_000; n++) {
                        if (r.acquireLine()) {
                            assertTrue(lines.usedBytes() <= 512);
                            r.releaseLine();
                        }
                        if (r.tryReserveBody(32)) {
                            assertTrue(body.get() <= 512); r.releaseBody(32);
                        }
                    }
                }
                return null;
            }));
            for (var job : jobs) job.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow(); assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(0, lines.usedBytes()); assertEquals(0, body.get());
    }

    @Test void exactBodyChargeSharedLimitAndMismatchedRelease() {
        AtomicLong global = new AtomicLong();
        try (ConnectionResources first = new ConnectionResources(global, 96, 64);
             ConnectionResources second = new ConnectionResources(global, 96, 64)) {
            assertTrue(first.tryReserveBody(64));
            assertEquals(96, global.get());
            assertFalse(second.tryReserveBody(0));
            assertEquals(0, second.usedBodyBytes());
            assertThrows(IllegalStateException.class, () -> first.tryReserveBody(1));
            assertThrows(IllegalStateException.class, () -> first.releaseBody(63));
            assertThrows(IllegalStateException.class, () -> first.releaseBody(-1));
            assertEquals(96, global.get());
            first.releaseBody(64);
            assertThrows(IllegalStateException.class, () -> first.releaseBody(64));
            assertTrue(second.tryReserveBody(0));
            assertEquals(32, global.get());
        }
        assertEquals(0, global.get());
    }

    @Test void lineSlotAndCloseAreBoundedAndOwnerConfined() throws Exception {
        AtomicLong global = new AtomicLong();
        ConnectionResources r = new ConnectionResources(global, 128, 96);
        assertTrue(r.acquireLine());
        assertFalse(r.acquireLine());
        assertEquals(1, r.lineSlots());
        r.releaseLine();
        assertThrows(IllegalStateException.class, r::releaseLine);
        assertThrows(IllegalArgumentException.class, () -> r.tryReserveBody(-1));
        assertThrows(IllegalArgumentException.class, () -> r.tryReserveBody(97));
        assertTrue(r.tryReserveBody(96));
        AtomicReference<Throwable> wrongOwner = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try { r.acquireLine(); } catch (Throwable failure) { wrongOwner.set(failure); }
        });
        other.start(); other.join(5000);
        assertFalse(other.isAlive());
        assertInstanceOf(IllegalStateException.class, wrongOwner.get());
        assertEquals(128, global.get());
        AtomicReference<Throwable> wrongClose = new AtomicReference<>();
        Thread closer = new Thread(() -> {
            try { r.close(); } catch (Throwable failure) { wrongClose.set(failure); }
        });
        closer.start(); closer.join(5000);
        assertFalse(closer.isAlive());
        assertInstanceOf(IllegalStateException.class, wrongClose.get());
        r.close(); r.close();
        assertEquals(0, global.get());
        assertEquals(0, r.lineSlots());
        assertThrows(IllegalStateException.class, r::acquireLine);
        assertThrows(IllegalStateException.class, () -> r.tryReserveBody(0));
    }

    @Test void checkedReservationAtLongBoundaryDoesNotOverflow() {
        AtomicLong global = new AtomicLong(Long.MAX_VALUE - 31);
        try (ConnectionResources r = new ConnectionResources(global, Long.MAX_VALUE, Integer.MAX_VALUE)) {
            assertFalse(r.tryReserveBody(0));
            assertEquals(Long.MAX_VALUE - 31, global.get());
        }
        global.set(Long.MAX_VALUE - 32);
        try (ConnectionResources r = new ConnectionResources(global, Long.MAX_VALUE, Integer.MAX_VALUE)) {
            assertTrue(r.tryReserveBody(0));
            assertEquals(Long.MAX_VALUE, global.get());
            r.releaseBody(0);
            assertEquals(Long.MAX_VALUE - 32, global.get());
        }
    }

    @Test void settingsValidateLocalBoundsButDoNotRejectSaturatedAggregateCeilings() {
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 0);
        assertDoesNotThrow(() -> new TransportSettings(address, 1, Integer.MAX_VALUE, 2,
                Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 2, Long.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(NullPointerException.class, () -> new TransportSettings(null, 1, 1, 2, 1, 0, 1, 2, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TransportSettings(
                InetSocketAddress.createUnresolved("invalid.test", 0), 1, 1, 2, 1, 0, 1, 2, 1, 1));
        int[][] invalid = {{0,1,2,1,1,2,1}, {1,0,2,1,1,2,1}, {1,1,1,1,1,2,1},
                {1,1,2,0,1,2,1}, {1,1,2,1,0,2,1}, {1,1,2,1,1,1,1}, {1,1,2,1,1,2,0}};
        for (int[] v : invalid) assertThrows(IllegalArgumentException.class,
                () -> new TransportSettings(address,v[0],v[1],v[2],v[3],0,v[4],v[5],1,v[6]));
        assertThrows(IllegalArgumentException.class, () -> new TransportSettings(address,1,1,2,1,-1,1,2,1,1));
        assertThrows(IllegalArgumentException.class, () -> new TransportSettings(address,1,1,2,1,Long.MAX_VALUE,1,2,1,1));
        assertThrows(IllegalArgumentException.class, () -> new TransportSettings(address,1,1,2,1,0,1,2,0,1));
        assertThrows(IllegalArgumentException.class, () -> new TransportSettings(address,1,1,2,1,0,1,2,1,2));
    }
}

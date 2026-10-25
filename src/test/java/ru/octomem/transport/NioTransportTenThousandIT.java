package ru.octomem.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.octomem.stats.Gauge;

/** Separate mandatory 10,000-actual-connection proof, never a scaled-down approximation. */
class NioTransportTenThousandIT {
    @Test @Timeout(240)
    void tenThousandActualConnectionsUseFixedEventLoopsAndReleaseOnShutdown() throws Exception {
        String run = "T006-10000-" + Instant.now();
        List<Socket> sockets = new ArrayList<>(10_000);
        NioTransportSocketTest.Fixture fixture = null;
        Set<Long> baselineThreads = threadIds();
        sample(run, "before", 0);
        try {
            fixture = new NioTransportSocketTest.Fixture(2, 10_000, 31, 0, 1);
            for (int i = 0; i < 10_000; i++) {
                Socket socket = fixture.connect();
                sockets.add(socket);
                NioTransportSocketTest.request(socket, 'P', 0);
                NioTransportSocketTest.verify(socket, 17);
            }
            assertEquals(10_000, sockets.size());
            assertEquals(10_000, fixture.peers.size());
            assertEquals(10_000L, fixture.stats.snapshot().gauges().get(Gauge.CURR_CONNECTIONS));
            Set<Thread> owners = new HashSet<>();
            for (NioTransportSocketTest.Peer p : fixture.peers) owners.add(p.owner);
            assertEquals(2, owners.size(), "exact configured event loops, no per-connection threads");
            Set<Long> duringThreads = threadIds();
            duringThreads.removeAll(baselineThreads);
            // JUnit timeout/management helpers may be present, so record the complete set too.
            System.out.println(run + " liveNewThreads=" + duringThreads + " eventLoopOwners=" + owners);
            assertEquals(3, duringThreads.size(), "one acceptor plus exactly two fixed event loops");
            sample(run, "all-open", sockets.size());
            fixture.close();
            for (NioTransportSocketTest.Peer p : fixture.peers) {
                assertEquals(1, p.closed.get());
                assertEquals(0, p.resources.lineSlots());
                assertEquals(0, p.resources.usedBodyBytes());
            }
            for (Thread owner : owners) assertFalse(owner.isAlive(), "event loop leaked after close");
            Set<Long> surviving = threadIds();
            surviving.retainAll(duringThreads);
            assertTrue(surviving.isEmpty(), "transport threads leaked: " + surviving);
            assertEquals(0L, fixture.stats.snapshot().gauges().get(Gauge.CURR_CONNECTIONS));
            fixture.assertHealthy();
            System.out.println(run + " outcome=PASS actualConcurrentSockets=10000");
        } catch (Exception | AssertionError failure) {
            System.out.println(run + " outcome=FAIL actualOpened=" + sockets.size() + " cause=" + failure);
            throw failure;
        } finally {
            for (Socket socket : sockets) socket.close();
            if (fixture != null) fixture.close();
            sample(run, "after-cleanup", 0);
        }
    }

    private static Set<Long> threadIds() {
        Set<Long> result = new HashSet<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) result.add(t.threadId());
        return result;
    }

    private static void sample(String run, String phase, int connections) {
        System.out.println(run + " phase=" + phase + " connections=" + connections
                + " threads=" + ManagementFactory.getThreadMXBean().getThreadCount()
                + " heapUsed=" + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()
                + " nonHeapUsed=" + ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed()
                + " java=" + System.getProperty("java.runtime.version")
                + " vendor=" + System.getProperty("java.vendor")
                + " os=" + System.getProperty("os.name") + " arch=" + System.getProperty("os.arch")
                + " args=" + ManagementFactory.getRuntimeMXBean().getInputArguments());
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            System.out.println(run + " phase=" + phase + " pool=" + pool.getName()
                    + " count=" + pool.getCount() + " memory=" + pool.getMemoryUsed()
                    + " capacity=" + pool.getTotalCapacity());
        }
    }
}

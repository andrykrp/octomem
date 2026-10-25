package ru.octomem.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.octomem.protocol.*;

/** Approved OS-operation seams inject faults; real sockets and production cleanup still execute. */
@Timeout(30)
class TransportFaultAcceptanceTest {
    @Test void selectedReadinessAndSelfQueuedWriterDoNotDuplicateDispatch() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean selectedWhileQueued = new java.util.concurrent.atomic.AtomicBoolean();
        TransportOps ops = new TransportOps() {
            @Override int select(Selector selector, long timeout) throws IOException {
                int result = super.select(selector, timeout);
                if (Thread.currentThread().getName().contains("event") && timeout == 0 && !selector.keys().isEmpty()) {
                    // Re-enable real readiness notification on a registered socket, without touching queues.
                    for (var key : selector.keys()) if (key.isValid()) key.interestOps(java.nio.channels.SelectionKey.OP_WRITE);
                    result = super.select(selector, 0);
                    if (!selector.selectedKeys().isEmpty()) selectedWhileQueued.set(true);
                }
                return result;
            }
        };
        try (NioTransportSocketTest.Fixture f = new NioTransportSocketTest.Fixture(1, 4, 17, 0, 1, null, ops);
             Socket socket = f.connect(65536)) {
            var peer = f.peer(0);
            NioTransportSocketTest.request(socket, 'S', 0);
            NioTransportSocketTest.verify(socket, 8 * 1024 * 1024);
            NioTransportSocketTest.request(socket, 'P', 0); NioTransportSocketTest.verify(socket, 17);
            assertTrue(selectedWhileQueued.get());
            assertEquals(2, peer.encoded.get()); assertEquals(2, peer.handled.get());
            f.close(); assertEquals(2, peer.writerClosed.get()); f.assertHealthy();
        }
    }
    @Test void selectorBoundaryIgnoresSyntheticCancelledSelectedKey() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1), boundaryPassed = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean invalidated = new java.util.concurrent.atomic.AtomicBoolean();
        TransportOps ops = new TransportOps() {
            @Override int read(SocketChannel socket, ByteBuffer dst) throws IOException {
                reads.incrementAndGet(); return super.read(socket, dst);
            }
            @Override int select(Selector selector, long timeout) throws IOException {
                if (Thread.currentThread().getName().contains("event") && invalidated.get()) {
                    boundaryPassed.countDown(); awaitHook(release); return 0;
                }
                int result = super.select(selector, timeout);
                if (Thread.currentThread().getName().contains("event") && !selector.selectedKeys().isEmpty()
                        && invalidated.compareAndSet(false, true)) {
                    selector.selectedKeys().iterator().next().cancel(); cancelled.countDown();
                }
                return result;
            }
        };
        NioTransport transport = transport(ops, r -> new EmptyHandler());
        try {
            transport.start();
            try (Socket socket = connect(transport)) {
                socket.getOutputStream().write(1);
                assertTrue(cancelled.await(5, TimeUnit.SECONDS));
                assertTrue(boundaryPassed.await(5, TimeUnit.SECONDS));
                assertEquals(0, reads.get(), "invalid selection must not pump a connection");
            }
        } finally {
            Thread closer = new Thread(transport::close); closer.start();
            release.countDown(); closer.join(6000); transport.close(); assertFalse(closer.isAlive());
        }
        assertEquals(0, transport.usedLineBytes());
    }
    @Test void reentrantStartupCancellationNeverLeavesThreadsOrSelectorsOpen() throws Exception {
        for (int at : new int[] {1, 2}) {
            var holder = new java.util.concurrent.atomic.AtomicReference<NioTransport>();
            List<Thread> threads = new CopyOnWriteArrayList<>();
            List<Selector> selectors = new CopyOnWriteArrayList<>();
            AtomicInteger calls = new AtomicInteger();
            TransportOps ops = new TransportOps() {
                @Override Selector openSelector() throws IOException {
                    Selector selector = super.openSelector(); selectors.add(selector); return selector;
                }
                @Override void startThread(Thread thread) {
                    threads.add(thread);
                    if (calls.incrementAndGet() == at) {
                        holder.get().close();
                        return;
                    }
                    super.startThread(thread);
                }
            };
            NioTransport transport = transport(ops, r -> new EmptyHandler()); holder.set(transport);
            try { assertThrows(IOException.class, transport::start); }
            finally { transport.close(); }
            for (Thread thread : threads) assertFalse(thread.isAlive());
            for (Selector selector : selectors) assertFalse(selector.isOpen());
        }
    }

    @Test void selectorFailureAfterOwnerInitiatedShutdownStillCompletesCleanup() throws Exception {
        for (boolean acceptor : new boolean[] {true, false}) {
            CountDownLatch startReturned = new CountDownLatch(1);
            var holder = new java.util.concurrent.atomic.AtomicReference<NioTransport>();
            AtomicInteger injected = new AtomicInteger();
            List<Thread> owners = new CopyOnWriteArrayList<>();
            List<Selector> selectors = new CopyOnWriteArrayList<>();
            TransportOps ops = new TransportOps() {
                @Override Selector openSelector() throws IOException {
                    Selector selector = super.openSelector(); selectors.add(selector); return selector;
                }
                @Override void startThread(Thread thread) { owners.add(thread); super.startThread(thread); }
                @Override int select(Selector selector, long timeout) throws IOException {
                    if (Thread.currentThread().getName().contains("acceptor") == acceptor
                            && injected.compareAndSet(0, 1)) {
                        awaitHook(startReturned);
                        holder.get().close();
                        throw new IOException("injected select failure after stopping");
                    }
                    return super.select(selector, timeout);
                }
            };
            NioTransport transport = transport(ops, r -> new EmptyHandler()); holder.set(transport);
            try {
                transport.start(); startReturned.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                for (Thread owner : owners) {
                    owner.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
                    assertFalse(owner.isAlive());
                }
                for (Selector selector : selectors) assertFalse(selector.isOpen());
                assertEquals(0, transport.usedLineBytes());
            } finally { startReturned.countDown(); transport.close(); }
        }
    }
    @Test void cancellingActiveWriterWhoseCloseThrowsStillReleasesHandlerAndBudgets() throws Exception {
        TransportOps ops = new TransportOps() {
            @Override int write(SocketChannel socket, ByteBuffer src) { return 0; }
        };
        try (NioTransportSocketTest.Fixture f = new NioTransportSocketTest.Fixture(1, 4, 17, 0, 1, null, ops);
             Socket socket = f.connect()) {
            var peer = f.peer(0);
            NioTransportSocketTest.request(socket, 'K', 0);
            f.await(() -> peer.writerCalls.get() > 0);
            assertEquals(0, peer.responsesClosed.get());
            assertTrue(f.transport.usedLineBytes() > 0);
            f.close();
            assertEquals(1, peer.writerClosed.get()); assertEquals(1, peer.responsesClosed.get());
            assertEquals(1, peer.closed.get());
            assertEquals(0, f.transport.usedLineBytes()); assertEquals(0, f.transport.usedBodyBytes());
            assertFalse(peer.owner.isAlive()); f.assertHealthy();
        }
    }

    @Test void realSocketLineDenialPrecedesReadAndDoesNotReleaseExternalSeed() throws Exception {
        SharedBudget budget = new SharedBudget(2304, 2303);
        AtomicInteger reads = new AtomicInteger(), factories = new AtomicInteger();
        List<ConnectionResources> resources = new CopyOnWriteArrayList<>();
        CountDownLatch healthyRead = new CountDownLatch(1);
        TransportOps ops = new TransportOps() {
            @Override int read(SocketChannel socket, ByteBuffer dst) throws IOException {
                reads.incrementAndGet(); healthyRead.countDown(); return super.read(socket, dst);
            }
        };
        var stats = NioTransportSocketTest.registry();
        NioTransport transport = new NioTransport(NioTransportSocketTest.settings(1, 4, 17, 0, 1,
                new InetSocketAddress("127.0.0.1", 0)), r -> {
                    factories.incrementAndGet(); resources.add(r); return new EmptyHandler();
                }, stats, new NioTransportSocketTest.ManualClock(), ops, budget);
        try {
            transport.start();
            try (Socket denied = connect(transport)) {
                denied.getOutputStream().write(1);
                assertEquals(-1, denied.getInputStream().read());
            }
            assertEquals(0, reads.get()); assertEquals(2303, budget.usedBytes());
            assertEquals(0, transport.usedBodyBytes());
            budget.release(2303);
            try (Socket healthy = connect(transport)) {
                healthy.getOutputStream().write(1);
                assertTrue(healthyRead.await(5, TimeUnit.SECONDS));
                healthy.shutdownOutput(); assertEquals(-1, healthy.getInputStream().read());
            }
            transport.close();
            assertEquals(2, factories.get());
            assertEquals(2L, stats.snapshot().counters().get(ru.octomem.stats.Counter.TOTAL_CONNECTIONS));
            assertEquals(0L, stats.snapshot().gauges().get(ru.octomem.stats.Gauge.CURR_CONNECTIONS));
            assertEquals(0, budget.usedBytes());
            for (ConnectionResources r : resources) assertEquals(0, r.usedLineBytes());
        } finally { transport.close(); }
    }

    @Test void staleReadyConnectionClosedByIdleScanIsNeverPumpedAgain() throws Exception {
        try (NioTransportSocketTest.Fixture f = new NioTransportSocketTest.Fixture(1, 4, 17, 10, 1);
             Socket socket = f.connect()) {
            var peer = f.peer(0);
            NioTransportSocketTest.request(socket, 'I', 0);
            f.await(() -> peer.closed.get() == 1);
            assertTrue(peer.writerCalls.get() >= 70);
            assertEquals(1, peer.writerClosed.get()); assertEquals(1, peer.responsesClosed.get());
            try (Socket healthy = f.connect()) {
                healthy.shutdownOutput(); assertEquals(-1, healthy.getInputStream().read());
            }
            f.close();
            assertEquals(1, peer.closed.get()); assertEquals(0, f.transport.usedLineBytes());
            f.assertHealthy();
        }
    }

    @Test void moreThanOneAdmissionBatchDrainsAllQueuedConnections() throws Exception {
        CountDownLatch paused = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        Object changed = new Object();
        TransportOps ops = new TransportOps() {
            @Override int select(Selector selector, long millis) throws IOException {
                if (Thread.currentThread().getName().contains("event")) { paused.countDown(); awaitHook(release); }
                return super.select(selector, millis);
            }
            @Override void beforeHandoff(SocketChannel socket) {
                synchronized (changed) { accepted.incrementAndGet(); changed.notifyAll(); }
            }
        };
        List<EmptyHandler> handlers = new CopyOnWriteArrayList<>();
        NioTransport transport = new NioTransport(NioTransportSocketTest.settings(1, 80, 17, 0, 1,
                new InetSocketAddress("127.0.0.1", 0)), r -> {
                    EmptyHandler h = new EmptyHandler(); handlers.add(h); return h;
                }, NioTransportSocketTest.registry(), new NioTransportSocketTest.ManualClock(), ops);
        List<Socket> sockets = new java.util.ArrayList<>();
        try {
            transport.start(); assertTrue(paused.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 65; i++) {
                sockets.add(connect(transport));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                synchronized (changed) {
                    while (accepted.get() < i + 1) {
                        long left = deadline - System.nanoTime(); assertTrue(left > 0);
                        TimeUnit.NANOSECONDS.timedWait(changed, left);
                    }
                }
            }
            release.countDown();
            for (Socket s : sockets) { s.shutdownOutput(); assertEquals(-1, s.getInputStream().read()); }
            transport.close(); assertEquals(65, handlers.size());
            for (EmptyHandler h : handlers) assertEquals(1, h.closed.get());
            assertEquals(0, transport.usedLineBytes());
        } finally {
            release.countDown(); transport.close();
            for (Socket s : sockets) s.close();
        }
    }
    @Test void shutdownDuringPreHandoffReturnsAdmissionAndNeverConstructsHandler() throws Exception {
        CountDownLatch handoff = new CountDownLatch(1), release = new CountDownLatch(1), joining = new CountDownLatch(1);
        AtomicInteger factories = new AtomicInteger();
        TransportOps ops = new TransportOps() {
            @Override void beforeHandoff(SocketChannel socket) throws IOException {
                handoff.countDown(); awaitHook(release);
            }
            @Override void join(Thread t, long millis) throws InterruptedException {
                joining.countDown(); super.join(t, millis);
            }
        };
        NioTransport transport = transport(ops, r -> { factories.incrementAndGet(); return new EmptyHandler(); });
        Thread closer = new Thread(transport::close);
        try {
            transport.start();
            try (Socket socket = connect(transport)) {
                assertTrue(handoff.await(5, TimeUnit.SECONDS));
                closer.start(); assertTrue(joining.await(5, TimeUnit.SECONDS));
                release.countDown(); closer.join(6000);
                assertFalse(closer.isAlive());
                assertEquals(-1, socket.getInputStream().read());
                assertEquals(0, factories.get());
                assertEquals(0, transport.usedLineBytes());
            }
        } finally { release.countDown(); transport.close(); closer.join(6000); }
    }

    @Test void shutdownCancelsAcceptedSocketsQueuedBehindPausedEventLoops() throws Exception {
        CountDownLatch loopsPaused = new CountDownLatch(2), release = new CountDownLatch(1), joining = new CountDownLatch(1);
        CountDownLatch accepted = new CountDownLatch(8);
        AtomicInteger factories = new AtomicInteger();
        TransportOps ops = new TransportOps() {
            @Override int select(Selector selector, long millis) throws IOException {
                if (Thread.currentThread().getName().contains("event")) {
                    loopsPaused.countDown(); awaitHook(release);
                }
                return super.select(selector, millis);
            }
            @Override void beforeHandoff(SocketChannel socket) { accepted.countDown(); }
            @Override void join(Thread t, long millis) throws InterruptedException {
                joining.countDown(); super.join(t, millis);
            }
        };
        NioTransport transport = transport(ops, r -> { factories.incrementAndGet(); return new EmptyHandler(); });
        List<Socket> sockets = new java.util.ArrayList<>();
        Thread closer = new Thread(transport::close);
        try {
            transport.start(); assertTrue(loopsPaused.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 8; i++) sockets.add(connect(transport));
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            closer.start(); assertTrue(joining.await(5, TimeUnit.SECONDS));
            release.countDown(); closer.join(6000); assertFalse(closer.isAlive());
            for (Socket s : sockets) assertEquals(-1, s.getInputStream().read());
            assertEquals(0, factories.get());
            assertEquals(0, transport.usedLineBytes());
        } finally {
            release.countDown(); transport.close(); closer.join(6000);
            for (Socket s : sockets) s.close();
        }
    }

    @Test void shutdownJoinDeadlineIsSharedAndDeferredCleanupCompletesAfterHookRelease() throws Exception {
        CountDownLatch blocked = new CountDownLatch(2), release = new CountDownLatch(1);
        List<Thread> owners = new CopyOnWriteArrayList<>();
        List<Long> joinAllowances = new CopyOnWriteArrayList<>();
        TransportOps ops = new TransportOps() {
            @Override void startThread(Thread t) { owners.add(t); super.startThread(t); }
            @Override int select(Selector selector, long millis) throws IOException {
                if (Thread.currentThread().getName().contains("event-")) {
                    blocked.countDown(); awaitHook(release);
                }
                return super.select(selector, millis);
            }
            @Override void join(Thread thread, long millis) throws InterruptedException {
                joinAllowances.add(millis); super.join(thread, millis);
                // A still-blocked owner models a small scheduling overrun of the timed join.
                if (thread.isAlive()) release.await(20, TimeUnit.MILLISECONDS);
            }
        };
        NioTransport transport = transport(ops, r -> new EmptyHandler());
        try {
            transport.start(); assertTrue(blocked.await(5, TimeUnit.SECONDS));
            long before = System.nanoTime(); transport.close();
            long elapsed = System.nanoTime() - before;
            assertTrue(elapsed < TimeUnit.SECONDS.toNanos(7), "one shared five-second deadline");
            assertTrue(elapsed >= TimeUnit.SECONDS.toNanos(4), "must actually exercise blocked-owner timeout");
            assertEquals(2, owners.stream().filter(Thread::isAlive).count(), "both paused owners remain pending");
            assertTrue(joinAllowances.stream().allMatch(ms -> ms > 0 && ms <= 5000));
        } finally { release.countDown(); transport.close(); }
        for (Thread owner : owners) assertFalse(owner.isAlive());
    }

    private static void awaitHook(CountDownLatch release) throws IOException {
        try {
            if (!release.await(15, TimeUnit.SECONDS)) throw new IOException("test hook release timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException(interrupted);
        }
    }
    @Test void forcedZeroAndPartialWritesResumeWithoutReencodingAndIoFailureClosesWriter() throws Exception {
        for (boolean fail : new boolean[] {false, true}) {
            AtomicInteger writes = new AtomicInteger();
            TransportOps ops = new TransportOps() {
                @Override int write(SocketChannel socket, ByteBuffer src) throws IOException {
                    int call = writes.incrementAndGet();
                    if (call == 1) return 0;
                    if (fail && call == 2) throw new IOException("injected write failure");
                    int limit = src.limit();
                    try { src.limit(src.position() + Math.min(1, src.remaining())); return super.write(socket, src); }
                    finally { src.limit(limit); }
                }
            };
            try (NioTransportSocketTest.Fixture f = new NioTransportSocketTest.Fixture(1, 4, 17, 0, 1, null, ops);
                 Socket socket = f.connect()) {
                NioTransportSocketTest.Peer peer = f.peer(0);
                NioTransportSocketTest.request(socket, 'P', 0);
                if (fail) {
                    assertEquals(-1, socket.getInputStream().read());
                    f.await(() -> peer.closed.get() == 1);
                } else {
                    NioTransportSocketTest.verify(socket, 17);
                    assertTrue(writes.get() >= 18);
                }
                assertEquals(1, peer.encoded.get());
                assertEquals(1, peer.responsesClosed.get());
                f.assertHealthy();
            }
        }
    }
    @Test void selectorOpenFailureAtEachStartupStageClosesEveryOpenedSelector() {
        for (int failAt = 1; failAt <= 3; failAt++) {
            int position = failAt;
            List<Selector> opened = new CopyOnWriteArrayList<>();
            AtomicInteger calls = new AtomicInteger();
            TransportOps ops = new TransportOps() {
                @Override Selector openSelector() throws IOException {
                    if (calls.incrementAndGet() == position) throw new IOException("injected open failure");
                    Selector selector = super.openSelector(); opened.add(selector); return selector;
                }
            };
            NioTransport transport = transport(ops, r -> null);
            try { assertThrows(IOException.class, transport::start); }
            finally { transport.close(); }
            for (Selector selector : opened) assertFalse(selector.isOpen());
        }
    }

    @Test void threadStartupFailureClosesNewAndAlreadyStartedLoopResources() {
        for (int failAt = 1; failAt <= 3; failAt++) {
            int position = failAt;
            List<Selector> opened = new CopyOnWriteArrayList<>();
            List<Thread> threads = new CopyOnWriteArrayList<>();
            AtomicInteger starts = new AtomicInteger();
            TransportOps ops = new TransportOps() {
                @Override Selector openSelector() throws IOException {
                    Selector selector = super.openSelector(); opened.add(selector); return selector;
                }
                @Override void startThread(Thread thread) {
                    threads.add(thread);
                    if (starts.incrementAndGet() == position) throw new IllegalStateException("injected start failure");
                    super.startThread(thread);
                }
            };
            NioTransport transport = transport(ops, r -> null);
            try { assertThrows(IllegalStateException.class, transport::start); }
            finally { transport.close(); }
            for (Thread thread : threads) assertFalse(thread.isAlive());
            for (Selector selector : opened) assertFalse(selector.isOpen());
        }
    }

    @Test void failedPreHandoffClosesSocketAndRestoresAdmission() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TransportOps ops = new TransportOps() {
            @Override void beforeHandoff(SocketChannel socket) throws IOException {
                if (calls.incrementAndGet() == 1) throw new IOException("injected handoff failure");
            }
        };
        AtomicInteger factoryCalls = new AtomicInteger();
        try (NioTransport transport = transport(ops, r -> { factoryCalls.incrementAndGet(); return new EmptyHandler(); })) {
            transport.start();
            try (Socket failed = connect(transport)) { assertEquals(-1, failed.getInputStream().read()); }
            try (Socket good = connect(transport)) {
                good.shutdownOutput(); assertEquals(-1, good.getInputStream().read());
            }
            transport.close();
            assertEquals(1, factoryCalls.get());
            // Coordinator decisions 249acb13db13/d99ac1f2e995 distinguish setup failure from policy refusal.
            assertEquals(0, transport.rejectedConnections());
            assertEquals(0, transport.usedBodyBytes());
            assertEquals(0, transport.usedLineBytes());
        }
    }

    @Test void readIOExceptionOnlyClosesAffectedSocket() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        TransportOps ops = new TransportOps() {
            @Override int read(SocketChannel socket, ByteBuffer dst) throws IOException {
                if (reads.incrementAndGet() == 1) throw new IOException("injected read failure");
                return super.read(socket, dst);
            }
        };
        List<EmptyHandler> handlers = new CopyOnWriteArrayList<>();
        try (NioTransport transport = transport(ops, r -> {
            EmptyHandler handler = new EmptyHandler(); handlers.add(handler); return handler;
        })) {
            transport.start();
            try (Socket bad = connect(transport)) {
                bad.getOutputStream().write(1); assertEquals(-1, bad.getInputStream().read());
            }
            try (Socket good = connect(transport)) {
                good.getOutputStream().write(2); good.shutdownOutput();
                assertEquals(-1, good.getInputStream().read());
            }
            transport.close();
            assertEquals(2, handlers.size());
            for (EmptyHandler handler : handlers) assertEquals(1, handler.closed.get());
        }
    }

    @Test void selectFailureOnAcceptorAndWorkerTriggersWholeTransportCleanup() throws Exception {
        for (boolean acceptor : new boolean[] {true, false}) {
            java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean();
            AtomicInteger injected = new AtomicInteger();
            List<Thread> started = new CopyOnWriteArrayList<>();
            List<Selector> selectors = new CopyOnWriteArrayList<>();
            TransportOps ops = new TransportOps() {
                @Override void startThread(Thread t) { started.add(t); super.startThread(t); }
                @Override Selector openSelector() throws IOException {
                    Selector selector = super.openSelector(); selectors.add(selector); return selector;
                }
                @Override int write(SocketChannel socket, ByteBuffer src) { return 0; }
                @Override int select(Selector selector, long millis) throws IOException {
                    if (armed.get() && Thread.currentThread().getName().contains("acceptor") == acceptor
                            && injected.compareAndSet(0, 1)) {
                        throw new IOException("injected select failure");
                    }
                    return super.select(selector, millis);
                }
            };
            NioTransportSocketTest.Fixture f = new NioTransportSocketTest.Fixture(2, 8, 17, 0,
                    NioTransportSocketTest.BODY * 2L, null, ops);
            try (Socket body = f.connect()) {
                // Factory callbacks run on different workers; establish the first peer before the second connect.
                var b = f.peer(0);
                try (Socket writer = f.connect()) {
                var w = f.peer(1);
                NioTransportSocketTest.request(body, 'B', NioTransportSocketTest.BODY);
                NioTransportSocketTest.request(writer, 'L', 0);
                f.await(() -> b.resources.usedBodyBytes() > 0 && w.writerCalls.get() > 0);
                assertTrue(f.transport.usedLineBytes() > 0);
                armed.set(true);
                for (Selector selector : selectors) selector.wakeup();
                // Join observes autonomous termination; no external close may mask missing shutdown.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                for (Thread t : started) {
                    t.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
                    assertFalse(t.isAlive(), "failure must autonomously stop " + t.getName());
                }
                assertEquals(1, injected.get());
                for (Selector selector : selectors) assertFalse(selector.isOpen());
                assertEquals(1, b.closed.get()); assertEquals(1, w.closed.get());
                assertEquals(1, w.writerClosed.get()); assertEquals(1, w.responsesClosed.get());
                assertEquals(0, f.transport.usedLineBytes()); assertEquals(0, f.transport.usedBodyBytes());
                try (java.net.ServerSocket rebound = new java.net.ServerSocket()) {
                    rebound.bind(f.transport.localAddress());
                }
                f.assertHealthy();
                }
            } finally {
                armed.set(false); f.close();
            }
        }
    }

    @Test void interruptedJoinPreservesInterruptAndSubsequentCloseFinishesCleanup() throws Exception {
        AtomicInteger joins = new AtomicInteger();
        List<Thread> started = new CopyOnWriteArrayList<>();
        TransportOps ops = new TransportOps() {
            @Override void startThread(Thread thread) { started.add(thread); super.startThread(thread); }
            @Override void join(Thread thread, long millis) throws InterruptedException {
                if (joins.incrementAndGet() == 1) throw new InterruptedException("injected join interrupt");
                super.join(thread, millis);
            }
        };
        NioTransport transport = transport(ops, r -> new EmptyHandler());
        try {
            transport.start(); transport.close();
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted(); transport.close();
        }
        for (Thread t : started) assertFalse(t.isAlive());
    }

    private static NioTransport transport(TransportOps ops, ConnectionFactory factory) {
        return new NioTransport(NioTransportSocketTest.settings(2, 8, 17, 0, 1,
                new InetSocketAddress("127.0.0.1", 0)), factory,
                NioTransportSocketTest.registry(), new NioTransportSocketTest.ManualClock(), ops);
    }

    private static Socket connect(NioTransport transport) throws IOException {
        Socket socket = new Socket();
        try {
            socket.setSoTimeout(5000); socket.connect(transport.localAddress(), 5000); return socket;
        } catch (IOException failure) { socket.close(); throw failure; }
    }

    private static final class EmptyHandler implements ConnectionHandler, ProtocolDecoder {
        final AtomicInteger closed = new AtomicInteger();
        public ProtocolDecoder decoder() { return this; }
        public ProtocolEncoder encoder() { return response -> { throw new AssertionError("no response expected"); }; }
        public void append(ByteBuffer input) { input.position(input.limit()); }
        public Command nextCommand() { return null; }
        public Response handle(Command command) { return null; }
        public void close() { closed.incrementAndGet(); }
    }
}

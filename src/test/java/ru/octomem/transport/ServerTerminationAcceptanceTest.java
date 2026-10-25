package ru.octomem.transport;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.util.SystemClock;

/** Independent owner-barrier checks through the existing package-scoped JDK seams. */
@Timeout(20)
class ServerTerminationAcceptanceTest {
    private NioTransport transport(TransportOps ops) {
        return new NioTransport(new TransportSettings(new InetSocketAddress("127.0.0.1", 0),
                1, 2, 512, 1, 0, 1024, 512, 4096, 128), resources -> {
                    throw new IllegalStateException("No commands used by barrier tests");
                }, new AtomicStatsRegistry(Map.of("pid", "1", "version", "test", "pointer_size", "64",
                        "threads", "1", "limit_maxbytes", "4096")), new SystemClock(), ops);
    }

    @Test void stopWaitRejectsAcceptorSelfAndAutonomousFailureIsObservable() throws Exception {
        AtomicReference<NioTransport> owner = new AtomicReference<>();
        CompletableFuture<Throwable> self = new CompletableFuture<>();
        TransportOps ops = new TransportOps() {
            @Override int select(Selector selector, long timeout) throws IOException {
                if (Thread.currentThread().getName().equals("octomem-acceptor")) {
                    try { owner.get().awaitStopRequested(); self.complete(null); }
                    catch (Throwable expected) { self.complete(expected); }
                    throw new IOException("Injected owner failure");
                }
                return super.select(selector, timeout);
            }
        };
        try (NioTransport transport = transport(ops)) {
            owner.set(transport); transport.start();
            assertInstanceOf(IllegalStateException.class, self.get(3, TimeUnit.SECONDS));
            assertTrue(transport.awaitStopRequested());
            transport.awaitTermination();
        }
    }

    @Test void stopWaitRejectsEventLoopSelf() throws Exception {
        AtomicReference<NioTransport> owner = new AtomicReference<>();
        CompletableFuture<Throwable> self = new CompletableFuture<>();
        TransportOps ops = new TransportOps() {
            @Override int select(Selector selector, long timeout) throws IOException {
                if (Thread.currentThread().getName().startsWith("octomem-event-")) {
                    try { owner.get().awaitStopRequested(); self.complete(null); }
                    catch (Throwable expected) { self.complete(expected); }
                    throw new IOException("Injected event owner failure");
                }
                return super.select(selector, timeout);
            }
        };
        try (NioTransport transport = transport(ops)) {
            owner.set(transport); transport.start();
            assertInstanceOf(IllegalStateException.class, self.get(3, TimeUnit.SECONDS));
            assertTrue(transport.awaitStopRequested()); transport.awaitTermination();
        }
    }

    @Test void terminationWaitsBeyondBoundedCloseAndInterruptionDoesNotReleaseOwner() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransportOps ops = new TransportOps() {
            @Override int select(Selector selector, long timeout) throws IOException {
                if (Thread.currentThread().getName().startsWith("octomem-event-")) {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException e) { throw new IOException(e); }
                }
                return super.select(selector, timeout);
            }
        };
        NioTransport transport = transport(ops);
        try {
            transport.start(); assertTrue(entered.await(3, TimeUnit.SECONDS));
            transport.close(); // Real five-second bounded join must not imply owner termination.
            FutureTask<Void> wait = new FutureTask<>(() -> { transport.awaitTermination(); return null; });
            Thread waiter = Thread.ofPlatform().daemon().start(wait);
            try {
                assertThrows(TimeoutException.class, () -> wait.get(100, TimeUnit.MILLISECONDS));
                waiter.interrupt();
                ExecutionException interrupted = assertThrows(ExecutionException.class, () -> wait.get(3, TimeUnit.SECONDS));
                assertInstanceOf(InterruptedException.class, interrupted.getCause());
                assertFalse(transport.awaitStopRequested());
            } finally { release.countDown(); waiter.join(3000); }
            transport.awaitTermination();
        } finally { release.countDown(); transport.close(); transport.awaitTermination(); }
    }

    @Test void startupFailureWithNewThreadsDoesNotWaitForeverOrStartThem() throws Exception {
        java.util.List<Thread> starts = new java.util.ArrayList<>();
        TransportOps ops = new TransportOps() {
            @Override void startThread(Thread thread) { starts.add(thread); throw new IllegalStateException("Injected before start"); }
        };
        try (NioTransport transport = transport(ops)) {
            assertThrows(IllegalStateException.class, transport::start);
            transport.awaitTermination();
            assertEquals(1, starts.size());
            assertEquals(Thread.State.NEW, starts.getFirst().getState());
        }
    }

    @Test void shutdownWinsFailureRaisedByCancelledHandoff() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        TransportOps ops = new TransportOps() {
            @Override void beforeHandoff(SocketChannel channel) throws IOException {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { throw new IOException(e); }
                throw new IOException("Failure after explicit stop");
            }
            @Override void join(Thread thread, long millis) { }
        };
        try (NioTransport transport = transport(ops)) {
            transport.start();
            try (Socket socket = new Socket()) {
                socket.connect(transport.localAddress(), 2000);
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                transport.close(); release.countDown(); transport.awaitTermination();
                assertFalse(transport.awaitStopRequested());
                assertEquals(0, transport.usedControlBytes());
            } finally { release.countDown(); }
        }
    }

    @Test void handoffDuringStoppingBeforeLoopStopRejectsAndReleasesAdmission() throws Exception {
        CountDownLatch handoff = new CountDownLatch(1), allowHandoff = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1), allowWakeup = new CountDownLatch(1);
        var selectorNumber = new java.util.concurrent.atomic.AtomicInteger();
        TransportOps ops = new TransportOps() {
            @Override Selector openSelector() throws IOException {
                Selector delegate = Selector.open();
                if (selectorNumber.getAndIncrement() != 0) return delegate;
                return new java.nio.channels.spi.AbstractSelector(delegate.provider()) {
                    protected java.nio.channels.SelectionKey register(java.nio.channels.spi.AbstractSelectableChannel channel,
                            int operations, Object attachment) {
                        try { return channel.register(delegate, operations, attachment); }
                        catch (java.nio.channels.ClosedChannelException e) { throw new java.io.UncheckedIOException(e); }
                    }
                    protected void implCloseSelector() throws IOException { delegate.close(); }
                    public java.util.Set<java.nio.channels.SelectionKey> keys() { return delegate.keys(); }
                    public java.util.Set<java.nio.channels.SelectionKey> selectedKeys() { return delegate.selectedKeys(); }
                    public int selectNow() throws IOException { return delegate.selectNow(); }
                    public int select(long timeout) throws IOException { return delegate.select(timeout); }
                    public int select() throws IOException { return delegate.select(); }
                    public Selector wakeup() {
                        if (Thread.currentThread().getName().equals("independent-close-gate")) {
                            stopped.countDown();
                            try {
                                if (!allowWakeup.await(5, TimeUnit.SECONDS)) throw new AssertionError("Wakeup gate timed out");
                            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                        }
                        delegate.wakeup(); return this;
                    }
                };
            }
            @Override void beforeHandoff(SocketChannel channel) throws IOException {
                handoff.countDown();
                try { if (!allowHandoff.await(5, TimeUnit.SECONDS)) throw new IOException("Handoff gate timed out"); }
                catch (InterruptedException e) { throw new IOException(e); }
            }
        };
        try (NioTransport transport = transport(ops)) {
            transport.start();
            try (Socket socket = new Socket()) {
                socket.connect(transport.localAddress(), 2000); socket.setSoTimeout(3000);
                assertTrue(handoff.await(3, TimeUnit.SECONDS));
                FutureTask<Void> close = new FutureTask<>(() -> { transport.close(); return null; });
                Thread closer = Thread.ofPlatform().name("independent-close-gate").daemon().start(close);
                try {
                    assertTrue(stopped.await(3, TimeUnit.SECONDS));
                    allowHandoff.countDown();
                    assertEquals(-1, socket.getInputStream().read());
                    allowWakeup.countDown(); close.get(5, TimeUnit.SECONDS);
                    transport.awaitTermination();
                    assertEquals(0, transport.usedControlBytes());
                } finally { allowHandoff.countDown(); allowWakeup.countDown(); closer.join(5000); }
            } finally { allowHandoff.countDown(); allowWakeup.countDown(); }
        }
    }
}

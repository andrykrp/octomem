import java.io.PrintWriter;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

/** Standalone JDK 25 risk probe, not a cache implementation or release test. */
public final class Probe {
    static final int PAGE = 1024 * 1024;
    static final int CAP = 64;
    static final long SEED = 0x500125L;
    static volatile long blackhole;

    /** Owns page admission and recycling; monitor never encloses network IO. */
    static final class Pool implements AutoCloseable {
        final boolean ffm;
        final int cap;
        final ArrayDeque<ByteBuffer> free = new ArrayDeque<>();
        final ArrayList<Version> active = new ArrayList<>();
        int allocated, peak, reclaimed, reused;
        boolean stopped;

        Pool(boolean ffm) { this(ffm, CAP); }
        Pool(boolean ffm, int cap) { this.ffm = ffm; this.cap = cap; }

        synchronized Version allocate() {
            if (stopped) throw new IllegalStateException("stopped");
            if (free.isEmpty() && allocated == cap) return null;
            Arena arena = null;
            MemorySegment segment = null;
            ByteBuffer bytes;
            if (!free.isEmpty()) {
                bytes = free.removeFirst();
                reused++;
            } else {
                if (ffm) {
                    arena = Arena.ofShared();
                    try {
                        segment = arena.allocate(PAGE, 8);
                        bytes = segment.asByteBuffer();
                    } catch (Throwable failure) {
                        arena.close();
                        throw failure;
                    }
                } else {
                    bytes = ByteBuffer.allocateDirect(PAGE);
                }
                allocated++;
                peak = Math.max(peak, allocated);
            }
            Version version = new Version(this, arena, segment, bytes);
            active.add(version);
            return version;
        }

        synchronized void reclaim(Version version) {
            require(active.remove(version), "reclaimed exactly once");
            if (version.arena != null) {
                version.arena.close();
                allocated--;
            } else {
                // Keep the native allocation charged even when its bytes are reusable.
                free.addLast(version.bytes);
            }
            reclaimed++;
        }

        /** Stops admission, then retires owners without revoking outstanding leases. */
        public synchronized void close() {
            stopped = true;
            for (Version version : active.toArray(Version[]::new)) version.retire();
            // Direct pages remain strongly held and charged until this Pool is unreachable.
        }
    }

    /** Atomic retired bit plus pin count prevents retire/pin ABA across page reuse. */
    static final class Version {
        final Pool pool;
        final Arena arena;
        final MemorySegment segment;
        final ByteBuffer bytes;
        final AtomicInteger state = new AtomicInteger();

        Version(Pool pool, Arena arena, MemorySegment segment, ByteBuffer bytes) {
            this.pool = pool; this.arena = arena; this.segment = segment; this.bytes = bytes;
        }

        Lease pin() {
            for (;;) {
                int before = state.get();
                if (before < 0) return null;
                if (before == Integer.MAX_VALUE) throw new IllegalStateException("pin overflow");
                if (state.compareAndSet(before, before + 1)) return new Lease(this);
            }
        }

        void retire() {
            int before = state.getAndUpdate(value -> value | Integer.MIN_VALUE);
            if (before == 0) pool.reclaim(this);
        }

        void release() {
            if (state.decrementAndGet() == Integer.MIN_VALUE) pool.reclaim(this);
        }
    }

    /** Idempotent lease; callers must not race reads against close on the same lease. */
    static final class Lease implements AutoCloseable {
        final Version version;
        final AtomicBoolean closed = new AtomicBoolean();
        Lease(Version version) { this.version = version; }
        public void close() { if (closed.compareAndSet(false, true)) version.release(); }
    }

    /** Exactly the ADR-016 six-array shape with cursor state inside its wrapper. */
    static final class Snapshot {
        final Object[] items;
        final int[] firstOccurrenceOrder, segmentIds, dedupOrder;
        final byte[] keyLengths, keys;
        long capturedClock, charge;
        int cursor;
        boolean closed;
        Snapshot(int n) {
            items = new Object[n]; firstOccurrenceOrder = new int[n];
            segmentIds = new int[n]; dedupOrder = new int[n];
            keyLengths = new byte[n]; keys = new byte[Math.multiplyExact(250, n)];
            charge = Math.addExact(512L, Math.multiplyExact(512L, n));
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void await(CountDownLatch latch) throws InterruptedException {
        require(latch.await(3, TimeUnit.SECONDS), "bounded latch deadline");
    }

    static long cpu() {
        return ProcessHandle.current().info().totalCpuDuration().orElseThrow().toNanos();
    }

    static void environment(PrintWriter out) {
        out.println("timestamp=" + Instant.now());
        for (String key : new String[]{"java.version", "java.vendor", "java.vm.name", "java.vm.version", "java.home", "os.name", "os.version", "os.arch"})
            out.println(key + "=" + System.getProperty(key));
        out.println("flags=" + ManagementFactory.getRuntimeMXBean().getInputArguments());
        out.println("pid=" + ProcessHandle.current().pid() + " processors=" + Runtime.getRuntime().availableProcessors());
        out.println("seed=" + SEED + " pageBytes=" + PAGE + " nativePageCap=" + (long) PAGE * CAP);
    }

    static void lifecycle(boolean ffm, PrintWriter out) throws Exception {
        Pool pool = new Pool(ffm);
        Version original = pool.allocate();
        original.bytes.putLong(0, 0x123456789abcdefL);
        AtomicReference<Version> published = new AtomicReference<>(original);
        CountDownLatch pinned = new CountDownLatch(1), replaced = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = Thread.ofPlatform().start(() -> {
            try (Lease lease = published.get().pin()) {
                require(lease != null, "initial pin");
                pinned.countDown(); await(replaced);
                require(lease.version.bytes.getLong(0) == 0x123456789abcdefL, "old bytes immutable through shutdown");
            } catch (Throwable error) { failure.set(error); pinned.countDown(); }
        });
        await(pinned);
        Version next = pool.allocate(); next.bytes.putLong(0, 42);
        published.set(next); original.retire(); original.retire();
        require(original.pin() == null, "cannot pin retired version");
        require(pool.reclaimed == 0 && pool.allocated == 2, "pinned version remains charged");
        pool.close(); pool.close();
        require(pool.active.size() == 1, "shutdown retains exactly pinned page");
        require(ffm ? original.segment.scope().isAlive() : pool.free.size() == 1, "old page not closed or reused");
        replaced.countDown(); reader.join(4000);
        require(!reader.isAlive() && failure.get() == null, "reader completes: " + failure.get());
        require(pool.active.isEmpty() && pool.reclaimed == 2, "shutdown final release");
        if (ffm) {
            require(!original.segment.scope().isAlive() && pool.allocated == 0, "arena closure deterministic");
            try { original.bytes.get(0); throw new AssertionError("closed access accepted"); }
            catch (IllegalStateException expected) { }
        } else require(pool.allocated == 2 && pool.free.size() == 2, "direct retained pool charged");
        try { pool.allocate(); throw new AssertionError("allocation after shutdown"); }
        catch (IllegalStateException expected) { }

        // The first direct pool still owns two charged pages; combined cap remains 64 MiB.
        int availablePages = ffm ? CAP : CAP - pool.allocated;
        Pool bounded = new Pool(ffm, availablePages);
        Version[] pages = new Version[availablePages];
        for (int i = 0; i < availablePages; i++) pages[i] = bounded.allocate();
        require(bounded.allocate() == null, "cap denies next page");
        Lease held = pages[0].pin(); pages[0].retire();
        require(bounded.allocate() == null, "pinned replacement OOM preserves capacity");
        held.close(); held.close();
        Version recycled = bounded.allocate();
        require(recycled != null && bounded.peak == availablePages, "release permits admission");
        require(ffm ? !pages[0].segment.scope().isAlive() : recycled.bytes == pages[0].bytes,
                "FFM close or same direct page reused");
        require(pages[0].pin() == null, "old wrapper cannot pin recycled page");
        bounded.close();
        out.println("lifecycle," + (ffm ? "ffm" : "direct") + ",PASS,peakPages=" + bounded.peak
                + ",postShutdownChargedPages=" + bounded.allocated + ",reclaimed=" + bounded.reclaimed);
    }

    static void layout(PrintWriter out) {
        require(SizeAgent.instrumentation != null, "premain instrumentation required");
        out.println("layout,n,wrapper,arrays,payloadEstimate8ByteRefs,actualTotal,logicalCharge,withinCharge");
        for (int n : new int[]{0, 1, 2, 17, 4093, 8191}) {
            Snapshot snapshot = new Snapshot(n);
            long wrapper = SizeAgent.instrumentation.getObjectSize(snapshot), arrays = 0;
            Object[] six = {snapshot.items, snapshot.firstOccurrenceOrder, snapshot.segmentIds,
                    snapshot.dedupOrder, snapshot.keyLengths, snapshot.keys};
            for (Object array : six) arrays += SizeAgent.instrumentation.getObjectSize(array);
            require(wrapper <= 128 && arrays <= 384L + 271L * n, "ADR layout model");
            out.printf("layout,%d,%d,%d,%d,%d,%d,%s%n", n, wrapper, arrays, 271L * n,
                    wrapper + arrays, snapshot.charge, wrapper + arrays <= snapshot.charge);
            if (n == 0) out.println("arrayHeaders=" + Arrays.toString(Arrays.stream(six)
                    .mapToLong(SizeAgent.instrumentation::getObjectSize).toArray()));
        }
        out.println("referenceStride16to32=" + (SizeAgent.instrumentation.getObjectSize(new Object[32])
                - SizeAgent.instrumentation.getObjectSize(new Object[16])) / 16);
    }

    static void row(PrintWriter out, String backend, String operation, int size, int repetition,
                    long operations, long elapsed, long cpuDelta, long[] samples, int count) {
        Arrays.sort(samples, 0, count);
        long p50 = count == 0 ? -1 : samples[(count - 1) / 2];
        long p99 = count == 0 ? -1 : samples[(int) Math.ceil(count * .99) - 1];
        out.printf(Locale.ROOT, "metric,%s,%s,%d,%d,%d,%d,%.3f,%d,%d,%d,%d,%d%n", backend, operation,
                size, repetition, operations, elapsed, operations * 1e9 / elapsed, p50, p99,
                cpuDelta, count, blackhole);
        out.flush();
    }

    static void memory(boolean ffm, int repetition, PrintWriter out) {
        Pool pool = new Pool(ffm);
        Version[] pages = new Version[CAP];
        for (int i = 0; i < CAP; i++) pages[i] = pool.allocate();
        out.println("memoryPhase=" + (ffm ? "ffm" : "direct") + " allocatedBytes=" + (long) pool.allocated * PAGE);
        for (String operation : new String[]{"read", "write", "churn"}) {
            if (operation.equals("churn")) for (Version page : pages) page.retire();
            for (int size : new int[]{64, 1024, 16384, PAGE}) {
                byte[] payload = new byte[size]; new Random(SEED + size).nextBytes(payload);
                if (!operation.equals("churn")) for (Version page : pages) page.bytes.put(0, payload);
                for (int phase = 0; phase < 2; phase++) {
                    Random random = new Random(SEED);
                    long[] samples = new long[65536]; int count = 0;
                    long operations = 0, checksum = 0, cpuStart = cpu(), start = System.nanoTime();
                    long deadline = start + (phase == 0 ? 100_000_000L : 250_000_000L);
                    do {
                        boolean sample = (operations & 15) == 0;
                        long before = sample ? System.nanoTime() : 0;
                        int index = random.nextInt(CAP);
                        if (operation.equals("churn")) {
                            Version version = pool.allocate(); require(version != null, "churn admitted");
                            version.bytes.put(0, payload);
                            try (Lease lease = version.pin()) {
                                version.retire(); checksum += lease.version.bytes.get(size - 1);
                            }
                        } else if (operation.equals("write")) {
                            pages[index].bytes.put(0, payload); checksum += pages[index].bytes.get(size - 1);
                        } else {
                            pages[index].bytes.get(0, payload); checksum += payload[size - 1];
                        }
                        if (sample) {
                            samples[(int) ((operations >>> 4) % samples.length)] = System.nanoTime() - before;
                            count = Math.min(count + 1, samples.length);
                        }
                        operations++;
                    } while (System.nanoTime() < deadline);
                    long elapsed = System.nanoTime() - start, cpuDelta = cpu() - cpuStart;
                    blackhole = checksum;
                    if (phase == 1) row(out, ffm ? "ffm" : "direct", operation, size, repetition,
                            operations, elapsed, cpuDelta, samples, count);
                }
            }
        }
        pool.close();
        out.println("poolEnd=" + (ffm ? "ffm" : "direct") + " peak=" + pool.peak
                + " charged=" + pool.allocated + " reuse=" + pool.reused + " reclaim=" + pool.reclaimed);
    }

    static void transport(PrintWriter out) throws Exception {
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (Selector selector = Selector.open(); ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            server.configureBlocking(false); server.register(selector, SelectionKey.OP_ACCEPT);
            Thread eventLoop = Thread.ofPlatform().start(() -> {
                try {
                    while (!stopped.get()) {
                        selector.select(100);
                        Iterator<SelectionKey> selected = selector.selectedKeys().iterator();
                        while (selected.hasNext()) {
                            SelectionKey key = selected.next(); selected.remove();
                            if (key.isAcceptable()) {
                                SocketChannel socket = server.accept();
                                if (socket != null) {
                                    socket.configureBlocking(false); socket.socket().setTcpNoDelay(true);
                                    socket.register(selector, SelectionKey.OP_READ, ByteBuffer.allocate(512));
                                }
                            } else {
                                SocketChannel socket = (SocketChannel) key.channel();
                                ByteBuffer lane = (ByteBuffer) key.attachment();
                                if (key.isReadable()) {
                                    int read = socket.read(lane);
                                    if (read < 0) { socket.close(); continue; }
                                    if (read > 0) { lane.flip(); key.interestOps(SelectionKey.OP_WRITE); }
                                } else if (key.isWritable()) {
                                    socket.write(lane);
                                    if (!lane.hasRemaining()) { lane.clear(); key.interestOps(SelectionKey.OP_READ); }
                                }
                            }
                        }
                    }
                } catch (Throwable error) { failure.set(error); }
            });
            try (Socket client = new Socket()) {
                client.connect(server.getLocalAddress(), 2000); client.setSoTimeout(2000); client.setTcpNoDelay(true);
                for (int size : new int[]{64, 1024, 16384, PAGE}) {
                    byte[] payload = new byte[size], response = new byte[size];
                    new Random(SEED + size).nextBytes(payload);
                    for (int repetition = 0; repetition <= 3; repetition++) {
                        long[] samples = new long[65536]; int count = 0;
                        long operations = 0, cpuStart = cpu(), start = System.nanoTime();
                        long deadline = start + (repetition == 0 ? 100_000_000L : 250_000_000L);
                        do {
                            long before = System.nanoTime();
                            int position = 0;
                            while (position < size) {
                                // A bounded client also drains each lane before offering the next.
                                int end = Math.min(size, position + 512);
                                client.getOutputStream().write(payload, position, end - position);
                                while (position < end) {
                                    int read = client.getInputStream().read(response, position, end - position);
                                    require(read > 0, "echo complete"); position += read;
                                }
                            }
                            require(Arrays.equals(payload, response), "echo bytes");
                            if (count < samples.length) samples[count++] = System.nanoTime() - before;
                            operations++;
                        } while (System.nanoTime() < deadline);
                        long elapsed = System.nanoTime() - start;
                        if (repetition > 0) row(out, "selector512", "echo", size, repetition,
                                operations, elapsed, cpu() - cpuStart, samples, count);
                    }
                }
            } finally {
                stopped.set(true); selector.wakeup(); eventLoop.join(3000);
                require(!eventLoop.isAlive(), "selector shutdown");
                for (SelectionKey key : selector.keys()) key.channel().close();
            }
            require(failure.get() == null, "selector failure: " + failure.get());
            out.println("transport,PASS,oneConnection,maxLaneBytes=512,clientStopAndWaitChunkBytes=512,errors=0,relativeM1M2M3=INCONCLUSIVE");
        }
    }

    static void jfr(Path input, PrintWriter out) throws Exception {
        long allocationSamples = 0, allocationWeight = 0, gc = 0, gcNanos = 0, allocationEvents = 0;
        try (RecordingFile recording = new RecordingFile(input)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String name = event.getEventType().getName();
                if (name.equals("jdk.ObjectAllocationSample")) { allocationSamples++; allocationWeight += event.getLong("weight"); }
                if (name.equals("jdk.ObjectAllocationInNewTLAB") || name.equals("jdk.ObjectAllocationOutsideTLAB")) allocationEvents++;
                if (name.equals("jdk.GarbageCollection")) { gc++; gcNanos += event.getDuration().toNanos(); }
            }
        }
        out.println("jfr=" + input + " allocationSamples=" + allocationSamples + " sampledWeightBytes="
                + allocationWeight + " explicitAllocationEvents=" + allocationEvents + " gcCount=" + gc + " gcDurationNs=" + gcNanos);
    }

    /** Runs one explicitly selected, bounded probe mode and writes raw evidence. */
    public static void main(String[] args) throws Exception {
        require(Runtime.version().feature() == 25, "JDK25 only");
        Thread watchdog = Thread.ofPlatform().daemon().start(() -> {
            try { Thread.sleep(55_000); System.err.println("P001 watchdog exceeded 55s"); Runtime.getRuntime().halt(124); }
            catch (InterruptedException expected) { }
        });
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[1])))) {
            environment(out);
            out.println("type,backend,operation,sizeBytes,repetition,operations,elapsedNs,opsPerSecond,p50Ns,p99Ns,processCpuNs,samples,checksum");
            switch (args[0]) {
                case "lifecycle" -> lifecycle(Boolean.parseBoolean(args[2]), out);
                case "layout" -> layout(out);
                case "memory" -> memory(Boolean.parseBoolean(args[2]), Integer.parseInt(args[3]), out);
                case "transport" -> transport(out);
                case "jfr" -> jfr(Path.of(args[2]), out);
                default -> throw new IllegalArgumentException("mode");
            }
        } finally { watchdog.interrupt(); }
    }
}

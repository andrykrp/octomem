package ru.octomem.instrumentation;

import java.nio.ByteBuffer;
import java.util.AbstractList;
import java.util.List;
import ru.octomem.engine.RetrievalSnapshot;
import ru.octomem.protocol.ChunkSink;
import ru.octomem.protocol.ProtocolEncoder;
import ru.octomem.protocol.Response;
import ru.octomem.protocol.ResponseKind;
import ru.octomem.protocol.ResponseWriter;
import ru.octomem.protocol.TextProtocolEncoder;
import ru.octomem.protocol.WriteStatus;

/**
 * P3: whole-response bounded-materialization proof.
 *
 * <p>Streams a statistics response whose formatted form is far larger than the
 * probe's heap bound. The producer list generates one STAT line on demand, so a
 * successful completion proves the encoder never materialized the whole response:
 * only the 256-byte writer scratch, one sink chunk, and one line String exist at
 * a time.
 */
public final class P3WholeResponse {
    private P3WholeResponse() { }

    public static void main(String[] args) throws Exception {
        ProbeSupport.printEnvironment();
        int lineCount = args.length > 0 ? Integer.parseInt(args[0]) : 4_000_000;
        int maxChunkBytes = 65536;
        int sinkCapacity = 65536;
        int lineChars = 60;
        long projected = (long) lineCount * (lineChars + 2) + 5;

        ProbeSupport.measure("projectedFormattedBytes", projected);
        ProbeSupport.measure("maxChunkBytes.config", maxChunkBytes);
        ProbeSupport.measure("sinkCapacity", sinkCapacity);
        ProbeSupport.measure("writerScratchBytes", 256);
        ProbeSupport.note("heapBound", "-Xmx256m; projection exceeds it for the default line count");

        LazyStatsResponse response = new LazyStatsResponse(lineCount, lineChars);
        ProtocolEncoder encoder = new TextProtocolEncoder(maxChunkBytes);
        CountingSink sink = new CountingSink(sinkCapacity);

        long heapBefore = ProbeSupport.heap().getUsed();
        ResponseWriter writer = encoder.encode(response);
        long emitted = 0;
        int calls = 0;
        long maxChunk = 0;
        long peakHeap = heapBefore;
        while (true) {
            long before = sink.total;
            WriteStatus status = writer.writeMore(sink);
            long chunk = sink.total - before;
            maxChunk = Math.max(maxChunk, chunk);
            calls++;
            if (status == WriteStatus.COMPLETE) {
                break;
            }
            if ((calls & 0x3FF) == 0) {
                peakHeap = Math.max(peakHeap, ProbeSupport.heap().getUsed());
            }
            if (calls > 10_000_000) {
                throw new IllegalStateException("writeMore did not make progress");
            }
        }
        emitted = sink.total;
        long heapAfter = ProbeSupport.heap().getUsed();

        ProbeSupport.measure("emittedBytes", emitted);
        ProbeSupport.measure("writeMore.calls", calls);
        ProbeSupport.measure("maxSingleChunkBytes", maxChunk);
        ProbeSupport.measure("maxChunkWithinConfiguredBound",
                maxChunk <= maxChunkBytes ? 1 : 0);
        ProbeSupport.measure("maxChunkWithinSinkCapacity",
                maxChunk <= sinkCapacity ? 1 : 0);
        ProbeSupport.measure("heapUsed.beforeStream", heapBefore);
        ProbeSupport.measure("heapUsed.afterStream", heapAfter);
        ProbeSupport.measure("heapUsed.peakSampled", peakHeap);
        ProbeSupport.measure("heapUsed.peakDelta", peakHeap - heapBefore);
        ProbeSupport.measure("response.closeCalled", response.closeCalled ? 1 : 0);
        ProbeSupport.measure("response.linesGenerated", response.generated);
        ProbeSupport.note("generatedEqualsLineCount",
                Boolean.toString(response.generated == lineCount));
        ProbeSupport.note("maBoundary", "completed under -Xmx256m with projected formatted bytes above the bound");

        ProbeSupport.section("cancellation");
        LazyStatsResponse second = new LazyStatsResponse(lineCount, lineChars);
        ResponseWriter cancelled = encoder.encode(second);
        CountingSink limited = new CountingSink(sinkCapacity);
        cancelled.writeMore(limited);
        ProbeSupport.measure("cancel.partialBytes", limited.total);
        cancelled.close();
        ProbeSupport.measure("cancel.closeCalled", second.closeCalled ? 1 : 0);
        ProbeSupport.measure("cancel.secondWriteMoreThrows", throwsAfterClose(cancelled, limited) ? 1 : 0);
    }

    static boolean throwsAfterClose(ResponseWriter writer, ChunkSink sink) {
        try {
            writer.writeMore(sink);
            return false;
        } catch (IllegalStateException expected) {
            return true;
        }
    }

    /** Response whose ordered STAT lines are generated lazily on access. */
    static final class LazyStatsResponse implements Response {
        private final int count;
        private final int lineChars;
        private final List<String> lines;
        long generated;
        boolean closeCalled;

        LazyStatsResponse(int count, int lineChars) {
            this.count = count;
            this.lineChars = lineChars;
            this.lines = new AbstractList<>() {
                @Override public String get(int index) {
                    if (index < 0 || index >= count) {
                        throw new IndexOutOfBoundsException();
                    }
                    generated++;
                    StringBuilder line = new StringBuilder("STAT octomem.synthetic.counter");
                    line.append(index).append(' ');
                    while (line.length() < lineChars) {
                        line.append('0');
                    }
                    return line.toString();
                }

                @Override public int size() { return count; }
            };
        }

        @Override public ResponseKind kind() { return ResponseKind.STATS; }
        @Override public RetrievalSnapshot snapshot() { return null; }
        @Override public boolean includeCas() { return false; }
        @Override public long number() { return 0; }
        @Override public String text() { return null; }
        @Override public List<String> statsLines() { return lines; }

        @Override public void close() {
            closeCalled = true;
        }
    }

    /** Bounded sink that copies nothing and counts bytes; never retains chunks. */
    static final class CountingSink implements ChunkSink {
        private final int capacity;
        long total;
        int maxAccepted;

        CountingSink(int capacity) {
            this.capacity = capacity;
        }

        @Override public int remainingCapacity() { return capacity; }

        @Override public void accept(ByteBuffer chunk) {
            int remaining = chunk.remaining();
            if (remaining > capacity) {
                throw new IllegalArgumentException("chunk exceeds capacity");
            }
            maxAccepted = Math.max(maxAccepted, remaining);
            total += remaining;
            chunk.position(chunk.limit());
        }
    }
}

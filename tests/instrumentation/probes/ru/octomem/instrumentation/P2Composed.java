package ru.octomem.instrumentation;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ru.octomem.cli.CacheServer;
import ru.octomem.config.ConfigLoader;
import ru.octomem.engine.DefaultCacheEngine;
import ru.octomem.stats.SlabClassStats;
import ru.octomem.storage.FfmSlabAllocator;
import ru.octomem.storage.SegmentedItemTable;
import ru.octomem.util.SystemClock;

/**
 * P2: composed-server logical charge accounting versus measured native pages,
 * used blocks and process RSS, driven over real TCP.
 *
 * <p>This probe starts the production composition and sends raw text-protocol
 * commands. Charges are read from public accessors ({@link CacheServer#resourceUsage()})
 * and reconciled against the allocator's per-class geometry. Nothing here is a
 * PASS claim about RSS; the measured RSS is reported as-is with its scope.
 */
public final class P2Composed {
    private P2Composed() { }

    public static void main(String[] args) throws Exception {
        ProbeSupport.printEnvironment();
        ServerConfigValues values = ServerConfigValues.defaults();
        var config = ConfigLoader.load(null, values.map());
        var bind = new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 0);

        ProbeSupport.section("baseline");
        ProbeSupport.measure("baseline.heapUsed", ProbeSupport.settledHeapUsed());
        ProbeSupport.measure("baseline.rss", ProbeSupport.residentSetBytes());

        CacheServer server = new CacheServer(config, bind, new SystemClock());
        ProbeSupport.section("constructedIdle");
        ProbeSupport.measure("constructed.rss", ProbeSupport.residentSetBytes());
        ProbeSupport.measure("constructed.retainedHeap", HeapAgent.retained(server).bytes());
        printUsage("constructed", server);

        server.start();
        var address = server.localAddress();
        ProbeSupport.note("boundAddress", address.getAddress().getHostAddress() + ":" + address.getPort());
        ProbeSupport.measure("started.rss", ProbeSupport.residentSetBytes());

        int connections = 3;
        List<Socket> clients = new ArrayList<>();
        for (int i = 0; i < connections; i++) {
            Socket socket = new Socket();
            socket.connect(address, 5000);
            socket.setSoTimeout(10000);
            socket.setTcpNoDelay(true);
            clients.add(socket);
            String version = exchange(socket, "version\r\n", "\r\n");
            ProbeSupport.note("version.reply." + i, version.trim());
        }
        Thread.sleep(300);
        ProbeSupport.section("idleConnections");
        printUsage("idle", server);
        ProbeSupport.measure("idle.expectedControlBytes", 10816L * connections);
        ProbeSupport.measure("idle.expectedLineBytes", 16640L * connections);

        ProbeSupport.section("pendingBodyReservation");
        int payload = 1024 * 1024 - 64;
        Socket bodyClient = clients.get(2);
        OutputStream bodyOut = bodyClient.getOutputStream();
        bodyOut.write(("set big 0 0 " + payload + " noreply\r\n").getBytes(StandardCharsets.US_ASCII));
        bodyOut.flush();
        Thread.sleep(300);
        printUsage("bodyPending", server);
        ProbeSupport.measure("bodyPending.expectedBodyBytes", (long) payload + 32);
        byte[] chunk = new byte[8192];
        long remaining = payload;
        while (remaining > 0) {
            int count = (int) Math.min(chunk.length, remaining);
            bodyOut.write(chunk, 0, count);
            remaining -= count;
        }
        bodyOut.write('\r');
        bodyOut.write('\n');
        bodyOut.flush();
        Thread.sleep(300);
        printUsage("bodyDrained", server);

        ProbeSupport.section("stores");
        Socket storeClient = clients.get(0);
        OutputStream storeOut = storeClient.getOutputStream();
        int small = 128;
        int medium = 64;
        int large = 8;
        for (int i = 0; i < small; i++) {
            writeSet(storeOut, "k-small-" + pad(i), 50);
        }
        for (int i = 0; i < medium; i++) {
            writeSet(storeOut, "k-medium-" + pad(i), 2000);
        }
        for (int i = 0; i < large; i++) {
            writeSet(storeOut, "k-large-" + pad(i), 500000);
        }
        storeOut.flush();
        Thread.sleep(500);
        printUsage("afterStores", server);

        ProbeSupport.section("reconciliation");
        FfmSlabAllocator allocator = (FfmSlabAllocator) field(server, "allocator");
        SegmentedItemTable table = (SegmentedItemTable) field(field(server, "engine"), "table");
        long predictedUsed = 0;
        long predictedTotal = 0;
        long totalChunks = 0;
        long usedChunks = 0;
        for (int id = 0; id < allocator.classCount(); id++) {
            SlabClassStats stats = allocator.classStats(id);
            if (stats.totalPages() == 0) {
                continue;
            }
            long pageBytes = Math.max(1024L * 1024L, stats.chunkSize());
            predictedUsed += stats.usedChunks() * (long) stats.chunkSize();
            predictedTotal += stats.totalPages() * pageBytes;
            totalChunks += stats.totalChunks();
            usedChunks += stats.usedChunks();
            ProbeSupport.measure("class." + id + ".chunkSize", stats.chunkSize());
            ProbeSupport.measure("class." + id + ".totalPages", stats.totalPages());
            ProbeSupport.measure("class." + id + ".usedChunks", stats.usedChunks());
            ProbeSupport.measure("class." + id + ".freeChunks", stats.freeChunks());
        }
        ProbeSupport.measure("native.usedMemory.logical", allocator.usedMemory());
        ProbeSupport.measure("native.predictedUsedFromPages", predictedUsed);
        ProbeSupport.measure("native.totalMemory.logical", allocator.totalMemory());
        ProbeSupport.measure("native.predictedTotalFromPages", predictedTotal);
        ProbeSupport.measure("native.totalChunks", totalChunks);
        ProbeSupport.measure("native.usedChunks", usedChunks);
        ProbeSupport.note("native.usedMatches",
                Boolean.toString(allocator.usedMemory() == predictedUsed));
        ProbeSupport.note("native.totalMatches",
                Boolean.toString(allocator.totalMemory() == predictedTotal));

        DefaultCacheEngine engine = (DefaultCacheEngine) field(server, "engine");
        ProbeSupport.measure("engine.snapshotMetadataUsed.logical", engine.snapshotMetadataUsedBytes());
        ProbeSupport.measure("engine.serviceMetadataUsed.logical", engine.serviceMetadataUsedBytes());
        ProbeSupport.measure("engine.expectedServiceBytes", 512L * (small + medium + large + 1));
        ProbeSupport.note("service.expectedScope",
                "512 per live published record including the pre-body 'big' record (one per record)");
        ProbeSupport.measure("table.metadataUsed.logical", table.metadataUsedBytes());
        ProbeSupport.note("service.scope",
                "512 per published record; pending/retired-retained records also charge until released");

        ProbeSupport.section("rssAfterLoad");
        ProbeSupport.measure("loaded.rss", ProbeSupport.residentSetBytes());
        ProbeSupport.measure("loaded.heapUsed", ProbeSupport.heap().getUsed());
        ProbeSupport.measure("loaded.committedHeap", ProbeSupport.heap().getCommitted());

        ProbeSupport.section("statsResponse");
        long statsBytes = countResponse(storeClient, "stats\r\n", "END\r\n");
        ProbeSupport.measure("statsResponse.bytes", statsBytes);
        long statsItemsBytes = countResponse(storeClient, "stats items\r\n", "END\r\n");
        ProbeSupport.measure("statsItemsResponse.bytes", statsItemsBytes);
        long statsSlabsBytes = countResponse(storeClient, "stats slabs\r\n", "END\r\n");
        ProbeSupport.measure("statsSlabsResponse.bytes", statsSlabsBytes);

        ProbeSupport.section("closeAndFinal");
        for (Socket socket : clients) {
            socket.close();
        }
        server.close();
        Thread.sleep(300);
        printUsage("closed", server);
        ProbeSupport.measure("closed.rss", ProbeSupport.residentSetBytes());
        ProbeSupport.measure("closed.heapUsed", ProbeSupport.settledHeapUsed());
    }

    static void writeSet(OutputStream out, String key, int valueBytes) throws Exception {
        String header = "set " + key + " 0 0 " + valueBytes + " noreply\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        byte[] body = new byte[valueBytes];
        out.write(body, 0, valueBytes);
        out.write('\r');
        out.write('\n');
    }

    static String pad(int index) {
        return String.format("%04d", index);
    }

    static void printUsage(String label, CacheServer server) {
        var usage = server.resourceUsage();
        ProbeSupport.measure(label + ".controlBytes", usage.controlBytes());
        ProbeSupport.measure(label + ".lineBytes", usage.lineBytes());
        ProbeSupport.measure(label + ".bodyBytes", usage.bodyBytes());
        ProbeSupport.measure(label + ".nativeTotalBytes", usage.nativeTotalBytes());
        ProbeSupport.measure(label + ".nativeUsedBytes", usage.nativeUsedBytes());
        ProbeSupport.measure(label + ".snapshotBytes", usage.snapshotBytes());
        ProbeSupport.measure(label + ".serviceBytes", usage.serviceBytes());
        ProbeSupport.measure(label + ".currentConnections", usage.currentConnections());
        ProbeSupport.measure(label + ".rejectedConnections", usage.rejectedConnections());
    }

    static String exchange(Socket socket, String command, String terminator) throws Exception {
        OutputStream out = socket.getOutputStream();
        out.write(command.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return readUntil(socket, terminator);
    }

    static long countResponse(Socket socket, String command, String terminator) throws Exception {
        return exchange(socket, command, terminator).length();
    }

    /** Reads until the terminator appears, returning everything consumed. */
    static String readUntil(Socket socket, String terminator) throws Exception {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] needle = terminator.getBytes(StandardCharsets.US_ASCII);
        int matched = 0;
        while (true) {
            int next = in.read();
            if (next < 0) {
                throw new IllegalStateException("connection closed before terminator");
            }
            buffer.write(next);
            if (next == needle[matched]) {
                matched++;
                if (matched == needle.length) {
                    return buffer.toString(StandardCharsets.US_ASCII);
                }
            } else {
                matched = next == needle[0] ? 1 : 0;
            }
            if (buffer.size() > 64 * 1024 * 1024) {
                throw new IllegalStateException("response exceeded probe bound");
            }
        }
    }

    static Object field(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // continue
            }
        }
        throw new NoSuchFieldException(name + " on " + target.getClass().getName());
    }

    /** Explicit probe configuration; no production file is read. */
    static final class ServerConfigValues {
        private ServerConfigValues() { }

        static ServerConfigValues defaults() {
            return new ServerConfigValues();
        }

        Map<String, String> map() {
            Map<String, String> values = new java.util.LinkedHashMap<>();
            values.put("listen.address", "127.0.0.1");
            values.put("listen.port", "11211");
            values.put("memory.limit", "67108864");
            values.put("item.maxSize", "1048576");
            values.put("connections.max", "16");
            values.put("transport.workers", "2");
            values.put("protocol.maxLineBytes", "8192");
            values.put("protocol.maxChunkBytes", "65536");
            values.put("connections.pendingWriteBytes", "8388608");
            values.put("connections.maxInflightCommands", "2");
            values.put("connections.idleTimeoutSeconds", "0");
            values.put("expiry.reclaimIntervalSeconds", "3600");
            values.put("log.level", "info");
            return values;
        }
    }
}

package ru.octomem.server;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Runs the packaged entry point, retaining ownership of every child process. */
@Timeout(30)
class CacheServerProcessIT {
    @TempDir Path temporary;

    private Process launch(Path log, String... args) throws IOException {
        Path jar = Path.of("target", "octomem-1.0.0.jar").toAbsolutePath();
        assertTrue(Files.isRegularFile(jar), "Package the current sources before running process acceptance");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
        command.add("-jar");
        command.add(jar.toString());
        command.add("--transport.workers=2");
        command.add("--memory.limit=4m");
        command.add("--item.maxSize=64k");
        command.addAll(List.of(args));
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static void stopOwned(Process process) throws InterruptedException {
        if (process.isAlive()) process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Owned JVM could not be reaped");
            fail("Owned JVM required forced termination");
        }
    }

    private void preserve(Process process, Path log) throws IOException {
        Path evidence = Path.of("tests", "reports", "server", "process-raw");
        Files.createDirectories(evidence);
        Files.copy(log, evidence.resolve(log.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(evidence.resolve(log.getFileName() + ".exit"), Integer.toString(process.exitValue()));
    }

    @Test void packagedServerStaysAliveServesRawWireAndStops() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = reservation.getLocalPort();
        }
        Path log = temporary.resolve("startup.log");
        Process process = launch(log, "--listen.address=127.0.0.1", "--listen.port=" + port);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            Socket connected = null;
            IOException last = null;
            while (System.nanoTime() < deadline && process.isAlive()) {
                Socket candidate = new Socket();
                try {
                    candidate.connect(new InetSocketAddress("127.0.0.1", port), 100);
                    connected = candidate;
                    break;
                } catch (IOException notReady) {
                    last = notReady;
                    candidate.close();
                    // Bounded process-startup wait, not a cache expiry or silence oracle.
                    process.waitFor(20, TimeUnit.MILLISECONDS);
                }
            }
            assertNotNull(connected, "No listener: " + last + "\n" + Files.readString(log));
            try (Socket socket = connected) {
                socket.setSoTimeout(3000);
                String input = "set first 17 0 3\r\none\r\nget first\r\ndelete first\r\nget first\r\n";
                String expected = "STORED\r\nVALUE first 17 3\r\none\r\nEND\r\nDELETED\r\nEND\r\n";
                socket.getOutputStream().write(input.getBytes(StandardCharsets.US_ASCII));
                assertArrayEquals(expected.getBytes(StandardCharsets.US_ASCII), socket.getInputStream().readNBytes(expected.length()));
                assertTrue(process.isAlive(), "CLI must await server termination rather than return after start");
            }
        } finally { stopOwned(process); preserve(process, log); }
        assertFalse(process.isAlive());
        try (ServerSocket rebound = new ServerSocket()) {
            rebound.setReuseAddress(true);
            rebound.bind(new InetSocketAddress("127.0.0.1", port));
        }
    }

    @Test void invalidConfigurationExitsTwoWithoutBinding() throws Exception {
        Path log = temporary.resolve("config-error.log");
        Process process = launch(log, "--listen.port=0");
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Configuration failure must not enter server loop");
            assertEquals(2, process.exitValue(), Files.readString(log));
            assertFalse(Files.readString(log).isBlank(), "Configuration rejection must be diagnosed");
        } finally { stopOwned(process); preserve(process, log); }
    }

    @Test void occupiedEphemeralPortExitsOneWithoutTouchingOtherListener() throws Exception {
        try (ServerSocket reserved = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Path log = temporary.resolve("bind-error.log");
            Process process = launch(log, "--listen.address=127.0.0.1", "--listen.port=" + reserved.getLocalPort());
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Bind failure must not hang");
                assertEquals(1, process.exitValue(), Files.readString(log));
                assertFalse(Files.readString(log).isBlank(), "Bind rejection must be diagnosed");
                assertFalse(reserved.isClosed());
            } finally { stopOwned(process); preserve(process, log); }
        }
    }

    @Test void orderlySystemExitRunsRealMainShutdownHook() throws Exception {
        Path log = temporary.resolve("orderly-hook.log");
        Path jar = Path.of("target", "octomem-1.0.0.jar").toAbsolutePath();
        String classpath = Path.of("target", "test-classes").toAbsolutePath()
                + java.io.File.pathSeparator + jar;
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, "ru.octomem.server.CacheServerProcessIT$OrderlyExitChild")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "System.exit hook deadlocked");
            assertEquals(0, process.exitValue(), new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
            assertTrue(new String(Files.readAllBytes(log), StandardCharsets.UTF_8).contains("OWNED_RESOURCES_ZERO"));
        } finally { stopOwned(process); preserve(process, log); }
    }

    /** Child launched solely to exercise JVM hook execution rather than Windows process kill. */
    public static final class OrderlyExitChild {
        public static void main(String[] args) throws Exception {
            var created = new java.util.concurrent.CompletableFuture<ru.octomem.cli.CacheServer>();
            var ready = new java.util.concurrent.CountDownLatch(1);
            Thread.ofPlatform().daemon().start(() -> {
                try {
                    var server = created.get(5, TimeUnit.SECONDS);
                    if (!ready.await(5, TimeUnit.SECONDS)) throw new AssertionError("No startup");
                    try (Socket socket = new Socket()) {
                        socket.connect(server.localAddress(), 2000); socket.setSoTimeout(3000);
                        socket.getOutputStream().write("set hook 0 0 1\r\nx\r\n".getBytes(StandardCharsets.US_ASCII));
                        if (!java.util.Arrays.equals("STORED\r\n".getBytes(StandardCharsets.US_ASCII), socket.getInputStream().readNBytes(8)))
                            throw new AssertionError("No raw storage");
                    }
                    System.exit(0);
                } catch (Throwable failure) { failure.printStackTrace(); Runtime.getRuntime().halt(7); }
            });
            ru.octomem.cli.Main.run(new String[]{"--transport.workers=1", "--memory.limit=4m", "--item.maxSize=64k"},
                    System.out, System.err, config -> {
                        var server = new ru.octomem.cli.CacheServer(config, new InetSocketAddress("127.0.0.1", 0));
                        created.complete(server);
                        return new ru.octomem.cli.Main.ServerLifecycle() {
                            public void start() throws IOException { server.start(); ready.countDown(); }
                            public void awaitTermination() throws InterruptedException { server.awaitTermination(); }
                            public void close() {
                                server.close();
                                var usage = server.resourceUsage();
                                if (usage.nativeTotalBytes() != 0 || usage.controlBytes() != 0 || usage.bodyBytes() != 0
                                        || usage.snapshotBytes() != 0 || usage.serviceBytes() != 0)
                                    Runtime.getRuntime().halt(8);
                                System.out.println("OWNED_RESOURCES_ZERO"); System.out.flush();
                            }
                        };
                    });
        }
    }
}

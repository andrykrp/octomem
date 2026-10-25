package ru.octomem.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies actual executable-JAR exit codes with a reserved loopback target port. */
class CliProcessIT {
    @TempDir Path temporary;

    @Test
    void versionSubprocessReturnsExactVersionWithoutDiagnostics() throws Exception {
        Result result = process("--version");
        assertEquals(0, result.code());
        assertEquals("octomem 1.0.0" + System.lineSeparator(), result.out());
        assertEquals("", result.err());
    }

    @Test
    void occupiedExplicitPortReportsStartupFailureAndExitsOne() throws Exception {
        try (ServerSocket held = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Result result = process("--listen.address=127.0.0.1", "--listen.port=" + held.getLocalPort(),
                    "--transport.workers=2", "--memory.limit=4m", "--item.maxSize=64k");
            assertEquals(1, result.code());
            assertEquals("", result.out());
            assertFalse(result.err().isBlank());
            assertFalse(held.isClosed());
        }
    }

    @Test
    void validationAndInvalidConfigurationExitWithoutBindingTargetPort() throws Exception {
        try (ServerSocket held = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            String port = "--listen.port=" + held.getLocalPort();
            Path good = Files.writeString(temporary.resolve("good.conf"), "protocol.maxChunkBytes=2\n");
            Result valid = process("--check-config", "--config", good.toString(), port);
            assertEquals(0, valid.code());
            assertEquals("", valid.err());
            Path bad = Files.writeString(temporary.resolve("bad.conf"), "protocol.maxChunkBytes=1\n");
            Result invalid = process("--check-config", "--config", bad.toString(), port);
            assertEquals(2, invalid.code());
            assertEquals("", invalid.out());
            assertTrue(invalid.err().contains("protocol.maxChunkBytes"));
            assertFalse(held.isClosed());
        }
    }

    @Test
    void subprocessRejectsMalformedUtf8AndDoesNotLeakFileSecrets() throws Exception {
        Path invalid = temporary.resolve("bad-utf8.conf");
        Files.write(invalid, new byte[] {(byte) 0xff});
        assertEquals(2, process("--check-config", "--config", invalid.toString()).code());
        Path secret = Files.writeString(temporary.resolve("secret.conf"), "password_FAKE_SECRET_d723=FAKE_SECRET_d723\n");
        Result result = process("--check-config", "--config", secret.toString());
        assertEquals(2, result.code());
        assertFalse((result.out() + result.err()).contains("FAKE_SECRET_d723"));
    }

    private Result process(String... args) throws Exception {
        Path root = Path.of(System.getProperty("user.dir"));
        Path artifact;
        try (var paths = Files.list(root.resolve("target"))) {
            List<Path> jars = paths.filter(p -> p.getFileName().toString().matches("octomem-[0-9.]+\\.jar")).toList();
            assertEquals(1, jars.size());
            artifact = jars.getFirst().toAbsolutePath();
        }
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xms16m", "-Xmx64m", "-XX:ActiveProcessorCount=2", "-XX:+UseSerialGC", "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar", artifact.toString()));
        command.addAll(List.of(args));
        Path stdout = temporary.resolve(java.util.UUID.randomUUID() + ".out");
        Path stderr = temporary.resolve(java.util.UUID.randomUUID() + ".err");
        ProcessBuilder builder = new ProcessBuilder(command).directory(temporary.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        for (String variable : List.of("CLASSPATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) builder.environment().remove(variable);
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Administrative subprocess must terminate without a server loop");
            return new Result(process.exitValue(), Files.readString(stdout, StandardCharsets.UTF_8), Files.readString(stderr, StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    private record Result(int code, String out, String err) { }
}

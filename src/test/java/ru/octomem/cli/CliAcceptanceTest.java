package ru.octomem.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the public CLI contract without process-global exit interception. */
class CliAcceptanceTest {
    @TempDir Path temporary;
    private static final List<String> KEYS = List.of("listen.address", "listen.port", "memory.limit", "item.maxSize",
            "connections.max", "transport.workers", "eviction.policy", "expiry.reclaimIntervalSeconds",
            "protocol.maxLineBytes", "protocol.maxChunkBytes", "connections.pendingWriteBytes",
            "connections.maxInflightCommands", "connections.idleTimeoutSeconds", "log.level");

    @Test
    void helpDocumentsEveryKeyAndEveryAdministrativeOption() {
        Result result = run("--help");
        assertEquals(0, result.code());
        assertEquals("", result.err());
        for (String key : KEYS) assertTrue(result.out().contains(key), "Missing help key " + key);
        for (String option : List.of("--help", "--version", "--check-config", "--config")) {
            assertTrue(result.out().contains(option), "Missing help option " + option);
        }
        assertTrue(result.out().codePoints().anyMatch(c -> c >= 0x0400 && c <= 0x04ff), "Human help must be Russian");
    }

    @Test
    void helpExplainsPortIntegerSecondsBoundsAndDisabledIdleTimeout() {
        String help = run("--help").out();
        assertTrue(help.contains("1..65535"), "Help must state inclusive TCP port range");
        assertTrue(help.contains("2147483647"), "Help must state int-backed field maximum");
        assertTrue(help.contains("9223372036"), "Help must state safe seconds maximum");
        assertTrue(help.contains("connections.idleTimeoutSeconds"));
        assertTrue(help.matches("(?s).*0[^\n]*(?:\u043e\u0442\u043a\u043b\u044e\u0447|\u0432\u044b\u043a\u043b\u044e\u0447)[^\n]*.*"),
                "Help must explain that zero disables idle timeout");
    }

    @Test
    void versionIsExactAndNormalStartupRunsOwnedLifecycle() {
        Result version = run("--version");
        assertEquals(0, version.code());
        assertEquals("octomem 1.0.0" + System.lineSeparator(), version.out());
        assertEquals("", version.err());
        java.util.ArrayList<String> calls = new java.util.ArrayList<>();
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(diagnostics, true, StandardCharsets.UTF_8)) {
            int code = Main.run(new String[0], output, output, config -> new Main.ServerLifecycle() {
                @Override public void start() { calls.add("start"); }
                @Override public void awaitTermination() { calls.add("await"); }
                @Override public void close() { calls.add("close"); }
            });
            assertEquals(0, code);
        }
        assertEquals(List.of("start", "await", "close"), calls);
    }

    @Test
    void nullArgumentsAndMalformedConfigPathsReturnErrorsWithoutExitingJvm() {
        for (String[] args : new String[][] {null, {null}, {"--config", null}, {"--config", " "},
                {"--config", "--check-config"}, {"--config", "invalid\u0000path"}}) {
            assertEquals(2, run(args).code());
        }
    }

    @Test
    void defaultsCheckSucceedsWithoutNeedingAvailableConfiguredPort() throws Exception {
        try (ServerSocket held = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Result result = run("--check-config", "--listen.port=" + held.getLocalPort());
            assertEquals(0, result.code());
            assertFalse(result.out().isBlank());
            assertEquals("", result.err());
            assertFalse(held.isClosed());
        }
    }

    static Stream<Arguments> validOverrides() {
        return Stream.of(Arguments.of("listen.address", "::1"), Arguments.of("listen.port", "12345"),
                Arguments.of("memory.limit", "2g"), Arguments.of("item.maxSize", "2m"),
                Arguments.of("connections.max", "2048"), Arguments.of("transport.workers", "3"),
                Arguments.of("eviction.policy", "lru"), Arguments.of("expiry.reclaimIntervalSeconds", "7"),
                Arguments.of("protocol.maxLineBytes", "16k"), Arguments.of("protocol.maxChunkBytes", "2"),
                Arguments.of("connections.pendingWriteBytes", "2"), Arguments.of("connections.maxInflightCommands", "32"),
                Arguments.of("connections.idleTimeoutSeconds", "60"), Arguments.of("log.level", "DEBUG"));
    }

    @ParameterizedTest
    @MethodSource("validOverrides")
    void everySchemaKeyIsAccessibleThroughCli(String key, String value) {
        Result result = run("--check-config", "--" + key + "=" + value);
        assertEquals(0, result.code());
        assertEquals("", result.err());
    }

    @Test
    void bothConfigPathFormsAndArgumentPrecedenceWork() throws Exception {
        Path config = Files.writeString(temporary.resolve("config with spaces.conf"), "listen.port=invalid\n");
        for (String[] args : List.of(new String[] {"--check-config", "--config", config.toString(), "--listen.port=12345"},
                new String[] {"--config=" + config, "--listen.port=12345", "--check-config"})) {
            assertEquals(0, run(args).code());
        }
        assertEquals(2, run("--check-config", "--config", config.toString()).code());
    }

    static Stream<Arguments> badArguments() {
        return Stream.of(
                Arguments.of((Object) new String[] {"--unknown=x"}),
                Arguments.of((Object) new String[] {"--config"}),
                Arguments.of((Object) new String[] {"--config="}),
                Arguments.of((Object) new String[] {"--config= "}),
                Arguments.of((Object) new String[] {"--config=invalid\u0000path"}),
                Arguments.of((Object) new String[] {"--listen.port"}),
                Arguments.of((Object) new String[] {"--listen.port", "12345"}),
                Arguments.of((Object) new String[] {"--listen.port="}),
                Arguments.of((Object) new String[] {"--listen.port=7", "--listen.port=8"}),
                Arguments.of((Object) new String[] {"--check-config", "--check-config"}),
                Arguments.of((Object) new String[] {"--help", "--help"}),
                Arguments.of((Object) new String[] {"--version", "--version"}),
                Arguments.of((Object) new String[] {"--help", "--version"}),
                Arguments.of((Object) new String[] {"--help", "--check-config"}),
                Arguments.of((Object) new String[] {"--version", "--check-config"}),
                Arguments.of((Object) new String[] {"--version", "--listen.port=7"}),
                Arguments.of((Object) new String[] {"--help", "--listen.port=7"}),
                Arguments.of((Object) new String[] {"--help=true"}),
                Arguments.of((Object) new String[] {"--version=true"}),
                Arguments.of((Object) new String[] {"--check-config=true"}),
                Arguments.of((Object) new String[] {"start"}),
                Arguments.of((Object) new String[] {"-h"}),
                Arguments.of((Object) new String[] {"--"}),
                Arguments.of((Object) new String[] {""}),
                Arguments.of((Object) new String[] {"--check-config", "--protocol.maxChunkBytes=0"}),
                Arguments.of((Object) new String[] {"--check-config", "--protocol.maxChunkBytes=1"}),
                Arguments.of((Object) new String[] {"--check-config", "--listen.port= 12345"}),
                Arguments.of((Object) new String[] {"--check-config", "--listen.port=12345 "}));
    }

    @ParameterizedTest
    @MethodSource("badArguments")
    void invalidArgumentsReturnConfigurationExitAndStderrOnly(String[] args) {
        Result result = run(args);
        assertEquals(2, result.code());
        assertEquals("", result.out());
        assertFalse(result.err().isBlank());
    }

    @Test
    void duplicateConfigPathsRejectEvenWhenBothFilesAreValid() throws Exception {
        Path config = Files.writeString(temporary.resolve("valid.conf"), "");
        assertEquals(2, run("--check-config", "--config", config.toString(), "--config=" + config).code());
        assertEquals(2, run("--check-config", "--config=" + config, "--config", config.toString()).code());
        assertEquals(2, run("--check-config", "--config=" + config, "--config=" + config).code());
        assertEquals(2, run("--help", "--config", config.toString()).code());
        assertEquals(2, run("--version", "--config=" + config).code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"--password_FAKE_SECRET_5fa1\nforged=FAKE_SECRET_5fa1", "--listen.port=FAKE_SECRET_5fa1\nforged", "--log.level=FAKE_SECRET_5fa1", "--config=FAKE_SECRET_5fa1\nforged"})
    void errorsNeverEchoSecretsOrNewlineInjection(String argument) {
        Result result = run("--check-config", argument);
        assertEquals(2, result.code());
        assertFalse((result.out() + result.err()).contains("FAKE_SECRET_5fa1"));
        assertFalse((result.out() + result.err()).contains("forged"));
    }

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ByteArrayOutputStream global = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        int code;
        try (PrintStream stdout = new PrintStream(out, true, StandardCharsets.UTF_8);
                PrintStream stderr = new PrintStream(err, true, StandardCharsets.UTF_8);
                PrintStream globals = new PrintStream(global, true, StandardCharsets.UTF_8)) {
            System.setOut(globals);
            System.setErr(globals);
            code = Main.run(args, stdout, stderr);
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
        assertEquals("", global.toString(StandardCharsets.UTF_8), "run must use supplied streams, not global stdio");
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int code, String out, String err) { }
}

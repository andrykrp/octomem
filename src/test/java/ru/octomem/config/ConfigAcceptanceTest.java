package ru.octomem.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Independent acceptance derived from the pre-implementation T002 frozen oracle. */
class ConfigAcceptanceTest {
    @TempDir Path temporary;

    private static Map<String, String> expectedDefaults() {
        return Map.ofEntries(
                Map.entry("listen.address", "127.0.0.1"), Map.entry("listen.port", "11211"),
                Map.entry("memory.limit", "67108864"), Map.entry("item.maxSize", "1048576"),
                Map.entry("connections.max", "1024"),
                Map.entry("transport.workers", Integer.toString(Math.max(2, Runtime.getRuntime().availableProcessors() * 2))),
                Map.entry("eviction.policy", "lru"), Map.entry("expiry.reclaimIntervalSeconds", "1"),
                Map.entry("protocol.maxLineBytes", "8192"), Map.entry("protocol.maxChunkBytes", "65536"),
                Map.entry("connections.pendingWriteBytes", "8388608"),
                Map.entry("connections.maxInflightCommands", "16"),
                Map.entry("connections.idleTimeoutSeconds", "0"), Map.entry("log.level", "info"));
    }

    @Test
    void allFourteenDefaultsAndTypedAccessorsMatchOriginalSpecification() throws Exception {
        ServerConfig config = ConfigLoader.load(null, Map.of());
        assertEquals(expectedDefaults(), config.values());
        assertEquals(expectedDefaults(), ConfigLoader.defaults());
        assertTypedValues(config);
    }

    @Test
    void shippedExampleParsesAndPreservesAllDefaultValues() throws Exception {
        Path example = temporary.resolve("config-example.properties");
        try (var resource = ConfigAcceptanceTest.class.getResourceAsStream("/config/config-example.properties")) {
            assertNotNull(resource, "Approved complete configuration example resource must exist");
            Files.copy(resource, example);
        }
        assertTrue(Files.isRegularFile(example), "Approved complete configuration example must exist");
        String text = Files.readString(example, StandardCharsets.UTF_8);
        for (String key : expectedDefaults().keySet()) assertTrue(text.contains(key + "="), "Example omits " + key);
        assertEquals(expectedDefaults(), ConfigLoader.load(example, Map.of()).values());
    }

    private static void assertTypedValues(ServerConfig config) {
        Map<String, String> values = config.values();
        assertAll(
                () -> assertEquals(values.get("listen.address"), config.listenAddress()),
                () -> assertEquals(Integer.parseInt(values.get("listen.port")), config.listenPort()),
                () -> assertEquals(Long.parseLong(values.get("memory.limit")), config.memoryLimit()),
                () -> assertEquals(Integer.parseInt(values.get("item.maxSize")), config.itemMaxSize()),
                () -> assertEquals(Integer.parseInt(values.get("connections.max")), config.connectionsMax()),
                () -> assertEquals(Integer.parseInt(values.get("transport.workers")), config.transportWorkers()),
                () -> assertEquals(values.get("eviction.policy"), config.evictionPolicy()),
                () -> assertEquals(Long.parseLong(values.get("expiry.reclaimIntervalSeconds")), config.expiryReclaimIntervalSeconds()),
                () -> assertEquals(Integer.parseInt(values.get("protocol.maxLineBytes")), config.protocolMaxLineBytes()),
                () -> assertEquals(Integer.parseInt(values.get("protocol.maxChunkBytes")), config.protocolMaxChunkBytes()),
                () -> assertEquals(Integer.parseInt(values.get("connections.pendingWriteBytes")), config.connectionsPendingWriteBytes()),
                () -> assertEquals(Integer.parseInt(values.get("connections.maxInflightCommands")), config.connectionsMaxInflightCommands()),
                () -> assertEquals(Long.parseLong(values.get("connections.idleTimeoutSeconds")), config.connectionsIdleTimeoutSeconds()),
                () -> assertEquals(values.get("log.level"), config.logLevel()));
    }

    static Stream<Arguments> overrides() {
        return Stream.of(
                Arguments.of("listen.address", "::1", "::1"),
                Arguments.of("listen.port", "12345", "12345"),
                Arguments.of("memory.limit", "2g", "2147483648"),
                Arguments.of("item.maxSize", "2m", "2097152"),
                Arguments.of("connections.max", "2048", "2048"),
                Arguments.of("transport.workers", "3", "3"),
                Arguments.of("eviction.policy", "lru", "lru"),
                Arguments.of("expiry.reclaimIntervalSeconds", "7", "7"),
                Arguments.of("protocol.maxLineBytes", "16k", "16384"),
                Arguments.of("protocol.maxChunkBytes", "2", "2"),
                Arguments.of("connections.pendingWriteBytes", "2", "2"),
                Arguments.of("connections.maxInflightCommands", "32", "32"),
                Arguments.of("connections.idleTimeoutSeconds", "60", "60"),
                Arguments.of("log.level", "debug", "debug"));
    }

    @ParameterizedTest
    @MethodSource("overrides")
    void everyKeyIsReachableFromFileAndArguments(String key, String input, String canonical) throws Exception {
        Map<String, String> expected = new HashMap<>(expectedDefaults());
        expected.put(key, canonical);
        ServerConfig fromFile = ConfigLoader.load(file(key + "=" + input + "\n"), Map.of());
        ServerConfig fromArgs = ConfigLoader.load(null, Map.of(key, input));
        assertEquals(expected, fromFile.values());
        assertEquals(expected, fromArgs.values());
        assertTypedValues(fromFile);
        assertTypedValues(fromArgs);
    }

    @Test
    void defaultsAndResultsCannotBeChangedThroughAnyMapView() throws Exception {
        Map<String, String> input = new HashMap<>(Map.of("listen.port", "12345"));
        ServerConfig config = ConfigLoader.load(null, input);
        input.put("listen.port", "12346");
        for (Map<String, String> map : List.of(config.values(), ConfigLoader.defaults())) {
            assertThrows(UnsupportedOperationException.class, () -> map.put("listen.port", "7"));
            assertThrows(UnsupportedOperationException.class, () -> map.remove("listen.port"));
            assertThrows(UnsupportedOperationException.class, () -> map.entrySet().iterator().next().setValue("7"));
            assertThrows(UnsupportedOperationException.class, () -> map.keySet().remove("listen.port"));
        }
        assertEquals(12345, config.listenPort());
        assertEquals(expectedDefaults(), ConfigLoader.load(null, Map.of()).values());
    }

    @Test
    void precedenceUsesMergedValuesButNeverDiscardsUnknownOrDuplicateFileKeys() throws Exception {
        ServerConfig config = ConfigLoader.load(file("listen.port=bad\nmemory.limit=1\nitem.maxSize=2\n"),
                Map.of("listen.port", "12345", "memory.limit", "64m"));
        assertEquals(12345, config.listenPort());
        assertEquals(67108864L, config.memoryLimit());
        assertEquals(2, config.itemMaxSize());
        assertEquals("info", config.logLevel());
        assertThrows(ConfigException.class, () -> ConfigLoader.load(file("listen.port=7\nlisten.port=8\n"), Map.of("listen.port", "9")));
        assertThrows(ConfigException.class, () -> ConfigLoader.load(file("password=secret\n"), Map.of("listen.port", "9")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"listen.port=", "listen.port= \t", "listen.port=12=3",
            "listen.port=12\\3", "listen.port=12\t3", "listen.port=12\u00003",
            "listen.port=12\u007f3", "listen.port=${PORT}", "listen.port", "=12345"})
    void malformedFileSyntaxCannotBeHiddenByValidArgumentOverride(String malformed) throws Exception {
        ConfigException failure = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(file("# first\n" + malformed + "\n"), Map.of("listen.port", "12345")));
        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains("2"), "Syntax diagnostic must identify physical line 2");
        assertFalse(failure.getMessage().contains("${PORT}"));
        assertFalse(failure.getMessage().contains("12=3"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-number", "-1", "65536", "1.5", "99999999999999999999999999"})
    void syntacticallyValidButInvalidNumericFileValuesMayBeOverridden(String value) throws Exception {
        assertEquals(12345, ConfigLoader.load(file("listen.port=" + value), Map.of("listen.port", "12345")).listenPort());
    }

    @Test
    void lineLimitCountsUtf16UnitsAndExcludesOnlyInitialBom() throws Exception {
        String line = "#" + "\ud83d\ude00".repeat(4095) + "x";
        assertEquals(8192, line.length());
        assertTrue(line.getBytes(StandardCharsets.UTF_8).length > 8192);
        assertEquals(expectedDefaults(), ConfigLoader.load(file(line), Map.of()).values());
        assertEquals(expectedDefaults(), ConfigLoader.load(file("\ufeff" + line), Map.of()).values());
        assertThrows(ConfigException.class, () -> ConfigLoader.load(file(line + "x"), Map.of()));
        assertThrows(ConfigException.class, () -> ConfigLoader.load(file("\ufeff" + line + "x"), Map.of()));
    }

    @Test
    void unknownAndDuplicateDiagnosticsUseSafePhysicalLineNumbers() throws Exception {
        ConfigException unknown = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(file("\ufeff# first\r\n! second\r\nRAW_SECRET_KEY_543=value\r\n"), Map.of()));
        assertEquals("Неизвестный ключ конфигурации, строка 3. См. --help.", unknown.getMessage());
        assertFalse(unknown.getMessage().contains("RAW_SECRET_KEY_543"));
        assertFalse(unknown.getMessage().contains("value"));
        ConfigException duplicate = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(file("# first\nlisten.port=7\n\nlisten.port=8\n"), Map.of("listen.port", "12345")));
        assertEquals("Повтор ключа listen.port, строка 4.", duplicate.getMessage());
        ConfigException malformed = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(file("# first\nRAW_SECRET_LINE_543\n"), Map.of()));
        assertEquals("Неверный формат строки конфигурации: 2. Ожидается ключ=значение.", malformed.getMessage());
    }

    @Test
    void fileSupportsUtf8CommentsBlankLinesCrLfAndFinalLineWithoutNewline() throws Exception {
        assertEquals(12345, ConfigLoader.load(file("# \u041a\u043e\u043c\u043c\u0435\u043d\u0442\u0430\u0440\u0438\u0439\r\n! comment\r\n\r\nlisten.port=12345"), Map.of()).listenPort());
        assertEquals(expectedDefaults(), ConfigLoader.load(file(""), Map.of()).values());
    }

    @Test
    void initialBomAndAsciiTrimmingAcceptWithoutInterpretingEscapes() throws Exception {
        assertEquals(12345, ConfigLoader.load(file("\ufeff \t# comment\n\t! comment\n \tlisten.port \t= \t12345 \t\n"), Map.of()).listenPort());
    }

    @Test
    void exactFileAndLineLimitsAcceptButOneByteOverRejects() throws Exception {
        String line = "#" + "x".repeat(8191);
        assertEquals(expectedDefaults(), ConfigLoader.load(file(line), Map.of()).values());
        String block = "#" + "x".repeat(8190) + "\n";
        assertEquals(1048576, block.repeat(128).getBytes(StandardCharsets.UTF_8).length);
        assertEquals(expectedDefaults(), ConfigLoader.load(file(block.repeat(128)), Map.of()).values());
        assertThrows(ConfigException.class, () -> ConfigLoader.load(file(block.repeat(128) + "\n"), Map.of()));
        assertThrows(ConfigException.class, () -> ConfigLoader.load(file(line + "x"), Map.of()));
    }

    @Test
    void nullOverridesNullEntriesAndOversizedValuesRejectAsConfigurationErrors() {
        assertThrows(ConfigException.class, () -> ConfigLoader.load(null, null));
        Map<String, String> nullKey = new HashMap<>();
        nullKey.put(null, "secret");
        assertThrows(ConfigException.class, () -> ConfigLoader.load(null, nullKey));
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("listen.port", null);
        assertThrows(ConfigException.class, () -> ConfigLoader.load(null, nullValue));
        assertThrows(ConfigException.class, () -> ConfigLoader.load(null, Map.of("listen.port", "1".repeat(8193))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"listen.port=12\t345", "listen.port=12\u0000345", "listen.port=12\u007f345", " \t \n", "\t!comment"})
    void controlsRejectWhileWhitespaceOnlyAndIndentedCommentsAreIgnored(String text) throws Exception {
        if (text.contains("listen.port")) {
            assertThrows(ConfigException.class, () -> ConfigLoader.load(file(text), Map.of()));
        } else {
            assertEquals(expectedDefaults(), ConfigLoader.load(file(text), Map.of()).values());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n\ufefflisten.port=12345", "listen.port=12345\r", "listen.port=12\r345\n", "listen.port=12345 # comment", "listen.port=12345 ! comment", "listen.port=123\\\n45", "listen.port=\\u0031", "\u00a0listen.port=12345", "listen.port=\u00a012345"})
    void nonContractWhitespaceEmbeddedBomAndEscapesReject(String text) throws Exception {
        assertThrows(ConfigException.class, () -> ConfigLoader.load(file(text), Map.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"error", "warn", "info", "debug", "ERROR", "WaRn", "INFO", "DeBuG"})
    void approvedLogLevelsAreAsciiCaseInsensitive(String level) throws Exception {
        assertEquals(level.toLowerCase(java.util.Locale.ROOT), ConfigLoader.load(null, Map.of("log.level", level)).logLevel());
    }

    @ParameterizedTest
    @ValueSource(strings = {"trace", "off", "fatal", "warning", "", " info", "info ", "\u0130NFO"})
    void unapprovedLogLevelsAndWhitespaceReject(String level) {
        assertThrows(ConfigException.class, () -> ConfigLoader.load(null, Map.of("log.level", level)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"listen.port", "listen.port:12345", "=12345", "unknown=x", "listen.port=7\nlisten.port=8", "listen.port=${PORT}", "listen.port=", "listen.port=12345=6"})
    void malformedFileSyntaxAndInterpolationReject(String text) throws Exception {
        assertThrows(ConfigException.class, () -> ConfigLoader.load(file(text), Map.of()));
    }

    @Test
    void missingDirectoryInvalidUtf8AndOversizedInputsRejectWithoutUncheckedErrors() throws Exception {
        assertThrows(ConfigException.class, () -> ConfigLoader.load(temporary.resolve("missing"), Map.of()));
        assertThrows(ConfigException.class, () -> ConfigLoader.load(temporary, Map.of()));
        Path invalid = temporary.resolve("invalid-utf8");
        Files.write(invalid, new byte[] {(byte) 0xc3, (byte) 0x28});
        assertThrows(ConfigException.class, () -> ConfigLoader.load(invalid, Map.of()));
        assertThrows(ConfigException.class, () -> ConfigLoader.load(file("#" + "x".repeat(8192)), Map.of()));
        assertThrows(ConfigException.class, () -> ConfigLoader.load(file("#comment\n".repeat(140000)), Map.of()));
    }

    static Stream<Arguments> invalidNumbers() {
        Stream.Builder<Arguments> rows = Stream.builder();
        for (String key : List.of("listen.port", "memory.limit", "item.maxSize", "connections.max", "transport.workers",
                "expiry.reclaimIntervalSeconds", "protocol.maxLineBytes", "protocol.maxChunkBytes",
                "connections.pendingWriteBytes", "connections.maxInflightCommands", "connections.idleTimeoutSeconds")) {
            for (String value : List.of("-1", "+1", "1.0", "1e3", "1_000", "1 0", "\u0661", "99999999999999999999999999999999", "${VALUE}", "")) {
                rows.add(Arguments.of(key, value));
            }
            if (!key.equals("connections.idleTimeoutSeconds")) rows.add(Arguments.of(key, "0"));
        }
        for (String key : List.of("connections.max", "transport.workers", "item.maxSize", "protocol.maxLineBytes",
                "protocol.maxChunkBytes", "connections.pendingWriteBytes", "connections.maxInflightCommands")) {
            rows.add(Arguments.of(key, "2147483648"));
        }
        rows.add(Arguments.of("listen.port", "65536"));
        rows.add(Arguments.of("protocol.maxChunkBytes", "1"));
        rows.add(Arguments.of("connections.pendingWriteBytes", "1"));
        rows.add(Arguments.of("expiry.reclaimIntervalSeconds", "9223372037"));
        rows.add(Arguments.of("connections.idleTimeoutSeconds", "9223372037"));
        return rows.build();
    }

    @ParameterizedTest
    @MethodSource("invalidNumbers")
    void invalidNumericInputsFailWithKnownKeyButWithoutValue(String key, String value) {
        ConfigException failure = assertThrows(ConfigException.class, () -> ConfigLoader.load(null, Map.of(key, value)));
        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains(key), "Known schema key must identify the invalid field");
    }

    static Stream<Arguments> numericBoundaries() {
        return Stream.of(
                Arguments.of("listen.port", "1"), Arguments.of("listen.port", "65535"),
                Arguments.of("memory.limit", "9223372036854775807"),
                Arguments.of("connections.max", "2147483647"), Arguments.of("transport.workers", "2147483647"),
                Arguments.of("protocol.maxLineBytes", "1"), Arguments.of("protocol.maxLineBytes", "2147483647"),
                Arguments.of("protocol.maxChunkBytes", "2"), Arguments.of("protocol.maxChunkBytes", "2147483647"),
                Arguments.of("connections.pendingWriteBytes", "2"), Arguments.of("connections.pendingWriteBytes", "2147483647"),
                Arguments.of("connections.maxInflightCommands", "1"), Arguments.of("connections.maxInflightCommands", "2147483647"),
                Arguments.of("expiry.reclaimIntervalSeconds", "9223372036"),
                Arguments.of("connections.idleTimeoutSeconds", "9223372036"), Arguments.of("connections.idleTimeoutSeconds", "0"));
    }

    @ParameterizedTest
    @MethodSource("numericBoundaries")
    void exactNumericBoundariesAcceptWithoutAllocatingConfiguredResources(String key, String value) throws Exception {
        assertEquals(value, ConfigLoader.load(null, Map.of(key, value)).values().get(key));
    }

    static Stream<Arguments> sizeCases() {
        Stream.Builder<Arguments> rows = Stream.builder();
        for (String suffix : List.of("", "k", "K", "m", "M", "g", "G")) {
            int shift = suffix.isEmpty() ? 0 : switch (suffix.toLowerCase(java.util.Locale.ROOT)) {
                case "k" -> 10; case "m" -> 20; default -> 30;
            };
            for (int number : List.of(2, 7, 1024)) {
                rows.add(Arguments.of(number + suffix, BigInteger.valueOf(number).shiftLeft(shift).toString()));
            }
        }
        return rows.build();
    }

    @ParameterizedTest
    @MethodSource("sizeCases")
    void binarySizeUnitsMatchIndependentBigIntegerOracle(String input, String expected) throws Exception {
        assertEquals(expected, ConfigLoader.load(null, Map.of("memory.limit", input, "item.maxSize", "1")).values().get("memory.limit"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1kb", "1KiB", "1t", "k", "1kk", "1 k", "9223372036854775808", "8589934592g", "8796093022208m", "9007199254740992k"})
    void invalidSizeGrammarAndMultiplicationOverflowReject(String size) {
        assertThrows(ConfigException.class, () -> ConfigLoader.load(null, Map.of("memory.limit", size)));
    }

    @Test
    void itemCannotExceedMemoryButEqualityAndMaximumIntAreValid() throws Exception {
        assertThrows(ConfigException.class, () -> ConfigLoader.load(null, Map.of("memory.limit", "1024", "item.maxSize", "1025")));
        ServerConfig exact = ConfigLoader.load(null, Map.of("memory.limit", "2147483647", "item.maxSize", "2147483647"));
        assertEquals(2147483647, exact.itemMaxSize());
        assertEquals(exact.itemMaxSize(), exact.memoryLimit());
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "0.0.0.0", "255.255.255.255", "::1", "::", "2001:db8::1", "2001:db8:0:0:0:0:0:1"})
    void numericAddressesAcceptWithoutResolution(String address) throws Exception {
        assertEquals(address, ConfigLoader.load(null, Map.of("listen.address", address)).listenAddress());
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "example.invalid", "256.0.0.1", "127.0.0", "127.1", "2130706433", "127..0.1", ".127.0.1", "127.0.0.1.", "1.2.3.4.5", "1234.0.0.1", "127.0.0.1:11211", "1::2::3", "gggg::1", "fe80::1%1", "[::1]", "::1]", "", "${HOST}"})
    void invalidAndDnsAddressesReject(String address) {
        assertThrows(ConfigException.class, () -> ConfigLoader.load(null, Map.of("listen.address", address)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"fifo", "random", "none", "${POLICY}", ""})
    void unsupportedEvictionPolicyRejects(String policy) {
        assertThrows(ConfigException.class, () -> ConfigLoader.load(null, Map.of("eviction.policy", policy)));
    }

    @Test
    void invalidKeysAndValuesDoNotLeakToDiagnosticsOrGlobalStreams() throws Exception {
        String secret = "ACCEPTANCE_FAKE_SECRET_57e1";
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        try (PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            System.setErr(capture);
            for (Map<String, String> input : List.of(Map.of("password_" + secret + "\nforged", secret),
                    Map.of("listen.port", secret + "\nforged"), Map.of("listen.address", secret),
                    Map.of("log.level", secret), Map.of("eviction.policy", secret))) {
                ConfigException failure = assertThrows(ConfigException.class, () -> ConfigLoader.load(null, input));
                failure.printStackTrace(capture);
            }
            ConfigException failure = assertThrows(ConfigException.class,
                    () -> ConfigLoader.load(file("password_" + secret + "=" + secret), Map.of()));
            failure.printStackTrace(capture);
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
        assertFalse(captured.toString(StandardCharsets.UTF_8).contains(secret));
        assertFalse(captured.toString(StandardCharsets.UTF_8).contains("forged"));
    }

    private Path file(String content) throws Exception {
        return Files.writeString(temporary.resolve("config-" + java.util.UUID.randomUUID() + ".conf"), content, StandardCharsets.UTF_8);
    }
}

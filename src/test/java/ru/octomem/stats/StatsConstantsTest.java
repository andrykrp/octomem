package ru.octomem.stats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class StatsConstantsTest {
    @Test
    void constructorCopiesMutableInputBeforeAnySnapshot() {
        var input = new HashMap<>(StatsOracle.CONSTANTS);
        StatsRegistry registry = new AtomicStatsRegistry(input);
        input.put("pid", "999");
        input.clear();
        assertEquals(StatsOracle.CONSTANTS, registry.snapshot().constants());
        registry.reset();
        assertEquals(StatsOracle.CONSTANTS, registry.snapshot().constants());
    }

    @Test
    void nullMapKeyAndValueAreRejected() {
        assertThrows(NullPointerException.class, () -> new AtomicStatsRegistry(null));
        var nullKey = new HashMap<>(StatsOracle.CONSTANTS);
        nullKey.remove("pid");
        nullKey.put(null, "123");
        assertThrows(NullPointerException.class, () -> new AtomicStatsRegistry(nullKey));
        for (String key : StatsOracle.CONSTANT_KEYS) {
            var input = new HashMap<>(StatsOracle.CONSTANTS);
            input.put(key, null);
            assertThrows(NullPointerException.class, () -> new AtomicStatsRegistry(input));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pid", "version", "pointer_size", "threads", "limit_maxbytes"})
    void everyConstantIsRequired(String key) {
        var input = new HashMap<>(StatsOracle.CONSTANTS);
        input.remove(key);
        assertThrows(IllegalArgumentException.class, () -> new AtomicStatsRegistry(input));
    }

    @Test
    void emptyAdditionalAndWrongCaseKeysAreRejectedWithoutEchoingInput() {
        assertThrows(IllegalArgumentException.class, () -> new AtomicStatsRegistry(Map.of()));
        for (String key : new String[]{"PID", "pid ", "secret-key-DO-NOT-ECHO\r\n"}) {
            var input = new HashMap<>(StatsOracle.CONSTANTS);
            input.put(key, "secret-value-DO-NOT-ECHO");
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> new AtomicStatsRegistry(input));
            assertFalse(error.toString().contains("DO-NOT-ECHO"));
            assertFalse(error.toString().contains("\r"));
            assertFalse(error.toString().contains("\n"));
            input.remove("pid");
            assertThrows(IllegalArgumentException.class, () -> new AtomicStatsRegistry(input));
        }
    }

    static Stream<Arguments> invalidValues() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (String key : new String[]{"pid", "threads", "limit_maxbytes"}) {
            for (String value : new String[]{"", "0", "00", "01", "+1", "-1", " 1", "1 ", "1\t",
                    "1\n", "1\r", "1.0", "1e3", "9223372036854775808", "99999999999999999999999",
                    "\u0661", "secret-value-DO-NOT-ECHO"}) {
                cases.add(Arguments.of(key, value));
            }
        }
        for (String value : new String[]{"", "0", "16", "128", "032", "+64", "64 ", " 64", "64\n"}) {
            cases.add(Arguments.of("pointer_size", value));
        }
        for (String value : new String[]{"", " ", " octomem", "octomem ", "\u0000", "\t", "\n", "\r", "\u001f",
                "\u007f", "\u0080", "\u0430", "v".repeat(129), "secret-value-DO-NOT-ECHO\r\n"}) {
            cases.add(Arguments.of("version", value));
        }
        return cases.build();
    }

    @ParameterizedTest
    @MethodSource("invalidValues")
    void lexicalViolationsAreRejectedAndErrorsDoNotEchoCallerData(String key, String value) {
        var input = new HashMap<>(StatsOracle.CONSTANTS);
        input.put(key, value);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new AtomicStatsRegistry(input));
        assertFalse(error.toString().contains("DO-NOT-ECHO"));
        assertFalse(error.toString().contains("\r"));
        assertFalse(error.toString().contains("\n"));
    }

    @Test
    void canonicalPositiveBoundariesAndPrintableVersionArePreservedLiterally() {
        for (String number : new String[]{"1", "9223372036854775807"}) {
            for (String pointerSize : new String[]{"32", "64"}) {
                var input = new HashMap<>(StatsOracle.CONSTANTS);
                input.put("pid", number);
                input.put("threads", number);
                input.put("limit_maxbytes", number);
                input.put("pointer_size", pointerSize);
                input.put("version", "!~".repeat(64));
                assertEquals(input, new AtomicStatsRegistry(input).snapshot().constants());
            }
        }
        StringBuilder printable = new StringBuilder();
        for (char character = 0x21; character <= 0x7e; character++) printable.append(character);
        var input = new HashMap<>(StatsOracle.CONSTANTS);
        input.put("version", printable.toString());
        assertEquals(input, new AtomicStatsRegistry(input).snapshot().constants());
        input.put("version", "!");
        assertEquals(input, new AtomicStatsRegistry(input).snapshot().constants());
        input.put("version", "1.0.0 (octomem)");
        assertEquals(input, new AtomicStatsRegistry(input).snapshot().constants());
    }
}

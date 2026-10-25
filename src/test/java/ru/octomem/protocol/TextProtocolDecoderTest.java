package ru.octomem.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Independent grammar oracle frozen in tests/reports/T007-decoder/oracle.md. */
class TextProtocolDecoderTest {
    record Example(String wire, CommandType type, List<String> keys, String body,
                   Integer flags, Long expiry, Long cas, Long delta, Integer level, boolean noreply) {
        void check(Command command) {
            assertEquals(type, command.type());
            assertEquals(keys, TextProtocolDecoderTest.keys(command));
            assertEquals(keys.size(), command.keys().size());
            assertEquals(noreply, command.noreply());
            if (body == null) assertNull(command.value());
            else assertArrayEquals(bytes(body), command.value());
            if (flags != null) assertEquals(flags.intValue(), command.flags());
            if (expiry != null) assertEquals(expiry.longValue(), command.exptime());
            if (cas != null) assertEquals(cas.longValue(), command.casId());
            if (delta != null) assertEquals(delta.longValue(), command.delta());
            if (level != null) assertEquals(level.intValue(), command.level());
        }
    }

    static byte[] bytes(String text) { return text.getBytes(StandardCharsets.ISO_8859_1); }

    static List<String> keys(Command command) {
        List<String> result = new ArrayList<>();
        command.keys().forEachKey((data, offset, length) ->
                result.add(new String(data, offset, length, StandardCharsets.ISO_8859_1)));
        return result;
    }

    static Example simple(String wire, CommandType type, List<String> keys, boolean noreply) {
        return new Example(wire, type, keys, null, null, null, null, null, null, noreply);
    }

    static List<Example> examples() {
        List<Example> out = new ArrayList<>();
        for (String name : List.of("get", "gets", "gat", "gats")) {
            boolean expiry = name.startsWith("ga");
            out.add(new Example("  " + name + (expiry ? " -1" : "") + "  a noreply a  \r\n",
                    CommandType.valueOf(name.toUpperCase(java.util.Locale.ROOT)),
                    List.of("a", "noreply", "a"), null, null, expiry ? -1L : null,
                    null, null, null, false));
        }
        for (String name : List.of("set", "add", "replace", "append", "prepend", "cas")) {
            for (boolean noreply : List.of(false, true)) {
                String body = "a\0\r\nb\n";
                boolean ignored = name.equals("append") || name.equals("prepend");
                out.add(new Example(name + " noreply 4294967295 -9223372036854775808 6"
                        + (name.equals("cas") ? " 18446744073709551615" : "")
                        + (noreply ? " noreply" : "") + "\r\n" + body + "\r\n",
                        CommandType.valueOf(name.toUpperCase(java.util.Locale.ROOT)), List.of("noreply"), body,
                        ignored ? null : -1, ignored ? null : 0L,
                        name.equals("cas") ? -1L : null, null, null, noreply));
            }
        }
        out.add(new Example("set k 0 0 0\n\r\n", CommandType.SET, List.of("k"), "", 0, 0L, null, null, null, false));
        for (String suffix : List.of("", " noreply", " -9223372036854775808", " 9223372036854775807 noreply")) {
            out.add(simple("delete k" + suffix + "\r\n", CommandType.DELETE, List.of("k"), suffix.endsWith("noreply")));
        }
        for (String name : List.of("incr", "decr")) {
            for (String value : List.of("0", "9223372036854775807", "9223372036854775808", "18446744073709551615")) {
                out.add(new Example(name + " k " + value + " noreply\r\n",
                        CommandType.valueOf(name.toUpperCase(java.util.Locale.ROOT)), List.of("k"), null,
                        null, null, null, Long.parseUnsignedLong(value), null, true));
            }
        }
        for (long expiry : new long[]{Integer.MIN_VALUE, -1, 0, 1, 2592000, 2592001, Integer.MAX_VALUE}) {
            out.add(new Example("touch k " + expiry + " noreply\n", CommandType.TOUCH, List.of("k"), null,
                    null, expiry, null, null, null, true));
            out.add(new Example("flush_all " + expiry + "\r\n", CommandType.FLUSH_ALL, List.of(), null,
                    null, expiry, null, null, null, false));
        }
        out.add(new Example("flush_all\r\n", CommandType.FLUSH_ALL, List.of(), null, null, 0L, null, null, null, false));
        out.add(simple("flush_all noreply\r\n", CommandType.FLUSH_ALL, List.of(), true));
        out.add(new Example("gat - a\r\n", CommandType.GAT, List.of("a"), null, null, 0L, null, null, null, false));
        out.add(new Example("touch k -\r\n", CommandType.TOUCH, List.of("k"), null, null, 0L, null, null, null, false));
        out.add(new Example("set k 0 +1 0\r\n\r\n", CommandType.SET, List.of("k"), "", 0, 1L, null, null, null, false));
        for (String suffix : List.of("", " items", " slabs", " reset")) {
            out.add(simple("stats" + suffix + "\r\n", CommandType.valueOf(("stats" + suffix).replace(' ', '_').toUpperCase(java.util.Locale.ROOT)), List.of(), false));
        }
        out.add(simple("version\n", CommandType.VERSION, List.of(), false));
        out.add(simple("quit\r\n", CommandType.QUIT, List.of(), false));
        for (int level : new int[]{0, 1, Integer.MAX_VALUE}) {
            out.add(new Example("verbosity " + level + " noreply\r\n", CommandType.VERBOSITY,
                    List.of(), null, null, null, null, null, level, true));
        }
        return out;
    }

    static Stream<Arguments> splits() {
        return examples().stream().flatMap(example -> IntStream.rangeClosed(0, bytes(example.wire()).length)
                .mapToObj(split -> Arguments.of(example, split)));
    }

    /** Feeds one fragment; retains no production parser logic in the oracle. */
    static void feed(TextProtocolDecoder decoder, ByteBuffer input, List<Command> found) throws ProtocolException {
        int attempts = input.remaining() * 3 + 5;
        do {
            int before = input.position();
            decoder.append(input);
            Command command = decoder.nextCommand();
            if (command != null) {
                found.add(command);
                return;
            }
            if (before == input.position() && !input.hasRemaining()) return;
            assertTrue(--attempts > 0, "decoder made no bounded progress");
        } while (input.hasRemaining());
    }

    static Command decode(TextProtocolDecoder decoder, String wire) throws ProtocolException {
        List<Command> found = new ArrayList<>();
        ByteBuffer input = ByteBuffer.wrap(bytes(wire));
        feed(decoder, input, found);
        assertFalse(input.hasRemaining());
        assertEquals(1, found.size());
        return found.getFirst();
    }

    @ParameterizedTest(name = "grammar {index}: split={1}")
    @MethodSource("splits")
    void parsesEveryCommandAtEveryByteBoundary(Example expected, int split) throws Exception {
        ProtocolBudgetTest.CountingBudget budget = new ProtocolBudgetTest.CountingBudget(true);
        try (TextProtocolDecoder decoder = new TextProtocolDecoder(8192, 1024, budget)) {
            List<Command> found = new ArrayList<>();
            byte[] wire = bytes(expected.wire());
            feed(decoder, ByteBuffer.wrap(wire, 0, split), found);
            feed(decoder, ByteBuffer.wrap(wire, split, wire.length - split), found);
            assertEquals(1, found.size());
            expected.check(found.getFirst());
            assertNull(decoder.nextCommand());
            decoder.release(found.getFirst());
            budget.assertBalanced();
        }
    }

    @Test
    void generatedGrammarModelSeededPipeline() throws Exception {
        Random random = new Random(0x700dec0deL);
        List<Example> expected = new ArrayList<>();
        StringBuilder wire = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            Example value;
            String key = "k" + random.nextInt(31);
            boolean noreply = random.nextBoolean();
            if (random.nextBoolean()) {
                int flags = random.nextInt();
                long expiry = random.nextLong(); // Pinned LP64 storage parser narrows signed64 to int32.
                byte[] body = new byte[random.nextInt(65)];
                random.nextBytes(body);
                String payload = new String(body, StandardCharsets.ISO_8859_1);
                value = new Example("set " + key + " " + Integer.toUnsignedString(flags) + " " + expiry + " "
                        + body.length + (noreply ? " noreply" : "") + "\r\n" + payload + "\r\n",
                        CommandType.SET, List.of(key), payload, flags, (long) (int) expiry, null, null, null, noreply);
            } else {
                List<String> keys = List.of(key, "noreply", key, "last");
                value = simple("gets " + String.join("  ", keys) + "\n", CommandType.GETS, keys, false);
            }
            expected.add(value);
            wire.append(value.wire());
        }
        byte[] data = bytes(wire.toString());
        ProtocolBudgetTest.CountingBudget budget = new ProtocolBudgetTest.CountingBudget(true);
        try (TextProtocolDecoder decoder = new TextProtocolDecoder(8192, 1024, budget)) {
            int offset = 0;
            int commandIndex = 0;
            while (offset < data.length) {
                int count = Math.min(data.length - offset, random.nextInt(23) + 1);
                ByteBuffer fragment = ByteBuffer.wrap(data, offset, count);
                while (fragment.hasRemaining()) {
                    List<Command> found = new ArrayList<>();
                    feed(decoder, fragment, found);
                    if (!found.isEmpty()) {
                        expected.get(commandIndex++).check(found.getFirst());
                        decoder.release(found.getFirst());
                    }
                }
                offset += count;
            }
            assertEquals(expected.size(), commandIndex);
            budget.assertBalanced();
        }
    }

    static Stream<Arguments> invalidGrammar() {
        List<Arguments> out = new ArrayList<>();
        for (String line : List.of("", "wat", "mg k", "ms k", "md k", "ma k", "watch", "lru_crawler", "cache_memlimit", "shutdown", "stats sizes", "stats conns", "stats cachedump", "GET k")) {
            out.add(Arguments.of(line + "\r\n", TextProtocolDecoder.ErrorCode.UNKNOWN_COMMAND, false));
        }
        for (String line : List.of("get", "gets", "gat", "gats 0", "gat +1 a", "delete", "delete a x", "delete a 1 noreply extra", "delete a noreply extra", "incr a", "decr", "touch a", "flush_all x", "flush_all noreply extra", "version x", "quit noreply", "verbosity", "verbosity -1", "verbosity 2147483648", "stats items x", "stats reset noreply", "stats slabs x", "get " + "k".repeat(251), "incr k 18446744073709551616", "decr k -1", "incr k +1", "touch k 9223372036854775808", "touch k -9223372036854775809", "touch k -", "touch k 1x", "touch k 0 noReply")) {
            if (line.equals("touch k -")) continue; // Live1.6.45 bare-minus zero quirk, positive above.
            TextProtocolDecoder.ErrorCode error = line.startsWith("incr k ") || line.equals("decr k -1")
                    ? TextProtocolDecoder.ErrorCode.INVALID_DELTA
                    : line.equals("gat +1 a") || line.equals("flush_all x") || line.startsWith("touch k ") && !line.equals("touch k 0 noReply")
                    ? TextProtocolDecoder.ErrorCode.INVALID_EXPTIME : TextProtocolDecoder.ErrorCode.BAD_COMMAND_LINE;
            out.add(Arguments.of(line + "\r\n", error, false));
        }
        for (String line : List.of("set", "add k", "replace k 0", "append k 0 0", "prepend k 0 0 -1", "cas k 0 0 0", "cas k 0 0 0 18446744073709551616", "set k 4294967296 0 0", "set k -1 0 0", "set k +1 0 0", "set k 0 +1 0", "set k 0 0 +1", "set k 0 0 2147483646", "set k 0 0 9223372036854775807", "set k 0 0 18446744073709551616", "set k 0 0 0 wrong", "set k 0 0 0 noreply extra", "set " + "k".repeat(251) + " 0 0 0")) {
            if (line.equals("set k 0 +1 0")) continue; // Retained as positive example.
            boolean deferredCas = line.equals("cas k 0 0 0 18446744073709551616");
            out.add(Arguments.of(line + (deferredCas ? "\r\n\r\n" : "\r\n"), TextProtocolDecoder.ErrorCode.BAD_COMMAND_LINE, !deferredCas));
        }
        for (String line : List.of("get a\tb\n", "get a\0b\n", "get a\u007fb\n", "get a\u0080b\n", "get a\rb\n")) {
            out.add(Arguments.of(line, TextProtocolDecoder.ErrorCode.BAD_COMMAND_LINE, true));
        }
        for (String expiry : List.of("-9223372036854775808", "9223372036854775807", "-2147483649", "2147483648")) {
            for (String op : List.of("touch k ", "flush_all ", "gat ", "gats ")) {
                out.add(Arguments.of(op + expiry + (op.startsWith("ga") ? " k" : "") + "\r\n",
                        TextProtocolDecoder.ErrorCode.INVALID_EXPTIME, false));
            }
        }
        return out.stream();
    }

    @ParameterizedTest
    @MethodSource("invalidGrammar")
    void rejectsInvalidGrammarWithExactClassification(String wire, TextProtocolDecoder.ErrorCode code, boolean fatal) throws Exception {
        ProtocolBudgetTest.CountingBudget budget = new ProtocolBudgetTest.CountingBudget(true);
        try (TextProtocolDecoder decoder = new TextProtocolDecoder(8192, 1024, budget)) {
            assertNull(decoder.errorCode());
            assertFalse(decoder.errorNoreply());
            ProtocolException failure = assertThrows(ProtocolException.class, () -> decode(decoder, wire));
            assertEquals(code, decoder.errorCode());
            assertEquals(code.kind(), failure.kind());
            assertEquals(fatal, failure.closeConnection());
            assertFalse(decoder.errorNoreply());
            assertEquals(0, budget.attempts.size());
            if (fatal) assertThrows(IllegalStateException.class, decoder::nextCommand);
            else {
                Command next = decode(decoder, "version\r\n");
                assertEquals(CommandType.VERSION, next.type());
                assertEquals(code, decoder.errorCode());
                decoder.release(next);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    void lineLimitExcludesTerminatorAndKey250Accepted(String ending) throws Exception {
        String line = "get " + "k".repeat(250);
        try (TextProtocolDecoder decoder = new TextProtocolDecoder(line.length(), 1024, new ProtocolBudgetTest.CountingBudget(true))) {
            Command command = decode(decoder, line + ending);
            assertEquals(List.of("k".repeat(250)), keys(command));
            decoder.release(command);
        }
        try (TextProtocolDecoder decoder = new TextProtocolDecoder(line.length() - 1, 1024, new ProtocolBudgetTest.CountingBudget(true))) {
            ProtocolException error = assertThrows(ProtocolException.class, () -> decode(decoder, line + ending));
            assertTrue(error.closeConnection());
            assertEquals(TextProtocolDecoder.ErrorCode.BAD_COMMAND_LINE, decoder.errorCode());
        }
    }

    @Test
    void fixedErrorTextsAndKinds() {
        assertEquals(7, TextProtocolDecoder.ErrorCode.values().length);
        String[] texts = {"ERROR", "bad command line format", "bad data chunk", "object too large for cache", "out of memory storing object", "invalid numeric delta argument", "invalid exptime argument"};
        ProtocolException.Kind[] kinds = {ProtocolException.Kind.ERROR, ProtocolException.Kind.CLIENT_ERROR,
                ProtocolException.Kind.CLIENT_ERROR, ProtocolException.Kind.SERVER_ERROR, ProtocolException.Kind.SERVER_ERROR,
                ProtocolException.Kind.CLIENT_ERROR, ProtocolException.Kind.CLIENT_ERROR};
        String[] names = {"UNKNOWN_COMMAND", "BAD_COMMAND_LINE", "BAD_DATA_CHUNK", "OBJECT_TOO_LARGE", "OUT_OF_MEMORY", "INVALID_DELTA", "INVALID_EXPTIME"};
        for (int i = 0; i < names.length; i++) {
            TextProtocolDecoder.ErrorCode code = TextProtocolDecoder.ErrorCode.valueOf(names[i]);
            assertEquals(texts[i], code.text());
            assertEquals(kinds[i], code.kind());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"touch k 100000000000000000000", "touch k -100000000000000000000",
            "touch k /", "incr k 99999999999999999999", "incr k /", "incr k :",
            "cas k 0 0 0 -1", "cas k 0 0 0 +1", "set k 0 9223372036854775808 0",
            "set k 0 -9223372036854775809 0", "set k 0 - 0", "set k 0 0x 0",
            "set k 0 0 1x", "get a noreply extra", "set k 0 0 0000000000000000000000000000000000000"})
    void extraNumericAndLiteralKeyVectors(String line) throws Exception {
        try (TextProtocolDecoder decoder = new TextProtocolDecoder(8192, 1024, new ProtocolBudgetTest.CountingBudget(true))) {
            if (line.equals("get a noreply extra")) {
                Command command = decode(decoder, line + "\r\n");
                assertEquals(List.of("a", "noreply", "extra"), keys(command));
                assertFalse(command.noreply());
                decoder.release(command);
            } else if (line.endsWith("0000000000000000000000000000000000000")) {
                Command command = decode(decoder, line + "\r\n\r\n");
                assertArrayEquals(new byte[0], command.value());
                decoder.release(command);
            } else {
                assertThrows(ProtocolException.class, () -> decode(decoder, line + (line.startsWith("cas ") ? "\r\n\r\n" : "\r\n")));
                assertEquals(line.startsWith("touch ") ? TextProtocolDecoder.ErrorCode.INVALID_EXPTIME
                        : line.startsWith("incr ") ? TextProtocolDecoder.ErrorCode.INVALID_DELTA
                        : TextProtocolDecoder.ErrorCode.BAD_COMMAND_LINE, decoder.errorCode());
            }
        }
    }
}

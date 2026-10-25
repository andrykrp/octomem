package ru.octomem.protocol;

import static org.junit.jupiter.api.Assertions.*;
import static ru.octomem.protocol.TextProtocolDecoderTest.*;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProtocolBudgetTest {
    static final class CountingBudget implements ProtocolBudget {
        final boolean allow;
        final List<Integer> attempts = new ArrayList<>();
        final List<Integer> acquired = new ArrayList<>();
        final List<Integer> released = new ArrayList<>();
        long used;
        CountingBudget(boolean allow) { this.allow = allow; }
        @Override public boolean tryReserveBody(int bytes) {
            assertTrue(bytes >= 0);
            attempts.add(bytes);
            if (allow) {
                acquired.add(bytes);
                used += bytes + 32L;
            }
            return allow;
        }
        @Override public void releaseBody(int bytes) {
            assertTrue(released.size() < acquired.size(), "release without reservation");
            assertEquals(acquired.get(released.size()).intValue(), bytes);
            released.add(bytes);
            used -= bytes + 32L;
            assertTrue(used >= 0);
        }
        void assertBalanced() {
            assertEquals(acquired, released);
            assertEquals(0, used);
        }
    }

    static Stream<Arguments> drains() {
        return Stream.of(false, true).flatMap(oversize -> Stream.of(false, true).flatMap(noreply ->
                IntStream.rangeClosed(0, 10).mapToObj(split -> Arguments.of(oversize, noreply, split))));
    }

    @ParameterizedTest
    @MethodSource("drains")
    void rejectedBodyDrainsEveryBoundaryBeforeErrorAndKeepsPipeline(boolean oversize, boolean noreply, int split) throws Exception {
        CountingBudget budget = new CountingBudget(false);
        try (TextProtocolDecoder decoder = new TextProtocolDecoder(8192, oversize ? 40 : 1024, budget)) {
            decoder.append(ByteBuffer.wrap(bytes("set k 0 0 8" + (noreply ? " noreply" : "") + "\r\n")));
            assertNull(decoder.nextCommand());
            assertEquals(oversize ? List.of() : List.of(8), budget.attempts);
            assertNoBody(decoder);
            byte[] drain = bytes("quit\r\n\0x\r\n");
            decoder.append(ByteBuffer.wrap(drain, 0, split));
            if (split < drain.length) assertNull(decoder.nextCommand());
            ByteBuffer rest = ByteBuffer.wrap(bytes(new String(drain, split, drain.length - split, java.nio.charset.StandardCharsets.ISO_8859_1) + "version\r\n"));
            decoder.append(rest);
            ProtocolException error = assertThrows(ProtocolException.class, decoder::nextCommand);
            assertFalse(error.closeConnection());
            assertEquals(oversize ? TextProtocolDecoder.ErrorCode.OBJECT_TOO_LARGE : TextProtocolDecoder.ErrorCode.OUT_OF_MEMORY, decoder.errorCode());
            assertEquals(noreply, decoder.errorNoreply());
            assertEquals("version\r\n".length(), rest.remaining());
            List<Command> found = new ArrayList<>();
            feed(decoder, rest, found);
            assertEquals(CommandType.VERSION, found.getFirst().type());
            decoder.release(found.getFirst());
            budget.assertBalanced();
        }
    }

    static void assertNoBody(TextProtocolDecoder decoder) throws Exception {
        // Structural check only: no production method participates in the oracle.
        Field current = TextProtocolDecoder.class.getDeclaredField("command");
        current.setAccessible(true);
        Command command = (Command) current.get(decoder);
        assertNull(command.value(), "denied body must not allocate payload storage");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 7, 8, 9, 127, 128})
    void wholeReservationHeldThroughReleaseOrClose(int size) throws Exception {
        for (boolean release : List.of(false, true)) {
            CountingBudget budget = new CountingBudget(true);
            TextProtocolDecoder decoder = new TextProtocolDecoder(8192, 1024, budget);
            decoder.append(ByteBuffer.wrap(bytes("set k 0 0 " + size + "\r\n")));
            assertTrue(budget.attempts.isEmpty(), "admission follows parsed line");
            assertNull(decoder.nextCommand());
            assertEquals(List.of(size), budget.attempts);
            assertEquals(size + 32L, budget.used);
            Command command = decode(decoder, "a".repeat(size) + "\r\n");
            assertEquals(size + 32L, budget.used);
            if (release) decoder.release(command);
            decoder.close();
            decoder.close();
            budget.assertBalanced();
            assertEquals(List.of(size), budget.released);
        }
    }

    @Test
    void cancellationAtEveryPartialBodyAndTerminatorPosition() throws Exception {
        for (int prefix = 0; prefix <= 10; prefix++) {
            CountingBudget budget = new CountingBudget(true);
            TextProtocolDecoder decoder = new TextProtocolDecoder(8192, 1024, budget);
            decoder.append(ByteBuffer.wrap(bytes("set k 0 0 8\r\n")));
            assertNull(decoder.nextCommand());
            decoder.append(ByteBuffer.wrap(bytes("abcdefgh\r\n"), 0, prefix));
            decoder.nextCommand();
            decoder.close();
            decoder.close();
            budget.assertBalanced();
            assertEquals(List.of(8), budget.released);
        }
    }

    static Stream<Arguments> badBodies() {
        return Stream.of(true, false).flatMap(allow -> Stream.of(40, 1024).flatMap(max ->
                Stream.of("abcdefgh\n", "abcdefgh\rx", "abcdefghxx").map(wire -> Arguments.of(allow, max, wire))));
    }

    @ParameterizedTest
    @MethodSource("badBodies")
    void malformedBodyOverridesDeniedErrorAndReleasesExactlyOnce(boolean allow, int max, String body) throws Exception {
        CountingBudget budget = new CountingBudget(allow);
        TextProtocolDecoder decoder = new TextProtocolDecoder(8192, max, budget);
        ProtocolException error = assertThrows(ProtocolException.class,
                () -> decode(decoder, "set k 0 0 8 noreply\r\n" + body));
        assertTrue(error.closeConnection());
        assertEquals(TextProtocolDecoder.ErrorCode.BAD_DATA_CHUNK, decoder.errorCode());
        assertFalse(decoder.errorNoreply());
        decoder.close();
        decoder.close();
        budget.assertBalanced();
    }

    @Test
    void alignedFullRecordBoundaryNotJustValueOrSizeClass() throws Exception {
        for (int keySize : new int[]{1, 7, 8, 249, 250}) {
            for (int valueSize : new int[]{0, 1, 7, 8, 9}) {
                int aligned = (32 + keySize + valueSize + 7) & ~7;
                String wire = "set " + "k".repeat(keySize) + " 0 0 " + valueSize + "\r\n" + "x".repeat(valueSize) + "\r\n";
                for (int max : new int[]{aligned - 1, aligned, aligned + 1}) {
                    CountingBudget budget = new CountingBudget(true);
                    try (TextProtocolDecoder decoder = new TextProtocolDecoder(8192, max, budget)) {
                        if (max < aligned) {
                            assertThrows(ProtocolException.class, () -> decode(decoder, wire));
                            assertEquals(TextProtocolDecoder.ErrorCode.OBJECT_TOO_LARGE, decoder.errorCode());
                            assertTrue(budget.attempts.isEmpty());
                        } else decoder.release(decode(decoder, wire));
                    }
                    budget.assertBalanced();
                }
            }
        }
    }

    @Test
    void maxTrustedDeclaredBytesStaysBoundedAndCloseDoesNotReleaseUnacquired() throws Exception {
        CountingBudget budget = new CountingBudget(false);
        try (TextProtocolDecoder decoder = new TextProtocolDecoder(8192, Integer.MAX_VALUE, budget)) {
            decoder.append(ByteBuffer.wrap(bytes("set k 0 0 2147483645\r\n")));
            assertNull(decoder.nextCommand());
            assertNoBody(decoder);
            assertTrue(budget.attempts.isEmpty());
            decoder.append(ByteBuffer.wrap(bytes("version\r\n")));
            assertNull(decoder.nextCommand());
            assertNull(decoder.errorCode(), "cannot report oversized error until body drain finishes");
        }
        budget.assertBalanced();
        CountingBudget denied = new CountingBudget(false);
        try (TextProtocolDecoder decoder = new TextProtocolDecoder(8192, Integer.MAX_VALUE, denied)) {
            decoder.append(ByteBuffer.wrap(bytes("set k 0 0 1000000000\r\n")));
            assertNull(decoder.nextCommand());
            assertEquals(List.of(1000000000), denied.attempts);
            assertNoBody(decoder);
        }
        denied.assertBalanced();
    }

    @Test
    void actualBodyAllocationFailureReturnsReservationInBoundedChildJvm() throws Exception {
        Process process = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-Xmx16m",
                "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1", "-cp", System.getProperty("java.class.path"),
                BodyAllocationFailureProbe.class.getName()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), "allocation probe timed out");
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("BODY_ALLOCATION_FAILURE_RELEASED_ONCE"), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    /** Runs separately so intentional OOME cannot compromise the test JVM. */
    public static final class BodyAllocationFailureProbe {
        public static void main(String[] args) throws Exception {
            int[] calls = new int[2];
            ProtocolBudget budget = new ProtocolBudget() {
                public boolean tryReserveBody(int size) {
                    if (size != 67108864) throw new AssertionError("wrong reservation");
                    calls[0]++;
                    return true;
                }
                public void releaseBody(int size) {
                    if (size != 67108864) throw new AssertionError("wrong release");
                    calls[1]++;
                }
            };
            TextProtocolDecoder decoder = new TextProtocolDecoder(8192, 134217728, budget);
            decoder.append(ByteBuffer.wrap("set k 0 0 67108864\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            boolean failed = false;
            try { decoder.nextCommand(); } catch (OutOfMemoryError expected) { failed = true; }
            if (!failed) throw new AssertionError("allocation unexpectedly succeeded");
            decoder.close();
            decoder.close();
            if (calls[0] != 1 || calls[1] != 1) throw new AssertionError("unbalanced reservation");
            try {
                decoder.nextCommand();
                throw new AssertionError("decoder remained open after allocation failure");
            } catch (IllegalStateException expected) {
                System.out.println("BODY_ALLOCATION_FAILURE_RELEASED_ONCE");
            }
        }
    }
}

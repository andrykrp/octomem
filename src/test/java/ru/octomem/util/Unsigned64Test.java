package ru.octomem.util;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Uses arbitrary precision arithmetic, not the production parser, as the oracle. */
class Unsigned64Test {
    private static final BigInteger MODULUS = BigInteger.ONE.shiftLeft(64);
    private static final long[] BOUNDARIES = {0, 1, 2, Long.MAX_VALUE - 1, Long.MAX_VALUE,
            Long.MIN_VALUE, Long.MIN_VALUE + 1, -2, -1};

    @Test
    void parseAndFormatAllBoundaryBitPatterns() {
        for (long bits : BOUNDARIES) {
            String expected = unsigned(bits).toString();
            assertEquals(bits, Unsigned64.parse(expected));
            assertEquals(bits, Unsigned64.parse("0".repeat(100) + expected));
            assertEquals(expected, Unsigned64.format(bits));
        }
        assertEquals(-1L, Unsigned64.parse("18446744073709551615"));
        assertEquals(Long.MIN_VALUE, Unsigned64.parse("9223372036854775808"));
    }

    static Stream<String> invalidUnsigned() {
        return Stream.of("", " ", "\t", "\n", "+0", "-0", "+1", "-1", " 1", "1 ", "1\n", "1\r",
                "1.0", "1e3", "1_0", "0x10", "1k", "\u0661", "\uff11", "1\u0660", "1\u0000",
                "\u00a01", "18446744073709551616", "18446744073709551619", "99999999999999999999",
                "9".repeat(100), "00018446744073709551616");
    }

    @ParameterizedTest
    @MethodSource("invalidUnsigned")
    void invalidUnsignedGrammarAndOverflowAreRejected(String input) {
        assertThrows(IllegalArgumentException.class, () -> Unsigned64.parse(input), input);
    }

    @Test
    void nullIsRejectedAsNull() {
        assertThrows(NullPointerException.class, () -> Unsigned64.parse(null));
    }

    @Test
    void arithmeticBoundaryCrossProductMatchesBigInteger() {
        for (long left : BOUNDARIES) {
            for (long right : BOUNDARIES) {
                checkArithmetic(left, right);
            }
        }
    }

    @Test
    void everyPowerOfTwoAndItsNeighborsMatchOracle() {
        for (int bit = 0; bit < 64; bit++) {
            BigInteger power = BigInteger.ONE.shiftLeft(bit);
            for (int delta = -1; delta <= 1; delta++) {
                BigInteger value = power.add(BigInteger.valueOf(delta));
                long bits = value.longValue();
                assertEquals(bits, Unsigned64.parse(value.toString()));
                assertEquals(value.toString(), Unsigned64.format(bits));
                for (long boundary : BOUNDARIES) {
                    checkArithmetic(bits, boundary);
                    checkArithmetic(boundary, bits);
                }
            }
        }
    }

    @Test
    void seededRoundTripsAndArithmeticMatchBigInteger() {
        Random random = new Random(0x640003L);
        for (int i = 0; i < 10000; i++) {
            long left = random.nextLong();
            long right = random.nextLong();
            String decimal = unsigned(left).toString();
            assertEquals(decimal, Unsigned64.format(left));
            assertEquals(left, Unsigned64.parse(decimal));
            checkArithmetic(left, right);
        }
    }

    private static void checkArithmetic(long left, long right) {
        BigInteger a = unsigned(left);
        BigInteger b = unsigned(right);
        String context = a + ", " + b;
        assertEquals(a.add(b).mod(MODULUS).longValue(), Unsigned64.addModulo(left, right), context);
        assertEquals(a.subtract(b).max(BigInteger.ZERO).longValue(), Unsigned64.subtractFloorZero(left, right), context);
    }

    private static BigInteger unsigned(long bits) {
        BigInteger value = BigInteger.valueOf(bits);
        return bits < 0 ? value.add(MODULUS) : value;
    }
}

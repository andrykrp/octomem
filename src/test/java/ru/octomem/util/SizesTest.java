package ru.octomem.util;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Independent ASCII grammar and binary-unit boundary checks. */
class SizesTest {
    @Test
    void positiveSizesAndCaseInsensitiveBinarySuffixes() {
        String[] suffixes = {"", "k", "K", "m", "M", "g", "G"};
        long[] factors = {1, 1024, 1024, 1048576, 1048576, 1073741824, 1073741824};
        for (int i = 0; i < suffixes.length; i++) {
            assertEquals(factors[i], Sizes.parseBytes("1" + suffixes[i]));
            assertEquals(12 * factors[i], Sizes.parseBytes("00012" + suffixes[i]));
            assertEquals(factors[i], Sizes.parseBytes("0".repeat(100) + "1" + suffixes[i]));
            long max = Long.MAX_VALUE / factors[i];
            assertEquals(max * factors[i], Sizes.parseBytes(max + suffixes[i]));
            String overflow = BigInteger.valueOf(max).add(BigInteger.ONE) + suffixes[i];
            assertThrows(IllegalArgumentException.class, () -> Sizes.parseBytes(overflow));
        }
    }

    static Stream<String> invalidSizes() {
        return Stream.of("", " ", "\t", "\n", "0", "00", "0k", "000G", "+1", "-1", "1.0",
                "1e3", "1_000", "1,000", "1 ", " 1", "1\t", "1\n", "1 k", "k", "1kb", "1KiB",
                "1b", "1t", "1kk", "1m0", "1\u0000", "\u0661", "\uff11", "1\u00a0", "\u22121",
                "9223372036854775808", "18446744073709551616", "9".repeat(100));
    }

    @ParameterizedTest
    @MethodSource("invalidSizes")
    void invalidGrammarAndOverflowAreRejected(String input) {
        assertThrows(IllegalArgumentException.class, () -> Sizes.parseBytes(input), input);
    }

    @Test
    void nullIsRejectedAsNull() {
        assertThrows(NullPointerException.class, () -> Sizes.parseBytes(null));
    }

    @Test
    void seededValidSizesMatchBigIntegerOracle() {
        Random random = new Random(0x530003L);
        String[] suffixes = {"", "k", "M", "g"};
        for (int i = 0; i < 2000; i++) {
            int unit = random.nextInt(4);
            BigInteger factor = BigInteger.valueOf(1024).pow(unit);
            BigInteger value = new BigInteger(63, random).mod(BigInteger.valueOf(Long.MAX_VALUE).divide(factor))
                    .add(BigInteger.ONE);
            String text = "0".repeat(i % 5) + value + suffixes[unit];
            assertEquals(value.multiply(factor).longValueExact(), Sizes.parseBytes(text), text);
        }
    }
}

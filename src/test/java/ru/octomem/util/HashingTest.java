package ru.octomem.util;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Independently computed FNV-1a vectors and arbitrary precision recurrence checks. */
class HashingTest {
    @Test
    void knownAsciiVectorsMatchFnv1a64() {
        String[] inputs = {"", "a", "b", "c", "foobar", "hello"};
        String[] hex = {"cbf29ce484222325", "af63dc4c8601ec8c", "af63df4c8601f1a5",
                "af63de4c8601eff2", "85944171f73967e8", "a430d84680aabd0b"};
        for (int i = 0; i < inputs.length; i++) {
            byte[] bytes = inputs[i].getBytes(StandardCharsets.US_ASCII);
            long expected = new BigInteger(hex[i], 16).longValue();
            assertEquals(expected, oracle(bytes), "Independent vector self-check: " + inputs[i]);
            assertEquals(expected, Hashing.hash64(bytes), inputs[i]);
        }
    }

    @Test
    void everyUnsignedByteAndBinarySequenceMatchesOracle() {
        byte[] all = new byte[256];
        for (int i = 0; i < all.length; i++) {
            all[i] = (byte) i;
            byte[] single = {(byte) i};
            assertEquals(oracle(single), Hashing.hash64(single), "unsigned byte " + i);
        }
        byte[] before = all.clone();
        assertEquals(oracle(all), Hashing.hash64(all));
        assertArrayEquals(before, all, "Hashing must not mutate keys");
        assertEquals(oracle(new byte[] {0, -1, 0, -128}), Hashing.hash64(new byte[] {0, -1, 0, -128}));
    }

    @Test
    void seededBinaryInputsMatchArbitraryPrecisionOracle() {
        Random random = new Random(0xf1a0003L);
        for (int i = 0; i < 1000; i++) {
            byte[] bytes = new byte[random.nextInt(1025)];
            random.nextBytes(bytes);
            byte[] before = bytes.clone();
            assertEquals(oracle(bytes), Hashing.hash64(bytes), "case " + i);
            assertEquals(Hashing.hash64(before), Hashing.hash64(bytes));
            assertArrayEquals(before, bytes);
        }
    }

    @Test
    void nullIsRejectedAsNull() {
        assertThrows(NullPointerException.class, () -> Hashing.hash64(null));
    }

    private static long oracle(byte[] input) {
        BigInteger hash = new BigInteger("14695981039346656037");
        BigInteger prime = new BigInteger("1099511628211");
        BigInteger modulus = BigInteger.ONE.shiftLeft(64);
        for (byte value : input) {
            hash = hash.xor(BigInteger.valueOf(Byte.toUnsignedInt(value))).multiply(prime).mod(modulus);
        }
        return hash.longValue();
    }
}

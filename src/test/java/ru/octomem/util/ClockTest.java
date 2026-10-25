package ru.octomem.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Verifies independent deterministic readings and the public system adapter. */
class ClockTest {
    @Test
    void mutableReadingsAreIndependentAndDoNotAdvanceOnRead() {
        MutableClock clock = new MutableClock();
        assertEquals(0, clock.currentTimeSeconds());
        assertEquals(0, clock.nanoTime());
        clock.setSeconds(-100);
        clock.setNanos(987654321);
        clock.advanceSeconds(150);
        assertEquals(50, clock.currentTimeSeconds());
        assertEquals(987654321, clock.nanoTime());
        clock.advanceNanos(-987654322);
        for (int i = 0; i < 100; i++) {
            assertEquals(50, clock.currentTimeSeconds());
            assertEquals(-1, clock.nanoTime());
        }
        clock.advanceSeconds(-51);
        clock.advanceNanos(2);
        assertEquals(-1, clock.currentTimeSeconds());
        assertEquals(1, clock.nanoTime());
    }

    @Test
    void advancesRejectBothOverflowDirectionsWithoutChangingEitherReading() {
        MutableClock clock = new MutableClock(Long.MAX_VALUE, Long.MIN_VALUE);
        assertThrows(ArithmeticException.class, () -> clock.advanceSeconds(1));
        assertThrows(ArithmeticException.class, () -> clock.advanceNanos(-1));
        assertEquals(Long.MAX_VALUE, clock.currentTimeSeconds());
        assertEquals(Long.MIN_VALUE, clock.nanoTime());
        clock.setSeconds(Long.MIN_VALUE);
        clock.setNanos(Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> clock.advanceSeconds(-1));
        assertThrows(ArithmeticException.class, () -> clock.advanceNanos(1));
        assertEquals(Long.MIN_VALUE, clock.currentTimeSeconds());
        assertEquals(Long.MAX_VALUE, clock.nanoTime());
        clock.advanceSeconds(Long.MAX_VALUE);
        clock.advanceNanos(Long.MIN_VALUE);
        assertEquals(-1, clock.currentTimeSeconds());
        assertEquals(-1, clock.nanoTime());
    }

    @Test
    void expirationBoundaryCanBeCrossedWithoutSleeping() {
        MutableClock clock = new MutableClock(100, -17);
        long deadline = clock.currentTimeSeconds() + 3;
        clock.advanceSeconds(2);
        assertFalse(clock.currentTimeSeconds() >= deadline);
        clock.advanceSeconds(1);
        assertTrue(clock.currentTimeSeconds() >= deadline);
        assertEquals(-17, clock.nanoTime());
    }

    @Test
    void publicSystemClockReadingsAreBracketedByJdkReadings() {
        Clock clock = new SystemClock();
        long beforeSeconds = Math.floorDiv(System.currentTimeMillis(), 1000);
        long seconds = clock.currentTimeSeconds();
        long afterSeconds = Math.floorDiv(System.currentTimeMillis(), 1000);
        assertTrue(seconds >= Math.min(beforeSeconds, afterSeconds)
                && seconds <= Math.max(beforeSeconds, afterSeconds));
        long beforeNanos = System.nanoTime();
        long nanos = clock.nanoTime();
        long afterNanos = System.nanoTime();
        assertTrue(nanos - beforeNanos >= 0 && afterNanos - nanos >= 0);
    }
}

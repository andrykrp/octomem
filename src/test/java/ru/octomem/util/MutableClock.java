package ru.octomem.util;

/** Single-threaded test clock with independently controlled wall and monotonic readings. */
public final class MutableClock implements Clock {
    private long seconds;
    private long nanos;

    /** Creates a clock whose two readings are zero. */
    public MutableClock() {}

    /**
     * Creates a clock with explicit independent readings.
     * @param seconds initial epoch seconds
     * @param nanos initial monotonic reading
     */
    public MutableClock(long seconds, long nanos) {
        this.seconds = seconds;
        this.nanos = nanos;
    }

    /** {@inheritDoc} */
    @Override
    public long currentTimeSeconds() { return seconds; }

    /** {@inheritDoc} */
    @Override
    public long nanoTime() { return nanos; }

    /**
     * Sets wall time without changing the monotonic reading.
     * @param seconds new epoch seconds
     */
    public void setSeconds(long seconds) { this.seconds = seconds; }

    /**
     * Sets the monotonic reading without changing wall time.
     * @param nanos new monotonic reading
     */
    public void setNanos(long nanos) { this.nanos = nanos; }

    /**
     * Adds a signed delta to wall time, leaving the clock unchanged on overflow.
     * @param delta signed seconds to add
     * @throws ArithmeticException if the result is outside the signed long range
     */
    public void advanceSeconds(long delta) { seconds = Math.addExact(seconds, delta); }

    /**
     * Adds a signed delta to monotonic time, leaving the clock unchanged on overflow.
     * @param delta signed nanoseconds to add
     * @throws ArithmeticException if the result is outside the signed long range
     */
    public void advanceNanos(long delta) { nanos = Math.addExact(nanos, delta); }
}

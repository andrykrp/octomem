package ru.octomem.engine;

/** Immutable outcome of an atomic unsigned counter update.
 * @since 1.0
 */
public interface CounterResult {
    /** Distinguishes a value from failures without sentinel numeric values. */
    enum Status {
        /** The new value was published. */
        VALUE,
        /** No live key exists. */
        NOT_FOUND,
        /** The stored value is not an unsigned decimal counter. */
        NON_NUMERIC,
        /** Capacity was denied; the original version remains unchanged. */
        OUT_OF_MEMORY
    }

    /** Returns the outcome.
     * @return non-null status
     */
    Status status();

    /**
     * Returns the unsigned 64-bit result as raw long bits.
     * @return new value, meaningful only for Status.VALUE; otherwise unspecified
     */
    long value();
}

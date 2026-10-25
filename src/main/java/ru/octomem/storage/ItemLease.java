package ru.octomem.storage;

/**
 * Owns one pin on an immutable record; handle and close are owner-thread confined.
 * Other readers need separate pins. A retired block is reusable only after the
 * final pin closes; releasing a live record's pin does not retire the record.
 *
 * @since 1.0
 */
public interface ItemLease extends AutoCloseable {
    /**
     * Returns a borrowed version valid until this lease closes.
     * @return non-null item
     * @throws IllegalStateException if closed
     */
    Item item();

    /** Releases the pin once; subsequent calls do nothing and never throw. */
    @Override
    void close();
}

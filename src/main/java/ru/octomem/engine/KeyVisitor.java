package ru.octomem.engine;

/**
 * Receives a borrowed key slice without allocating a per-key object.
 * The backing array must not be changed or retained beyond command release.
 * Callbacks run synchronously on the caller's thread, never under cache locks.
 *
 * @since 1.0
 */
@FunctionalInterface
public interface KeyVisitor {
    /**
     * Visits one input occurrence, including duplicates.
     *
     * @param backing non-null immutable borrowed command bytes
     * @param offset nonnegative first byte index
     * @param length key length in [1, 250], within the backing array
     */
    void accept(byte[] backing, int offset, int length);
}

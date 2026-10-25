package ru.octomem.stats;

/**
 * Immutable coherent size-class statistics using JDK-only types.
 * Counters are nonnegative; totalChunks equals usedChunks plus freeChunks.
 * Retained/retired and reserved chunks remain used until released. This minimal
 * coordinator-approved T-004 interface does not implement the T-005 registry.
 *
 * @since 1.0
 */
public interface SlabClassStats {
    /** Returns the class identifier.
     * @return nonnegative allocator-local identifier
     */
    int classId();

    /** Returns the block size.
     * @return positive bytes per chunk
     */
    int chunkSize();

    /** Returns allocated pages.
     * @return nonnegative page count
     */
    long totalPages();

    /** Returns total chunks.
     * @return nonnegative chunk count
     */
    long totalChunks();

    /** Returns occupied chunks, including retained and reserved chunks.
     * @return nonnegative used count
     */
    long usedChunks();

    /** Returns reusable chunks.
     * @return nonnegative free count
     */
    long freeChunks();
}

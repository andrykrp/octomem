package ru.octomem.engine;

/**
 * Ordered, repeatable borrowed command keys, valid until command release.
 * Implementations are confined to the connection event loop. Each traversal is
 * linear and sequential: no per-key arrays, views, offset tables, recursion,
 * indexed rescans or production List adapters. Input duplicates are preserved.
 *
 * @since 1.0
 */
public interface KeySequence {
    /**
     * Counts input occurrences in one linear scan without per-key allocation.
     * @return nonnegative count including duplicates and eventual misses
     */
    int size();

    /**
     * Visits every occurrence in input order using borrowed byte slices.
     * The visitor must not mutate backing bytes; failures propagate to the caller.
     * @param visitor non-null synchronous visitor
     * @throws NullPointerException if visitor is null
     */
    void forEachKey(KeyVisitor visitor);
}

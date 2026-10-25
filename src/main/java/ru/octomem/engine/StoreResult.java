package ru.octomem.engine;

/** Outcome of an atomic store; failures preserve the original version.
 * @since 1.0
 */
public enum StoreResult {
    /** A new version was published. */
    STORED,
    /** The add/replace/append/prepend precondition failed. */
    NOT_STORED,
    /** The expected CAS token did not match. */
    EXISTS,
    /** CAS found no live key. */
    NOT_FOUND,
    /** The complete aligned record exceeds the configured item limit. */
    TOO_LARGE,
    /** A recoverable capacity reservation or allocation failed. */
    OUT_OF_MEMORY,
    /** The store arguments are invalid. */
    INVALID
}

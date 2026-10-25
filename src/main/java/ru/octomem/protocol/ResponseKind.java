package ru.octomem.protocol;

/** Text response forms; no kind represents QUIT or suppressed output.
 * @since 1.0
 */
public enum ResponseKind {
    /** Snapshot values followed by END. */
    RETRIEVAL,
    /** Successful store. */
    STORED,
    /** Failed conditional store. */
    NOT_STORED,
    /** CAS mismatch. */
    EXISTS,
    /** Missing key. */
    NOT_FOUND,
    /** Successful deletion. */
    DELETED,
    /** Successful touch. */
    TOUCHED,
    /** Successful administrative command. */
    OK,
    /** Statistics reset. */
    RESET,
    /** Unsigned counter value. */
    NUMBER,
    /** Statistics lines followed by END. */
    STATS,
    /** Product version. */
    VERSION,
    /** Unknown command. */
    ERROR,
    /** Client error with a safe protocol message. */
    CLIENT_ERROR,
    /** Server error with a safe protocol message. */
    SERVER_ERROR
}

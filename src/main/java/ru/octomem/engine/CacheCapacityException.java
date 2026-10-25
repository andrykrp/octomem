package ru.octomem.engine;

/**
 * Checked recoverable admission failure, never a miss or partial mutation.
 * Carries no input, arbitrary message, cause or stack trace. Protocol mapping is
 * the fixed SERVER_ERROR out of memory storing object, not exception rendering.
 * This does not promise recovery from an arbitrary JVM OutOfMemoryError.
 *
 * @since 1.0
 */
public final class CacheCapacityException extends Exception {
    private static final long serialVersionUID = 1L;

    /** Creates a bounded failure with no mutable diagnostic attachments. */
    public CacheCapacityException() {
        super("out of memory storing object", null, false, false);
    }
}

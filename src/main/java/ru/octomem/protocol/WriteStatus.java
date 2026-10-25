package ru.octomem.protocol;

/** Progress of one resumable response.
 * @since 1.0
 */
public enum WriteStatus {
    /** More output remains; resume only when scheduling/capacity permits. */
    IN_PROGRESS,
    /** Last bytes transferred and owned resources closed; do not write again. */
    COMPLETE
}

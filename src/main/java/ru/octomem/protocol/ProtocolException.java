package ru.octomem.protocol;

import java.util.Objects;

/**
 * Checked bounded protocol failure, containing neither raw input nor a cause.
 * Fixed diagnostic messages classify failures, not complete wire text: the
 * protocol handler selects the normative safe message for its framing context.
 * No arbitrary diagnostic constructor, suppression or writable stack is exposed.
 *
 * @since 1.0
 */
public final class ProtocolException extends Exception {
    private static final long serialVersionUID = 1L;

    /** Error family used for safe response selection. */
    public enum Kind {
        /** Unknown command. */
        ERROR,
        /** Invalid client command or frame. */
        CLIENT_ERROR,
        /** Server-side resource or processing failure. */
        SERVER_ERROR
    }

    /** The fixed error family, without client-supplied diagnostic data. */
    private final Kind kind;
    /** Whether the connection must close because framing cannot continue safely. */
    private final boolean closeConnection;

    /**
     * Creates a classified failure with a fixed family message and no cause.
     * @param kind non-null error family
     * @param closeConnection whether framing cannot safely continue
     * @throws NullPointerException if kind is null
     */
    public ProtocolException(Kind kind, boolean closeConnection) {
        super(Objects.requireNonNull(kind, "kind").name(), null, false, false);
        this.kind = kind;
        this.closeConnection = closeConnection;
    }

    /** Returns the error family.
     * @return non-null family
     */
    public Kind kind() {
        return kind;
    }

    /** Reports whether parsing must stop for this connection.
     * @return true if the stream must be closed
     */
    public boolean closeConnection() {
        return closeConnection;
    }
}

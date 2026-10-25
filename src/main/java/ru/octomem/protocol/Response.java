package ru.octomem.protocol;

import java.util.List;
import ru.octomem.engine.RetrievalSnapshot;

/**
 * Event-loop-confined response owning its already admitted command snapshot.
 * Successful construction/transfer takes ownership from the caller; failure
 * leaves cleanup with that caller. Successful encode transfers lifecycle
 * responsibility to the writer. No further live-table lookups are permitted.
 * All accessors require an open response; unused numeric fields are unspecified.
 *
 * @since 1.0
 */
public interface Response {
    /** Returns the response form.
     * @return non-null kind
     */
    ResponseKind kind();

    /** Returns the owned snapshot without transferring it to the accessor caller.
     * @return non-null for RETRIEVAL (including no hits), otherwise null
     */
    RetrievalSnapshot snapshot();

    /** Controls VALUE CAS output.
     * @return true for GETS/GATS retrievals, false for GET/GAT
     */
    boolean includeCas();

    /** Returns the counter result.
     * @return unsigned 64-bit bits for NUMBER
     */
    long number();

    /** Returns safe protocol text, never raw internal exception messages.
     * @return non-null version/error text for VERSION/CLIENT_ERROR/SERVER_ERROR,
     * otherwise null; no framing CR/LF is included
     */
    String text();

    /**
     * Exposes ordered statistics without full serialized response materialization.
     * Production uses a bounded/lazy read-only representation; no unbounded
     * formatted list. Each entry is one STAT line without CRLF or final END;
     * strings and formatting storage remain covered by outbound/control budgets.
     * @return non-null read-only list with non-null entries for STATS, otherwise null
     */
    List<String> statsLines();

    /**
     * Idempotently releases the entire snapshot and response resources.
     * Required on completion, cancellation, failure, disconnect and shutdown,
     * including hits never visited by the writer. Confined to the owning loop.
     */
    void close();
}

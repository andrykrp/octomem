package ru.octomem.protocol;

import java.util.List;
import java.util.Objects;
import ru.octomem.engine.RetrievalSnapshot;

/**
 * Connection-confined response with immutable outcome fields and idempotent
 * snapshot cleanup. Construction transfers snapshot ownership only on success.
 * Its wrapper and lazy statistics are covered by the composing connection's
 * 8192-byte dispatcher control reservation. Detached class counter images carry
 * a separate engine snapshot-pool reservation released by this response.
 * @since 1.0
 */
public final class CacheResponse implements Response {
    private final ResponseKind kind;
    private final RetrievalSnapshot snapshot;
    private final boolean includeCas;
    private final long number;
    private final String text;
    private final List<String> statsLines;
    private boolean closed;

    CacheResponse(ResponseKind kind, RetrievalSnapshot snapshot, boolean includeCas,
            long number, String text, List<String> statsLines) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.snapshot = snapshot;
        this.includeCas = includeCas;
        this.number = number;
        this.text = text;
        this.statsLines = statsLines;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Response is closed");
    }

    @Override public ResponseKind kind() { requireOpen(); return kind; }
    @Override public RetrievalSnapshot snapshot() { requireOpen(); return snapshot; }
    @Override public boolean includeCas() { requireOpen(); return includeCas; }
    @Override public long number() { requireOpen(); return number; }
    @Override public String text() { requireOpen(); return text; }
    @Override public List<String> statsLines() { requireOpen(); return statsLines; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { if (snapshot != null) snapshot.close(); }
        finally { if (statsLines instanceof CacheCommandDispatcher.ClassLines lines) lines.close(); }
    }
}

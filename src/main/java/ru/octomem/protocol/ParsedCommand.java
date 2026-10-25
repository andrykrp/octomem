package ru.octomem.protocol;

import java.util.Objects;
import ru.octomem.engine.KeySequence;
import ru.octomem.engine.KeyVisitor;
import ru.octomem.engine.StorageAdmission;

/**
 * Decoder-owned command and sequential key view with no per-key objects.
 * Backing and body are borrowed read-only until the decoder releases this command.
 * All access after release fails; consumers must not retain borrowed arrays beyond
 * release. Confined to the connection event loop, including visitor callbacks.
 *
 * @since 1.0
 */
final class ParsedCommand implements AdmittedCommand, KeySequence {
    byte[] line;
    byte[] body;
    CommandType operation;
    int keysStart;
    int keysEnd;
    int flags;
    int level;
    long expiry;
    long cas;
    long delta;
    boolean noreply;
    StorageAdmission.Token admission;

    ParsedCommand(byte[] line, CommandType operation) {
        this.line = line;
        this.operation = operation;
    }

    void clear() {
        if(admission!=null) { admission.close(); admission=null; }
        line = null;
        body = null;
    }

    private void requireOpen() {
        if (line == null) {
            throw new IllegalStateException("Command released");
        }
    }

    @Override public CommandType type() { requireOpen(); return operation; }
    @Override public StorageAdmission.Token admission() { requireOpen(); return admission; }
    @Override public KeySequence keys() { requireOpen(); return this; }
    @Override public int flags() { requireOpen(); return flags; }
    @Override public long exptime() { requireOpen(); return expiry; }
    @Override public byte[] value() { requireOpen(); return body; }
    @Override public long casId() { requireOpen(); return cas; }
    @Override public long delta() { requireOpen(); return delta; }
    @Override public int level() { requireOpen(); return level; }
    @Override public boolean noreply() { requireOpen(); return noreply; }

    @Override
    public int size() {
        requireOpen();
        int count = 0;
        int cursor = keysStart;
        while (cursor < keysEnd) {
            if (line[cursor] == ' ') {
                cursor++;
            } else {
                count++;
                while (cursor < keysEnd && line[cursor] != ' ') {
                    cursor++;
                }
            }
        }
        return count;
    }

    @Override
    public void forEachKey(KeyVisitor visitor) {
        Objects.requireNonNull(visitor, "visitor");
        requireOpen();
        int cursor = keysStart;
        while (cursor < keysEnd) {
            if (line[cursor] == ' ') {
                cursor++;
            } else {
                int start = cursor;
                while (cursor < keysEnd && line[cursor] != ' ') {
                    cursor++;
                }
                visitor.accept(line, start, cursor - start);
                requireOpen();
            }
        }
    }
}

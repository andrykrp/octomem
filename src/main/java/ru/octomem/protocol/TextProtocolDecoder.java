package ru.octomem.protocol;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import ru.octomem.engine.StorageAdmission;
import ru.octomem.engine.StoreResult;

/**
 * Incremental ASCII decoder, confined to one connection event loop.
 * The transport reserves a line slot charged {@code 2L * maxLineBytes + 256}
 * before append, and retains it through release/error handling. Append stops at
 * a line boundary until nextCommand requests parsing and body admission; callers
 * retain unconsumed pipeline bytes and alternate append/nextCommand as needed.
 * One returned command blocks all progress until explicit release after its
 * response lifecycle. No input ByteBuffer is retained, and no body is cloned.
 * Close is required on cancellation, disconnect, shutdown and handler failure.
 *
 * @since 1.0
 */
public final class TextProtocolDecoder implements ProtocolDecoder, AutoCloseable {
    /** Fixed safe error detail supplementing the frozen ProtocolException family. */
    public enum ErrorCode {
        /** Unknown or unsupported operation. */
        UNKNOWN_COMMAND(ProtocolException.Kind.ERROR, "ERROR"),
        /** Invalid command grammar or argument. */
        BAD_COMMAND_LINE(ProtocolException.Kind.CLIENT_ERROR, "bad command line format"),
        /** Invalid unsigned arithmetic delta. */
        INVALID_DELTA(ProtocolException.Kind.CLIENT_ERROR, "invalid numeric delta argument"),
        /** Invalid signed 32-bit expiry argument on a non-storage command. */
        INVALID_EXPTIME(ProtocolException.Kind.CLIENT_ERROR, "invalid exptime argument"),
        /** Invalid mandatory body terminator. */
        BAD_DATA_CHUNK(ProtocolException.Kind.CLIENT_ERROR, "bad data chunk"),
        /** Complete aligned record exceeds the configured item limit. */
        OBJECT_TOO_LARGE(ProtocolException.Kind.SERVER_ERROR, "object too large for cache"),
        /** Whole-body admission was denied. */
        OUT_OF_MEMORY(ProtocolException.Kind.SERVER_ERROR, "out of memory storing object");

        private final ProtocolException.Kind kind;
        private final String text;

        ErrorCode(ProtocolException.Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }

        /** @return frozen exception family for this safe error */
        public ProtocolException.Kind kind() { return kind; }
        /** @return fixed detail without family/CRLF, or ERROR for the bare error */
        public String text() { return text; }
    }

    private final int maxLineBytes;
    private final int itemMaxSize;
    private final ProtocolBudget budget;
    private final StorageAdmission admission;
    private byte[] line;
    private ParsedCommand command;
    private int length;
    private int cursor;
    private int tokenStart;
    private int tokenEnd;
    private long remainingBody;
    private int bodyOffset;
    private int terminator;
    private int reservedBody = -1;
    private boolean lineCr;
    private boolean lineReady;
    private boolean bodyMode;
    private boolean returned;
    private boolean closed;
    private boolean fatal;
    private ErrorCode pendingError;
    private ErrorCode lastError;
    private boolean lastNoreply;

    /**
     * Creates a lazy decoder; no buffers are allocated until admitted input.
     * Record size is the current storage layout: 32-byte header, key and payload,
     * rounded up to eight bytes, not allocator size-class rounding.
     * @param maxLineBytes positive maximum command bytes excluding LF/CRLF
     * @param itemMaxSize positive maximum complete aligned record size
     * @param budget non-null body admission owner, accounting payload plus 32
     * @throws IllegalArgumentException if either limit is nonpositive
     * @throws NullPointerException if budget is null
     * @apiNote Without a storage admission owner this is a framing-only codec;
     * cache-backed wiring must provide the owner to invalidate rejected SETs.
     */
    public TextProtocolDecoder(int maxLineBytes, int itemMaxSize, ProtocolBudget budget) {
        this(maxLineBytes,itemMaxSize,budget,null);
    }

    /** Installs optional real native admission after complete heap body admission.
     * Tokens remain decoder-owned until cancel or prepared engine transfer.
     */
    public TextProtocolDecoder(int maxLineBytes, int itemMaxSize, ProtocolBudget budget, StorageAdmission admission) {
        if (maxLineBytes <= 0 || itemMaxSize <= 0) {
            throw new IllegalArgumentException("Limits must be positive");
        }
        this.maxLineBytes = maxLineBytes;
        this.itemMaxSize = itemMaxSize;
        this.budget = Objects.requireNonNull(budget, "budget");
        this.admission=admission;
    }

    /**
     * Returns safe detail for the most recently thrown ProtocolException.
     * The handler must capture this and errorNoreply immediately in its catch.
     * @return last fixed error code, or null before the first protocol exception
     */
    public ErrorCode errorCode() { return lastError; }

    /** @return whether the last thrown error is a suppressible drained noreply error */
    public boolean errorNoreply() { return lastNoreply; }

    @Override
    public void append(ByteBuffer input) {
        Objects.requireNonNull(input, "input");
        requireOpen();
        if (returned || fatal || (!bodyMode && lineReady)) {
            return;
        }
        if (bodyMode) {
            int count = (int) Math.min(remainingBody, input.remaining());
            if (command.body != null) {
                input.get(command.body, bodyOffset, count);
                bodyOffset += count;
            } else {
                input.position(input.position() + count);
            }
            remainingBody -= count;
            while (remainingBody == 0 && terminator < 2 && input.hasRemaining()) {
                int value = input.get() & 255;
                if (value != (terminator == 0 ? '\r' : '\n')) {
                    pendingError = ErrorCode.BAD_DATA_CHUNK;
                    fatal = true;
                    clearResources();
                    return;
                }
                terminator++;
            }
            return;
        }
        while (input.hasRemaining()) {
            int value = input.get() & 255;
            if (value == '\n') {
                lineReady = true;
                return;
            }
            if (lineCr || (value != '\r' && (value < 32 || value > 126))) {
                pendingError = ErrorCode.BAD_COMMAND_LINE;
                fatal = true;
                clearResources();
                return;
            }
            if (value == '\r') {
                lineCr = true;
                continue;
            }
            if (length == maxLineBytes) {
                pendingError = ErrorCode.BAD_COMMAND_LINE;
                fatal = true;
                clearResources();
                return;
            }
            if (line == null) {
                line = new byte[Math.min(maxLineBytes, 128)];
            } else if (length == line.length) {
                int capacity = (int) Math.min(maxLineBytes, 2L * line.length);
                line = Arrays.copyOf(line, capacity);
            }
            line[length++] = (byte) value;
        }
    }

    @Override
    public Command nextCommand() throws ProtocolException {
        requireOpen();
        if (returned) {
            return null;
        }
        if (fatal) {
            throwFailure(pendingError, true, false);
        }
        if (bodyMode) {
            if (remainingBody != 0 || terminator != 2) {
                return null;
            }
            if (pendingError != null) {
                throwFailure(pendingError, false, command.noreply);
            }
        } else {
            if (!lineReady) {
                return null;
            }
            parseLine();
            if (bodyMode) {
                return null;
            }
        }
        returned = true;
        return command;
    }

    /**
     * Releases a returned command after completion or cancellation of its response.
     * Invalidates keys/body and returns body admission exactly once. The transport
     * independently releases its line slot; another slot must precede new input.
     * @param released exact outstanding command returned by this decoder
     * @throws IllegalArgumentException for null, foreign or already released commands
     */
    public void release(Command released) {
        requireOpen();
        if (!returned || released == null || released != command) {
            throw new IllegalArgumentException("Not the outstanding command");
        }
        reset();
    }

    /** Idempotently invalidates all borrowed resources and returns body admission. */
    @Override
    public void close() {
        clearResources();
        returned = false;
        closed = true;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Decoder closed");
        }
    }

    private void clearResources() {
        if (command != null) {
            command.clear();
            command = null;
        }
        line = null;
        if (reservedBody >= 0) {
            int payload = reservedBody;
            reservedBody = -1;
            budget.releaseBody(payload);
        }
    }

    private void reset() {
        clearResources();
        length = cursor = tokenStart = tokenEnd = bodyOffset = terminator = 0;
        remainingBody = 0;
        lineCr = lineReady = bodyMode = returned = false;
        pendingError = null;
    }

    private void throwFailure(ErrorCode code, boolean mustClose, boolean noreply)
            throws ProtocolException {
        throw failure(code,mustClose,noreply);
    }

    private ProtocolException failure(ErrorCode code, boolean mustClose, boolean noreply) {
        lastError = code;
        lastNoreply = noreply && !mustClose;
        reset();
        closed = mustClose;
        return new ProtocolException(code.kind(), mustClose);
    }

    private ProtocolException badLine() {
        if(admission!=null && command!=null && command.operation==CommandType.FLUSH_ALL) admission.malformedFlush();
        // Invalid storage grammar cannot establish a trustworthy body boundary.
        boolean storage = command != null && isStorage(command.operation);
        return failure(ErrorCode.BAD_COMMAND_LINE, storage, false);
    }

    private static boolean isStorage(CommandType type) {
        return switch (type) {
            case SET, ADD, REPLACE, APPEND, PREPEND, CAS -> true;
            default -> false;
        };
    }

    private void parseLine() throws ProtocolException {
        if (!token()) {
            throwFailure(ErrorCode.UNKNOWN_COMMAND, false, false);
        }
        CommandType type = operation();
        if (type == null) {
            throwFailure(ErrorCode.UNKNOWN_COMMAND, false, false);
        }
        command = new ParsedCommand(line, type);
        switch (type) {
            case GET, GETS, GAT, GATS -> {
                if (type == CommandType.GAT || type == CommandType.GATS) {
                    requiredToken();
                    command.expiry = exptime();
                }
                requiredToken();
                command.keysStart = tokenStart;
                do {
                    key();
                    command.keysEnd = tokenEnd;
                } while (token());
            }
            case SET, ADD, REPLACE, APPEND, PREPEND, CAS -> {
                singleKey();
                requiredToken();
                command.flags = (int) unsigned(0xffff_ffffL);
                requiredToken();
                // The pinned LP64 reference parses storage expiry as signed long
                // and narrows to int32 before applying TTL semantics.
                command.expiry = (int) signed();
                requiredToken();
                long bytes = unsigned(Integer.MAX_VALUE - 2L);
                if (type == CommandType.CAS) {
                    requiredToken();
                    command.cas = unsigned(-1L, ErrorCode.BAD_COMMAND_LINE, true);
                }
                suffix();
                remainingBody = bytes;
                bodyMode = true;
                // A malformed CAS token cannot change the already validated body
                // length. Drain it without allocating/publishing, honoring noreply.
                if(pendingError!=null) break;
                int keyLength = command.keysEnd - command.keysStart;
                // Compare by subtraction before rounding, including long-sized rejects.
                long available = (itemMaxSize & ~7L) - 32L - keyLength;
                if (bytes > available) {
                    if(admission!=null && type==CommandType.SET) {
                        admission.rejectOversizedSet(Arrays.copyOfRange(command.line,command.keysStart,command.keysEnd));
                    }
                    pendingError = ErrorCode.OBJECT_TOO_LARGE;
                } else if (!budget.tryReserveBody((int) bytes)) {
                    if(admission!=null) admission.capacityDenied();
                    pendingError = ErrorCode.OUT_OF_MEMORY;
                } else {
                    reservedBody = (int) bytes;
                    try {
                        if(admission!=null) {
                            byte[] key=Arrays.copyOfRange(command.line,command.keysStart,command.keysEnd);
                            StorageAdmission.Result result=admission.admit(StorageAdmission.Mode.valueOf(type.name()),
                                    key,command.flags,command.expiry,command.cas,(int)bytes);
                            if(result.token()==null) {
                                pendingError=result.status()==StoreResult.TOO_LARGE ? ErrorCode.OBJECT_TOO_LARGE : ErrorCode.OUT_OF_MEMORY;
                                reservedBody=-1; budget.releaseBody((int)bytes); break;
                            }
                            command.admission=result.token();
                        }
                        command.body = new byte[(int) bytes];
                    } catch (RuntimeException | Error failure) {
                        close();
                        throw failure;
                    }
                }
            }
            case DELETE -> {
                singleKey();
                if (token()) {
                    if (matches("noreply")) {
                        command.noreply = true;
                        end();
                    } else {
                        signed();
                        suffix();
                    }
                }
            }
            case INCR, DECR, TOUCH -> {
                singleKey();
                requiredToken();
                if (type == CommandType.TOUCH) {
                    command.expiry = exptime();
                } else {
                    command.delta = unsigned(-1L, ErrorCode.INVALID_DELTA);
                }
                suffix();
            }
            case FLUSH_ALL -> {
                if (token()) {
                    if (matches("noreply")) {
                        command.noreply = true;
                        end();
                    } else {
                        command.expiry = exptime();
                        suffix();
                    }
                }
            }
            case STATS -> {
                if (token()) {
                    if (matches("items")) {
                        command.operation = CommandType.STATS_ITEMS;
                    } else if (matches("slabs")) {
                        command.operation = CommandType.STATS_SLABS;
                    } else if (matches("reset")) {
                        command.operation = CommandType.STATS_RESET;
                    } else {
                        throwFailure(ErrorCode.UNKNOWN_COMMAND, false, false);
                    }
                    end();
                }
            }
            case VERBOSITY -> {
                requiredToken();
                command.level = (int) unsigned(Integer.MAX_VALUE);
                suffix();
            }
            case VERSION, QUIT -> end();
            default -> throw new AssertionError("Unreachable wire operation");
        }
    }

    private CommandType operation() {
        if (matches("get")) return CommandType.GET;
        if (matches("gets")) return CommandType.GETS;
        if (matches("gat")) return CommandType.GAT;
        if (matches("gats")) return CommandType.GATS;
        if (matches("set")) return CommandType.SET;
        if (matches("add")) return CommandType.ADD;
        if (matches("replace")) return CommandType.REPLACE;
        if (matches("append")) return CommandType.APPEND;
        if (matches("prepend")) return CommandType.PREPEND;
        if (matches("cas")) return CommandType.CAS;
        if (matches("delete")) return CommandType.DELETE;
        if (matches("incr")) return CommandType.INCR;
        if (matches("decr")) return CommandType.DECR;
        if (matches("touch")) return CommandType.TOUCH;
        if (matches("flush_all")) return CommandType.FLUSH_ALL;
        if (matches("stats")) return CommandType.STATS;
        if (matches("version")) return CommandType.VERSION;
        if (matches("verbosity")) return CommandType.VERBOSITY;
        if (matches("quit")) return CommandType.QUIT;
        return null;
    }

    private boolean token() {
        while (cursor < length && line[cursor] == ' ') cursor++;
        tokenStart = cursor;
        while (cursor < length && line[cursor] != ' ') cursor++;
        tokenEnd = cursor;
        return tokenEnd > tokenStart;
    }

    private void requiredToken() throws ProtocolException {
        if (!token()) throw badLine();
    }

    private void key() throws ProtocolException {
        if (tokenEnd - tokenStart > 250) throw badLine();
    }

    private void singleKey() throws ProtocolException {
        requiredToken();
        key();
        command.keysStart = tokenStart;
        command.keysEnd = tokenEnd;
    }

    private boolean matches(String expected) {
        if (tokenEnd - tokenStart != expected.length()) return false;
        for (int i = 0; i < expected.length(); i++) {
            if (line[tokenStart + i] != expected.charAt(i)) return false;
        }
        return true;
    }

    private void suffix() throws ProtocolException {
        if (token()) {
            if (!matches("noreply")) throw badLine();
            command.noreply = true;
            end();
        }
    }

    private void end() throws ProtocolException {
        if (token()) throw badLine();
    }

    private long unsigned(long maximum) throws ProtocolException {
        return unsigned(maximum, ErrorCode.BAD_COMMAND_LINE);
    }

    private long unsigned(long maximum, ErrorCode error) throws ProtocolException {
        return unsigned(maximum,error,false);
    }

    private long unsigned(long maximum, ErrorCode error, boolean deferBodyError) throws ProtocolException {
        if(maximum==-1L && tokenEnd-tokenStart>22) {
            if(deferBodyError) { pendingError=error; return 0; }
            throw numericFailure(error);
        }
        long result = 0;
        long quotient = Long.divideUnsigned(maximum, 10);
        int remainder = (int) Long.remainderUnsigned(maximum, 10);
        for (int i = tokenStart; i < tokenEnd; i++) {
            int digit = line[i] - '0';
            if (digit < 0 || digit > 9 || Long.compareUnsigned(result, quotient) > 0
                    || (result == quotient && digit > remainder)) {
                if(deferBodyError) { pendingError=error; return 0; }
                throw numericFailure(error);
            }
            result = result * 10 + digit;
        }
        return result;
    }

    private long signed() throws ProtocolException {
        return signed(ErrorCode.BAD_COMMAND_LINE);
    }

    private long signed(ErrorCode error) throws ProtocolException {
        boolean negative = line[tokenStart] == '-';
        int start = negative || line[tokenStart] == '+' ? tokenStart + 1 : tokenStart;
        if (start == tokenEnd) throw numericFailure(error);
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long result = 0;
        for (int i = start; i < tokenEnd; i++) {
            int digit = line[i] - '0';
            if (digit < 0 || digit > 9 || result < limit / 10) throw numericFailure(error);
            result *= 10;
            if (result < limit + digit) throw numericFailure(error);
            result -= digit;
        }
        return negative ? result : -result;
    }

    private long exptime() throws ProtocolException {
        ErrorCode error=ErrorCode.INVALID_EXPTIME;
        if(tokenEnd-tokenStart>11 || line[tokenStart]=='+') throw numericFailure(error);
        // Pinned 1.6.45 mcmc commands accept a lone minus as zero;
        // storage commands use a different grammar and reject it.
        if(tokenEnd-tokenStart==1 && line[tokenStart]=='-') return 0;
        long value=signed(error);
        if(value<Integer.MIN_VALUE || value>Integer.MAX_VALUE) throw numericFailure(error);
        return value;
    }

    private ProtocolException numericFailure(ErrorCode error) {
        if(error==ErrorCode.BAD_COMMAND_LINE) return badLine();
        if(command.operation==CommandType.FLUSH_ALL && admission!=null) admission.malformedFlush();
        boolean quiet=false;
        if(command.operation==CommandType.TOUCH || command.operation==CommandType.INCR
                || command.operation==CommandType.DECR || command.operation==CommandType.FLUSH_ALL) {
            // The numeric token is malformed, not the frame. Suppress only when
            // the remaining grammar is exactly the supported noreply suffix.
            quiet=token() && matches("noreply") && !token();
        }
        return failure(error,false,quiet);
    }
}

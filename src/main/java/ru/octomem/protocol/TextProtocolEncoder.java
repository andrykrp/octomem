package ru.octomem.protocol;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import ru.octomem.storage.Item;

/**
 * Streams text-protocol responses without materializing values or whole replies.
 * Writers and their borrowed response data are confined to one event loop.
 * Each writer owns 256 scratch bytes and one reusable buffer wrapper; a defensive
 * key copy temporarily adds at most 250 payload bytes. Object overhead is extra.
 * The composing caller must admit writer, wrapper and key-copy storage separately
 * before encoding; the transport's 512-byte lane is not that admission.
 * Response strings, statistics and snapshot pins retain their producer budgets.
 *
 * @since 1.0
 */
public final class TextProtocolEncoder implements ProtocolEncoder {
    private final int maxChunkBytes;

    /**
     * Creates an encoder with a finite per-call byte allowance.
     * @param maxChunkBytes maximum chunk and per-writeMore byte count, at least two
     * @throws IllegalArgumentException if the allowance is less than two
     */
    public TextProtocolEncoder(int maxChunkBytes) {
        if (maxChunkBytes < 2) {
            throw new IllegalArgumentException("maxChunkBytes must be at least two");
        }
        this.maxChunkBytes = maxChunkBytes;
    }

    /**
     * Transfers response ownership only after successful writer construction.
     * Applicable top-level payloads are checked before transfer. Version/error
     * text must be nonempty printable ASCII without framing; its producer applies
     * any configured line limit. Statistics are validated lazily as consumed.
     * @param response non-null open caller-owned response
     * @return a new writer owning response cleanup
     * @throws NullPointerException if response or a required payload is null
     * @throws IllegalArgumentException if version/error text is invalid
     */
    @Override
    public ResponseWriter encode(Response response) {
        return new Writer(Objects.requireNonNull(response, "response"), maxChunkBytes);
    }

    private enum Stage { TEXT, BUFFER, CRLF, NEXT_ITEM, KEY, HEADER, VALUE,
        VALUE_END, NEXT_STAT, END, DONE }

    private static final class Writer implements ResponseWriter {
        private final Response response;
        private final int maxChunkBytes;
        private final byte[] scratch = new byte[256];
        private final ByteBuffer buffer = ByteBuffer.wrap(scratch);
        private final Iterator<Item> items;
        private final List<String> stats;
        private final boolean includeCas;
        private Stage stage;
        private Stage following;
        private Stage afterLine;
        private String text;
        private String detail;
        private int position;
        private int length;
        private int statIndex;
        private boolean statLine;
        private boolean statValue;
        private Item item;
        private int valueLength;
        private int valueOffset;
        private boolean closed;

        private Writer(Response response, int maxChunkBytes) {
            this.response = response;
            this.maxChunkBytes = maxChunkBytes;
            ResponseKind kind = Objects.requireNonNull(response.kind(), "kind");
            stats = kind == ResponseKind.STATS
                    ? Objects.requireNonNull(response.statsLines(), "statsLines") : null;
            includeCas = kind == ResponseKind.RETRIEVAL && response.includeCas();
            afterLine = Stage.DONE;
            switch (kind) {
                case RETRIEVAL -> stage = Stage.NEXT_ITEM;
                case STATS -> stage = Stage.NEXT_STAT;
                case NUMBER -> number(response.number());
                case VERSION, CLIENT_ERROR, SERVER_ERROR -> {
                    detail = Objects.requireNonNull(response.text(), "text");
                    if (detail.isEmpty()) {
                        throw new IllegalArgumentException("empty protocol text");
                    }
                    for (int i = 0; i < detail.length(); i++) {
                        printable(detail.charAt(i));
                    }
                    setText(switch (kind) {
                        case VERSION -> "VERSION ";
                        case CLIENT_ERROR -> "CLIENT_ERROR ";
                        default -> "SERVER_ERROR ";
                    }, Stage.TEXT);
                }
                default -> setText(switch (kind) {
                    case STORED -> "STORED";
                    case NOT_STORED -> "NOT_STORED";
                    case EXISTS -> "EXISTS";
                    case NOT_FOUND -> "NOT_FOUND";
                    case DELETED -> "DELETED";
                    case TOUCHED -> "TOUCHED";
                    case OK -> "OK";
                    case RESET -> "RESET";
                    case ERROR -> "ERROR";
                    default -> throw new IllegalArgumentException("unsupported response kind");
                }, Stage.CRLF);
            }
            // Obtain the single snapshot cursor last; failed construction leaves
            // the response with the caller and never closes its resources.
            items = kind == ResponseKind.RETRIEVAL
                    ? Objects.requireNonNull(Objects.requireNonNull(response.snapshot(),
                            "snapshot").values(), "values") : null;
        }

        @Override
        public WriteStatus writeMore(ChunkSink sink) {
            if (closed) {
                throw new IllegalStateException("writer is closed");
            }
            try {
                Objects.requireNonNull(sink, "sink");
                int allowance = maxChunkBytes;
                while (true) {
                    if (stage == Stage.DONE) {
                        close();
                        return WriteStatus.COMPLETE;
                    }
                    if (allowance == 0) {
                        return WriteStatus.IN_PROGRESS;
                    }
                    int capacity = sink.remainingCapacity();
                    if (capacity < 0) {
                        throw new IllegalStateException("negative sink capacity");
                    }
                    if (capacity == 0) {
                        return WriteStatus.IN_PROGRESS;
                    }
                    int count = Math.min(Math.min(capacity, allowance), scratch.length);
                    switch (stage) {
                        case TEXT -> {
                            count = Math.min(count, text.length() - position);
                            for (int i = 0; i < count; i++) {
                                char c = text.charAt(position + i);
                                if (statLine) {
                                    printable(c);
                                    if (!statValue && position + i >= 5 && c == ' ') {
                                        if (position + i == 5 || position + i == text.length() - 1) {
                                            throw new IllegalArgumentException("invalid STAT fields");
                                        }
                                        statValue = true;
                                    }
                                }
                                scratch[i] = (byte) c;
                            }
                            if (statLine && position + count == text.length() && !statValue) {
                                throw new IllegalArgumentException("missing STAT value");
                            }
                            send(sink, 0, count);
                            position += count;
                            allowance -= count;
                            if (position == text.length()) {
                                text = null;
                                statLine = false;
                                if (following == Stage.TEXT) {
                                    setText(detail, Stage.CRLF);
                                    detail = null;
                                } else {
                                    stage = following;
                                }
                            }
                        }
                        case BUFFER -> {
                            count = Math.min(count, length - position);
                            send(sink, position, count);
                            position += count;
                            allowance -= count;
                            if (position == length) {
                                stage = following;
                            }
                        }
                        case CRLF, VALUE_END -> {
                            if (count < 2) {
                                return WriteStatus.IN_PROGRESS;
                            }
                            scratch[0] = '\r';
                            scratch[1] = '\n';
                            send(sink, 0, 2);
                            allowance -= 2;
                            stage = stage == Stage.VALUE_END ? Stage.NEXT_ITEM : afterLine;
                        }
                        case NEXT_ITEM -> {
                            if (!items.hasNext()) {
                                stage = Stage.END;
                            } else {
                                item = Objects.requireNonNull(items.next(), "item");
                                valueLength = item.valueLength();
                                if (valueLength < 0) {
                                    throw new IllegalArgumentException("negative value length");
                                }
                                valueOffset = 0;
                                setText("VALUE ", Stage.KEY);
                            }
                        }
                        case KEY -> {
                            byte[] key = Objects.requireNonNull(item.key(), "key");
                            if (key.length == 0 || key.length > 250) {
                                throw new IllegalArgumentException("invalid key length");
                            }
                            for (byte b : key) {
                                if ((b & 255) <= 32 || (b & 255) == 127) {
                                    throw new IllegalArgumentException("invalid key byte");
                                }
                            }
                            System.arraycopy(key, 0, scratch, 0, key.length);
                            setBuffer(key.length, Stage.HEADER);
                        }
                        case HEADER -> {
                            int end = 0;
                            scratch[end++] = ' ';
                            end = unsigned(Integer.toUnsignedLong(item.flags()), end);
                            scratch[end++] = ' ';
                            end = unsigned(valueLength, end);
                            if (includeCas) {
                                scratch[end++] = ' ';
                                end = unsigned(item.casId(), end);
                            }
                            afterLine = Stage.VALUE;
                            setBuffer(end, Stage.CRLF);
                        }
                        case VALUE -> {
                            if (valueOffset == valueLength) {
                                item = null;
                                stage = Stage.VALUE_END;
                            } else {
                                count = Math.min(count, valueLength - valueOffset);
                                buffer.clear().limit(count);
                                int read = item.readValue(valueOffset, buffer);
                                if (read <= 0 || read > count || buffer.position() != read
                                        || buffer.limit() != count) {
                                    throw new IllegalStateException("invalid value read progress");
                                }
                                send(sink, 0, read);
                                valueOffset += read;
                                allowance -= read;
                            }
                        }
                        case NEXT_STAT -> {
                            if (statIndex == stats.size()) {
                                stage = Stage.END;
                            } else {
                                String line = Objects.requireNonNull(stats.get(statIndex++), "STAT line");
                                if (!line.startsWith("STAT ") || line.length() < 8) {
                                    throw new IllegalArgumentException("invalid STAT prefix or fields");
                                }
                                setText(line, Stage.CRLF);
                                statLine = true;
                                statValue = false;
                                afterLine = Stage.NEXT_STAT;
                            }
                        }
                        case END -> {
                            afterLine = Stage.DONE;
                            setText("END", Stage.CRLF);
                        }
                        default -> throw new IllegalStateException("invalid writer stage");
                    }
                }
            } catch (RuntimeException | Error failure) {
                try {
                    close();
                } catch (RuntimeException | Error cleanup) {
                    if (failure != cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            }
        }

        private void send(ChunkSink sink, int start, int count) {
            buffer.clear().position(start).limit(start + count);
            sink.accept(buffer);
            if (buffer.position() != start + count || buffer.limit() != start + count) {
                throw new IllegalStateException("sink did not consume chunk");
            }
        }

        private void setText(String value, Stage next) {
            text = value;
            position = 0;
            following = next;
            stage = Stage.TEXT;
        }

        private void setBuffer(int size, Stage next) {
            position = 0;
            length = size;
            following = next;
            stage = Stage.BUFFER;
        }

        private void number(long value) {
            setBuffer(unsigned(value, 0), Stage.CRLF);
        }

        private int unsigned(long value, int start) {
            int end = start;
            do {
                scratch[end++] = (byte) ('0' + Long.remainderUnsigned(value, 10));
                value = Long.divideUnsigned(value, 10);
            } while (value != 0);
            for (int left = start, right = end - 1; left < right; left++, right--) {
                byte b = scratch[left];
                scratch[left] = scratch[right];
                scratch[right] = b;
            }
            return end;
        }

        private static void printable(char c) {
            if (c < 32 || c > 126) {
                throw new IllegalArgumentException("non-printable protocol text");
            }
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                item = null;
                text = null;
                detail = null;
                response.close();
            }
        }
    }
}

package ru.octomem.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

/** Independent byte-level test peer; no product encoder/parser is reused. */
final class ExpandedWire implements AutoCloseable {
    final Socket socket = new Socket();
    final Path transcript;

    ExpandedWire(InetSocketAddress address, Path transcript) throws IOException {
        this.transcript = transcript;
        socket.connect(address, 2000);
        socket.setSoTimeout(3000);
    }

    static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }
    static byte[] integers(int... values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.BIG_ENDIAN);
        for (int value : values) b.putInt(value);
        return b.array();
    }
    static byte[] arithmetic(long delta, long initial, int expiry) {
        return ByteBuffer.allocate(20).putLong(delta).putLong(initial).putInt(expiry).array();
    }
    static byte[] packet(int opcode, byte[] key, byte[] value, byte[] extras, long cas, int opaque) {
        return ByteBuffer.allocate(24 + extras.length + key.length + value.length).order(ByteOrder.BIG_ENDIAN)
                .put((byte) 0x80).put((byte) opcode).putShort((short) key.length).put((byte) extras.length)
                .put((byte) 0).putShort((short) 0).putInt(extras.length + key.length + value.length)
                .putInt(opaque).putLong(cas).put(extras).put(key).put(value).array();
    }
    void record(String kind, byte[] data) throws IOException {
        Files.writeString(transcript, kind + " " + HexFormat.of().formatHex(data) + "\n", StandardOpenOption.APPEND);
    }
    void send(byte[] data) throws IOException {
        record("SEND", data); socket.getOutputStream().write(data); socket.getOutputStream().flush();
    }
    void send(String text) throws IOException { send(ascii(text)); }
    void fragmented(byte[] data, int fragment) throws IOException {
        record("SEND-FRAGMENTED-" + fragment, data);
        for (int p = 0; p < data.length; p += fragment) {
            socket.getOutputStream().write(data, p, Math.min(fragment, data.length - p));
            socket.getOutputStream().flush();
        }
    }
    byte[] bytes(int count) throws IOException {
        assertTrue(count >= 0 && count <= 2_097_152, "bounded response length");
        byte[] data = socket.getInputStream().readNBytes(count); record("RECV", data);
        assertEquals(count, data.length, "truncated response"); return data;
    }
    String line() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 8192; i++) {
            int value = socket.getInputStream().read(); assertNotEquals(-1, value, "unexpected EOF"); out.write(value);
            if (value == '\n') {
                byte[] data = out.toByteArray(); record("LINE", data);
                assertTrue(data.length >= 2 && data[data.length - 2] == '\r');
                return out.toString(StandardCharsets.US_ASCII);
            }
        }
        throw new AssertionError("unbounded text line");
    }
    void expect(String request, String expected) throws IOException {
        record("EXPECTED", ascii(expected)); send(request);
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        for (int i = 0; i < expected.chars().filter(c -> c == '\n').count(); i++) actual.writeBytes(ascii(line()));
        assertArrayEquals(ascii(expected), actual.toByteArray());
    }
    Reply reply() throws IOException {
        ByteBuffer h = ByteBuffer.wrap(bytes(24));
        assertEquals(0x81, Byte.toUnsignedInt(h.get()), "binary response magic");
        int op = Byte.toUnsignedInt(h.get()); int key = Short.toUnsignedInt(h.getShort());
        int extra = Byte.toUnsignedInt(h.get()); assertEquals(0, h.get(), "raw datatype");
        int status = Short.toUnsignedInt(h.getShort()); long length = Integer.toUnsignedLong(h.getInt());
        int opaque = h.getInt(); long cas = h.getLong();
        assertTrue(length >= key + extra && length <= 2_097_152, "bounded valid response geometry");
        byte[] body = bytes((int) length);
        return new Reply(op, status, opaque, cas, Arrays.copyOfRange(body, 0, extra),
                Arrays.copyOfRange(body, extra, extra + key), Arrays.copyOfRange(body, extra + key, body.length));
    }
    Reply request(int op, String key, byte[] value, byte[] extra, long cas) throws IOException {
        send(packet(op, ascii(key), value, extra, cas, 0x89abcdef));
        Reply reply = reply(); reply.header(op, 0x89abcdef); return reply;
    }
    void barrier() throws IOException {
        send(packet(10, new byte[0], new byte[0], new byte[0], 0, 123));
        Reply response = reply(); response.header(10, 123); response.success();
        assertEquals(0, response.value.length);
    }
    @Override public void close() throws IOException { socket.close(); }

    record Reply(int opcode, int status, int opaque, long cas, byte[] extras, byte[] key, byte[] value) {
        void header(int op, int expectedOpaque) { assertEquals(op, opcode); assertEquals(expectedOpaque, opaque); }
        void success() { assertEquals(0, status, new String(value, StandardCharsets.US_ASCII)); }
        void error(int code, String text) {
            assertEquals(code, status); assertArrayEquals(ascii(text), value);
            assertEquals(0, extras.length); assertEquals(0, key.length);
        }
    }
}

package ru.octomem.protocol;

import static org.junit.jupiter.api.Assertions.*;
import static ru.octomem.protocol.TextProtocolDecoderTest.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.octomem.engine.KeySequence;

class ParsedCommandTest {
    @Test
    void borrowedKeysShareBackingPreserveOrderAndVisitorFailures() throws Exception {
        try (TextProtocolDecoder decoder = new TextProtocolDecoder(8192, 1024, new ProtocolBudgetTest.CountingBudget(true))) {
            Command command = decode(decoder, "get a  noreply a " + "z".repeat(250) + "\r\n");
            KeySequence sequence = command.keys();
            List<byte[]> backings = new ArrayList<>();
            List<Integer> offsets = new ArrayList<>();
            sequence.forEachKey((data, offset, length) -> { backings.add(data); offsets.add(offset); });
            assertEquals(4, sequence.size());
            assertTrue(offsets.get(0) < offsets.get(1));
            assertTrue(offsets.get(1) < offsets.get(2));
            assertTrue(offsets.get(2) < offsets.get(3));
            for (byte[] backing : backings) assertSame(backings.getFirst(), backing);
            sequence.forEachKey((data, offset, length) -> assertSame(backings.getFirst(), data));
            assertEquals(List.of("a", "noreply", "a", "z".repeat(250)), keys(command));
            RuntimeException sentinel = new RuntimeException("visitor sentinel");
            assertSame(sentinel, assertThrows(RuntimeException.class,
                    () -> sequence.forEachKey((data, offset, length) -> { throw sentinel; })));
            assertThrows(NullPointerException.class, () -> sequence.forEachKey(null));
            assertEquals(4, sequence.size());
            decoder.release(command);
        }
    }

    @Test
    void outstandingCommandPreventsPipelineInvalidationAndCopiesNoBody() throws Exception {
        ProtocolBudgetTest.CountingBudget budget = new ProtocolBudgetTest.CountingBudget(true);
        try (TextProtocolDecoder decoder = new TextProtocolDecoder(8192, 1024, budget)) {
            Command first = decode(decoder, "set key 42 7 3\r\nx\0y\r\n");
            byte[] body = first.value();
            ByteBuffer next = ByteBuffer.wrap(bytes("get other\r\n"));
            for (int i = 0; i < 10; i++) {
                decoder.append(next);
                assertEquals(0, next.position());
                assertNull(decoder.nextCommand());
                assertSame(body, first.value());
                assertArrayEquals(bytes("x\0y"), body);
                assertEquals(List.of("key"), keys(first));
                assertEquals(42, first.flags());
                assertEquals(7, first.exptime());
                assertEquals(35, budget.used);
            }
            decoder.release(first);
            List<Command> found = new ArrayList<>();
            feed(decoder, next, found);
            assertEquals(List.of("other"), keys(found.getFirst()));
            decoder.release(found.getFirst());
            budget.assertBalanced();
        }
    }

    @Test
    void releaseValidationAndClosedLifecycle() throws Exception {
        ProtocolBudgetTest.CountingBudget budget = new ProtocolBudgetTest.CountingBudget(true);
        TextProtocolDecoder decoder = new TextProtocolDecoder(8192, 1024, budget);
        try (TextProtocolDecoder other = new TextProtocolDecoder(8192, 1024, new ProtocolBudgetTest.CountingBudget(true))) {
            assertThrows(NullPointerException.class, () -> decoder.append(null));
            assertThrows(IllegalArgumentException.class, () -> decoder.release(null));
            Command foreign = decode(other, "version\r\n");
            Command local = decode(decoder, "set k 0 0 0\r\n\r\n");
            KeySequence borrowed = local.keys();
            assertThrows(IllegalArgumentException.class, () -> decoder.release(null));
            assertThrows(IllegalArgumentException.class, () -> decoder.release(foreign));
            assertEquals(32, budget.used);
            decoder.release(local);
            assertThrows(IllegalStateException.class, local::type);
            assertThrows(IllegalStateException.class, borrowed::size);
            assertThrows(IllegalStateException.class, () -> borrowed.forEachKey((data, offset, length) -> fail("released key")));
            assertThrows(IllegalArgumentException.class, () -> decoder.release(local));
            budget.assertBalanced();
            other.release(foreign);
        }
        decoder.close();
        decoder.close();
        assertThrows(IllegalStateException.class, () -> decoder.append(ByteBuffer.allocate(0)));
        assertThrows(IllegalStateException.class, decoder::nextCommand);
        assertThrows(IllegalStateException.class, () -> decoder.release(null));
    }

    @Test
    void constructorValidationAndEmptyClose() {
        ProtocolBudgetTest.CountingBudget budget = new ProtocolBudgetTest.CountingBudget(true);
        assertThrows(IllegalArgumentException.class, () -> new TextProtocolDecoder(0, 1, budget));
        assertThrows(IllegalArgumentException.class, () -> new TextProtocolDecoder(1, 0, budget));
        assertThrows(NullPointerException.class, () -> new TextProtocolDecoder(1, 1, null));
        TextProtocolDecoder decoder = new TextProtocolDecoder(1, 1, budget);
        decoder.close();
        decoder.close();
        budget.assertBalanced();
    }

    @Test
    void appendCannotAdvanceUnparsedLineOrPendingFatalFrame() throws Exception {
        try (TextProtocolDecoder decoder = new TextProtocolDecoder(8192, 1024, new ProtocolBudgetTest.CountingBudget(true))) {
            ByteBuffer input = ByteBuffer.wrap(bytes("version\r\nquit\r\n"));
            decoder.append(input);
            int position = input.position();
            decoder.append(input);
            assertEquals(position, input.position());
            Command command = decoder.nextCommand();
            assertEquals(CommandType.VERSION, command.type());
            decoder.release(command);
        }
        try (TextProtocolDecoder decoder = new TextProtocolDecoder(8192, 1024, new ProtocolBudgetTest.CountingBudget(true))) {
            ByteBuffer input = ByteBuffer.wrap(bytes("get\0bad\r\nversion\r\n"));
            decoder.append(input);
            int position = input.position();
            decoder.append(input);
            assertEquals(position, input.position());
            assertThrows(ProtocolException.class, decoder::nextCommand);
            assertEquals(TextProtocolDecoder.ErrorCode.BAD_COMMAND_LINE, decoder.errorCode());
        }
    }
}

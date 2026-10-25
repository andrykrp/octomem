package ru.octomem.server;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import ru.octomem.engine.DefaultCacheEngine;
import ru.octomem.engine.ExtendedRequest;
import ru.octomem.engine.ExtendedResult;
import ru.octomem.protocol.ChunkSink;
import ru.octomem.protocol.ExtendedResponse;
import ru.octomem.protocol.WriteStatus;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.storage.FfmSlabAllocator;
import ru.octomem.storage.SegmentedItemTable;
import ru.octomem.util.Clock;
import static org.junit.jupiter.api.Assertions.*;

/** Real leased values streamed through a controlled backpressure sink, not a mock engine. */
class ExpandedStreamingTest {
    @Test void zeroOneTwoByteSinkDoesNotSplitCrLfOrLosePinnedValue() {
        try(var allocator=new FfmSlabAllocator(65536,4096,4096);var table=new SegmentedItemTable(1,2,65536)) {
            Clock clock=new Clock(){public long currentTimeSeconds(){return 1700000000;}public long nanoTime(){return 0;}};
            var stats=new AtomicStatsRegistry(Map.of("pid","1","version","expanded","pointer_size","64","threads","1","limit_maxbytes","65536"));
            var engine=new DefaultCacheEngine(allocator,table,clock,stats,new DefaultCacheEngine.Settings(65536,4096,"noeviction",86400));
            engine.start();
            try {
                ExtendedRequest set=new ExtendedRequest();set.operation=ExtendedRequest.Operation.SET;set.key=ExpandedWire.ascii("k");set.value=ExpandedWire.ascii("old");
                try(var result=engine.execute(set)){assertEquals(ExtendedResult.Status.OK,result.status);}
                ExtendedRequest get=new ExtendedRequest();get.operation=ExtendedRequest.Operation.GET;get.key=set.key;
                ExtendedResult held=engine.execute(get);assertEquals(ExtendedResult.Status.OK,held.status);
                AtomicInteger releases=new AtomicInteger();Sink sink=new Sink();
                var response=new ExtendedResponse(ExpandedWire.ascii("VA 3\r\n"),held,true,true,8,releases::incrementAndGet);
                try {
                    sink.capacity=0;assertEquals(WriteStatus.IN_PROGRESS,response.writeMore(sink));assertEquals(0,sink.data.size());
                    set.value=ExpandedWire.ascii("new");try(var result=engine.execute(set)){assertEquals(ExtendedResult.Status.OK,result.status);}
                    boolean completed=false;
                    for(int i=0;i<50;i++){sink.capacity=i%3;WriteStatus status=response.writeMore(sink);if(status==WriteStatus.COMPLETE){completed=true;break;}}
                    assertTrue(completed);assertArrayEquals(ExpandedWire.ascii("VA 3\r\nold\r\n"),sink.data.toByteArray());
                    assertEquals(1,releases.get());response.close();assertEquals(1,releases.get());
                    assertThrows(IllegalStateException.class,()->response.writeMore(sink));
                } finally {response.close();}
                try(var result=engine.execute(get)){ByteBuffer b=ByteBuffer.allocate(3);result.item().readValue(0,b);assertArrayEquals(ExpandedWire.ascii("new"),b.array());}
            } finally {engine.stop();}
            assertEquals(0,allocator.usedMemory());
        }
    }
    private static final class Sink implements ChunkSink {
        final ByteArrayOutputStream data=new ByteArrayOutputStream();int capacity;
        public int remainingCapacity(){return capacity;}
        public void accept(ByteBuffer chunk){assertTrue(chunk.remaining()<=capacity);byte[] bytes=new byte[chunk.remaining()];chunk.get(bytes);
            assertFalse(bytes.length==1&&(bytes[0]=='\r'||bytes[0]=='\n'),"CRLF must be atomic");capacity-=bytes.length;data.writeBytes(bytes);}
    }
}

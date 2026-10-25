package ru.octomem.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.octomem.engine.RetrievalSnapshot;
import static org.junit.jupiter.api.Assertions.*;

/** Public codec lifecycle and malformed-frame behavior with explicit admission faults. */
class ExpandedCodecTest {
    static byte[] b(String s){return s.getBytes(StandardCharsets.US_ASCII);}
    static byte[] frame(int op,int extras,byte[] key,byte[] value){return ByteBuffer.allocate(24+extras+key.length+value.length)
            .put((byte)128).put((byte)op).putShort((short)key.length).put((byte)extras).put((byte)0).putShort((short)0)
            .putInt(extras+key.length+value.length).putInt(123).putLong(456).put(new byte[extras]).put(key).put(value).array();}
    static final class Budget implements ProtocolBudget{
        int held=-1,calls;boolean deny;
        public boolean tryReserveBody(int bytes){calls++;if(deny)return false;assertEquals(-1,held);held=bytes;return true;}
        public void releaseBody(int bytes){assertEquals(held,bytes);held=-1;}
    }
    @Test void commandInterfaceExposesBorrowedMetadataAndEmptyKeys(){
        ExtendedCommand c=new ExtendedCommand();assertEquals(0,c.keys().size());c.keys().forEachKey((k,o,n)->fail("empty key callback"));
        c.request.key=b("raw");c.request.flags=-1;c.request.ttl=9;c.request.expectedCas=-1;c.request.delta=5;c.payload=b("value");
        List<String> keys=new ArrayList<>();c.keys().forEachKey((k,o,n)->keys.add(new String(k,o,n,StandardCharsets.US_ASCII)));
        assertEquals(List.of("raw"),keys);assertEquals(1,c.keys().size());assertEquals(-1,c.flags());assertEquals(9,c.exptime());
        assertEquals(-1,c.casId());assertEquals(5,c.delta());assertSame(c.payload,c.value());assertEquals(0,c.level());assertFalse(c.noreply());assertEquals(CommandType.VERSION,c.type());
    }
    @Test void oneByteFragmentedStorageOwnsAndReleasesExactlyOneReservation(){
        Budget budget=new Budget();AtomicInteger admissions=new AtomicInteger();
        try(var d=new ExtendedDecoder(true,8192,4096,budget,k->fail("unexpected reject"),(r,n)->{admissions.incrementAndGet();return 0;})){
            byte[] bytes=frame(1,8,b("k"),b("data"));assertNull(d.nextCommand());
            for(int i=0;i<bytes.length;i++){d.append(ByteBuffer.wrap(bytes,i,1));if(i<bytes.length-1)assertNull(d.nextCommand());}
            ExtendedCommand command=(ExtendedCommand)d.nextCommand();assertNotNull(command);assertNull(d.nextCommand());assertArrayEquals(b("data"),command.value());
            ByteBuffer next=ByteBuffer.wrap(frame(10,0,new byte[0],new byte[0]));d.append(next);assertEquals(0,next.position());
            assertThrows(IllegalArgumentException.class,()->d.release(new ExtendedCommand()));assertEquals(4,budget.held);assertEquals(1,admissions.get());
            d.release(command);assertEquals(-1,budget.held);d.append(next);assertNotNull(d.nextCommand());
        }assertEquals(-1,budget.held);
    }
    @Test void cancellationAndHeapDenialNeverInvokeNativeAdmission(){
        Budget budget=new Budget();AtomicInteger admitted=new AtomicInteger();
        var d=new ExtendedDecoder(true,8192,4096,budget,k->{},(r,n)->{admitted.incrementAndGet();return 0;});byte[] request=frame(1,8,b("k"),new byte[10]);
        d.append(ByteBuffer.wrap(request,0,35));assertEquals(10,budget.held);d.close();d.close();assertEquals(-1,budget.held);
        ByteBuffer ignored=ByteBuffer.wrap(request);d.append(ignored);assertEquals(0,ignored.position());
        budget.deny=true;admitted.set(0);try(var denied=new ExtendedDecoder(true,8192,4096,budget,k->fail("heap denial cannot invalidate"),(r,n)->{admitted.incrementAndGet();return 0;})){
            denied.append(ByteBuffer.wrap(request));ExtendedCommand c=(ExtendedCommand)denied.nextCommand();assertEquals(130,c.error);assertEquals(0,admitted.get());assertNull(c.payload);
        }
    }
    @ParameterizedTest @ValueSource(ints={3,130})
    void nativeAdmissionRefusalReleasesHeapBeforeRejectedBody(int status){
        Budget budget=new Budget();try(var d=new ExtendedDecoder(true,8192,4096,budget,k->{},(r,n)->status)){
            byte[] request=frame(1,8,b("k"),new byte[10]);d.append(ByteBuffer.wrap(request,0,33));assertEquals(-1,budget.held);assertNull(d.nextCommand());
            d.append(ByteBuffer.wrap(request,33,10));ExtendedCommand c=(ExtendedCommand)d.nextCommand();assertEquals(status,c.error);assertNull(c.payload);
        }
    }
    @Test void getWithExhaustedBodyBudgetDoesNotReservePayload(){
        Budget budget=new Budget();budget.deny=true;try(var d=new ExtendedDecoder(true,8192,4096,budget,k->{},(r,n)->{throw new AssertionError();})){
            d.append(ByteBuffer.wrap(frame(0,0,b("k"),new byte[0])));ExtendedCommand c=(ExtendedCommand)d.nextCommand();assertEquals(0,c.error);assertEquals(0,budget.calls);
        }
    }
    @ParameterizedTest @ValueSource(ints={0,5,6,7})
    void malformedBinaryHeaderFailsClosed(int variant){
        Budget budget=new Budget();byte[] bytes=frame(0,0,b("k"),new byte[0]);switch(variant){case 0->bytes[0]=1;case 5->bytes[5]=1;case 6->bytes[4]=21;case 7->ByteBuffer.wrap(bytes).putInt(8,-1);default->throw new AssertionError();}
        try(var d=new ExtendedDecoder(true,8192,4096,budget,k->{},(r,n)->0)){d.append(ByteBuffer.wrap(bytes));ExtendedCommand c=(ExtendedCommand)d.nextCommand();assertNotNull(c);assertTrue(c.close);assertNotEquals(0,c.error);assertEquals(0,budget.calls);}
    }
    @Test void unauthorizedStorageRejectedFromHeaderWithoutBodyReservation(){
        Budget budget=new Budget();try(var d=new ExtendedDecoder(true,8192,4096,budget,k->fail(),(r,n)->{throw new AssertionError();},()->false)){
            byte[] frame=frame(1,8,b("k"),new byte[100]);d.append(ByteBuffer.wrap(frame,0,24));ExtendedCommand c=(ExtendedCommand)d.nextCommand();assertEquals(32,c.error);assertTrue(c.close);assertEquals("Auth failure.",c.errorText);assertEquals(0,budget.calls);
        }
    }
    @ParameterizedTest @ValueSource(strings={"", "mg", "ms k", "mx k", "mn extra", "mg k\tT0", "mg k MZ", "ma k MZ", "ms k 0 MZ\r\n\r\n", "mg k O", "mg k C", "mg k F4294967296", "mg k E18446744073709551616", "mg k F-1", "mg k T2147483648", "mg k T000000000000"})
    void invalidMetaFramesDoNotReachAdmission(String request){
        if(request.equals("mg k O"))request="mg k O"+"x".repeat(33);
        Budget budget=new Budget();try(var d=new ExtendedDecoder(false,8192,4096,budget,k->fail(),(r,n)->{throw new AssertionError();})){
            d.append(ByteBuffer.wrap(b(request.endsWith("\r\n")?request:request+"\r\n")));ExtendedCommand c=(ExtendedCommand)d.nextCommand();assertNotNull(c);assertEquals(4,c.error);assertEquals(0,budget.calls);
        }
    }
    @Test void metaLimitsWhitespaceAndBadBodyFraming(){
        Budget budget=new Budget();try(var d=new ExtendedDecoder(false,64,4096,budget,k->{},(r,n)->0)){
            d.append(ByteBuffer.wrap(b(" ".repeat(70))));ExtendedCommand c=(ExtendedCommand)d.nextCommand();assertTrue(c.close);assertEquals(4,c.error);
        }
        for(String malformed:List.of("ms k -1\r\n","ms k 1\r\nxXX"))try(var d=new ExtendedDecoder(false,8192,4096,budget,k->{},(r,n)->0)){
            d.append(ByteBuffer.wrap(b(malformed)));ExtendedCommand c=(ExtendedCommand)d.nextCommand();assertNotNull(c);assertEquals(4,c.error);assertTrue(c.close);
        }assertEquals(-1,budget.held);
    }
    @Test void validMetaRoutingAndDebugKeepMetadataNormalized(){
        Budget budget=new Budget();try(var d=new ExtendedDecoder(false,8192,4096,budget,k->{},(r,n)->0)){
            d.append(ByteBuffer.wrap(b("  me AAEC b T1 N2 R3 I x C4  \r\n")));ExtendedCommand c=(ExtendedCommand)d.nextCommand();assertEquals(0,c.error);assertArrayEquals(new byte[]{0,1,2},c.request.key);
            assertFalse(c.request.hasTtl);assertFalse(c.request.create);assertFalse(c.request.recache);assertFalse(c.request.compareCas);assertFalse(c.request.invalidate);assertFalse(c.request.removeValue);
        }
    }
    @Test void responseShortReadFailsAndOwnerCleanupIsExactlyOnce(){
        AtomicInteger leaseClosed=new AtomicInteger(),released=new AtomicInteger();
        ru.octomem.storage.Item faulty=new ru.octomem.storage.Item(){
            public byte[] key(){return b("k");}public int flags(){return 0;}public long expiryTime(){return 0;}public long casId(){return 1;}public int valueLength(){return 4;}
            public int readValue(long offset,ByteBuffer dst){return 0;}public boolean isExpired(long now){return false;}public boolean isActive(){return false;}public void markActive(){}
        };
        var result=new ru.octomem.engine.ExtendedResult(ru.octomem.engine.ExtendedResult.Status.OK);result.size=4;
        var lease=new ru.octomem.storage.ItemLease(){public ru.octomem.storage.Item item(){return faulty;}public void close(){leaseClosed.incrementAndGet();}};
        result.attach(lease);assertThrows(IllegalStateException.class,()->result.attach(lease));
        var response=new ExtendedResponse(new byte[0],result,true,true,8,released::incrementAndGet);
        ChunkSink sink=new ChunkSink(){public int remainingCapacity(){return 8;}public void accept(ByteBuffer b){b.position(b.limit());}};
        try{assertThrows(IllegalStateException.class,()->response.writeMore(sink));}finally{response.close();response.close();}
        assertEquals(1,leaseClosed.get());assertEquals(1,released.get());assertNull(result.item());
    }
    @Test void statsPacketsStreamEmptyTerminatorAndCloseSourceOnCancellation(){
        AtomicInteger sourceClosed=new AtomicInteger(),released=new AtomicInteger();
        Response source=new Response(){public ResponseKind kind(){return ResponseKind.STATS;}public RetrievalSnapshot snapshot(){return null;}public boolean includeCas(){return false;}public long number(){return 0;}public String text(){return null;}public List<String> statsLines(){return List.of("STAT name value");}public void close(){sourceClosed.incrementAndGet();}};
        var response=ExtendedResponse.statistics(source,16,123,2,released::incrementAndGet);java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        ChunkSink sink=new ChunkSink(){public int remainingCapacity(){return 2;}public void accept(ByteBuffer b){while(b.hasRemaining())bytes.write(b.get());}};
        boolean done=false;try{for(int i=0;i<100;i++)if(response.writeMore(sink)==WriteStatus.COMPLETE){done=true;break;}assertTrue(done);}finally{response.close();}
        ByteBuffer wire=ByteBuffer.wrap(bytes.toByteArray());assertEquals((byte)129,wire.get());wire.position(6);assertEquals(0,wire.getShort());int size=wire.getInt();assertEquals(9,size);wire.position(24+size);assertEquals((byte)129,wire.get());assertEquals(16,wire.get());wire.position(24+size+8);assertEquals(0,wire.getInt());assertEquals(1,sourceClosed.get());assertEquals(1,released.get());
    }
    @ParameterizedTest @ValueSource(ints={32,33,34})
    void authPayloadLimitRejectsWithoutAllocatingAnnouncedBody(int op){
        Budget budget=new Budget();try(var d=new ExtendedDecoder(true,8192,4096,budget,k->{},(r,n)->0)){
            byte[] packet=frame(op,0,op==32?new byte[0]:b("PLAIN"),new byte[1281]);d.append(ByteBuffer.wrap(packet,0,24+(op==32?0:5)));
            ExtendedCommand c=(ExtendedCommand)d.nextCommand();assertNotNull(c);assertNotEquals(0,c.error);assertTrue(c.close);assertEquals(0,budget.calls);
        }
    }
    @Test void unknownFrameDrainsPayloadThenNextFrameAndRejectsMalformedMetaLimits(){
        Budget budget=new Budget();try(var d=new ExtendedDecoder(true,8192,4096,budget,k->{},(r,n)->0)){
            byte[] packet=frame(127,0,new byte[0],new byte[10]);d.append(ByteBuffer.wrap(packet,0,25));assertNull(d.nextCommand());d.append(ByteBuffer.wrap(packet,25,9));var c=(ExtendedCommand)d.nextCommand();assertEquals(129,c.error);d.release(c);
            d.append(ByteBuffer.wrap(frame(10,0,new byte[0],new byte[0])));assertEquals(0,((ExtendedCommand)d.nextCommand()).error);
        }
        for(String line:List.of("mg "+"a".repeat(341)+"\r\n","mg k "+"v ".repeat(32)+"\r\n","mg "+"a".repeat(251)+"\r\n","mg AA== b\r\n"))try(var d=new ExtendedDecoder(false,8192,4096,budget,k->{},(r,n)->0)){
            d.append(ByteBuffer.wrap(b(line)));var c=(ExtendedCommand)d.nextCommand();assertNotNull(c);if(line.equals("mg AA== b\r\n"))assertArrayEquals(new byte[]{0},c.request.key);else assertEquals(4,c.error);
        }
    }
    @Test void unknownHugeFrameNeverUsesStorageBodyBudget(){
        Budget budget=new Budget();try(var d=new ExtendedDecoder(true,8192,64,budget,k->fail(),(r,n)->{throw new AssertionError();})){
            byte[] request=frame(127,0,new byte[0],new byte[1000]);d.append(ByteBuffer.wrap(request,0,24));assertNull(d.nextCommand());assertEquals(0,budget.calls);
            d.append(ByteBuffer.wrap(request,24,1000));ExtendedCommand c=(ExtendedCommand)d.nextCommand();assertEquals(129,c.error);assertNull(c.payload);assertFalse(c.close);
        }
    }
    @Test void growingMetaHeaderAndNonAsciiAreBounded(){
        Budget budget=new Budget();try(var d=new ExtendedDecoder(false,512,4096,budget,k->{},(r,n)->0)){
            byte[] input=b("mg k P"+"p".repeat(200)+" O"+"o".repeat(32)+"\r\n");for(byte x:input)d.append(ByteBuffer.wrap(new byte[]{x}));assertEquals(0,((ExtendedCommand)d.nextCommand()).error);
        }
        try(var d=new ExtendedDecoder(false,512,4096,budget,k->{},(r,n)->0)){d.append(ByteBuffer.wrap(new byte[]{'m','g',' ','k',' ',(byte)0xff,'\r','\n'}));var c=(ExtendedCommand)d.nextCommand();assertEquals(4,c.error);assertTrue(c.close);}
    }
    @Test void splitMetaTerminatorAndLineFeedOnlyFramesRemainAligned(){
        Budget budget=new Budget();try(var d=new ExtendedDecoder(false,8192,4096,budget,k->{},(r,n)->0)){
            d.append(ByteBuffer.wrap(b("ms k 1\r\nx\r")));assertNull(d.nextCommand());assertEquals(1,budget.held);d.append(ByteBuffer.wrap(b("\n")));ExtendedCommand c=(ExtendedCommand)d.nextCommand();assertEquals(0,c.error);d.release(c);
            d.append(ByteBuffer.wrap(b("mg k\n")));c=(ExtendedCommand)d.nextCommand();assertEquals(0,c.error);d.release(c);d.append(ByteBuffer.wrap(b("\n")));assertEquals(4,((ExtendedCommand)d.nextCommand()).error);
        }
    }
    @Test void metaAdmissionErrorsAreNotSuccessfulFrames(){
        for(int status:new int[]{3,130}){Budget budget=new Budget();try(var d=new ExtendedDecoder(false,8192,4096,budget,k->{},(r,n)->status)){
            d.append(ByteBuffer.wrap(b("ms k 1\r\nx\r\n")));ExtendedCommand c=(ExtendedCommand)d.nextCommand();assertEquals(status,c.error);assertNull(c.payload);assertEquals(-1,budget.held);
        }}
    }
    @ParameterizedTest @ValueSource(strings={"x\r","x\ry","x\n","\n"})
    void responseRejectsInvalidTextPrefixesWithoutTakingCleanupOwnership(String prefix){
        AtomicInteger release=new AtomicInteger();assertThrows(IllegalArgumentException.class,()->new ExtendedResponse(b(prefix),null,false,true,2,release::incrementAndGet));assertEquals(0,release.get());
    }
    @Test void responseConstructorRejectsInvalidOwnershipOrChunkContract(){
        AtomicInteger release=new AtomicInteger();assertThrows(NullPointerException.class,()->new ExtendedResponse(b("VA 0\r\n"),null,true,true,2,release::incrementAndGet));
        assertThrows(IllegalArgumentException.class,()->new ExtendedResponse(b("HD\r\n"),null,false,true,1,release::incrementAndGet));
        assertThrows(NullPointerException.class,()->new ExtendedResponse(b("HD\r\n"),null,false,true,2,null));assertEquals(0,release.get());
    }
}

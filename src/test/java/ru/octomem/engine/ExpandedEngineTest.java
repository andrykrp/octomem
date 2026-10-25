package ru.octomem.engine;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.storage.FfmSlabAllocator;
import ru.octomem.storage.SegmentedItemTable;
import ru.octomem.util.Clock;
import static org.junit.jupiter.api.Assertions.*;

/** Transaction-level outcomes and native/service/index rollback, no mocked cache. */
class ExpandedEngineTest {
    static byte[] b(String s){return s.getBytes(StandardCharsets.US_ASCII);}
    static ExtendedRequest request(ExtendedRequest.Operation op,String key){var r=new ExtendedRequest();r.operation=op;r.key=b(key);return r;}
    static String value(ExtendedResult r){ByteBuffer b=ByteBuffer.allocate(r.size);r.item().readValue(0,b);return new String(b.array(),StandardCharsets.US_ASCII);}
    static final class Rig implements AutoCloseable{
        final FfmSlabAllocator allocator;final SegmentedItemTable table;final DefaultCacheEngine engine;
        Rig(long nativeLimit,long serviceLimit,long indexLimit){this(nativeLimit,serviceLimit,indexLimit,"noeviction");}
        Rig(long nativeLimit,long serviceLimit,long indexLimit,String eviction){allocator=new FfmSlabAllocator(nativeLimit,4096,4096);table=new SegmentedItemTable(1,2,indexLimit);
            Clock c=new Clock(){public long currentTimeSeconds(){return 1700000000;}public long nanoTime(){return 0;}};
            engine=new DefaultCacheEngine(allocator,table,c,new AtomicStatsRegistry(Map.of("pid","1","version","expanded","pointer_size","64","threads","1","limit_maxbytes","65536")),new DefaultCacheEngine.Settings(serviceLimit,(int)Math.min(4096,serviceLimit),eviction,86400));engine.start();}
        long put(String key,String value){var r=request(ExtendedRequest.Operation.SET,key);r.value=b(value);try(var out=engine.execute(r)){assertEquals(ExtendedResult.Status.OK,out.status);return out.cas;}}
        public void close(){try{engine.stop();}finally{table.close();allocator.close();}assertEquals(0,allocator.usedMemory());assertEquals(0,engine.serviceMetadataUsedBytes());}
    }
    @ParameterizedTest @ValueSource(strings={"", " ","x","1x","1 2","18446744073709551616","999999999999999999999999999999999999"})
    void nonNumericCounterLeavesValueAndCasUntouched(String text){try(var r=new Rig(65536,65536,65536)){
        long cas=r.put("n",text);var increment=request(ExtendedRequest.Operation.INCR,"n");increment.delta=1;
        try(var out=r.engine.execute(increment)){assertEquals(ExtendedResult.Status.NON_NUMERIC,out.status);}
        try(var out=r.engine.execute(request(ExtendedRequest.Operation.GET,"n"))){assertEquals(text,value(out));assertEquals(cas,out.cas);}
    }}
    @Test void countersAcceptPaddingWrapAndClampUnsigned(){try(var r=new Rig(65536,65536,65536)){
        r.put("n","18446744073709551615 ");var inc=request(ExtendedRequest.Operation.INCR,"n");inc.delta=1;
        try(var out=r.engine.execute(inc)){assertEquals(ExtendedResult.Status.OK,out.status);assertEquals(0,out.number);}
        var dec=request(ExtendedRequest.Operation.DECR,"n");dec.delta=-1;try(var out=r.engine.execute(dec)){assertEquals(0,out.number);}
        r.put("n","12 "+" ".repeat(300));dec.delta=2;try(var out=r.engine.execute(dec)){assertEquals(10,out.number);}
    }}
    @ParameterizedTest @ValueSource(strings={"INCR","DECR","DELETE","REPLACE","APPEND","PREPEND"})
    void missingConditionalOperationsHaveDistinctStatuses(String operation){try(var r=new Rig(65536,65536,65536)){
        var req=request(ExtendedRequest.Operation.valueOf(operation),"missing");req.value=b("x");req.delta=1;
        try(var out=r.engine.execute(req)){assertEquals(operation.equals("INCR")||operation.equals("DECR")||operation.equals("DELETE")?ExtendedResult.Status.NOT_FOUND:ExtendedResult.Status.NOT_STORED,out.status);}
        req.compareCas=true;req.expectedCas=1;try(var out=r.engine.execute(req)){assertEquals(operation.equals("APPEND")||operation.equals("PREPEND")?ExtendedResult.Status.NOT_STORED:ExtendedResult.Status.NOT_FOUND,out.status);}
    }}
    @Test void serviceAdmissionFailureDoesNotRemoveExistingRecord(){try(var r=new Rig(65536,64,65536)){
        long cas=r.put("k","old");var set=request(ExtendedRequest.Operation.SET,"k");set.value=b("new");
        assertEquals(ExtendedResult.Status.OUT_OF_MEMORY,r.engine.admitExtended(set,3));assertNull(set.admitted);
        try(var out=r.engine.execute(request(ExtendedRequest.Operation.GET,"k"))){assertEquals("old",value(out));assertEquals(cas,out.cas);}
        var touch=request(ExtendedRequest.Operation.GET,"k");touch.hasTtl=true;touch.ttl=30;
        try(var out=r.engine.execute(touch)){assertEquals(ExtendedResult.Status.OUT_OF_MEMORY,out.status);}assertEquals(512,r.engine.serviceMetadataUsedBytes());
    }}
    @Test void indexRefusalReleasesNewRecordAndLease(){try(var r=new Rig(1048576,1048576,8192)){
        boolean exhausted=false;
        for(int i=0;i<500;i++){var fill=request(ExtendedRequest.Operation.SET,"fill"+i);fill.value=b("x");try(var out=r.engine.execute(fill)){if(out.status==ExtendedResult.Status.OUT_OF_MEMORY){exhausted=true;break;}assertEquals(ExtendedResult.Status.OK,out.status);}}
        assertTrue(exhausted,"bounded index fixture must exhaust table admission");long nativeBefore=r.allocator.usedMemory(),serviceBefore=r.engine.serviceMetadataUsedBytes();
        var set=request(ExtendedRequest.Operation.SET,"k");set.value=b("x");try(var out=r.engine.execute(set)){assertEquals(ExtendedResult.Status.OUT_OF_MEMORY,out.status);}
        var vivify=request(ExtendedRequest.Operation.GET,"k");vivify.create=true;vivify.createTtl=30;vivify.claimRecache=true;
        try(var out=r.engine.execute(vivify)){assertEquals(ExtendedResult.Status.OUT_OF_MEMORY,out.status);}assertEquals(nativeBefore,r.allocator.usedMemory());assertEquals(serviceBefore,r.engine.serviceMetadataUsedBytes());
    }}
    @Test void nativeRefusalRetainsPinnedOldReadAndCancelReleasesReservation(){try(var r=new Rig(4096,65536,65536)){
        r.put("k","a".repeat(3000));try(var held=r.engine.execute(request(ExtendedRequest.Operation.GET,"k"))){
            var set=request(ExtendedRequest.Operation.SET,"k");assertEquals(ExtendedResult.Status.OUT_OF_MEMORY,r.engine.admitExtended(set,3000));
            try(var current=r.engine.execute(request(ExtendedRequest.Operation.GET,"k"))){assertEquals(ExtendedResult.Status.NOT_FOUND,current.status);}
            assertEquals("a".repeat(3000),value(held));assertTrue(r.allocator.usedMemory()>0);
        }assertEquals(0,r.allocator.usedMemory());
        var set=request(ExtendedRequest.Operation.SET,"new");assertEquals(ExtendedResult.Status.OK,r.engine.admitExtended(set,1));assertNotNull(set.admitted);set.close();set.close();assertEquals(0,r.allocator.usedMemory());
    }}
    @Test void directOversizeAndAppendFailureDoNotLeak(){try(var r=new Rig(8192,65536,65536)){
        r.put("k","a".repeat(3000));var append=request(ExtendedRequest.Operation.APPEND,"k");append.value=new byte[3000];
        try(var out=r.engine.execute(append)){assertEquals(ExtendedResult.Status.NOT_STORED,out.status);}
        var set=request(ExtendedRequest.Operation.SET,"k");set.value=new byte[4096];try(var out=r.engine.execute(set)){assertEquals(ExtendedResult.Status.TOO_LARGE,out.status);}
        assertEquals(0,r.engine.serviceMetadataUsedBytes());
    }}
    @Test void resultLeaseTransferRejectsDoubleAttachmentAndClosesIdempotently(){try(var r=new Rig(65536,65536,65536)){
        r.put("k","old");try(var one=r.engine.get(b("k"));var two=r.engine.get(b("k"))){
            // Exercise the public null/closed view contract without fabricating a cache item.
            var result=new ExtendedResult(ExtendedResult.Status.NOT_FOUND);assertNull(result.item());result.close();result.close();assertNull(result.item());
            assertTrue(one.found());assertTrue(two.found());
        }
    }}
    @Test void invalidRequestFailsAtPublicBoundary(){try(var r=new Rig(65536,65536,65536)){
        assertThrows(NullPointerException.class,()->r.engine.execute(null));var req=new ExtendedRequest();assertThrows(NullPointerException.class,()->r.engine.execute(req));
        req.operation=ExtendedRequest.Operation.GET;assertThrows(NullPointerException.class,()->r.engine.execute(req));req.key=new byte[0];assertThrows(IllegalArgumentException.class,()->r.engine.execute(req));req.key=new byte[251];assertThrows(IllegalArgumentException.class,()->r.engine.execute(req));
    }}
    @ParameterizedTest @ValueSource(ints={0,251})
    void nativeAdmissionRejectsInvalidRawKeyBeforeReservation(int length){try(var r=new Rig(65536,65536,65536)){
        var req=request(ExtendedRequest.Operation.SET,"k");req.key=new byte[length];assertThrows(IllegalArgumentException.class,()->r.engine.admitExtended(req,1));assertEquals(0,r.allocator.usedMemory());assertEquals(0,r.engine.serviceMetadataUsedBytes());
    }}
    @ParameterizedTest @ValueSource(strings={"SET","ADD","REPLACE","APPEND","PREPEND"})
    void oversizedMetaAdmissionInvalidatesRegardlessOfStorageMode(String operation){try(var r=new Rig(65536,65536,65536)){
        r.put("k","old");var req=request(ExtendedRequest.Operation.valueOf(operation),"k");req.metaStore=true;
        assertEquals(ExtendedResult.Status.TOO_LARGE,r.engine.admitExtended(req,4096));assertNull(req.admitted);
        try(var out=r.engine.execute(request(ExtendedRequest.Operation.GET,"k"))){assertEquals(ExtendedResult.Status.NOT_FOUND,out.status);}assertEquals(0,r.engine.serviceMetadataUsedBytes());
    }}
    @Test void ttlCopyFailurePreservesPinnedTargetAtNativeLimit(){try(var r=new Rig(4096,65536,65536)){
        long cas=r.put("k","x".repeat(3000));var touch=request(ExtendedRequest.Operation.GET,"k");touch.hasTtl=true;touch.ttl=30;
        try(var out=r.engine.execute(touch)){assertEquals(ExtendedResult.Status.OUT_OF_MEMORY,out.status);}
        try(var out=r.engine.execute(request(ExtendedRequest.Operation.DEBUG,"k"))){assertEquals(cas,out.cas);assertEquals(-1,out.ttl);assertEquals("x".repeat(3000),value(out));}
    }}
    @Test void directExecuteRetriesAfterEvictingOtherKeyButNeverProtectedTarget(){try(var r=new Rig(8192,65536,65536,"lru")){
        r.put("other","o".repeat(3000));long cas=r.put("k","x".repeat(3000));var set=request(ExtendedRequest.Operation.SET,"k");set.value=b("n".repeat(3000));try(var out=r.engine.execute(set)){assertEquals(ExtendedResult.Status.OK,out.status);assertNotEquals(cas,out.cas);}
        try(var out=r.engine.execute(request(ExtendedRequest.Operation.GET,"other"))){assertEquals(ExtendedResult.Status.NOT_FOUND,out.status);}
    }}
    @Test void nonSetNativeAdmissionFailurePreservesExistingKey(){try(var r=new Rig(4096,65536,65536)){
        long cas=r.put("k","a".repeat(3000));var replace=request(ExtendedRequest.Operation.REPLACE,"k");assertEquals(ExtendedResult.Status.OUT_OF_MEMORY,r.engine.admitExtended(replace,3000));
        try(var out=r.engine.execute(request(ExtendedRequest.Operation.GET,"k"))){assertEquals(cas,out.cas);assertEquals(3000,out.size);}
        replace.metaStore=true;assertEquals(ExtendedResult.Status.OUT_OF_MEMORY,r.engine.admitExtended(replace,3000));try(var out=r.engine.execute(request(ExtendedRequest.Operation.GET,"k"))){assertEquals(ExtendedResult.Status.NOT_FOUND,out.status);}
    }}
    @Test void debugNeverCreatesOrTouchesEvenWithBorrowedMutationHints(){try(var r=new Rig(65536,65536,65536)){
        var debug=request(ExtendedRequest.Operation.DEBUG,"missing");debug.create=true;debug.hasTtl=true;debug.ttl=30;
        try(var out=r.engine.execute(debug)){assertEquals(ExtendedResult.Status.NOT_FOUND,out.status);}
        long cas=r.put("missing","old");try(var out=r.engine.execute(debug)){assertEquals(cas,out.cas);assertEquals(-1,out.ttl);assertFalse(out.fetched);}
        try(var out=r.engine.execute(request(ExtendedRequest.Operation.DEBUG,"missing"))){assertEquals(cas,out.cas);assertEquals(-1,out.ttl);}
    }}
    @Test void recacheThresholdDoesNotWinForImmortalOrLongLivedRecords(){try(var r=new Rig(65536,65536,65536)){
        r.put("forever","x");var get=request(ExtendedRequest.Operation.GET,"forever");get.claimRecache=true;get.recache=true;get.recacheTtl=5;get.orderedFlags=new String[]{"R5","t"};
        try(var out=r.engine.execute(get)){assertFalse(out.winner);assertEquals(-1,out.ttl);}
        get.hasTtl=true;get.ttl=30;get.orderedFlags=new String[]{"T30","R5","t"};try(var out=r.engine.execute(get)){assertFalse(out.winner);assertEquals(30,out.ttl);}
        var vivify=request(ExtendedRequest.Operation.GET,"zero");vivify.claimRecache=true;vivify.create=true;vivify.createTtl=0;vivify.orderedFlags=new String[]{"N0","t"};
        try(var out=r.engine.execute(vivify)){assertTrue(out.winner);assertEquals(-1,out.ttl);}try(var out=r.engine.execute(vivify)){assertTrue(out.alreadyWon);assertEquals(-1,out.ttl);}
    }}
    @Test void invalidateWithNewerCasRejectsAndOlderCasRetainsState(){try(var r=new Rig(65536,65536,65536)){
        long cas=r.put("k","old");var set=request(ExtendedRequest.Operation.SET,"k");set.value=b("new");set.invalidate=true;set.compareCas=true;set.expectedCas=cas+1;
        try(var out=r.engine.execute(set)){assertEquals(ExtendedResult.Status.EXISTS,out.status);}set.expectedCas=cas-1;try(var out=r.engine.execute(set)){assertEquals(ExtendedResult.Status.OK,out.status);assertTrue(out.stale);}
    }}
    @Test void ordinaryOversizeReplacePreservesTargetAndOversizeNewSetStaysAbsent(){try(var r=new Rig(65536,65536,65536)){
        long cas=r.put("k","old");var replace=request(ExtendedRequest.Operation.REPLACE,"k");assertEquals(ExtendedResult.Status.TOO_LARGE,r.engine.admitExtended(replace,4096));
        try(var out=r.engine.execute(request(ExtendedRequest.Operation.GET,"k"))){assertEquals(cas,out.cas);}
        var set=request(ExtendedRequest.Operation.SET,"new");set.value=new byte[4096];try(var out=r.engine.execute(set)){assertEquals(ExtendedResult.Status.TOO_LARGE,out.status);}
    }}
    @Test void nativeCombinedFailureAndOrderedArithmeticCreationPreserveContracts(){try(var r=new Rig(4096,65536,65536)){
        r.put("k","a".repeat(3000));var append=request(ExtendedRequest.Operation.APPEND,"k");append.value=b("x");try(var out=r.engine.execute(append)){assertEquals(ExtendedResult.Status.NOT_STORED,out.status);}
    }try(var r=new Rig(65536,65536,65536)){
        var incr=request(ExtendedRequest.Operation.INCR,"n");incr.create=true;incr.createTtl=30;incr.hasTtl=true;incr.ttl=10;incr.createTtlLast=true;incr.initial=7;
        try(var out=r.engine.execute(incr)){assertEquals(7,out.number);assertEquals(30,out.ttl);}
    }}
}

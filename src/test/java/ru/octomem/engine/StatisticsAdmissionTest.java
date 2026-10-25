package ru.octomem.engine;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.octomem.stats.*;
import ru.octomem.storage.*;
import ru.octomem.util.Clock;
import static org.junit.jupiter.api.Assertions.*;

/** A fault in a statistics collaborator cannot strand newly reserved storage ownership. */
class StatisticsAdmissionTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void failedAdmissionStatisticsRollBackBeforeReturningToCaller(boolean error){
        try(var allocator=new FfmSlabAllocator(65536,4096,4096);var table=new SegmentedItemTable(1,2,65536)){
            var delegate=new AtomicStatsRegistry(Map.of("pid","1","version","stats-fault","pointer_size","64","threads","1","limit_maxbytes","65536"));AtomicBoolean fail=new AtomicBoolean(true);
            AssertionError expectedError=new AssertionError("synthetic statistics failure");IllegalStateException expectedException=new IllegalStateException("synthetic statistics failure");
            StatsRegistry stats=new StatsRegistry(){
                public void increment(Counter counter,long delta){if(counter==Counter.CMD_SET&&fail.get()){if(error)throw expectedError;throw expectedException;}delegate.increment(counter,delta);}
                public void setGauge(Gauge gauge,long value){delegate.setGauge(gauge,value);}public Snapshot snapshot(){return delegate.snapshot();}public void reset(){delegate.reset();}
            };
            Clock clock=new Clock(){public long currentTimeSeconds(){return 1700000000;}public long nanoTime(){return 0;}};
            var engine=new DefaultCacheEngine(allocator,table,clock,stats,new DefaultCacheEngine.Settings(65536,4096,"noeviction",86400));engine.start();
            ExtendedRequest request=new ExtendedRequest();request.operation=ExtendedRequest.Operation.SET;request.key=new byte[]{'k'};request.metaStore=true;
            try{
                if(error)assertSame(expectedError,assertThrows(AssertionError.class,()->engine.admitExtended(request,3)));else assertSame(expectedException,assertThrows(IllegalStateException.class,()->engine.admitExtended(request,3)));
                // Assert before request.close(): caller cleanup must not conceal leaked reservation.
                assertAll(()->assertNull(request.admitted),()->assertEquals(0,allocator.usedMemory()),()->assertEquals(0,engine.serviceMetadataUsedBytes()));
                fail.set(false);assertEquals(ExtendedResult.Status.OK,engine.admitExtended(request,3));request.value=new byte[]{'n','e','w'};
                try(var result=engine.execute(request)){assertEquals(ExtendedResult.Status.OK,result.status);}
                assertEquals(1,stats.snapshot().counters().get(Counter.CMD_SET));
            }finally{request.close();engine.stop();}
            assertEquals(0,allocator.usedMemory());assertEquals(0,engine.serviceMetadataUsedBytes());
        }
    }
}

package ru.octomem.transport;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Trusted datagram I/O fault injection still uses actual sockets and worker cleanup. */
@Timeout(15)
class ExpandedUdpFaultTest {
    static int port()throws Exception{try(DatagramSocket s=new DatagramSocket(new InetSocketAddress("127.0.0.1",0))){return s.getLocalPort();}}
    @Test void singleStartAndAfterCloseCannotLeakOrRestart()throws Exception{
        int port=port();AtomicInteger closed=new AtomicInteger();UdpTransport transport=new UdpTransport(new InetSocketAddress("127.0.0.1",port),r->new ExpandedNioTlsTest.Handler(r,closed,false),4096);
        try{assertEquals(0,transport.usedControlBytes());transport.start();assertTrue(transport.usedControlBytes()>=1_048_576+65536+1400+262144+10816);assertThrows(IllegalStateException.class,transport::start);}finally{transport.close();}
        transport.close();assertEquals(0,transport.usedControlBytes());assertThrows(IllegalStateException.class,transport::start);
        try(DatagramSocket rebound=new DatagramSocket(new InetSocketAddress("127.0.0.1",port))){assertEquals(port,rebound.getLocalPort());}
    }
    @ParameterizedTest @ValueSource(strings={"receive-io","receive-runtime","send-io","send-error"})
    void unexpectedIoOrErrorSignalsCommonFailureAndReleasesFixedCharge(String fault)throws Exception{
        int port=port();CountDownLatch failed=new CountDownLatch(1);AtomicInteger failures=new AtomicInteger(),closed=new AtomicInteger();AtomicLong body=new AtomicLong();
        UdpTransport.Ops ops=new UdpTransport.Ops(){
            @Override SocketAddress receive(DatagramChannel channel,ByteBuffer target)throws IOException{if(fault.equals("receive-io"))throw new IOException("synthetic receive");if(fault.equals("receive-runtime"))throw new IllegalStateException("synthetic receive");return super.receive(channel,target);}
            @Override int send(DatagramChannel channel,ByteBuffer bytes,SocketAddress target)throws IOException{if(fault.equals("send-error"))throw new AssertionError("synthetic send");throw new IOException("synthetic send");}
        };
        UdpTransport transport=new UdpTransport(new InetSocketAddress("127.0.0.1",port),r->new ExpandedNioTlsTest.Handler(r,closed,false),4096,()->{failures.incrementAndGet();failed.countDown();},()->new ConnectionResources(body,65536,4096),ops);
        try{transport.start();try(DatagramSocket peer=new DatagramSocket()){byte[] bytes=ByteBuffer.allocate(17).putShort((short)1).putShort((short)0).putShort((short)1).putShort((short)0).put("version\r\n".getBytes()).array();peer.send(new DatagramPacket(bytes,bytes.length,InetAddress.getLoopbackAddress(),port));assertTrue(failed.await(3,TimeUnit.SECONDS));}}
        finally{transport.close();}assertEquals(1,failures.get());assertEquals(0,transport.usedControlBytes());assertEquals(0,body.get());if(fault.startsWith("send"))assertEquals(1,closed.get());
    }
    @Test void noProgressSendDropsOnlyDatagramAndNextRequestStillWorks()throws Exception{
        int port=port();AtomicInteger sends=new AtomicInteger(),closed=new AtomicInteger();UdpTransport.Ops ops=new UdpTransport.Ops(){@Override int send(DatagramChannel c,ByteBuffer b,SocketAddress p)throws IOException{if(sends.incrementAndGet()==1)return 0;return super.send(c,b,p);}};
        UdpTransport transport=new UdpTransport(new InetSocketAddress("127.0.0.1",port),r->new ExpandedNioTlsTest.Handler(r,closed,false),4096,()->fail("unexpected worker failure"),()->new ConnectionResources(new AtomicLong(),65536,4096),ops);
        try{transport.start();try(DatagramSocket peer=new DatagramSocket()){peer.setSoTimeout(2000);for(int id=1;id<=2;id++){byte[] data=ByteBuffer.allocate(17).putShort((short)id).putShort((short)0).putShort((short)1).putShort((short)0).put("version\r\n".getBytes()).array();peer.send(new DatagramPacket(data,data.length,InetAddress.getLoopbackAddress(),port));}byte[] reply=new byte[100];DatagramPacket packet=new DatagramPacket(reply,reply.length);peer.receive(packet);assertEquals(2,Short.toUnsignedInt(ByteBuffer.wrap(reply).getShort()));}}
        finally{transport.close();}assertEquals(2,closed.get());assertEquals(0,transport.usedControlBytes());
    }
    @ParameterizedTest @ValueSource(strings={"before-runtime","before-error","after-runtime"})
    void startupFailureBeforeOrAfterThreadStartReleasesAllOwnership(String fault)throws Exception{
        int port=port();UdpTransport.Ops ops=new UdpTransport.Ops(){@Override void start(Thread worker){if(fault.equals("after-runtime"))worker.start();if(fault.equals("before-error"))throw new AssertionError("synthetic start failure");throw new IllegalStateException("synthetic start failure");}};
        var transport=new UdpTransport(new InetSocketAddress("127.0.0.1",port),r->new ExpandedNioTlsTest.Handler(r,new AtomicInteger(),false),4096,()->{},()->new ConnectionResources(new AtomicLong(),65536,4096),ops);
        try{if(fault.equals("before-error"))assertThrows(AssertionError.class,transport::start);else assertThrows(IllegalStateException.class,transport::start);}finally{transport.close();}
        assertEquals(0,transport.usedControlBytes());try(DatagramSocket rebound=new DatagramSocket(new InetSocketAddress("127.0.0.1",port))){assertEquals(port,rebound.getLocalPort());}
    }
    @Test void concurrentCloseWaitsForPublishedWorkerWithoutLeakingCharge()throws Exception{
        int port=port();CountDownLatch inStart=new CountDownLatch(1),allowStart=new CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();
        UdpTransport.Ops ops=new UdpTransport.Ops(){@Override void start(Thread worker){inStart.countDown();try{if(!allowStart.await(5,TimeUnit.SECONDS))throw new AssertionError("start barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}worker.start();}};
        var transport=new UdpTransport(new InetSocketAddress("127.0.0.1",port),r->new ExpandedNioTlsTest.Handler(r,new AtomicInteger(),false),4096,()->{},()->new ConnectionResources(new AtomicLong(),65536,4096),ops);
        Thread starter=new Thread(()->{try{transport.start();}catch(Throwable t){error.set(t);}}),closer=new Thread(transport::close);
        try{starter.start();assertTrue(inStart.await(3,TimeUnit.SECONDS));closer.start();allowStart.countDown();starter.join(5000);closer.join(5000);assertFalse(starter.isAlive());assertFalse(closer.isAlive());assertNull(error.get());assertEquals(0,transport.usedControlBytes());}finally{allowStart.countDown();transport.close();}
    }
    @Test void closeBeforeStartAndNonLoopbackConstructionFailClosed()throws Exception{
        ConnectionFactory factory=r->new ExpandedNioTlsTest.Handler(r,new AtomicInteger(),false);
        assertThrows(IllegalArgumentException.class,()->new UdpTransport(new InetSocketAddress("0.0.0.0",11223),factory,4096));
        var transport=new UdpTransport(new InetSocketAddress("127.0.0.1",port()),factory,4096);transport.close();assertThrows(IllegalStateException.class,transport::start);assertEquals(0,transport.usedControlBytes());
    }
    @Test void deniedSharedLineBudgetReturnsBoundedErrorAndNoHandler()throws Exception{
        int port=port();SharedBudget lines=new SharedBudget(0,0);AtomicInteger factories=new AtomicInteger();
        var transport=new UdpTransport(new InetSocketAddress("127.0.0.1",port),r->{factories.incrementAndGet();throw new AssertionError();},4096,()->fail(),()->new ConnectionResources(new AtomicLong(),65536,4096,lines,1,1),new UdpTransport.Ops());
        try{transport.start();try(DatagramSocket peer=new DatagramSocket()){peer.setSoTimeout(2000);byte[] request=ByteBuffer.allocate(17).putLong(1L<<16).put("version\r\n".getBytes()).array();peer.send(new DatagramPacket(request,request.length,InetAddress.getLoopbackAddress(),port));byte[] out=new byte[256];DatagramPacket response=new DatagramPacket(out,out.length);peer.receive(response);assertEquals("SERVER_ERROR UDP response limit or invalid request\r\n",new String(out,8,response.getLength()-8,java.nio.charset.StandardCharsets.US_ASCII));}}finally{transport.close();}assertEquals(0,factories.get());assertEquals(0,lines.usedBytes());
    }
    @Test void interruptedCloseJoinsWorkerThenRestoresInterrupt()throws Exception{
        var transport=new UdpTransport(new InetSocketAddress("127.0.0.1",port()),r->new ExpandedNioTlsTest.Handler(r,new AtomicInteger(),false),4096);transport.start();Thread.currentThread().interrupt();
        try{transport.close();assertTrue(Thread.currentThread().isInterrupted());assertEquals(0,transport.usedControlBytes());}finally{Thread.interrupted();transport.close();}
    }
    @ParameterizedTest @ValueSource(strings={"encode-failure","decoder-stall","silent-protocol-error","close-after-response"})
    void componentFaultsReleaseResponseHandlerAndDatagramResources(String mode)throws Exception{
        int port=port();AtomicInteger closed=new AtomicInteger();CountDownLatch handled=new CountDownLatch(1);AtomicLong body=new AtomicLong();
        ConnectionFactory factory=r->new ExpandedNioTlsTest.Handler(r,closed,mode.equals("close-after-response")){
            @Override public ru.octomem.protocol.ProtocolEncoder encoder(){if(mode.equals("encode-failure"))return response->{throw new IllegalStateException("synthetic encode");};return super.encoder();}
            @Override public ru.octomem.protocol.ProtocolDecoder decoder(){if(!mode.equals("decoder-stall")&&!mode.equals("silent-protocol-error"))return super.decoder();return new ru.octomem.protocol.ProtocolDecoder(){
                public void append(ByteBuffer input){}
                public ru.octomem.protocol.Command nextCommand()throws ru.octomem.protocol.ProtocolException{if(mode.equals("silent-protocol-error"))throw new ru.octomem.protocol.ProtocolException(ru.octomem.protocol.ProtocolException.Kind.CLIENT_ERROR,true);return null;}
                public void release(ru.octomem.protocol.Command c){}
            };}
            @Override public ru.octomem.protocol.Response protocolError(ru.octomem.protocol.ProtocolException failure){return null;}
            @Override public void close(){super.close();handled.countDown();}
        };
        var transport=new UdpTransport(new InetSocketAddress("127.0.0.1",port),factory,4096,()->fail("unexpected worker failure"),()->new ConnectionResources(body,65536,4096),new UdpTransport.Ops());
        try{transport.start();try(DatagramSocket peer=new DatagramSocket()){peer.setSoTimeout(2000);byte[] data=ByteBuffer.allocate(17).putLong(1L<<16).put("version\r\n".getBytes()).array();peer.send(new DatagramPacket(data,data.length,InetAddress.getLoopbackAddress(),port));assertTrue(handled.await(3,TimeUnit.SECONDS));
            if(mode.equals("encode-failure")||mode.equals("close-after-response")){byte[] bytes=new byte[200];DatagramPacket reply=new DatagramPacket(bytes,bytes.length);peer.receive(reply);String actual=new String(bytes,8,reply.getLength()-8,java.nio.charset.StandardCharsets.US_ASCII);assertEquals(mode.equals("encode-failure")?"SERVER_ERROR UDP response limit or invalid request\r\n":"VERSION fault\r\n",actual);}
        }}finally{transport.close();}assertEquals(1,closed.get());assertEquals(0,body.get());assertEquals(0,transport.usedControlBytes());
    }
    @Test void workerCanInitiateShutdownDuringFragmentSendWithoutSelfJoin()throws Exception{
        int port=port();AtomicInteger failures=new AtomicInteger();CountDownLatch done=new CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<UdpTransport> holder=new java.util.concurrent.atomic.AtomicReference<>();
        UdpTransport.Ops ops=new UdpTransport.Ops(){@Override int send(DatagramChannel c,ByteBuffer b,SocketAddress p)throws IOException{holder.get().close();done.countDown();throw new IOException("socket closed by owner");}};
        var transport=new UdpTransport(new InetSocketAddress("127.0.0.1",port),r->new ExpandedNioTlsTest.Handler(r,new AtomicInteger(),false),4096,failures::incrementAndGet,()->new ConnectionResources(new AtomicLong(),65536,4096),ops);holder.set(transport);
        try{transport.start();try(DatagramSocket peer=new DatagramSocket()){byte[] bytes=ByteBuffer.allocate(17).putLong(1L<<16).put("version\r\n".getBytes()).array();peer.send(new DatagramPacket(bytes,bytes.length,InetAddress.getLoopbackAddress(),port));assertTrue(done.await(3,TimeUnit.SECONDS));}}finally{transport.close();}assertEquals(0,failures.get());assertEquals(0,transport.usedControlBytes());
    }
    @Test void closeAfterSuccessfulFragmentStopsFurtherFragmentAndReceiveTurns()throws Exception{
        int port=port();CountDownLatch sent=new CountDownLatch(1);AtomicInteger sends=new AtomicInteger();java.util.concurrent.atomic.AtomicReference<UdpTransport> owner=new java.util.concurrent.atomic.AtomicReference<>();
        UdpTransport.Ops ops=new UdpTransport.Ops(){@Override int send(DatagramChannel channel,ByteBuffer bytes,SocketAddress peer){int size=bytes.remaining();bytes.position(bytes.limit());sends.incrementAndGet();owner.get().close();sent.countDown();return size;}};
        var transport=new UdpTransport(new InetSocketAddress("127.0.0.1",port),r->new ExpandedNioTlsTest.Handler(r,new AtomicInteger(),false),4096,()->fail("normal close not worker failure"),()->new ConnectionResources(new AtomicLong(),65536,4096),ops);owner.set(transport);
        try{transport.start();try(DatagramSocket peer=new DatagramSocket()){byte[] data=ByteBuffer.allocate(17).putLong(1L<<16).put("version\r\n".getBytes()).array();peer.send(new DatagramPacket(data,data.length,InetAddress.getLoopbackAddress(),port));assertTrue(sent.await(3,TimeUnit.SECONDS));}}finally{transport.close();}assertEquals(1,sends.get());assertEquals(0,transport.usedControlBytes());
    }
    @ParameterizedTest @ValueSource(ints={-1,0,1})
    void binaryReplyLimitIsInclusiveAndNeverSendsPartialSuccess(int difference)throws Exception{
        int port=port();var clock=new ru.octomem.util.SystemClock();
        try(var allocator=new ru.octomem.storage.FfmSlabAllocator(4L*1024*1024,1048576,2097152);var table=new ru.octomem.storage.SegmentedItemTable(1,2,65536)){
            var stats=new ru.octomem.stats.AtomicStatsRegistry(java.util.Map.of("pid","1","version","limit","pointer_size","64","threads","1","limit_maxbytes","4194304"));
            var engine=new ru.octomem.engine.DefaultCacheEngine(allocator,table,clock,stats,new ru.octomem.engine.DefaultCacheEngine.Settings(4194304,2097152,"noeviction",86400));engine.start();
            try{
                int length=1048576-28+difference;byte[] value=new byte[length];java.util.Arrays.fill(value,(byte)'x');assertEquals(ru.octomem.engine.StoreResult.STORED,engine.set(new byte[]{'k'},value,7,0));
                var dispatcher=new ru.octomem.protocol.CacheCommandDispatcher(engine,stats,allocator,clock,"limit");var factory=new MemcachedConnectionFactory(8192,2097152,512,engine,dispatcher,"limit");
                java.io.ByteArrayOutputStream captured=new java.io.ByteArrayOutputStream();CountDownLatch complete=new CountDownLatch(1);AtomicInteger packets=new AtomicInteger();
                UdpTransport.Ops ops=new UdpTransport.Ops(){@Override int send(DatagramChannel channel,ByteBuffer data,SocketAddress peer){int size=data.remaining();assertTrue(size<=1400);assertEquals(33,Short.toUnsignedInt(data.getShort()));int seq=Short.toUnsignedInt(data.getShort()),total=Short.toUnsignedInt(data.getShort());assertEquals(0,data.getShort());assertEquals(packets.getAndIncrement(),seq);byte[] payload=new byte[data.remaining()];data.get(payload);captured.writeBytes(payload);if(seq+1==total)complete.countDown();return size;}};
                var transport=new UdpTransport(new InetSocketAddress("127.0.0.1",port),factory,2097152,()->fail("worker failure"),()->new ConnectionResources(new AtomicLong(),4194304,2097152),ops);
                try{transport.start();try(DatagramSocket peer=new DatagramSocket()){byte[] request=ByteBuffer.allocate(33).putShort((short)33).putShort((short)0).putShort((short)1).putShort((short)0).put((byte)128).put((byte)0).putShort((short)1).putInt(0).putInt(1).putInt(99).putLong(0).put((byte)'k').array();peer.send(new DatagramPacket(request,request.length,InetAddress.getLoopbackAddress(),port));assertTrue(complete.await(5,TimeUnit.SECONDS));}
                    ByteBuffer response=ByteBuffer.wrap(captured.toByteArray());assertEquals(129,Byte.toUnsignedInt(response.get()));assertEquals(0,response.get());response.position(6);assertEquals(difference<=0?0:130,Short.toUnsignedInt(response.getShort()));
                    if(difference<=0){assertEquals(1048576+difference,captured.size());response.position(24);assertEquals(7,response.getInt());byte[] actual=new byte[length];response.get(actual);assertArrayEquals(value,actual);}else{assertTrue(captured.size()<200);assertEquals(1,packets.get());}
                }finally{transport.close();assertEquals(0,transport.usedControlBytes());}
            }finally{engine.stop();}
        }
    }
}

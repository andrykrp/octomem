package ru.octomem.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngineResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.octomem.protocol.*;
import ru.octomem.stats.AtomicStatsRegistry;
import ru.octomem.util.SystemClock;
import static org.junit.jupiter.api.Assertions.*;

/** Real NIO owner/admission/cleanup driven through trusted TLS and I/O fault seams. */
@Timeout(20)
class ExpandedNioTlsTest {
    static NioTransport transport(TransportOps ops,ConnectionFactory factory,int connections)throws Exception{
        var settings=new TransportSettings(new InetSocketAddress("127.0.0.1",0),1,connections,2,1,0,1024,2,65536,4096);
        return new NioTransport(settings,factory,new AtomicStatsRegistry(Map.of("pid","1","version","fault","pointer_size","64","threads","1","limit_maxbytes","65536")),new SystemClock(),ops,null,null,SSLContext.getDefault());
    }
    static Socket connect(NioTransport transport)throws Exception{Socket s=new Socket();s.connect(transport.localAddress(),2000);s.setSoTimeout(3000);return s;}
    static class Handler implements ConnectionHandler {
        final AtomicInteger closes;final TextProtocolDecoder decoder;final boolean closeAfter;
        Handler(ConnectionResources resources,AtomicInteger closes,boolean closeAfter){this.closes=closes;this.closeAfter=closeAfter;decoder=new TextProtocolDecoder(1024,4096,new ProtocolBudget(){public boolean tryReserveBody(int n){return resources.tryReserveBody(n);}public void releaseBody(int n){resources.releaseBody(n);}});}
        public ProtocolDecoder decoder(){return decoder;}public ProtocolEncoder encoder(){return new TextProtocolEncoder(2);}
        public Response handle(Command command){decoder.release(command);return new Response(){public ResponseKind kind(){return ResponseKind.VERSION;}public ru.octomem.engine.RetrievalSnapshot snapshot(){return null;}public boolean includeCas(){return false;}public long number(){return 0;}public String text(){return "fault";}public List<String> statsLines(){return null;}public void close(){}};}
        public Response protocolError(ProtocolException e){throw new AssertionError(e);}
        public boolean closeAfterResponse(){return closeAfter;}public void close(){decoder.close();closes.incrementAndGet();}
    }
    @Test void partialCiphertextWritesDrainResponseAndCloseExactlyOnce()throws Exception{
        AtomicInteger writes=new AtomicInteger(),closes=new AtomicInteger();TransportOps ops=new TransportOps(){
            @Override TlsChannel tls(SSLContext c,Executor executor,Runnable wake)throws IOException{try{var engine=new ExpandedTlsChannelTest.Engine();engine.unwrapStatus=SSLEngineResult.Status.OK;return new TlsChannel(engine,executor,wake,this);}catch(IOException e){throw e;}catch(Exception e){throw new IOException(e);}}
            @Override int write(SocketChannel socket,ByteBuffer bytes)throws IOException{
                if(writes.incrementAndGet()%3==1)return 0;int limit=bytes.limit();bytes.limit(bytes.position()+Math.min(1,bytes.remaining()));try{return super.write(socket,bytes);}finally{bytes.limit(limit);}}
        };
        try(var transport=transport(ops,r->new Handler(r,closes,true),2)){
            transport.start();try(Socket peer=connect(transport)){peer.getOutputStream().write("version\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));byte[] expected="VERSION fault\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);assertArrayEquals(expected,peer.getInputStream().readNBytes(expected.length));assertEquals(21,peer.getInputStream().read());assertEquals(-1,peer.getInputStream().read());}
            transport.close();assertTrue(writes.get()>10);assertEquals(1,closes.get());assertEquals(0,transport.usedControlBytes());assertEquals(0,transport.usedLineBytes());
        }
    }
    @Test void partialPlaintextWrapAndDelegatedRenegotiationPreservePendingWriter()throws Exception{
        AtomicInteger closes=new AtomicInteger(),delegated=new AtomicInteger();TransportOps ops=new TransportOps(){
            @Override TlsChannel tls(SSLContext c,Executor executor,Runnable wake)throws IOException{try{var e=new ExpandedTlsChannelTest.Engine();e.unwrapStatus=SSLEngineResult.Status.OK;e.writeLimit=1;e.renegotiateOnWrap=true;e.delegated=()->{delegated.incrementAndGet();e.state.value=SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;};return new TlsChannel(e,executor,wake,this);}catch(Exception x){throw new IOException(x);}}
        };
        try(var transport=transport(ops,r->new Handler(r,closes,true),1)){transport.start();try(Socket peer=connect(transport)){
            peer.getOutputStream().write("version\r\n".getBytes());assertEquals("VERSION fault\r\n",new String(peer.getInputStream().readNBytes(15),java.nio.charset.StandardCharsets.US_ASCII));assertEquals(21,peer.getInputStream().read());assertEquals(-1,peer.getInputStream().read());assertEquals(1,delegated.get());
        }transport.close();assertEquals(1,closes.get());assertEquals(0,transport.usedControlBytes());}
    }
    @Test void deferredCloseNotifyRetriesWithoutDroppingFinalReply()throws Exception{
        AtomicInteger alerts=new AtomicInteger(),closes=new AtomicInteger();TransportOps ops=new TransportOps(){
            @Override TlsChannel tls(SSLContext c,Executor executor,Runnable wake)throws IOException{try{var e=new ExpandedTlsChannelTest.Engine();e.unwrapStatus=SSLEngineResult.Status.OK;return new TlsChannel(e,executor,wake,this);}catch(Exception x){throw new IOException(x);}}
            @Override int write(SocketChannel socket,ByteBuffer bytes)throws IOException{if(bytes.hasRemaining()&&bytes.get(bytes.position())==21&&alerts.incrementAndGet()<=3)return 0;return super.write(socket,bytes);}
        };
        try(var transport=transport(ops,r->new Handler(r,closes,true),1)){transport.start();try(Socket peer=connect(transport)){
            peer.getOutputStream().write("version\r\n".getBytes());assertEquals("VERSION fault\r\n",new String(peer.getInputStream().readNBytes(15),java.nio.charset.StandardCharsets.US_ASCII));assertEquals(21,peer.getInputStream().read());assertEquals(-1,peer.getInputStream().read());
        }transport.close();assertTrue(alerts.get()>=4);assertEquals(1,closes.get());assertEquals(0,transport.usedControlBytes());}
    }
    @Test void queuedTlsTaskRetainsAdmissionUntilCompletionAndShutdownJoinsWorker()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),handlerClosed=new CountDownLatch(1);AtomicInteger closes=new AtomicInteger();
        TransportOps ops=new TransportOps(){@Override TlsChannel tls(SSLContext c,Executor executor,Runnable wake)throws IOException{try{
            var engine=new ExpandedTlsChannelTest.Engine();engine.state.value=SSLEngineResult.HandshakeStatus.NEED_TASK;engine.delegated=()->{entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("task release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}engine.state.value=SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;};return new TlsChannel(engine,executor,wake,this);
        }catch(Exception e){throw new IOException(e);}}};
        var transport=transport(ops,r->new Handler(r,closes,false){@Override public void close(){super.close();handlerClosed.countDown();}},1);
        try{
            transport.start();try(Socket peer=connect(transport)){peer.getOutputStream().write(1);assertTrue(entered.await(3,TimeUnit.SECONDS));assertTrue(transport.usedControlBytes()>=TlsChannel.CHARGE);
                Thread closer=new Thread(transport::close,"test-transport-close");closer.start();assertTrue(handlerClosed.await(3,TimeUnit.SECONDS));assertTrue(transport.usedControlBytes()>=TlsChannel.CHARGE,"pending task still owns charged buffers");release.countDown();closer.join(5000);assertFalse(closer.isAlive());assertEquals(0,transport.usedControlBytes());assertEquals(1,closes.get());}
        }finally{release.countDown();transport.close();}
    }
    @Test void completionAlreadyInFlightWhenShutdownBeginsCannotRequeueStoppedOwner()throws Exception{
        CountDownLatch callbackEntered=new CountDownLatch(1),deliverCallback=new CountDownLatch(1),handlerClosed=new CountDownLatch(1);AtomicInteger closes=new AtomicInteger();
        TransportOps ops=new TransportOps(){@Override TlsChannel tls(SSLContext context,Executor executor,Runnable ownerWake)throws IOException{try{
            var e=new ExpandedTlsChannelTest.Engine();e.state.value=SSLEngineResult.HandshakeStatus.NEED_TASK;e.delegated=()->e.state.value=SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;
            Runnable delayedWake=()->{callbackEntered.countDown();try{if(!deliverCallback.await(5,TimeUnit.SECONDS))throw new AssertionError("callback delivery timeout");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}ownerWake.run();};
            return new TlsChannel(e,executor,delayedWake,this);
        }catch(Exception failure){throw new IOException(failure);}}};
        var transport=transport(ops,r->new Handler(r,closes,false){@Override public void close(){super.close();handlerClosed.countDown();}},1);
        try{transport.start();try(Socket peer=connect(transport)){peer.getOutputStream().write(1);assertTrue(callbackEntered.await(3,TimeUnit.SECONDS));
            Thread closer=new Thread(transport::close,"shutdown-with-inflight-tls-completion");closer.start();assertTrue(handlerClosed.await(3,TimeUnit.SECONDS));deliverCallback.countDown();closer.join(5000);assertFalse(closer.isAlive());transport.awaitTermination();assertEquals(1,closes.get());assertEquals(0,transport.usedControlBytes());assertEquals(0,transport.usedLineBytes());
        }}finally{deliverCallback.countDown();transport.close();}
    }
    @Test void invalidAdditionalControlChargeFailsBeforeAnyThreads()throws Exception{
        ConnectionFactory invalid=new ConnectionFactory(){public ConnectionHandler create(ConnectionResources r){throw new AssertionError();}public long additionalControlBytes(){return -1;}};
        assertThrows(IllegalArgumentException.class,()->transport(new TransportOps(),invalid,1));
    }
    @Test void transportSupportsStatsRegistryWithoutClassCapability()throws Exception{
        var delegate=new AtomicStatsRegistry(Map.of("pid","1","version","plain-stats","pointer_size","64","threads","1","limit_maxbytes","65536"));
        ru.octomem.stats.StatsRegistry plain=new ru.octomem.stats.StatsRegistry(){public void increment(ru.octomem.stats.Counter c,long n){delegate.increment(c,n);}public void setGauge(ru.octomem.stats.Gauge g,long n){delegate.setGauge(g,n);}public ru.octomem.stats.Snapshot snapshot(){return delegate.snapshot();}public void reset(){delegate.reset();}};
        var settings=new TransportSettings(new InetSocketAddress("127.0.0.1",0),1,1,2,1,0,1024,2,65536,4096);AtomicInteger closed=new AtomicInteger();
        try(var transport=new NioTransport(settings,r->new Handler(r,closed,false),plain,new SystemClock())){transport.start();try(Socket s=connect(transport)){s.getOutputStream().write("version\r\n".getBytes());assertEquals("VERSION fault\r\n",new String(s.getInputStream().readNBytes(15),java.nio.charset.StandardCharsets.US_ASCII));}transport.close();assertEquals(1,closed.get());assertEquals(0,transport.usedControlBytes());}
    }
    @Test void tlsSelectorFailureClosesOwnedThreadsAndControlBudget()throws Exception{
        AtomicInteger closes=new AtomicInteger();CountDownLatch failNow=new CountDownLatch(1),failureInjected=new CountDownLatch(1),failureCleanup=new CountDownLatch(1);java.util.concurrent.atomic.AtomicBoolean failed=new java.util.concurrent.atomic.AtomicBoolean();
        TransportOps ops=new TransportOps(){
            @Override TlsChannel tls(SSLContext c,Executor executor,Runnable wake)throws IOException{try{var e=new ExpandedTlsChannelTest.Engine();e.unwrapStatus=SSLEngineResult.Status.OK;return new TlsChannel(e,executor,wake,this);}catch(Exception x){throw new IOException(x);}}
            @Override int select(java.nio.channels.Selector selector,long timeout)throws IOException{if(Thread.currentThread().getName().contains("event")&&failNow.getCount()==0&&failed.compareAndSet(false,true)){failureInjected.countDown();throw new IOException("synthetic selector failure");}return super.select(selector,timeout);}
        };
        var transport=transport(ops,r->new Handler(r,closes,false){@Override public void close(){super.close();failureCleanup.countDown();}},1);
        try{transport.start();try(Socket peer=connect(transport)){peer.getOutputStream().write("version\r\n".getBytes());assertEquals("VERSION fault\r\n",new String(peer.getInputStream().readNBytes(15),java.nio.charset.StandardCharsets.US_ASCII));failNow.countDown();peer.getOutputStream().write("version\r\n".getBytes());assertTrue(failureInjected.await(3,TimeUnit.SECONDS));assertTrue(failureCleanup.await(3,TimeUnit.SECONDS),"failure path, not test close, must retire the handler");transport.awaitTermination();assertTrue(failed.get());assertEquals(0,transport.usedControlBytes());}}finally{failNow.countDown();transport.close();}
    }
}

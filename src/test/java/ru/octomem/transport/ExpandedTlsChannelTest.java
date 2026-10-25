package ru.octomem.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.*;
import static javax.net.ssl.SSLEngineResult.Status.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic SSL-state adapter faults; real SSLSocket integration is tested separately. */
@Timeout(10)
class ExpandedTlsChannelTest {
    static final class Pair implements AutoCloseable {
        final SocketChannel owner,peer;
        Pair()throws IOException{try(var listen=ServerSocketChannel.open()){listen.bind(new InetSocketAddress("127.0.0.1",0));peer=SocketChannel.open(listen.getLocalAddress());owner=listen.accept();owner.configureBlocking(false);peer.configureBlocking(false);}}
        public void close()throws IOException{try{owner.close();}finally{peer.close();}}
    }
    static final class Queue implements Executor {final ArrayDeque<Runnable> tasks=new ArrayDeque<>();public void execute(Runnable task){tasks.add(task);}void run(){tasks.remove().run();}}
    /** Scripted state machine honors consumed/produced positions and SSL result contracts. */
    static class Engine extends SSLEngine {
        HandshakeStatusHolder state=new HandshakeStatusHolder();
        SSLEngineResult.Status unwrapStatus=BUFFER_UNDERFLOW,wrapStatus=OK;
        Runnable delegated;boolean outbound,closed,inbound;boolean endless;int wraps,unwraps,emptyRecords,handshakeRecords;boolean postHandshakeTask,renegotiateOnWrap,completeOnUnwrap;int writeLimit=Integer.MAX_VALUE;byte[] plaintext;
        SSLSession session;
        Engine()throws Exception{session=SSLContext.getDefault().createSSLEngine().getSession();}
        public SSLEngineResult.HandshakeStatus getHandshakeStatus(){return state.value;}
        public void beginHandshake(){}
        public Runnable getDelegatedTask(){if(endless)return ()->{};Runnable next=delegated;delegated=null;return next;}
        public SSLEngineResult wrap(ByteBuffer[] sources,int offset,int length,ByteBuffer target){
            wraps++;int consumed=0,produced=0;
            if(renegotiateOnWrap&&sources[offset].hasRemaining()){renegotiateOnWrap=false;state.value=NEED_TASK;return new SSLEngineResult(OK,NEED_TASK,0,0);}
            if(wrapStatus==BUFFER_OVERFLOW)return new SSLEngineResult(BUFFER_OVERFLOW,state.value,0,0);
            if(closed){if(target.hasRemaining()){target.put((byte)21);produced=1;outbound=true;}return new SSLEngineResult(CLOSED,NOT_HANDSHAKING,0,produced);}
            if(state.value==NEED_WRAP){target.put((byte)22);produced=1;state.value=NOT_HANDSHAKING;return new SSLEngineResult(OK,FINISHED,0,produced);}
            for(int i=offset;i<offset+length;i++)while(sources[i].hasRemaining()&&target.hasRemaining()&&consumed<writeLimit){target.put(sources[i].get());consumed++;produced++;}
            return new SSLEngineResult(wrapStatus,state.value,consumed,produced);
        }
        public SSLEngineResult unwrap(ByteBuffer source,ByteBuffer[] targets,int offset,int length){
            if(postHandshakeTask){postHandshakeTask=false;state.value=NEED_TASK;return new SSLEngineResult(OK,NEED_TASK,0,0);}
            if(completeOnUnwrap){completeOnUnwrap=false;state.value=NEED_UNWRAP_AGAIN;return new SSLEngineResult(OK,NEED_UNWRAP_AGAIN,0,0);}
            if(handshakeRecords>0){unwraps++;if(!source.hasRemaining())return new SSLEngineResult(BUFFER_UNDERFLOW,NEED_UNWRAP,0,0);source.get();handshakeRecords--;if(handshakeRecords==0)state.value=NOT_HANDSHAKING;return new SSLEngineResult(OK,state.value==NOT_HANDSHAKING?FINISHED:NEED_UNWRAP,1,0);}
            unwraps++;if(state.value==NEED_UNWRAP_AGAIN){state.value=NOT_HANDSHAKING;int produced=0;if(plaintext!=null){targets[offset].put(plaintext);produced=plaintext.length;plaintext=null;}return new SSLEngineResult(OK,FINISHED,0,produced);}
            if(emptyRecords>0 && source.hasRemaining()){source.get();emptyRecords--;return new SSLEngineResult(OK,NOT_HANDSHAKING,1,0);}
            if(plaintext!=null){byte[] data=plaintext;plaintext=null;targets[offset].put(data);return new SSLEngineResult(OK,NOT_HANDSHAKING,0,data.length);}
            if(unwrapStatus==OK&&source.hasRemaining()){int n=source.remaining();while(source.hasRemaining())targets[offset].put(source.get());return new SSLEngineResult(OK,NOT_HANDSHAKING,n,n);}
            if(unwrapStatus==OK)return new SSLEngineResult(BUFFER_UNDERFLOW,state.value,0,0);
            return new SSLEngineResult(unwrapStatus,state.value,0,0);
        }
        public void closeInbound(){inbound=true;}public boolean isInboundDone(){return inbound;}
        public void closeOutbound(){closed=true;}public boolean isOutboundDone(){return outbound;}
        public SSLSession getSession(){return session;}public String[] getSupportedCipherSuites(){return new String[0];}public String[] getEnabledCipherSuites(){return new String[0];}public void setEnabledCipherSuites(String[] s){}
        public String[] getSupportedProtocols(){return new String[]{"TLSv1.2","TLSv1.3"};}public String[] getEnabledProtocols(){return getSupportedProtocols();}public void setEnabledProtocols(String[] p){}
        public void setUseClientMode(boolean b){}public boolean getUseClientMode(){return false;}public void setNeedClientAuth(boolean b){}public boolean getNeedClientAuth(){return false;}public void setWantClientAuth(boolean b){}public boolean getWantClientAuth(){return false;}public void setEnableSessionCreation(boolean b){}public boolean getEnableSessionCreation(){return true;}
    }
    static final class HandshakeStatusHolder{SSLEngineResult.HandshakeStatus value=NOT_HANDSHAKING;}
    @Test void delegatedWorkIsQueuedOnceOwnerSuspendsAndCompletionWakes()throws Exception{
        Engine e=new Engine();e.state.value=NEED_TASK;AtomicInteger performed=new AtomicInteger(),wakes=new AtomicInteger();
        e.delegated=()->{performed.incrementAndGet();e.state.value=NOT_HANDSHAKING;};Queue executor=new Queue();TlsChannel tls=new TlsChannel(e,executor,wakes::incrementAndGet);
        try(var p=new Pair()){assertFalse(tls.handshake(p.owner));assertEquals(0,performed.get());assertEquals(0,tls.interests());assertFalse(tls.runnable());assertFalse(tls.handshake(p.owner));assertEquals(1,executor.tasks.size());assertFalse(tls.finish(p.owner));
            executor.run();assertEquals(1,performed.get());assertEquals(1,wakes.get());assertTrue(tls.handshake(p.owner));assertTrue(tls.ready());assertEquals(java.nio.channels.SelectionKey.OP_READ,tls.interests());
        }finally{tls.close();}
    }
    @Test void closingWhileTaskQueuedCannotWakeOrReopenConnection()throws Exception{
        Engine e=new Engine();e.state.value=NEED_TASK;e.delegated=()->e.state.value=NOT_HANDSHAKING;Queue q=new Queue();AtomicInteger wake=new AtomicInteger();TlsChannel tls=new TlsChannel(e,q,wake::incrementAndGet);
        AtomicInteger released=new AtomicInteger();
        try(var p=new Pair()){assertFalse(tls.handshake(p.owner));tls.close(released::incrementAndGet);assertEquals(0,released.get(),"task retains charged buffers until completion");q.run();assertEquals(1,released.get());assertEquals(0,wake.get());assertTrue(e.closed);assertThrows(IOException.class,()->tls.handshake(p.owner));}
    }
    @Test void executorRejectionAndTaskFailureAreExplicitIoFailures()throws Exception{
        Engine rejected=new Engine();rejected.state.value=NEED_TASK;TlsChannel tls=new TlsChannel(rejected,r->{throw new RejectedExecutionException();},()->{});
        try(var p=new Pair()){assertThrows(IOException.class,()->tls.handshake(p.owner));}finally{tls.close();}
        for(boolean endless:new boolean[]{false,true}){Engine e=new Engine();e.state.value=NEED_TASK;e.endless=endless;e.delegated=()->{throw new IllegalStateException("synthetic TLS task");};Queue q=new Queue();TlsChannel failed=new TlsChannel(e,q,()->{});
            try(var p=new Pair()){assertFalse(failed.handshake(p.owner));q.run();assertTrue(failed.runnable());assertThrows(IOException.class,()->failed.handshake(p.owner));}finally{failed.close();}}
    }
    @Test void unwrapAgainDoesNotWaitForNewNetworkBytes()throws Exception{
        Engine e=new Engine();e.state.value=NEED_UNWRAP_AGAIN;TlsChannel tls=new TlsChannel(e,Runnable::run,()->{});
        try(var p=new Pair()){assertTrue(tls.runnable());assertTrue(tls.handshake(p.owner));assertEquals(1,e.unwraps);}finally{tls.close();}
    }
    @Test void underflowReturnsReadInterestAndEofFailsHandshake()throws Exception{
        Engine e=new Engine();e.state.value=NEED_UNWRAP;TlsChannel tls=new TlsChannel(e,Runnable::run,()->{});
        try(var p=new Pair()){assertFalse(tls.handshake(p.owner));assertEquals(java.nio.channels.SelectionKey.OP_READ,tls.interests());assertFalse(tls.pendingWrite());p.peer.close();assertThrows(IOException.class,()->tls.handshake(p.owner));}finally{tls.close();}
    }
    @Test void overflowAndClosedRecordsFailInsteadOfGrowingBuffers()throws Exception{
        for(var status:new SSLEngineResult.Status[]{BUFFER_OVERFLOW,CLOSED}){Engine e=new Engine();e.state.value=NEED_UNWRAP;e.unwrapStatus=status;TlsChannel tls=new TlsChannel(e,Runnable::run,()->{});
            try(var p=new Pair()){assertThrows(IOException.class,()->tls.handshake(p.owner));}finally{tls.close();}}
        Engine e=new Engine();e.state.value=NEED_WRAP;e.wrapStatus=BUFFER_OVERFLOW;TlsChannel tls=new TlsChannel(e,Runnable::run,()->{});
        try(var p=new Pair()){assertEquals(java.nio.channels.SelectionKey.OP_WRITE,tls.interests());assertThrows(IOException.class,()->tls.handshake(p.owner));}finally{tls.close();}
    }
    @Test void bufferedPlaintextRespectsTinyTargetAndFinalCiphertextDrains()throws Exception{
        Engine e=new Engine();e.plaintext=new byte[]{1,2,3};TlsChannel tls=new TlsChannel(e,Runnable::run,()->{});
        try(var p=new Pair()){
            ByteBuffer tiny=ByteBuffer.allocate(1);assertEquals(1,tls.read(p.owner,tiny));assertEquals(1,tiny.get(0));tiny.clear();assertEquals(1,tls.read(p.owner,tiny));assertEquals(2,tiny.get(0));
            ByteBuffer rest=ByteBuffer.allocate(2);assertEquals(1,tls.read(p.owner,rest));assertEquals(3,rest.get(0));
            ByteBuffer outgoing=ByteBuffer.wrap(new byte[]{7,8});assertEquals(2,tls.write(p.owner,outgoing));assertFalse(outgoing.hasRemaining());assertTrue(tls.finish(p.owner));assertTrue(tls.finish(p.owner));
            ByteBuffer network=ByteBuffer.allocate(3);for(int i=0;i<100&&network.hasRemaining();i++)p.peer.read(network);assertArrayEquals(new byte[]{7,8,21},network.array());
        }finally{tls.close();}
    }
    @Test void applicationEofAndCloseOverflowRemainDistinct()throws Exception{
        Engine e=new Engine();e.unwrapStatus=CLOSED;TlsChannel tls=new TlsChannel(e,Runnable::run,()->{});try(var p=new Pair()){assertEquals(-1,tls.read(p.owner,ByteBuffer.allocate(1)));}finally{tls.close();}
        Engine bad=new Engine();bad.wrapStatus=BUFFER_OVERFLOW;TlsChannel closing=new TlsChannel(bad,Runnable::run,()->{});try(var p=new Pair()){assertThrows(IOException.class,()->closing.finish(p.owner));}finally{closing.close();}
    }
    @Test void boundedUnwrapMustRescheduleBufferedRecordsWithoutNewReadiness()throws Exception{
        Engine e=new Engine();e.unwrapStatus=OK;e.emptyRecords=5;TlsChannel tls=new TlsChannel(e,Runnable::run,()->{});
        try(var p=new Pair()){
            p.peer.write(ByteBuffer.wrap(new byte[]{0,0,0,0,0,42}));ByteBuffer target=ByteBuffer.allocate(1);
            assertEquals(0,tls.read(p.owner,target));
            assertTrue(tls.runnable(),"buffered complete TLS records need owner rescheduling after fairness budget");
            assertEquals(1,tls.read(p.owner,target));assertEquals(42,target.get(0));
        }finally{tls.close();}
    }
    @Test void sessionBufferRequirementsAreRejectedBeforeHandshake()throws Exception {
        for(String oversized: new String[]{"getPacketBufferSize","getApplicationBufferSize"}) {
            Engine e=new Engine();SSLSession original=e.session;
            e.session=(SSLSession)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{SSLSession.class},(proxy,method,args)->method.getName().equals(oversized)?65537:method.invoke(original,args));
            assertThrows(IOException.class,()->new TlsChannel(e,Runnable::run,()->{}));
        }
    }
    @Test void writeWaitsForHandshakeAndReadReportsNetworkEof()throws Exception {
        Engine e=new Engine();e.state.value=NEED_TASK;e.delegated=()->e.state.value=NOT_HANDSHAKING;Queue q=new Queue();TlsChannel tls=new TlsChannel(e,q,()->{});
        try(var p=new Pair()){ByteBuffer source=ByteBuffer.wrap(new byte[]{7});assertEquals(0,tls.write(p.owner,source));assertEquals(0,source.position());q.run();assertEquals(1,tls.write(p.owner,source));
            p.peer.configureBlocking(true);ByteBuffer received=ByteBuffer.allocate(1);assertEquals(1,p.peer.read(received));assertEquals(7,received.get(0));p.peer.shutdownOutput();assertEquals(-1,tls.read(p.owner,ByteBuffer.allocate(1)));}finally{tls.close();}
    }
    @Test void actualSocketBackpressureRetainsCiphertextAndFinalClose()throws Exception {
        Engine e=new Engine();TlsChannel tls=new TlsChannel(e,Runnable::run,()->{});
        try(var p=new Pair()){
            p.owner.setOption(java.net.StandardSocketOptions.SO_SNDBUF,1024);p.peer.setOption(java.net.StandardSocketOptions.SO_RCVBUF,1024);
            boolean blocked=false;for(int i=0;i<256;i++){ByteBuffer data=ByteBuffer.allocate(65536);int accepted=tls.write(p.owner,data);if(tls.pendingWrite()){blocked=true;assertTrue(accepted>=0);break;}}
            assertTrue(blocked,"bounded loop should reach real socket backpressure");assertEquals(java.nio.channels.SelectionKey.OP_WRITE,tls.interests());
            assertFalse(tls.finish(p.owner));ByteBuffer drain=ByteBuffer.allocate(65536);boolean complete=false;
            try(var selector=java.nio.channels.Selector.open()){
                p.peer.register(selector,java.nio.channels.SelectionKey.OP_READ);p.owner.register(selector,java.nio.channels.SelectionKey.OP_WRITE);
                long deadline=System.nanoTime()+5_000_000_000L;
                while(System.nanoTime()<deadline){selector.select(100);selector.selectedKeys().clear();drain.clear();p.peer.read(drain);if(tls.finish(p.owner)){complete=true;break;}}
            }
            assertTrue(complete,"draining peer permits ciphertext and close_notify completion");
        }finally{tls.close();}
    }
    @Test void handshakeFairnessBudgetReschedulesCoalescedHandshakeRecords()throws Exception {
        Engine e=new Engine();e.state.value=NEED_UNWRAP;e.handshakeRecords=20;TlsChannel tls=new TlsChannel(e,Runnable::run,()->{});
        try(var p=new Pair()){p.peer.write(ByteBuffer.wrap(new byte[20]));assertFalse(tls.handshake(p.owner));assertTrue(tls.runnable());assertTrue(tls.handshake(p.owner));assertTrue(tls.ready());}finally{tls.close();}
    }
    @Test void postHandshakeTaskSuspendsReadsAndCompletionRestoresProgress()throws Exception {
        Engine e=new Engine();e.postHandshakeTask=true;e.delegated=()->e.state.value=NOT_HANDSHAKING;Queue q=new Queue();TlsChannel tls=new TlsChannel(e,q,()->{});
        try(var p=new Pair()){ByteBuffer target=ByteBuffer.allocate(1);assertEquals(0,tls.read(p.owner,target));assertEquals(0,tls.read(p.owner,target));assertEquals(1,q.tasks.size());q.run();e.plaintext=new byte[]{9};assertEquals(1,tls.read(p.owner,target));assertEquals(9,target.get(0));}finally{tls.close();}
    }
    @Test void handshakeWriteBackpressureDoesNotConsumeApplicationData()throws Exception {
        Engine e=new Engine();e.state.value=NEED_WRAP;AtomicInteger writes=new AtomicInteger();TransportOps ops=new TransportOps(){@Override int write(SocketChannel socket,ByteBuffer bytes)throws IOException{if(writes.incrementAndGet()<3)return 0;return super.write(socket,bytes);}};
        TlsChannel tls=new TlsChannel(e,Runnable::run,()->{},ops);
        try(var p=new Pair()){ByteBuffer source=ByteBuffer.wrap(new byte[]{1});assertEquals(0,tls.write(p.owner,source));assertEquals(0,source.position());assertTrue(tls.pendingWrite());assertFalse(tls.handshake(p.owner));assertTrue(tls.handshake(p.owner));assertEquals(1,tls.write(p.owner,source));}finally{tls.close();}
    }
    @Test void handshakeCompletionPlaintextReturnedWithoutAnotherSocketEvent()throws Exception{
        Engine e=new Engine();e.completeOnUnwrap=true;e.plaintext=new byte[]{4,5};TlsChannel tls=new TlsChannel(e,Runnable::run,()->{});
        try(var p=new Pair()){ByteBuffer one=ByteBuffer.allocate(1);assertEquals(1,tls.read(p.owner,one));assertEquals(4,one.get(0));assertTrue(tls.runnable(),"remaining plaintext must reschedule owner");one.clear();assertEquals(1,tls.read(p.owner,one));assertEquals(5,one.get(0));}finally{tls.close();}
    }
    @Test void taskReadinessAndPlaintextLeftByHandshakeAreRunnable()throws Exception{
        Engine e=new Engine();e.state.value=NEED_TASK;e.delegated=()->e.state.value=NOT_HANDSHAKING;Queue q=new Queue();TlsChannel tls=new TlsChannel(e,q,()->{});
        try(var p=new Pair()){assertTrue(tls.runnable());assertFalse(tls.handshake(p.owner));q.run();assertTrue(tls.handshake(p.owner));assertFalse(tls.runnable());}finally{tls.close();}
    }
    @Test void finishedWrapDoesNotStartAnotherHandshake()throws Exception{
        Engine e=new Engine(){@Override public SSLEngineResult wrap(ByteBuffer[] src,int off,int len,ByteBuffer dst){SSLEngineResult r=super.wrap(src,off,len,dst);return new SSLEngineResult(r.getStatus(),SSLEngineResult.HandshakeStatus.FINISHED,r.bytesConsumed(),r.bytesProduced());}};
        TlsChannel tls=new TlsChannel(e,Runnable::run,()->{});try(var p=new Pair()){ByteBuffer source=ByteBuffer.wrap(new byte[]{7});assertEquals(1,tls.write(p.owner,source));assertTrue(tls.ready());assertFalse(tls.runnable());}finally{tls.close();}
    }
}

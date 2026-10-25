package ru.octomem.transport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;

/** Owner-loop TLS1.2/1.3 record adapter with fixed, pre-admitted buffers.
 * No plaintext fallback or buffer growth; progress is bounded per invocation.
 */
final class TlsChannel {
    static final long CHARGE=262144;
    private static final int CAPACITY=65536;
    private static final ByteBuffer EMPTY=ByteBuffer.allocate(0).asReadOnlyBuffer();
    private final SSLEngine engine;
    private final ByteBuffer incoming=ByteBuffer.allocate(CAPACITY);
    private final ByteBuffer outgoing=ByteBuffer.allocate(CAPACITY);
    private final ByteBuffer plain=ByteBuffer.allocate(CAPACITY);
    private boolean handshake;
    private final java.util.concurrent.Executor executor;
    private final Runnable wakeup;
    private volatile boolean taskPending;
    private volatile Throwable taskFailure;
    private boolean closing;
    private volatile boolean closed;
    private boolean bufferedWork;
    private Runnable afterTask;
    private final TransportOps io;
    TlsChannel(SSLContext context,java.util.concurrent.Executor executor,Runnable wakeup)throws IOException{
        this(context,executor,wakeup,new TransportOps());
    }
    TlsChannel(SSLContext context,java.util.concurrent.Executor executor,Runnable wakeup,TransportOps io)throws IOException{
        this(context.createSSLEngine(),executor,wakeup,io);
    }
    /** Trusted package-local engine seam for deterministic protocol progress tests. */
    TlsChannel(SSLEngine engine,java.util.concurrent.Executor executor,Runnable wakeup)throws IOException{
        this(engine,executor,wakeup,new TransportOps());
    }
    TlsChannel(SSLEngine engine,java.util.concurrent.Executor executor,Runnable wakeup,TransportOps io)throws IOException{
        this.executor=executor;this.wakeup=wakeup;
        this.io=io;
        this.engine=java.util.Objects.requireNonNull(engine);engine.setUseClientMode(false);engine.setEnabledProtocols(new String[]{"TLSv1.3","TLSv1.2"});
        if(engine.getSession().getPacketBufferSize()>CAPACITY || engine.getSession().getApplicationBufferSize()>CAPACITY)throw new IOException("TLS buffer limit");
        outgoing.limit(0);plain.limit(0);engine.beginHandshake();
    }
    boolean pendingWrite(){return outgoing.hasRemaining() || engine.getHandshakeStatus()==SSLEngineResult.HandshakeStatus.NEED_WRAP;}
    boolean ready(){return handshake;}
    int interests(){return taskPending?0:pendingWrite()?java.nio.channels.SelectionKey.OP_WRITE:java.nio.channels.SelectionKey.OP_READ;}
    boolean runnable(){return !taskPending && (bufferedWork || plain.hasRemaining() || taskFailure!=null || engine.getHandshakeStatus()==SSLEngineResult.HandshakeStatus.NEED_TASK
            || engine.getHandshakeStatus()==SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN);}
    boolean flush(SocketChannel socket)throws IOException{if(outgoing.hasRemaining())io.write(socket,outgoing);return !outgoing.hasRemaining();}
    boolean handshake(SocketChannel socket)throws IOException{
        if(closed)throw new IOException("TLS closed");
        if(taskPending)return false;
        if(taskFailure!=null)throw new IOException("TLS task failed");
        bufferedWork=false;
        for(int i=0;i<16;i++){
            if(!flush(socket))return false;
            switch(engine.getHandshakeStatus()){
                case NOT_HANDSHAKING,FINISHED->{handshake=true;return true;}
                case NEED_TASK->{
                    taskPending=true;
                    try{executor.execute(()->{
                        try{Runnable task;int tasks=0;while(!closed && (task=engine.getDelegatedTask())!=null){if(++tasks>16)throw new IllegalStateException("TLS task limit");task.run();}}
                        catch(Throwable failure){taskFailure=failure;}
                        finally{
                            Runnable done;
                            synchronized(this){taskPending=false;done=afterTask;afterTask=null;if(closed)engine.closeOutbound();else wakeup.run();}
                            if(done!=null)done.run();
                        }
                    });}catch(java.util.concurrent.RejectedExecutionException rejected){taskPending=false;throw new IOException("TLS busy");}
                    return false;
                }
                case NEED_WRAP->{outgoing.clear();check(engine.wrap(EMPTY.duplicate(),outgoing));outgoing.flip();}
                case NEED_UNWRAP,NEED_UNWRAP_AGAIN->{
                    incoming.flip();plain.compact();SSLEngineResult r=engine.unwrap(incoming,plain);incoming.compact();plain.flip();
                    check(r);if(r.getStatus()==SSLEngineResult.Status.BUFFER_UNDERFLOW){int n=io.read(socket,incoming);if(n<0)throw new IOException("TLS EOF");if(n==0)return false;}
                }
            }
        }
        bufferedWork=incoming.position()>0;return false;
    }
    int read(SocketChannel socket,ByteBuffer target)throws IOException{
        if(!handshake(socket))return 0;
        if(plain.hasRemaining())return transfer(plain,target);
        for(int i=0;i<4;i++){
            incoming.flip();plain.clear();SSLEngineResult r=engine.unwrap(incoming,plain);incoming.compact();plain.flip();
            if(r.getStatus()==SSLEngineResult.Status.CLOSED)return -1;
            check(r);if(plain.hasRemaining())return transfer(plain,target);
            if(r.getHandshakeStatus()!=SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING){handshake=false;handshake(socket);return plain.hasRemaining()?transfer(plain,target):0;}
            if(r.getStatus()==SSLEngineResult.Status.BUFFER_UNDERFLOW){bufferedWork=false;int n=io.read(socket,incoming);if(n<=0)return n;}
        }
        bufferedWork=incoming.position()>0;
        return 0;
    }
    int write(SocketChannel socket,ByteBuffer source)throws IOException{
        if(!handshake(socket) || !flush(socket))return 0;
        outgoing.clear();SSLEngineResult r=engine.wrap(source,outgoing);outgoing.flip();check(r);flush(socket);
        if(r.getHandshakeStatus()!=SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING && r.getHandshakeStatus()!=SSLEngineResult.HandshakeStatus.FINISHED){handshake=false;handshake(socket);}
        return r.bytesConsumed();
    }
    private static int transfer(ByteBuffer from,ByteBuffer to){int n=Math.min(from.remaining(),to.remaining()),limit=from.limit();from.limit(from.position()+n);to.put(from);from.limit(limit);return n;}
    private static void check(SSLEngineResult r)throws IOException{
        if(r.getStatus()==SSLEngineResult.Status.BUFFER_OVERFLOW || r.getStatus()==SSLEngineResult.Status.CLOSED)throw new IOException("TLS record rejected");
    }
    boolean finish(SocketChannel socket)throws IOException{
        if(taskPending)return false;
        if(!flush(socket))return false;
        if(!closing){closing=true;engine.closeOutbound();}
        if(!engine.isOutboundDone()){
            outgoing.clear();SSLEngineResult r=engine.wrap(EMPTY.duplicate(),outgoing);outgoing.flip();
            if(r.getStatus()==SSLEngineResult.Status.BUFFER_OVERFLOW)throw new IOException("TLS close overflow");
        }
        return flush(socket) && engine.isOutboundDone();
    }
    void close(){close(()->{});}
    /** Releases retained admission only after an outstanding delegated task has settled. */
    synchronized void close(Runnable release){
        closed=true;
        if(taskPending)afterTask=release;else{engine.closeOutbound();release.run();}
        java.util.Arrays.fill(incoming.array(),(byte)0);java.util.Arrays.fill(outgoing.array(),(byte)0);java.util.Arrays.fill(plain.array(),(byte)0);
    }
}

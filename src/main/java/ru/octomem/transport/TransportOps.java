package ru.octomem.transport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

/** Trusted package-private JDK operation seams for deterministic lifecycle tests. */
class TransportOps {
    Selector openSelector() throws IOException { return Selector.open(); }
    void startThread(Thread thread) { thread.start(); }
    void beforeHandoff(SocketChannel socket) throws IOException { }
    int read(SocketChannel socket, ByteBuffer buffer) throws IOException { return socket.read(buffer); }
    int write(SocketChannel socket, ByteBuffer buffer) throws IOException { return socket.write(buffer); }
    /** Trusted factory seam keeps production TLS real while allowing deterministic progress tests. */
    TlsChannel tls(javax.net.ssl.SSLContext context,java.util.concurrent.Executor executor,Runnable wakeup)throws IOException{
        return new TlsChannel(context,executor,wakeup,this);
    }
    /** Zero selects without blocking; positive values are bounded milliseconds. */
    int select(Selector selector, long timeoutMillis) throws IOException {
        return timeoutMillis == 0 ? selector.selectNow() : selector.select(timeoutMillis);
    }
    void join(Thread thread, long millis) throws InterruptedException { thread.join(millis); }
}

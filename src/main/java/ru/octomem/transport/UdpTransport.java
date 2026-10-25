package ru.octomem.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import ru.octomem.protocol.*;

/** Optional loopback-only single-datagram request transport. One bounded worker,
 * no per-peer state; replies are buffered to a fixed 1MiB cap before fragmentation
 * so an output-limit error never follows a partial successful response.
 */
public final class UdpTransport implements AutoCloseable {
    public static final int RESPONSE_LIMIT=1_048_576;
    private static final int PAYLOAD=1392;
    private final InetSocketAddress address;
    private final ConnectionFactory factory;
    private final int itemMax;
    private DatagramChannel channel;
    private Selector selector;
    private Thread worker;
    private volatile boolean closed;
    private final Runnable failure;
    private final java.util.function.Supplier<ConnectionResources> resourcesFactory;
    private final SharedBudget fixedBudget;
    private final long fixedCharge;
    private final Ops ops;
    private boolean started;
    private boolean admitted;
    /** Package-local I/O seams for deterministic failure/cleanup tests. */
    static class Ops {
        void start(Thread worker){worker.start();}
        SocketAddress receive(DatagramChannel channel,ByteBuffer target)throws IOException{return channel.receive(target);}
        int send(DatagramChannel channel,ByteBuffer bytes,SocketAddress peer)throws IOException{return channel.send(bytes,peer);}
    }
    public UdpTransport(InetSocketAddress address,ConnectionFactory factory,int itemMax){
        this(address,factory,itemMax,()->{});
    }
    public UdpTransport(InetSocketAddress address,ConnectionFactory factory,int itemMax,Runnable failure){
        this(address,factory,itemMax,failure,()->new ConnectionResources(new AtomicLong(),(long)itemMax+32,itemMax),new Ops());
    }
    UdpTransport(InetSocketAddress address,ConnectionFactory factory,int itemMax,Runnable failure,
            java.util.function.Supplier<ConnectionResources> resourcesFactory,Ops ops){
        if(!address.getAddress().isLoopbackAddress())throw new IllegalArgumentException("UDP requires loopback");
        this.address=address;this.factory=factory;this.itemMax=itemMax;
        this.failure=failure;
        this.resourcesFactory=resourcesFactory;this.ops=ops;
        fixedCharge=RESPONSE_LIMIT+65536L+1400+262144+10816+factory.additionalControlBytes();
        fixedBudget=new SharedBudget(fixedCharge,0);
    }
    public void start()throws IOException{
        boolean ownsStartup=false;
        try{synchronized(this){
            if(started || closed)throw new IllegalStateException("UDP transport cannot restart");
            started=true;ownsStartup=true;
            // Private initially-empty ledger, exact limit, single guarded start.
            fixedBudget.reserve(fixedCharge);admitted=true;
            selector=Selector.open();channel=DatagramChannel.open();channel.configureBlocking(false);channel.bind(address);
            channel.register(selector,SelectionKey.OP_READ);worker=new Thread(this::run,"octomem-udp");worker.setDaemon(true);ops.start(worker);
        }}catch(IOException|RuntimeException|Error failure){if(ownsStartup)close();throw failure;}
    }
    private void run(){
        try{
            ByteBuffer input=ByteBuffer.allocate(65536),output=ByteBuffer.allocate(RESPONSE_LIMIT),packet=ByteBuffer.allocate(1400);
            while(!closed){
                selector.select(100);selector.selectedKeys().clear();
                for(int turn=0;turn<32 && !closed;turn++){
                    input.clear();SocketAddress peer=ops.receive(channel,input);if(peer==null)break;input.flip();
                    if(input.remaining()<8)continue;
                    short id=input.getShort();int sequence=input.getShort()&65535,total=input.getShort()&65535;input.getShort();output.clear();
                    if(total!=1)continue;
                    execute(input,output);
                    output.flip();int count=Math.max(1,(output.remaining()+PAYLOAD-1)/PAYLOAD);
                    if(!output.hasRemaining())continue;
                    for(int index=0;index<count && !closed;index++){
                        packet.clear();packet.putShort(id).putShort((short)index).putShort((short)count).putShort((short)0);
                        int n=Math.min(PAYLOAD,output.remaining()),limit=output.limit();output.limit(output.position()+n);packet.put(output);output.limit(limit);packet.flip();
                        if(ops.send(channel,packet,peer)==0)break; // UDP has no reliable delivery/retry contract.
                    }
                }
            }
        }catch(IOException|RuntimeException|Error unexpected){if(!closed)failure.run();}
        finally{try{channel.close();}catch(IOException ignored){}try{selector.close();}catch(IOException ignored){}releaseFixed();}
    }
    private void execute(ByteBuffer input,ByteBuffer output){
        if(!completeDatagram(input))return;
        boolean binary=input.hasRemaining() && (input.get(input.position())&255)==128;
        int opcode=binary?input.get(input.position()+1)&255:0,opaque=binary?input.getInt(input.position()+12):0;
        ConnectionResources resources=resourcesFactory.get();
        try(resources){
            if(!resources.acquireLine())throw new IllegalStateException("UDP line admission denied");
            try(ConnectionHandler handler=factory.create(resources)){
                ProtocolDecoder decoder=handler.decoder();
                for(int steps=0;steps<65536;steps++){
                    Command command;
                    try{command=decoder.nextCommand();}
                    catch(ProtocolException failure){Response error=handler.protocolError(failure);if(error!=null)write(handler,error,output);break;}
                    if(command!=null){
                        if(command.type()==CommandType.QUIT)break;
                        Response result=handler.handle(command);if(result!=null)write(handler,result,output);
                        if(handler.closeAfterResponse())break;
                        continue;
                    }
                    if(!input.hasRemaining())break;
                    int before=input.position();decoder.append(input);if(input.position()==before)break;
                }
            }
        }catch(Exception failure){output.clear();output.put(binary?ExtendedResponse.binaryHeader(opcode,opaque,130,0,new byte[0],new byte[0],"UDP response limit or invalid request".getBytes(StandardCharsets.US_ASCII),0):
                "SERVER_ERROR UDP response limit or invalid request\r\n".getBytes(StandardCharsets.US_ASCII));}
    }
    private static void write(ConnectionHandler handler,Response response,ByteBuffer output){
        ResponseWriter writer=null;
        try{writer=handler.encoder().encode(response);
            ChunkSink sink=new ChunkSink(){public int remainingCapacity(){return Math.min(512,output.remaining());}
                public void accept(ByteBuffer chunk){output.put(chunk);}};
            for(;;){int before=output.position();if(writer.writeMore(sink)==WriteStatus.COMPLETE)return;
                if(output.position()==before)throw new IllegalStateException("UDP response limit");}
        }finally{try{if(writer!=null)writer.close();}finally{response.close();}}
    }
    /** Checks framing for the complete packet before any shared-cache admission. */
    private static boolean completeDatagram(ByteBuffer source){
        ByteBuffer bytes=source.duplicate();
        boolean binary=bytes.hasRemaining() && (bytes.get(bytes.position())&255)==128;
        while(bytes.hasRemaining()){
            if(binary){
                if(bytes.remaining()<24 || (bytes.get(bytes.position())&255)!=128)return false;
                long body=Integer.toUnsignedLong(bytes.getInt(bytes.position()+8));
                if(body>bytes.remaining()-24)return false;bytes.position(bytes.position()+24+(int)body);continue;
            }
            int start=bytes.position(),end=start;
            while(bytes.hasRemaining()){if(bytes.get()=='\n'){end=bytes.position();break;}}
            if(end==start)return false;
            int length=end-start;byte[] line=new byte[length];ByteBuffer copy=bytes.duplicate();copy.position(start);copy.get(line);
            String header=new String(line,StandardCharsets.US_ASCII).strip();
            String[] fields=header.split(" +",7);String op=fields[0];
            int sizeToken=switch(op){case "set","add","replace","append","prepend","cas"->4;case "ms"->2;default->-1;};
            if(sizeToken>=0){
                if(fields.length<=sizeToken)return false;
                long size;try{size=op.equals("ms") && fields[sizeToken].equals("-")?0:Long.parseLong(fields[sizeToken]);}catch(NumberFormatException invalid){return false;}
                if(size<0 || size>bytes.remaining()-2)return false;
                bytes.position(bytes.position()+(int)size);if(bytes.get()!='\r'||bytes.get()!='\n')return false;
            }
        }
        return true;
    }
    @Override public void close(){
        Thread owned;
        synchronized(this){
            closed=true;owned=worker;
            try{if(channel!=null)channel.close();}catch(IOException ignored){}
            if(selector!=null)selector.wakeup();
            if(owned==null || owned.getState()==Thread.State.NEW)releaseFixed();
        }
        boolean interrupted=false;
        if(owned!=null && Thread.currentThread()!=owned)for(;;){try{owned.join();break;}catch(InterruptedException e){interrupted=true;}}
        try{if(selector!=null)selector.close();}catch(IOException ignored){}
        if(interrupted)Thread.currentThread().interrupt();
    }
    private synchronized void releaseFixed(){if(admitted){admitted=false;fixedBudget.release(fixedCharge);}}
    /** Fixed worker graph reservation, independent of native cache and shared ingress. */
    public long usedControlBytes(){return fixedBudget.usedBytes();}
}

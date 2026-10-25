package ru.octomem.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import ru.octomem.engine.ExtendedResult;
import ru.octomem.engine.RetrievalSnapshot;

/** Streaming binary/meta response with an owned lease and bounded header.
 * Value bytes are copied in at most 256-byte slices, never materialized as a response.
 */
public final class ExtendedResponse implements Response,ResponseWriter {
    private final ExtendedResult result;
    private final Runnable release;
    private final int chunkLimit;
    private ByteBuffer header;
    private final ByteBuffer scratch=ByteBuffer.allocate(256);
    private final boolean value;
    private final boolean text;
    private int offset;
    private boolean closed;
    private Response stats;
    private int statsIndex;
    private int opcode,opaque;
    private boolean statsEnded;

    public ExtendedResponse(byte[] header,ExtendedResult result,boolean value,boolean text,int chunkLimit,Runnable release) {
        if(value)java.util.Objects.requireNonNull(result,"value result");
        if(chunkLimit<2)throw new IllegalArgumentException("Chunk limit below CRLF");
        if(text)for(int i=0;i<header.length;i++){
            if(header[i]=='\r'){
                if(++i==header.length || header[i]!='\n')throw new IllegalArgumentException("Invalid text prefix");
            }else if(header[i]=='\n')throw new IllegalArgumentException("Invalid text prefix");
        }
        this.header=ByteBuffer.wrap(header);this.result=result;this.value=value;this.text=text;
        this.chunkLimit=chunkLimit;this.release=java.util.Objects.requireNonNull(release);
    }
    /** Creates a streamed sequence of binary STAT packets plus an empty terminator. */
    public static ExtendedResponse statistics(Response source,int opcode,int opaque,int chunkLimit,Runnable release) {
        ExtendedResponse response=new ExtendedResponse(new byte[0],null,false,false,chunkLimit,release);
        response.stats=source;response.opcode=opcode;response.opaque=opaque;return response;
    }
    /** Encodes a fixed binary header, extras and optional key/error bytes. */
    public static byte[] binaryHeader(int opcode,int opaque,int status,long cas,byte[] extras,byte[] key,byte[] body,int valueBytes) {
        int prefix=extras.length+key.length+body.length;
        ByteBuffer out=ByteBuffer.allocate(24+prefix).order(ByteOrder.BIG_ENDIAN);
        out.put((byte)129).put((byte)opcode).putShort((short)key.length).put((byte)extras.length).put((byte)0)
                .putShort((short)status).putInt(prefix+valueBytes).putInt(opaque).putLong(cas);
        out.put(extras).put(key).put(body);return out.array();
    }
    @Override public WriteStatus writeMore(ChunkSink sink) {
        if(closed) throw new IllegalStateException("Closed response");
        int budget=Math.min(chunkLimit,sink.remainingCapacity());
        while(budget>0) {
            if(header.hasRemaining()) {
                int n=Math.min(budget,header.remaining());
                if(text && header.get(header.position())=='\r' && n<2)return WriteStatus.IN_PROGRESS;
                if(text && n<header.remaining() && header.get(header.position()+n-1)=='\r')n--;
                int limit=header.limit();header.limit(header.position()+n);
                try {sink.accept(header);}finally {header.limit(limit);}budget-=n;continue;
            }
            if(stats!=null && !statsEnded) {
                List<String> lines=stats.statsLines();
                if(statsIndex<lines.size()) {
                    String line=lines.get(statsIndex++);int split=line.indexOf(' ',5);
                    byte[] key=line.substring(5,split).getBytes(StandardCharsets.US_ASCII);
                    byte[] val=line.substring(split+1).getBytes(StandardCharsets.US_ASCII);
                    header=ByteBuffer.wrap(binaryHeader(opcode,opaque,0,0,new byte[0],key,val,0));
                } else {statsEnded=true;header=ByteBuffer.wrap(binaryHeader(opcode,opaque,0,0,new byte[0],new byte[0],new byte[0],0));}
                continue;
            }
            if(value && offset<result.size) {
                int n=Math.min(budget,Math.min(scratch.capacity(),result.size-offset));
                scratch.clear().limit(n);int read=result.item().readValue(offset,scratch);
                if(read!=n) throw new IllegalStateException("Short value read");
                scratch.flip();sink.accept(scratch);offset+=n;budget-=n;continue;
            }
            if(value && text && offset==result.size) {
                offset++;header=ByteBuffer.wrap(new byte[]{'\r','\n'});continue;
            }
            close();return WriteStatus.COMPLETE;
        }
        // Completion itself consumes no sink capacity. A datagram response may
        // exactly fill its admitted output buffer and still finish successfully.
        if(!header.hasRemaining() && (stats==null || statsEnded)
                && (!value || offset>=result.size && (!text || offset>result.size))){close();return WriteStatus.COMPLETE;}
        return WriteStatus.IN_PROGRESS;
    }
    @Override public void close() {
        if(closed)return;closed=true;
        try {if(result!=null)result.close();}finally {try {if(stats!=null)stats.close();}finally {release.run();}}
    }
    @Override public ResponseKind kind(){return ResponseKind.OK;}
    @Override public RetrievalSnapshot snapshot(){return null;}
    @Override public boolean includeCas(){return false;}
    @Override public long number(){return 0;}
    @Override public String text(){return null;}
    @Override public List<String> statsLines(){return null;}
}

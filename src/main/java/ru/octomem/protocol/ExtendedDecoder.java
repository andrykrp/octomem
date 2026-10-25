package ru.octomem.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.function.Consumer;
import java.util.function.ToIntBiFunction;
import java.util.function.BooleanSupplier;
import ru.octomem.engine.ExtendedRequest;

/** Bounded binary/meta decoder. Payload is admitted before allocation and retained
 * until response release. Header, extras/key and ASCII line are never payload-sized.
 */
public final class ExtendedDecoder implements ProtocolDecoder,AutoCloseable {
    private final boolean binary;
    private final int maxLine,itemMax;
    private final ProtocolBudget budget;
    private final Consumer<byte[]> rejectSet;
    private final ToIntBiFunction<ExtendedRequest,Integer> admission;
    private final BooleanSupplier authenticated;
    private byte[] header;
    private byte[] prefix;
    private int headerUsed,prefixUsed,bodyUsed,terminator;
    private long discard;
    private ExtendedCommand command;
    private boolean ready,returned,closed;
    public ExtendedDecoder(boolean binary,int maxLine,int itemMax,ProtocolBudget budget,Consumer<byte[]> rejectSet,
            ToIntBiFunction<ExtendedRequest,Integer> admission) {
        this(binary,maxLine,itemMax,budget,rejectSet,admission,()->true);
    }
    public ExtendedDecoder(boolean binary,int maxLine,int itemMax,ProtocolBudget budget,Consumer<byte[]> rejectSet,
            ToIntBiFunction<ExtendedRequest,Integer> admission,BooleanSupplier authenticated){
        this.binary=binary; this.maxLine=maxLine; this.itemMax=itemMax; this.budget=budget;
        this.rejectSet=rejectSet;
        this.admission=admission;
        this.authenticated=authenticated;
    }
    @Override public void append(ByteBuffer input) {
        if(closed || ready) return;
        if(command==null) {
            if(header==null)header=new byte[binary?24:Math.min(maxLine,128)];
            while(input.hasRemaining()) {
                byte b=input.get();
                if(!binary && b=='\n') { parseMeta(); break; }
                if(headerUsed==header.length) {
                    if(headerUsed==maxLine){failFrame("bad command line format");return;}
                    header=Arrays.copyOf(header,(int)Math.min(maxLine,2L*header.length));
                }
                header[headerUsed++]=b;
                if(binary && headerUsed==24) { parseBinaryHeader(); break; }
            }
            if(command==null || ready) return;
        }
        if(binary && prefixUsed<prefix.length) {
            int n=Math.min(input.remaining(),prefix.length-prefixUsed);
            input.get(prefix,prefixUsed,n); prefixUsed+=n;
            if(prefixUsed<prefix.length) return;
            parseBinaryPrefix();
        }
        if(discard>0) {
            int n=(int)Math.min(input.remaining(),discard); input.position(input.position()+n); discard-=n;
            if(discard>0) return;
        } else if(command.payload!=null && bodyUsed<command.payload.length) {
            int n=Math.min(input.remaining(),command.payload.length-bodyUsed);
            input.get(command.payload,bodyUsed,n); bodyUsed+=n;
            if(bodyUsed<command.payload.length) return;
        }
        if(!binary && "ms".equals(command.meta)) {
            while(input.hasRemaining() && terminator<2) {
                if(input.get()!=(terminator==0?'\r':'\n')) { command.error=4; command.errorText="bad data chunk"; command.close=true; ready=true; return; }
                terminator++;
            }
            if(terminator<2) return;
        }
        ready=true;
    }
    @Override public Command nextCommand() {
        if(ready && !returned) { returned=true; command.request.value=command.payload; return command; }
        return null;
    }
    /** Releases the exact frame after response completion or cancellation. */
    public void release(ExtendedCommand frame) {
        if(frame!=command) throw new IllegalArgumentException("Foreign frame");
        try{command.request.close();}finally{if(command.bodyReserved>=0) budget.releaseBody(command.bodyReserved);}
        command=null; prefix=null;header=null; headerUsed=prefixUsed=bodyUsed=terminator=0;
        discard=0; ready=returned=false;
    }
    @Override public void close() { if(command!=null) release(command); closed=true; }
    private void failFrame(String text) {
        if(command==null) command=new ExtendedCommand();
        command.binary=binary; command.error=4; command.errorText=text; command.close=true; ready=true;
    }
    private void parseBinaryHeader() {
        command=new ExtendedCommand(); command.binary=true;
        command.request.binaryProtocol=true;
        ByteBuffer h=ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        int magic=h.get()&255; command.opcode=h.get()&255;
        int key=h.getShort()&65535, extras=h.get()&255, datatype=h.get()&255;
        h.getShort(); long total=Integer.toUnsignedLong(h.getInt()); command.opaque=h.getInt();
        command.request.expectedCas=h.getLong(); command.request.compareCas=command.request.expectedCas!=0;
        if(!authenticated.getAsBoolean() && command.opcode!=11 && (command.opcode<32 || command.opcode>34)){
            command.error=32;command.errorText="Auth failure.";command.close=true;ready=true;return;
        }
        if(total<key+extras) {failFrame("Unknown command");command.error=129;return;}
        if(magic!=128 || datatype!=0 || key>250 || extras>20 || total>Integer.MAX_VALUE) {
            failFrame("Invalid arguments"); return;
        }
        prefix=new byte[key+extras]; command.bodyLength=(int)total-key-extras;
        if(prefix.length==0) parseBinaryPrefix();
    }
    private void parseBinaryPrefix() {
        int extras=header[4]&255;
        command.request.key=Arrays.copyOfRange(prefix,extras,prefix.length);
        int op=command.opcode;
        command.quiet=op==9 || op==13 || op>=17 && op<=26 || op==30 || op==36;
        int base=switch(op) { case 9->0;case 13->12;case 17->1;case 18->2;case 19->3;case 20->4;
            case 21->5;case 22->6;case 23->7;case 24->8;case 25->14;case 26->15;case 30->29;case 36->35;default->op; };
        ExtendedRequest r=command.request; int expected=0; boolean keyRequired=true,valueAllowed=false;
        switch(base) {
            case 0,12 -> { r.operation=ExtendedRequest.Operation.GET; r.compareCas=false; }
            case 1,2,3 -> { expected=8; valueAllowed=true; r.operation=base==1?ExtendedRequest.Operation.SET:base==2?ExtendedRequest.Operation.ADD:ExtendedRequest.Operation.REPLACE; }
            case 4 -> r.operation=ExtendedRequest.Operation.DELETE;
            case 5,6 -> { expected=20; r.operation=base==5?ExtendedRequest.Operation.INCR:ExtendedRequest.Operation.DECR; }
            case 7,8,10,11,16 -> { keyRequired=false; expected=base==8?extras:0; }
            case 14,15 -> { valueAllowed=true; r.operation=base==14?ExtendedRequest.Operation.APPEND:ExtendedRequest.Operation.PREPEND; }
            case 28,29,35 -> { expected=4;r.operation=ExtendedRequest.Operation.GET;r.hasTtl=true;r.compareCas=false; }
            case 32 -> keyRequired=false;
            case 33,34 -> valueAllowed=true;
            default -> { command.error=129;command.errorText="Unknown command";keyRequired=false;expected=extras; }
        }
        if(command.error==0 && (extras!=expected || keyRequired && r.key.length==0
                || !keyRequired && base!=16 && r.key.length!=0 || !valueAllowed && command.bodyLength!=0
                || base==8 && extras!=0 && extras!=4)) { command.error=4;command.errorText="Invalid arguments";command.close=true; }
        if(command.error==0) {
            ByteBuffer e=ByteBuffer.wrap(prefix).order(ByteOrder.BIG_ENDIAN);
            if(base==1 || base==2 || base==3) { r.flags=e.getInt();r.ttl=Integer.toUnsignedLong(e.getInt());r.hasTtl=true; }
            else if(base==5 || base==6) { r.delta=e.getLong();r.initial=e.getLong();long ttl=Integer.toUnsignedLong(e.getInt());r.create=ttl!=0xffffffffL;r.createTtl=ttl; }
            else if(expected==4) r.ttl=Integer.toUnsignedLong(e.getInt());
            if(base==16) command.stats=new String(r.key,StandardCharsets.US_ASCII);
        }
        admitBody();
        if(command.bodyLength==0) ready=true;
    }
    private void admitBody() {
        if(binary && command.opcode>=32 && command.opcode<=34 && command.bodyLength>1280){command.error=32;command.errorText="Auth failure";command.close=true;ready=true;return;}
        if(command.error==0 && ((32L+command.request.key.length+command.bodyLength+7)&~7L)>itemMax
                && storageOperation()) {
            command.error=3;command.errorText=binary?"Too large.":"object too large for cache";
            if(command.request.operation==ExtendedRequest.Operation.SET || command.request.metaStore)rejectSet.accept(command.request.key);
        }
        if(command.error==0 && command.bodyLength==0 && !storageOperation()){command.payload=new byte[0];return;}
        if(command.error==0) {
            if(budget.tryReserveBody(command.bodyLength)) {
                command.bodyReserved=command.bodyLength;
                int status=storageOperation()?admission.applyAsInt(command.request,command.bodyLength):0;
                if(status!=0){command.error=status;command.errorText=status==3?(binary?"Too large.":"object too large for cache"):
                    binary?"Out of memory allocating item":"out of memory storing object";
                    budget.releaseBody(command.bodyLength);command.bodyReserved=-1;
                }else command.payload=new byte[command.bodyLength];
            } else { command.error=130;command.errorText="out of memory storing object"; }
        }
        if(command.payload==null) discard=command.bodyLength;
    }
    private boolean storageOperation(){return command.request.operation!=null && switch(command.request.operation){
        case SET,ADD,REPLACE,APPEND,PREPEND->true;default->false;};}
    private void parseMeta() {
        command=new ExtendedCommand();
        int end=headerUsed;
        if(end>0 && header[end-1]=='\r') end--;
        String[] tokens=new String[32]; int count=0,start=0;
        while(start<end) {
            while(start<end && header[start]==' ') start++;
            if(start==end) break;
            int stop=start; while(stop<end && header[stop]!=' ') stop++;
            if(count==tokens.length || stop-start>340) { failFrame("bad command line format");return; }
            for(int i=start;i<stop;i++) if(header[i]<33 || header[i]>126) { failFrame("bad command line format");return; }
            tokens[count++]=new String(header,start,stop-start,StandardCharsets.US_ASCII); start=stop;
        }
        if(count==0) { failFrame("bad command line format");return; }
        command.meta=tokens[0];
        if("mn".equals(command.meta)) { if(count!=1) failFrame("bad command line format");else ready=true;return; }
        if(count<2) { failFrame("bad command line format");return; }
        boolean store="ms".equals(command.meta);
        int first=store?3:2;
        if(store && count<3) { failFrame("bad command line format");return; }
        ExtendedRequest r=command.request;
        r.operation=switch(command.meta) {case "mg"->ExtendedRequest.Operation.GET;case "ms"->ExtendedRequest.Operation.SET;
            case "md"->ExtendedRequest.Operation.DELETE;case "ma"->ExtendedRequest.Operation.INCR;
            case "me"->ExtendedRequest.Operation.DEBUG;default->null;};
        if(r.operation==null) { failFrame("bad command line format");return; }
        r.claimRecache="mg".equals(command.meta);
        r.metaStore=store;
        command.options=Arrays.copyOfRange(tokens,first,count);
        r.orderedFlags=command.options;
        try {
            command.bodyLength=store?(int)signedToken(tokens[2]):0;
            if(command.bodyLength<0 || command.bodyLength>Integer.MAX_VALUE-2) throw new IllegalArgumentException();
            boolean[] seen=new boolean[128];
            for(String flag:command.options) {
                char c=flag.charAt(0); String value=flag.substring(1);
                if(seen[c]) { command.errorText="duplicate flag";throw new IllegalArgumentException(); } seen[c]=true;
                try {switch(c) {
                    case 'b' -> r.binaryKey=true;
                    case 'q' -> command.quiet=true;
                    case 'T' -> {r.ttl=signedToken(value);r.hasTtl=true;r.createTtlLast=false;}
                    case 'N' -> {r.create=true;r.createTtl=signedToken(value);r.createTtlLast=true;}
                    case 'R' -> {r.recache=true;r.recacheTtl=signedToken(value);}
                    case 'C' -> {r.compareCas=true;r.expectedCas=unsignedToken(value,22);}
                    case 'E' -> {r.overrideCas=true;r.newCas=unsignedToken(value,22);}
                    case 'F' -> {long f=unsignedToken(value,11);if(Long.compareUnsigned(f,0xffffffffL)>0) throw new NumberFormatException();r.flags=(int)f;}
                    case 'D' -> r.delta=unsignedToken(value,22);
                    case 'J' -> r.initial=unsignedToken(value,22);
                    case 'I' -> r.invalidate=true;
                    case 'x' -> r.removeValue=true;
                    case 'u' -> r.noUpdate=true;
                    case 'M' -> r.operation=mode(command.meta,value);
                    case 'O' -> {if(value.length()>32) {command.errorText="opaque token too long";throw new IllegalArgumentException();}}
                    case 't' -> r.ttlBeforeUpdate=!r.hasTtl;
                    case 'c','f','h','k','l','s','v','P','L' -> { }
                    default -> {command.errorText="bad command line format";throw new IllegalArgumentException();}
                }}catch(NumberFormatException invalid){
                    command.errorText="ma".equals(command.meta)?"invalid or duplicate flag":"bad token in command line format";throw invalid;
                }
            }
            if(r.binaryKey) {
                try{r.key=Base64.getDecoder().decode(tokens[1]);}
                catch(IllegalArgumentException invalid){command.errorText="error decoding key";throw invalid;}
            }else r.key=tokens[1].getBytes(StandardCharsets.US_ASCII);
            if(r.key.length<1 || r.key.length>250) throw new IllegalArgumentException();
            if("me".equals(command.meta)) {
                r.hasTtl=false;r.create=false;r.recache=false;r.invalidate=false;r.removeValue=false;r.compareCas=false;
            }
        } catch(IllegalArgumentException failure) {
            command.error=4;if(command.errorText==null) command.errorText="ma".equals(command.meta)?"invalid or duplicate flag":"bad command line format";
            if(r.key==null) r.key=new byte[0];
            if(command.bodyLength<0) command.close=true;
        }
        if(store && !command.close) admitBody(); else ready=true;
    }
    private static ExtendedRequest.Operation mode(String command,String mode) {
        if("ms".equals(command)) return switch(mode) {case "S"->ExtendedRequest.Operation.SET;case "E"->ExtendedRequest.Operation.ADD;
            case "R"->ExtendedRequest.Operation.REPLACE;case "A"->ExtendedRequest.Operation.APPEND;case "P"->ExtendedRequest.Operation.PREPEND;
            default->throw new IllegalArgumentException();};
        if("ma".equals(command)) return switch(mode) {case "I","+"->ExtendedRequest.Operation.INCR;case "D","-"->ExtendedRequest.Operation.DECR;
            default->throw new IllegalArgumentException();};
        throw new IllegalArgumentException();
    }
    private static long signedToken(String value){
        if(value.isEmpty() || value.length()>11 || value.charAt(0)=='+')throw new NumberFormatException();
        if(value.equals("-"))return 0;
        return Integer.parseInt(value);
    }
    private static long unsignedToken(String value,int limit){
        if(value.isEmpty() || value.length()>limit)throw new NumberFormatException();
        for(int i=0;i<value.length();i++)if(value.charAt(i)<'0'||value.charAt(i)>'9')throw new NumberFormatException();
        return Long.parseUnsignedLong(value);
    }
}

package ru.octomem.transport;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import ru.octomem.engine.DefaultCacheEngine;
import ru.octomem.engine.ExtendedRequest;
import ru.octomem.engine.ExtendedResult;
import ru.octomem.protocol.*;

/** Shared-cache protocol negotiation without additional threads or socket ownership. */
public final class MemcachedConnectionFactory implements ConnectionFactory {
    private final TextConnectionFactory classic;
    private final DefaultCacheEngine engine;
    private final CacheCommandDispatcher dispatcher;
    private final int maxLine,itemMax,chunk;
    private final String version;
    private final TransportSecurity security;
    public MemcachedConnectionFactory(int maxLine,int itemMax,int chunk,DefaultCacheEngine engine,
            CacheCommandDispatcher dispatcher,String version) {
        this(maxLine,itemMax,chunk,engine,dispatcher,version,TransportSecurity.none());
    }
    public MemcachedConnectionFactory(int maxLine,int itemMax,int chunk,DefaultCacheEngine engine,
            CacheCommandDispatcher dispatcher,String version,TransportSecurity security) {
        this.maxLine=maxLine;this.itemMax=itemMax;this.chunk=chunk;this.engine=engine;this.dispatcher=dispatcher;this.version=version;
        this.security=security;
        classic=new TextConnectionFactory(maxLine,itemMax,dispatcher,new TextProtocolEncoder(chunk),engine);
    }
    @Override public ConnectionHandler create(ConnectionResources resources) {return new Handler(resources);}
    /** Covers protocol negotiation, bounded meta token objects and response prefixes. */
    @Override public long additionalControlBytes(){return 32768;}
    private final class Handler implements ConnectionHandler,ProtocolDecoder,ProtocolEncoder {
        private final ConnectionResources resources;
        private TextConnectionHandler text;
        private ExtendedDecoder extended;
        private ProtocolDecoder active;
        private boolean binary,chosen,closeAfter,closed;
        private ExtendedResponse output;
        private ExtendedResult pendingResult;
        private boolean authenticated=security.mode().equals("none");
        private TextProtocolDecoder authentication;
        Handler(ConnectionResources resources){this.resources=resources;}
        @Override public ProtocolDecoder decoder(){return this;}
        @Override public ProtocolEncoder encoder(){return this;}
        @Override public void append(ByteBuffer input) {
            if(active==null && input.hasRemaining()) {
                int first=input.get(input.position())&255;
                if(!chosen){binary=first==128;chosen=true;}
                if(!authenticated && !binary) {
                    if(authentication==null)authentication=new TextProtocolDecoder(maxLine,1568,new ProtocolBudget(){
                        // Authentication bytes belong to the fixed per-connection
                        // control allowance, independently of cache item geometry.
                        public boolean tryReserveBody(int n){return n<=1280;}
                        public void releaseBody(int n){ }
                    });
                    active=authentication;active.append(input);return;
                }
                if(binary || first=='m') {
                    if(extended==null)extended=new ExtendedDecoder(binary,maxLine,itemMax,new ProtocolBudget(){
                        public boolean tryReserveBody(int n){return resources.tryReserveBody(n);}
                        public void releaseBody(int n){resources.releaseBody(n);}
                    },key->{if(authenticated)engine.rejectExtendedSet(key);},(r,n)->!authenticated?0:switch(engine.admitExtended(r,n)){
                        case OK->0;case TOO_LARGE->3;default->130;},()->authenticated);
                    active=extended;
                } else {
                    if(text==null)text=classic.create(resources);
                    active=text.decoder();
                }
            }
            if(active!=null)active.append(input);
        }
        @Override public Command nextCommand() throws ProtocolException {return active==null?null:active.nextCommand();}
        @Override public Response protocolError(ProtocolException failure){
            if(active==authentication){closeAfter=true;output=new ExtendedResponse("CLIENT_ERROR authentication required\r\n".getBytes(StandardCharsets.US_ASCII),null,false,true,chunk,()->{authentication.close();active=null;output=null;});return output;}
            return text.protocolError(failure);
        }
        @Override public boolean closeAfterResponse(){return closeAfter;}
        @Override public Response handle(Command command) {
            if(!(command instanceof ExtendedCommand c)) {
                if(!authenticated) {
                    boolean valid=security.mode().equals("text") && command.type()==CommandType.SET
                            && security.authenticate(command.value(),false);
                    authenticated=valid;
                    if(command.value()!=null)java.util.Arrays.fill(command.value(),(byte)0);
                    output=new ExtendedResponse((valid?"STORED\r\n":"CLIENT_ERROR authentication failure\r\n").getBytes(StandardCharsets.US_ASCII),null,false,true,chunk,
                            ()->{authentication.release(command);active=null;output=null;});return output;
                }
                Response result=text.handle(command);if(result==null)active=null;return result;
            }
            closeAfter=c.close;
            try{return c.binary?binary(c):meta(c);}
            finally{if(pendingResult!=null){pendingResult.close();pendingResult=null;}}
        }
        private Runnable cleanup(ExtendedCommand c){return ()->{extended.release(c);active=null;output=null;};}
        private Response reply(ExtendedCommand c,byte[] prefix,ExtendedResult result,boolean value,boolean ascii) {
            output=new ExtendedResponse(prefix,result,value,ascii,chunk,cleanup(c));pendingResult=null;return output;
        }
        private Response suppress(ExtendedCommand c,ExtendedResult result) {if(result!=null)result.close();cleanup(c).run();return null;}
        private Response binary(ExtendedCommand c) {
            if(!security.mode().equals("none") || c.opcode>=32 && c.opcode<=34) {
                Response auth=authenticate(c);if(auth!=null)return auth;
            }
            int op=c.opcode,base=switch(op){case 9->0;case 13->12;case 17->1;case 18->2;case 19->3;case 20->4;case 21->5;case 22->6;
                case 23->7;case 24->8;case 25->14;case 26->15;case 30->29;case 36->35;default->op;};
            int status=c.error;String error=c.errorText;ExtendedResult result=null;
            byte[] extras=new byte[0],key=new byte[0],body=new byte[0];boolean value=false;long cas=0;
            if(status==0) {
                if(base==7){closeAfter=true;if(c.quiet)return suppress(c,null);}
                else if(base==8)engine.flushAll(c.request.ttl);
                else if(base==11)body=version.getBytes(StandardCharsets.US_ASCII);
                else if(base==16) {
                    Response stats=dispatcher.statistics(c.stats);
                    if(stats.statsLines()!=null){try{output=ExtendedResponse.statistics(stats,op,c.opaque,chunk,cleanup(c));return output;}catch(RuntimeException|Error failure){stats.close();throw failure;}}
                    stats.close();status=1;error="Not found";
                } else if(base!=10) {
                    result=engine.execute(c.request);pendingResult=result;cas=base==4?0:result.cas;
                    status=switch(result.status){case OK->0;case NOT_FOUND->1;case EXISTS->2;case TOO_LARGE->3;
                        case NOT_STORED->base==2?2:base==3?1:5;case NON_NUMERIC->6;case OUT_OF_MEMORY->130;};
                    if(status==0) {
                        boolean get=base==0 || base==12 || base==29 || base==35;
                        if(get || base==28)extras=ByteBuffer.allocate(4).putInt(result.flags).array();
                        if(base==12 || base==35)key=c.request.key;
                        value=get;
                        if(base==5 || base==6)body=ByteBuffer.allocate(8).putLong(result.number).array();
                        if(c.quiet && !get)return suppress(c,result);
                    } else {
                        if(c.quiet && status==1 && (base==0 || base==12 || base==29 || base==35))return suppress(c,result);
                        if(status==1 && (base==12 || base==35))key=c.request.key;
                    }
                }
            }
            if(status!=0 && key.length==0)body=(error!=null?error:switch(status){case 1->"Not found";case 2->"Data exists for key.";
                case 3->"Too large.";case 5->"Not stored.";case 6->"Non-numeric server-side value for incr or decr";
                case 130->base==5||base==6?"Out of memory incrementing value":"Out of memory allocating item";default->"Invalid arguments";}).getBytes(StandardCharsets.US_ASCII);
            if(c.quiet && status==0 && (base==8))return suppress(c,result);
            return reply(c,ExtendedResponse.binaryHeader(op,c.opaque,status,cas,extras,key,body,value?result.size:0),result,value,false);
        }
        private Response authenticate(ExtendedCommand c){
            int status=0;String body="";
            if(c.error!=0){status=c.error;body=c.errorText;}
            else if(c.opcode==11)return null;
            else if(security.mode().equals("sasl") && c.opcode==32)body="PLAIN";
            else if(security.mode().equals("sasl") && c.opcode==33){
                authenticated=java.util.Arrays.equals(c.request.key,"PLAIN".getBytes(StandardCharsets.US_ASCII)) && security.authenticate(c.payload,true);
                status=authenticated?0:32;body=authenticated?"Authenticated":"Auth failure.";
            }else if(c.opcode>=32 && c.opcode<=34){status=security.mode().equals("sasl")?32:129;body=status==32?"Auth failure.":"Unknown command";}
            else if(!authenticated){status=32;body="Auth failure.";closeAfter=true;}
            else return null;
            if(c.payload!=null && c.opcode>=32 && c.opcode<=34)java.util.Arrays.fill(c.payload,(byte)0);
            return reply(c,ExtendedResponse.binaryHeader(c.opcode,c.opaque,status,0,new byte[0],new byte[0],body.getBytes(StandardCharsets.US_ASCII),0),null,false,false);
        }
        private Response meta(ExtendedCommand c) {
            if(c.error!=0)return reply(c,((c.error==4?"CLIENT_ERROR ":"SERVER_ERROR ")+c.errorText+"\r\n").getBytes(StandardCharsets.US_ASCII),null,false,true);
            if("mn".equals(c.meta))return reply(c,"MN\r\n".getBytes(StandardCharsets.US_ASCII),null,false,true);
            ExtendedResult r=engine.execute(c.request);
            pendingResult=r;
            boolean get="mg".equals(c.meta),arithmetic="ma".equals(c.meta),debug="me".equals(c.meta);
            String code=switch(r.status){case OK->"HD";case NOT_FOUND->get||debug?"EN":"NF";case EXISTS->"EX";case NOT_STORED->"NS";
                case TOO_LARGE->"SERVER_ERROR object too large for cache";case OUT_OF_MEMORY->arithmetic?"SERVER_ERROR out of memory":"SERVER_ERROR out of memory storing object";
                case NON_NUMERIC->"CLIENT_ERROR cannot increment or decrement non-numeric value";};
            if(c.quiet && (get && r.status==ExtendedResult.Status.NOT_FOUND || !get && r.status==ExtendedResult.Status.OK))return suppress(c,r);
            boolean sendValue=false;StringBuilder line=new StringBuilder(128);
            if(debug && r.status==ExtendedResult.Status.OK) {
                line.append("ME ").append(reflect(c.request.key,r.binaryKey)).append(" exp=").append(r.ttl)
                    .append(" la=").append(r.age).append(" cas=").append(Long.toUnsignedString(r.cas)).append(" fetch=")
                    .append(r.fetched?"yes":"no").append(" cls=").append(r.classId+1).append(" size=").append(32+c.request.key.length+r.size);
            } else {
                if(r.status==ExtendedResult.Status.OK && (get || arithmetic))for(String f:c.options)if(f.equals("v"))sendValue=true;
                if(get && c.request.compareCas && c.request.expectedCas==r.cas)sendValue=false;
                String numeric=arithmetic?Long.toUnsignedString(r.number):null;
                if(sendValue)line.append("VA ").append(arithmetic?numeric.length():r.size);else line.append(code);
                for(String flag:c.options) {
                    char f=flag.charAt(0);
                    if(f=='O')line.append(' ').append(flag);
                    else if(f=='k') {line.append(" k").append(reflect(c.request.key,c.request.binaryKey||r.binaryKey));if(c.request.binaryKey||r.binaryKey)line.append(" b");}
                    else if(r.status==ExtendedResult.Status.OK)switch(f) {
                        case 'c'->line.append(" c").append(Long.toUnsignedString(r.cas));
                        case 'f'->line.append(" f").append(Integer.toUnsignedString(r.flags));
                        case 's'->line.append(" s").append(r.size);
                        case 't'->line.append(" t").append(arithmetic && c.request.ttlBeforeUpdate?r.previousTtl:r.ttl);
                        case 'l'->line.append(" l").append(r.age);
                        case 'h'->line.append(" h").append(r.fetched?'1':'0');
                        default->{ }
                    };
                }
                if(get && r.status==ExtendedResult.Status.OK) {
                    if(r.alreadyWon)line.append(" Z");if(r.stale)line.append(" X");if(r.winner)line.append(" W");
                }
                if(arithmetic && sendValue){line.append("\r\n").append(numeric);sendValue=false;}
            }
            line.append("\r\n");return reply(c,line.toString().getBytes(StandardCharsets.US_ASCII),r,sendValue,true);
        }
        private String reflect(byte[] key,boolean binary){return binary?Base64.getEncoder().encodeToString(key):new String(key,StandardCharsets.US_ASCII);}
        @Override public ResponseWriter encode(Response response) {
            if(response instanceof ExtendedResponse e)return e;
            ResponseWriter writer=text.encode(response);
            return new ResponseWriter(){
                public WriteStatus writeMore(ChunkSink sink){WriteStatus status=writer.writeMore(sink);if(status==WriteStatus.COMPLETE)active=null;return status;}
                public void close(){try{writer.close();}finally{active=null;}}
            };
        }
        @Override public void close(){if(closed)return;closed=true;try{if(output!=null)output.close();}finally{try{if(extended!=null)extended.close();}finally{try{if(text!=null)text.close();}finally{if(authentication!=null)authentication.close();}}}}
    }
}

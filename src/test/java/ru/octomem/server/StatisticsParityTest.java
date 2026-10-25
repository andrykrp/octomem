package ru.octomem.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.octomem.cli.CacheServer;
import ru.octomem.config.ConfigLoader;
import static org.junit.jupiter.api.Assertions.*;

/** Independent exact counter vectors from pinned1.6.45 public source and live probes. */
@Timeout(15)
class StatisticsParityTest {
    private static final List<String> FIELDS=List.of("cmd_get","get_hits","get_misses","cmd_set",
            "cas_hits","cas_badval","cas_misses","delete_hits","delete_misses","incr_hits","incr_misses",
            "decr_hits","decr_misses","cmd_touch","touch_hits","touch_misses","cmd_flush");
    static Stream<Arguments> cases(){
        List<Arguments> cases=new ArrayList<>();
        for(String family:List.of("classic","binary","meta")){
            for(String op:List.of("noop","get-hit","get-miss","set-new","set-existing","add-new","add-existing",
                    "replace-hit","replace-miss","cas-equal","cas-lower","cas-higher","cas-missing","delete-hit","delete-miss",
                    "incr-hit","incr-miss","incr-nonnumeric","decr-hit","decr-miss","decr-nonnumeric","touch-hit","touch-miss","flush"))cases.add(Arguments.of(family,op));
            if(!family.equals("classic"))for(String op:List.of("delete-cas-equal","delete-cas-lower","delete-cas-higher","delete-cas-missing",
                    "incr-create","decr-create","incr-cas-equal","incr-cas-lower","incr-cas-missing","decr-cas-lower","decr-cas-missing"))cases.add(Arguments.of(family,op));
            if(!family.equals("classic"))for(String op:List.of("append-cas-equal","append-cas-lower","append-cas-missing","prepend-cas-equal","prepend-cas-lower","prepend-cas-missing"))cases.add(Arguments.of(family,op));
        }
        for(String op:List.of("delete-invalidate","delete-tombstone","delete-invalidate-tombstone","get-cas-equal","get-cas-mismatch",
                "get-create","replace-cas-stale","quiet-get-miss","quiet-set","quiet-delete-hit"))cases.add(Arguments.of("meta",op));
        cases.add(Arguments.of("classic","gat-hit"));cases.add(Arguments.of("classic","gat-miss"));return cases.stream();
    }
    /** Whole17-counter vector, including zeros, prevents cross-category false positives. */
    private static Map<String,Long> expected(String family,String op){
        Map<String,Long> result=new LinkedHashMap<>();FIELDS.forEach(f->result.put(f,0L));
        if(op.equals("noop")||op.contains("nonnumeric"))return result;
        if(op.startsWith("quiet-"))op=op.substring(6);
        if(op.startsWith("get")){result.put("cmd_get",1L);result.put(op.equals("get-miss")?"get_misses":"get_hits",1L);}
        else if(op.startsWith("append")||op.startsWith("prepend")){result.put("cmd_set",1L);}
        else if(op.startsWith("touch")||op.startsWith("gat")){
            if(family.equals("meta")&&op.endsWith("miss")){result.put("cmd_get",1L);result.put("get_misses",1L);}
            else{result.put("cmd_touch",1L);result.put(op.endsWith("miss")?"touch_misses":"touch_hits",1L);}
        }else if(op.startsWith("set")||op.startsWith("add")||op.startsWith("replace")||op.startsWith("cas")){
            result.put("cmd_set",1L);
            if(op.equals("cas-equal")||op.equals("replace-cas-stale"))result.put("cas_hits",1L);
            if(op.equals("cas-lower")||op.equals("cas-higher"))result.put("cas_badval",1L);
            if(op.equals("cas-missing"))result.put("cas_misses",1L);
        }else if(op.startsWith("delete")){
            if(op.contains("invalidate"))return result;
            if(op.endsWith("lower")||op.endsWith("higher")){if(family.equals("meta"))result.put("delete_misses",1L);}
            else result.put(op.endsWith("miss")||op.endsWith("missing")?"delete_misses":"delete_hits",1L);
        }else if(op.startsWith("incr")||op.startsWith("decr")){
            if(op.endsWith("create")||op.endsWith("lower")||op.endsWith("higher"))return result;
            result.put(op.substring(0,4)+(op.endsWith("miss")||op.endsWith("missing")?"_misses":"_hits"),1L);
        }else if(op.equals("flush"))result.put("cmd_flush",1L);
        else throw new AssertionError(op);
        return result;
    }
    @ParameterizedTest(name="{0} {1}") @MethodSource("cases")
    void operationCounterVectorMatchesIndependentReference(String family,String op)throws Exception{
        var config=ConfigLoader.load(null,Map.of("transport.workers","1","memory.limit","4194304","item.maxSize","4096","connections.max","8"));
        CacheServer server=new CacheServer(config,new InetSocketAddress("127.0.0.1",0));
        try{server.start();try(Wire text=new Wire(server.localAddress());Wire binary=new Wire(server.localAddress())){
            // Ensure even a lower CAS remains nonzero (binary CAS0 means unconditional).
            assertEquals(List.of("STORED\r\n"),text.text("set seed 0 0 1\r\ns\r\n"));
            boolean absent=op.endsWith("miss")||op.endsWith("missing")||op.endsWith("new")||op.endsWith("create");long token=99;
            if(!absent){String value=op.contains("nonnumeric")?"abc":"9";assertEquals(List.of("STORED\r\n"),text.text("set k 7 0 "+value.length()+"\r\n"+value+"\r\n"));
                var fetched=text.text("gets k\r\n");token=Long.parseUnsignedLong(fetched.get(0).trim().split(" ")[4]);}
            assertEquals(List.of("RESET\r\n"),text.text("stats reset\r\n"));Map<String,Long> before=text.stats();
            execute(text,binary,family,op,token);Map<String,Long> after=text.stats();Map<String,Long> actual=new LinkedHashMap<>();
            for(String field:FIELDS){assertTrue(before.containsKey(field)&&after.containsKey(field),"Unsupported stats field "+field);actual.put(field,after.get(field)-before.get(field));}
            assertEquals(expected(family,op),actual,"Pinned1.6.45 vector for "+family+" "+op);
        }}finally{server.close();assertEquals(0,server.resourceUsage().nativeUsedBytes());assertEquals(0,server.resourceUsage().bodyBytes());assertEquals(0,server.resourceUsage().controlBytes());}
    }
    private static void execute(Wire text,Wire binary,String family,String op,long token)throws Exception{
        long cas=op.endsWith("lower")?token-1:op.endsWith("higher")||op.equals("get-cas-mismatch")?token+1:op.endsWith("missing")?99:token;
        if(family.equals("binary")){
            int code;byte[] extras=new byte[0],value=new byte[0];long expectedCas=0;String key="k";
            if(op.equals("noop")){code=10;key="";}
            else if(op.startsWith("get"))code=0;
            else if(op.startsWith("set")||op.startsWith("add")||op.startsWith("replace")||op.startsWith("cas")){
                code=op.startsWith("add")?2:op.startsWith("replace")?3:1;extras=ByteBuffer.allocate(8).putInt(7).putInt(0).array();value=bytes("x");if(op.startsWith("cas"))expectedCas=cas;
            }else if(op.startsWith("delete")){code=4;if(op.contains("-cas-"))expectedCas=cas;}
            else if(op.startsWith("append")||op.startsWith("prepend")){code=op.startsWith("append")?14:15;value=bytes("x");expectedCas=cas;}
            else if(op.startsWith("incr")||op.startsWith("decr")){code=op.startsWith("incr")?5:6;extras=ByteBuffer.allocate(20).putLong(2).putLong(9).putInt(op.endsWith("create")?30:-1).array();if(op.contains("-cas-"))expectedCas=cas;}
            else if(op.startsWith("touch")){code=28;extras=ByteBuffer.allocate(4).putInt(30).array();}
            else{code=8;key="";}
            Reply reply=binary.binary(code,key,value,extras,expectedCas);int status=0;
            if(op.contains("nonnumeric"))status=6;
            else if(op.endsWith("lower")||op.endsWith("higher")||op.equals("add-existing"))status=2;
            else if(op.endsWith("miss")||op.endsWith("missing"))status=op.startsWith("append")||op.startsWith("prepend")?5:1;
            assertEquals(status,reply.status,"binary response outcome");
            if(status==0&&(op.startsWith("incr")||op.startsWith("decr")))assertEquals(op.endsWith("create")?9:op.startsWith("incr")?11:7,ByteBuffer.wrap(reply.body).getLong());
            return;
        }
        String command;
        if(family.equals("classic")){
            if(op.equals("noop"))command="";
            else if(op.startsWith("get"))command="get k\r\n";
            else if(op.startsWith("gat"))command="gat 30 k\r\n";
            else if(op.startsWith("set")||op.startsWith("add")||op.startsWith("replace"))command=op.split("-")[0]+" k 7 0 1\r\nx\r\n";
            else if(op.startsWith("cas"))command="cas k 7 0 1 "+cas+"\r\nx\r\n";
            else if(op.startsWith("delete"))command="delete k\r\n";
            else if(op.startsWith("incr")||op.startsWith("decr"))command=op.split("-")[0]+" k 2\r\n";
            else if(op.startsWith("touch"))command="touch k 30\r\n";else command="flush_all\r\n";
        }else{
            if(op.equals("noop"))command="";
            else if(op.equals("quiet-get-miss"))command="mg k q\r\n";
            else if(op.equals("quiet-set"))command="ms k 1 q\r\nx\r\n";
            else if(op.equals("quiet-delete-hit"))command="md k q\r\n";
            else if(op.startsWith("get"))command="mg k"+(op.equals("get-create")?" N30":op.contains("-cas-")?" C"+cas+" v":" v")+"\r\n";
            else if(op.startsWith("set")||op.startsWith("add")||op.startsWith("replace")||op.startsWith("cas")){
                String flags=op.startsWith("add")?"ME":op.startsWith("replace")?"MR":"";
                if(op.startsWith("cas"))flags+=" C"+cas;if(op.equals("replace-cas-stale"))flags+=" C"+(token-1)+" I";
                command="ms k 1 "+flags+"\r\nx\r\n";
            }else if(op.startsWith("append")||op.startsWith("prepend"))command="ms k 1 M"+(op.startsWith("append")?"A":"P")+" C"+cas+"\r\nx\r\n";
            else if(op.startsWith("delete")){
                String flags=op.contains("-cas-")?" C"+cas:op.equals("delete-invalidate")?" I":op.equals("delete-tombstone")?" x":op.equals("delete-invalidate-tombstone")?" x I":"";
                command="md k"+flags+"\r\n";
            }else if(op.startsWith("incr")||op.startsWith("decr"))command="ma k D2"+(op.startsWith("decr")?" MD":"")+(op.endsWith("create")?" N30 J9":"")+(op.contains("-cas-")?" C"+cas:"")+" v\r\n";
            else if(op.startsWith("touch"))command="mg k T30\r\n";else command="flush_all\r\n";
        }
        List<String> actual=text.text(command);assertEquals(expectedText(family,op),actual,"operation response must precede counter comparison");
    }
    private static List<String> expectedText(String family,String op){
        if(op.equals("noop")||op.startsWith("quiet-"))return List.of();
        if(op.contains("nonnumeric"))return List.of("CLIENT_ERROR cannot increment or decrement non-numeric value\r\n");
        if(op.equals("flush"))return List.of("OK\r\n");
        if(family.equals("classic")){
            if(op.startsWith("get")||op.startsWith("gat"))return op.endsWith("miss")?List.of("END\r\n"):List.of("VALUE k 7 1\r\n","9\r\n","END\r\n");
            if(op.equals("add-existing")||op.equals("replace-miss"))return List.of("NOT_STORED\r\n");
            if(op.equals("cas-lower")||op.equals("cas-higher"))return List.of("EXISTS\r\n");
            if(op.endsWith("miss")||op.endsWith("missing"))return List.of("NOT_FOUND\r\n");
            if(op.startsWith("incr"))return List.of("11\r\n");if(op.startsWith("decr"))return List.of("7\r\n");
            if(op.startsWith("delete"))return List.of("DELETED\r\n");if(op.startsWith("touch"))return List.of("TOUCHED\r\n");return List.of("STORED\r\n");
        }
        if(op.endsWith("lower")||op.endsWith("higher"))return List.of("EX\r\n");
        if(op.equals("add-existing")||op.equals("replace-miss"))return List.of("NS\r\n");
        if((op.startsWith("append")||op.startsWith("prepend"))&&op.endsWith("missing"))return List.of("NS\r\n");
        if(op.endsWith("miss")||op.endsWith("missing"))return List.of(op.startsWith("get")||op.startsWith("touch")?"EN\r\n":"NF\r\n");
        if(op.equals("get-create"))return List.of("HD W\r\n");
        if(op.equals("get-cas-equal"))return List.of("HD\r\n");
        if(op.startsWith("get"))return List.of("VA 1\r\n","9\r\n");
        if(op.startsWith("incr")||op.startsWith("decr")){String value=op.endsWith("create")?"9":op.startsWith("incr")?"11":"7";return List.of("VA "+value.length()+"\r\n",value+"\r\n");}
        return List.of("HD\r\n");
    }
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.US_ASCII);}
    static Stream<Arguments> phaseCases(){
        List<Arguments> result=new ArrayList<>();for(String family:List.of("classic","meta","binary"))for(String phase:List.of("header-disconnect","complete","bad-body","oversize-header")){
            if(family.equals("binary")&&phase.equals("bad-body"))continue;result.add(Arguments.of(family,phase));
        }return result.stream();
    }
    @ParameterizedTest(name="{0} storage phase {1}") @MethodSource("phaseCases")
    void storageCounterPhaseMatchesReferenceRatherThanOnlySuccessfulMutation(String family,String phase)throws Exception{
        var config=ConfigLoader.load(null,Map.of("transport.workers","1","memory.limit","4194304","item.maxSize","4096","connections.max","8"));
        CacheServer server=new CacheServer(config,new InetSocketAddress("127.0.0.1",0));
        try{server.start();try(Wire control=new Wire(server.localAddress());Wire upload=new Wire(server.localAddress())){
            assertEquals(List.of("RESET\r\n"),control.text("stats reset\r\n"));Map<String,Long> before=control.stats();
            if(phase.equals("complete")){
                if(family.equals("binary"))assertEquals(0,upload.binary(1,"k",bytes("abc"),new byte[8],0).status);
                else assertEquals(List.of(family.equals("classic")?"STORED\r\n":"HD\r\n"),upload.text(family.equals("classic")?"set k 0 0 3\r\nabc\r\n":"ms k 3\r\nabc\r\n"));
            }else{
                int length=phase.equals("oversize-header")?4097:3;byte[] header;
                if(family.equals("binary"))header=ByteBuffer.allocate(33).put((byte)128).put((byte)1).putShort((short)1).put((byte)8).put((byte)0).putShort((short)0).putInt(9+length).putInt(123).putLong(0).putLong(0).put((byte)'k').array();
                else header=bytes(family.equals("classic")?"set k 0 0 "+length+"\r\n":"ms k "+length+"\r\n");
                upload.send(header);
                if(phase.equals("bad-body")){upload.send(bytes("abcXX"));assertEquals("CLIENT_ERROR bad data chunk\r\n",upload.line());}
                else{
                    upload.socket.shutdownOutput();int count=0;while(upload.socket.getInputStream().read()!=-1){assertTrue(++count<=256,"bounded failure response before EOF");}
                }
            }
            Map<String,Long> after=control.stats();Map<String,Long> actual=new LinkedHashMap<>(),expected=new LinkedHashMap<>();
            for(String field:FIELDS){assertTrue(before.containsKey(field)&&after.containsKey(field));actual.put(field,after.get(field)-before.get(field));expected.put(field,0L);}
            boolean counted=phase.equals("complete")||!family.equals("binary")&&!phase.equals("oversize-header");expected.put("cmd_set",counted?1L:0L);assertEquals(expected,actual);
        }}finally{server.close();assertEquals(0,server.resourceUsage().nativeUsedBytes());assertEquals(0,server.resourceUsage().bodyBytes());}
    }
    record Reply(int status,byte[] body){}
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"meta","binary"})
    void resetBetweenHeaderAndBodyPreservesProtocolSpecificCountingPhase(String family)throws Exception{
        CacheServer server=new CacheServer(ConfigLoader.load(null,Map.of("transport.workers","1","memory.limit","4194304","item.maxSize","4096")),new InetSocketAddress("127.0.0.1",0));
        try{server.start();try(Wire control=new Wire(server.localAddress());Wire upload=new Wire(server.localAddress())){
            assertEquals(List.of("RESET\r\n"),control.text("stats reset\r\n"));
            if(family.equals("meta"))upload.send(bytes("ms k 3\r\n"));
            else upload.send(ByteBuffer.allocate(33).put((byte)128).put((byte)1).putShort((short)1).put((byte)8).put((byte)0).putShort((short)0).putInt(12).putInt(123).putLong(0).putLong(0).put((byte)'k').array());
            boolean admitted=false;long deadline=System.nanoTime()+2_000_000_000L;
            do{Map<String,Long> observed=control.stats();if(family.equals("meta")?observed.get("cmd_set")==1:server.resourceUsage().bodyBytes()>0){admitted=true;break;}}while(System.nanoTime()<deadline);
            assertTrue(admitted,"header phase must be observed before reset");assertEquals(List.of("RESET\r\n"),control.text("stats reset\r\n"));
            upload.send(bytes(family.equals("meta")?"abc\r\n":"abc"));if(family.equals("meta"))assertEquals("HD\r\n",upload.line());else{ByteBuffer reply=ByteBuffer.wrap(upload.read(24));reply.position(6);assertEquals(0,reply.getShort());}
            long expected=family.equals("meta")?0:1;assertEquals(expected,control.stats().get("cmd_set"));assertEquals(expected,control.classSetCounts().values().stream().mapToLong(Long::longValue).sum());
        }}finally{server.close();}
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"meta","binary"})
    void appendCountsIncomingFragmentClassNotCombinedResultClass(String family)throws Exception{
        CacheServer server=new CacheServer(ConfigLoader.load(null,Map.of("transport.workers","1","memory.limit","4194304","item.maxSize","4096")),new InetSocketAddress("127.0.0.1",0));
        try{server.start();try(Wire control=new Wire(server.localAddress());Wire operation=new Wire(server.localAddress())){
            assertEquals(List.of("STORED\r\n"),control.text("set k 0 0 3000\r\n"+"x".repeat(3000)+"\r\n"));assertEquals(List.of("RESET\r\n"),control.text("stats reset\r\n"));
            if(family.equals("meta"))assertEquals(List.of("HD\r\n"),operation.text("ms k 1 MA\r\ny\r\n"));else assertEquals(0,operation.binary(14,"k",bytes("y"),new byte[0],0).status);
            Map<String,String> slabs=control.rawStats("stats slabs");long total=0;
            for(var entry:slabs.entrySet())if(entry.getKey().endsWith(":cmd_set")){long count=Long.parseLong(entry.getValue());total+=count;if(count>0){String prefix=entry.getKey().substring(0,entry.getKey().indexOf(':'));assertTrue(Long.parseLong(slabs.get(prefix+":chunk_size"))<3000,"incoming one-byte fragment class, not large combined item");}}
            assertEquals(1,total);assertEquals(1,control.stats().get("cmd_set"));
        }}finally{server.close();}
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"meta","binary"})
    void casHitCountsOldClassWhileStoreCountsNewClass(String family)throws Exception{
        CacheServer server=new CacheServer(ConfigLoader.load(null,Map.of("transport.workers","1","memory.limit","4194304","item.maxSize","4096")),new InetSocketAddress("127.0.0.1",0));
        try{server.start();try(Wire control=new Wire(server.localAddress());Wire operation=new Wire(server.localAddress())){
            assertEquals(List.of("STORED\r\n"),control.text("set k 0 0 3000\r\n"+"x".repeat(3000)+"\r\n"));String header=control.text("gets k\r\n").get(0);long cas=Long.parseUnsignedLong(header.trim().split(" ")[4]);
            assertEquals(List.of("RESET\r\n"),control.text("stats reset\r\n"));
            if(family.equals("meta"))assertEquals(List.of("HD\r\n"),operation.text("ms k 1 C"+cas+"\r\ny\r\n"));else assertEquals(0,operation.binary(1,"k",bytes("y"),new byte[8],cas).status);
            Map<String,String> slabs=control.rawStats("stats slabs");long sets=0,hits=0;
            for(var entry:slabs.entrySet())if(entry.getKey().endsWith(":cmd_set")||entry.getKey().endsWith(":cas_hits")){
                long count=Long.parseLong(entry.getValue());boolean set=entry.getKey().endsWith(":cmd_set");if(set)sets+=count;else hits+=count;
                if(count>0){String prefix=entry.getKey().substring(0,entry.getKey().indexOf(':'));long size=Long.parseLong(slabs.get(prefix+":chunk_size"));assertEquals(set,size<3000,"CAS hit belongs to prior large class, store to incoming small class");}
            }
            assertEquals(1,sets);assertEquals(1,hits);assertEquals(1,control.stats().get("cas_hits"));
        }}finally{server.close();}
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"meta","binary"})
    void evictionRetryCountsOneAdmittedStoreNotEveryAllocationAttempt(String family)throws Exception{
        CacheServer server=new CacheServer(ConfigLoader.load(null,Map.of("transport.workers","1","memory.limit","1048576","item.maxSize","1048576")),new InetSocketAddress("127.0.0.1",0));
        try{server.start();try(Wire control=new Wire(server.localAddress());Wire operation=new Wire(server.localAddress())){
            String body="x".repeat(450000);assertEquals(List.of("STORED\r\n"),control.text("set other 0 0 450000\r\n"+body+"\r\n"));assertEquals(List.of("STORED\r\n"),control.text("set k 0 0 450000\r\n"+body+"\r\n"));
            assertEquals(List.of("RESET\r\n"),control.text("stats reset\r\n"));
            if(family.equals("meta"))assertEquals(List.of("HD\r\n"),operation.text("ms k 450000\r\n"+body+"\r\n"));else assertEquals(0,operation.binary(1,"k",bytes(body),new byte[8],0).status);
            assertEquals(1,control.stats().get("cmd_set"));assertEquals(1,control.classSetCounts().values().stream().mapToLong(Long::longValue).sum());assertEquals(List.of("END\r\n"),control.text("get other\r\n"));
        }}finally{server.close();}
    }
    private static final class Wire implements AutoCloseable{
        final Socket socket=new Socket();
        Wire(InetSocketAddress address)throws Exception{socket.connect(address,2000);socket.setSoTimeout(2000);}
        void send(byte[] value)throws IOException{socket.getOutputStream().write(value);socket.getOutputStream().flush();}
        byte[] read(int n)throws IOException{byte[] data=socket.getInputStream().readNBytes(n);assertEquals(n,data.length);return data;}
        String line()throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();for(int i=0;i<8192;i++){int c=socket.getInputStream().read();assertNotEquals(-1,c);out.write(c);if(c=='\n')return out.toString(StandardCharsets.US_ASCII);}throw new AssertionError("line bound");}
        List<String> text(String request)throws IOException{send(bytes(request+"mn\r\n"));List<String> result=new ArrayList<>();for(int i=0;i<16;i++){String line=line();if(line.equals("MN\r\n"))return result;result.add(line);if(line.startsWith("VALUE "))result.add(new String(read(Integer.parseInt(line.trim().split(" ")[3])+2),StandardCharsets.US_ASCII));if(line.startsWith("VA "))result.add(new String(read(Integer.parseInt(line.trim().split(" ")[1])+2),StandardCharsets.US_ASCII));}throw new AssertionError("response bound");}
        Map<String,Long> stats()throws IOException{send(bytes("stats\r\n"));Map<String,Long> values=new LinkedHashMap<>();for(int i=0;i<256;i++){String line=line();if(line.equals("END\r\n"))return values;String[] parts=line.trim().split(" ",3);assertEquals("STAT",parts[0]);if(FIELDS.contains(parts[1]))assertNull(values.put(parts[1],Long.parseLong(parts[2])));}throw new AssertionError("stats bound");}
        Map<String,String> rawStats(String command)throws IOException{send(bytes(command+"\r\n"));Map<String,String> result=new LinkedHashMap<>();for(int i=0;i<4096;i++){String line=line();if(line.equals("END\r\n"))return result;String[] fields=line.trim().split(" ",3);assertEquals("STAT",fields[0]);assertNull(result.put(fields[1],fields[2]));}throw new AssertionError("class stats bound");}
        Map<String,Long> classSetCounts()throws IOException{Map<String,Long> result=new LinkedHashMap<>();rawStats("stats slabs").forEach((k,v)->{if(k.endsWith(":cmd_set"))result.put(k,Long.parseLong(v));});return result;}
        Reply binary(int op,String key,byte[] value,byte[] extras,long cas)throws IOException{byte[] k=bytes(key);send(ByteBuffer.allocate(24+k.length+extras.length+value.length).put((byte)128).put((byte)op).putShort((short)k.length).put((byte)extras.length).put((byte)0).putShort((short)0).putInt(k.length+extras.length+value.length).putInt(123).putLong(cas).put(extras).put(k).put(value).array());ByteBuffer h=ByteBuffer.wrap(read(24));assertEquals(129,Byte.toUnsignedInt(h.get()));assertEquals(op,Byte.toUnsignedInt(h.get()));h.getShort();h.get();h.get();int status=Short.toUnsignedInt(h.getShort());int size=h.getInt();assertTrue(size>=0&&size<8192);assertEquals(123,h.getInt());h.getLong();return new Reply(status,read(size));}
        public void close()throws IOException{socket.close();}
    }
}

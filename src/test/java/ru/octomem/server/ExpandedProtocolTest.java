package ru.octomem.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.octomem.cli.CacheServer;
import ru.octomem.config.ConfigLoader;
import ru.octomem.util.Clock;
import static org.junit.jupiter.api.Assertions.*;
import static ru.octomem.server.ExpandedWire.*;

/** Independent pinned1.6.45 expansion acceptance; oracle precedes production work. */
@Timeout(20)
class ExpandedProtocolTest {
    private static final byte[] EMPTY = new byte[0];
    private final List<ExpandedWire> peers = new ArrayList<>();
    private CacheServer server;
    private Path transcript;
    private final TestClock clock = new TestClock();

    @BeforeEach void evidence(TestInfo info) throws IOException {
        Path root = Path.of(System.getProperty("expanded.evidence", "tests/reports/protocol-expansion-20261025"), "wire");
        Files.createDirectories(root);
        transcript = root.resolve(info.getTestMethod().orElseThrow().getName() + "-" + UUID.randomUUID() + ".txt");
        Files.writeString(transcript, "CASE " + info.getDisplayName() + "\n", StandardOpenOption.CREATE_NEW);
    }
    private void start(String... overrides) throws Exception {
        Map<String,String> config = new HashMap<>(Map.of("transport.workers","2","memory.limit","4194304",
                "item.maxSize","65536","connections.max","16","expiry.reclaimIntervalSeconds","86400"));
        for (int i=0;i<overrides.length;i+=2) config.put(overrides[i],overrides[i+1]);
        server = new CacheServer(ConfigLoader.load(null,config), new InetSocketAddress("127.0.0.1",0),clock);
        server.start();
    }
    private ExpandedWire peer() throws IOException {
        ExpandedWire p = new ExpandedWire(server.localAddress(),transcript);peers.add(p);return p;
    }
    @AfterEach void close() throws Exception {
        try { for (ExpandedWire p : peers) p.close(); }
        finally { if(server!=null) {
            server.close();var r=server.resourceUsage();
            assertAll(()->assertEquals(0,r.nativeUsedBytes()),()->assertEquals(0,r.nativeTotalBytes()),
                    ()->assertEquals(0,r.bodyBytes()),()->assertEquals(0,r.snapshotBytes()),
                    ()->assertEquals(0,r.controlBytes()),()->assertEquals(0,r.lineBytes()),()->assertEquals(0,r.serviceBytes()));
        } }
    }
    private long put(ExpandedWire p,String key,String value) throws IOException {
        var r=p.request(1,key,ascii(value),integers(7,0),0);r.success();assertNotEquals(0,r.cas());return r.cas();
    }

    @ParameterizedTest @ValueSource(ints={0,9,12,13,28,29,30,35,36})
    void retrievalOpcodesPreserveCasAndExactGeometry(int op) throws Exception {
        start();var p=peer();long cas=put(p,"key","value");
        var r=p.request(op,"key",EMPTY,op>=28?integers(30):EMPTY,0);r.success();
        assertEquals(cas,r.cas());assertArrayEquals(integers(7),r.extras());
        assertArrayEquals(op==12||op==13||op==35||op==36?ascii("key"):EMPTY,r.key());
        assertArrayEquals(op==28?EMPTY:ascii("value"),r.value());p.barrier();
        if(op>=28) { clock.seconds+=30;p.request(0,"key",EMPTY,EMPTY,0).error(1,"Not found"); }
    }

    @ParameterizedTest @ValueSource(ints={0,9,12,13,28,29,30,35,36})
    void retrievalMissesAndQuietBarrier(int op) throws Exception {
        start();var p=peer();p.send(packet(op,ascii("miss"),EMPTY,op>=28?integers(0):EMPTY,0,77));
        boolean quiet=op==9||op==13||op==30||op==36;
        if(!quiet) {var r=p.reply();r.header(op,77);
            if(op==12||op==35){assertEquals(1,r.status());assertArrayEquals(ascii("miss"),r.key());assertEquals(0,r.value().length);}
            else r.error(1,"Not found");
        }
        p.barrier();
    }

    @ParameterizedTest @ValueSource(ints={1,2,3,17,18,19})
    void storageSuccessCasAndQuietErrors(int op) throws Exception {
        start();var p=peer();boolean quiet=op>=17;int normal=quiet?op-16:op;
        if(normal==3)put(p,"k","old");
        p.send(packet(op,ascii("k"),ascii("new"),integers(-1,0),0,456));
        if(!quiet){var r=p.reply();r.header(op,456);r.success();assertNotEquals(0,r.cas());}
        p.barrier();var stored=p.request(0,"k",EMPTY,EMPTY,0);stored.success();
        assertArrayEquals(ascii("new"),stored.value());assertArrayEquals(integers(-1),stored.extras());
        if(normal==2){var r=p.request(op,"k",ascii("bad"),integers(0,0),0);r.error(2,"Data exists for key.");}
        else if(normal==3)p.request(op,"absent",ascii("bad"),integers(0,0),0).error(1,"Not found");
        p.request(op,"k",ascii("bad"),integers(0,0),stored.cas()+1).error(2,"Data exists for key.");
        p.barrier();
    }

    @ParameterizedTest @ValueSource(ints={14,15,25,26})
    void appendPrependModesPreserveFlagsAndCheckCas(int op) throws Exception {
        start();var p=peer();long cas=put(p,"k","m");boolean append=op==14||op==25;
        p.send(packet(op,ascii("k"),ascii("x"),EMPTY,cas,11));
        if(op<25)p.reply().success();p.barrier();
        var r=p.request(0,"k",EMPTY,EMPTY,0);r.success();assertArrayEquals(ascii(append?"mx":"xm"),r.value());
        assertArrayEquals(integers(7),r.extras());assertNotEquals(cas,r.cas());
        p.request(op,"k",ascii("bad"),EMPTY,cas).error(2,"Data exists for key.");
        p.request(op,"missing",ascii("bad"),EMPTY,0).error(5,"Not stored.");
    }

    @ParameterizedTest @ValueSource(ints={4,20})
    void deleteCasAndQuietMiss(int op) throws Exception {
        start();var p=peer();long cas=put(p,"k","x");
        p.request(op,"k",EMPTY,EMPTY,cas+1).error(2,"Data exists for key.");
        p.send(packet(op,ascii("k"),EMPTY,EMPTY,cas,14));if(op==4)p.reply().success();p.barrier();
        p.request(op,"k",EMPTY,EMPTY,0).error(1,"Not found");
    }

    @ParameterizedTest @ValueSource(ints={5,6,21,22})
    void arithmeticInitialUnsignedAndNoCreate(int op) throws Exception {
        start();var p=peer();boolean quiet=op>=21;
        p.request(op,"missing",EMPTY,arithmetic(1,9,-1),0).error(1,"Not found");
        p.send(packet(op,ascii("n"),EMPTY,arithmetic(2,9,0),0,42));
        if(!quiet){var r=p.reply();r.success();assertEquals(9,ByteBuffer.wrap(r.value()).getLong());}p.barrier();
        var original=p.request(0,"n",EMPTY,EMPTY,0);assertArrayEquals(ascii("9"),original.value());
        p.request(op,"n",EMPTY,arithmetic(1,0,0),original.cas()+1).error(2,"Data exists for key.");
        p.send(packet(op,ascii("n"),EMPTY,arithmetic(20,0,0),0,43));
        if(!quiet){var r=p.reply();r.success();assertEquals(op==5?29:0,ByteBuffer.wrap(r.value()).getLong());}p.barrier();
        put(p,"text","abc");p.request(op,"text",EMPTY,arithmetic(1,0,-1),0).error(6,"Non-numeric server-side value for incr or decr");
        put(p,"max","18446744073709551615");
        var wrap=p.request(5,"max",EMPTY,arithmetic(1,0,-1),0);wrap.success();assertEquals(0,ByteBuffer.wrap(wrap.value()).getLong());
    }

    @ParameterizedTest @ValueSource(ints={8,24})
    void flushImmediateAndDelayed(int op) throws Exception {
        start();var p=peer();put(p,"k","x");
        p.send(packet(op,EMPTY,EMPTY,integers(2),0,8));if(op==8)p.reply().success();p.barrier();
        p.request(0,"k",EMPTY,EMPTY,0).success();clock.seconds+=3;p.request(0,"k",EMPTY,EMPTY,0).error(1,"Not found");
        put(p,"k","x");p.send(packet(op,EMPTY,EMPTY,EMPTY,0,9));if(op==8)p.reply().success();p.barrier();
        p.request(0,"k",EMPTY,EMPTY,0).error(1,"Not found");
    }

    @ParameterizedTest @ValueSource(ints={7,23})
    void quitPreservesPriorRepliesAndCloses(int op) throws Exception {
        start();var p=peer();p.send(packet(10,EMPTY,EMPTY,EMPTY,0,17));p.send(packet(op,EMPTY,EMPTY,EMPTY,0,18));
        p.reply().header(10,17);if(op==7){var r=p.reply();r.header(7,18);r.success();}
        assertEquals(-1,p.socket.getInputStream().read());
    }

    @Test void versionStatsAndUnknownCommand() throws Exception {
        start();var p=peer();var version=p.request(11,"",EMPTY,EMPTY,0);version.success();
        assertArrayEquals(ascii("1.0.0 (octomem)"),version.value());
        p.send(packet(16,EMPTY,EMPTY,EMPTY,0,91));Map<String,String> stats=new HashMap<>();
        boolean terminated=false;
        for(int i=0;i<256;i++){var r=p.reply();r.header(16,91);r.success();if(r.key().length==0){assertEquals(0,r.value().length);terminated=true;break;}
            assertNull(stats.put(new String(r.key(),java.nio.charset.StandardCharsets.US_ASCII),new String(r.value(),java.nio.charset.StandardCharsets.US_ASCII)));}
        assertTrue(terminated,"STAT must end with an empty binary frame");
        assertTrue(stats.containsKey("curr_items"));assertTrue(stats.containsKey("version"));
        p.request(0x7f,"",ascii("ignored"),EMPTY,0).error(0x81,"Unknown command");p.barrier();
    }

    @ParameterizedTest @ValueSource(ints={1,2,7,23})
    void fragmentedBinaryAndInterprotocolSharedState(int fragment) throws Exception {
        start();var b=peer();var t=peer();byte[] value={0,13,10,(byte)255,1};
        b.fragmented(packet(1,ascii("shared"),value,integers(99,0),0,0x80000001),fragment);
        var stored=b.reply();stored.success();stored.header(1,0x80000001);
        t.send("mg shared v f c\r\n");assertEquals("VA 5 f99 c"+Long.toUnsignedString(stored.cas())+"\r\n",t.line());
        assertArrayEquals(value,t.bytes(5));assertArrayEquals(ascii("\r\n"),t.bytes(2));
        t.expect("ms shared 3 F8 E999\r\nnew\r\n","HD\r\n");
        var fetched=b.request(0,"shared",EMPTY,EMPTY,0);fetched.success();assertEquals(999,fetched.cas());assertArrayEquals(ascii("new"),fetched.value());
    }

    @Test void binaryKeysRoundTripViaMetaBase64() throws Exception {
        start();var b=peer();var t=peer();byte[] key={0,1,2};
        t.expect("ms AAEC 1 b E600\r\nz\r\n","HD\r\n");
        b.send(packet(12,key,EMPTY,EMPTY,0,77));var r=b.reply();r.success();assertArrayEquals(key,r.key());assertEquals(600,r.cas());
        t.expect("mg AAEC b v k c\r\n","VA 1 kAAEC b c600\r\nz\r\n");
    }

    @ParameterizedTest @ValueSource(ints={0,1,5,10})
    void invalidBinaryExtrasFailAndClose(int op) throws Exception {
        start();var b=peer();b.send(packet(op,op==10?EMPTY:ascii("k"),EMPTY,new byte[]{1},0,19));
        var r=b.reply();r.header(op,19);r.error(4,"Invalid arguments");assertEquals(-1,b.socket.getInputStream().read());
    }

    @Test void oversizeBinarySetInvalidatesAndDrains() throws Exception {
        start("item.maxSize","128");var b=peer();put(b,"k","old");
        b.request(17,"k",new byte[129],integers(0,0),0).error(3,"Too large.");b.barrier();
        b.request(0,"k",EMPTY,EMPTY,0).error(1,"Not found");
    }

    @Test void rawBinaryKeyOversizeInvalidatesBeforeDrainAndCannotDeleteNewWriter() throws Exception {
        start("item.maxSize","128");var rejected=peer();var writer=peer();byte[] key={0,32,1,(byte)255};
        rejected.send(packet(1,key,ascii("old"),integers(7,0),0,1));rejected.reply().success();
        byte[] frame=packet(1,key,new byte[129],integers(0,0),0,2);
        rejected.send(java.util.Arrays.copyOf(frame,24+8+key.length));
        boolean missing=false;long deadline=System.nanoTime()+2_000_000_000L;
        for(int i=0;i<100&&System.nanoTime()<deadline;i++) {
            writer.send(packet(0,key,EMPTY,EMPTY,0,3));var found=writer.reply();
            if(found.status()==1){found.error(1,"Not found");missing=true;break;}
            found.success();assertArrayEquals(ascii("old"),found.value());
        }
        assertTrue(missing,"header rejection invalidates raw binary key before body drain");
        writer.send(packet(1,key,ascii("new"),integers(8,0),0,4));long cas=writer.reply().cas();
        rejected.send(java.util.Arrays.copyOfRange(frame,24+8+key.length,frame.length));
        rejected.reply().error(3,"Too large.");rejected.barrier();
        writer.send(packet(0,key,EMPTY,EMPTY,0,5));var current=writer.reply();current.success();
        assertArrayEquals(ascii("new"),current.value());assertEquals(cas,current.cas());
    }

    @ParameterizedTest @ValueSource(ints={0,251})
    void binaryInvalidKeyLengthsAreRejected(int length) throws Exception {
        start();var b=peer();b.send(packet(0,new byte[length],EMPTY,EMPTY,0,17));
        b.reply().error(4,"Invalid arguments");assertEquals(-1,b.socket.getInputStream().read());
    }

    @Test void binaryMaximumKeyAndBodyGeometry() throws Exception {
        start();var b=peer();byte[] key=new byte[250];java.util.Arrays.fill(key,(byte)'x');
        b.send(packet(1,key,ascii("ok"),integers(0,0),0,1));b.reply().success();
        b.send(packet(12,key,EMPTY,EMPTY,0,2));var r=b.reply();r.success();assertArrayEquals(key,r.key());
        byte[] invalid=packet(0,ascii("k"),EMPTY,EMPTY,0,3);ByteBuffer.wrap(invalid).putInt(8,0);
        b.send(invalid);b.reply().error(0x81,"Unknown command");assertEquals(-1,b.socket.getInputStream().read());
    }

    @Test void metaUnknownFlagAndDuplicateModesNeverMutate() throws Exception {
        start();var t=peer();t.expect("ms k 3 E100\r\nold\r\n","HD\r\n");
        t.expect("ms k 3 F1 F2\r\nnew\r\nmn\r\n","CLIENT_ERROR duplicate flag\r\nMN\r\n");
        t.expect("mg k v c\r\n","VA 3 c100\r\nold\r\n");
        t.expect("mg k b\r\nmn\r\n","CLIENT_ERROR error decoding key\r\nMN\r\n");
    }

    @Test void metaMetadataFlagsConditionalGetAndTtlCas() throws Exception {
        start();var t=peer();
        t.expect("ms m 3 F7 E100 Oset k c s Proute Lroute\r\nold\r\n","HD Oset km c100 s3\r\n");
        t.expect("mg m v c f s t h l k Oget u Proute Lroute\r\n","VA 3 c100 f7 s3 t-1 h0 l0 km Oget\r\nold\r\n");
        t.expect("mg m v C100 T30 c\r\n","HD c100\r\n");
        t.expect("mg m T0 E101 c\r\n","HD c100\r\n");
        t.expect("mg m v C99\r\n","VA 3\r\nold\r\n");
        clock.seconds+=31;t.expect("mg m v\r\n","VA 3\r\nold\r\n");
    }

    @Test void metaVivifyRecacheAndStaleStateMachine() throws Exception {
        start();var t=peer();
        t.expect("mg viv N30 E400 v c\r\n","VA 0 c400 W\r\n\r\n");
        t.expect("mg viv N30 v c\r\n","VA 0 c400 Z\r\n\r\n");
        t.expect("ms rec 1 T30 E100\r\nx\r\nmg rec R60 v\r\nmg rec R60 v\r\n","HD\r\nVA 1 W\r\nx\r\nVA 1 Z\r\nx\r\n");
        t.expect("md rec I E200 T30 Odel\r\nmg rec v c\r\nmg rec v c\r\n","HD Odel\r\nVA 1 c200 X W\r\nx\r\nVA 1 c200 Z X\r\nx\r\n");
        t.expect("ms rec 3 C100 I E201 c\r\nnew\r\nmg rec v c\r\n","HD c201\r\nVA 3 c201 Z X\r\nnew\r\n");
        t.expect("md rec x E300\r\nmg rec v c s\r\n","HD\r\nVA 0 c300 s0\r\n\r\n");
    }

    @ParameterizedTest @ValueSource(strings={"E","R","A","P","S"})
    void metaStorageModesAndCasNegatives(String mode) throws Exception {
        start();var t=peer();
        if(mode.equals("R")||mode.equals("A")||mode.equals("P"))t.expect("ms k 1 M"+mode+"\r\nx\r\n","NS\r\n");
        t.expect("ms k 1 E100 F7\r\nm\r\n","HD\r\n");
        t.expect("ms k 1 M"+mode+" E200\r\nx\r\n",mode.equals("E")?"NS\r\n":"HD\r\n");
        String value=mode.equals("E")?"m":mode.equals("A")?"mx":mode.equals("P")?"xm":"x";
        t.expect("mg k v\r\n","VA "+value.length()+"\r\n"+value+"\r\n");
        t.expect("ms k 1 C999\r\nz\r\nms missing 1 C999\r\nz\r\n","EX\r\nNF\r\n");
    }

    @ParameterizedTest @ValueSource(strings={"A","P"})
    void metaAppendVivifyUsesNttl(String mode) throws Exception {
        start();var t=peer();t.expect("ms k 1 M"+mode+" N30 T1 F7\r\nx\r\n","HD\r\n");
        clock.seconds+=2;t.expect("mg k v\r\n","VA 1\r\nx\r\n");clock.seconds+=28;t.expect("mg k\r\n","EN\r\n");
    }

    @ParameterizedTest @ValueSource(strings={"I","+","D","-"})
    void metaArithmeticInitialModesCasAndExplicitVersion(String mode) throws Exception {
        start();var t=peer();t.expect("ma num N30 J9 D2 v c E500\r\n","VA 1 c500\r\n9\r\n");
        String value=mode.equals("I")||mode.equals("+")?"11":"7";
        t.expect("ma num D2 M"+mode+" v E501 c\r\n","VA "+value.length()+" c501\r\n"+value+"\r\n");
        t.expect("ma num C500 v\r\n","EX\r\n");
        t.expect("ma num T0 D0 t k Oa\r\n","HD t-1 knum Oa\r\n");
    }

    @Test void metaQuietRulesAndClassicPipeline() throws Exception {
        start();var t=peer();t.expect("ms k 1 q\r\nx\r\nmg absent q\r\nma n N0 J1 q\r\nmn\r\n","MN\r\n");
        t.expect("ms k 1 ME q\r\ny\r\nmd absent q\r\nma absent q\r\nmn\r\n","NS\r\nNF\r\nNF\r\nMN\r\n");
        t.expect("mg k v q\r\nget k\r\nmd k q\r\nmn\r\n","VA 1\r\nx\r\nVALUE k 0 1\r\nx\r\nEND\r\nMN\r\n");
    }

    @Test void metaDeleteCasAndDebug() throws Exception {
        start();var t=peer();t.expect("ms k 1 E100\r\nx\r\nmd k C99\r\n","HD\r\nEX\r\n");
        t.send("me k\r\n");String debug=t.line();assertTrue(debug.startsWith("ME k "));assertTrue(debug.contains("cas=100"));
        assertTrue(debug.contains("exp=-1"));assertTrue(debug.contains("cls="));assertTrue(debug.contains("size="));
        t.expect("md k C100 k Odel\r\nme k\r\n","HD kk Odel\r\nEN\r\n");
    }

    @ParameterizedTest @ValueSource(strings={"mg k v v q","mg k ! q","ma k Dnope q"})
    void metaErrorsRemainVisibleInQuietMode(String request) throws Exception {
        start();var t=peer();String error=request.startsWith("ma")?"invalid or duplicate flag"
                :request.contains("!")?"bad command line format":"duplicate flag";
        t.expect(request+"\r\nmn\r\n","CLIENT_ERROR "+error+"\r\nMN\r\n");
    }

    @Test void metaOpaqueLimitAndFragmentedPayload() throws Exception {
        start();var t=peer();t.fragmented(ascii("ms k 3 E700 O"+"a".repeat(32)+"\r\nx\r\n\r\n"),1);
        assertEquals("HD O"+"a".repeat(32)+"\r\n",t.line());
        t.expect("mg k v\r\n","VA 3\r\nx\r\n\r\n");
        t.expect("mg k O"+"a".repeat(33)+"\r\nmn\r\n","CLIENT_ERROR opaque token too long\r\nMN\r\n");
    }

    @Test void concurrentMetaVivifyHasOneWinner() throws Exception {
        start();var a=peer();var b=peer();var barrier=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{barrier.await(2,java.util.concurrent.TimeUnit.SECONDS);a.send("mg race N30 c\r\n");return a.line();});
            var second=pool.submit(()->{barrier.await(2,java.util.concurrent.TimeUnit.SECONDS);b.send("mg race N30 c\r\n");return b.line();});
            String one=first.get(5,java.util.concurrent.TimeUnit.SECONDS),two=second.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1,(one.contains(" W")?1:0)+(two.contains(" W")?1:0));
            assertEquals(1,(one.contains(" Z")?1:0)+(two.contains(" Z")?1:0));
            assertEquals(one.split(" ")[1],two.split(" ")[1],"both clients see the same vivified CAS");
        }
    }

    @ParameterizedTest @ValueSource(strings={"binary","debug"})
    void nonMetaReadsCannotStealStaleRecacheWinner(String reader) throws Exception {
        start();var meta=peer();meta.expect("ms k 1 E100\r\nx\r\nmd k I E200\r\n","HD\r\nHD\r\n");
        if(reader.equals("binary")){var b=peer();b.request(0,"k",EMPTY,EMPTY,0).success();}
        else{meta.send("me k\r\n");assertTrue(meta.line().startsWith("ME k "));}
        meta.expect("mg k v c\r\n","VA 1 c200 X W\r\nx\r\n");
    }

    @Test void crossProtocolConcurrentCasHasExactlyOneWinner() throws Exception {
        start();var binary=peer();var text=peer();long cas=put(binary,"race","old");
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{barrier.await(2,java.util.concurrent.TimeUnit.SECONDS);return binary.request(1,"race",ascii("bin"),integers(7,0),cas).status();});
            var second=pool.submit(()->{barrier.await(2,java.util.concurrent.TimeUnit.SECONDS);text.send("ms race 4 C"+Long.toUnsignedString(cas)+"\r\nmeta\r\n");return text.line();});
            int binaryStatus=first.get(5,java.util.concurrent.TimeUnit.SECONDS);
            String metaStatus=second.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(binaryStatus==0||binaryStatus==2);assertTrue(metaStatus.equals("HD\r\n")||metaStatus.equals("EX\r\n"));
            assertEquals(1,(binaryStatus==0?1:0)+(metaStatus.equals("HD\r\n")?1:0));
            var current=binary.request(0,"race",EMPTY,EMPTY,0);current.success();
            assertArrayEquals(ascii(binaryStatus==0?"bin":"meta"),current.value());assertNotEquals(cas,current.cas());
        }
    }

    @Test void tinyChunksStreamLargeValuesAndCancellationReleasesOwners() throws Exception {
        start("protocol.maxChunkBytes","2","connections.pendingWriteBytes","2");
        var binary=peer();var meta=peer();String value="a".repeat(4096);
        put(binary,"large",value);var read=binary.request(0,"large",EMPTY,EMPTY,0);read.success();assertArrayEquals(ascii(value),read.value());
        meta.send("mg large v\r\n");assertEquals("VA 4096\r\n",meta.line());assertArrayEquals(ascii(value),meta.bytes(4096));assertArrayEquals(ascii("\r\n"),meta.bytes(2));
        binary.send(packet(0,ascii("large"),EMPTY,EMPTY,0,11));binary.close();
        var partial=peer();byte[] incomplete=packet(1,ascii("partial"),new byte[1024],integers(0,0),0,12);
        partial.send(java.util.Arrays.copyOf(incomplete,40));partial.close();
        meta.expect("mn\r\n","MN\r\n"); // unrelated connection still makes progress
    }

    @ParameterizedTest @ValueSource(ints={2,18})
    void binaryAddMatchingCasReplacesExistingValue(int op) throws Exception {
        start();var b=peer();long cas=put(b,"k","old");
        b.send(packet(op,ascii("k"),ascii("new"),integers(9,0),cas,77));
        if(op==2)b.reply().success();b.barrier();
        var r=b.request(0,"k",EMPTY,EMPTY,0);r.success();assertArrayEquals(ascii("new"),r.value());assertNotEquals(cas,r.cas());
    }

    @Test void metaOlderCasInvalidationPreservesOriginalTtl() throws Exception {
        start();var t=peer();t.expect("ms k 3 E200 T30\r\nold\r\nms k 3 C100 I E201 T0\r\nnew\r\n","HD\r\nHD\r\n");
        t.expect("mg k t c\r\n","HD t30 c201 X W\r\n");
        clock.seconds+=30;t.expect("mg k\r\n","EN\r\n");
    }

    @Test void metaEmptyTombstoneResetsFlagsAndTtlButKeepsCas() throws Exception {
        start();var t=peer();t.expect("ms k 3 E200 F7 T30\r\nold\r\nmd k x\r\n","HD\r\nHD\r\n");
        t.expect("mg k v f t c\r\n","VA 0 f0 t-1 c200\r\n\r\n");
        clock.seconds+=31;t.expect("mg k v\r\n","VA 0\r\n\r\n");
    }

    @Test void nativeSetPressureEvictsUnrelatedKeyBeforeRejectingTarget() throws Exception {
        start("memory.limit","65536","item.maxSize","32768");var b=peer();String large="a".repeat(30000);
        put(b,"other",large);put(b,"target",large);
        b.request(1,"target",ascii(large),integers(9,0),0).success();
        var r=b.request(0,"target",EMPTY,EMPTY,0);r.success();assertArrayEquals(integers(9),r.extras());
        b.request(0,"other",EMPTY,EMPTY,0).error(1,"Not found");
    }

    @Test void nativeOomRejectsHeaderBeforeBodyAndPreservesSupersedingWriter() throws Exception {
        start("memory.limit","32768","item.maxSize","32768");var rejected=peer();var writer=peer();
        put(rejected,"k","o".repeat(30000));byte[] frame=packet(1,ascii("k"),new byte[30000],integers(0,0),0,31);
        rejected.send(java.util.Arrays.copyOf(frame,33));
        boolean absent=false;long deadline=System.nanoTime()+2_000_000_000L;
        for(int i=0;i<100&&System.nanoTime()<deadline;i++){var r=writer.request(0,"k",EMPTY,EMPTY,0);if(r.status()==1){absent=true;break;}r.success();}
        assertTrue(absent,"native SET admission must invalidate before payload arrives");
        long fresh=put(writer,"k","fresh");rejected.send(java.util.Arrays.copyOfRange(frame,33,frame.length));
        rejected.reply().error(130,"Out of memory allocating item");rejected.barrier();
        var now=writer.request(0,"k",EMPTY,EMPTY,0);now.success();assertEquals(fresh,now.cas());assertArrayEquals(ascii("fresh"),now.value());
    }

    @Test void itemLimitUsesAlignedFullRecordNotOnlyPayload() throws Exception {
        start("item.maxSize","129");var b=peer();put(b,"k","old");
        b.request(1,"k",new byte[96],integers(0,0),0).error(3,"Too large.");
        b.request(0,"k",EMPTY,EMPTY,0).error(1,"Not found");
        b.request(1,"k",new byte[95],integers(0,0),0).success();
    }

    @Test void staleFlagOnlyAppliesToOlderCasNotEqualCas() throws Exception {
        start();var t=peer();t.expect("ms k 3 E200 T30\r\nold\r\nms k 3 C200 I E201 T0\r\nnew\r\n","HD\r\nHD\r\n");
        t.expect("mg k v c t\r\n","VA 3 c201 t-1\r\nnew\r\n");
    }

    @Test void explicitTombstoneFlagsTtlAndCasAreHonored() throws Exception {
        start();var t=peer();t.expect("ms k 3 E200 F7 T30\r\nold\r\nmd k x E300 F9 T10\r\n","HD\r\nHD\r\n");
        t.expect("mg k v f t c\r\n","VA 0 f9 t10 c300\r\n\r\n");clock.seconds+=10;t.expect("mg k\r\n","EN\r\n");
    }

    @Test void binaryCounterMissingCasStillCreatesAndAppendCasMissIsNotStored() throws Exception {
        start();var b=peer();var created=b.request(5,"n",EMPTY,arithmetic(2,9,0),123);created.success();assertEquals(9,ByteBuffer.wrap(created.value()).getLong());
        b.request(14,"absent",ascii("x"),EMPTY,123).error(5,"Not stored.");
        var r=b.request(4,"n",EMPTY,EMPTY,0);r.success();
    }

    @Test void metaSetIgnoresNAndArithmeticHonorsTWithTokenOrder() throws Exception {
        start();var t=peer();t.expect("ms k 1 N1 E100\r\nx\r\n","HD\r\n");clock.seconds+=2;
        t.expect("mg k v t\r\n","VA 1 t-1\r\nx\r\n");
        t.expect("ma n N30 J9 T10 t v\r\n","VA 1 t10\r\n9\r\n");
        t.expect("ma n D0 t T20\r\n","HD t10\r\n");
        t.expect("ma n D0 T40 t\r\n","HD t40\r\n");
    }

    @Test void debugDoesNotMarkItemFetched() throws Exception {
        start();var t=peer();t.expect("ms k 1 E100\r\nx\r\n","HD\r\n");t.send("me k\r\n");assertTrue(t.line().contains("fetch=no"));
        t.expect("mg k h u c\r\n","HD h0 c100\r\n");t.expect("mg k h u\r\n","HD h0\r\n");
    }

    @ParameterizedTest @ValueSource(strings={"mg k Tnope q","mg k Cnope q","mg k Enope q","mg k Nnope q","mg k Rnope q"})
    void metaMalformedNumericFlagsAreNeverSilentlyIgnored(String command) throws Exception {
        start();var t=peer();t.expect(command+"\r\nmn\r\n","CLIENT_ERROR bad token in command line format\r\nMN\r\n");
    }

    @Test void metaGetTtlResponseAndVivifyRespectFlagOrder()throws Exception {
        start();var t=peer();t.expect("ms k 1 T30\r\nx\r\nmg k t T120\r\nmg k T60 t\r\n","HD\r\nHD t30\r\nHD t60\r\n");
        t.expect("mg a T120 N30 t\r\nmg b N30 T120 t\r\n","HD t30 W\r\nHD t120 W\r\n");
    }
    @ParameterizedTest @ValueSource(strings={"ME","MR","MA","MP","C100"})
    void metaInitialOversizeInvalidatesAllStorageModes(String flags)throws Exception {
        start("item.maxSize","128");var t=peer();t.expect("ms k 3 E100\r\nold\r\n","HD\r\n");
        t.expect("ms k 129 "+flags+" q\r\n"+"x".repeat(129)+"\r\nmg k\r\n","SERVER_ERROR object too large for cache\r\nEN\r\n");
    }
    @ParameterizedTest @ValueSource(strings={"T+1","N000000000000","C00000000000000000000000","E+1"})
    void metaNumericTokensMatchPinnedLexicalLimits(String flag)throws Exception {
        start();var t=peer();t.expect("mg k "+flag+" q\r\nmn\r\n","CLIENT_ERROR bad token in command line format\r\nMN\r\n");
    }
    @Test void metaBareMinusTtlMeansZero()throws Exception {
        start();var t=peer();t.expect("ms k 1 T30\r\nx\r\nmg k T- t\r\n","HD\r\nHD t-1\r\n");
    }
    @ParameterizedTest @ValueSource(strings={"items","slabs","reset","bogus"})
    void binaryStatsSubcommandsHaveBoundedPacketSequence(String command)throws Exception {
        start();var b=peer();put(b,"k","value");b.send(packet(16,ascii(command),EMPTY,EMPTY,0,77));
        var first=b.reply();first.header(16,77);
        if(command.equals("bogus")){first.error(1,"Not found");return;}
        first.success();int packets=1;
        while(first.key().length!=0&&packets<256){first=b.reply();first.success();first.header(16,77);packets++;}
        assertEquals(0,first.key().length);assertEquals(0,first.value().length);if(!command.equals("reset"))assertTrue(packets>1);b.barrier();
    }
    @Test void metaArithmeticNumericFailureAndUnknownModeStayVisible()throws Exception{
        start();var t=peer();t.expect("ms k 3\r\nabc\r\nma k q\r\nmn\r\n","HD\r\nCLIENT_ERROR cannot increment or decrement non-numeric value\r\nMN\r\n");
        t.expect("ms k 1 MZ\r\nx\r\nmg k v\r\n","CLIENT_ERROR bad command line format\r\nVA 3\r\nabc\r\n");
    }
    @ParameterizedTest @ValueSource(ints={5,6})
    void binaryArithmeticOomHasOperationSpecificPayload(int opcode)throws Exception{
        start("memory.limit","32768","item.maxSize","32768");var b=peer();put(b,"n","0".repeat(31999)+"9");
        b.request(opcode,"n",EMPTY,arithmetic(1,0,-1),0).error(130,"Out of memory incrementing value");b.barrier();
    }
    @ParameterizedTest @ValueSource(strings={"ma n D1","mg n T30","md n I"})
    void metaNativeFailureRetainsTargetAndUsesCorrectError(String command)throws Exception{
        start("memory.limit","32768","item.maxSize","32768");var b=peer();put(b,"n","0".repeat(31999)+"9");var t=peer();
        t.expect(command+"\r\nmn\r\n",(command.startsWith("ma")?"SERVER_ERROR out of memory\r\n":"SERVER_ERROR out of memory storing object\r\n")+"MN\r\n");
        b.request(0,"n",EMPTY,EMPTY,0).success();
    }
    @Test void fetchedMetadataReflectsRealRetrievalAndBinaryKeyMarkerPersists()throws Exception{
        start();var t=peer();t.expect("ms k 1\r\nx\r\nmg k v\r\nmg k h\r\n","HD\r\nVA 1\r\nx\r\nHD h1\r\n");t.send("me k\r\n");assertTrue(t.line().contains("fetch=yes"));
        t.expect("ms azE= 1 b\r\nx\r\nmg k1 k v\r\n","HD\r\nVA 1 kazE= b\r\nx\r\n");
    }
    @Test void metaReplaceMissingNeverVivifiesWithN()throws Exception{
        start();var t=peer();t.expect("ms absent 1 MR N30\r\nx\r\nmg absent\r\n","NS\r\nEN\r\n");
    }
    @Test void metaReplaceWithOlderCasAndIUsesStaleCasStoreSemantics()throws Exception{
        start();var t=peer();t.expect("ms existing 3 E200 T30\r\nold\r\nms existing 3 MR C100 I E201 T0\r\nnew\r\nmg existing v c t\r\n",
                "HD\r\nHD\r\nVA 3 c201 t30 X W\r\nnew\r\n");
        clock.seconds+=30;t.expect("mg existing\r\n","EN\r\n");
    }
    @ParameterizedTest @ValueSource(longs={100,200,300})
    void metaAddIgnoresCasComparisonForBothExistenceBranches(long token)throws Exception{
        start();var t=peer();t.expect("ms existing 1 E200\r\nx\r\nms existing 1 ME C"+token+"\r\ny\r\nmg existing v c\r\n",
                "HD\r\nNS\r\nVA 1 c200\r\nx\r\n");
        t.expect("ms absent 1 ME C"+token+" E500\r\ny\r\nmg absent v c\r\n","HD\r\nVA 1 c500\r\ny\r\n");
    }
    @ParameterizedTest @ValueSource(strings={"A","P"})
    void metaConcatVivificationIgnoresCasOnAbsentTarget(String mode)throws Exception{
        start();var t=peer();for(long token:new long[]{100,200,300}){
            String key="key"+token;t.expect("ms "+key+" 1 M"+mode+" N30 C"+token+" E600\r\nz\r\nmg "+key+" v c t\r\n","HD\r\nVA 1 c600 t30\r\nz\r\n");
        }
    }
    @Test void metaInvalidatedEmptyTombstoneBumpsCasUnlikePlainTombstone()throws Exception{
        start();var t=peer();t.expect("ms k 1 E200\r\nx\r\nmd k x I\r\n","HD\r\nHD\r\n");
        t.send("mg k c s f t\r\n");String response=t.line();assertTrue(response.startsWith("HD c"));assertTrue(response.endsWith(" s0 f0 t-1 X W\r\n"));
        String token=response.split(" ")[1].substring(1);assertNotEquals("200",token);Long.parseUnsignedLong(token);
    }
    private static final class TestClock implements Clock {
        volatile long seconds=1_700_000_000L;
        public long currentTimeSeconds(){return seconds;}
        public long nanoTime(){return System.nanoTime();}
    }
}

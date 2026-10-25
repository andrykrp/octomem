package ru.octomem.server;

import java.io.ByteArrayOutputStream;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.octomem.cli.CacheServer;
import ru.octomem.config.ConfigLoader;
import static org.junit.jupiter.api.Assertions.*;

/** Independent real UDP and Unix-domain lifecycle tests; platform limitations explicit. */
@Timeout(20)
class ExpandedLocalTransportTest {
    @TempDir Path temp;
    CacheServer server;int udpPort;Path trace;
    @BeforeEach void evidence(TestInfo info)throws Exception{
        Path root=Path.of(System.getProperty("local.evidence","tests/reports/protocol-expansion-20261025/local-transports"),"wire");Files.createDirectories(root);
        trace=root.resolve(info.getTestMethod().orElseThrow().getName()+"-"+UUID.randomUUID()+".txt");Files.writeString(trace,"CASE "+info.getDisplayName()+"\n");
    }
    static byte[] b(String s){return s.getBytes(StandardCharsets.US_ASCII);}
    Map<String,String> config(){return new HashMap<>(Map.of("transport.workers","1","memory.limit","4194304","item.maxSize","1048576","connections.max","8"));}
    void startUdp()throws Exception{
        try(DatagramSocket reserve=new DatagramSocket(new InetSocketAddress("127.0.0.1",0))){udpPort=reserve.getLocalPort();}
        var c=config();c.put("listen.udpPort",Integer.toString(udpPort));server=new CacheServer(ConfigLoader.load(null,c),new InetSocketAddress("127.0.0.1",0));server.start();
    }
    DatagramSocket udp()throws Exception{DatagramSocket s=new DatagramSocket(new InetSocketAddress("127.0.0.1",0));s.setSoTimeout(2000);return s;}
    byte[] packet(int id,int seq,int total,int reserved,byte[] body){return ByteBuffer.allocate(8+body.length).putShort((short)id).putShort((short)seq).putShort((short)total).putShort((short)reserved).put(body).array();}
    void send(DatagramSocket s,byte[] bytes)throws Exception{Files.writeString(trace,"SEND "+HexFormat.of().formatHex(bytes)+"\n",StandardOpenOption.APPEND);s.send(new DatagramPacket(bytes,bytes.length,InetAddress.getLoopbackAddress(),udpPort));}
    byte[] receive(DatagramSocket s,int id)throws Exception{
        Map<Integer,byte[]> fragments=new TreeMap<>();int count=-1;
        do{byte[] bytes=new byte[65535];DatagramPacket p=new DatagramPacket(bytes,bytes.length);s.receive(p);assertEquals(udpPort,p.getPort());assertTrue(p.getLength()>=8&&p.getLength()<=1400);
            byte[] data=Arrays.copyOf(bytes,p.getLength());Files.writeString(trace,"RECV "+HexFormat.of().formatHex(data)+"\n",StandardOpenOption.APPEND);ByteBuffer h=ByteBuffer.wrap(data);
            assertEquals(id,Short.toUnsignedInt(h.getShort()));int seq=Short.toUnsignedInt(h.getShort()),total=Short.toUnsignedInt(h.getShort());assertEquals(0,h.getShort());assertTrue(total>0&&total<=754);if(count<0)count=total;else assertEquals(count,total);assertTrue(seq<count);assertNull(fragments.put(seq,Arrays.copyOfRange(data,8,data.length)),"duplicate UDP fragment");
        }while(fragments.size()<count);
        ByteArrayOutputStream output=new ByteArrayOutputStream();for(byte[] data:fragments.values())output.writeBytes(data);return output.toByteArray();
    }
    byte[] exchange(DatagramSocket s,int id,String command)throws Exception{send(s,packet(id,0,1,0,b(command)));return receive(s,id);}
    Socket tcp()throws Exception{Socket s=new Socket();s.connect(server.localAddress(),2000);s.setSoTimeout(2000);return s;}
    static String line(Socket s)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();for(int i=0;i<8192;i++){int c=s.getInputStream().read();assertNotEquals(-1,c);out.write(c);if(c=='\n')return out.toString(StandardCharsets.US_ASCII);}throw new AssertionError("line limit");}
    @AfterEach void close()throws Exception{if(server!=null){server.close();var r=server.resourceUsage();assertAll(()->assertEquals(0,r.nativeUsedBytes()),()->assertEquals(0,r.bodyBytes()),()->assertEquals(0,r.controlBytes()),()->assertEquals(0,r.lineBytes()),()->assertEquals(0,r.currentConnections()));}}

    @Test void udpClassicMetaBinaryShareTcpEngine()throws Exception{
        startUdp();try(var udp=udp();var tcp=tcp()){
            assertArrayEquals(b("STORED\r\n"),exchange(udp,65535,"set shared 7 0 1\r\nx\r\n"));tcp.getOutputStream().write(b("get shared\r\n"));assertEquals("VALUE shared 7 1\r\n",line(tcp));assertEquals("x\r\n",line(tcp));assertEquals("END\r\n",line(tcp));
            assertArrayEquals(b("VA 1\r\nx\r\n"),exchange(udp,2,"mg shared v\r\n"));
            byte[] binary=ByteBuffer.allocate(30).put((byte)128).put((byte)0).putShort((short)6).putInt(0).putInt(6).putInt(0x89abcdef).putLong(0).put(b("shared")).array();send(udp,packet(3,0,1,0,binary));ByteBuffer r=ByteBuffer.wrap(receive(udp,3));assertEquals(129,Byte.toUnsignedInt(r.get()));assertEquals(0,r.get());assertEquals(0,r.getShort());assertEquals(4,r.get());assertEquals(0,r.get());assertEquals(0,r.getShort());assertEquals(5,r.getInt());assertEquals(0x89abcdef,r.getInt());assertNotEquals(0,r.getLong());assertEquals(7,r.getInt());assertEquals('x',r.get());
        }
    }
    @Test void udpFragmentsLargeValueExactly()throws Exception{
        startUdp();String value="x".repeat(6000);try(var tcp=tcp();var udp=udp()){
            tcp.getOutputStream().write(b("set large 7 0 6000\r\n"+value+"\r\n"));assertEquals("STORED\r\n",line(tcp));assertArrayEquals(b("VALUE large 7 6000\r\n"+value+"\r\nEND\r\n"),exchange(udp,17,"get large\r\n"));
        }
    }
    @ParameterizedTest @ValueSource(ints={1,65535})
    void udpSequenceIsIgnoredForSinglePacketLikePinnedReference(int sequence)throws Exception{
        startUdp();try(var udp=udp()){send(udp,packet(99,sequence,1,7,b("version\r\n")));assertArrayEquals(b("VERSION 1.0.0 (octomem)\r\n"),receive(udp,99));}
    }
    @Test void quietAndMalformedPacketsCannotContaminateFollowingRequest()throws Exception{
        startUdp();try(var udp=udp()){
            send(udp,new byte[]{1,2,3});send(udp,packet(1,0,2,0,b("set forbidden 0 0 1\r\nx\r\n")));send(udp,packet(2,0,1,0,b("set broken 0 0 3\r\nx")));
            send(udp,packet(3,0,1,0,b("set quiet 0 0 1 noreply\r\nz\r\n")));assertArrayEquals(b("VERSION 1.0.0 (octomem)\r\n"),exchange(udp,4,"version\r\n"));
            assertArrayEquals(b("VALUE quiet 0 1\r\nz\r\nEND\r\n"),exchange(udp,5,"get forbidden broken quiet\r\n"));
        }
    }
    @Test void responseCapReturnsOnlyBoundedErrorNotPartialSuccess()throws Exception{
        startUdp();String value="x".repeat(600000);try(var tcp=tcp();var udp=udp()){
            tcp.getOutputStream().write(b("set large 0 0 600000\r\n"+value+"\r\n"));assertEquals("STORED\r\n",line(tcp));
            assertArrayEquals(b("SERVER_ERROR UDP response limit or invalid request\r\n"),exchange(udp,77,"mg large v\r\nmg large v\r\n"));assertArrayEquals(b("VERSION 1.0.0 (octomem)\r\n"),exchange(udp,78,"version\r\n"));
        }
    }
    @Test void binaryResponseCapKeepsProtocolOpcodeAndOpaque()throws Exception{
        startUdp();String value="x".repeat(600000);try(var tcp=tcp();var udp=udp()){
            tcp.getOutputStream().write(b("set large 0 0 600000\r\n"+value+"\r\n"));assertEquals("STORED\r\n",line(tcp));
            byte[] get=ByteBuffer.allocate(29).put((byte)128).put((byte)0).putShort((short)5).putInt(0).putInt(5).putInt(0x89abcdef).putLong(0).put(b("large")).array();byte[] pipeline=ByteBuffer.allocate(get.length*2).put(get).put(get).array();
            send(udp,packet(88,0,1,0,pipeline));ByteBuffer response=ByteBuffer.wrap(receive(udp,88));assertEquals(129,Byte.toUnsignedInt(response.get()));assertEquals(0,response.get());assertEquals(0,response.getShort());assertEquals(0,response.getShort());assertEquals(130,Short.toUnsignedInt(response.getShort()));int length=response.getInt();assertEquals(0x89abcdef,response.getInt());assertEquals(0,response.getLong());assertEquals(length,response.remaining());assertTrue(length>0);
        }
    }
    @Test void metaZeroLengthMinusTokenMatchesExistingParser()throws Exception{
        startUdp();try(var udp=udp()){assertArrayEquals(b("HD\r\n"),exchange(udp,20,"ms empty -\r\n\r\n"));assertArrayEquals(b("VA 0\r\n\r\n"),exchange(udp,21,"mg empty v\r\n"));}
    }
    @ParameterizedTest @ValueSource(strings={"set","meta","binary"})
    void truncatedDatagramCannotInvalidateExistingValueBeforeCompleteBody(String mode)throws Exception{
        startUdp();try(var udp=udp();var tcp=tcp()){
            tcp.getOutputStream().write(b("set protected 7 0 3\r\nold\r\n"));assertEquals("STORED\r\n",line(tcp));
            byte[] truncated=switch(mode){case "set"->b("set protected 0 0 1048577\r\nx");case "meta"->b("ms protected 1048577\r\nx");
                default->ByteBuffer.allocate(24+8+9+1).put((byte)128).put((byte)1).putShort((short)9).put((byte)8).put((byte)0).putShort((short)0).putInt(1048577+17).putInt(9).putLong(0).putLong(0).put(b("protected")).put((byte)'x').array();};
            send(udp,packet(50,0,1,0,truncated));send(udp,packet(51,0,1,0,b("version\r\n")));
            // Rejected incomplete frames may produce one bounded error or silence;
            // the following request id proves the worker processed the boundary.
            for(int i=0;i<2;i++){byte[] data=new byte[1400];DatagramPacket response=new DatagramPacket(data,data.length);udp.receive(response);int id=Short.toUnsignedInt(ByteBuffer.wrap(data).getShort());if(id==51){assertArrayEquals(b("VERSION 1.0.0 (octomem)\r\n"),Arrays.copyOfRange(data,8,response.getLength()));break;}assertEquals(50,id);assertTrue(i==0);}
            tcp.getOutputStream().write(b("get protected\r\n"));assertEquals("VALUE protected 7 3\r\n",line(tcp));assertEquals("old\r\n",line(tcp));assertEquals("END\r\n",line(tcp));
        }
    }
    @ParameterizedTest @ValueSource(strings={"-1","0","65536","not-port"})
    void invalidUdpPortFailsBeforeBind(String port){var c=config();c.put("listen.udpPort",port);assertThrows(Exception.class,()->{try(var invalid=new CacheServer(ConfigLoader.load(null,c),new InetSocketAddress("127.0.0.1",0))){invalid.start();}});}
    @ParameterizedTest @ValueSource(strings={"external","auth","tls","unix"})
    void udpCannotExposeExternalOrBypassProtectedListener(String mode)throws Exception{
        var c=config();c.put("listen.udpPort","11223");String bind="127.0.0.1";
        switch(mode){case "external"->bind="0.0.0.0";case "auth"->{Path f=temp.resolve("users");Files.writeString(f,"synthetic:synthetic\n");c.put("auth.mode","text");c.put("auth.file",f.toString());}case "tls"->{c.put("tls.keyStore",temp.resolve("missing.p12").toString());c.put("tls.keyStorePasswordFile",temp.resolve("missing-secret").toString());}case "unix"->c.put("listen.unixSocket",temp.resolve("mix.sock").toString());}
        String address=bind;assertThrows(Exception.class,()->{try(var invalid=new CacheServer(ConfigLoader.load(null,c),new InetSocketAddress(address,0))){invalid.start();}});
    }
    @Test void occupiedUdpPortFailsStartupWithoutRetainingTcpListener()throws Exception{
        try(DatagramSocket occupied=new DatagramSocket(new InetSocketAddress("127.0.0.1",0))){var c=config();c.put("listen.udpPort",Integer.toString(occupied.getLocalPort()));server=new CacheServer(ConfigLoader.load(null,c),new InetSocketAddress("127.0.0.1",0));assertThrows(Exception.class,server::start);server.close();assertEquals(0,server.resourceUsage().controlBytes());}
    }
    @Test void unixExistingRegularPathIsNeverRemoved()throws Exception{
        Path socket=temp.resolve("existing.sock");Files.writeString(socket,"user-owned");var c=config();c.put("listen.unixSocket",socket.toString());assertThrows(Exception.class,()->{try(var invalid=new CacheServer(ConfigLoader.load(null,c),new InetSocketAddress("127.0.0.1",0))){invalid.start();}});assertEquals("user-owned",Files.readString(socket));
    }
    @Test void unixSupportedLifecycleOrExplicitUnsupportedFailure()throws Exception{
        Path socket=temp.resolve("live.sock");boolean supported;
        try(var probe=ServerSocketChannel.open(StandardProtocolFamily.UNIX)){supported=true;}catch(UnsupportedOperationException unavailable){supported=false;}
        var c=config();c.put("listen.unixSocket",socket.toString());
        if(!supported){assertThrows(Exception.class,()->{try(var invalid=new CacheServer(ConfigLoader.load(null,c),new InetSocketAddress("127.0.0.1",0))){invalid.start();}});Files.writeString(trace,"UNIX_FUNCTIONAL_NOT_RUN_PLATFORM_UNSUPPORTED; fail-closed tested\n",StandardOpenOption.APPEND);return;}
        server=new CacheServer(ConfigLoader.load(null,c),new InetSocketAddress("127.0.0.1",0));server.start();assertTrue(Files.exists(socket));
        try(SocketChannel peer=SocketChannel.open(StandardProtocolFamily.UNIX)){peer.connect(UnixDomainSocketAddress.of(socket));peer.write(ByteBuffer.wrap(b("version\r\n")));ByteBuffer result=ByteBuffer.allocate(b("VERSION 1.0.0 (octomem)\r\n").length);while(result.hasRemaining())assertTrue(peer.read(result)>0);assertArrayEquals(b("VERSION 1.0.0 (octomem)\r\n"),result.array());}
        server.close();assertTrue(Files.exists(socket),"ADR022: operator owns stale socket entry; server never unlinks");Files.delete(socket);
    }
    @Test void checkConfigRejectsInsecureLocalTransportCombinationsWithoutBinding()throws Exception{
        for(String settings:List.of("listen.address=0.0.0.0\nlisten.udpPort=11223\n","listen.unixSocket=relative.sock\n","listen.unixSocket="+temp.resolve("mix.sock")+"\nlisten.udpPort=11223\n")){
            Path config=temp.resolve("invalid-"+UUID.randomUUID()+".properties");Files.writeString(config,settings.replace("\\","/"));
            ByteArrayOutputStream out=new ByteArrayOutputStream(),err=new ByteArrayOutputStream();int code=ru.octomem.cli.Main.run(new String[]{"--config="+config,"--check-config"},new java.io.PrintStream(out),new java.io.PrintStream(err));assertNotEquals(0,code,"check-config must enforce startup transport security constraints");
        }
    }
    @ParameterizedTest @ValueSource(strings={"version", "set", "set\r\n", "ms k\r\n", "set k 0 0 nope\r\n", "set k 0 0 -1\r\n", "set k 0 0 1\r\nxXX", "set k 0 0 1\r\nx\rX", "ms k 3\r\nx"})
    void invalidDatagramFramingDropsBeforeNextValidRequest(String malformed)throws Exception{
        startUdp();try(var udp=udp()){send(udp,packet(1,0,1,0,b(malformed)));assertArrayEquals(b("VERSION 1.0.0 (octomem)\r\n"),exchange(udp,2,"version\r\n"));}
    }
    @Test void emptyTruncatedBinaryAndQuitDatagramsDoNotRetainState()throws Exception{
        startUdp();try(var udp=udp()){
            send(udp,packet(1,0,1,0,new byte[0]));send(udp,packet(2,0,1,0,new byte[]{(byte)128,0}));
            byte[] invalid=ByteBuffer.allocate(48).put((byte)128).put((byte)10).putShort((short)0).putInt(0).putInt(0).putInt(0).putLong(0).put(new byte[24]).array();send(udp,packet(3,0,1,0,invalid));
            send(udp,packet(4,0,1,0,b("quit\r\n")));assertArrayEquals(b("VERSION 1.0.0 (octomem)\r\n"),exchange(udp,5,"version\r\n"));
        }
    }
    @Test void completeInvalidCommandReturnsProtocolErrorNotWorkerFailure()throws Exception{
        startUdp();try(var udp=udp()){
            assertArrayEquals(b("CLIENT_ERROR bad command line format\r\n"),exchange(udp,9,"touch k 0 wrong\r\n"));assertArrayEquals(b("VERSION 1.0.0 (octomem)\r\n"),exchange(udp,10,"version\r\n"));
        }
    }
}

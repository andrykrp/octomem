package ru.octomem.server;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.octomem.cli.CacheServer;
import ru.octomem.config.ConfigLoader;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic-only actual TCP/TLS authentication tests; never logs credential payloads. */
@Timeout(30)
class ExpandedSecurityTest {
    @TempDir static Path material;
    private static final String USER="synthetic-user";
    private static final String PASS=UUID.randomUUID().toString();
    private static final String STORE_PASS=UUID.randomUUID().toString();
    private static Path credentials,store,passwordFile;
    private static SSLContext trust;
    private CacheServer server;
    private final List<Socket> sockets=new ArrayList<>();

    @BeforeAll static void certificates()throws Exception {
        credentials=material.resolve("users.txt");Files.writeString(credentials,USER+":"+PASS+"\n");
        passwordFile=material.resolve("store-password.txt");Files.writeString(passwordFile,STORE_PASS);
        store=material.resolve("localhost.p12");
        Path keytool=Path.of(System.getProperty("java.home"),"bin","keytool.exe");
        Process p=new ProcessBuilder(keytool.toString(),"-genkeypair","-alias","test-localhost","-keyalg","RSA","-keysize","2048",
                "-validity","2","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-storetype","PKCS12",
                "-keystore",store.toString(),"-storepass:file",passwordFile.toString(),"-noprompt")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if(!p.waitFor(20,TimeUnit.SECONDS)){p.destroyForcibly();p.waitFor(5,TimeUnit.SECONDS);fail("bounded synthetic keytool timeout");}
        assertEquals(0,p.exitValue(),"synthetic keytool generation");
        KeyStore keys=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(store)){keys.load(in,STORE_PASS.toCharArray());}
        KeyStore roots=KeyStore.getInstance("PKCS12");roots.load(null,null);roots.setCertificateEntry("test-localhost",keys.getCertificate("test-localhost"));
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(roots);
        trust=SSLContext.getInstance("TLS");trust.init(null,tm.getTrustManagers(),null);
    }
    private Map<String,String> config(String mode,boolean tls){
        Map<String,String> c=new HashMap<>(Map.of("transport.workers","2","memory.limit","4194304","item.maxSize","4096","connections.max","16"));
        if(!mode.equals("none")){c.put("auth.mode",mode);c.put("auth.file",credentials.toString());}
        if(tls){c.put("tls.keyStore",store.toString());c.put("tls.keyStorePasswordFile",passwordFile.toString());}return c;
    }
    private void start(String mode,boolean tls)throws Exception{server=new CacheServer(ConfigLoader.load(null,config(mode,tls)),new InetSocketAddress("127.0.0.1",0));server.start();}
    private Socket plain()throws Exception{Socket s=new Socket();s.connect(server.localAddress(),2000);s.setSoTimeout(3000);sockets.add(s);return s;}
    private SSLSocket tls(String version)throws Exception{
        SSLSocket s=(SSLSocket)trust.getSocketFactory().createSocket();s.setEnabledProtocols(new String[]{version});
        SSLParameters parameters=s.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");s.setSSLParameters(parameters);
        s.connect(new InetSocketAddress("localhost",server.localAddress().getPort()),2000);s.setSoTimeout(3000);sockets.add(s);s.startHandshake();assertEquals(version,s.getSession().getProtocol());return s;
    }
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.US_ASCII);}
    private static void send(Socket s,String request)throws Exception{s.getOutputStream().write(bytes(request));s.getOutputStream().flush();}
    private static String line(Socket s)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();for(int i=0;i<8192;i++){int c=s.getInputStream().read();assertNotEquals(-1,c,"unexpected EOF");b.write(c);if(c=='\n')return b.toString(StandardCharsets.US_ASCII);}throw new AssertionError("line limit");}
    private static void textAuth(Socket s,boolean correct)throws Exception{
        String data=USER+" "+(correct?PASS:"incorrect");send(s,"set auth-token 0 0 "+bytes(data).length+"\r\n"+data+"\r\n");
        if(correct)assertEquals("STORED\r\n",line(s));else assertTrue(line(s).startsWith("CLIENT_ERROR"));
    }
    private static byte[] sasl(){return bytes("\0"+USER+"\0"+PASS);}
    private static Reply binary(Socket s,int op,String key,byte[] data,byte[] extra)throws Exception{
        s.getOutputStream().write(ExpandedWire.packet(op,bytes(key),data,extra,0,0x89abcdef));s.getOutputStream().flush();
        byte[] header=s.getInputStream().readNBytes(24);assertEquals(24,header.length);ByteBuffer h=ByteBuffer.wrap(header);
        assertEquals(129,Byte.toUnsignedInt(h.get()));assertEquals(op,Byte.toUnsignedInt(h.get()));int k=Short.toUnsignedInt(h.getShort()),e=Byte.toUnsignedInt(h.get());h.get();int status=Short.toUnsignedInt(h.getShort()),n=h.getInt();assertEquals(0x89abcdef,h.getInt());h.getLong();
        assertTrue(n>=0&&n<65536);byte[] body=s.getInputStream().readNBytes(n);assertEquals(n,body.length);return new Reply(status,Arrays.copyOfRange(body,e+k,n));
    }
    private record Reply(int status,byte[] data){}
    @AfterEach void close()throws Exception{
        try{for(Socket s:sockets)s.close();}finally{if(server!=null){server.close();var r=server.resourceUsage();assertAll(()->assertEquals(0,r.nativeUsedBytes()),()->assertEquals(0,r.bodyBytes()),()->assertEquals(0,r.controlBytes()),()->assertEquals(0,r.lineBytes()),()->assertEquals(0,r.currentConnections()));}}
    }

    @Test void textAuthenticationIsolationFailureAndReconnect()throws Exception{
        start("text",false);Socket authorized=plain();textAuth(authorized,true);send(authorized,"set protected 0 0 3\r\nold\r\n");assertEquals("STORED\r\n",line(authorized));
        Socket denied=plain();textAuth(denied,false);send(denied,"set protected 0 0 3 noreply\r\nbad\r\n");assertTrue(line(denied).startsWith("CLIENT_ERROR"));
        send(authorized,"get protected auth-token\r\n");assertEquals("VALUE protected 0 3\r\n",line(authorized));assertEquals("old\r\n",line(authorized));assertEquals("END\r\n",line(authorized));
        textAuth(denied,true);send(denied,"version\r\n");assertEquals("VERSION 1.0.0 (octomem)\r\n",line(denied));denied.close();
        Socket reconnected=plain();send(reconnected,"get protected\r\n");assertTrue(line(reconnected).startsWith("CLIENT_ERROR"));
    }
    @Test void textCredentialsBoundIsIndependentOfTinyCacheItemLimit()throws Exception{
        String longUser="u".repeat(100),longPass="p".repeat(200);Path local=material.resolve("long-users.txt");Files.writeString(local,longUser+":"+longPass+"\n");
        Map<String,String> settings=config("text",false);settings.put("item.maxSize","128");settings.put("auth.file",local.toString());
        server=new CacheServer(ConfigLoader.load(null,settings),new InetSocketAddress("127.0.0.1",0));server.start();Socket s=plain();
        String body=longUser+" "+longPass;send(s,"set auth 0 0 "+body.length()+"\r\n"+body+"\r\n");assertEquals("STORED\r\n",line(s));send(s,"version\r\n");assertEquals("VERSION 1.0.0 (octomem)\r\n",line(s));
    }
    @Test void saslMechanismQuietDenialAndPerConnectionState()throws Exception{
        start("sasl",false);Socket a=plain();Reply list=binary(a,32,"",new byte[0],new byte[0]);assertEquals(0,list.status);assertArrayEquals(bytes("PLAIN"),list.data);
        assertEquals(32,binary(a,17,"hidden",bytes("bad"),ExpandedWire.integers(0,0)).status);
        a.close();a=plain(); // Upstream closes unauthenticated cache-operation connections.
        assertEquals(0,binary(a,33,"PLAIN",sasl(),new byte[0]).status);
        assertEquals(1,binary(a,0,"hidden",new byte[0],new byte[0]).status);
        assertEquals(0,binary(a,1,"k",bytes("value"),ExpandedWire.integers(0,0)).status);
        Socket b=plain();assertEquals(32,binary(b,0,"k",new byte[0],new byte[0]).status);b.close();b=plain();
        assertEquals(32,binary(b,33,"UNKNOWN",sasl(),new byte[0]).status);
        assertEquals(32,binary(b,34,"PLAIN",sasl(),new byte[0]).status);
        assertEquals(32,binary(b,33,"PLAIN",bytes("malformed"),new byte[0]).status);
        assertEquals(0,binary(b,33,"PLAIN",sasl(),new byte[0]).status);
        assertArrayEquals(bytes("value"),binary(b,0,"k",new byte[0],new byte[0]).data);
    }
    @ParameterizedTest @ValueSource(strings={"TLSv1.2","TLSv1.3"})
    void tlsTextAuthPipelineAndIndependentClients(String version)throws Exception{
        start("text",true);SSLSocket a=tls(version);textAuth(a,true);send(a,"ms k 1 q\r\nx\r\nmg k v\r\nmn\r\n");
        assertEquals("VA 1\r\n",line(a));assertEquals("x\r\n",line(a));assertEquals("MN\r\n",line(a));
        SSLSocket b=tls(version);send(b,"get k\r\n");assertTrue(line(b).startsWith("CLIENT_ERROR"));textAuth(b,true);send(b,"get k\r\n");assertEquals("VALUE k 0 1\r\n",line(b));assertEquals("x\r\n",line(b));assertEquals("END\r\n",line(b));
    }
    @Test void tlsSaslBinaryOperations()throws Exception{
        start("sasl",true);Socket s=tls("TLSv1.3");assertEquals(0,binary(s,33,"PLAIN",sasl(),new byte[0]).status);
        assertEquals(0,binary(s,1,"k",bytes("x"),ExpandedWire.integers(1,0)).status);assertArrayEquals(bytes("x"),binary(s,0,"k",new byte[0],new byte[0]).data);
    }
    @Test void saslUnauthenticatedVersionIsAllowed()throws Exception{
        start("sasl",false);Socket s=plain();Reply version=binary(s,11,"",new byte[0],new byte[0]);assertEquals(0,version.status);assertArrayEquals(bytes("1.0.0 (octomem)"),version.data);
    }
    @ParameterizedTest @ValueSource(ints={0,7,17,23})
    void unauthorizedBinaryOperationsHaveExactErrorAndClose(int op)throws Exception{
        start("sasl",false);Socket s=plain();Reply denied=binary(s,op,op==7||op==23?"":"k",op==17?bytes("x"):new byte[0],op==17?ExpandedWire.integers(0,0):new byte[0]);
        assertEquals(32,denied.status);assertArrayEquals(bytes("Auth failure."),denied.data);assertEquals(-1,s.getInputStream().read());
    }
    @Test void malformedSaslListCannotSucceed()throws Exception{
        start("sasl",false);Socket s=plain();Reply bad=binary(s,32,"",new byte[0],new byte[]{1});assertEquals(4,bad.status);assertArrayEquals(bytes("Invalid arguments"),bad.data);assertEquals(-1,s.getInputStream().read());
    }
    @ParameterizedTest @ValueSource(strings={"TLSv1.2","TLSv1.3"})
    void tlsQuitDrainsEncryptedResponseBeforeEof(String version)throws Exception{
        start("none",true);Socket s=tls(version);assertEquals(0,binary(s,7,"",new byte[0],new byte[0]).status);assertEquals(-1,s.getInputStream().read());
    }
    @ParameterizedTest @ValueSource(strings={"TLSv1.2","TLSv1.3"})
    void tlsClassicQuitFlushesEarlierPipelinedReply(String version)throws Exception {
        start("none",true);Socket s=tls(version);send(s,"version\r\nquit\r\n");assertEquals("VERSION 1.0.0 (octomem)\r\n",line(s));assertEquals(-1,s.getInputStream().read());
    }
    @ParameterizedTest @ValueSource(ints={33,34})
    void wrongSaslExchangeHasPinnedExactFailureText(int op)throws Exception {
        start("sasl",false);Socket s=plain();Reply denied=binary(s,op,"PLAIN",bytes("\0synthetic-user\0wrong"),new byte[0]);assertEquals(32,denied.status);assertArrayEquals(bytes("Auth failure."),denied.data);
    }
    @ParameterizedTest @ValueSource(ints={32,33,34})
    void saslDisabledReturnsUnknownCommandRatherThanAuthenticate(int op)throws Exception{
        start("none",false);Socket s=plain();Reply denied=binary(s,op,op==32?"":"PLAIN",op==32?new byte[0]:sasl(),new byte[0]);assertEquals(129,denied.status);assertArrayEquals(bytes("Unknown command"),denied.data);
    }
    @Test void malformedFakeSetAuthFailsClosedAndNeverPublishes()throws Exception{
        start("text",false);Socket s=plain();send(s,"set auth invalid 0 3\r\n");assertEquals("CLIENT_ERROR authentication required\r\n",line(s));assertEquals(-1,s.getInputStream().read());
    }
    @Test void authenticationPayloadAboveOwnCapIsDeniedEvenWhenItemLimitAllowsIt()throws Exception{
        start("text",false);Socket s=plain();send(s,"set auth 0 0 1281\r\n"+"x".repeat(1281)+"\r\n");assertEquals("CLIENT_ERROR authentication required\r\n",line(s));assertEquals(-1,s.getInputStream().read());
    }
    @Test void textFakeSetCannotAuthenticateBinaryOnlySaslMode()throws Exception{
        start("sasl",false);Socket s=plain();String body=USER+" "+PASS;send(s,"set auth 0 0 "+body.length()+"\r\n"+body+"\r\n");assertEquals("CLIENT_ERROR authentication failure\r\n",line(s));
    }
    @Test void plaintextAndUntrustedTlsCannotDowngrade()throws Exception{
        start("none",true);Socket clear=plain();send(clear,"version\r\n");
        try{int first=clear.getInputStream().read();assertNotEquals((int)'V',first,"TLS listener must not return plaintext VERSION");}catch(SocketException closed){/* rejection */}
        SSLSocket untrusted=(SSLSocket)SSLContext.getDefault().getSocketFactory().createSocket("localhost",server.localAddress().getPort());sockets.add(untrusted);untrusted.setSoTimeout(3000);
        assertThrows(SSLException.class,untrusted::startHandshake);
        Socket good=tls("TLSv1.3");send(good,"version\r\n");assertEquals("VERSION 1.0.0 (octomem)\r\n",line(good));
    }
    @Test void incompleteHandshakeEofDoesNotPoisonNextClient()throws Exception{
        start("none",true);Socket partial=plain();partial.getOutputStream().write(new byte[]{22,3,3,0});partial.close();
        Socket good=tls("TLSv1.2");send(good,"version\r\n");assertEquals("VERSION 1.0.0 (octomem)\r\n",line(good));
    }
    @ParameterizedTest @ValueSource(strings={"empty-users","missing-users","invalid-mode","external-plaintext","wrong-store-password","missing-store","certificate-only"})
    void invalidSecurityConfigurationFailsBeforeBinding(String fault)throws Exception{
        Map<String,String> c=config("text",false);String bind="127.0.0.1";
        switch(fault){
            case "empty-users"->{Path p=material.resolve("empty.txt");Files.writeString(p,"");c.put("auth.file",p.toString());}
            case "missing-users"->c.put("auth.file",material.resolve("absent.txt").toString());
            case "invalid-mode"->c.put("auth.mode","unknown");
            case "external-plaintext"->bind="0.0.0.0";
            case "wrong-store-password"->{c=config("none",true);Path p=material.resolve("wrong.txt");Files.writeString(p,"wrong-synthetic");c.put("tls.keyStorePasswordFile",p.toString());}
            case "missing-store"->{c=config("none",true);c.put("tls.keyStore",material.resolve("absent.p12").toString());}
            case "certificate-only"->{c=config("none",true);KeyStore keys=KeyStore.getInstance("PKCS12");keys.load(null,null);Path p=material.resolve("empty.p12");try(var out=Files.newOutputStream(p)){keys.store(out,STORE_PASS.toCharArray());}c.put("tls.keyStore",p.toString());}
        }
        final Map<String,String> candidate=c;final String address=bind;
        Class<? extends Exception> expected=fault.equals("invalid-mode")?ru.octomem.config.ConfigException.class:IllegalArgumentException.class;
        assertThrows(expected,()->{try(CacheServer unexpected=new CacheServer(ConfigLoader.load(null,candidate),new InetSocketAddress(address,0))){unexpected.start();}});
    }
}

package ru.octomem.transport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** Immutable startup-only security material. No secrets are exposed in messages or stats. */
public final class TransportSecurity {
    private final String mode;
    private final Map<String,byte[]> users;
    private final SSLContext tls;
    private TransportSecurity(String mode,Map<String,byte[]> users,SSLContext tls){this.mode=mode;this.users=Map.copyOf(users);this.tls=tls;}
    public static TransportSecurity none(){return new TransportSecurity("none",Map.of(),null);}
    public String mode(){return mode;}
    public SSLContext tls(){return tls;}
    /** Side-effect-free validation shared by CLI preflight and actual startup. */
    public static void validateLocalTransports(Map<String,String> config,boolean loopback){
        String unix=config.get("listen.unixSocket"),udp=config.get("listen.udpPort");
        if(unix!=null && (!Path.of(unix).isAbsolute() || config.containsKey("tls.keyStore"))
                || udp!=null && (unix!=null || config.containsKey("tls.keyStore") || !config.getOrDefault("auth.mode","none").equals("none") || !loopback))
            throw new IllegalArgumentException("Invalid local transport security configuration");
    }
    /** Loads bounded local files before any listener is opened; invalid state fails closed. */
    public static TransportSecurity load(Map<String,String> config,boolean loopback) {
        validateLocalTransports(config,loopback);
        String mode=config.getOrDefault("auth.mode","none");
        String credentials=config.getOrDefault("auth.file","");
        String store=config.getOrDefault("tls.keyStore","");
        String secret=config.getOrDefault("tls.keyStorePasswordFile","");
        if(!java.util.Set.of("none","sasl","text").contains(mode) || mode.equals("none")!=credentials.isEmpty()
                || store.isEmpty()!=secret.isEmpty() || !mode.equals("none") && store.isEmpty() && !loopback)
            throw new IllegalArgumentException("Invalid security configuration");
        try {
            Map<String,byte[]> users=new HashMap<>();
            if(!credentials.isEmpty()) {
                byte[] bytes=read(Path.of(credentials),65536);
                try {
                    for(String line:StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().split("\\R")) {
                        if(line.isEmpty() || line.startsWith("#"))continue;
                        int colon=line.indexOf(':');
                        if(colon<1 || colon==line.length()-1 || colon>128 || line.length()>1153)throw new IOException();
                        String name=line.substring(0,colon);
                        if(line.substring(colon+1).getBytes(StandardCharsets.UTF_8).length>1024 || name.chars().anyMatch(c->c<=32 || c>=127) || users.putIfAbsent(name,
                                hash(line.substring(colon+1).getBytes(StandardCharsets.UTF_8)))!=null)throw new IOException();
                    }
                } finally{Arrays.fill(bytes,(byte)0);}
                if(users.isEmpty())throw new IOException();
            }
            SSLContext context=null;
            if(!store.isEmpty()) {
                byte[] passBytes=read(Path.of(secret),4096);
                String passwordText=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(passBytes)).toString();
                if(passwordText.endsWith("\n"))passwordText=passwordText.substring(0,passwordText.length()-1);
                if(passwordText.endsWith("\r"))passwordText=passwordText.substring(0,passwordText.length()-1);
                char[] password=passwordText.toCharArray();
                Arrays.fill(passBytes,(byte)0);
                try {
                    KeyStore keys=KeyStore.getInstance("PKCS12");
                    byte[] encoded=read(Path.of(store),4*1024*1024);
                    try(var input=new java.io.ByteArrayInputStream(encoded)){keys.load(input,password);}
                    finally{Arrays.fill(encoded,(byte)0);}
                    boolean privateKey=false;var aliases=keys.aliases();while(aliases.hasMoreElements())privateKey|=keys.isKeyEntry(aliases.nextElement());
                    if(!privateKey)throw new GeneralSecurityException();
                    KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,password);
                    context=SSLContext.getInstance("TLS");context.init(km.getKeyManagers(),null,null);
                    context.getServerSessionContext().setSessionCacheSize(1024);context.getServerSessionContext().setSessionTimeout(300);
                }finally{Arrays.fill(password,'\0');}
            }
            return new TransportSecurity(mode,users,context);
        }catch(IOException|GeneralSecurityException|RuntimeException failure){throw new IllegalArgumentException("Unable to initialize transport security");}
    }
    private static byte[] read(Path path,int limit)throws IOException{
        try(var in=Files.newInputStream(path)){byte[] bytes=in.readNBytes(limit+1);if(bytes.length==0 || bytes.length>limit)throw new IOException();return bytes;}
    }
    private static byte[] hash(byte[] bytes){
        try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(GeneralSecurityException impossible){throw new IllegalStateException();}
        finally{Arrays.fill(bytes,(byte)0);}
    }
    /** Verifies bounded synthetic or supplied credentials without logging either component. */
    public boolean authenticate(byte[] payload,boolean sasl){
        if(payload==null || payload.length>1280)return false;
        int start=0,split=-1;
        if(sasl){while(start<payload.length && payload[start]!=0)start++;if(start==payload.length)return false;start++;}
        for(int i=start;i<payload.length;i++)if(payload[i]==(sasl?0:' ')){split=i;break;}
        if(split<=start || split-start>128 || payload.length-split-1>1024)return false;
        String user=new String(payload,start,split-start,StandardCharsets.UTF_8);
        byte[] candidate=hash(Arrays.copyOfRange(payload,split+1,payload.length));
        byte[] expected=users.get(user);
        boolean equal=MessageDigest.isEqual(candidate,expected==null?new byte[32]:expected);
        Arrays.fill(candidate,(byte)0);return expected!=null && equal;
    }
}

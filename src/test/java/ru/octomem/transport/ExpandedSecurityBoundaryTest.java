package ru.octomem.transport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Startup bounds and credential parsing use only disposable synthetic material. */
class ExpandedSecurityBoundaryTest {
    @TempDir Path temp;
    TransportSecurity load(byte[] data)throws Exception{Path file=temp.resolve("users");Files.write(file,data);return TransportSecurity.load(Map.of("auth.mode","sasl","auth.file",file.toString()),true);}
    @ParameterizedTest @ValueSource(strings={"", "# comments only\n", "no-colon", ":secret", "user:", "bad user:secret", "üser:secret", "same:a\nsame:b"})
    void malformedFilesFailClosedWithoutSecretInException(String content)throws Exception{
        IllegalArgumentException failure=assertThrows(IllegalArgumentException.class,()->load(content.getBytes(StandardCharsets.UTF_8)));assertEquals("Unable to initialize transport security",failure.getMessage());assertNull(failure.getCause());
    }
    @Test void boundedFilesStrictUtf8AndFieldLengths()throws Exception{
        for(byte[] invalid:new byte[][]{new byte[]{(byte)0xc3,0x28},new byte[65537],("u".repeat(129)+":x").getBytes(),("u:"+"x".repeat(1025)).getBytes(),("u:"+"é".repeat(513)).getBytes(StandardCharsets.UTF_8)})assertThrows(IllegalArgumentException.class,()->load(invalid));
    }
    @Test void validBoundaryCredentialsAndMalformedPayloads()throws Exception{
        String user="u".repeat(128),pass="p".repeat(1024);TransportSecurity security=load(("#comment\n\n"+user+":"+pass+"\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(security.authenticate((user+" "+pass).getBytes(StandardCharsets.UTF_8),false));assertTrue(security.authenticate(("\0"+user+"\0"+pass).getBytes(StandardCharsets.UTF_8),true));
        assertFalse(security.authenticate(null,true));assertFalse(security.authenticate(new byte[1281],true));
        for(String invalid:new String[]{"missingseparator","\0", "\0\0x", "\0"+"u".repeat(129)+"\0x", "\0u\0"+"p".repeat(1025),"\0unknown\0wrong"})assertFalse(security.authenticate(invalid.getBytes(StandardCharsets.UTF_8),true));
        assertFalse(security.authenticate((user+" wrong").getBytes(StandardCharsets.UTF_8),false));
    }
    @Test void securityOptionPairsCannotDisableAuthenticationSilently(){
        for(Map<String,String> config:java.util.List.of(Map.of("auth.mode","invalid"),Map.of("auth.mode","text"),Map.of("auth.file","unused"),Map.of("tls.keyStore","unused"),Map.of("tls.keyStorePasswordFile","unused")))assertThrows(IllegalArgumentException.class,()->TransportSecurity.load(config,true));
        TransportSecurity none=TransportSecurity.none();assertEquals("none",none.mode());assertNull(none.tls());assertFalse(none.authenticate("u p".getBytes(),false));
    }
}

package ru.octomem.acceptance;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Expanded capability declarations are additive and never imply release acceptance. */
class ExpandedManifestIT {
    private static JsonObject manifest()throws Exception{return new GsonBuilder().setStrictness(Strictness.STRICT).create().fromJson(Files.readString(Path.of("compatibility-manifest.json")),JsonObject.class);}
    private static void exact(JsonObject root,String field,Set<String> expected){
        assertTrue(root.has(field),field);var array=root.getAsJsonArray(field);Set<String> values=new HashSet<>();
        array.forEach(v->assertTrue(values.add(v.getAsString()),"duplicate "+field));assertEquals(expected,values);
    }
    private static void validate(JsonObject root){
        assertEquals("text",root.get("protocol").getAsString(),"legacy classic profile remains separately frozen");
        exact(root,"protocolFamilies",Set.of("text","binary","meta"));exact(root,"metaCommands",Set.of("mg","ms","md","ma","me","mn"));
        exact(root,"securityModes",Set.of("none","sasl-plain","text-auth","tls-1.2","tls-1.3"));
        assertTrue(root.has("expandedProfile"));JsonObject profile=root.getAsJsonObject("expandedProfile");
        assertEquals("implemented-under-independent-verification",profile.get("status").getAsString());assertFalse(profile.get("releaseAcceptance").getAsBoolean());
        assertEquals("Memcached 1.6.45 core; no Couchbase/TAP extensions",profile.get("reference").getAsString());
        assertEquals("docs/memcached-protocols-and-security.md",profile.get("specification").getAsString());
        assertEquals("tests/reports/protocol-expansion-20261025",profile.get("evidenceDirectory").getAsString());
    }
    @Test void expandedClaimsAreExactAndDoNotClaimReleaseAcceptance()throws Exception{
        JsonObject root=manifest();validate(root);assertTrue(Files.isRegularFile(Path.of(root.getAsJsonObject("expandedProfile").get("specification").getAsString())));
    }
    @Test void missingDuplicateUnsupportedAndAcceptanceClaimsAreRejected()throws Exception{
        JsonObject original=manifest();for(String field:Set.of("protocolFamilies","metaCommands","securityModes")){
            JsonObject missing=original.deepCopy();missing.remove(field);assertThrows(AssertionError.class,()->validate(missing));
            JsonObject duplicate=original.deepCopy();duplicate.getAsJsonArray(field).add(duplicate.getAsJsonArray(field).get(0));assertThrows(AssertionError.class,()->validate(duplicate));
            JsonObject extra=original.deepCopy();extra.getAsJsonArray(field).add("unsupported-universal-extension");assertThrows(AssertionError.class,()->validate(extra));
        }
        JsonObject accepted=original.deepCopy();accepted.getAsJsonObject("expandedProfile").addProperty("releaseAcceptance",true);assertThrows(AssertionError.class,()->validate(accepted));
        JsonObject status=original.deepCopy();status.getAsJsonObject("expandedProfile").addProperty("status","PASS");assertThrows(AssertionError.class,()->validate(status));
    }
}

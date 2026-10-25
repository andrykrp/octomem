package ru.octomem.acceptance;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Checks the declared local-transport contract without converting evidence to release approval. */
class LocalTransportManifestIT {
    private static JsonObject manifest() throws Exception {
        return new GsonBuilder().setStrictness(Strictness.STRICT).create()
                .fromJson(Files.readString(Path.of("compatibility-manifest.json")), JsonObject.class);
    }

    private static void booleanClaim(JsonObject object, String name, boolean expected) {
        JsonElement value = object.get(name);
        assertNotNull(value, name);
        assertTrue(value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean(), name);
        assertEquals(expected, value.getAsBoolean(), name);
    }

    private static void validate(JsonObject manifest) {
        JsonElement transports = manifest.get("transports");
        assertNotNull(transports);
        assertTrue(transports.isJsonArray());
        Set<String> names = new HashSet<>();
        for (JsonElement transport : transports.getAsJsonArray()) {
            assertTrue(transport.isJsonPrimitive() && transport.getAsJsonPrimitive().isString());
            assertTrue(names.add(transport.getAsString()), "Duplicate transport declaration");
        }
        assertEquals(Set.of("tcp", "unix-domain", "udp-loopback"), names);
        assertTrue(manifest.has("udpProfile") && manifest.get("udpProfile").isJsonObject());
        JsonObject udp = manifest.getAsJsonObject("udpProfile");
        booleanClaim(udp, "enabledByDefault", false);
        booleanClaim(udp, "loopbackOnly", true);
        booleanClaim(udp, "authenticationSupported", false);
        for (var expected : Map.of("maxResponseBytes", "1048576",
                "datagramPayloadBytes", "1392", "requestPackets", "1").entrySet()) {
            JsonElement value = udp.get(expected.getKey());
            assertNotNull(value, expected.getKey());
            assertTrue(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber());
            assertEquals(expected.getValue(), value.getAsString(), expected.getKey());
        }
        assertTrue(manifest.has("expandedProfile") && manifest.get("expandedProfile").isJsonObject());
        booleanClaim(manifest.getAsJsonObject("expandedProfile"), "releaseAcceptance", false);
    }

    @Test void exactLocalTransportDeclarationsMatchTheBoundedVerifiedProfile() throws Exception {
        validate(manifest());
    }

    @Test void missingDuplicateAndUnknownTransportsAreRejected() throws Exception {
        JsonObject original = manifest();
        JsonObject missing = original.deepCopy();
        missing.remove("transports");
        assertThrows(AssertionError.class, () -> validate(missing));
        JsonObject omitted = original.deepCopy();
        omitted.getAsJsonArray("transports").remove(0);
        assertThrows(AssertionError.class, () -> validate(omitted));
        for (String extra : Set.of("tcp", "udp-external", "universal-transport")) {
            JsonObject changed = original.deepCopy();
            changed.getAsJsonArray("transports").add(extra);
            assertThrows(AssertionError.class, () -> validate(changed));
        }
    }

    @Test void weakenedSecurityLimitsAndPrematureAcceptanceAreRejected() throws Exception {
        JsonObject original = manifest();
        for (String name : Set.of("enabledByDefault", "loopbackOnly", "authenticationSupported")) {
            JsonObject changed = original.deepCopy();
            JsonObject udp = changed.getAsJsonObject("udpProfile");
            udp.addProperty(name, !udp.get(name).getAsBoolean());
            assertThrows(AssertionError.class, () -> validate(changed), name);
        }
        for (String name : Set.of("maxResponseBytes", "datagramPayloadBytes", "requestPackets")) {
            for (int delta : new int[]{-1, 1}) {
                JsonObject changed = original.deepCopy();
                JsonObject udp = changed.getAsJsonObject("udpProfile");
                udp.addProperty(name, udp.get(name).getAsInt() + delta);
                assertThrows(AssertionError.class, () -> validate(changed), name);
            }
        }
        JsonObject omitted = original.deepCopy();
        omitted.remove("udpProfile");
        assertThrows(AssertionError.class, () -> validate(omitted));
        JsonObject accepted = original.deepCopy();
        accepted.getAsJsonObject("expandedProfile").addProperty("releaseAcceptance", true);
        assertThrows(AssertionError.class, () -> validate(accepted));
        JsonObject coerced = original.deepCopy();
        coerced.getAsJsonObject("udpProfile").addProperty("loopbackOnly", "true");
        assertThrows(AssertionError.class, () -> validate(coerced));
    }
}

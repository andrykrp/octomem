package ru.octomem.acceptance;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/** Checks exact documented compatibility claims and locally retained reference provenance. */
class CompatibilityManifestIT {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final Set<String> COMMANDS = Set.of("get", "gets", "gat", "gats", "set", "add", "replace", "append", "prepend", "cas", "delete", "incr", "decr", "touch", "flush_all", "stats", "stats items", "stats slabs", "stats reset", "version", "verbosity", "quit");
    private static final Set<String> STATS = Set.of("pid", "uptime", "time", "version", "pointer_size", "threads", "curr_connections", "total_connections", "connection_structures", "conn_yields", "cmd_get", "cmd_set", "cmd_flush", "cmd_touch", "get_hits", "get_misses", "delete_hits", "delete_misses", "incr_hits", "incr_misses", "decr_hits", "decr_misses", "cas_hits", "cas_misses", "cas_badval", "touch_hits", "touch_misses", "curr_items", "total_items", "bytes", "reclaimed", "expired_unfetched", "evicted_unfetched", "limit_maxbytes", "evictions", "bytes_read", "bytes_written");
    private static final Set<String> ITEMS = Set.of("items:N:number", "items:N:age", "items:N:evicted", "items:N:evicted_nonzero", "items:N:evicted_time", "items:N:outofmemory", "items:N:tailrepairs", "items:N:reclaimed", "items:N:expired_unfetched", "items:N:evicted_unfetched", "items:N:crawler_reclaimed");
    private static final Set<String> SLABS = Set.of("N:chunk_size", "N:chunks_per_page", "N:total_pages", "N:total_chunks", "N:used_chunks", "N:free_chunks", "N:free_chunks_end", "N:get_hits", "N:cmd_set", "N:delete_hits", "N:incr_hits", "N:decr_hits", "N:cas_hits", "N:cas_badval", "N:mem_requested");
    private static final String CALIBRATION_RAW_PATH = "tests/reports/release/perf-qual/rosa-20260918-6pairs/attempt-9c62c1250095424eac29376414587680/calibration.json";

    @Test
    void exactMandatoryProfileIsFrozenAndNonPlaceholder() throws Exception {
        JsonObject manifest = manifest();
        validateProfile(manifest);
        assertTrue(string(manifest, "octomemVersion").matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?"));
        assertTrue(string(manifest, "referenceMemcachedVersion").matches("[0-9]+\\.[0-9]+\\.[0-9]+"));
        assertTrue(string(manifest, "versionString").contains("octomem"));
        assertTrue(string(manifest, "versionString").contains(string(manifest, "octomemVersion")));
        assertTrue(string(manifest, "referenceSourceSha256").matches("[a-fA-F0-9]{64}"));
        assertFalse(string(manifest, "referenceSourceSha256").matches("(.)\\1{63}"), "Synthetic checksum");
        assertTrue(manifest.getAsJsonArray("knownDifferences").size() >= 11, "Preserve every docs/04 section 9 difference");
        for (JsonElement element : manifest.getAsJsonArray("knownDifferences")) {
            for (String key : Set.of("difference", "reason", "status")) {
                string(element.getAsJsonObject(), key);
            }
        }
        String differences = manifest.getAsJsonArray("knownDifferences").toString().toLowerCase(Locale.ROOT);
        for (String marker : Set.of("binary", "meta", "stats sizes", "stats conns", "stats cachedump", "udp", "sasl", "watch", "lru_crawler", "cache_memlimit", "shutdown", "repeated keys", "delete", "accepted and ignored", "single lf", "8192", "octomem")) {
            assertTrue(differences.contains(marker), "Missing documented difference: " + marker);
        }
    }

    @Test
    void missingExtraDuplicateAndPlaceholderClaimsAreRejected() throws Exception {
        JsonObject original = manifest();
        JsonObject missing = original.deepCopy();
        missing.getAsJsonArray("commands").remove(0);
        assertThrows(AssertionError.class, () -> validateProfile(missing));
        JsonObject extra = original.deepCopy();
        extra.getAsJsonArray("commands").add("mg");
        assertThrows(AssertionError.class, () -> validateProfile(extra));
        JsonObject duplicate = original.deepCopy();
        duplicate.getAsJsonArray("commands").add("get");
        assertThrows(AssertionError.class, () -> validateProfile(duplicate));
        for (String placeholder : Set.of("latest", "to-be-selected", "", "TBD")) {
            JsonObject invalid = original.deepCopy();
            invalid.addProperty("referenceMemcachedVersion", placeholder);
            assertThrows(AssertionError.class, () -> validateProfile(invalid));
        }
        for (String field : Set.of("statsFields", "statsItemsFields", "statsSlabsFields")) {
            JsonObject invalid = original.deepCopy();
            invalid.getAsJsonArray(field).remove(0);
            assertThrows(AssertionError.class, () -> validateProfile(invalid), "Missing mandatory " + field);
        }
    }

    @Test
    void referenceArchiveDigestAndVersionMatchAuthenticRetainedSource() throws Exception {
        JsonObject manifest = manifest();
        String version = string(manifest, "referenceMemcachedVersion");
        String name = "memcached-" + version + ".tar.gz";
        assertEquals(name, string(manifest, "referenceSourceArtifact"));
        assertEquals("https://www.memcached.org/files/" + name, string(manifest, "referenceSourceUrl"));
        assertEquals("https://github.com/memcached/memcached/blob/" + version + "/doc/protocol.txt", string(manifest, "protocolNormativeReference"));
        Path archive = FoundationChecks.ROOT.resolve(".tools/archives").resolve(name);
        assertTrue(Files.isRegularFile(archive), "Official reference archive must be retained for offline verification");
        assertEquals(manifest.get("referenceSourceSizeBytes").getAsLong(), Files.size(archive));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)));
        assertEquals(string(manifest, "referenceSourceSha256").toLowerCase(Locale.ROOT), digest);
        string(manifest, "referenceSourceSha256Provenance");
        Path extracted = FoundationChecks.ROOT.resolve(".tools/memcached-src/memcached-" + version);
        assertTrue(Files.readString(extracted.resolve("version.m4")).contains("[" + version + "]"), "Reference version evidence disagrees");
        assertTrue(Files.size(extracted.resolve("doc/protocol.txt")) > 1000, "Missing upstream protocol evidence");
        // Compare evidence with bytes inside the hashed archive, not an unrelated extracted tree.
        for (String relative : Set.of("version.m4", "doc/protocol.txt")) {
            assertArrayEquals(Files.readAllBytes(extracted.resolve(relative)),
                    tarEntry(archive, "memcached-" + version + "/" + relative));
        }
    }

    @Test
    void platformTargetsAreNotMisrepresentedAsTestedPlatforms() throws Exception {
        JsonArray platforms = manifest().getAsJsonArray("platforms");
        assertNotNull(platforms);
        assertFalse(platforms.isEmpty());
        for (JsonElement element : platforms) {
            JsonObject platform = element.getAsJsonObject();
            assertEquals("OpenJDK 25", string(platform, "javaPlatform"));
            assertEquals(25, platform.get("bytecodeRelease").getAsInt());
            assertTrue(string(platform, "verificationStatus").contains("not-yet-verified"),
                    "T-001 target selection is not platform certification");
        }
    }

    @Test
    void platformIdentityAndTrustedOsObligationsAreExplicit() throws Exception {
        JsonObject manifest = manifest();
        validatePlatformRequirements(manifest);
        for (JsonElement value : manifest.getAsJsonArray("platforms")) {
            JsonObject platform = value.getAsJsonObject();
            assertEquals("Windows", string(platform, "osName"));
            assertEquals("11", string(platform, "osVersion"));
            assertEquals("amd64", string(platform, "cpuArchitecture"));
            assertTrue(string(platform, "jdkVersion").matches("25\\.[0-9]+\\.[0-9]+(?:\\.[0-9]+)?\\+[0-9]+"));
            String hash = string(platform, "jdkArchiveSha256");
            assertTrue(hash.matches("[a-fA-F0-9]{64}"));
            Path archive = FoundationChecks.ROOT.resolve(".tools/archives/OpenJDK25U-jdk_x64_windows_hotspot_"
                    + string(platform, "jdkVersion").replace('+', '_') + ".zip");
            assertEquals(hash.toLowerCase(Locale.ROOT), HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive))));
        }
    }

    @Test
    void recordBudgetIsFrozenWithoutFabricatingReferenceCalibration() throws Exception {
        validateRecordBudget(manifest());
    }

    @Test
    void absentOrWeakenedPlatformAndBudgetClaimsAreRejected() throws Exception {
        JsonObject original = manifest();
        JsonObject noObligation = original.deepCopy();
        noObligation.remove("platformRequirements");
        assertThrows(AssertionError.class, () -> validatePlatformRequirements(noObligation));
        JsonObject oneOs = original.deepCopy();
        oneOs.getAsJsonObject("platformRequirements").addProperty("trustedOsCount", 1);
        assertThrows(AssertionError.class, () -> validatePlatformRequirements(oneOs));
        JsonObject noBudget = original.deepCopy();
        noBudget.remove("itemMaxSize");
        assertThrows(AssertionError.class, () -> validateRecordBudget(noBudget));
        JsonObject wrongBudget = original.deepCopy();
        wrongBudget.getAsJsonObject("itemMaxSize").addProperty("defaultBytes", 0);
        assertThrows(AssertionError.class, () -> validateRecordBudget(wrongBudget));
        JsonObject fakeCalibration = original.deepCopy();
        fakeCalibration.getAsJsonObject("itemMaxSize").getAsJsonObject("referenceBoundary").addProperty("status", "PASS");
        assertThrows(AssertionError.class, () -> validateRecordBudget(fakeCalibration));
    }

    @Test
    void measuredBoundaryRequiresVerifiedCalibrationAndRejectsTampering() throws Exception {
        validateRecordBudget(budget(measuredBoundary()));

        String rawSha256 = calibrationFixture().get("rawSha256").getAsString();
        for (String status : Set.of("NOT_RUN", "PASS", "ACCEPTED", "MEASURED_RELEASE_ACCEPTANCE", "MEASURED_PLATFORM_PASS")) {
            JsonObject tampered = measuredBoundary();
            tampered.addProperty("status", status);
            assertThrows(AssertionError.class, () -> validateRecordBudget(budget(tampered)), "Rejected status: " + status);
        }

        JsonObject missingCalibration = measuredBoundary();
        missingCalibration.remove("calibration");
        assertThrows(AssertionError.class, () -> validateRecordBudget(budget(missingCalibration)), "Missing calibration");
        JsonObject emptyCalibration = measuredBoundary();
        emptyCalibration.add("calibration", new JsonObject());
        assertThrows(AssertionError.class, () -> validateRecordBudget(budget(emptyCalibration)), "Empty calibration");
        for (String field : Set.of("rawPath", "rawSha256", "measuredDate", "reference", "candidate", "applicability")) {
            JsonObject partial = measuredBoundary();
            partial.getAsJsonObject("calibration").remove(field);
            assertThrows(AssertionError.class, () -> validateRecordBudget(budget(partial)), "Missing calibration." + field);
        }
        for (String server : new String[] {"reference", "candidate"}) {
            for (String field : Set.of("keyVariants", "acceptedRange", "rejectedRange", "flagsSensitivity")) {
                JsonObject partial = measuredBoundary();
                partial.getAsJsonObject("calibration").getAsJsonObject(server).remove(field);
                assertThrows(AssertionError.class, () -> validateRecordBudget(budget(partial)), "Missing calibration." + server + "." + field);
            }
        }
        for (String[] identity : new String[][] {{"reference", "binarySha256"}, {"candidate", "jarSha256"}}) {
            JsonObject partial = measuredBoundary();
            partial.getAsJsonObject("calibration").getAsJsonObject(identity[0]).remove(identity[1]);
            assertThrows(AssertionError.class, () -> validateRecordBudget(budget(partial)), "Missing " + identity[0] + " identity");
        }

        JsonObject truncatedHash = measuredBoundary();
        truncatedHash.getAsJsonObject("calibration").addProperty("rawSha256", rawSha256.substring(0, 63));
        assertThrows(AssertionError.class, () -> validateRecordBudget(budget(truncatedHash)), "Truncated rawSha256");
        JsonObject malformedHash = measuredBoundary();
        malformedHash.getAsJsonObject("calibration").addProperty("rawSha256", "z" + rawSha256.substring(1));
        assertThrows(AssertionError.class, () -> validateRecordBudget(budget(malformedHash)), "Malformed rawSha256");
        JsonObject unverifiedHash = measuredBoundary();
        unverifiedHash.getAsJsonObject("calibration").addProperty("rawSha256", flip(rawSha256));
        assertThrows(AssertionError.class, () -> validateRecordBudget(budget(unverifiedHash)), "Unverified rawSha256");
        JsonObject missingRaw = measuredBoundary();
        missingRaw.getAsJsonObject("calibration").addProperty("rawPath", CALIBRATION_RAW_PATH + ".missing");
        assertThrows(AssertionError.class, () -> validateRecordBudget(budget(missingRaw)), "Missing raw file");
        JsonObject truncatedIdentity = measuredBoundary();
        truncatedIdentity.getAsJsonObject("calibration").getAsJsonObject("reference").addProperty("binarySha256", "abc");
        assertThrows(AssertionError.class, () -> validateRecordBudget(budget(truncatedIdentity)), "Truncated reference identity");

        JsonObject partialKeys = measuredBoundary();
        partialKeys.getAsJsonObject("calibration").getAsJsonObject("reference").add("keyVariants", numbers(1, 16));
        assertThrows(AssertionError.class, () -> validateRecordBudget(budget(partialKeys)), "Partial key variants");

        JsonObject widenedRange = measuredBoundary();
        JsonObject referenceAccepted = widenedRange.getAsJsonObject("calibration").getAsJsonObject("reference").getAsJsonObject("acceptedRange");
        referenceAccepted.addProperty("max", referenceAccepted.get("max").getAsInt() + 1);
        assertThrows(AssertionError.class, () -> validateRecordBudget(budget(widenedRange)), "Widened accepted range");

        JsonObject wrongFlags = measuredBoundary();
        wrongFlags.getAsJsonObject("calibration").getAsJsonObject("reference").addProperty("flagsSensitivity", "sensitive: flags=0 admits 3 more bytes than flags != 0");
        assertThrows(AssertionError.class, () -> validateRecordBudget(budget(wrongFlags)), "Wrong flags sensitivity");
        JsonObject falseSensitivity = measuredBoundary();
        falseSensitivity.getAsJsonObject("calibration").getAsJsonObject("candidate").addProperty("flagsSensitivity", "sensitive: flags=0 admits 4 more bytes than flags != 0");
        assertThrows(AssertionError.class, () -> validateRecordBudget(budget(falseSensitivity)), "False candidate sensitivity");

        JsonObject releaseAcceptance = measuredBoundary();
        releaseAcceptance.getAsJsonObject("calibration").getAsJsonObject("applicability").addProperty("releaseAcceptance", true);
        assertThrows(AssertionError.class, () -> validateRecordBudget(budget(releaseAcceptance)), "releaseAcceptance true");
        JsonObject unbounded = measuredBoundary();
        unbounded.getAsJsonObject("calibration").getAsJsonObject("applicability").addProperty("bounded", false);
        assertThrows(AssertionError.class, () -> validateRecordBudget(budget(unbounded)), "bounded false");
    }

    private static void validatePlatformRequirements(JsonObject manifest) {
        assertTrue(manifest.has("platformRequirements"), "Missing trusted-OS obligation");
        JsonObject requirements = manifest.getAsJsonObject("platformRequirements");
        assertEquals(2, requirements.get("trustedOsCount").getAsInt());
        assertTrue(requirements.get("distinctRightsholders").getAsBoolean());
        assertEquals(25, requirements.get("requiredJavaRelease").getAsInt());
        assertEquals("NOT_RUN", string(requirements, "verificationStatus"));
        assertEquals("unavailable", string(requirements, "accessStatus"));
    }

    private static void validateRecordBudget(JsonObject manifest) throws Exception {
        assertTrue(manifest.has("itemMaxSize"), "Missing record budget");
        JsonObject budget = manifest.getAsJsonObject("itemMaxSize");
        assertEquals(1_048_576, budget.get("defaultBytes").getAsLong(), "Documented one-MiB full-record budget");
        String semantics = string(budget, "semantics").toLowerCase(Locale.ROOT);
        for (String term : Set.of("full", "record", "key", "service fields", "value")) {
            assertTrue(semantics.contains(term), "Budget must include " + term);
        }
        assertTrue(budget.has("referenceBoundary"), "Calibration status must be explicit");
        JsonObject boundary = budget.getAsJsonObject("referenceBoundary");
        assertFalse(boundary.has("formula"), "Do not invent an exact reference formula before calibration");
        assertFalse(boundary.has("assumptions"), "No unsupported calibration assumptions");

        String status = text(boundary, "status");
        assertEquals("MEASURED", status, "itemMaxSize.referenceBoundary.status: measured boundary required, actual " + status);
        String residual = status.toUpperCase(Locale.ROOT).replace("NOT_RELEASE_ACCEPTANCE", "");
        assertFalse(residual.matches(".*(^|[^A-Z])(PASS|RELEASE|ACCEPTANCE|ACCEPTED|APPROVED|CERTIFIED|VERIFIED)([^A-Z]|$).*"),
                "itemMaxSize.referenceBoundary.status: release-acceptance word is not a measured boundary, actual " + status);

        JsonObject calibration = object(boundary, "calibration", "measured");
        String rawPath = text(calibration, "rawPath", "sourceFile");
        assertFalse(Path.of(rawPath).isAbsolute(), "Calibration rawPath must be repository-relative: " + rawPath);
        Path raw = FoundationChecks.ROOT.resolve(rawPath).normalize();
        assertTrue(raw.startsWith(FoundationChecks.ROOT), "Calibration rawPath escapes the repository: " + rawPath);
        assertTrue(Files.isRegularFile(raw), "Referenced calibration raw file is missing: " + rawPath);
        String rawSha256 = hash64(calibration, "rawSha256", "sourceSha256");
        String measuredDate = text(calibration, "measuredDate", "date", "measurementDate");
        assertTrue(measuredDate.matches("\\d{4}-\\d{2}-\\d{2}.*"), "Calibration measuredDate must be an ISO date: " + measuredDate);
        JsonObject reference = object(calibration, "reference");
        JsonObject candidate = object(calibration, "candidate");
        String referenceSha256 = hash64(reference, "binarySha256", "sha256", "referenceSha256");
        String candidateSha256 = hash64(candidate, "jarSha256", "sha256", "candidateSha256");
        Set<Integer> referenceKeys = keyVariants(reference, "keyVariants", "keyBytes", "measuredKeyBytes");
        Set<Integer> candidateKeys = keyVariants(candidate, "keyVariants", "keyBytes", "measuredKeyBytes");
        int[] referenceAccepted = range(reference, "acceptedRange", "referenceAcceptedRange", "accepted");
        int[] referenceRejected = range(reference, "rejectedRange", "referenceRejectedRange", "rejected");
        int[] candidateAccepted = range(candidate, "acceptedRange", "candidateAcceptedRange", "accepted");
        int[] candidateRejected = range(candidate, "rejectedRange", "candidateRejectedRange", "rejected");
        String referenceFlags = text(reference, "flagsSensitivity", "flagsSensitivityDifference");
        String candidateFlags = text(candidate, "flagsSensitivity", "flagsSensitivityDifference");
        JsonObject applicability = object(calibration, "applicability");
        assertTrue(booleanField(applicability, "bounded"), "Calibration applicability.bounded must be true");
        assertFalse(booleanField(applicability, "releaseAcceptance"), "Calibration applicability.releaseAcceptance must be false");

        assertEquals(rawSha256, sha256(raw),
                "itemMaxSize.referenceBoundary.calibration.rawSha256 disagrees with the referenced raw file " + rawPath);
        Calibration derived = deriveCalibration(raw);
        assertEquals(derived.referenceSha256(), referenceSha256,
                "itemMaxSize.referenceBoundary.calibration.reference.binarySha256 disagrees with the raw calibration");
        assertEquals(derived.candidateSha256(), candidateSha256,
                "itemMaxSize.referenceBoundary.calibration.candidate.jarSha256 disagrees with the raw calibration");
        assertEquals(derived.keyVariants(), referenceKeys,
                "itemMaxSize.referenceBoundary.calibration.reference.keyVariants disagrees with the raw calibration: expected "
                        + derived.keyVariants() + ", actual " + referenceKeys);
        assertEquals(derived.keyVariants(), candidateKeys,
                "itemMaxSize.referenceBoundary.calibration.candidate.keyVariants disagrees with the raw calibration: expected "
                        + derived.keyVariants() + ", actual " + candidateKeys);
        assertRange("reference.acceptedRange", derived.referenceAcceptedMin(), derived.referenceAcceptedMax(), referenceAccepted);
        assertRange("reference.rejectedRange", derived.referenceRejectedMin(), derived.referenceRejectedMax(), referenceRejected);
        assertRange("candidate.acceptedRange", derived.candidateAcceptedMin(), derived.candidateAcceptedMax(), candidateAccepted);
        assertRange("candidate.rejectedRange", derived.candidateRejectedMin(), derived.candidateRejectedMax(), candidateRejected);
        validateFlagsSensitivity("reference", derived.referenceFlagsDifference(), referenceFlags);
        validateFlagsSensitivity("candidate", derived.candidateFlagsDifference(), candidateFlags);
        assertTrue(measuredDate.startsWith(derived.measuredDate()),
                "itemMaxSize.referenceBoundary.calibration.measuredDate disagrees with the raw campaign date: expected "
                        + derived.measuredDate() + ", actual " + measuredDate);
    }

    private static void validateFlagsSensitivity(String server, int expectedDifference, String claim) {
        String lower = claim.toLowerCase(Locale.ROOT);
        if (expectedDifference == 0) {
            assertTrue(lower.contains("insensitive") || lower.contains("identical"),
                    server + " flagsSensitivity must state the raw-verified insensitivity: " + claim);
            assertFalse(Pattern.compile("(?<!\\d)[1-9]\\d*(?!\\d)\\s+more").matcher(claim).find(),
                    server + " flagsSensitivity must not claim a byte difference: " + claim);
            assertFalse(Pattern.compile("(?<!in)sensitive").matcher(lower).find(),
                    server + " flagsSensitivity must not claim sensitivity: " + claim);
        } else {
            assertFalse(lower.contains("insensitive"),
                    server + " flagsSensitivity must not claim insensitivity: " + claim);
            assertTrue(Pattern.compile("(?<!in)sensitive").matcher(lower).find(),
                    server + " flagsSensitivity must state the raw-verified sensitivity: " + claim);
            assertTrue(Pattern.compile("(?<!\\d)" + expectedDifference + "(?!\\d)").matcher(claim).find(),
                    server + " flagsSensitivity must state the raw-verified difference " + expectedDifference + ": " + claim);
        }
    }

    private static void validateProfile(JsonObject manifest) {
        rejectPlaceholders(manifest);
        assertEquals("text", string(manifest, "protocol"));
        assertEquals(COMMANDS, strings(manifest, "commands"));
        Set<String> actualStats = strings(manifest, "statsFields");
        assertTrue(actualStats.containsAll(STATS), "Missing mandatory general stats");
        var extra = new HashSet<>(actualStats);
        extra.removeAll(STATS);
        assertTrue(extra.isEmpty() || extra.equals(Set.of("eviction_policy")), "Undocumented general stats additions");
        assertEquals(ITEMS, strings(manifest, "statsItemsFields"));
        assertEquals(SLABS, strings(manifest, "statsSlabsFields"));
    }

    private static Set<String> strings(JsonObject object, String key) {
        JsonArray values = object.getAsJsonArray(key);
        assertNotNull(values, key);
        assertFalse(values.isEmpty(), key);
        var result = new HashSet<String>();
        for (JsonElement value : values) {
            assertTrue(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), key);
            assertTrue(result.add(value.getAsString()), "Duplicate " + key + ": " + value);
        }
        return result;
    }

    private static void rejectPlaceholders(JsonElement value) {
        assertFalse(value.isJsonNull(), "Null manifest claim");
        if (value.isJsonObject()) {
            value.getAsJsonObject().entrySet().forEach(entry -> rejectPlaceholders(entry.getValue()));
        } else if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(CompatibilityManifestIT::rejectPlaceholders);
        } else if (value.getAsJsonPrimitive().isString()) {
            String text = value.getAsString().trim().toLowerCase(Locale.ROOT);
            assertFalse(text.isEmpty() || text.equals("latest") || text.contains("to-be-") || text.equals("tbd"),
                    "Placeholder manifest value: " + value);
        }
    }

    private static String string(JsonObject object, String key) {
        assertTrue(object.has(key) && object.get(key).isJsonPrimitive() && object.getAsJsonPrimitive(key).isString(), key);
        String value = object.get(key).getAsString();
        assertFalse(value.isBlank(), key);
        return value;
    }

    private static JsonObject manifest() throws Exception {
        return JSON.fromJson(Files.readString(FoundationChecks.ROOT.resolve("compatibility-manifest.json")), JsonObject.class);
    }

    private static JsonElement field(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull()) {
                return object.get(key);
            }
        }
        throw new AssertionError("Missing required manifest field; expected one of: " + String.join(", ", keys));
    }

    private static JsonObject object(JsonObject object, String... keys) {
        JsonElement value = field(object, keys);
        assertTrue(value.isJsonObject(), "Expected a JSON object for: " + String.join(", ", keys));
        return value.getAsJsonObject();
    }

    private static String text(JsonObject object, String... keys) {
        JsonElement value = field(object, keys);
        assertTrue(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), "Expected a JSON string for: " + String.join(", ", keys));
        String result = value.getAsString();
        assertFalse(result.isBlank(), "Blank JSON string for: " + String.join(", ", keys));
        return result;
    }

    private static boolean booleanField(JsonObject object, String key) {
        JsonElement value = field(object, key);
        assertTrue(value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean(), "Expected a JSON boolean: " + key);
        return value.getAsBoolean();
    }

    private static String hash64(JsonObject object, String... keys) {
        String value = text(object, keys);
        assertTrue(value.matches("[a-fA-F0-9]{64}"), "Malformed or truncated SHA-256 for " + String.join(", ", keys) + ": " + value);
        assertFalse(value.matches("(.)\\1{63}"), "Synthetic SHA-256 for " + String.join(", ", keys) + ": " + value);
        return value.toLowerCase(Locale.ROOT);
    }

    private static int integer(JsonElement value, String field) {
        assertTrue(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), "Expected a JSON number: " + field);
        return value.getAsInt();
    }

    private static Set<Integer> keyVariants(JsonObject calibration, String... keys) {
        JsonElement value = field(calibration, keys);
        assertTrue(value.isJsonArray(), "Expected a JSON array for: " + String.join(", ", keys));
        var result = new TreeSet<Integer>();
        for (JsonElement element : value.getAsJsonArray()) {
            assertTrue(result.add(integer(element, keys[0])), "Duplicate key variant in " + keys[0]);
        }
        assertFalse(result.isEmpty(), "Empty key variants in " + keys[0]);
        return result;
    }

    private static int[] range(JsonObject calibration, String... keys) {
        JsonElement value = field(calibration, keys);
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            assertEquals(2, array.size(), "Expected a [min, max] pair for: " + keys[0]);
            return new int[] {integer(array.get(0), keys[0]), integer(array.get(1), keys[0])};
        }
        assertTrue(value.isJsonObject(), "Expected an array or {min,max} object for: " + keys[0]);
        JsonObject object = value.getAsJsonObject();
        return new int[] {integer(field(object, "min", "minBytes", "from"), keys[0]),
                integer(field(object, "max", "maxBytes", "to"), keys[0])};
    }

    private static void assertRange(String field, int expectedMin, int expectedMax, int[] actual) {
        assertEquals(expectedMin, actual[0], "itemMaxSize.referenceBoundary.calibration." + field
                + " disagrees with the raw calibration: expected minimum " + expectedMin + ", actual " + actual[0]);
        assertEquals(expectedMax, actual[1], "itemMaxSize.referenceBoundary.calibration." + field
                + " disagrees with the raw calibration: expected maximum " + expectedMax + ", actual " + actual[1]);
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static <T> T consistent(T current, T next, String label) {
        if (current == null) {
            return next;
        }
        assertEquals(current, next, label);
        return current;
    }

    private static Calibration deriveCalibration(Path raw) throws Exception {
        JsonArray rows = JSON.fromJson(Files.readString(raw), JsonArray.class);
        assertNotNull(rows, "Raw calibration must be a JSON array");
        assertEquals(24, rows.size(), "Verified 24-point reference/candidate calibration grid");
        var keys = new TreeSet<Integer>();
        var referenceZero = new TreeMap<Integer, Integer>();
        var referenceNonzero = new TreeMap<Integer, Integer>();
        var candidateZero = new TreeMap<Integer, Integer>();
        var candidateNonzero = new TreeMap<Integer, Integer>();
        int referenceAcceptedMin = Integer.MAX_VALUE, referenceAcceptedMax = Integer.MIN_VALUE;
        int referenceRejectedMin = Integer.MAX_VALUE, referenceRejectedMax = Integer.MIN_VALUE;
        int candidateAcceptedMin = Integer.MAX_VALUE, candidateAcceptedMax = Integer.MIN_VALUE;
        int candidateRejectedMin = Integer.MAX_VALUE, candidateRejectedMax = Integer.MIN_VALUE;
        String referenceSha256 = null;
        String candidateSha256 = null;
        boolean referenceSeen = false;
        boolean candidateSeen = false;
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject();
            String server = row.get("server").getAsString();
            int keyBytes = row.get("keyBytes").getAsInt();
            long flags = row.get("flags").getAsLong();
            int accepted = row.get("accepted").getAsInt();
            int rejected = row.get("rejected").getAsInt();
            assertEquals(accepted + 1, rejected, "Raw calibration rejected value must be one byte above accepted");
            keys.add(keyBytes);
            JsonObject identity = row.getAsJsonObject("identity");
            referenceSha256 = consistent(referenceSha256, identity.get("referenceSha256").getAsString(), "Raw reference identity must be constant");
            candidateSha256 = consistent(candidateSha256, identity.get("jarSha256").getAsString(), "Raw candidate identity must be constant");
            if (server.equals("reference")) {
                referenceSeen = true;
                referenceAcceptedMin = Math.min(referenceAcceptedMin, accepted);
                referenceAcceptedMax = Math.max(referenceAcceptedMax, accepted);
                referenceRejectedMin = Math.min(referenceRejectedMin, rejected);
                referenceRejectedMax = Math.max(referenceRejectedMax, rejected);
                (flags == 0 ? referenceZero : referenceNonzero).put(keyBytes, accepted);
            } else if (server.equals("octomem")) {
                candidateSeen = true;
                candidateAcceptedMin = Math.min(candidateAcceptedMin, accepted);
                candidateAcceptedMax = Math.max(candidateAcceptedMax, accepted);
                candidateRejectedMin = Math.min(candidateRejectedMin, rejected);
                candidateRejectedMax = Math.max(candidateRejectedMax, rejected);
                (flags == 0 ? candidateZero : candidateNonzero).put(keyBytes, accepted);
            } else {
                fail("Unexpected calibration server: " + server);
            }
        }
        assertTrue(referenceSeen && candidateSeen, "Raw calibration must cover both servers");
        return new Calibration(referenceAcceptedMin, referenceAcceptedMax, referenceRejectedMin, referenceRejectedMax,
                candidateAcceptedMin, candidateAcceptedMax, candidateRejectedMin, candidateRejectedMax,
                flagsDifference(referenceZero, referenceNonzero, "reference"),
                flagsDifference(candidateZero, candidateNonzero, "candidate"),
                keys, referenceSha256, candidateSha256, dateFromPath(raw));
    }

    private static int flagsDifference(Map<Integer, Integer> zero, Map<Integer, Integer> nonzero, String server) {
        assertEquals(zero.keySet(), nonzero.keySet(), "Raw " + server + " calibration must cover the same keys for flags=0 and flags!=0");
        Integer difference = null;
        for (int key : zero.keySet()) {
            int delta = zero.get(key) - nonzero.get(key);
            assertTrue(delta >= 0, "Raw " + server + " flags=0 must admit at least as much as flags!=0");
            difference = consistent(difference, delta, "Raw " + server + " flags-sensitivity difference must be constant");
        }
        assertNotNull(difference, "Missing raw flags-sensitivity difference for " + server);
        return difference;
    }

    private static String dateFromPath(Path raw) {
        Matcher matcher = Pattern.compile("(\\d{4})(\\d{2})(\\d{2})").matcher(raw.toString());
        assertTrue(matcher.find(), "Raw calibration path must embed a campaign date: " + raw);
        return matcher.group(1) + "-" + matcher.group(2) + "-" + matcher.group(3);
    }

    private static JsonObject budget(JsonObject boundary) {
        JsonObject budget = new JsonObject();
        budget.addProperty("defaultBytes", 1_048_576);
        budget.addProperty("semantics", "Full stored record budget covering the key, the service fields and the value of the record.");
        budget.add("referenceBoundary", boundary);
        JsonObject manifest = new JsonObject();
        manifest.add("itemMaxSize", budget);
        return manifest;
    }

    private static JsonObject measuredBoundary() throws Exception {
        JsonObject boundary = new JsonObject();
        boundary.addProperty("status", "MEASURED");
        boundary.add("calibration", calibrationFixture());
        return boundary;
    }

    private static JsonObject calibrationFixture() throws Exception {
        Path raw = FoundationChecks.ROOT.resolve(CALIBRATION_RAW_PATH);
        Calibration derived = deriveCalibration(raw);
        JsonObject calibration = new JsonObject();
        calibration.addProperty("rawPath", CALIBRATION_RAW_PATH);
        calibration.addProperty("rawSha256", sha256(raw));
        calibration.addProperty("measuredDate", derived.measuredDate());
        calibration.add("reference", serverFixture("binarySha256", derived.referenceSha256(), derived.keyVariants(),
                "sensitive: flags=0 admits " + derived.referenceFlagsDifference() + " more bytes than flags != 0",
                derived.referenceAcceptedMin(), derived.referenceAcceptedMax(),
                derived.referenceRejectedMin(), derived.referenceRejectedMax()));
        calibration.add("candidate", serverFixture("jarSha256", derived.candidateSha256(), derived.keyVariants(),
                "insensitive: identical for every measured flag value",
                derived.candidateAcceptedMin(), derived.candidateAcceptedMax(),
                derived.candidateRejectedMin(), derived.candidateRejectedMax()));
        JsonObject applicability = new JsonObject();
        applicability.addProperty("bounded", true);
        applicability.addProperty("releaseAcceptance", false);
        calibration.add("applicability", applicability);
        return calibration;
    }

    private static JsonObject serverFixture(String identityField, String identity, Set<Integer> keyVariants, String flagsSensitivity,
            int acceptedMin, int acceptedMax, int rejectedMin, int rejectedMax) {
        JsonObject server = new JsonObject();
        server.addProperty(identityField, identity);
        server.add("keyVariants", numbers(keyVariants));
        server.add("acceptedRange", range(acceptedMin, acceptedMax));
        server.add("rejectedRange", range(rejectedMin, rejectedMax));
        server.addProperty("flagsSensitivity", flagsSensitivity);
        return server;
    }

    private static JsonObject range(int min, int max) {
        JsonObject range = new JsonObject();
        range.addProperty("min", min);
        range.addProperty("max", max);
        return range;
    }

    private static JsonArray numbers(Iterable<Integer> values) {
        JsonArray array = new JsonArray();
        for (int value : values) {
            array.add(value);
        }
        return array;
    }

    private static JsonArray numbers(int... values) {
        JsonArray array = new JsonArray();
        for (int value : values) {
            array.add(value);
        }
        return array;
    }

    private static String flip(String hash) {
        char last = hash.charAt(hash.length() - 1);
        return hash.substring(0, hash.length() - 1) + (last == '0' ? '1' : '0');
    }

    private record Calibration(int referenceAcceptedMin, int referenceAcceptedMax, int referenceRejectedMin, int referenceRejectedMax,
            int candidateAcceptedMin, int candidateAcceptedMax, int candidateRejectedMin, int candidateRejectedMax,
            int referenceFlagsDifference, int candidateFlagsDifference, Set<Integer> keyVariants,
            String referenceSha256, String candidateSha256, String measuredDate) { }

    private static byte[] tarEntry(Path archive, String expected) throws Exception {
        try (InputStream input = new GZIPInputStream(Files.newInputStream(archive))) {
            while (true) {
                byte[] header = input.readNBytes(512);
                assertEquals(512, header.length, "Truncated tar archive");
                String name = new String(header, 0, 100, StandardCharsets.US_ASCII).split("\0", 2)[0];
                assertFalse(name.isEmpty(), "Archive missing " + expected);
                String octal = new String(header, 124, 12, StandardCharsets.US_ASCII).replace("\0", "").trim();
                long size = Long.parseLong(octal, 8);
                assertTrue(size >= 0 && size < 64 * 1024 * 1024, "Bounded source archive entry");
                if (name.equals(expected)) {
                    byte[] content = input.readNBytes((int) size);
                    assertEquals(size, content.length);
                    return content;
                }
                input.skipNBytes(size + (512 - size % 512) % 512);
            }
        }
    }
}

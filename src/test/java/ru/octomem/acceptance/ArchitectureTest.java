package ru.octomem.acceptance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/** Checks the normative package DAG and proves rejection using separately compiled fixtures. */
class ArchitectureTest {
    // Independent ADR-014 fixture oracle, not derived from the checker under test.
    private static final List<String> PACKAGES = List.of(
            "transport", "protocol", "engine", "storage", "expiry", "eviction", "stats", "config", "cli", "util");
    private static final Set<String> ALLOWED_EDGES = Set.of(
            "transport:protocol", "transport:engine", "transport:storage", "transport:stats", "transport:util",
            "protocol:engine", "protocol:storage", "protocol:stats", "protocol:util",
            "engine:storage", "engine:expiry", "engine:eviction", "engine:stats", "engine:util",
            "storage:stats", "storage:util", "expiry:storage", "expiry:stats", "expiry:util",
            "eviction:storage", "eviction:stats",
            "cli:transport", "cli:protocol", "cli:engine", "cli:storage", "cli:expiry", "cli:eviction",
            "cli:stats", "cli:config", "cli:util");

    @TempDir Path temporary;

    @Test
    void checkerMatchesAdoptedContractExactly() {
        assertEquals(Set.copyOf(PACKAGES), FoundationChecks.DAG.keySet());
        Set<String> actual = FoundationChecks.DAG.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(to -> entry.getKey() + ":" + to))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(30, ALLOWED_EDGES.size());
        assertEquals(ALLOWED_EDGES, actual);
    }

    @Test
    void productionObeysNormativeDag() throws Exception {
        FoundationChecks.requireJdk25();
        Path classes = FoundationChecks.ROOT.resolve("target/classes");
        assertTrue(Files.isDirectory(classes));
        try (var entries = Files.walk(classes)) {
            assertTrue(entries.anyMatch(p -> p.toString().endsWith(".class")), "Empty architecture input is not acceptance");
        }
        assertEquals(List.of(), FoundationChecks.violations(classes));
        for (String group : FoundationChecks.DAG.keySet()) {
            assertTrue(Files.isRegularFile(FoundationChecks.ROOT.resolve("src/main/java/ru/octomem/" + group + "/package-info.java")),
                    "Missing documented package: " + group);
        }
    }

    @TestFactory
    Stream<DynamicTest> everyForbiddenPackageEdgeIsRejected() {
        return PACKAGES.stream().flatMap(from -> PACKAGES.stream()
                .filter(to -> !from.equals(to) && !ALLOWED_EDGES.contains(from + ":" + to))
                .map(to -> DynamicTest.dynamicTest(from + " -> " + to + " forbidden", () -> {
                    Path output = compile("forbidden-" + from + "-" + to, List.of(
                            source(from, "Probe", "public ru.octomem." + to + ".Target reference;"),
                            source(to, "Target", "")));
                    assertEquals(List.of("ru.octomem." + from + ".Probe -> ru.octomem." + to + ".Target"),
                            FoundationChecks.violations(output));
                })));
    }

    @TestFactory
    Stream<DynamicTest> everyAllowedPackageEdgeIsAccepted() {
        return ALLOWED_EDGES.stream().sorted().map(edge -> DynamicTest.dynamicTest(edge + " allowed", () -> {
            String[] groups = edge.split(":");
            Path output = compile("allowed-" + groups[0] + "-" + groups[1], List.of(
                    source(groups[0], "Probe", "public ru.octomem." + groups[1] + ".Target reference;"),
                    source(groups[1], "Target", "")));
            assertEquals(List.of(), FoundationChecks.violations(output));
        }));
    }

    @TestFactory
    Stream<DynamicTest> samePackageAndStandardLibraryAreAcceptedForEveryPackage() {
        return PACKAGES.stream().map(group -> DynamicTest.dynamicTest(group + " local and JDK", () -> {
            Path output = compile("local-" + group, List.of(
                    source(group, "Probe", "public java.util.List<ru.octomem." + group + ".Target> reference;"),
                    source(group, "Target", "")));
            assertEquals(List.of(), FoundationChecks.violations(output));
        }));
    }

    @Test
    void allowedEdgesAndStandardLibraryAreAccepted() throws Exception {
        Path output = compile("allowed", List.of(
                source("protocol", "Probe", "public java.util.List<ru.octomem.engine.Target> reference;"),
                source("engine", "Target", "")));
        assertEquals(List.of(), FoundationChecks.violations(output));
    }

    @Test
    void genericOnlyForbiddenEdgeIsRejected() throws Exception {
        Path output = compile("generic", List.of(
                source("stats", "Probe", "public java.util.List<ru.octomem.storage.Target> reference;"),
                source("storage", "Target", "")));
        assertFalse(FoundationChecks.violations(output).isEmpty());
    }

    @Test
    void foreignTypeIsRejectedEvenWhenPresentOnClasspath() throws Exception {
        Path output = compile("foreign", List.of(
                source("cli", "Probe", "public foreign.library.Target reference;"),
                "package foreign.library; public class Target {}"));
        assertTrue(FoundationChecks.violations(output).stream().anyMatch(v -> v.contains("foreign.library.Target")));
    }

    private static String source(String group, String name, String body) {
        return "package ru.octomem." + group + "; public class " + name + " { " + body + " }";
    }

    private Path compile(String id, List<String> sources) throws Exception {
        FoundationChecks.requireJdk25();
        Path directory = Files.createDirectories(temporary.resolve(id));
        Path output = Files.createDirectories(directory.resolve("classes"));
        var args = new ArrayList<>(List.of("--release", "25", "-d", output.toString()));
        for (int i = 0; i < sources.size(); i++) {
            String source = sources.get(i);
            String name = source.substring(source.indexOf("public class ") + 13).split("[ {]", 2)[0];
            Path file = Files.createDirectories(directory.resolve("source" + i)).resolve(name + ".java");
            Files.writeString(file, source);
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        return output;
    }
}

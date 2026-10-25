package ru.octomem.acceptance;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.spi.ToolProvider;

/** Shared acceptance checks; deliberately independent of production implementation. */
final class FoundationChecks {
    static final Path ROOT = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
    static final Map<String, Set<String>> DAG = Map.of(
            "transport", Set.of("protocol", "engine", "storage", "stats", "util"),
            "protocol", Set.of("engine", "storage", "stats", "util"),
            "engine", Set.of("storage", "expiry", "eviction", "stats", "util"),
            "storage", Set.of("stats", "util"), "expiry", Set.of("storage", "stats", "util"),
            "eviction", Set.of("storage", "stats"), "stats", Set.of(),
            "config", Set.of(), "util", Set.of(),
            "cli", Set.of("transport", "protocol", "engine", "storage", "expiry", "eviction", "stats", "config", "util"));

    private FoundationChecks() { }

    static void requireJdk25() {
        assertEquals(25, Runtime.version().feature(), "Acceptance must run on verified free OpenJDK 25");
        assertTrue(System.getProperty("java.runtime.name").contains("OpenJDK"), "Proprietary JDK is not permitted");
    }

    static String jdeps(Path input, String... options) {
        requireJdk25();
        assertTrue(Files.exists(input), "Missing artifact: " + input);
        var out = new StringWriter();
        var err = new StringWriter();
        var args = new ArrayList<>(List.of(options));
        args.add(input.toString());
        int exit = ToolProvider.findFirst("jdeps").orElseThrow().run(new PrintWriter(out), new PrintWriter(err), args.toArray(String[]::new));
        assertEquals(0, exit, () -> "jdeps failed: " + out + err);
        assertTrue(err.toString().isBlank(), () -> "jdeps diagnostics: " + err);
        return out.toString();
    }

    static List<String> violations(Path input) {
        String output = jdeps(input, "-verbose:class", "-filter:none");
        var violations = new ArrayList<String>();
        for (String line : output.lines().toList()) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 4 || !fields[1].equals("->") || !fields[0].startsWith("ru.octomem.")) {
                continue;
            }
            String from = group(fields[0]);
            String target = fields[2];
            if (!DAG.containsKey(from)) {
                violations.add("Unknown source package: " + fields[0]);
            } else if (target.startsWith("ru.octomem.")) {
                String to = group(target);
                if (!DAG.containsKey(to) || (!from.equals(to) && !DAG.get(from).contains(to))) {
                    violations.add(fields[0] + " -> " + target);
                }
            } else if (!fields[3].startsWith("java.") || target.startsWith("sun.") || target.startsWith("com.sun.")) {
                violations.add("Non-standard runtime type: " + line.trim());
            }
        }
        return violations;
    }

    private static String group(String type) {
        String rest = type.substring("ru.octomem.".length());
        int dot = rest.indexOf('.');
        return dot < 0 ? "" : rest.substring(0, dot);
    }
}

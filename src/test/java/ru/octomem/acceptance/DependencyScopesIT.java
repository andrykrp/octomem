package ru.octomem.acceptance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

/** Runs the actual build guard against disposable POMs, never altering the root POM. */
class DependencyScopesIT {
    @TempDir Path temporary;

    @Test
    void outputDecoderAcceptsUtf8AndRejectsLegacyCorruption() throws Exception {
        String expected = "Guard diagnostic: \u041e\u0448\u0438\u0431\u043a\u0430";
        assertEquals(expected, StandardCharsets.UTF_8.newDecoder().decode(
                java.nio.ByteBuffer.wrap(expected.getBytes(StandardCharsets.UTF_8))).toString());
        byte[] legacy = expected.getBytes(java.nio.charset.Charset.forName("windows-1251"));
        assertThrows(java.nio.charset.CharacterCodingException.class,
                () -> StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(legacy)));
    }

    @Test
    void launcherPreservesArgumentsWithSpacesAndExitCode() throws Exception {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        Path directory = Files.createDirectories(temporary.resolve("launcher with spaces"));
        String wrapper = windows ? "probe.cmd" : "probe.sh";
        Files.writeString(directory.resolve(wrapper), windows
                ? "@echo off\r\necho ARG=[%~1]\r\nexit /b 7\r\n"
                : "printf 'ARG=[%s]\\n' \"$1\"\nexit 7\n");
        Path log = directory.resolve("probe.log");
        Process process = new ProcessBuilder(launcher(windows, wrapper, List.of("argument with spaces")))
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(7, process.exitValue());
            assertEquals("ARG=[argument with spaces]" + System.lineSeparator(), Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static List<String> launcher(boolean windows, String wrapper, List<String> arguments) {
        var command = new ArrayList<>(windows ? List.of("cmd.exe", "/d", "/c", wrapper) : List.of("sh", wrapper));
        command.addAll(arguments);
        return command;
    }

    @Test
    void providedAndSystemDependenciesAreRejected() throws Exception {
        for (String scope : List.of("provided", "system")) {
            String output = fixture(scope, false);
            assertTrue(output.contains("BannedDependencies"), output);
            assertTrue(output.contains("com.google.code.gson:gson") && output.contains("banned"), output);
        }
    }

    @Test
    void forbiddenTransitiveDependencyIsRejectedEvenWhenDirectCarrierIsExempt() throws Exception {
        String output = fixture("compile", true);
        assertTrue(output.contains("BannedDependencies"), output);
        assertTrue(output.contains("org.opentest4j:opentest4j") && output.contains("banned"), output);
    }

    private String fixture(String scope, boolean transitive) throws Exception {
        FoundationChecks.requireJdk25();
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(FoundationChecks.ROOT.resolve("pom.xml").toFile());
        Element dependencies = (Element) document.getElementsByTagName("dependencies").item(0);
        while (dependencies.hasChildNodes()) dependencies.removeChild(dependencies.getFirstChild());
        Element dependency = document.createElement("dependency");
        dependencies.appendChild(dependency);
        String[][] fields = transitive
                ? new String[][] {{"groupId", "org.junit.jupiter"}, {"artifactId", "junit-jupiter-api"}, {"version", "${junit-jupiter.version}"}, {"scope", scope}}
                : new String[][] {{"groupId", "com.google.code.gson"}, {"artifactId", "gson"}, {"version", "${gson.version}"}, {"scope", scope}};
        for (String[] field : fields) {
            Element child = document.createElement(field[0]);
            child.setTextContent(field[1]);
            dependency.appendChild(child);
        }
        Path directory = Files.createDirectories(temporary.resolve(transitive ? "transitive" : scope));
        if (scope.equals("system")) {
            Path localJar = directory.resolve("system-fixture.jar");
            Files.copy(ReleaseArtifactIT.artifact(), localJar);
            Element path = document.createElement("systemPath");
            path.setTextContent(localJar.toString());
            dependency.appendChild(path);
        }
        if (transitive) {
            // Exempt only the carrier in this fixture, so a failure proves recursive leaf inspection.
            Element banned = (Element) document.getElementsByTagName("bannedDependencies").item(0);
            Element includes = document.createElement("includes");
            Element include = document.createElement("include");
            include.setTextContent("org.junit.jupiter:junit-jupiter-api");
            includes.appendChild(include);
            banned.appendChild(includes);
        }
        Path pom = directory.resolve("pom.xml");
        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(document), new StreamResult(pom.toFile()));
        Path log = directory.resolve("maven.txt");
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        String repository = System.getProperty("maven.repo.local",
                Path.of(System.getProperty("user.home"), ".m2", "repository").toString());
        // The working directory selects the wrapper; a bare batch filename avoids cmd's leading-quote rules.
        var command = launcher(windows, windows ? "mvnw.cmd" : "mvnw", List.of(
                "--offline", "--batch-mode", "--no-transfer-progress", "-Dmaven.repo.local=" + repository,
                "-f", pom.toString(), "validate"));
        var builder = new ProcessBuilder(command)
                .directory(FoundationChecks.ROOT.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "MAVEN_ARGS")) {
            builder.environment().remove(variable);
        }
        builder.environment().put("MAVEN_OPTS", "-Xms32m -Xmx192m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC "
                + "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8");
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Dependency fixture timeout");
            byte[] raw = Files.readAllBytes(log);
            // Preserve bytes even if strict decoding fails; never hide corruption with replacement characters.
            System.out.println("Maven fixture raw base64 scope=" + scope + " transitive=" + transitive + ": "
                    + Base64.getEncoder().encodeToString(raw));
            String output = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString();
            System.out.println("Dependency guard fixture scope=" + scope + ", transitive=" + transitive
                    + ", exit=" + process.exitValue() + "\n" + Files.readString(pom) + "\n" + output);
            assertNotEquals(0, process.exitValue(), "Forbidden dependency accepted: " + output);
            assertTrue(output.contains("BUILD FAILURE"), output);
            return output;
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}

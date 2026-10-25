package ru.octomem.acceptance;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Inspects the packaged server, not the test classpath. Runs after Maven package. */
class ReleaseArtifactIT {
    @TempDir Path temporary;

    @Test
    void executableJarActuallyLaunchesAndExitsCleanly() throws Exception {
        FoundationChecks.requireJdk25();
        Path stdout = temporary.resolve("stdout.txt");
        Path stderr = temporary.resolve("stderr.txt");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xms16m", "-Xmx64m", "-XX:ActiveProcessorCount=2", "-XX:+UseSerialGC",
                "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-jar", artifact().toString(), "--version").directory(temporary.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        for (String variable : List.of("CLASSPATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            builder.environment().remove(variable);
        }
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Skeleton JAR must exit within ten seconds");
            assertEquals(0, process.exitValue());
            var manifest = JsonParser.parseString(Files.readString(FoundationChecks.ROOT.resolve("compatibility-manifest.json"))).getAsJsonObject();
            assertEquals("octomem " + manifest.get("octomemVersion").getAsString() + System.lineSeparator(), Files.readString(stdout));
            assertEquals("", Files.readString(stderr), "Unexpected startup diagnostics");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Failed to stop timed-out subprocess");
            }
        }
    }

    @Test
    void resolvedRuntimeDependencyTreeContainsOnlyProject() throws Exception {
        Path tree = FoundationChecks.ROOT.resolve("target/runtime-dependency-tree.json");
        assertTrue(Files.isRegularFile(tree), "Generate dependency:tree with includeScope=runtime and outputType=json before IT");
        var root = JsonParser.parseString(Files.readString(tree)).getAsJsonObject();
        assertEquals("ru.octomem", root.get("groupId").getAsString());
        assertEquals("octomem", root.get("artifactId").getAsString());
        assertTrue(!root.has("children") || root.getAsJsonArray("children").isEmpty(),
                "Resolved compile/runtime third-party dependencies: " + root);
    }

    @Test
    void packagedClassesAreRelease25WithoutPreviewOrForeignPayload() throws Exception {
        FoundationChecks.requireJdk25();
        Path artifact = artifact();
        int classes = 0;
        try (var jar = new JarFile(artifact.toFile());
             var loader = new URLClassLoader(new java.net.URL[] {artifact.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            for (var entry : jar.stream().toList()) {
                String name = entry.getName();
                assertFalse(name.toLowerCase(Locale.ROOT).matches(".*\\.(jar|zip|dll|so(?:\\.[0-9.]+)?|dylib|jnilib|exe|a|lib)$"),
                        "Bundled library/archive: " + name);
                if (!name.endsWith(".class")) {
                    continue;
                }
                classes++;
                assertTrue(name.startsWith("ru/octomem/"), "Foreign or versioned class: " + name);
                assertFalse(name.startsWith("ru/octomem/acceptance/"), "Acceptance tests leaked into server");
                byte[] bytes = jar.getInputStream(entry).readAllBytes();
                assertClassVersion(bytes);
                if (!name.endsWith("package-info.class")) {
                    Class<?> type = Class.forName(name.substring(0, name.length() - 6).replace('/', '.'), false, loader);
                    for (var method : type.getDeclaredMethods()) {
                        assertFalse(Modifier.isNative(method.getModifiers()), "Native method: " + method);
                    }
                }
            }
            assertTrue(classes > 0, "Empty JAR is not acceptance");
        }
        assertEquals(List.of(), FoundationChecks.violations(artifact));
        assertTrue(FoundationChecks.jdeps(artifact, "--jdk-internals").isBlank(), "Internal JDK API dependency");
    }

    @Test
    void manifestDefinesAnExecutableSelfContainedEntrypoint() throws Exception {
        Path artifact = artifact();
        try (var jar = new JarFile(artifact.toFile());
             var loader = new URLClassLoader(new java.net.URL[] {artifact.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            assertNotNull(jar.getManifest(), "Missing META-INF/MANIFEST.MF");
            var attributes = jar.getManifest().getMainAttributes();
            String main = attributes.getValue("Main-Class");
            assertNotNull(main, "Executable JAR requires Main-Class");
            assertTrue(main.startsWith("ru.octomem.cli."), "Entrypoint belongs to cli");
            assertNull(attributes.getValue("Class-Path"), "External runtime classpath is forbidden");
            assertNotEquals("true", attributes.getValue("Multi-Release"), "No alternate runtime release");
            var method = Class.forName(main, false, loader).getMethod("main", String[].class);
            assertTrue(Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers()));
            assertEquals(void.class, method.getReturnType());
        }
    }

    @Test
    void wrongReleaseAndPreviewHeadersAreRejected() {
        byte[] valid = {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe, 0, 0, 0, 69};
        assertDoesNotThrow(() -> assertClassVersion(valid));
        byte[] old = valid.clone();
        old[7] = 65;
        assertThrows(AssertionError.class, () -> assertClassVersion(old));
        byte[] preview = valid.clone();
        preview[4] = preview[5] = (byte) 0xff;
        assertThrows(AssertionError.class, () -> assertClassVersion(preview));
    }

    private static void assertClassVersion(byte[] bytes) throws Exception {
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            assertEquals(0xcafebabe, input.readInt(), "Classfile magic");
            assertEquals(0, input.readUnsignedShort(), "Preview/nonstandard minor version forbidden");
            assertEquals(69, input.readUnsignedShort(), "Must be compiled with --release 25");
        }
    }

    static Path artifact() throws Exception {
        try (var entries = Files.list(FoundationChecks.ROOT.resolve("target"))) {
            var artifacts = entries.filter(p -> p.toString().endsWith(".jar"))
                    .filter(p -> !p.getFileName().toString().matches(".*-(sources|javadoc|tests)\\.jar"))
                    .toList();
            assertEquals(1, artifacts.size(), "Expected exactly one server JAR; run package before IT");
            return artifacts.getFirst();
        }
    }
}

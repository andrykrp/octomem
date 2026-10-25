package ru.octomem.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;

/** Test-only process boundary for scenarios whose defective locks cannot be interrupted. */
public final class BoundedTestJvm {
    private BoundedTestJvm() { }

    /** Child-only teardown: never enter engine cleanup with a possibly live lock owner. */
    public static void shutdownOrHalt(ExecutorService pool) {
        pool.shutdownNow();
        try {
            if (pool.awaitTermination(5, TimeUnit.SECONDS)) return;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        System.err.println("Child worker failed to terminate; refusing potentially blocking resource teardown");
        Runtime.getRuntime().halt(2);
    }

    /** Runs a static test-owned body, preserving JVM bounds and separate coverage data. */
    public static void run(Class<?> owner, String method, int seconds) throws Exception {
        Path output = Path.of("target", "child-test-results");
        Files.createDirectories(output);
        Path log = Files.createTempFile(output, owner.getSimpleName() + "-" + method + "-", ".log");
        List<String> command = new ArrayList<>();
        Path data = null;
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (argument.startsWith("-javaagent:") && argument.contains("jacoco")) {
                Path coverage = Path.of("target", "child-coverage");
                Files.createDirectories(coverage);
                data = coverage.resolve(log.getFileName() + ".exec").toAbsolutePath();
                String agent = argument.split("=", 2)[0];
                command.add(agent + "=destfile=" + data + ",append=false");
            } else if (!argument.startsWith("-javaagent:") && !argument.startsWith("-agentlib:")) {
                command.add(argument);
            }
        }
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(BoundedTestJvm.class.getName());
        command.add(owner.getName());
        command.add(method);
        Files.writeString(log.resolveSibling(log.getFileName() + ".command.txt"),
                String.join("\n", command) + "\nPARENT_DEADLINE_SECONDS=" + seconds + "\n");
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(seconds, TimeUnit.SECONDS), () -> "Child deadline exceeded; log=" + log);
            assertEquals(0, child.exitValue(), () -> "Child failed; log=" + log);
            if (data != null) assertTrue(Files.isRegularFile(data) && Files.size(data) > 0,
                    "Successful instrumented child must publish its own coverage data");
        } finally {
            if (child.isAlive()) child.destroyForcibly();
            boolean interrupted = Thread.interrupted();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            try {
                while (child.isAlive() && System.nanoTime() < deadline) {
                    try { child.waitFor(100, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException signal) { interrupted = true; }
                }
                assertTrue(!child.isAlive(), "Child JVM must be reaped");
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
            Files.writeString(log.resolveSibling(log.getFileName() + ".exit.txt"),
                    "pid=" + child.pid() + "\nalive=" + child.isAlive() + "\nexit=" + child.exitValue() + "\n");
        }
    }

    /** Invokes only an explicitly selected static test body; failures retain their original stack. */
    public static void main(String[] args) throws Throwable {
        try {
            var body = Class.forName(args[0]).getDeclaredMethod(args[1]);
            body.setAccessible(true);
            body.invoke(null);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}

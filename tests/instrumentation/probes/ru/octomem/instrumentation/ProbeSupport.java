package ru.octomem.instrumentation;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.util.List;

/**
 * Shared raw measurement helpers for the standalone instrumentation probes.
 *
 * <p>Every value printed by these helpers is either an explicitly labelled logical
 * charge read from production accessors, or an actively measured quantity. The
 * probe emits {@code MEASURE <key>=<value>} lines so the captured output can be
 * read without the probe's human commentary.
 */
public final class ProbeSupport {
    private ProbeSupport() { }

    /** Prints one labelled measurement line. */
    public static void measure(String key, long value) {
        System.out.println("MEASURE " + key + "=" + value);
    }

    /** Prints one labelled textual observation. */
    public static void note(String key, String value) {
        System.out.println("NOTE " + key + "=" + value);
    }

    /** Prints a section heading. */
    public static void section(String name) {
        System.out.println();
        System.out.println("== " + name + " ==");
    }

    /** Prints the process identity and the explicitly supplied VM pointer-mode flags. */
    public static void printEnvironment() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        System.out.println("ENV jvm=" + runtime.getVmName() + " " + runtime.getVmVersion());
        System.out.println("ENV javaVersion=" + System.getProperty("java.version"));
        System.out.println("ENV pid=" + ProcessHandle.current().pid());
        List<String> args = runtime.getInputArguments();
        StringBuilder flags = new StringBuilder();
        for (String arg : args) {
            if (arg.contains("CompressedOops") || arg.contains("CompressedClassPointers")
                    || arg.contains("UseSerialGC") || arg.startsWith("-Xmx")) {
                if (flags.length() > 0) {
                    flags.append(' ');
                }
                flags.append(arg);
            }
        }
        System.out.println("ENV pointerFlags=" + flags);
    }

    /** Returns the current measured JVM heap usage. */
    public static MemoryUsage heap() {
        MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
        return bean.getHeapMemoryUsage();
    }

    /** Forces a best-effort stabilization point and returns used heap bytes. */
    public static long settledHeapUsed() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return heap().getUsed();
    }

    /**
     * Returns the measured process resident set size in bytes on Windows via
     * {@code K32GetProcessMemoryInfo}. Returns -1 when unavailable.
     */
    public static long residentSetBytes() {
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);
            SymbolLookup psapi = SymbolLookup.libraryLookup("psapi", arena);
            SymbolLookup current = kernel32.find("GetCurrentProcess").isPresent()
                    ? kernel32 : psapi;
            MemorySegment process = (MemorySegment) linker.downcallHandle(
                    current.find("GetCurrentProcess").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS)).invoke();
            MethodHandle query = linker.downcallHandle(
                    psapi.find("K32GetProcessMemoryInfo")
                            .or(() -> kernel32.find("K32GetProcessMemoryInfo"))
                            .orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            MemorySegment counters = arena.allocate(80, 8);
            int ok = (int) query.invoke(process, counters, 80);
            return ok == 0 ? -1L : counters.get(ValueLayout.JAVA_LONG, 16);
        } catch (Throwable failure) {
            return -1L;
        }
    }

    /**
     * Reproduces the allocator size-class growth rule from
     * {@code FfmSlabAllocator} to derive the expected class count for a budget
     * without observing the allocator. This is a derivation from source, not a
     * measurement of the running allocator.
     */
    public static int derivedClassCount(int maxBlockSize) {
        int maximum = (maxBlockSize + 7) & ~7;
        int count = 1;
        for (int size = 64; size < maximum; size = nextSize(size, maximum)) {
            count++;
        }
        return count;
    }

    private static int nextSize(int size, int maximum) {
        long grown = (size * 5L + 3) / 4;
        return (int) Math.min((grown + 7) & ~7L, maximum);
    }
}

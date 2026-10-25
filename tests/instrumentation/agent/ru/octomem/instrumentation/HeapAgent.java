package ru.octomem.instrumentation;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Minimal java-agent exposing {@link Instrumentation#getObjectSize(Object)} and a
 * bounded reflective retained-size walk to standalone memory probes.
 *
 * <p>This class lives under {@code tests/instrumentation} and is never part of the
 * production artifact. It does not modify any production class.
 */
public final class HeapAgent {
    private static volatile Instrumentation instrumentation;

    private HeapAgent() { }

    /** JVM premain entry point; records the instrumentation capability. */
    public static void premain(String arguments, Instrumentation inst) {
        instrumentation = inst;
    }

    /** Returns the installed instrumentation, or throws when the agent is absent. */
    public static Instrumentation instrumentation() {
        Instrumentation current = instrumentation;
        if (current == null) {
            throw new IllegalStateException("HeapAgent was not installed via -javaagent");
        }
        return current;
    }

    /** Shallow measured size of one object as reported by the JVM. */
    public static long shallow(Object object) {
        return object == null ? 0L : instrumentation().getObjectSize(object);
    }

    /** Retained-size measurement result with a completeness indicator. */
    public record Result(long bytes, int objects, int inaccessibleReferences) {
        /** Returns whether every reachable reference could be walked. */
        public boolean complete() {
            return inaccessibleReferences == 0;
        }
    }

    /**
     * Reflective retained-size approximation: sums the JVM-reported shallow size of
     * the object and every object reachable through non-static reference fields and
     * array elements, visiting each identity once. Objects assignable to a skip type
     * contribute zero and are not traversed (used to exclude shared back-references
     * such as the allocator). Inaccessible JDK-internal fields are counted in
     * {@link Result#inaccessibleReferences()} so the caller can state measurement
     * completeness instead of silently under-reporting.
     */
    public static Result retained(Object root, Class<?>... skipTypes) {
        Map<Object, Boolean> visited = new IdentityHashMap<>();
        long[] totals = new long[2];
        walk(root, visited, skipTypes, totals);
        return new Result(totals[0], visited.size(), (int) totals[1]);
    }

    private static void walk(Object object, Map<Object, Boolean> visited,
            Class<?>[] skipTypes, long[] totals) {
        if (object == null || visited.put(object, Boolean.TRUE) != null) {
            return;
        }
        for (Class<?> skipType : skipTypes) {
            if (skipType.isInstance(object)) {
                return;
            }
        }
        totals[0] += shallow(object);
        Class<?> type = object.getClass();
        if (type.isArray()) {
            if (!type.getComponentType().isPrimitive()) {
                int length = Array.getLength(object);
                for (int i = 0; i < length; i++) {
                    walk(Array.get(object, i), visited, skipTypes, totals);
                }
            }
            return;
        }
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                Object child;
                try {
                    field.setAccessible(true);
                    child = field.get(object);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    totals[1]++;
                    continue;
                }
                walk(child, visited, skipTypes, totals);
            }
        }
    }
}

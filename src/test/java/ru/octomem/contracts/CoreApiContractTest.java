package ru.octomem.contracts;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Independent signature oracle frozen from docs/03 and coordinator support decisions. */
class CoreApiContractTest {
    @Test
    void engineSignaturesAreExact() throws Exception {
        api("engine.CacheEngine", "",
                "snapshot(engine.KeySequence):engine.RetrievalSnapshot!engine.CacheCapacityException",
                "snapshotAndTouch(engine.KeySequence,long):engine.RetrievalSnapshot!engine.CacheCapacityException",
                "get(byte[]):engine.GetResult", "getWithCas(byte[]):engine.GetResult",
                "getAndTouch(byte[],long):engine.GetResult!engine.CacheCapacityException",
                "set(byte[],byte[],int,long):engine.StoreResult", "add(byte[],byte[],int,long):engine.StoreResult",
                "replace(byte[],byte[],int,long):engine.StoreResult", "append(byte[],byte[]):engine.StoreResult",
                "prepend(byte[],byte[]):engine.StoreResult", "cas(byte[],byte[],int,long,long):engine.StoreResult",
                "delete(byte[]):boolean", "increment(byte[],long):engine.CounterResult",
                "decrement(byte[],long):engine.CounterResult", "touch(byte[],long):boolean!engine.CacheCapacityException",
                "flushAll(long):void", "start():void", "stop():void");
        api("engine.KeySequence", "", "size():int", "forEachKey(engine.KeyVisitor):void");
        api("engine.KeyVisitor", "", "accept(byte[],int,int):void");
        api("engine.GetResult", "java.lang.AutoCloseable", "found():boolean", "item():storage.Item", "close():void");
        api("engine.RetrievalSnapshot", "java.lang.AutoCloseable", "capturedClock():long",
                "values():java.util.Iterator", "close():void");
        assertEquals("java.util.Iterator<ru.octomem.storage.Item>",
                type("engine.RetrievalSnapshot").getMethod("values").getGenericReturnType().getTypeName());
        api("engine.CounterResult", "", "status():engine.CounterResult$Status", "value():long");
        enumeration("engine.CounterResult$Status", "VALUE,NOT_FOUND,NON_NUMERIC,OUT_OF_MEMORY");
        enumeration("engine.StoreResult", "STORED,NOT_STORED,EXISTS,NOT_FOUND,TOO_LARGE,OUT_OF_MEMORY,INVALID");
    }

    @Test
    void storageSignaturesExposeNoMutableValueArrayOrSharedCursor() throws Exception {
        api("storage.Item", "", "key():byte[]", "flags():int", "expiryTime():long", "casId():long",
                "valueLength():int", "readValue(long,java.nio.ByteBuffer):int", "isExpired(long):boolean",
                "isActive():boolean", "markActive():void", "default hasExpiry():boolean");
        api("storage.ItemLease", "java.lang.AutoCloseable", "item():storage.Item", "close():void");
        api("storage.MemoryAllocator", "", "allocate(int):storage.SlabHandle", "free(storage.SlabHandle):void",
                "totalMemory():long", "usedMemory():long", "classCount():int", "classStats(int):stats.SlabClassStats",
                "default trim():long");
        api("storage.SlabHandle", "", "classId():int", "capacity():int", "read(int,java.nio.ByteBuffer):int",
                "write(int,java.nio.ByteBuffer):int");
        api("stats.SlabClassStats", "", "classId():int", "chunkSize():int", "totalPages():long",
                "totalChunks():long", "usedChunks():long", "freeChunks():long");
    }

    @Test
    void protocolSignaturesAreExact() throws Exception {
        api("protocol.Command", "", "type():protocol.CommandType", "keys():engine.KeySequence", "flags():int",
                "exptime():long", "value():byte[]", "casId():long", "delta():long", "level():int", "noreply():boolean");
        api("protocol.Response", "", "kind():protocol.ResponseKind", "snapshot():engine.RetrievalSnapshot",
                "includeCas():boolean", "number():long", "text():java.lang.String", "statsLines():java.util.List", "close():void");
        assertEquals("java.util.List<java.lang.String>",
                type("protocol.Response").getMethod("statsLines").getGenericReturnType().getTypeName());
        api("protocol.ProtocolDecoder", "", "append(java.nio.ByteBuffer):void",
                "nextCommand():protocol.Command!protocol.ProtocolException");
        api("protocol.ProtocolEncoder", "", "encode(protocol.Response):protocol.ResponseWriter");
        api("protocol.ResponseWriter", "", "writeMore(protocol.ChunkSink):protocol.WriteStatus", "close():void");
        api("protocol.ChunkSink", "", "remainingCapacity():int", "accept(java.nio.ByteBuffer):void");
        enumeration("protocol.WriteStatus", "IN_PROGRESS,COMPLETE");
        enumeration("protocol.CommandType", "GET,GETS,GAT,GATS,SET,ADD,REPLACE,APPEND,PREPEND,CAS,DELETE,INCR,DECR,TOUCH,FLUSH_ALL,STATS,STATS_ITEMS,STATS_SLABS,STATS_RESET,VERSION,VERBOSITY,QUIT");
        enumeration("protocol.ResponseKind", "RETRIEVAL,STORED,NOT_STORED,EXISTS,NOT_FOUND,DELETED,TOUCHED,OK,RESET,NUMBER,STATS,VERSION,ERROR,CLIENT_ERROR,SERVER_ERROR");
    }

    @Test
    void capacityExceptionIsCheckedFinalAndCannotCarryCallerSuppliedDetails() throws Exception {
        Class<?> type = checkedException("engine.CacheCapacityException");
        assertEquals(1, type.getConstructors().length);
        Throwable first = (Throwable) type.getConstructor().newInstance();
        Throwable second = (Throwable) type.getConstructor().newInstance();
        assertNotNull(first.getMessage());
        assertFalse(first.getMessage().isBlank());
        assertEquals(first.getMessage(), second.getMessage());
        assertNull(first.getCause());
        assertThrows(IllegalStateException.class, () -> first.initCause(new Exception("untrusted")));
    }

    @Test
    void protocolExceptionHasOnlyBoundedStatusAndConnectionDisposition() throws Exception {
        Class<?> type = checkedException("protocol.ProtocolException");
        Class<?> kind = type("protocol.ProtocolException$Kind");
        enumeration("protocol.ProtocolException$Kind", "ERROR,CLIENT_ERROR,SERVER_ERROR");
        assertEquals(1, type.getConstructors().length);
        var constructor = type.getConstructor(kind, boolean.class);
        assertEquals(kind, type.getMethod("kind").getReturnType());
        assertEquals(boolean.class, type.getMethod("closeConnection").getReturnType());
        for (Object status : kind.getEnumConstants()) {
            Throwable open = (Throwable) constructor.newInstance(status, false);
            Throwable closed = (Throwable) constructor.newInstance(status, true);
            assertSame(status, type.getMethod("kind").invoke(open));
            assertEquals(false, type.getMethod("closeConnection").invoke(open));
            assertEquals(true, type.getMethod("closeConnection").invoke(closed));
            assertNotNull(open.getMessage());
            assertFalse(open.getMessage().isBlank());
            assertEquals(open.getMessage(), closed.getMessage());
            assertNull(open.getCause());
            assertNull(closed.getCause());
            assertThrows(IllegalStateException.class, () -> open.initCause(new Exception("untrusted")));
        }
        var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> constructor.newInstance(null, false));
        assertInstanceOf(NullPointerException.class, failure.getCause());
    }

    @Test
    void signatureOracleRejectsUnexpectedPublicMethodsAndWrongSignatures() throws Exception {
        assertFalse(UnexpectedPublicMethod.class.getDeclaredMethod("unexpected").isSynthetic());
        assertThrows(AssertionError.class, () -> api(UnexpectedPublicMethod.class, "", "expected():void"));
        assertThrows(AssertionError.class, () -> api(UnexpectedPublicMethod.class, "",
                "expected():void", "unexpected():boolean"));
    }

    @Test
    void signatureOracleStillRejectsNonSyntheticPrivateMethods() throws Exception {
        Method method = UnexpectedPrivateMethod.class.getDeclaredMethod("$jacocoInit");
        assertFalse(method.isSynthetic());
        assertTrue(Modifier.isPrivate(method.getModifiers()));
        assertThrows(AssertionError.class, () -> api(UnexpectedPrivateMethod.class, "", "expected():void"));
    }

    @Test
    void signatureOracleRequiresExplicitApprovalForDefaultMethods() {
        api(DefaultMethod.class, "", "default expected():boolean");
        assertThrows(AssertionError.class, () -> api(DefaultMethod.class, "", "expected():boolean"));
        assertThrows(AssertionError.class, () -> api(AbstractMethod.class, "", "default expected():boolean"));
    }

    public interface UnexpectedPublicMethod {
        void expected();
        void unexpected();
    }

    public interface UnexpectedPrivateMethod {
        void expected();
        private static void $jacocoInit() { }
    }

    public interface DefaultMethod {
        default boolean expected() { return true; }
    }

    public interface AbstractMethod {
        boolean expected();
    }

    private static Class<?> checkedException(String name) throws Exception {
        Class<?> type = type(name);
        assertEquals(Exception.class, type.getSuperclass());
        assertTrue(Modifier.isFinal(type.getModifiers()), name);
        return type;
    }

    private static void api(String name, String parent, String... signatures) throws Exception {
        api(type(name), parent, signatures);
    }

    private static void api(Class<?> type, String parent, String... signatures) {
        String name = type.getName();
        assertTrue(type.isInterface(), name);
        assertTrue(Modifier.isPublic(type.getModifiers()), name);
        assertEquals(parent.isEmpty() ? Set.of() : Set.of(parent), Arrays.stream(type.getInterfaces())
                .map(Class::getName).collect(Collectors.toSet()), name + " parents");
        Set<String> actual = new TreeSet<>();
        for (Method method : type.getDeclaredMethods()) {
            // Compiler and coverage instrumentation helpers are not declared source API.
            if (method.isSynthetic()) continue;
            assertTrue(Modifier.isPublic(method.getModifiers())
                    && (Modifier.isAbstract(method.getModifiers()) || method.isDefault()), method.toString());
            actual.add((method.isDefault() ? "default " : "") + method.getName() + "(" + Arrays.stream(method.getParameterTypes()).map(CoreApiContractTest::name)
                    .collect(Collectors.joining(",")) + "):" + name(method.getReturnType())
                    + Arrays.stream(method.getExceptionTypes()).map(t -> "!" + name(t)).collect(Collectors.joining()));
        }
        assertEquals(new TreeSet<>(Arrays.asList(signatures)), actual, name);
    }

    private static void enumeration(String name, String values) throws Exception {
        Class<?> type = type(name);
        assertTrue(type.isEnum(), name);
        assertEquals(values, Arrays.stream(type.getEnumConstants()).map(value -> ((Enum<?>) value).name())
                .collect(Collectors.joining(",")), name);
    }

    private static String name(Class<?> type) {
        return type.getTypeName().replace("ru.octomem.", "");
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName("ru.octomem." + name);
    }
}

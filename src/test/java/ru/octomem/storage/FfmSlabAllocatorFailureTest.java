package ru.octomem.storage;

import org.junit.jupiter.api.Test;
import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FfmSlabAllocatorFailureTest {
    @Test
    void creationFailureRollsBackAndRethrowsOriginalExceptionThenCanRetry() {
        RuntimeException failure = new IllegalStateException("injected creation failure");
        var factory = new FfmSlabAllocator.ArenaFactory() {
            boolean fail = true;
            public Arena create() {
                if (fail) { fail = false; throw failure; }
                return Arena.ofShared();
            }
        };
        try (var allocator = new FfmSlabAllocator(128, 128, 64, factory)) {
            assertSame(failure, assertThrows(IllegalStateException.class, () -> allocator.allocate(1)));
            assertEquals(0, allocator.totalMemory());
            assertEquals(0, allocator.usedMemory());
            assertEquals(0, allocator.classStats(0).totalPages());
            SlabHandle handle = allocator.allocate(1);
            assertNotNull(handle);
            allocator.free(handle);
        }
    }

    @Test
    void postNativeFailureClosesUnpublishedArenaAndPreservesExistingPage() {
        for (boolean outOfMemory : new boolean[]{false, true}) {
            Throwable failure = outOfMemory ? new OutOfMemoryError("injected only")
                    : new IllegalStateException("injected only");
            List<Arena> arenas = new ArrayList<>();
            var factory = new FfmSlabAllocator.ArenaFactory() {
                int publications;
                public Arena create() {
                    Arena arena = Arena.ofShared();
                    arenas.add(arena);
                    return arena;
                }
                public void beforePublish() {
                    if (++publications == 2) {
                        if (failure instanceof OutOfMemoryError error) throw error;
                        throw (RuntimeException) failure;
                    }
                }
            };
            try (var allocator = new FfmSlabAllocator(256, 128, 128, factory)) {
                SlabHandle existing = allocator.allocate(64);
                assertNotNull(existing);
                Throwable actual = assertThrows(failure.getClass(), () -> allocator.allocate(128));
                assertSame(failure, actual);
                assertEquals(2, arenas.size());
                assertTrue(arenas.get(0).scope().isAlive());
                assertFalse(arenas.get(1).scope().isAlive());
                assertEquals(128, allocator.totalMemory());
                assertEquals(64, allocator.usedMemory());
                assertEquals(0, allocator.classStats(allocator.classCount() - 1).totalPages());
                SlabHandle retry = allocator.allocate(128);
                assertNotNull(retry);
                assertEquals(256, allocator.totalMemory());
                allocator.free(retry);
                allocator.free(existing);
                assertEquals(256, allocator.trim());
                arenas.forEach(arena -> assertFalse(arena.scope().isAlive()));
            }
        }
    }

    @Test
    void arenaCreationIsPerPageNotPerBlockAndFinalFreeAfterCloseReclaims() {
        List<Arena> arenas = new ArrayList<>();
        FfmSlabAllocator.ArenaFactory factory = () -> {
            Arena arena = Arena.ofShared();
            arenas.add(arena);
            return arena;
        };
        try (var allocator = new FfmSlabAllocator(128, 128, 64, factory)) {
            SlabHandle first = allocator.allocate(1);
            SlabHandle second = allocator.allocate(1);
            assertNotNull(first);
            assertNotNull(second);
            assertEquals(1, arenas.size());
            allocator.free(first);
            SlabHandle reused = allocator.allocate(1);
            assertNotNull(reused);
            assertEquals(1, arenas.size());
            assertNull(allocator.allocate(1));
            assertEquals(1, arenas.size());
            allocator.close();
            assertTrue(arenas.getFirst().scope().isAlive());
            allocator.free(second);
            assertTrue(arenas.getFirst().scope().isAlive());
            allocator.free(reused);
            assertFalse(arenas.getFirst().scope().isAlive());
        }
    }
}

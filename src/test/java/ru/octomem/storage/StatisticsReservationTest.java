package ru.octomem.storage;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Allocation-class lookup must not outlive its native reservation ownership. */
class StatisticsReservationTest {
    @Test void cancelledReservationCannotExposeReusedBlockClass() {
        try (var allocator = new FfmSlabAllocator(65536,4096,4096)) {
            var budget = new StoredItem.ServiceBudget(4096);
            var reservation = StoredItem.reserve(budget,allocator,1,3,4096).reservation();
            assertNotNull(reservation);
            int allocationClass = reservation.classId();
            assertTrue(allocationClass >= 0,"SlabHandle contract uses nonnegative allocator-local identifiers");
            assertEquals(allocationClass,reservation.classId());
            assertEquals(512,budget.usedBytes());
            reservation.close();reservation.close();
            assertThrows(IllegalStateException.class,reservation::classId);
            assertEquals(0,budget.usedBytes());assertEquals(0,allocator.usedMemory());
        }
    }
    @Test void transferredReservationClassMatchesItemAndThenBecomesUnavailable() {
        try (var allocator = new FfmSlabAllocator(65536,4096,4096)) {
            var budget = new StoredItem.ServiceBudget(4096);
            try (var reservation = StoredItem.reserve(budget,allocator,1,3,4096).reservation()) {
                assertNotNull(reservation);int allocationClass=reservation.classId();
                StoredItem item=reservation.finish(new byte[]{'k'},(offset,target)->{while(target.hasRemaining())target.put((byte)'x');},ByteBuffer.allocate(64),7,false,0,1);
                try {
                    assertEquals(allocationClass,item.classId());
                    assertThrows(IllegalStateException.class,reservation::classId);
                    assertEquals(512,budget.usedBytes());
                    try(var lease=item.pin()){ByteBuffer data=ByteBuffer.allocate(3);assertEquals(3,lease.item().readValue(0,data));assertArrayEquals(new byte[]{'x','x','x'},data.array());}
                } finally {item.retire();}
            }
            assertEquals(0,budget.usedBytes());assertEquals(0,allocator.usedMemory());
        }
    }
}

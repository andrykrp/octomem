package ru.octomem.engine;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.opentest4j.AssertionFailedError;

/** Negative controls for process-level deadlines and failure propagation. */
@Timeout(15)
class BoundedTestJvmTest {
    @Test void noninterruptibleMonitorIsKilledAndReaped() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> BoundedTestJvm.run(BoundedTestJvmTest.class, "blockedMonitor", 2));
        assertTrue(failure.getMessage().contains("Child deadline exceeded"));
    }

    @Test void childAssertionIsNotReportedAsSuccess() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> BoundedTestJvm.run(BoundedTestJvmTest.class, "failedAssertion", 5));
        assertTrue(failure.getMessage().contains("Child failed"));
    }

    static void failedAssertion() { throw new AssertionError("deliberate-child-oracle-failure"); }

    static void blockedMonitor() throws InterruptedException {
        Object monitor = new Object();
        CountDownLatch locked = new CountDownLatch(1);
        new Thread(() -> {
            synchronized (monitor) {
                locked.countDown();
                for (;;) {
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException ignored) { /* Deliberately nonterminating fault. */ }
                }
            }
        }, "deliberate-monitor-owner").start();
        locked.await();
        synchronized (monitor) { throw new AssertionError("Monitor unexpectedly released"); }
    }
}

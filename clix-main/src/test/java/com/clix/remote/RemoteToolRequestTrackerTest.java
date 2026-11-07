package com.clix.remote;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteToolRequestTrackerTest {
    @Test
    void duplicateAndConflictingRequestsCannotStartTwice() {
        var tracker = new RemoteToolRequestTracker();
        tracker.startTurn(java.util.UUID.randomUUID().toString());
        var request = new RemoteToolRequestTracker.Request("one", "write_file", "{} ");
        assertEquals(RemoteToolRequestTracker.Receipt.NEW, tracker.receive(request));
        assertTrue(tracker.start("one"));
        assertEquals(RemoteToolRequestTracker.Receipt.DUPLICATE, tracker.receive(request));
        assertEquals(RemoteToolRequestTracker.Receipt.CONFLICT,
                tracker.receive(new RemoteToolRequestTracker.Request("one", "execute_command", "{}")));
        assertFalse(tracker.start("one"));
        assertTrue(tracker.complete("one", "done"));
        assertFalse(tracker.start("one"));
        assertEquals("done", tracker.get("one").result());
    }

    @Test
    void disconnectDoesNotTurnUnknownExecutionIntoRetry() {
        var tracker = new RemoteToolRequestTracker();
        tracker.startTurn(java.util.UUID.randomUUID().toString());
        tracker.receive(new RemoteToolRequestTracker.Request("one", "execute_command", "{}"));
        tracker.receive(new RemoteToolRequestTracker.Request("pending", "read_file", "{}"));
        assertTrue(tracker.start("one"));
        tracker.disconnect();
        assertEquals(RemoteToolRequestTracker.State.OUTCOME_UNKNOWN, tracker.get("one").state());
        assertEquals(RemoteToolRequestTracker.State.NOT_EXECUTED, tracker.get("pending").state());
        assertFalse(tracker.complete("one", "done"));
        assertFalse(tracker.start("one"));
        assertEquals(RemoteToolRequestTracker.Receipt.CLOSED,
                tracker.receive(new RemoteToolRequestTracker.Request("two", "read_file", "{}")));
    }

    @Test
    void concurrentStartsAllowOnlyOneExecution() throws Exception {
        var tracker = new RemoteToolRequestTracker();
        tracker.startTurn(java.util.UUID.randomUUID().toString());
        tracker.receive(new RemoteToolRequestTracker.Request("one", "write_file", "{}"));
        var pool = Executors.newFixedThreadPool(2);
        var release = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> { release.await(); return tracker.start("one"); });
            var b = pool.submit(() -> { release.await(); return tracker.start("one"); });
            release.countDown();
            assertNotEquals(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void timeoutLeavesOutcomeUnknownAndCannotBeRetried() {
        var tracker = new RemoteToolRequestTracker();
        tracker.startTurn(java.util.UUID.randomUUID().toString());
        var request = new RemoteToolRequestTracker.Request("one", "execute_command", "{}");
        assertEquals(RemoteToolRequestTracker.Receipt.NEW, tracker.receive(request));
        assertTrue(tracker.start("one"));
        assertTrue(tracker.markOutcomeUnknown("one"));
        assertEquals(RemoteToolRequestTracker.State.OUTCOME_UNKNOWN, tracker.get("one").state());
        assertFalse(tracker.start("one"));
        assertEquals(RemoteToolRequestTracker.Receipt.DUPLICATE, tracker.receive(request));
    }
}

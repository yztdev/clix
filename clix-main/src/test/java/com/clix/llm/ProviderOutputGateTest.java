package com.clix.llm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProviderOutputGateTest {

    private static final ProviderOutputGate.ChunkSink NOOP = ignored -> { };

    /** I-15: after cancel, the 10 late chunks never reach the business callback (count 0) but are counted (10). */
    @Test void postCancelChunksAreQuarantinedNotDelivered() {
        ProviderOutputGate gate = new ProviderOutputGate();
        AtomicInteger delivered = new AtomicInteger();
        ProviderOutputGate.ChunkSink sink = ignored -> delivered.incrementAndGet();

        // Pre-cancel chunks are delivered normally.
        for (int i = 1; i <= 3; i++) assertTrue(gate.deliver("pre" + i, sink));
        assertEquals(3, delivered.get());

        // turn.cancel switches the gate to QUARANTINED before any business cleanup.
        gate.onCancel();
        assertEquals(ProviderOutputGate.State.QUARANTINED, gate.state());

        // 10 late chunks after cancel: 0 business callbacks, all counted as quarantined.
        for (int i = 1; i <= 10; i++) assertFalse(gate.deliver("post" + i, sink));
        assertEquals(3, delivered.get(), "business callback count for the late chunks must be 0");
        assertEquals(10, gate.quarantinedChunkCount());
    }

    /** I-16: delivered-before-cancel and quarantined-after-cancel are kept in separate buckets. */
    @Test void deliveredAndQuarantinedCountsAreIndependent() {
        ProviderOutputGate gate = new ProviderOutputGate();
        AtomicInteger delivered = new AtomicInteger();
        ProviderOutputGate.ChunkSink sink = ignored -> delivered.incrementAndGet();

        for (int i = 1; i <= 4; i++) gate.deliver("a" + i, sink);
        gate.onCancel();
        for (int i = 1; i <= 2; i++) gate.deliver("b" + i, sink);

        assertEquals(4, gate.deliveredCount());
        assertEquals(2, gate.quarantinedChunkCount());
        assertEquals(4, delivered.get());

        // A mere provider cancel ack is NOT a close signal and never becomes "provider confirmed".
        gate.providerCancelAck();
        assertEquals(ProviderOutputGate.State.QUARANTINED, gate.state());
        assertNull(gate.gateCloseReason());
    }

    /** I-17: chunks already buffered before the gate is enabled are flushed and counted as quarantined. */
    @Test void bufferedChunksAreFlushedIntoQuarantineOnCancel() {
        ProviderOutputGate gate = new ProviderOutputGate();
        for (int i = 1; i <= 5; i++) gate.buffer("buf" + i);
        assertEquals(0, gate.quarantinedChunkCount());

        gate.onCancel();
        assertEquals(ProviderOutputGate.State.QUARANTINED, gate.state());
        assertEquals(5, gate.quarantinedChunkCount());

        // After QUARANTINED, new buffer() calls go straight to quarantine (no re-buffering).
        gate.buffer("buf6");
        assertEquals(6, gate.quarantinedChunkCount());
    }

    /** I-18: only a protocol stream-end or request-destroyed+transport-closed may CLOSE the gate. */
    @Test void onlyObservableTerminationEvidenceClosesTheGate() {
        // --- Path A: provider protocol stream-end ---
        ProviderOutputGate a = new ProviderOutputGate();
        a.onCancel();
        assertEquals(ProviderOutputGate.State.QUARANTINED, a.state());
        a.providerCancelAck();                       // mere ack: not enough
        assertEquals(ProviderOutputGate.State.QUARANTINED, a.state());
        assertFalse(a.close("timeout"));             // timeout never closes
        assertFalse(a.close("stopped_reading"));     // local stop-reading never closes
        a.providerStreamEnd();
        assertEquals(ProviderOutputGate.State.CLOSED, a.state());
        assertEquals("provider_stream_end", a.gateCloseReason());

        // --- Path B: request destroyed + transport confirmed closed ---
        ProviderOutputGate b = new ProviderOutputGate();
        b.onCancel();
        b.requestDestroyedTransportClosed();
        assertEquals(ProviderOutputGate.State.CLOSED, b.state());
        assertEquals("request_destroyed_transport_closed", b.gateCloseReason());

        // --- Normal completion: OPEN gate closed directly by stream-end ---
        ProviderOutputGate c = new ProviderOutputGate();
        assertTrue(c.close("provider_stream_end"));
        assertEquals(ProviderOutputGate.State.CLOSED, c.state());

        // --- Already CLOSED: further quarantine is a no-op, close is rejected ---
        assertEquals(0, c.quarantinedChunkCount());
        c.recordQuarantinedChunk("late");
        assertEquals(0, c.quarantinedChunkCount(), "closed gate must not accrue new quarantine");
        assertFalse(c.close("provider_stream_end"));
    }
    @Test void repeatedDiagnosticsDoNotResetQuarantineDigest() {
        ProviderOutputGate gate = new ProviderOutputGate();
        gate.onCancel();
        gate.deliver("first", NOOP);
        String snapshot = gate.quarantinedChunkDigest();
        assertEquals(snapshot, gate.quarantinedChunkDigest());
        gate.deliver("second", NOOP);
        assertNotEquals(snapshot, gate.quarantinedChunkDigest());
    }

    @Test void concurrentCancellationSerializesAdapterDelivery() throws Exception {
        ProviderOutputGate gate = new ProviderOutputGate();
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.CountDownLatch inCallback = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread delivery = new Thread(() -> gate.deliver("earlier", chunk -> {
            calls.incrementAndGet();
            inCallback.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
        delivery.start();
        assertTrue(inCallback.await(2, java.util.concurrent.TimeUnit.SECONDS));
        Thread cancel = new Thread(gate::onCancel);
        cancel.start();
        release.countDown();
        cancel.join(2000);
        delivery.join(2000);
        assertFalse(cancel.isAlive());
        assertFalse(gate.deliver("late", ignored -> calls.incrementAndGet()));
        assertEquals(1, calls.get());
        assertEquals(1, gate.quarantinedChunkCount());
    }

}

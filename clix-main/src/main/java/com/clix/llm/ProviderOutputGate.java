package com.clix.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-turn provider output gate (design doc V1.2 section G) — the first line of defense.
 *
 * <p>This gate sits at the Provider Adapter streaming boundary. Every chunk must be offered to
 * this gate <em>before</em> it can reach {@code onToken}/{@code onMessageDelta}/tool-call parsing/
 * compaction/agentHistory/ledger writes or the UI send queue. On {@code turn.cancel} the gate is
 * switched to {@code QUARANTINED} atomically and <em>before</em> any cancellation cleanup or
 * business callback runs. While {@code QUARANTINED} or {@code CLOSED}, a chunk is counted and
 * hashed but its text is never retained or dispatched.
 *
 * <p>The server-side second line ({@code RemoteAgentSession}'s turn_id filter plus
 * {@code TurnOutputGate}) still exists and must not be removed; it cannot replace this first-time
 * interception.
 *
 * <p>Close evidence is restricted to the two observable reasons in {@link #CLOSE_REASONS}: a
 * provider protocol stream-end (e.g. Anthropic {@code message_stop}, OpenAI {@code [DONE]}) or the
 * request object being destroyed with the transport confirmed closed. A mere cancel acknowledgement
 * or local stop-reading is never sufficient to close the gate.
 */
public final class ProviderOutputGate {
    public enum State { OPEN, QUARANTINED, CLOSED }

    /** Evidence types that alone justify CLOSED (doc V1.2 G). */
    public static final Set<String> CLOSE_REASONS = Set.of(
            "provider_stream_end", "request_destroyed_transport_closed");

    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
    private final AtomicInteger deliveredCount = new AtomicInteger();
    private final AtomicInteger quarantinedCount = new AtomicInteger();
    private final Deque<String> buffer = new ArrayDeque<>();
    private final MessageDigest digest = sha256();
    private volatile String firstChunkHash;
    private volatile String lastChunkHash;
    private volatile String firstChunkAt;
    private volatile String lastChunkAt;
    private volatile String closeReason;
    private volatile boolean providerAck;
    private volatile boolean transportClosed;

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    /** The single business sink a provider adapter delivers a chunk to when the gate is OPEN. */
    public interface ChunkSink {
        void onChunk(String chunk);
    }

    /** True only while the gate is OPEN; the adapter must not deliver chunks otherwise. */
    public boolean acceptsChunk() {
        return state.get() == State.OPEN;
    }

    public State state() {
        return state.get();
    }

    public boolean wasQuarantined() {
        return quarantinedCount.get() > 0 || state.get() != State.OPEN;
    }

    /**
     * First action on {@code turn.cancel} (doc V1.2 G). Atomically {@code OPEN -> QUARANTINED} and
     * flushes any pre-dispatch buffered chunks into the quarantine count. Must precede cleanup and
     * business callbacks.
     */
    public void onCancel() {
        synchronized (this) {
            // Transition first: concurrent adapter deliveries cannot pass while we drain.
            state.compareAndSet(State.OPEN, State.QUARANTINED);
            while (!buffer.isEmpty()) recordQuarantined(buffer.pollFirst());
        }
    }

    /**
     * Adapter buffers chunks before the gate is engaged. Once the gate is {@code QUARANTINED} or
     * {@code CLOSED} the chunk is immediately recorded as quarantined rather than buffered (I-17).
     */
    public void buffer(String chunk) {
        synchronized (this) {
            if (state.get() == State.OPEN) buffer.addLast(chunk);
            else recordQuarantined(chunk);
        }
    }

    /**
     * Delivers a chunk. Returns true only if the gate was OPEN and the sink received it; otherwise
     * the chunk is quarantined and false is returned. The chunk text is never retained.
     */
    public synchronized boolean deliver(String chunk, ChunkSink sink) {
        if (state.get() == State.OPEN) {
            // Mutual exclusion with onCancel ensures no post-transition business callback.
            sink.onChunk(chunk);
            deliveredCount.incrementAndGet();
            return true;
        }
        recordQuarantined(chunk);
        return false;
    }

    /** Records a chunk produced by the streaming boundary without delivering it (e.g. renderer). */
    public void recordQuarantinedChunk(String chunk) {
        recordQuarantined(chunk);
    }

    /** Chunks delivered to the business layer before cancellation (separate bucket from quarantined). */
    public int deliveredCount() {
        return deliveredCount.get();
    }

    public int quarantinedChunkCount() {
        return quarantinedCount.get();
    }

    // --- Provider / transport termination signals (doc V1.2 G) ---

    /** Provider-level stream-end signal (e.g. Anthropic message_stop, OpenAI [DONE]). */
    public void providerStreamEnd() {
        providerAck = true;
        tryClose("provider_stream_end");
    }

    /** The request object was destroyed AND the underlying transport/connection is confirmed closed. */
    public void requestDestroyedTransportClosed() {
        transportClosed = true;
        tryClose("request_destroyed_transport_closed");
    }

    /**
     * A mere provider cancel acknowledgement is NOT sufficient to close the gate: the stream may
     * still deliver chunks. Recorded for diagnostics only (doc V1.2 G).
     */
    public void providerCancelAck() {
        providerAck = true;
    }

    /**
     * A provider protocol stream-end or a destroyed request + confirmed transport close is
     * observable termination evidence regardless of whether the gate was already QUARANTINED
     * (cancelled) or still OPEN (normal completion). A mere cancel acknowledgement or local
     * stop-reading never reaches this method (doc V1.2 G).
     */
    private void tryClose(String reason) {
        if (state.get() == State.CLOSED) return;
        if (!CLOSE_REASONS.contains(reason)) return;
        closeReason = reason;
        state.set(State.CLOSED);
    }

    /** Closes the gate only with an explicit, observable termination reason. Returns false otherwise. */
    public boolean close(String reason) {
        if (reason == null || !CLOSE_REASONS.contains(reason)) return false;
        if (state.get() == State.CLOSED) return false;
        closeReason = reason;
        state.set(State.CLOSED);
        return true;
    }

    public String gateCloseReason() {
        return closeReason;
    }

    public boolean providerAck() {
        return providerAck;
    }

    public boolean transportClosed() {
        return transportClosed;
    }

    private synchronized void recordQuarantined(String chunk) {
        if (state.get() == State.CLOSED) return;
        String now = Instant.now().toString();
        byte[] bytes = (chunk == null ? "" : chunk).getBytes(StandardCharsets.UTF_8);
        byte[] hash = sha256().digest(bytes);
        String hashHex = hex(hash);
        if (firstChunkHash == null) {
            firstChunkHash = hashHex;
            firstChunkAt = now;
        }
        lastChunkHash = hashHex;
        lastChunkAt = now;
        quarantinedCount.incrementAndGet();
        digest.update(hash);
    }

    public synchronized String quarantinedChunkDigest() {
        // digest() resets the rolling digest; repeated diagnostics must be idempotent.
        try {
            return hex(((MessageDigest) digest.clone()).digest());
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException("SHA-256 digest clone unavailable", e);
        }
    }

    public String firstChunkHash() {
        return firstChunkHash;
    }

    public String lastChunkHash() {
        return lastChunkHash;
    }

    public String firstChunkAt() {
        return firstChunkAt;
    }

    public String lastChunkAt() {
        return lastChunkAt;
    }

    /** Adds the V1.2 H/G diagnostic object fields to a turn.cancelled payload. */
    public void addDiagnostics(ObjectNode payload) {
        payload.put("provider_output_quarantined", wasQuarantined());
        payload.put("quarantined_chunk_count", quarantinedCount.get());
        payload.put("quarantined_chunk_digest", quarantinedChunkDigest());
        ObjectNode window = payload.putObject("quarantine_window");
        if (firstChunkAt != null) window.put("first_chunk_at", firstChunkAt);
        if (lastChunkAt != null) window.put("last_chunk_at", lastChunkAt);
        if (firstChunkHash != null) window.put("first_chunk_hash", firstChunkHash);
        if (lastChunkHash != null) window.put("last_chunk_hash", lastChunkHash);
        if (closeReason != null) payload.put("gate_close_reason", closeReason);
        payload.put("provider_chunk_delivered_count", deliveredCount.get());
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }
}

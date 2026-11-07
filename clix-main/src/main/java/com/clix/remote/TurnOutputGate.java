package com.clix.remote;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-turn provider output gate (design doc V1.2 section G, server-side part).
 *
 * <p>A cancelled turn must never leak late chunks into the next turn. The gate is switched to
 * QUARANTINED before any cancellation cleanup or business callback runs. While quarantined,
 * chunks are counted and hashed but their text is never retained or dispatched.
 *
 * <p>This is the server-side defense. The Provider Adapter level gate (which decides CLOSED
 * from provider_stream_end / request_destroyed_transport_closed evidence) belongs to V1.2 and
 * is not part of phase one; here the run returning is the observable evidence that this server
 * side will no longer dispatch chunks for the request object.
 */
final class TurnOutputGate {
    enum State { OPEN, QUARANTINED, CLOSED }

    /** Only these two reasons are accepted as evidence for CLOSED (doc V1.2 G). */
    static final Set<String> CLOSE_REASONS = Set.of(
            "provider_stream_end", "request_destroyed_transport_closed");

    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
    private final AtomicInteger quarantinedChunkCount = new AtomicInteger();
    private final MessageDigest digest = sha256();
    private volatile String firstChunkAt;
    private volatile String lastChunkAt;
    private volatile String closeReason;
    private volatile String cachedDigest;

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    boolean acceptsOutput() {
        return state.get() == State.OPEN;
    }

    State state() {
        return state.get();
    }

    boolean wasQuarantined() {
        return quarantinedChunkCount.get() > 0 || state.get() != State.OPEN;
    }

    /** Called first in turn.cancel, before model/agent/tool cleanup. */
    void quarantine() {
        state.compareAndSet(State.OPEN, State.QUARANTINED);
    }

    /** Records only a digest of the chunk; the chunk text itself is never stored. */
    synchronized void recordQuarantinedChunk(String chunk) {
        if (state.get() == State.CLOSED) return;
        String now = Instant.now().toString();
        if (firstChunkAt == null) firstChunkAt = now;
        lastChunkAt = now;
        quarantinedChunkCount.incrementAndGet();
        byte[] bytes = chunk == null ? new byte[0] : chunk.getBytes(StandardCharsets.UTF_8);
        digest.update(sha256().digest(bytes));
    }

    /** Closes the gate only with an explicit, observable termination reason. */
    boolean close(String reason) {
        if (reason == null || !CLOSE_REASONS.contains(reason)) return false;
        if (state.get() == State.CLOSED) return false;
        closeReason = reason;
        state.set(State.CLOSED);
        return true;
    }

    String closeReason() {
        return closeReason;
    }

    int quarantinedChunkCount() {
        return quarantinedChunkCount.get();
    }

    synchronized String quarantinedChunkDigest() {
        if (cachedDigest == null) cachedDigest = hex(digest.digest());
        return cachedDigest;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    /** Adds the V1.2 H diagnostic object fields to a turn.cancelled payload. */
    void addDiagnostics(ObjectNode payload) {
        payload.put("provider_output_quarantined", wasQuarantined());
        payload.put("quarantined_chunk_count", quarantinedChunkCount.get());
        payload.put("quarantined_chunk_digest", quarantinedChunkDigest());
        ObjectNode window = payload.putObject("quarantine_window");
        if (firstChunkAt != null) window.put("first_chunk_at", firstChunkAt);
        if (lastChunkAt != null) window.put("last_chunk_at", lastChunkAt);
        if (closeReason != null) payload.put("gate_close_reason", closeReason);
    }
}

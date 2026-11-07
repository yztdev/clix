package com.clix.effect;

import com.clix.history.ConversationLedger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-shot, crash-aware injection bookkeeping (design doc V1.2 F).
 *
 * <p>The injected event is written only after the request carrying the context was
 * observably sent. An attempt without a matching injected event means the process may
 * have crashed in the "sent but not persisted" window, so a later turn may inject once
 * more with a duplicate warning. Once the injected event exists, no other turn receives
 * that source's context, and no context leaks into a concurrent turn.
 */
public final class InterruptionInjector {
    public static final String ATTEMPT_EVENT = "interruption_context_attempt";

    private record Pending(String source, String target, String requestId, String digest, int attempt) { }

    private final ConversationLedger ledger;
    private final Map<String, String> sourceToTarget = new ConcurrentHashMap<>();
    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();
    private final Map<String, Pending> pendingByTarget = new ConcurrentHashMap<>();

    public InterruptionInjector(ConversationLedger ledger) {
        this.ledger = java.util.Objects.requireNonNull(ledger, "ledger");
        restore();
    }

    private void restore() {
        try {
            for (ConversationLedger.Entry entry : ledger.readAll()) {
                if (entry.metadata() == null) continue;
                String source = str(entry.metadata().get("source_cancelled_turn_id"));
                if (source.isEmpty()) continue;
                if (EffectEvent.CONTEXT_INJECTED.equals(entry.event())) {
                    sourceToTarget.put(source, str(entry.metadata().get("target_turn_id")));
                } else if (ATTEMPT_EVENT.equals(entry.event())) {
                    attempts.merge(source, 1, Integer::sum);
                }
            }
        } catch (java.io.IOException e) {
            // A partially written final line must not prevent a new turn from starting.
        }
    }

    private static String str(Object value) { return value == null ? "" : value.toString(); }

    /** Returns the context to attach to this exact turn, or null when injection is not allowed. */
    public String plan(String source, String target, String requestId,
                       com.fasterxml.jackson.databind.node.ObjectNode cancelled,
                       List<com.fasterxml.jackson.databind.node.ObjectNode> effects,
                       int conflicts, int versionCount) {
        if (source == null || source.isBlank() || target == null || target.isBlank()) return null;
        if (sourceToTarget.containsKey(source)) return null;
        // One source may be bound to exactly one target, including while the first request
        // is still in flight: a concurrent turn must never receive the same context.
        if (pendingByTarget.values().stream().anyMatch(pending -> pending.source().equals(source))) {
            return null;
        }
        Pending existing = pendingByTarget.get(target);
        if (existing != null && !existing.source().equals(source)) return null;

        boolean duplicateHint = attempts.getOrDefault(source, 0) > 0;
        int attempt = attempts.merge(source, 1, Integer::sum);
        String context = InterruptionContextBuilder.build(cancelled, effects, conflicts,
                versionCount, duplicateHint);
        String digest = EffectEvent.sha256(context);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source_cancelled_turn_id", source);
        metadata.put("target_turn_id", target);
        metadata.put("request_id", requestId);
        metadata.put("context_digest", digest);
        metadata.put("injection_attempt", attempt);
        metadata.put("attempted_at", Instant.now().toString());
        ledger.appendEvent(ATTEMPT_EVENT, "remote", "server", "effect", metadata);
        pendingByTarget.put(target, new Pending(source, target, requestId, digest, attempt));
        return context;
    }

    /** Called only after an observable send success (first stream delta or completed request). */
    public void markSent(String targetTurnId) {
        Pending pending = pendingByTarget.remove(targetTurnId);
        if (pending == null) return;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source_cancelled_turn_id", pending.source());
        metadata.put("target_turn_id", pending.target());
        metadata.put("request_id", pending.requestId());
        metadata.put("context_digest", pending.digest());
        metadata.put("injection_attempt", pending.attempt());
        metadata.put("injected_at", Instant.now().toString());
        ledger.appendEvent(EffectEvent.CONTEXT_INJECTED, "remote", "server", "effect", metadata);
        sourceToTarget.put(pending.source(), pending.target());
    }

    /** A terminal turn that never had a successful send must not keep a pending binding. */
    public void abandon(String targetTurnId) { pendingByTarget.remove(targetTurnId); }

    public boolean injected(String sourceTurnId) { return sourceToTarget.containsKey(sourceTurnId); }

    public String boundTarget(String sourceTurnId) { return sourceToTarget.get(sourceTurnId); }

    public boolean hasPending(String targetTurnId) { return pendingByTarget.containsKey(targetTurnId); }

    public int attemptCount(String sourceTurnId) { return attempts.getOrDefault(sourceTurnId, 0); }
}

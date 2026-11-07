package com.clix.remote;

import com.clix.effect.EffectEvent;
import com.clix.effect.EffectIngestor;
import com.clix.effect.InterruptionContextBuilder;
import com.clix.effect.InterruptionInjector;
import com.clix.history.ConversationLedger;
import com.clix.tool.ToolRegistry.ToolInvocation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Server-side phase-three coordinator: idempotent effect ingest, append-only
 * ConversationLedger events and one-shot interruption context injection.
 *
 * <p>Ingest never closes the WebSocket: a conflicting observation is a fact to keep, not a
 * protocol error (design doc V1.2 E). The injection marker is written only after the model
 * request carrying the context was observably sent (V1.2 F).
 */
final class RemoteEffectCoordinator {
    private static final int HISTORY_LIMIT = 64;

    private static final class TurnFacts {
        volatile String userMessage = "";
        final List<Map<String, Object>> completedTools = new ArrayList<>();
        final LinkedHashMap<String, Map<String, Object>> inFlight = new LinkedHashMap<>();
    }

    private final ConversationLedger ledger;
    private final EffectIngestor ingestor;
    private final InterruptionInjector injector;
    private final Map<String, TurnFacts> facts = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> cancelledRecords = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> factOrder = new ConcurrentLinkedQueue<>();
    private final AtomicReference<String> lastCancelledTurnId = new AtomicReference<>();
    private final AtomicReference<String> lastCompletedTurnId = new AtomicReference<>();

    RemoteEffectCoordinator(ConversationLedger ledger) {
        this.ledger = java.util.Objects.requireNonNull(ledger, "ledger");
        this.ingestor = new EffectIngestor((type, metadata) ->
                ledger.appendEvent(type, "remote", "server", "effect", metadata));
        this.injector = new InterruptionInjector(ledger);
    }

    private TurnFacts facts(String turnId) {
        return facts.computeIfAbsent(turnId, ignored -> {
            factOrder.add(turnId);
            while (factOrder.size() > HISTORY_LIMIT) {
                String oldest = factOrder.poll();
                if (oldest != null) facts.remove(oldest);
            }
            return new TurnFacts();
        });
    }

    void turnStarted(String turnId, String userMessage) {
        TurnFacts f = facts(turnId);
        f.userMessage = userMessage == null ? "" : userMessage;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("turn_id", turnId);
        metadata.put("user_message", f.userMessage);
        ledger.appendEvent("turn_started", "remote", "server", "turn", metadata);
    }

    void toolsRequested(String turnId, List<ToolInvocation> calls) {
        TurnFacts f = facts(turnId);
        synchronized (f) {
            if (calls != null) for (ToolInvocation call : calls) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("tool_call_id", call.id());
                entry.put("name", call.name());
                entry.put("arguments_json", call.argumentsJson());
                f.inFlight.put(call.id(), entry);
            }
        }
    }

    void toolsCompleted(String turnId, JsonNode results) {
        if (results == null || !results.isArray()) return;
        TurnFacts f = facts(turnId);
        synchronized (f) {
            for (JsonNode result : results) {
                String id = result.path("id").asText("");
                String name = result.path("name").asText("tool");
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", name);
                entry.put("successful", result.path("successful").asBoolean(false));
                List<String> paths = affectedPaths(result.path("argumentsJson").asText(""));
                if (!paths.isEmpty()) entry.put("affected_paths", paths);
                f.completedTools.add(entry);
                f.inFlight.remove(id);
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("turn_id", turnId);
        metadata.put("completed_count", results.size());
        ledger.appendEvent("tool_terminalized", "remote", "server", "effect", metadata);
    }

    private static List<String> affectedPaths(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return List.of();
        try {
            JsonNode args = EffectEvent.JSON.readTree(argumentsJson);
            String path = args.path("path").asText("");
            return path.isBlank() ? List.of() : List.of(path);
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Accepts late, out-of-order and conflicting observations without any connection effect. */
    void ingest(ObjectNode wire) {
        if (wire == null) return;
        String turnId = wire.path("turn_id").asText("");
        ingestor.ingest(wire, turnId);
    }

    /** Enriches turn.cancelled and appends the structured ledger record. */
    ObjectNode cancelled(String turnId, ObjectNode payload) {
        ObjectNode view = ingestor.currentView(turnId);
        TurnFacts f = facts(turnId);
        ObjectNode record = EffectEvent.JSON.createObjectNode();
        record.put("event", "turn_cancelled");
        record.put("turn_id", turnId);
        record.put("user_message", f.userMessage);
        record.put("stage", "tool_execution");
        record.put("termination", text(payload, "termination", "unknown"));
        record.put("transport_cancel_status", text(payload, "transport_cancel_status", "unknown"));
        record.put("provider_cancel_ack", text(payload, "provider_cancel_ack", "unsupported"));
        record.put("effect_audit", view.path("effect_audit").asText("unavailable"));
        record.put("external_side_effects_possible", view.path("external_side_effects_possible").asBoolean(false));
        record.put("conflict_count", view.path("conflict_count").asInt(0));
        record.put("version_count", view.path("version_count").asInt(0));
        record.put("needs_verification", view.path("needs_verification").asBoolean(false));

        ArrayNode completed = EffectEvent.JSON.createArrayNode();
        ArrayNode inFlight = EffectEvent.JSON.createArrayNode();
        synchronized (f) {
            for (Map<String, Object> tool : f.completedTools) completed.add(EffectEvent.JSON.valueToTree(tool));
            for (Map<String, Object> tool : f.inFlight.values()) inFlight.add(EffectEvent.JSON.valueToTree(tool));
        }
        record.set("completed_tools", completed);
        ObjectNode interrupted = EffectEvent.JSON.createObjectNode();
        if (!inFlight.isEmpty()) {
            JsonNode last = inFlight.get(inFlight.size() - 1);
            interrupted.put("name", last.path("name").asText("unknown"));
            interrupted.put("command_summary", summaryOf(last.path("arguments_json").asText("")));
            interrupted.put("effect_audit", view.path("effect_audit").asText("unavailable"));
            interrupted.set("observed_paths", view.path("observed_paths").deepCopy());
        }
        record.set("interrupted_tool", interrupted);
        record.set("effects", effectsArray(turnId));

        Map<String, Object> metadata = EffectEvent.JSON.convertValue(record,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        ledger.appendEvent(EffectEvent.TURN_CANCELLED, "remote", "server", "turn", metadata);

        cancelledRecords.put(turnId, record);
        lastCancelledTurnId.set(turnId);
        payload.put("effect_audit", view.path("effect_audit").asText("unavailable"));
        payload.put("conflict_count", view.path("conflict_count").asInt(0));
        payload.put("version_count", view.path("version_count").asInt(0));
        payload.put("needs_verification", view.path("needs_verification").asBoolean(false));
        payload.put("external_side_effects_possible", view.path("external_side_effects_possible").asBoolean(false));
        payload.put("user_message", f.userMessage);
        payload.put("stage", "tool_execution");
        payload.set("completed_tools", completed.deepCopy());
        payload.set("interrupted_tool", interrupted.deepCopy());
        payload.set("effects", record.path("effects").deepCopy());
        return record;
    }

    private static String summaryOf(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return "（无摘要）";
        try {
            JsonNode args = EffectEvent.JSON.readTree(argumentsJson);
            String command = args.path("command").asText("");
            if (!command.isBlank()) return com.clix.effect.CommandEffectProbe.summary(command);
            String path = args.path("path").asText("");
            return path.isBlank() ? "（无摘要）" : path;
        } catch (IOException e) {
            return "（无摘要）";
        }
    }

    private ArrayNode effectsArray(String turnId) {
        ArrayNode array = EffectEvent.JSON.createArrayNode();
        for (ObjectNode effect : ingestor.effectsForTurn(turnId)) array.add(effect.deepCopy());
        return array;
    }

    void turnCompleted(String turnId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("turn_id", turnId);
        ledger.appendEvent("turn_completed", "remote", "server", "turn", metadata);
        lastCompletedTurnId.set(turnId);
    }

    /**
     * Plans one injection for a new turn. Returns null when the client already attached the
     * same context to the user message (avoids duplicating it), while still binding the
     * source/target pair for the injected ledger event.
     */
    String planInjection(String target, String requestId, boolean clientAlreadyAttached) {
        String source = lastCancelledTurnId.get();
        if (source == null || source.isBlank() || source.equals(target)) return null;
        ObjectNode cancelled = cancelledRecords.get(source);
        if (cancelled == null) return null;
        ObjectNode view = ingestor.currentView(source);
        String context = injector.plan(source, target, requestId, cancelled,
                ingestor.effectsForTurn(source), view.path("conflict_count").asInt(0),
                view.path("version_count").asInt(0));
        if (context == null) return null;
        return clientAlreadyAttached ? "" : context;
    }

    void markSent(String targetTurnId) { injector.markSent(targetTurnId); }
    void abandon(String targetTurnId) { injector.abandon(targetTurnId); }
    boolean injected(String sourceTurnId) { return injector.injected(sourceTurnId); }
    String boundTarget(String sourceTurnId) { return injector.boundTarget(sourceTurnId); }
    boolean hasPending(String turnId) { return injector.hasPending(turnId); }
    boolean rejectsReusedOperation(String operationId, String turnId) {
        return ingestor.rejectsReusedOperation(operationId, turnId);
    }

    ObjectNode view(String turnId) { return ingestor.currentView(turnId); }
    String lastCancelledTurnId() { return lastCancelledTurnId.get(); }
    String lastCompletedTurnId() { return lastCompletedTurnId.get(); }
    String newRequestId() { return UUID.randomUUID().toString(); }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node == null ? "" : node.path(field).asText("");
        return value.isBlank() ? fallback : value;
    }
}

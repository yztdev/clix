package com.clix.effect;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Builds the wire-level {@code tool.effect.payload} (design doc H) and the matching local
 * audit event. {@code audit_scope} and {@code audit_limitations} are mandatory and are
 * always present, even when there are no known limitations: an empty array, never null.
 */
public final class EffectReport {
    private EffectReport() { }

    public static ObjectNode payload(String turnId, String toolCallId, String operationId,
                                     String tool, String outcome) {
        ObjectNode node = EffectEvent.JSON.createObjectNode();
        node.put("turn_id", turnId == null ? "" : turnId);
        node.put("tool_call_id", toolCallId == null ? "" : toolCallId);
        node.put("operation_id", operationId == null ? "" : operationId);
        node.put("tool", tool == null ? "" : tool);
        node.put("outcome", outcome == null ? "cancelled_unknown" : outcome);
        node.put("effect_audit", "unavailable");
        node.put("audit_scope", "none");
        node.set("audit_limitations", EffectEvent.JSON.createArrayNode());
        node.set("observed_paths", EffectEvent.JSON.createArrayNode());
        node.set("files", EffectEvent.JSON.createArrayNode());
        node.put("external_side_effects_possible", false);
        node.put("sequence", 0L);
        node.put("schema_version", EffectEvent.SCHEMA_VERSION);
        node.put("content_hash", "");
        return node;
    }

    public static void putStrings(ObjectNode node, String field, List<String> values) {
        ArrayNode array = EffectEvent.JSON.createArrayNode();
        if (values != null) for (String value : values) array.add(value == null ? "" : value);
        node.set(field, array);
    }

    /** Finalizes identity fields and the content hash in one place. */
    public static ObjectNode finalize(ObjectNode payload, long sequence) {
        payload.remove("event_id");
        payload.remove("occurred_at");
        payload.put("sequence", sequence);
        payload.put("schema_version", EffectEvent.SCHEMA_VERSION);
        payload.put("content_hash", "");
        payload.put("content_hash", EffectEvent.wireHash(payload));
        EffectEvent event = terminalEvent(payload, sequence);
        payload.put("event_id", event.eventId());
        payload.put("occurred_at", event.occurredAt());
        payload.put("event_type", EffectEvent.TOOL_EFFECT);
        return payload;
    }

    public static EffectEvent terminalEvent(ObjectNode wire, long sequence) {
        Map<String, Object> body = EffectEvent.JSON.convertValue(wire,
                new TypeReference<Map<String, Object>>() { });
        String hash = wire.path("content_hash").asText("");
        if (hash.isBlank()) hash = EffectEvent.create(EffectEvent.TOOL_EFFECT,
                wire.path("turn_id").asText(""), wire.path("tool_call_id").asText(""),
                wire.path("operation_id").asText(""), sequence, body).contentHash();
        return EffectEvent.createWithHash(EffectEvent.TOOL_EFFECT,
                wire.path("turn_id").asText(""), wire.path("tool_call_id").asText(""),
                wire.path("operation_id").asText(""), sequence, body, hash);
    }

    public static ObjectNode fileEntry(String path, String operation, String beforeStatus,
                                       String beforeSha, String afterStatus, String afterSha,
                                       long bytesWritten, String outcome) {
        ObjectNode node = EffectEvent.JSON.createObjectNode();
        node.put("path", path);
        node.put("operation", operation);
        node.put("before_status", beforeStatus);
        node.put("before_sha256", beforeSha == null ? "" : beforeSha);
        node.put("after_status", afterStatus);
        node.put("after_sha256", afterSha == null ? "" : afterSha);
        node.put("bytes_written", bytesWritten);
        node.put("outcome", outcome);
        return node;
    }
}

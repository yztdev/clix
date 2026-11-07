package com.clix.effect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Unified, append-only audit event (design doc V1.2 A).
 *
 * <p>Every local audit record and every server-side ledger event carries the same identity
 * block. {@code occurred_at} is diagnostic only; ordering uses {@code sequence}. The
 * {@code content_hash} is computed over a canonical (field-sorted) JSON rendering so that
 * two logically identical payloads always hash alike regardless of insertion order.
 */
public record EffectEvent(
        String eventId,
        String eventType,
        String turnId,
        String toolCallId,
        String operationId,
        String occurredAt,
        long sequence,
        int schemaVersion,
        String contentHash,
        Map<String, Object> payload) {

    public static final int SCHEMA_VERSION = 2;
    public static final ObjectMapper JSON = new ObjectMapper();
    /** Volatile transport fields are never part of the logical content hash (design doc H/E). */
    public static final java.util.Set<String> HASH_EXCLUDED = java.util.Set.of(
            "event_id", "event_type", "occurred_at", "content_hash", "sequence", "schema_version");

    public static final String EFFECT_STARTED = "effect_started";
    public static final String TOOL_EFFECT = "tool.effect";
    public static final String TURN_CANCELLED = "turn_cancelled";
    public static final String EFFECT_CONFLICT = "effect_conflict";
    public static final String SEQUENCE_CONFLICT = "sequence_conflict";
    public static final String CONTEXT_INJECTED = "interruption_context_injected";

    public static EffectEvent create(String eventType, String turnId, String toolCallId,
                                     String operationId, long sequence, Map<String, Object> payload) {
        Map<String, Object> safe = payload == null ? Map.of() : new LinkedHashMap<>(payload);
        ObjectNode canonical = JSON.createObjectNode();
        canonical.set("event_type", JSON.valueToTree(eventType));
        canonical.set("turn_id", JSON.valueToTree(turnId));
        canonical.set("tool_call_id", JSON.valueToTree(toolCallId));
        canonical.set("operation_id", JSON.valueToTree(operationId));
        canonical.set("payload", JSON.valueToTree(safe));
        return new EffectEvent(
                UUID.randomUUID().toString(),
                eventType,
                turnId,
                toolCallId,
                operationId,
                Instant.now().toString(),
                sequence,
                SCHEMA_VERSION,
                contentHash(canonical),
                Map.copyOf(safe));
    }

    /** Content hash of the identity fields plus payload; excludes event id/time/sequence. */
    public static String contentHash(JsonNode node) {
        StringBuilder canonical = new StringBuilder();
        appendCanonical(node, canonical);
        return sha256(canonical.toString());
    }

    /**
     * Hash of a wire-level effect payload. Identity/diagnostic fields are removed first so
     * that a duplicate re-send hashes identically and a genuine observation change does not.
     * Both client and server must use this one function or conflict detection is meaningless.
     */
    public static String wireHash(ObjectNode wire) {
        ObjectNode canonical = wire == null ? JSON.createObjectNode() : wire.deepCopy();
        for (String field : HASH_EXCLUDED) canonical.remove(field);
        return contentHash(canonical);
    }

    /** Builds an audit event that carries exactly the same content hash as the wire payload. */
    public static EffectEvent createWithHash(String eventType, String turnId, String toolCallId,
                                             String operationId, long sequence,
                                             Map<String, Object> payload, String hash) {
        Map<String, Object> safe = payload == null ? Map.of() : new LinkedHashMap<>(payload);
        return new EffectEvent(
                UUID.randomUUID().toString(), eventType, turnId, toolCallId, operationId,
                Instant.now().toString(), sequence, SCHEMA_VERSION, hash, Map.copyOf(safe));
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return "sha256:" + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void appendCanonical(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull()) { out.append("null"); return; }
        if (node.isObject()) {
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);
            out.append('{');
            for (String key : keys) {
                out.append(key).append(':');
                appendCanonical(node.get(key), out);
                out.append(',');
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (JsonNode child : node) { appendCanonical(child, out); out.append(','); }
            out.append(']');
        } else {
            out.append(node.toString());
        }
    }

    public ObjectNode toNode() {
        ObjectNode node = JSON.createObjectNode();
        node.put("event_id", eventId);
        node.put("event_type", eventType);
        node.put("turn_id", turnId == null ? "" : turnId);
        node.put("tool_call_id", toolCallId == null ? "" : toolCallId);
        node.put("operation_id", operationId == null ? "" : operationId);
        node.put("occurred_at", occurredAt);
        node.put("sequence", sequence);
        node.put("schema_version", schemaVersion);
        node.put("content_hash", contentHash);
        node.set("payload", JSON.valueToTree(payload));
        return node;
    }

    public static EffectEvent fromNode(JsonNode node) {
        Map<String, Object> payload = new LinkedHashMap<>();
        JsonNode p = node.path("payload");
        if (p.isObject()) p.fields().forEachRemaining(entry -> payload.put(entry.getKey(),
                JSON.convertValue(entry.getValue(), Object.class)));
        return new EffectEvent(
                node.path("event_id").asText(""),
                node.path("event_type").asText(""),
                node.path("turn_id").asText(""),
                node.path("tool_call_id").asText(""),
                node.path("operation_id").asText(""),
                node.path("occurred_at").asText(""),
                node.path("sequence").asLong(0L),
                node.path("schema_version").asInt(1),
                node.path("content_hash").asText(""),
                Map.copyOf(payload));
    }

    /** Recompute the hash from this event's identity fields and payload (for ingest checks). */
    public String recomputedHash() {
        ObjectNode canonical = JSON.createObjectNode();
        canonical.put("event_type", eventType);
        canonical.put("turn_id", turnId);
        canonical.put("tool_call_id", toolCallId);
        canonical.put("operation_id", operationId);
        canonical.set("payload", JSON.valueToTree(payload));
        return contentHash(canonical);
    }
}

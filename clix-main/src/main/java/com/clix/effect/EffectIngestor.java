package com.clix.effect;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Server-side idempotent ingest for {@code tool.effect} (design doc V1.2 E).
 *
 * <p>The logical key is {@code turn_id + tool_call_id + operation_id}, but a key may keep
 * several content versions. Conflicts are observations disagreeing, never a protocol error:
 * the connection stays open and new turns are never blocked.
 */
public final class EffectIngestor {
    public enum Outcome { ACCEPTED, DUPLICATE, CONFLICT, SEQUENCE_CONFLICT, REJECTED }

    public record Version(long sequence, String contentHash, ObjectNode payload) { }

    public record Ingest(Outcome outcome, String key, int versionCount) { }

    private final Map<String, List<Version>> versions = new ConcurrentHashMap<>();
    private final Map<String, Integer> conflictsByKey = new ConcurrentHashMap<>();
    private final Map<String, String> operationOwner = new ConcurrentHashMap<>();
    private final List<String> conflictDiagnostics = new CopyOnWriteArrayList<>();
    private final AtomicInteger conflictCount = new AtomicInteger();
    private final AtomicInteger sequenceConflictCount = new AtomicInteger();
    private final BiConsumer<String, Map<String, Object>> appendEvent;

    public EffectIngestor(BiConsumer<String, Map<String, Object>> appendEvent) {
        this.appendEvent = appendEvent == null ? (type, metadata) -> { } : appendEvent;
    }

    public Ingest ingest(ObjectNode wire, String activeTurnId) {
        normalizeDefaults(wire);
        String turnId = wire.path("turn_id").asText("");
        String toolCallId = wire.path("tool_call_id").asText("");
        String operationId = wire.path("operation_id").asText("");
        String key = turnId + "|" + toolCallId + "|" + operationId;

        // A /retry must never reuse an operation id, even if the command text is identical.
        String owner = operationOwner.putIfAbsent(operationId, turnId);
        if (owner != null && !owner.equals(turnId)) {
            Map<String, Object> diagnostic = new LinkedHashMap<>();
            diagnostic.put("operation_id", operationId);
            diagnostic.put("owner_turn_id", owner);
            diagnostic.put("rejected_turn_id", turnId);
            appendEvent.accept("effect_rejected", diagnostic);
            return new Ingest(Outcome.REJECTED, key, 0);
        }

        String hash = wire.path("content_hash").asText("");
        long sequence = wire.path("sequence").asLong(0L);
        List<Version> existing = versions.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>());
        for (Version version : existing) {
            if (version.contentHash().equals(hash)) {
                return new Ingest(Outcome.DUPLICATE, key, existing.size());
            }
        }

        Outcome outcome = Outcome.ACCEPTED;
        if (!existing.isEmpty()) {
            boolean sameSequence = existing.stream().anyMatch(v -> v.sequence() == sequence);
            if (sameSequence) {
                outcome = Outcome.SEQUENCE_CONFLICT;
                sequenceConflictCount.incrementAndGet();
                appendEvent.accept(EffectEvent.SEQUENCE_CONFLICT, conflictMetadata(key, wire, existing, sequence));
            } else {
                outcome = Outcome.CONFLICT;
                conflictCount.incrementAndGet();
                appendEvent.accept(EffectEvent.EFFECT_CONFLICT, conflictMetadata(key, wire, existing, sequence));
            }
            conflictsByKey.merge(key, 1, Integer::sum);
            conflictDiagnostics.add(key + " sequence=" + sequence + " hash=" + hash);
        }
        existing.add(new Version(sequence, hash, wire.deepCopy()));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("key", key);
        metadata.put("sequence", sequence);
        metadata.put("content_hash", hash);
        metadata.put("turn_id", turnId);
        metadata.put("tool_call_id", toolCallId);
        metadata.put("operation_id", operationId);
        metadata.put("effect_audit", wire.path("effect_audit").asText("unavailable"));
        metadata.put("outcome", wire.path("outcome").asText("cancelled_unknown"));
        appendEvent.accept(EffectEvent.TOOL_EFFECT, metadata);
        return new Ingest(outcome, key, existing.size());
    }

    private Map<String, Object> conflictMetadata(String key, ObjectNode wire,
                                                 List<Version> existing, long sequence) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("key", key);
        metadata.put("incoming_sequence", sequence);
        metadata.put("incoming_content_hash", wire.path("content_hash").asText(""));
        List<String> hashes = new ArrayList<>();
        for (Version version : existing) hashes.add(version.contentHash());
        metadata.put("existing_content_hashes", hashes);
        metadata.put("versions_retained", existing.size() + 1);
        return metadata;
    }

    /** Missing fields get conservative schema defaults; absence is never read as false/confirmed. */
    private static void normalizeDefaults(ObjectNode wire) {
        if (!wire.has("effect_audit") || wire.path("effect_audit").asText("").isBlank()) {
            wire.put("effect_audit", "unavailable");
        }
        if (!wire.has("audit_scope") || wire.path("audit_scope").asText("").isBlank()) {
            wire.put("audit_scope", "unspecified");
        }
        if (!wire.has("audit_limitations") || !wire.path("audit_limitations").isArray()) {
            wire.set("audit_limitations", EffectEvent.JSON.createArrayNode().add("schema_defaults_applied"));
        }
        if (!wire.has("observed_paths") || !wire.path("observed_paths").isArray()) {
            wire.set("observed_paths", EffectEvent.JSON.createArrayNode());
        }
        if (!wire.has("files") || !wire.path("files").isArray()) {
            wire.set("files", EffectEvent.JSON.createArrayNode());
        }
        if (!wire.has("external_side_effects_possible")) {
            wire.put("external_side_effects_possible", true);
            ((ArrayNode) wire.path("audit_limitations")).add("external_side_effects_defaulted_to_possible");
        }
        if (!wire.has("outcome") || wire.path("outcome").asText("").isBlank()) {
            wire.put("outcome", "cancelled_unknown");
        }
        if (!wire.has("sequence")) wire.put("sequence", 0L);
        if (!wire.has("schema_version")) wire.put("schema_version", 1);
        if (!wire.has("content_hash") || wire.path("content_hash").asText("").isBlank()) {
            wire.put("content_hash", EffectEvent.wireHash(wire));
        }
    }

    /** The recovery view prefers the largest sequence but keeps older versions. */
    public ObjectNode currentView(String turnId) {
        ObjectNode view = EffectEvent.JSON.createObjectNode();
        Set<String> observed = new LinkedHashSet<>();
        ArrayNode files = EffectEvent.JSON.createArrayNode();
        String grade = "verified";
        boolean external = false;
        int count = 0;
        String prefix = turnId + "|";
        for (Map.Entry<String, List<Version>> entry : versions.entrySet()) {
            if (!entry.getKey().startsWith(prefix) || entry.getValue().isEmpty()) continue;
            Version latest = entry.getValue().stream()
                    .max(Comparator.comparingLong(Version::sequence)).orElseThrow();
            count++;
            String candidate = latest.payload().path("effect_audit").asText("unavailable");
            if ("unavailable".equals(candidate)) grade = "unavailable";
            else if ("partial".equals(candidate) && !"unavailable".equals(grade)) grade = "partial";
            external |= latest.payload().path("external_side_effects_possible").asBoolean(false);
            latest.payload().path("observed_paths").forEach(node -> observed.add(node.asText("")));
            latest.payload().path("files").forEach(files::add);
        }
        if (count == 0) grade = "unavailable";
        int turnConflicts = conflictsForTurn(turnId);
        view.put("effect_audit", grade);
        view.put("effect_count", count);
        view.put("conflict_count", turnConflicts);
        view.put("sequence_conflict_count", sequenceConflictCount.get());
        view.put("version_count", versionCount(turnId));
        view.put("external_side_effects_possible", external);
        view.put("needs_verification", turnConflicts > 0 || grade.equals("unavailable"));
        EffectReport.putStrings(view, "observed_paths", new ArrayList<>(observed));
        view.set("files", files);
        return view;
    }

    public List<ObjectNode> effectsForTurn(String turnId) {
        List<ObjectNode> result = new ArrayList<>();
        String prefix = turnId + "|";
        for (Map.Entry<String, List<Version>> entry : versions.entrySet()) {
            if (!entry.getKey().startsWith(prefix) || entry.getValue().isEmpty()) continue;
            Version latest = entry.getValue().stream()
                    .max(Comparator.comparingLong(Version::sequence)).orElseThrow();
            result.add(latest.payload());
        }
        return List.copyOf(result);
    }

    public int versionCount(String turnId) {
        String prefix = turnId + "|";
        return versions.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .mapToInt(entry -> entry.getValue().size()).sum();
    }

    /** Conflicts are counted per turn so one turn's disagreement never contaminates a new turn. */
    public int conflictsForTurn(String turnId) {
        String prefix = turnId + "|";
        return conflictsByKey.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .mapToInt(Map.Entry::getValue).sum();
    }

    public int conflictCount() { return conflictCount.get(); }
    public int sequenceConflictCount() { return sequenceConflictCount.get(); }
    public List<String> conflictDiagnostics() { return List.copyOf(conflictDiagnostics); }

    /** True when an operation id was already consumed by another turn (/retry guard). */
    public boolean rejectsReusedOperation(String operationId, String turnId) {
        String owner = operationOwner.get(operationId);
        return owner != null && !owner.equals(turnId);
    }
}

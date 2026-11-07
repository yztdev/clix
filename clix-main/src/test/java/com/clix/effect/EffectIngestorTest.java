package com.clix.effect;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** V1.2 E and I(12): idempotence, conflicts, out-of-order versions, no connection effect. */
class EffectIngestorTest {
    private final List<String> eventTypes = new ArrayList<>();
    private final EffectIngestor ingestor =
            new EffectIngestor((type, metadata) -> eventTypes.add(type));

    private static ObjectNode wire(String turn, String operation, long sequence, String audit) {
        ObjectNode node = EffectReport.payload(turn, "call-1", operation,
                "execute_command", "cancelled_unknown");
        node.put("effect_audit", audit);
        node.put("audit_scope", "git_worktree_delta");
        EffectReport.putStrings(node, "audit_limitations", List.of("shell_black_box"));
        EffectReport.putStrings(node, "observed_paths", List.of("target/a.txt"));
        EffectReport.finalize(node, sequence);
        return node;
    }

    @Test void duplicateIsIgnoredButEveryDifferentVersionIsRetained() {
        ObjectNode first = wire("t1", "op-1", 1L, "partial");
        assertEquals(EffectIngestor.Outcome.ACCEPTED, ingestor.ingest(first, "t1").outcome());
        assertEquals(EffectIngestor.Outcome.DUPLICATE,
                ingestor.ingest(first.deepCopy(), "t1").outcome());
        assertEquals(1, ingestor.versionCount("t1"));

        assertEquals(EffectIngestor.Outcome.CONFLICT,
                ingestor.ingest(wire("t1", "op-1", 2L, "verified"), "t1").outcome());
        assertEquals(EffectIngestor.Outcome.SEQUENCE_CONFLICT,
                ingestor.ingest(wire("t1", "op-1", 1L, "unavailable"), "t1").outcome());
        ObjectNode late = wire("t1", "op-1", 0L, "partial");
        EffectReport.putStrings(late, "observed_paths", List.of("target/a.txt", "target/b.txt"));
        EffectReport.finalize(late, 0L);
        assertEquals(EffectIngestor.Outcome.CONFLICT, ingestor.ingest(late, "t1").outcome(),
                "sequence 较小、晚到仍追加为历史版本，不覆盖较新视图");
        assertEquals(4, ingestor.versionCount("t1"));
        assertTrue(eventTypes.contains(EffectEvent.EFFECT_CONFLICT));
        assertTrue(eventTypes.contains(EffectEvent.SEQUENCE_CONFLICT));
        assertEquals(3, ingestor.conflictCount() + ingestor.sequenceConflictCount());

        ObjectNode view = ingestor.currentView("t1");
        assertEquals("verified", view.path("effect_audit").asText(), "恢复视图优先较大 sequence");
        assertTrue(view.path("needs_verification").asBoolean());
        assertEquals(4, view.path("version_count").asInt());
        assertTrue(view.path("observed_paths").toString().contains("target/a.txt"));
    }

    @Test void reusedOperationIdIsRejectedButNeverBlocksANewTurn() {
        ingestor.ingest(wire("t1", "op-1", 1L, "partial"), "t1");
        EffectIngestor.Ingest rejected = ingestor.ingest(wire("t2", "op-1", 1L, "partial"), "t2");
        assertEquals(EffectIngestor.Outcome.REJECTED, rejected.outcome());
        assertEquals(0, ingestor.versionCount("t2"));
        assertTrue(ingestor.rejectsReusedOperation("op-1", "t2"));
        assertEquals(EffectIngestor.Outcome.ACCEPTED,
                ingestor.ingest(wire("t2", "op-2", 1L, "partial"), "t2").outcome(),
                "拒绝旧 operation 后新 Turn 不受阻塞");
        assertTrue(eventTypes.contains("effect_rejected"));
    }

    @Test void missingFieldsGetConservativeSchemaDefaults() {
        ObjectNode raw = EffectEvent.JSON.createObjectNode();
        raw.put("turn_id", "t3");
        raw.put("tool_call_id", "c3");
        raw.put("operation_id", "o3");
        assertEquals(EffectIngestor.Outcome.ACCEPTED, ingestor.ingest(raw, "t3").outcome());
        assertEquals("unavailable", raw.path("effect_audit").asText());
        assertEquals("unspecified", raw.path("audit_scope").asText());
        assertTrue(raw.path("audit_limitations").isArray());
        assertTrue(raw.path("audit_limitations").toString().contains("schema_defaults_applied"));
        assertTrue(raw.path("observed_paths").isArray());
        assertTrue(raw.path("external_side_effects_possible").asBoolean());
        assertEquals("cancelled_unknown", raw.path("outcome").asText());
        assertTrue(raw.path("content_hash").asText().startsWith("sha256:"));
    }
}

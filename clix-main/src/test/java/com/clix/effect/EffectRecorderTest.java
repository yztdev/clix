package com.clix.effect;

import com.clix.tool.ToolRegistry.ToolExecutionResult;
import com.clix.tool.ToolRegistry.ToolInvocation;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** V1.2 I(1)(2)(3): audit grade is independent of outcome and flush failures never claim verified. */
class EffectRecorderTest {
    @TempDir Path root;

    private final List<ObjectNode> published = new ArrayList<>();

    private EffectRecorder recorder(Path audit) {
        EffectRecorder recorder = new EffectRecorder(root, audit,
                (type, payload) -> { if ("tool.effect".equals(type)) published.add(payload.deepCopy()); },
                command -> { throw new AssertionError("非 Git 项目不得启动推断式扫描"); });
        recorder.beginTurn(UUID.randomUUID().toString());
        return recorder;
    }

    private static ToolInvocation write(String id, String path, String content) {
        return new ToolInvocation(id, "write_file",
                "{\"path\":\"" + path + "\",\"content\":\"" + content + "\"}");
    }

    private static ToolExecutionResult result(ToolInvocation call, boolean successful, boolean timedOut) {
        return new ToolExecutionResult(call.id(), call.name(), call.argumentsJson(),
                successful ? "文件已写入" : "写入文件失败", 0L, timedOut, List.of(), successful, List.of());
    }

    @Test void verifiedAuditIsIndependentOfOutcomeAndCarriesBeforeAfterHashes() throws Exception {
        Files.createDirectories(root.resolve("src"));
        for (int attempt = 0; attempt < 3; attempt++) {
            published.clear();
            EffectRecorder recorder = recorder(root.resolve("audit-" + attempt + ".jsonl"));
            ToolInvocation call = write("call-" + attempt, "src/App.java", "class App {}");
            List<EffectRecorder.Capture> captures = recorder.before(List.of(call));
            if (attempt == 0) Files.writeString(root.resolve("src/App.java"), "class App {}");
            String expectedOutcome = switch (attempt) {
                case 0 -> "completed";
                case 1 -> "failed";
                default -> "cancelled_unknown";
            };
            ToolExecutionResult outcome = attempt == 2 ? null
                    : result(call, attempt == 0, false);
            recorder.after(captures, outcome == null ? null : List.of(outcome));

            assertEquals(1, published.size());
            ObjectNode wire = published.get(0);
            assertEquals(expectedOutcome, wire.path("outcome").asText());
            assertEquals("verified", wire.path("effect_audit").asText(),
                    "FAILED / CANCELLED_UNKNOWN 仍可 verified：记录的是观察范围而非成功与否");
            assertEquals("single_target_path", wire.path("audit_scope").asText());
            assertTrue(wire.path("audit_limitations").isArray());
            assertTrue(wire.path("content_hash").asText().startsWith("sha256:"));
            assertEquals(EffectEvent.SCHEMA_VERSION, wire.path("schema_version").asInt());
            assertTrue(wire.path("sequence").asLong() > 0);
            ObjectNode file = (ObjectNode) wire.path("files").get(0);
            assertEquals("src/App.java", file.path("path").asText());
            assertTrue(file.path("before_sha256").asText().isEmpty()
                    || file.path("before_sha256").asText().startsWith("sha256:"));
            if (attempt == 0) {
                assertEquals("absent", file.path("before_status").asText());
                assertEquals("present", file.path("after_status").asText());
                assertTrue(file.path("after_sha256").asText().startsWith("sha256:"));
                assertEquals(1, wire.path("observed_paths").size());
            }
            assertFalse(wire.path("operation_id").asText().isBlank());
        }
    }

    @Test void flushFailureAndPathRejectionNeverReportVerified() throws Exception {
        published.clear();
        EffectRecorder recorder = recorder(root.resolve("flush.jsonl"));
        ToolInvocation call = write("c1", "safe.txt", "data");
        List<EffectRecorder.Capture> before = recorder.before(List.of(call));
        recorder.setFlushEnabled(false);
        Files.writeString(root.resolve("safe.txt"), "data");
        recorder.after(before, List.of(result(call, true, false)));
        ObjectNode wire = published.get(0);
        assertEquals("completed", wire.path("outcome").asText());
        assertNotEquals("verified", wire.path("effect_audit").asText());
        assertTrue(wire.path("audit_limitations").toString().contains("audit_flush_failed"));

        published.clear();
        EffectRecorder invalid = recorder(root.resolve("invalid.jsonl"));
        ToolInvocation escaping = write("c2", "../outside.txt", "data");
        List<EffectRecorder.Capture> bad = invalid.before(List.of(escaping));
        invalid.after(bad, List.of(result(escaping, false, false)));
        ObjectNode rejected = published.get(0);
        assertEquals("unavailable", rejected.path("effect_audit").asText());
        assertEquals("none", rejected.path("audit_scope").asText());
        assertEquals("failed", rejected.path("outcome").asText());
    }

    @Test void everyOperationGetsAFreshIdAndTheAuditLogIsAppendOnly() throws Exception {
        published.clear();
        Path audit = root.resolve("append.jsonl");
        EffectRecorder recorder = recorder(audit);
        recorder.before(List.of(write("c1", "a.txt", "x")));
        // Simulate a crash: no terminal record for the first operation.
        published.clear();
        List<EffectRecorder.Capture> second = recorder.before(List.of(write("c2", "b.txt", "y")));
        Files.writeString(root.resolve("b.txt"), "y");
        recorder.after(second, List.of(result(write("c2", "b.txt", "y"), true, false)));

        EffectAuditLog log = new EffectAuditLog(audit);
        List<EffectEvent> events = log.readAll();
        assertEquals(3, events.size(), "两次 effect_started + 一次 tool.effect（第一次模拟崩溃无终态）");
        assertTrue(events.stream().anyMatch(e -> EffectEvent.EFFECT_STARTED.equals(e.eventType())));
        assertTrue(events.stream().anyMatch(e -> EffectEvent.TOOL_EFFECT.equals(e.eventType())));
        assertEquals(1, log.unfinishedOperations().size());
        String firstId = events.get(0).operationId();
        assertNotEquals(firstId, events.get(events.size() - 1).operationId());

        published.clear();
        EffectRecorder restarted = recorder(audit);
        assertEquals(1, restarted.replayUnfinished());
        assertEquals(1, published.size());
        assertEquals("unavailable", published.get(0).path("effect_audit").asText());
        assertEquals("cancelled_unknown", published.get(0).path("outcome").asText());
        assertTrue(published.get(0).path("audit_limitations").toString().contains("possibly_executed"));
        assertEquals(0, new EffectAuditLog(audit).unfinishedOperations().size());
    }

    @Test void executeCommandCarriesRedactedSummaryRealTerminationAndBoundedObservation() {
        published.clear();
        EffectRecorder recorder = recorder(root.resolve("cmd.jsonl"));
        recorder.setTerminationSupplier(() -> "forced");
        String secret = "curl https://example.com -H 'Authorization: Bearer abcd1234SECRET'";
        ToolInvocation call = new ToolInvocation("c1", "execute_command",
                "{\"command\":\"" + secret + "\"}");
        List<EffectRecorder.Capture> captures = recorder.before(List.of(call));
        recorder.after(captures, List.of(new ToolExecutionResult("c1", "execute_command",
                call.argumentsJson(), "已取消", 0L, true, List.of(), false, List.of())));

        ObjectNode wire = published.get(0);
        assertEquals("forced", wire.path("termination").asText());
        assertEquals("cancelled_unknown", wire.path("outcome").asText());
        assertEquals("unavailable", wire.path("effect_audit").asText());
        assertEquals("none", wire.path("audit_scope").asText());
        assertEquals(0, wire.path("observed_paths").size());
        assertTrue(wire.path("command_digest").asText().startsWith("sha256:"));
        assertTrue(wire.path("command_summary").asText().contains("***"));
        assertFalse(wire.path("command_summary").asText().contains("abcd1234SECRET"));
        assertTrue(wire.path("external_side_effects_possible").asBoolean());
        assertTrue(wire.path("audit_limitations").toString().contains("manual_check_required"));
    }

    @Test void wireHashIsStableAcrossResendsAndChangesWithObservation() {
        ObjectNode wire = EffectReport.payload("t1", "c1", "o1", "write_file", "completed");
        wire.put("effect_audit", "verified");
        EffectReport.finalize(wire, 7L);
        String hash = wire.path("content_hash").asText();
        ObjectNode resend = wire.deepCopy();
        resend.put("event_id", UUID.randomUUID().toString());
        resend.put("occurred_at", "2030-01-01T00:00:00Z");
        assertEquals(hash, EffectEvent.wireHash(resend));
        resend.put("effect_audit", "partial");
        assertNotEquals(hash, EffectEvent.wireHash(resend));
    }
}

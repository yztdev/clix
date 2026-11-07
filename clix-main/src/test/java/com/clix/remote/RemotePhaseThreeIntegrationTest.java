package com.clix.remote;

import com.clix.effect.EffectEvent;
import com.clix.effect.EffectRecorder;
import com.clix.effect.EffectReport;
import com.clix.effect.InterruptionContextBuilder;
import com.clix.history.ConversationLedger;
import com.clix.hitl.ApprovalRequest;
import com.clix.hitl.ApprovalResult;
import com.clix.hitl.HitlHandler;
import com.clix.tool.ToolRegistry.ToolInvocation;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Phase-three wiring: client recorder → WebSocket effect frames → ledger → /status, /continue, /retry. */
class RemotePhaseThreeIntegrationTest {
    @TempDir Path root;

    private static final class ApprovingHandler implements HitlHandler {
        private boolean enabled = true;
        public ApprovalResult requestApproval(ApprovalRequest request) { return ApprovalResult.approve(); }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
    }

    private static ObjectNode effect(String turn, String operation, long sequence,
                                     String audit, String observedPath) {
        ObjectNode node = EffectReport.payload(turn, "call-1", operation,
                "execute_command", "cancelled_unknown");
        node.put("effect_audit", audit);
        node.put("audit_scope", "git_worktree_delta");
        EffectReport.putStrings(node, "audit_limitations", List.of("shell_black_box"));
        EffectReport.putStrings(node, "observed_paths", List.of(observedPath));
        node.put("external_side_effects_possible", true);
        EffectReport.finalize(node, sequence);
        return node;
    }

    private static ObjectNode cancellation() {
        return EffectEvent.JSON.createObjectNode()
                .put("turn_id", "turn-1")
                .put("termination", "unknown")
                .put("transport_cancel_status", "unconfirmed")
                .put("provider_cancel_ack", "unsupported")
                .put("workspace_state", "needs_verification");
    }

    @Test void serverAppendsStructuredCancellationFactsAndIngestsLateEffects() throws Exception {
        ConversationLedger ledger = ConversationLedger.open(root.resolve("history"), "session-c");
        RemoteEffectCoordinator coordinator = new RemoteEffectCoordinator(ledger);
        coordinator.turnStarted("turn-1", "运行测试并修复错误");
        coordinator.toolsRequested("turn-1", List.of(new ToolInvocation("call-2",
                "execute_command", "{\"command\":\"mvn test\"}")));
        coordinator.ingest(effect("turn-1", "op-1", 1L, "partial", "target/report.txt"));

        ObjectNode payload = cancellation();
        coordinator.cancelled("turn-1", payload);

        assertEquals("partial", payload.path("effect_audit").asText());
        assertEquals(1, payload.path("effects").size());
        assertEquals("运行测试并修复错误", payload.path("user_message").asText());
        assertTrue(payload.path("interrupted_tool").path("command_summary").asText().contains("mvn test"));
        assertTrue(payload.path("external_side_effects_possible").asBoolean());
        assertTrue(ledger.readAll().stream().anyMatch(e -> "turn_cancelled".equals(e.event())));
        assertTrue(ledger.readAll().stream().anyMatch(e -> EffectEvent.TOOL_EFFECT.equals(e.event())));

        // A late effect for an already terminal turn is still retained, never a protocol error.
        coordinator.ingest(effect("turn-1", "op-2", 2L, "unavailable", "target/late.txt"));
        assertEquals(2, coordinator.view("turn-1").path("effect_count").asInt());
    }

    @Test void clientInterruptionNoteAndStatusUseStructuredFacts() {
        ObjectNode payload = cancellation();
        payload.put("effect_audit", "partial");
        payload.put("conflict_count", 2);
        payload.put("version_count", 3);
        payload.put("needs_verification", true);
        payload.set("effects", EffectEvent.JSON.createArrayNode()
                .add(effect("turn-1", "op-1", 1L, "partial", "target/report.txt")));

        var commands = new RemoteClientCommands(new WorkspaceGuard());
        commands.onTurnStarted("turn-1", "运行测试并修复错误");
        commands.onCancelled(payload, Set.of("op-unfinished"));

        String note = commands.consumeInterruptionNote();
        assertNotNull(note);
        assertTrue(note.startsWith(InterruptionContextBuilder.OPEN));
        assertTrue(note.contains("运行测试并修复错误"));
        assertTrue(note.contains("target/report.txt"));
        assertTrue(note.contains("2 个副作用记录冲突"));
        assertTrue(note.contains("可能已执行但未确认的操作：1 个"));
        assertTrue(note.contains(InterruptionContextBuilder.HARD_CONSTRAINT));
        assertNull(commands.consumeInterruptionNote(), "中断说明只能注入一次");

        commands.onCancelled(payload, Set.of());
        String status = commands.status(null, null, new WorkspaceGuard(), 0, "cgroup");
        assertTrue(status.contains("需重新核验：是"));
        assertTrue(status.contains("保留版本：3 个"));
        assertTrue(status.contains("target/report.txt"));
        assertTrue(status.contains("不代表工具成功"));
    }

    @Test void retryDoesNotCarryOldOperationOrToolCallIdentity() {
        ObjectNode payload = cancellation();
        payload.put("effect_audit", "partial");
        payload.set("effects", EffectEvent.JSON.createArrayNode()
                .add(effect("turn-1", "op-1", 1L, "partial", "target/report.txt")));
        var commands = new RemoteClientCommands(new WorkspaceGuard());
        commands.onTurnStarted("turn-1", "修复取消逻辑");
        commands.onCancelled(payload, Set.of());
        String retry = commands.retryContent();
        assertNotNull(retry);
        assertTrue(retry.contains("修复取消逻辑"));
        assertTrue(retry.contains("重新提交为新 Turn"));
        assertFalse(retry.contains("\"tool_call_id\": \"call-1\""));
        assertNull(commands.consumeInterruptionNote());
    }

    @Test void actualFileToolEmitsEffectFrameWithFreshOperationIdAndHashes() throws Exception {
        List<ObjectNode> frames = new ArrayList<>();
        EffectRecorder recorder = new EffectRecorder(root, root.resolve("audit.jsonl"),
                (type, payload) -> { },
                command -> { throw new AssertionError("未声明 git 观察时不应启动命令"); });
        var registry = new RemoteClientToolRegistry(new ApprovingHandler());
        var tools = new RemoteLocalTools(registry, root, recorder);
        tools.connectEffectSink((type, payload) -> {
            if ("tool.effect".equals(type)) frames.add(payload.deepCopy());
        });
        tools.startTurn(UUID.randomUUID().toString());

        assertTrue(tools.execute(List.of(new ToolInvocation("1", "write_file",
                "{\"path\":\"a.txt\",\"content\":\"one\"}"))).get(0).successful());
        assertTrue(tools.execute(List.of(new ToolInvocation("2", "write_file",
                "{\"path\":\"a.txt\",\"content\":\"two\"}"))).get(0).successful());

        assertEquals(2, frames.size());
        ObjectNode first = frames.get(0);
        ObjectNode second = frames.get(1);
        assertNotEquals(first.path("operation_id").asText(), second.path("operation_id").asText(),
                "每次执行必须生成全新的 operation_id");
        assertNotEquals(first.path("tool_call_id").asText(), second.path("tool_call_id").asText());
        assertEquals("verified", first.path("effect_audit").asText());
        assertEquals("completed", first.path("outcome").asText());
        assertTrue(first.path("content_hash").asText().startsWith("sha256:"));
        assertEquals(EffectEvent.SCHEMA_VERSION, first.path("schema_version").asInt());
        ObjectNode file = (ObjectNode) first.path("files").get(0);
        assertEquals("a.txt", file.path("path").asText());
        assertEquals("absent", file.path("before_status").asText());
        assertTrue(file.path("after_sha256").asText().startsWith("sha256:"));
        assertEquals("present", ((ObjectNode) second.path("files").get(0)).path("before_status").asText());
    }

    @Test void conflictsNeverBlockANewTurnAndAreVisibleInStatus() {
        ConversationLedger ledger;
        try { ledger = ConversationLedger.open(root.resolve("history"), "session-d"); }
        catch (Exception e) { throw new AssertionError(e); }
        RemoteEffectCoordinator coordinator = new RemoteEffectCoordinator(ledger);
        coordinator.ingest(effect("turn-1", "op-1", 1L, "partial", "a.txt"));
        ObjectNode conflicting = effect("turn-1", "op-1", 2L, "unavailable", "b.txt");
        coordinator.ingest(conflicting);
        ObjectNode view = coordinator.view("turn-1");
        assertTrue(view.path("needs_verification").asBoolean());
        assertEquals(2, view.path("version_count").asInt());

        ObjectNode payload = cancellation();
        coordinator.cancelled("turn-1", payload);
        assertTrue(payload.path("conflict_count").asInt() >= 1);
        assertTrue(payload.path("needs_verification").asBoolean());

        // A new turn starts cleanly with its own operation id.
        coordinator.turnStarted("turn-2", "继续");
        coordinator.ingest(effect("turn-2", "op-2", 1L, "partial", "c.txt"));
        assertEquals(1, coordinator.view("turn-2").path("effect_count").asInt());
        assertEquals(0, coordinator.view("turn-2").path("conflict_count").asInt());
    }
}

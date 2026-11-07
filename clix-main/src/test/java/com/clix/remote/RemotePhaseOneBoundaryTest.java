package com.clix.remote;

import com.clix.hitl.ApprovalRequest;
import com.clix.hitl.ApprovalResult;
import com.clix.hitl.HitlHandler;
import com.clix.tool.ToolRegistry.ToolInvocation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Phase-one client boundaries: local commands, workspace gate, interruptible input and approval. */
class RemotePhaseOneBoundaryTest {

    @Test void localCommandsAreParsedLocally() {
        assertEquals(RemoteClientCommands.Kind.STATUS, RemoteClientCommands.parse("/status"));
        assertEquals(RemoteClientCommands.Kind.CONTINUE, RemoteClientCommands.parse("/continue"));
        assertEquals(RemoteClientCommands.Kind.RETRY, RemoteClientCommands.parse("/retry"));
        assertEquals(RemoteClientCommands.Kind.QUARANTINE, RemoteClientCommands.parse("/quarantine"));
        assertEquals(RemoteClientCommands.Kind.QUARANTINE_UNLOCK, RemoteClientCommands.parse("/quarantine unlock --force"));
        assertEquals(RemoteClientCommands.Kind.MODE, RemoteClientCommands.parse("/mode ask"));
        assertEquals(RemoteClientCommands.Kind.HISTORY, RemoteClientCommands.parse("/history abc"));
        assertEquals(RemoteClientCommands.Kind.EXIT, RemoteClientCommands.parse("/quit"));
        assertEquals(RemoteClientCommands.Kind.UNKNOWN, RemoteClientCommands.parse("/compact"));
        assertEquals(RemoteClientCommands.Kind.NONE, RemoteClientCommands.parse("普通输入"));
    }

    @Test void interruptionNoteIsInjectedOnceAndNeverClaimsRollback() {
        var commands = new RemoteClientCommands(new WorkspaceGuard());
        commands.onTurnStarted(java.util.UUID.randomUUID().toString(), "运行测试并修复错误");
        commands.onTurnFinished("CANCELLED", "CANCELLED");
        ObjectNode payload = new ObjectMapper().createObjectNode()
                .put("termination", "unknown")
                .put("transport_cancel_status", "unconfirmed")
                .put("provider_cancel_ack", "unsupported")
                .put("provider_output_quarantined", true)
                .put("effect_audit", "unavailable")
                .put("workspace_state", "needs_verification");
        commands.onCancelled(payload);

        String note = commands.consumeInterruptionNote();
        assertNotNull(note);
        assertTrue(note.startsWith("[CLIX_INTERRUPTED_TURN_CONTEXT]"));
        assertTrue(note.contains("运行测试并修复错误"));
        assertTrue(note.contains("未知风险"));
        assertTrue(note.contains("不要假定被中断的工具未执行或已成功"));
        assertFalse(note.contains("已回滚"));
        assertFalse(note.contains("操作已撤销"));
        assertNull(commands.consumeInterruptionNote(), "中断说明只能注入一次");
        assertFalse(commands.hasPendingInterruption());
    }

    @Test void retryResubmitsOnlyTheOriginalGoalWithANewTurn() {
        var commands = new RemoteClientCommands(new WorkspaceGuard());
        commands.onTurnStarted(java.util.UUID.randomUUID().toString(), "修复 Agent 的取消逻辑");
        commands.onCancelled(new ObjectMapper().createObjectNode());
        String retry = commands.retryContent();
        assertNotNull(retry);
        assertTrue(retry.contains("修复 Agent 的取消逻辑"));
        assertTrue(retry.contains("重新提交为新 Turn"));
        assertFalse(retry.contains("tool_call_id\": \"old"));
        assertNull(commands.consumeInterruptionNote(), "/retry 已消费中断说明，普通输入不会重复注入");
    }

    @Test void quarantinedWorkspaceAllowsReadOnlyOnlyUntilTwoStepRelease() {
        var guard = new WorkspaceGuard();
        guard.quarantine("取消达到 10 秒上限");
        assertEquals(WorkspaceGuard.State.QUARANTINED_UNKNOWN, guard.state());
        assertTrue(guard.allows("read_file"));
        assertTrue(guard.allows("grep_code"));
        assertFalse(guard.allows("write_file"));
        assertFalse(guard.allows("execute_command"));

        String text = guard.beginForcedRelease("D:/project", "alice");
        Matcher matcher = Pattern.compile("unlock-[0-9a-f]{12}").matcher(text);
        assertTrue(matcher.find(), "第一步必须展示一次性确认短语");
        String phrase = matcher.group();
        assertFalse(guard.confirmForcedRelease("unlock-wrong-phrase"));
        assertEquals(WorkspaceGuard.State.QUARANTINED_UNKNOWN, guard.state());
        assertTrue(guard.confirmForcedRelease(phrase));
        assertEquals(WorkspaceGuard.State.FORCED_RELEASED, guard.state());
        assertTrue(guard.allows("write_file"));
        assertTrue(guard.statusLine().contains("未经过监管域核验"));
    }

    @Test void workspaceGateRejectsWritesInRemoteLocalTools(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("input.txt"), "data");
        var tools = new RemoteLocalTools(new RemoteClientToolRegistry(new ApprovingHandler()), root);
        tools.startTurn(java.util.UUID.randomUUID().toString());
        var guard = new WorkspaceGuard();
        guard.quarantine("10 秒取消超时");
        tools.setWorkspaceGuard(guard);

        var results = tools.execute(List.of(
                new ToolInvocation("r1", "read_file", "{\"path\":\"input.txt\"}"),
                new ToolInvocation("w1", "write_file", "{\"path\":\"out.txt\",\"content\":\"x\"}"),
                new ToolInvocation("c1", "execute_command", "{\"command\":\"echo hi\"}")));
        assertTrue(results.get(0).successful());
        assertFalse(results.get(1).successful());
        assertTrue(results.get(1).result().contains("QUARANTINED_UNKNOWN"));
        assertFalse(results.get(2).successful());
        assertFalse(Files.exists(root.resolve("out.txt")));
    }

    @Test void injectedLineTakesPriorityAndDiscardDropsTheCurrentLine() throws Exception {
        BlockingDeque<String> script = new LinkedBlockingDeque<>();
        BufferedReader source = scriptedSource(script);
        var reader = new RemoteInputReader(source);
        try {
            reader.inject("injected");
            script.put("normal");
            assertEquals("injected", reader.readLine());

            BlockingDeque<String> secondScript = new LinkedBlockingDeque<>();
            var second = new RemoteInputReader(scriptedSource(secondScript));
            try {
                second.discardCurrentLine();
                secondScript.put("discarded");
                secondScript.put("kept");
                assertEquals("kept", second.readLine());
            } finally { second.close(); }
        } finally { reader.close(); }
    }

    @Test void readLineIsInterruptibleSoCtrlCCanReleaseTheToolWorker() throws Exception {
        BlockingDeque<String> script = new LinkedBlockingDeque<>();
        var reader = new RemoteInputReader(scriptedSource(script));
        CountDownLatch interrupted = new CountDownLatch(1);
        Thread waiting = new Thread(() -> {
            try { reader.readLine(); }
            catch (IOException e) { interrupted.countDown(); }
        }, "test-remote-input");
        waiting.setDaemon(true);
        waiting.start();
        Thread.sleep(50);
        waiting.interrupt();
        assertTrue(interrupted.await(2, TimeUnit.SECONDS), "审批等待必须能被中断");
        waiting.join(1000);
        assertFalse(waiting.isAlive());
        reader.close();
    }

    @Test void pendingApprovalIsRejectedAndNewTurnResetsTheGate() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        HitlHandler blocking = new HitlHandler() {
            public ApprovalResult requestApproval(ApprovalRequest request) {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return ApprovalResult.approve();
            }
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean enabled) { }
        };
        var handler = new RemoteApprovalHandler(blocking);
        AtomicReference<ApprovalResult> result = new AtomicReference<>();
        Thread worker = new Thread(() -> result.set(handler.requestApproval(
                ApprovalRequest.of("write_file", "{}", null, null, null))));
        worker.setDaemon(true);
        worker.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        handler.cancelPendingApproval();
        worker.join(2000);
        assertFalse(worker.isAlive(), "取消后审批线程必须立即释放工具 worker");
        assertNotNull(result.get());
        assertTrue(result.get().isRejected());
        assertTrue(handler.isApprovalCancelled());
        handler.startTurn();
        assertFalse(handler.isApprovalCancelled());
    }

    private static BufferedReader scriptedSource(BlockingDeque<String> script) {
        return new BufferedReader(new StringReader("")) {
            @Override public String readLine() {
                try { return script.take(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
            }
        };
    }

    private static final class ApprovingHandler implements HitlHandler {
        public ApprovalResult requestApproval(ApprovalRequest request) { return ApprovalResult.approve(); }
        public boolean isEnabled() { return true; }
        public void setEnabled(boolean enabled) { }
    }
}

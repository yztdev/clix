package com.clix.remote;

import com.clix.hitl.ApprovalRequest;
import com.clix.hitl.ApprovalResult;
import com.clix.hitl.HitlHandler;
import com.clix.tool.ToolRegistry.ToolInvocation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteWslCancellationTest {
    @Test
    void deletingCancellationGateStopsRunningWslProcessGroup(@TempDir Path project) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().startsWith("windows"));
        var handler = new HitlHandler() {
            public ApprovalResult requestApproval(ApprovalRequest request) { return ApprovalResult.approve(); }
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean enabled) { }
        };
        var tools = new RemoteLocalTools(new RemoteClientToolRegistry(handler), project);
        tools.startTurn(java.util.UUID.randomUUID().toString());
        var json = new ObjectMapper();
        String arguments = json.createObjectNode().put("command",
                "printf started > ready.txt; sleep 15; printf done > marker.txt").toString();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> tools.execute(List.of(
                    new ToolInvocation("one", "execute_command", arguments))).get(0));
            Path gate = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline) {
                try (var files = Files.list(project)) {
                    gate = files.filter(path -> path.getFileName().toString().startsWith(".clix-running-")
                            && path.getFileName().toString().endsWith(".gate")).findFirst().orElse(null);
                }
                if (gate != null && Files.exists(project.resolve("ready.txt"))) break;
                Thread.sleep(25);
            }
            assertNotNull(gate, "未创建 WSL 取消门闩");
            assertTrue(Files.exists(project.resolve("ready.txt")), "WSL 命令未启动");
            Files.delete(gate);
            assertFalse(result.get(8, TimeUnit.SECONDS).successful(), "取消后的命令不应成功");
            Thread.sleep(1000);
            assertFalse(Files.exists(project.resolve("marker.txt")), "删除取消门闩后命令仍继续执行");
        } finally { executor.shutdownNow(); }
    }

    @Test
    void killedClientJvmDoesNotLeaveWslCommandRunning(@TempDir Path project) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().startsWith("windows"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                "com.clix.remote.WslChildHarness", project.toString()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(project.resolve("ready.txt")) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(25);
            assertTrue(Files.exists(project.resolve("ready.txt")), "独立客户端的 WSL 命令未启动");
            child.destroyForcibly();
            assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            Thread.sleep(6500);
            assertFalse(Files.exists(project.resolve("marker.txt")), "客户端被终止后 WSL 命令仍继续执行");
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }
    @Test
    void killedWindowsLauncherDoesNotLeaveWslCommandRunning(@TempDir Path project) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().startsWith("windows"));
        var handler = new HitlHandler() {
            public ApprovalResult requestApproval(ApprovalRequest request) { return ApprovalResult.approve(); }
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean enabled) { }
        };
        var registry = new RemoteClientToolRegistry(handler);
        var pid = new java.util.concurrent.atomic.AtomicLong();
        registry.setCommandExecutionObserver(event -> {
            if (event.phase() == com.clix.tool.CommandExecutionObserver.Phase.STARTED) pid.set(event.processId());
        });
        var tools = new RemoteLocalTools(registry, project);
        tools.startTurn(java.util.UUID.randomUUID().toString());
        var json = new ObjectMapper();
        var client = new RemoteClientSession(tools, (type, payload) -> { });
        String turnId = java.util.UUID.randomUUID().toString();
        client.startTurn(turnId);
        String arguments = json.createObjectNode().put("command", "printf started > ready.txt; sleep 5; printf done > marker.txt").toString();
        var payload = json.createObjectNode().put("turn_id", turnId)
                .put("batch_id", "batch");
        payload.set("calls", json.valueToTree(List.of(new ToolInvocation("one", "execute_command", arguments))));
        var frame = json.createObjectNode().put("type", "tool.request"); frame.set("payload", payload);
        try {
            client.onMessage(frame);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while ((pid.get() == 0 || !Files.exists(project.resolve("ready.txt"))) && System.nanoTime() < deadline) Thread.sleep(25);
            assertTrue(pid.get() > 0 && Files.exists(project.resolve("ready.txt")),
                    () -> "WSL command did not start: pid=" + pid.get()
                            + " ready=" + Files.exists(project.resolve("ready.txt")));
            assertTrue(ProcessHandle.of(pid.get()).orElseThrow().destroyForcibly());
            Thread.sleep(6500);
            assertFalse(Files.exists(project.resolve("marker.txt")), "Windows 启动进程结束后 WSL 命令仍在执行");
        } finally { client.close(); }
    }

    @Test
    void normalWslCommandPreservesOutputAndExitStatus(@TempDir Path project) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().startsWith("windows"));
        var handler = new HitlHandler() {
            public ApprovalResult requestApproval(ApprovalRequest request) { return ApprovalResult.approve(); }
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean enabled) { }
        };
        var tools = new RemoteLocalTools(new RemoteClientToolRegistry(handler), project);
        tools.startTurn(java.util.UUID.randomUUID().toString());
        var json = new ObjectMapper();
        String arguments = json.createObjectNode().put("command", "printf 'clix output with spaces'; exit 7").toString();
        var result = tools.execute(List.of(new ToolInvocation("one", "execute_command", arguments))).get(0);
        assertFalse(result.successful());
        assertTrue(result.result().contains("clix output with spaces"), result.result());
        assertTrue(result.result().contains("exit code: 7"), result.result());
        // WSL may release its Windows working directory shortly after the process exits.
        Thread.sleep(2000);
    }

    @Test
    void closingClientStopsWslCommandBeforeDelayedWrite(@TempDir Path project) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().startsWith("windows"));
        Process probe = new ProcessBuilder("wsl.exe", "--exec", "sh", "-c", "command -v setsid && command -v bash")
                .redirectErrorStream(true).start();
        assertTrue(probe.waitFor(15, TimeUnit.SECONDS) && probe.exitValue() == 0, "WSL 和 setsid 必须可用");
        var handler = new HitlHandler() {
            public ApprovalResult requestApproval(ApprovalRequest request) { return ApprovalResult.approve(); }
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean enabled) { }
        };
        var tools = new RemoteLocalTools(new RemoteClientToolRegistry(handler), project);
        tools.startTurn(java.util.UUID.randomUUID().toString());
        var json = new ObjectMapper();
        var result = new java.util.concurrent.atomic.AtomicReference<String>();
        var client = new RemoteClientSession(tools, (type, payload) -> result.set(payload.toString()));
        String turnId = java.util.UUID.randomUUID().toString();
        client.startTurn(turnId);
        String arguments = json.createObjectNode().put("command", "printf started > ready.txt; sleep 5; printf done > marker.txt").toString();
        var payload = json.createObjectNode().put("turn_id", turnId)
                .put("batch_id", "batch");
        payload.set("calls", json.valueToTree(List.of(new ToolInvocation("one", "execute_command", arguments))));
        var frame = json.createObjectNode().put("type", "tool.request"); frame.set("payload", payload);
        try {
            client.onMessage(frame);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(project.resolve("ready.txt")) && result.get() == null && System.nanoTime() < deadline) Thread.sleep(25);
            assertTrue(Files.exists(project.resolve("ready.txt")), () -> "命令未能启动: " + result.get());
            client.close();
            Thread.sleep(6500);
            assertFalse(Files.exists(project.resolve("marker.txt")), "取消后 WSL 命令仍继续执行");
        } finally { client.close(); }
    }
}

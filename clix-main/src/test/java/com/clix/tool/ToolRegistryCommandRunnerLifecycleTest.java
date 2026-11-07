package com.clix.tool;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryCommandRunnerLifecycleTest {

    private static final class RecordingRunner implements PlatformCommandRunner {
        String turnId;
        String toolCallId;
        String projectRoot;
        boolean ended;
        boolean cancelled;

        @Override public CommandOutcome execute(CommandSpec spec) {
            assertEquals("call-1", toolCallId);
            return new CommandOutcome(ToolOutput.text("supervised"), 123L,
                    List.of("powershell.exe", "-Command", spec.command()), 0, "EXITED", null);
        }
        @Override public void beginTurn(String turnId) { this.turnId = turnId; }
        @Override public void beginToolCall(String toolCallId) { this.toolCallId = toolCallId; }
        @Override public void endToolCall() { ended = true; toolCallId = null; }
        @Override public void setProjectRoot(String projectRoot) { this.projectRoot = projectRoot; }
        @Override public boolean cancelTurn(String turnId, long absoluteDeadlineNanos) {
            cancelled = true;
            return "turn-1".equals(turnId) && absoluteDeadlineNanos > System.nanoTime();
        }
        @Override public String statusLine() { return "job_object"; }
        @Override public List<String> unknownOperationIds() { return List.of(); }
        @Override public void startRecovery(Runnable onConfirmed) { if (onConfirmed != null) onConfirmed.run(); }
    }

    @Test void localToolRegistryBindsTurnToolCallAndProjectToRunner() {
        ToolRegistry registry = new ToolRegistry();
        RecordingRunner runner = new RecordingRunner();
        registry.setPlatformCommandRunner(runner);
        registry.setProjectPath(Path.of(".").toAbsolutePath().normalize().toString());
        registry.beginCommandTurn("turn-1");

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call-1", "execute_command", "{\"command\":\"echo hi\"}")));

        assertEquals("turn-1", runner.turnId);
        assertNotNull(runner.projectRoot);
        assertEquals(1, results.size());
        assertTrue(results.get(0).result().contains("supervised"));
        assertTrue(runner.ended);
        assertTrue(registry.cancelCommandTurn("turn-1", System.nanoTime() + TimeUnit.SECONDS.toNanos(10)));
        assertTrue(runner.cancelled);
    }
}

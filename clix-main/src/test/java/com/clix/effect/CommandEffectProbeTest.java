package com.clix.effect;

import com.clix.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** V1.2 I(4)(5)(6)(7): bounded observation, no inferred scan, explicit audit_scope. */
class CommandEffectProbeTest {
    @TempDir Path root;

    private static final class FakeRunner implements CommandEffectProbe.CommandRunner {
        int calls;
        String head = "HEAD-1";
        String porcelain = "";
        @Override public String run(List<String> command) {
            calls++;
            return command.contains("rev-parse") ? head : porcelain;
        }
    }

    @Test void nonGitWithoutDeclaredPathsIsUnavailableAndStartsNoScan() {
        FakeRunner runner = new FakeRunner();
        CommandEffectProbe probe = new CommandEffectProbe(root, runner);
        var before = probe.before("echo hi", null);
        assertFalse(before.git());
        assertFalse(before.declared());
        assertEquals(0, before.scanCount());
        assertEquals(0, runner.calls, "非 Git 且未声明路径时不得启动任何扫描命令");

        var after = probe.after(before, "echo hi");
        assertEquals("unavailable", after.effectAudit());
        assertEquals("none", after.auditScope());
        assertTrue(after.observedPaths().isEmpty());
        assertEquals(0, after.scanCount());
        assertTrue(after.auditLimitations().contains("no_observation_performed"));
        assertTrue(after.auditLimitations().contains("unobserved_file_changes_manual_check_required"));
        assertTrue(after.auditLimitations().contains("shell_black_box"));
    }

    @Test void interruptionContextForUnavailableObservationAsksForManualCheck() {
        var cancelled = EffectEvent.JSON.createObjectNode();
        var effect = EffectReport.payload("t1", "c1", "o1", "execute_command", "cancelled_unknown");
        effect.put("effect_audit", "unavailable");
        String context = InterruptionContextBuilder.build(cancelled, List.of(effect), 0, 1, false);
        assertTrue(context.contains("未观察文件变化；请手工检查"));
        assertTrue(context.contains(InterruptionContextBuilder.HARD_CONSTRAINT));
    }

    @Test void nonGitWithDeclaredPathsOnlyTouchesTheWhitelistAndStaysPartial() throws Exception {
        Files.writeString(root.resolve("out.txt"), "before");
        FakeRunner runner = new FakeRunner();
        CommandEffectProbe probe = new CommandEffectProbe(root, runner);
        var before = probe.before("tool", List.of("out.txt", "out.txt", "../escape.txt"));
        assertTrue(before.declared());
        assertEquals(1, before.scanCount());
        assertEquals(List.of("out.txt"), before.declaredPaths());
        assertEquals(0, runner.calls);
        assertTrue(before.limitations().contains("declared_path_rejected:../escape.txt"));

        Files.writeString(root.resolve("out.txt"), "after");
        var after = probe.after(before, "tool");
        assertEquals("partial", after.effectAudit(), "声明路径采集完整也不升级为全局 verified");
        assertEquals("declared_output_paths", after.auditScope());
        assertTrue(after.auditLimitations().contains("declared_paths_only"));
        assertTrue(after.auditLimitations().contains("not_a_full_workspace_scan"));
        assertTrue(after.observedPaths().contains("out.txt"));
        assertEquals(0, runner.calls);
    }

    @Test void gitDeltaWithoutDeclaredPathsStaysPartial() throws Exception {
        Files.createDirectories(root.resolve(".git"));
        FakeRunner runner = new FakeRunner();
        CommandEffectProbe probe = new CommandEffectProbe(root, runner);
        var before = probe.before("mvn test", null);
        assertTrue(before.git());
        runner.porcelain = " M src/A.java";
        var after = probe.after(before, "mvn test");
        assertEquals("partial", after.effectAudit());
        assertEquals("git_worktree_delta", after.auditScope());
        assertTrue(after.observedPaths().contains("src/A.java"));
        assertTrue(after.auditLimitations().contains("shell_black_box"));
    }

    @Test void gitWithFullyCapturedDeclaredPathsIsVerifiedOnlyForThatScope() throws Exception {
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve("report.txt"), "before");
        FakeRunner runner = new FakeRunner();
        CommandEffectProbe probe = new CommandEffectProbe(root, runner);
        var before = probe.before("mvn test", List.of("report.txt"));
        Files.writeString(root.resolve("report.txt"), "after");
        var after = probe.after(before, "mvn test");
        assertEquals("verified", after.effectAudit());
        assertEquals("declared_output_paths", after.auditScope());
        assertTrue(after.auditLimitations().contains("verified_scope_declared_output_paths_only"));
        assertTrue(after.observedPaths().contains("report.txt"));
    }

    @Test void executeCommandSchemaAdvertisesBoundedOutputPaths() {
        var definition = new ToolRegistry().getToolDefinitions().stream()
                .filter(tool -> "execute_command".equals(tool.name())).findFirst().orElseThrow();
        var property = definition.parameters().path("properties").path("output_paths");
        assertEquals("array", property.path("type").asText());
        assertEquals("string", property.path("items").path("type").asText());
        assertFalse(definition.parameters().path("required").toString().contains("output_paths"));
    }

    @Test void commandSummaryIsRedactedTruncatedAndMarksExternalEffects() {
        String secret = "curl -H 'Authorization: Bearer abc123SECRET' https://example.com/api";
        String summary = CommandEffectProbe.summary(secret);
        assertFalse(summary.contains("abc123SECRET"));
        assertTrue(summary.contains("***"));
        assertTrue(CommandEffectProbe.externalSideEffectsPossible(secret));
        assertTrue(CommandEffectProbe.externalSideEffectsPossible("git push origin main"));
        assertFalse(CommandEffectProbe.externalSideEffectsPossible("echo local"));
        assertTrue(CommandEffectProbe.summary("x".repeat(500)).length() <= 201);
        assertFalse(CommandEffectProbe.digest(secret).contains(secret));
    }
}

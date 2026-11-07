package com.clix.remote;

import com.clix.process.ActiveProcessSupervisor;
import com.clix.process.CapabilityDetector;
import com.clix.process.PlatformShell;
import com.clix.process.ProcessContainment;
import com.clix.process.FakeContainment;
import com.clix.tool.PlatformCommandRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Phase-two wiring: supervisor → ToolRegistry runner → workspace gate → recovery inspector. */
class RemotePhaseTwoIntegrationTest {

    private static CapabilityDetector.Result result(FakeContainment fake) {
        return new CapabilityDetector.Result(fake, PlatformShell.disabled("unit test"),
                "fake", "unit test containment");
    }

    private static SupervisorCommandRunner runner(FakeContainment fake,
                                                   FakeContainment.FakeClock clock) {
        return new SupervisorCommandRunner("/project", result(fake), clock,
                new ActiveProcessSupervisor.PhaseBudget(10, 10, 10));
    }

    private static PlatformCommandRunner.CommandSpec spec(long timeoutMillis) {
        return new PlatformCommandRunner.CommandSpec("echo hi", null, Path.of("."), Map.of(),
                "/project", timeoutMillis, "fake");
    }

    private static String runningOperation(SupervisorCommandRunner runner, String turnId) {
        ActiveProcessSupervisor supervisor = runner.supervisor();
        supervisor.beginTurn(turnId);
        String operationId = supervisor.registerStarting(new ActiveProcessSupervisor.RegisterRequest(
                turnId, "call-1", "digest", "/project", "fake", "echo hi"));
        try {
            assertNotNull(supervisor.start(operationId, new ProcessContainment.StartRequest(
                    operationId, turnId, "call-1", supervisor.nonce(operationId), "echo hi",
                    List.of("echo hi"), Path.of("."), Map.of(), "/project", "fake", 1_000L)));
        } catch (java.io.IOException e) {
            fail("启动失败：" + e.getMessage());
        }
        return operationId;
    }

    @Test void runnerExecutesCommandThroughSupervisor() {
        FakeContainment fake = new FakeContainment();
        fake.output = "hello from phase two";
        SupervisorCommandRunner runner = runner(fake, new FakeContainment.FakeClock());
        runner.beginTurn("t1");
        runner.beginToolCall("call-1");
        PlatformCommandRunner.CommandOutcome outcome = runner.execute(spec(1_000L));
        assertNotNull(outcome);
        assertEquals("EXITED", outcome.outcome());
        assertTrue(outcome.output().successful());
        assertTrue(outcome.output().text().contains("hello from phase two"));
        assertEquals(1, fake.startCount);
        assertEquals(ActiveProcessSupervisor.OperationState.COMPLETED,
                runner.supervisor().status().operations().get(0).state());
    }

    @Test void unconfirmedCancellationIsExposedToTheWorkspaceGate() {
        FakeContainment fake = new FakeContainment();
        fake.alwaysUnknown = true;
        FakeContainment.FakeClock clock = new FakeContainment.FakeClock();
        SupervisorCommandRunner runner = runner(fake, clock);
        String operationId = runningOperation(runner, "t1");

        assertFalse(runner.cancelTurn("t1",
                clock.nanoTime() + TimeUnit.SECONDS.toNanos(10)));
        assertEquals(List.of(operationId), runner.unknownOperationIds());
        assertTrue(runner.statusLine().contains("未确认：1"));
        assertFalse(runner.lastResults().isEmpty());
        assertEquals(ActiveProcessSupervisor.OperationState.CANCELLED_UNKNOWN,
                runner.lastResults().get(0).state());
    }

    @Test void recoveryInspectorReturnsWorkspaceToSafeAfterEmptyDomain() throws Exception {
        FakeContainment fake = new FakeContainment();
        fake.alwaysUnknown = true;
        FakeContainment.FakeClock clock = new FakeContainment.FakeClock();
        SupervisorCommandRunner runner = runner(fake, clock);
        runningOperation(runner, "t1");
        runner.cancelTurn("t1", clock.nanoTime() + TimeUnit.SECONDS.toNanos(10));

        WorkspaceGuard workspace = new WorkspaceGuard();
        workspace.quarantine("取消达到 10 秒绝对上限，监管域状态未确认");
        assertTrue(workspace.isRestricted());
        assertFalse(workspace.allows("write_file"));

        fake.alwaysUnknown = false;
        runner.startRecovery(() -> workspace.confirmSafe("后台诊断确认监管域为空"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (workspace.state() != WorkspaceGuard.State.SAFE && System.nanoTime() < deadline) {
            Thread.sleep(25L);
        }
        assertEquals(WorkspaceGuard.State.SAFE, workspace.state());
        assertTrue(workspace.allows("write_file"));
        assertTrue(workspace.statusLine().contains("后台诊断确认监管域为空"));
        // The terminal tool state is never rewritten by later verification.
        assertEquals(ActiveProcessSupervisor.OperationState.CANCELLED_UNKNOWN,
                runner.supervisor().status().operations().get(0).state());
    }

    @Test void statusShowsRealContainmentInsteadOfPhaseOnePlaceholder() {
        FakeContainment fake = new FakeContainment();
        SupervisorCommandRunner runner = runner(fake, new FakeContainment.FakeClock());
        String status = new RemoteClientCommands(new WorkspaceGuard())
                .status(null, null, new WorkspaceGuard(), 0, runner.statusLine());
        assertTrue(status.contains("活动监管域：监管域级别：cgroup"), status);
        assertFalse(status.contains("ActiveProcessSupervisor 尚未实现"), status);
        assertFalse(status.contains("活动监管域：不可用"), status);
    }

    @Test void forcedReleaseKeepsItsOwnRiskState() {
        WorkspaceGuard workspace = new WorkspaceGuard();
        workspace.quarantine("unknown");
        String prompt = workspace.beginForcedRelease("/project", "tester");
        assertTrue(prompt.contains("unlock-"));
        assertFalse(workspace.confirmForcedRelease("wrong phrase"));
        assertTrue(workspace.confirmForcedRelease(prompt.substring(
                prompt.lastIndexOf("unlock-")).trim()));
        assertEquals(WorkspaceGuard.State.FORCED_RELEASED, workspace.state());
        assertTrue(workspace.statusLine().contains("未经过监管域核验"));
    }
}

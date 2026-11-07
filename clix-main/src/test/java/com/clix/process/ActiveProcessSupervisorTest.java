package com.clix.process;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Acceptance tests for design doc phase two: "活动进程监督器" and the 10s time budget. */
class ActiveProcessSupervisorTest {

    private FakeContainment containment;
    private FakeContainment.FakeClock clock;
    private ActiveProcessSupervisor supervisor;

    @BeforeEach void setUp() {
        containment = new FakeContainment();
        clock = new FakeContainment.FakeClock();
        supervisor = new ActiveProcessSupervisor(containment, clock,
                ActiveProcessSupervisor.PhaseBudget.standard(), null);
    }

    private long deadline() {
        return clock.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    }

    private static ActiveProcessSupervisor.RegisterRequest request(String turnId) {
        return new ActiveProcessSupervisor.RegisterRequest(turnId, "call-1", "digest",
                "/project", "fake", "echo hi");
    }

    private static ProcessContainment.StartRequest startRequest(String operationId, String nonce,
                                                                 String turnId) {
        return new ProcessContainment.StartRequest(operationId, turnId, "call-1", nonce,
                "echo hi", List.of("echo hi"), Path.of("."), Map.of(), "/project", "fake", 5_000L);
    }

    private String start(String turnId) {
        supervisor.beginTurn(turnId);
        String operationId = supervisor.registerStarting(request(turnId));
        try {
            assertNotNull(supervisor.start(operationId,
                    startRequest(operationId, supervisor.nonce(operationId), turnId)));
        } catch (java.io.IOException e) {
            fail("启动失败：" + e.getMessage());
        }
        return operationId;
    }

    @Test void activeRecordIsStartingBeforeLaunch() {
        supervisor.beginTurn("t1");
        String operationId = supervisor.registerStarting(request("t1"));
        ActiveProcessSupervisor.Operation operation = supervisor.operation(operationId);
        assertEquals(ActiveProcessSupervisor.OperationState.STARTING, operation.state());
        assertEquals(1, supervisor.status().activeOperations());
        assertTrue(supervisor.isFreshOperation(operationId, "t1", "call-1"));
        assertFalse(supervisor.isFreshOperation(operationId, "t2", "call-1"));
    }

    @Test void cancelBeforeStartRejectsLaunch() {
        supervisor.beginTurn("t1");
        String operationId = supervisor.registerStarting(request("t1"));
        supervisor.cancelTurn("t1", deadline());
        try {
            assertNull(supervisor.start(operationId,
                    startRequest(operationId, supervisor.nonce(operationId), "t1")));
        } catch (java.io.IOException e) {
            fail("启动失败：" + e.getMessage());
        }
        assertEquals(0, containment.startCount);
        assertEquals(ActiveProcessSupervisor.OperationState.REJECTED,
                supervisor.operation(operationId).state());
    }

    /** The exact race the design doc calls out: approved process created, PID not registered yet. */
    @Test void cancelDuringStartingWindowTerminatesFreshProcess() {
        supervisor.beginTurn("t1");
        String operationId = supervisor.registerStarting(request("t1"));
        ProcessContainment.StartRequest startRequest =
                startRequest(operationId, supervisor.nonce(operationId), "t1");
        // Process already exists on the platform, but markRunning has not run yet.
        ProcessContainment.ContainedProcess handle = containment.start(startRequest);
        supervisor.cancelTurn("t1", deadline());
        assertEquals(ActiveProcessSupervisor.OperationState.STARTING,
                supervisor.operation(operationId).state());

        assertFalse(supervisor.markRunning(operationId, handle));
        assertFalse(containment.signals.isEmpty(), "刚创建的进程必须被立即终止，不能漏过竞态窗口");
        assertEquals(ActiveProcessSupervisor.OperationState.CANCELLED_CONFIRMED,
                supervisor.operation(operationId).state());
    }

    @Test void gracefulIntProducesCancelledConfirmed() {
        String operationId = start("t1");
        List<ActiveProcessSupervisor.TerminationResult> results = supervisor.cancelTurn("t1", deadline());
        assertEquals(1, results.size());
        ActiveProcessSupervisor.TerminationResult result = results.get(0);
        assertTrue(result.confirmed());
        assertEquals(ActiveProcessSupervisor.OperationState.CANCELLED_CONFIRMED, result.state());
        assertEquals(ActiveProcessSupervisor.TerminationKind.GRACEFUL, result.termination());
        assertEquals(List.of(ProcessContainment.Signal.INT), containment.signals);
    }

    @Test void ignoringIntAndTermEscalatesToForcedKill() {
        containment.emptyAfterSignal = ProcessContainment.Signal.KILL;
        String operationId = start("t1");
        ActiveProcessSupervisor.TerminationResult result = supervisor.cancelTurn("t1", deadline()).get(0);
        assertTrue(result.confirmed());
        assertTrue(result.forced());
        assertEquals(ActiveProcessSupervisor.TerminationKind.FORCED, result.termination());
        assertEquals(List.of(ProcessContainment.Signal.INT, ProcessContainment.Signal.TERM,
                ProcessContainment.Signal.KILL), containment.signals);
    }

    @Test void unobservableDomainProducesCancelledUnknown() {
        containment.alwaysUnknown = true;
        String operationId = start("t1");
        ActiveProcessSupervisor.TerminationResult result = supervisor.cancelTurn("t1", deadline()).get(0);
        assertFalse(result.confirmed());
        assertEquals(ActiveProcessSupervisor.OperationState.CANCELLED_UNKNOWN, result.state());
        assertEquals(ActiveProcessSupervisor.TerminationKind.UNKNOWN, result.termination());
        assertEquals(ProcessContainment.EmptyDomain.UNKNOWN, result.domain());
        assertTrue(supervisor.unknownOperationIds().contains(operationId));
    }

    @Test void failedIdentityNeverSendsAnySignal() {
        containment.identityOk = false;
        start("t1");
        ActiveProcessSupervisor.TerminationResult result = supervisor.cancelTurn("t1", deadline()).get(0);
        assertTrue(containment.signals.isEmpty(), "nonce / PID start-time 校验失败时不得发信号");
        assertFalse(result.confirmed());
        assertTrue(result.reason().contains("nonce") || result.reason().contains("PID"));
    }

    @Test void escapedProcessIsNeverClaimedConfirmed() {
        containment.escaped = true;
        start("t1");
        ActiveProcessSupervisor.TerminationResult result = supervisor.cancelTurn("t1", deadline()).get(0);
        assertFalse(result.confirmed());
        assertEquals(ActiveProcessSupervisor.OperationState.CANCELLED_UNKNOWN, result.state());
        assertTrue(result.reason().contains("脱离监管域"));
    }

    @Test void secondCancelCannotExtendFirstDeadline() {
        start("t1");
        long first = clock.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long later = first + TimeUnit.SECONDS.toNanos(10);
        supervisor.cancelTurn("t1", first);
        supervisor.cancelTurn("t1", later);
        assertEquals(first, supervisor.deadlineNanos("t1"));
    }

    @Test void completionWinsOverLateCancel() {
        String operationId = start("t1");
        supervisor.complete(operationId, 0);
        ActiveProcessSupervisor.TerminationResult result = supervisor.terminate(operationId, deadline());
        assertEquals(ActiveProcessSupervisor.TerminationKind.NOT_REQUIRED, result.termination());
        assertEquals(ActiveProcessSupervisor.OperationState.COMPLETED,
                supervisor.operation(operationId).state());
    }

    @Test void recoveryConfirmsDomainWithoutRewritingTerminalState() {
        containment.neverEmpty = true;
        String operationId = start("t1");
        supervisor.cancelTurn("t1", deadline());
        assertEquals(ActiveProcessSupervisor.OperationState.CANCELLED_UNKNOWN,
                supervisor.operation(operationId).state());
        containment.neverEmpty = false;
        assertTrue(supervisor.confirmDomainEmptyLater(operationId));
        assertEquals(ActiveProcessSupervisor.OperationState.CANCELLED_UNKNOWN,
                supervisor.operation(operationId).state(),
                "后台核验不能事后改写工具终态（V1.2 B）");
        assertEquals("empty-confirmed-by-recovery", supervisor.operation(operationId).lastVerification());
    }

    @Test void statusExposesContainmentLevelAndUnknownCount() {
        containment.alwaysUnknown = true;
        start("t1");
        supervisor.cancelTurn("t1", deadline());
        ActiveProcessSupervisor.Status status = supervisor.status();
        assertEquals(ContainmentLevel.CGROUP, status.containment());
        assertEquals(1L, status.unknownOperations());
        assertEquals(0L, status.activeOperations());
        assertEquals(1, status.operations().size());
    }

    @Test void recoveryDiagnosisStopsAtSixtySeconds() throws Exception {
        containment.alwaysUnknown = true;
        String operationId = start("t1");
        supervisor.cancelTurn("t1", deadline());
        RecoveryInspector inspector = new RecoveryInspector(supervisor, clock);
        var future = inspector.start(List.of(operationId), () -> { });
        clock.advance(TimeUnit.SECONDS.toMillis(61));
        RecoveryInspector.Outcome outcome = future.get(5, TimeUnit.SECONDS);
        assertFalse(outcome.confirmed());
        assertTrue(outcome.detail().contains("60 秒"));
        inspector.close();
    }

    @Test void phaseBudgetCannotExceedTenSeconds() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActiveProcessSupervisor.PhaseBudget(9_000, 3_000, 5_000));
        assertThrows(IllegalArgumentException.class,
                () -> new ActiveProcessSupervisor.PhaseBudget(2_000, 3_000, 6_000));
        assertNotNull(new ActiveProcessSupervisor.PhaseBudget(100, 100, 100));
    }
}

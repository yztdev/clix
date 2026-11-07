package com.clix.remote;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Phase-one test matrix: turn state machine, repeated cancellation and absolute deadline. */
class RemoteTurnLifecycleTest {

    @Test void idleRunningCancellingCancelledIdleAndCompletedIdle() {
        var starts = new AtomicInteger();
        var controller = new CurrentTurnController((id, force) -> starts.incrementAndGet(), id -> { });
        try {
            assertEquals(CurrentTurnController.Phase.IDLE, controller.phase());
            var turn = controller.begin();
            assertEquals(CurrentTurnController.Phase.RUNNING, controller.phase());
            assertEquals(CurrentTurnController.CancelRequest.STARTED, controller.requestCancel());
            assertEquals(CurrentTurnController.Phase.CANCELLING, controller.phase());
            controller.complete(turn.id(), CurrentTurnController.Outcome.CANCELLED);
            assertEquals(CurrentTurnController.Phase.CANCELLED, controller.phase());
            controller.release(turn);
            assertEquals(CurrentTurnController.Phase.IDLE, controller.phase());

            var second = controller.begin();
            controller.complete(second.id(), CurrentTurnController.Outcome.COMPLETED);
            assertEquals(CurrentTurnController.Phase.COMPLETED, controller.phase());
            controller.release(second);
            assertEquals(CurrentTurnController.Phase.IDLE, controller.phase());
        } finally { controller.close(); }
    }

    @Test void forcedSecondCtrlCDoesNotExtendTheAbsoluteDeadline() throws Exception {
        var controller = new CurrentTurnController((id, force) -> { }, id -> { }, Duration.ofMillis(250));
        try {
            var turn = controller.begin();
            assertEquals(CurrentTurnController.CancelRequest.STARTED, controller.requestCancel());
            long deadline = turn.cancelDeadlineNanos();
            Thread.sleep(80);
            assertEquals(CurrentTurnController.CancelRequest.FORCED, controller.requestCancel());
            assertEquals(deadline, turn.cancelDeadlineNanos(), "第二次 Ctrl+C 不得重置截止时间");
            assertEquals(CurrentTurnController.Outcome.CANCEL_TIMEOUT, controller.await(turn));
            assertEquals(CurrentTurnController.Phase.CANCEL_TIMEOUT, controller.phase());
            assertEquals(0L, controller.cancelDeadlineRemainingMillis());
            controller.release(turn);
            assertEquals(CurrentTurnController.Phase.IDLE, controller.phase());
        } finally { controller.close(); }
    }

    @Test void repeatedCancelIsIdempotentAndTerminalStateWins() throws InterruptedException {
        var sends = new AtomicInteger();
        var controller = new CurrentTurnController((id, force) -> { if (!force) sends.incrementAndGet(); }, id -> { });
        try {
            var turn = controller.begin();
            controller.complete(turn.id(), CurrentTurnController.Outcome.COMPLETED);
            assertEquals(CurrentTurnController.CancelRequest.IGNORED, controller.requestCancel());
            assertEquals(0, sends.get(), "已终态的 Turn 不能再发出取消请求");
            assertEquals(CurrentTurnController.Outcome.COMPLETED, controller.await(turn));
            controller.release(turn);
        } finally { controller.close(); }
    }

    @Test void serverBudgetNeverExceedsClientBudgetOrEightSeconds() {
        assertEquals(8_000L, RemoteProtocol.SERVER_TERMINALIZATION_MS);
        assertEquals(8_000L, RemoteProtocol.serverTerminalizationMillis(10_000L));
        assertEquals(200L, RemoteProtocol.serverTerminalizationMillis(2_200L));
        assertEquals(0L, RemoteProtocol.serverTerminalizationMillis(1_000L));
        assertEquals(8_000L, RemoteProtocol.serverTerminalizationMillis(60_000L));
    }
}

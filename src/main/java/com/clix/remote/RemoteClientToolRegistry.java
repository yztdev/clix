package com.clix.remote;

import com.clix.hitl.HitlHandler;
import com.clix.hitl.HitlToolRegistry;
import com.clix.process.ActiveProcessSupervisor;
import com.clix.process.SupervisorCommandRunner;
import com.clix.tool.ToolRegistry.ToolInvocation;

import java.util.concurrent.TimeUnit;

/** Applies the connection state immediately before each locally executed tool. */
public final class RemoteClientToolRegistry extends HitlToolRegistry {
    private final RemoteToolRequestTracker requests = new RemoteToolRequestTracker();
    private final RemoteApprovalHandler approvalHandler;
    private final SupervisorCommandRunner runner;

    public RemoteClientToolRegistry(HitlHandler handler) {
        this(handler, null);
    }

    /** Test seam: inject a runner with a fake containment domain. */
    RemoteClientToolRegistry(HitlHandler handler, SupervisorCommandRunner runner) {
        super(wrapApprovals(handler));
        approvalHandler = getHitlHandler() instanceof RemoteApprovalHandler
                ? (RemoteApprovalHandler) getHitlHandler() : null;
        this.runner = runner == null ? new SupervisorCommandRunner(".", strictContainment()) : runner;
        // Phase two: every execute_command is now registered before launch and terminated by
        // the bounded, containment-aware supervisor instead of the legacy descendants() kill.
        setPlatformCommandRunner(this.runner);
        enableRemoteWslCancellation();
    }

    private static boolean strictContainment() {
        return Boolean.getBoolean("clix.remote.strictContainment")
                || Boolean.parseBoolean(System.getenv("CLIX_REMOTE_STRICT_CONTAINMENT"));
    }

    /** The remote client always installs a turn-scoped approval gate; tests may pass a plain handler. */
    private static HitlHandler wrapApprovals(HitlHandler handler) {
        if (handler instanceof RemoteApprovalHandler) return handler;
        return new RemoteApprovalHandler(handler);
    }

    RemoteToolRequestTracker requests() {
        return requests;
    }

    RemoteApprovalHandler approvalHandler() {
        return approvalHandler;
    }

    SupervisorCommandRunner runner() { return runner; }

    ActiveProcessSupervisor supervisor() { return runner.supervisor(); }

    void setProjectRoot(String projectRoot) { runner.setProjectRoot(projectRoot); }

    void startRemoteTurn(String turnId) {
        startCommandTurn();
        startAutoReviewTurn();
        if (approvalHandler != null) approvalHandler.startTurn();
        requests.startTurn(turnId);
        runner.beginTurn(turnId);
    }

    void cancelRemoteTurn(String turnId) {
        cancelPendingApproval();
        requests.cancelTurn(turnId);
        // The first Ctrl+C deadline is absolute; the runner never extends it.
        runner.cancelTurn(turnId, System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(ActiveProcessSupervisor.HARD_DEADLINE_MILLIS));
    }

    void cancelPendingApproval() {
        if (approvalHandler != null) approvalHandler.cancelPendingApproval();
    }

    void endToolCall() { runner.endToolCall(); }

    /** Connection-level shutdown fallback: stop the current turn's containment domains. */
    @Override public void cancelActiveCommands() {
        String turnId = runner.supervisor().currentTurnId();
        if (turnId != null) {
            runner.cancelTurn(turnId, System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(ActiveProcessSupervisor.HARD_DEADLINE_MILLIS));
        }
    }

    @Override
    protected boolean beforeToolInvocation(ToolInvocation invocation) {
        return requests.start(invocation.id());
    }

    @Override
    protected boolean mayExecuteAfterApproval() {
        return requests.isTurnActive()
                && (approvalHandler == null || !approvalHandler.isApprovalCancelled());
    }
}

package com.clix.remote;

import com.clix.hitl.ApprovalRequest;
import com.clix.hitl.ApprovalResult;
import com.clix.hitl.HitlHandler;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Turn-scoped approval decorator for the remote client only.
 *
 * <p>Design doc "用户体验" requires that Ctrl+C while HITL is waiting rejects that approval
 * and cancels the turn without leaving the local tool worker blocked on stdin. The delegate
 * approval prompt reads a line synchronously, so cancellation interrupts the waiting thread;
 * {@link RemoteInputReader#readLine()} is interruptible and the delegate renderer converts the
 * interruption into a conservative rejection. A post-approval re-check
 * ({@code mayExecuteAfterApproval}) remains the second line of defense.
 */
final class RemoteApprovalHandler implements HitlHandler {
    private final HitlHandler delegate;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Thread> approvalThread = new AtomicReference<>();
    private final AtomicInteger staleApprovals = new AtomicInteger();

    RemoteApprovalHandler(HitlHandler delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** A new turn gets a fresh approval scope; stale approval state never carries over. */
    void startTurn() {
        cancelled.set(false);
        Thread waiting = approvalThread.get();
        if (waiting != null && waiting.isAlive()) staleApprovals.incrementAndGet();
        approvalThread.set(null);
    }

    /** Called by the Ctrl+C path before the local tool worker is released. */
    void cancelPendingApproval() {
        cancelled.set(true);
        Thread waiting = approvalThread.get();
        if (waiting != null) waiting.interrupt();
    }

    boolean isApprovalCancelled() {
        return cancelled.get();
    }

    int staleApprovals() {
        return staleApprovals.get();
    }

    @Override public ApprovalResult requestApproval(ApprovalRequest request) {
        if (cancelled.get()) return ApprovalResult.reject("当前 Turn 已取消");
        approvalThread.set(Thread.currentThread());
        try {
            ApprovalResult result = delegate.requestApproval(request);
            if (result == null || cancelled.get() || Thread.currentThread().isInterrupted()) {
                Thread.interrupted(); // do not leak the interrupt flag to the shared tool worker
                return ApprovalResult.reject("当前 Turn 已取消");
            }
            return result;
        } finally {
            approvalThread.compareAndSet(Thread.currentThread(), null);
        }
    }

    @Override public boolean isEnabled() { return delegate.isEnabled(); }
    @Override public void setEnabled(boolean enabled) { delegate.setEnabled(enabled); }
    @Override public boolean isHighRiskConfirmationEnabled() { return delegate.isHighRiskConfirmationEnabled(); }
    @Override public void setHighRiskConfirmationEnabled(boolean enabled) { delegate.setHighRiskConfirmationEnabled(enabled); }
    @Override public String confirmationModeLabel() { return delegate.confirmationModeLabel(); }
    @Override public String switchConfirmationMode(String mode) { return delegate.switchConfirmationMode(mode); }
    @Override public void onAutoApproved(String toolName, String reason) { delegate.onAutoApproved(toolName, reason); }
    @Override public void onAutoDenied(String toolName, String reason) { delegate.onAutoDenied(toolName, reason); }
    @Override public boolean isApprovedAllByTool(String toolName) { return delegate.isApprovedAllByTool(toolName); }
    @Override public boolean isApprovedAllByServer(String serverName) { return delegate.isApprovedAllByServer(serverName); }
    @Override public void clearApprovedAll() { delegate.clearApprovedAll(); }
    @Override public void clearApprovedAllForServer(String serverName) { delegate.clearApprovedAllForServer(serverName); }
}

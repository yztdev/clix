package com.clix.process;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded background diagnosis for CANCELLED_UNKNOWN operations (design doc "工作区安全状态").
 *
 * <p>Starts when quarantine begins, polls the containment domain with bounded probes, and runs
 * for at most 60 seconds. Confirming an empty domain lets the workspace return to SAFE; it never
 * rewrites the terminal tool state. When the budget expires, automatic diagnosis stops and the
 * user is pointed at the two-step {@code /quarantine unlock --force} release.</p>
 */
public final class RecoveryInspector implements AutoCloseable {

    public static final long DIAGNOSIS_BUDGET_MILLIS = 60_000L;
    public static final long PROBE_INTERVAL_MILLIS = 1_000L;

    public record Outcome(boolean confirmed, int probes, long elapsedMillis, String detail) { }

    private final ActiveProcessSupervisor supervisor;
    private final ActiveProcessSupervisor.Clock clock;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "clix-recovery-inspector");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile CompletableFuture<Outcome> current;

    public RecoveryInspector(ActiveProcessSupervisor supervisor) {
        this(supervisor, ActiveProcessSupervisor.SYSTEM_CLOCK);
    }

    public RecoveryInspector(ActiveProcessSupervisor supervisor, ActiveProcessSupervisor.Clock clock) {
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Returns a future that completes when all unknown domains are confirmed empty, or at 60s. */
    public CompletableFuture<Outcome> start(List<String> operationIds, Runnable onConfirmed) {
        if (!running.compareAndSet(false, true)) {
            CompletableFuture<Outcome> existing = current;
            return existing == null ? CompletableFuture.completedFuture(
                    new Outcome(false, 0, 0, "诊断已在运行")) : existing;
        }
        CompletableFuture<Outcome> future = new CompletableFuture<>();
        current = future;
        long startMillis = TimeUnit.NANOSECONDS.toMillis(clock.nanoTime());
        List<String> ids = List.copyOf(operationIds);
        AtomicInteger probes = new AtomicInteger();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                long elapsed = TimeUnit.NANOSECONDS.toMillis(clock.nanoTime()) - startMillis;
                if (elapsed >= DIAGNOSIS_BUDGET_MILLIS) {
                    complete(future, new Outcome(false, probes.get(), elapsed,
                            "60 秒后台诊断截止，监管域仍无法确认；自动诊断已停止，"
                                    + "可使用 /quarantine unlock --force 两步解除"), null);
                    return;
                }
                probes.incrementAndGet();
                boolean allConfirmed = !ids.isEmpty();
                for (String id : ids) {
                    if (!supervisor.confirmDomainEmptyLater(id)) { allConfirmed = false; break; }
                }
                if (allConfirmed) {
                    complete(future, new Outcome(true, probes.get(), elapsed,
                            "监管域已确认清空，工作区恢复 SAFE"), onConfirmed);
                }
            } catch (RuntimeException e) {
                complete(future, new Outcome(false, probes.get(), 0L,
                        "诊断异常：" + e.getMessage()), null);
            }
        }, 0L, PROBE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        return future;
    }

    private void complete(CompletableFuture<Outcome> future, Outcome outcome,
                          Runnable onConfirmed) {
        if (!future.complete(outcome)) return;
        running.set(false);
        scheduler.shutdownNow();
        if (onConfirmed != null) {
            try { onConfirmed.run(); } catch (RuntimeException ignoredException) { /* keep outcome */ }
        }
    }

    public boolean isRunning() { return running.get(); }

    @Override public void close() {
        scheduler.shutdownNow();
        running.set(false);
        CompletableFuture<Outcome> future = current;
        if (future != null) future.complete(new Outcome(false, 0, 0, "诊断已停止"));
    }
}

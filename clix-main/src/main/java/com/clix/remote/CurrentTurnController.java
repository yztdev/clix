package com.clix.remote;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Client-side lifecycle gate for exactly one active turn on a reusable connection. */
final class CurrentTurnController implements AutoCloseable {
    /** Internal CAS gate; {@link Phase} is the observable state machine. */
    enum State { RUNNING, CANCELLING, TERMINAL }
    /** Design doc: IDLE → RUNNING → CANCELLING → CANCELLED → IDLE / COMPLETED → IDLE. */
    enum Phase { IDLE, RUNNING, CANCELLING, CANCELLED, COMPLETED, FAILED, CANCEL_TIMEOUT, CONNECTION_CLOSED }
    enum Outcome { COMPLETED, CANCELLED, FAILED, CANCEL_TIMEOUT, CONNECTION_CLOSED }
    enum CancelRequest { STARTED, FORCED, IGNORED }

    @FunctionalInterface interface CancelSender { void send(String turnId, boolean force); }

    static final class Turn {
        private final String id = UUID.randomUUID().toString();
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);
        private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.RUNNING);
        private final AtomicReference<Outcome> outcome = new AtomicReference<>();
        private volatile long cancelDeadlineNanos;

        String id() { return id; }
        State state() { return state.get(); }
        Phase phase() { return phase.get(); }
        Outcome outcome() { return outcome.get(); }
        long cancelDeadlineNanos() { return cancelDeadlineNanos; }
    }

    private final CancelSender cancelSender;
    private final Consumer<String> timeoutHandler;
    private final long cancelTimeoutNanos;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "clix-turn-cancel-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<Turn> current = new AtomicReference<>();
    private volatile String lastTurnId;
    private volatile Phase lastPhase = Phase.IDLE;
    private volatile Outcome lastOutcome;
    private volatile long lastCancelDeadlineNanos;

    CurrentTurnController(CancelSender cancelSender, Consumer<String> timeoutHandler) {
        this(cancelSender, timeoutHandler, Duration.ofMillis(RemoteProtocol.CANCEL_DEADLINE_MS));
    }

    CurrentTurnController(CancelSender cancelSender, Consumer<String> timeoutHandler, Duration cancelTimeout) {
        this.cancelSender = java.util.Objects.requireNonNull(cancelSender);
        this.timeoutHandler = java.util.Objects.requireNonNull(timeoutHandler);
        if (cancelTimeout == null || cancelTimeout.isNegative() || cancelTimeout.isZero()
                || cancelTimeout.toMillis() > RemoteProtocol.CANCEL_DEADLINE_MS) {
            throw new IllegalArgumentException("取消期限必须在 0 到 10 秒之间");
        }
        this.cancelTimeoutNanos = cancelTimeout.toNanos();
    }

    Turn begin() {
        Turn turn = new Turn();
        if (!current.compareAndSet(null, turn)) throw new IllegalStateException("已有任务正在执行");
        return turn;
    }

    CancelRequest requestCancel() {
        Turn turn = current.get();
        if (turn == null) return CancelRequest.IGNORED;
        if (turn.state.compareAndSet(State.RUNNING, State.CANCELLING)) {
            turn.phase.compareAndSet(Phase.RUNNING, Phase.CANCELLING);
            // Set once. A second Ctrl+C or a retry must never extend this absolute deadline.
            turn.cancelDeadlineNanos = System.nanoTime() + cancelTimeoutNanos;
            lastCancelDeadlineNanos = turn.cancelDeadlineNanos;
            cancelSender.send(turn.id, false);
            timer.schedule(() -> expire(turn), cancelTimeoutNanos, TimeUnit.NANOSECONDS);
            return CancelRequest.STARTED;
        }
        if (turn.state.get() == State.CANCELLING) {
            cancelSender.send(turn.id, true);
            return CancelRequest.FORCED;
        }
        return CancelRequest.IGNORED;
    }

    private void expire(Turn turn) {
        if (current.get() != turn || turn.state.get() != State.CANCELLING) return;
        timeoutHandler.accept(turn.id);
        finish(turn, Outcome.CANCEL_TIMEOUT);
    }

    boolean isCurrent(String turnId) {
        Turn turn = current.get();
        return turn != null && turn.id.equals(turnId);
    }

    boolean hasActiveTurn() {
        return current.get() != null;
    }

    Phase phase() {
        Turn turn = current.get();
        return turn == null ? Phase.IDLE : turn.phase.get();
    }

    String lastTurnId() { return lastTurnId; }
    Phase lastPhase() { return lastPhase; }
    Outcome lastOutcome() { return lastOutcome; }

    /** Remaining time on the first Ctrl+C deadline, never negative, never extended. */
    long cancelDeadlineRemainingMillis() {
        long deadline = lastCancelDeadlineNanos;
        if (deadline <= 0) return 0L;
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    void complete(String turnId, Outcome outcome) {
        Turn turn = current.get();
        if (turn == null || !turn.id.equals(turnId)) return;
        finish(turn, outcome);
    }

    private void finish(Turn turn, Outcome outcome) {
        if (!turn.outcome.compareAndSet(null, outcome)) return;
        turn.state.set(State.TERMINAL);
        turn.phase.set(toPhase(outcome));
        lastTurnId = turn.id;
        lastPhase = turn.phase.get();
        lastOutcome = outcome;
        turn.done.countDown();
    }

    private static Phase toPhase(Outcome outcome) {
        return switch (outcome) {
            case COMPLETED -> Phase.COMPLETED;
            case CANCELLED -> Phase.CANCELLED;
            case FAILED -> Phase.FAILED;
            case CANCEL_TIMEOUT -> Phase.CANCEL_TIMEOUT;
            case CONNECTION_CLOSED -> Phase.CONNECTION_CLOSED;
        };
    }

    void connectionClosed() {
        Turn turn = current.get();
        if (turn != null) finish(turn, Outcome.CONNECTION_CLOSED);
    }

    Outcome await(Turn turn) throws InterruptedException {
        turn.done.await();
        return turn.outcome();
    }

    void release(Turn turn) {
        if (turn.state.get() != State.TERMINAL) throw new IllegalStateException("任务尚未终态化");
        if (current.compareAndSet(turn, null)) {
            turn.phase.set(Phase.IDLE);
            lastPhase = Phase.IDLE;
        }
    }

    @Override public void close() { timer.shutdownNow(); }
}

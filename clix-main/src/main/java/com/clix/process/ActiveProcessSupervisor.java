package com.clix.process;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Lifecycle owner for every foreground command executed by the remote client.
 *
 * <p>Design doc "活动进程监督器": the active record is created as STARTING *before* the process
 * is launched, so a cancellation racing with launch can never miss the "approved but PID not
 * registered yet" window. Termination is a bounded INT(2s) → TERM(3s) → KILL + empty-domain
 * confirmation(5s) sequence under one absolute 10-second deadline. Only a domain whose nonce
 * and PID start-time still verify may be signalled, and CANCELLED_CONFIRMED requires an
 * observed EMPTY domain.</p>
 *
 * <p>All platform facts come from an injected {@link ProcessContainment}, so the state machine
 * and the deadline rules are unit-testable without WSL, systemd or a Windows console.</p>
 */
public final class ActiveProcessSupervisor implements AutoCloseable {

    public static final long HARD_DEADLINE_MILLIS = 10_000L;
    public static final long INT_PHASE_MILLIS = 2_000L;
    public static final long TERM_PHASE_MILLIS = 3_000L;
    public static final long KILL_PHASE_MILLIS = 5_000L;

    public enum OperationState {
        STARTING, RUNNING, CANCELLING, CANCELLED_CONFIRMED, CANCELLED_UNKNOWN,
        COMPLETED, FAILED, TIMED_OUT, REJECTED
    }

    public enum TerminationKind { GRACEFUL, FORCED, NOT_REQUIRED, UNKNOWN }

    /** Injectable monotonic clock; test clocks advance during sleep. */
    public interface Clock {
        long nanoTime();
        void sleep(long millis) throws InterruptedException;
    }

    public static final Clock SYSTEM_CLOCK = new Clock() {
        @Override public long nanoTime() { return System.nanoTime(); }
        @Override public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
    };

    /** Per-phase budgets may only be shortened, never extended beyond the doc's 2/3/5 seconds. */
    public static final class PhaseBudget {
        public final long intMillis;
        public final long termMillis;
        public final long killMillis;

        public PhaseBudget(long intMillis, long termMillis, long killMillis) {
            if (intMillis < 0 || intMillis > INT_PHASE_MILLIS
                    || termMillis < 0 || termMillis > TERM_PHASE_MILLIS
                    || killMillis < 0 || killMillis > KILL_PHASE_MILLIS
                    || intMillis + termMillis + killMillis > HARD_DEADLINE_MILLIS) {
                throw new IllegalArgumentException("阶段预算只能缩短，不能延长 10 秒全局上限");
            }
            this.intMillis = intMillis;
            this.termMillis = termMillis;
            this.killMillis = killMillis;
        }

        public static PhaseBudget standard() {
            return new PhaseBudget(INT_PHASE_MILLIS, TERM_PHASE_MILLIS, KILL_PHASE_MILLIS);
        }
    }

    public record RegisterRequest(
            String turnId, String toolCallId, String commandDigest,
            String projectRoot, String platform, String command) { }

    public record TerminationResult(
            String operationId,
            OperationState state,
            TerminationKind termination,
            ContainmentLevel containment,
            ProcessContainment.EmptyDomain domain,
            boolean confirmed,
            boolean forced,
            String reason,
            String limitation,
            long elapsedMillis,
            List<ProcessContainment.Signal> signals) {
        public String terminationWire() {
            return termination == null ? "unknown" : switch (termination) {
                case GRACEFUL -> "graceful";
                case FORCED -> "forced";
                case NOT_REQUIRED -> "not_required";
                case UNKNOWN -> "unknown";
            };
        }
    }

    /** Immutable snapshot used by /status and by the recovery inspector. */
    public record OperationView(
            String operationId, String turnId, String toolCallId, String nonce,
            String commandDigest, String projectRoot, String platform,
            OperationState state, long pid, long pgid, long sid,
            ContainmentLevel containment, Integer exitCode, String reason) { }

    public record Status(
            String currentTurnId, ContainmentLevel containment, String limitation,
            long activeOperations, long unknownOperations, List<OperationView> operations) { }

    /** Mutable per-operation record; all transitions are guarded by the instance monitor. */
    public static final class Operation {
        private final String id;
        private final String turnId;
        private final String toolCallId;
        private final String nonce;
        private final String commandDigest;
        private final String projectRoot;
        private final String platform;
        private final long startedAtNanos;
        private volatile OperationState state = OperationState.STARTING;
        private volatile ProcessContainment.ContainedProcess handle;
        private volatile TerminationResult result;
        private volatile Integer exitCode;
        private volatile String reason;
        private volatile String lastVerification;
        /** Set when cancellation arrives after STARTING but before the PID was registered. */
        private volatile boolean cancelWhileStarting;

        private Operation(String id, RegisterRequest request, String nonce, long now) {
            this.id = id;
            this.turnId = request.turnId();
            this.toolCallId = request.toolCallId();
            this.nonce = nonce;
            this.commandDigest = request.commandDigest();
            this.projectRoot = request.projectRoot();
            this.platform = request.platform();
            this.startedAtNanos = now;
        }

        public String id() { return id; }
        public String turnId() { return turnId; }
        public String nonce() { return nonce; }
        public OperationState state() { return state; }
        public TerminationResult result() { return result; }
        public String lastVerification() { return lastVerification; }
        public OperationView view(ContainmentLevel level) {
            ProcessContainment.ContainedProcess h = handle;
            return new OperationView(id, turnId, toolCallId, nonce, commandDigest, projectRoot,
                    platform, state, h == null ? 0 : h.pid(), h == null ? 0 : h.pgid(),
                    h == null ? 0 : h.sid(), level, exitCode, reason);
        }
    }

    private final ProcessContainment containment;
    private final Clock clock;
    private final PhaseBudget budget;
    private final Consumer<Operation> eventListener;
    private final ConcurrentHashMap<String, Operation> operations = new ConcurrentHashMap<>();
    private final java.util.Set<String> cancellingTurns = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> turnDeadlines = new ConcurrentHashMap<>();
    private volatile String currentTurnId;

    public ActiveProcessSupervisor(ProcessContainment containment) {
        this(containment, SYSTEM_CLOCK, PhaseBudget.standard(), null);
    }

    public ActiveProcessSupervisor(ProcessContainment containment, Clock clock,
                                   PhaseBudget budget, Consumer<Operation> eventListener) {
        this.containment = Objects.requireNonNull(containment, "containment");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.eventListener = eventListener;
    }

    public ProcessContainment containment() { return containment; }

    public void beginTurn(String turnId) {
        if (turnId == null || turnId.isBlank()) throw new IllegalArgumentException("turn_id 无效");
        currentTurnId = turnId;
    }

    public String currentTurnId() { return currentTurnId; }

    /** Doc: "活动记录必须在命令启动前创建为 STARTING". */
    public String registerStarting(RegisterRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.turnId() == null || request.turnId().isBlank()) {
            throw new IllegalArgumentException("turn_id 无效");
        }
        String operationId = UUID.randomUUID().toString();
        String nonce = UUID.randomUUID().toString();
        Operation op = new Operation(operationId, request, nonce, clock.nanoTime());
        operations.put(operationId, op);
        notifyListener(op);
        return operationId;
    }

    /** Never reuse an operation id, approval token or tool call id (V1.2 C). */
    public boolean isFreshOperation(String operationId, String turnId, String toolCallId) {
        Operation op = operationId == null ? null : operations.get(operationId);
        return op != null && op.state == OperationState.STARTING
                && op.turnId.equals(turnId) && Objects.equals(op.toolCallId, toolCallId);
    }

    /**
     * Launches the process and closes the STARTING window. Returns null when the turn was
     * cancelled while the process was being created: the freshly created process has already
     * been terminated by {@link #markRunning}.
     */
    public ProcessContainment.ContainedProcess start(String operationId,
                                                      ProcessContainment.StartRequest request)
            throws java.io.IOException {
        Operation op = operations.get(operationId);
        if (op == null || op.state != OperationState.STARTING) {
            throw new IllegalStateException("operation 未处于 STARTING，不能重复启动");
        }
        if (cancellingTurns.contains(op.turnId)) {
            synchronized (op) {
                if (op.result == null) {
                    op.state = OperationState.REJECTED;
                    op.reason = "取消已先到达，未创建进程";
                }
            }
            notifyListener(op);
            return null;
        }
        ProcessContainment.ContainedProcess handle = containment.start(request);
        if (!markRunning(operationId, handle)) return null;
        return handle;
    }

    /** CAS-like transition: RUNNING → COMPLETED and RUNNING → CANCELLING cannot both win. */
    public boolean markRunning(String operationId, ProcessContainment.ContainedProcess handle) {
        Operation op = operations.get(operationId);
        if (op == null || handle == null) return false;
        synchronized (op) {
            if (op.state != OperationState.STARTING || op.result != null) return false;
            op.handle = handle;
            op.state = OperationState.RUNNING;
        }
        notifyListener(op);
        if (op.cancelWhileStarting || cancellingTurns.contains(op.turnId)) {
            long deadline = turnDeadlines.getOrDefault(op.turnId,
                    clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HARD_DEADLINE_MILLIS));
            terminate(operationId, deadline);
            return false;
        }
        return true;
    }

    public boolean isCancelled(String operationId) {
        Operation op = operationId == null ? null : operations.get(operationId);
        if (op == null) return false;
        if (cancellingTurns.contains(op.turnId)) return true;
        return op.state == OperationState.CANCELLING || op.state == OperationState.CANCELLED_CONFIRMED
                || op.state == OperationState.CANCELLED_UNKNOWN || op.state == OperationState.REJECTED;
    }

    public List<TerminationResult> cancelTurn(String turnId, long absoluteDeadlineNanos) {
        if (turnId == null || turnId.isBlank()) return List.of();
        cancellingTurns.add(turnId);
        long existing = turnDeadlines.getOrDefault(turnId, 0L);
        // Once set, the first Ctrl+C deadline is absolute: a second Ctrl+C must not extend it.
        if (existing == 0L || absoluteDeadlineNanos < existing) {
            turnDeadlines.put(turnId, absoluteDeadlineNanos);
        }
        long deadline = turnDeadlines.get(turnId);
        List<TerminationResult> results = new ArrayList<>();
        for (Operation op : operations.values()) {
            if (!op.turnId.equals(turnId)) continue;
            OperationState state = op.state;
            if (state == OperationState.STARTING && op.handle == null) {
                // The process may already exist but its PID is not registered yet. Never claim
                // "no process was created" here: let markRunning terminate the fresh process.
                op.cancelWhileStarting = true;
                continue;
            }
            if (state == OperationState.STARTING || state == OperationState.RUNNING
                    || state == OperationState.CANCELLING) {
                results.add(terminate(op.id, deadline));
            } else if (op.result != null) {
                results.add(op.result);
            }
        }
        return List.copyOf(results);
    }

    public TerminationResult terminate(String operationId, long absoluteDeadlineNanos) {
        Operation op = operations.get(operationId);
        if (op == null) return unknownResult(null, "operation 不存在", List.of());
        synchronized (op) {
            if (op.result != null) return op.result;
            if (op.state == OperationState.COMPLETED || op.state == OperationState.FAILED
                    || op.state == OperationState.TIMED_OUT || op.state == OperationState.REJECTED) {
                return finish(op, op.state, TerminationKind.NOT_REQUIRED,
                        ProcessContainment.EmptyDomain.EMPTY, true, false,
                        "工具在取消到达前已终态，不改写结果", List.of(), op.startedAtNanos);
            }
            op.state = OperationState.CANCELLING;
        }
        long started = clock.nanoTime();
        ProcessContainment.ContainedProcess handle = op.handle;
        if (handle == null) {
            return finish(op, OperationState.CANCELLED_CONFIRMED, TerminationKind.NOT_REQUIRED,
                    ProcessContainment.EmptyDomain.EMPTY, true, false,
                    "STARTING 阶段取消，未创建进程，监管域为空", List.of(), started);
        }
        if (!containment.identityVerified(handle)) {
            return finish(op, OperationState.CANCELLED_UNKNOWN, TerminationKind.UNKNOWN,
                    ProcessContainment.EmptyDomain.UNKNOWN, false, false,
                    "nonce / PID start-time 校验失败或 PID 已复用，未发送任何信号", List.of(), started);
        }

        List<ProcessContainment.Signal> signals = new ArrayList<>();
        ProcessContainment.EmptyDomain domain = ProcessContainment.EmptyDomain.UNKNOWN;
        TerminationKind kind = TerminationKind.UNKNOWN;
        boolean confirmed = false;
        String reason = "监管域状态未确认";
        Phase[] phases = {
                new Phase(ProcessContainment.Signal.INT, budget.intMillis),
                new Phase(ProcessContainment.Signal.TERM, budget.termMillis),
                new Phase(ProcessContainment.Signal.KILL, budget.killMillis)
        };
        for (Phase phase : phases) {
            long remainingNanos = absoluteDeadlineNanos - clock.nanoTime();
            if (remainingNanos <= 0) { reason = "达到 10 秒绝对截止时间，停止继续升级信号"; break; }
            long sliceMillis = Math.min(phase.millis,
                    Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            boolean sent = containment.signal(handle, phase.signal);
            if (sent) signals.add(phase.signal);
            domain = awaitEmpty(handle, sliceMillis);
            if (domain == ProcessContainment.EmptyDomain.EMPTY) {
                confirmed = true;
                kind = phase.signal == ProcessContainment.Signal.KILL
                        ? TerminationKind.FORCED : TerminationKind.GRACEFUL;
                reason = sent ? "监管域已确认清空" : "进程在信号前已退出，监管域已确认清空";
                break;
            }
            if (!sent) { reason = "平台拒绝发送信号，监管域状态未确认"; break; }
        }
        if (confirmed && containment.escapedDomain(handle)) {
            confirmed = false;
            domain = ProcessContainment.EmptyDomain.UNKNOWN;
            kind = TerminationKind.UNKNOWN;
            reason = "检测到脱离监管域的进程（二次 setsid / 无法归属），不声称已清理";
        }
        if (!confirmed && domain == ProcessContainment.EmptyDomain.NOT_EMPTY) {
            reason = "监管域仍有成员：" + reason;
        }
        OperationState state = confirmed
                ? OperationState.CANCELLED_CONFIRMED : OperationState.CANCELLED_UNKNOWN;
        return finish(op, state, kind, domain, confirmed,
                kind == TerminationKind.FORCED, reason, List.copyOf(signals), started);
    }

    private ProcessContainment.EmptyDomain awaitEmpty(
            ProcessContainment.ContainedProcess handle, long sliceMillis) {
        long end = clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(sliceMillis);
        ProcessContainment.EmptyDomain last = ProcessContainment.EmptyDomain.UNKNOWN;
        while (true) {
            last = containment.checkEmpty(handle);
            if (last == ProcessContainment.EmptyDomain.EMPTY) return last;
            long remaining = end - clock.nanoTime();
            if (remaining <= 0) return last;
            long step = Math.min(25L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
            try {
                clock.sleep(step);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
    }

    private TerminationResult finish(Operation op, OperationState state, TerminationKind kind,
                                     ProcessContainment.EmptyDomain domain, boolean confirmed,
                                     boolean forced, String reason,
                                     List<ProcessContainment.Signal> signals, long startedNanos) {
        synchronized (op) {
            if (op.result != null) return op.result;
            op.state = state;
            op.reason = reason;
            ContainmentLevel level = op.handle == null ? containment.level() : op.handle.level();
            op.result = new TerminationResult(op.id, state, kind, level, domain, confirmed, forced,
                    reason, containment.limitation(),
                    TimeUnit.NANOSECONDS.toMillis(Math.max(0L, clock.nanoTime() - startedNanos)),
                    signals);
        }
        notifyListener(op);
        return op.result;
    }

    private static TerminationResult unknownResult(String id, String reason,
                                                   List<ProcessContainment.Signal> signals) {
        return new TerminationResult(id, OperationState.CANCELLED_UNKNOWN, TerminationKind.UNKNOWN,
                ContainmentLevel.NONE, ProcessContainment.EmptyDomain.UNKNOWN, false, false,
                reason, null, 0L, signals);
    }

    public void complete(String operationId, int exitCode) {
        transition(operationId, OperationState.COMPLETED, exitCode, null);
    }

    public void failed(String operationId, String reason) {
        transition(operationId, OperationState.FAILED, null, reason);
    }

    public void timedOut(String operationId) {
        transition(operationId, OperationState.TIMED_OUT, null, "命令超时");
    }

    private void transition(String operationId, OperationState state, Integer exitCode, String reason) {
        Operation op = operations.get(operationId);
        if (op == null) return;
        if (op.cancelWhileStarting && op.handle == null) {
            finish(op, OperationState.CANCELLED_CONFIRMED, TerminationKind.NOT_REQUIRED,
                    ProcessContainment.EmptyDomain.EMPTY, true, false,
                    "取消在 PID 登记前到达且启动未完成，确认未创建进程",
                    List.of(), clock.nanoTime());
            return;
        }
        ProcessContainment.ContainedProcess handle;
        synchronized (op) {
            if (op.result != null || op.state == OperationState.CANCELLING
                    || op.state == OperationState.CANCELLED_CONFIRMED
                    || op.state == OperationState.CANCELLED_UNKNOWN) return;
            op.state = state;
            op.exitCode = exitCode;
            op.reason = reason;
            handle = op.handle;
        }
        if (handle != null) containment.release(handle);
        notifyListener(op);
    }

    public String readOutput(String operationId) throws java.io.IOException {
        Operation op = operations.get(operationId);
        return op == null || op.handle == null ? "" : containment.readOutput(op.handle);
    }

    public Integer waitFor(String operationId, long millis) throws InterruptedException {
        Operation op = operations.get(operationId);
        return op == null || op.handle == null ? -1 : containment.waitFor(op.handle, millis);
    }

    /**
     * Background recovery check. It never rewrites a terminal tool state (V1.2 B forbids
     * asynchronous re-scoring of an audit verdict); it only records that the containment domain
     * was later observed empty, which lets the workspace return to SAFE.
     */
    public boolean confirmDomainEmptyLater(String operationId) {
        Operation op = operations.get(operationId);
        if (op == null || op.handle == null) return false;
        if (op.state != OperationState.CANCELLED_UNKNOWN) return op.state == OperationState.CANCELLED_CONFIRMED;
        if (!containment.identityVerified(op.handle)) return false;
        if (containment.checkEmpty(op.handle) != ProcessContainment.EmptyDomain.EMPTY) return false;
        if (containment.escapedDomain(op.handle)) return false;
        op.lastVerification = "empty-confirmed-by-recovery";
        notifyListener(op);
        return true;
    }

    /** Absolute first-Ctrl+C deadline for a turn; exposed for status and acceptance tests. */
    public long deadlineNanos(String turnId) {
        return turnDeadlines.getOrDefault(turnId, 0L);
    }

    public List<String> unknownOperationIds() {
        List<String> ids = new ArrayList<>();
        for (Operation op : operations.values()) {
            if (op.state == OperationState.CANCELLED_UNKNOWN && op.handle != null) ids.add(op.id);
        }
        return List.copyOf(ids);
    }

    public Operation operation(String operationId) { return operations.get(operationId); }

    /** The per-operation nonce injected into the process environment at launch. */
    public String nonce(String operationId) {
        Operation op = operationId == null ? null : operations.get(operationId);
        return op == null ? null : op.nonce();
    }

    public Status status() {
        List<OperationView> views = new ArrayList<>();
        long active = 0L;
        long unknown = 0L;
        for (Operation op : operations.values()) {
            views.add(op.view(op.handle == null ? containment.level() : op.handle.level()));
            if (op.state == OperationState.STARTING || op.state == OperationState.RUNNING
                    || op.state == OperationState.CANCELLING) active++;
            if (op.state == OperationState.CANCELLED_UNKNOWN) unknown++;
        }
        views.sort(java.util.Comparator.comparing(OperationView::operationId));
        return new Status(currentTurnId, containment.level(), containment.limitation(),
                active, unknown, List.copyOf(views));
    }

    private void notifyListener(Operation op) {
        if (eventListener != null) eventListener.accept(op);
    }

    private record Phase(ProcessContainment.Signal signal, long millis) { }

    @Override public void close() {
        containment.close();
    }
}

package com.clix.remote;

import com.clix.process.ActiveProcessSupervisor;
import com.clix.process.CapabilityDetector;
import com.clix.process.ProcessContainment;
import com.clix.process.RecoveryInspector;
import com.clix.tool.PlatformCommandRunner;
import com.clix.tool.ToolOutput;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Remote-client implementation of {@link PlatformCommandRunner}.
 *
 * <p>Every execute_command goes through the supervisor: STARTING is registered before the
 * process exists, the platform containment launches it, and completion/cancellation are decided
 * by the same atomic state machine. Timeout and user cancellation share the graded termination
 * path but are recorded as different outcomes.</p>
 */
final class SupervisorCommandRunner implements PlatformCommandRunner, AutoCloseable {

    private static final long POLL_MILLIS = 100L;
    private static final int MAX_OUTPUT_CHARS = 32_000;

    private final ActiveProcessSupervisor supervisor;
    private final CapabilityDetector.Result capability;
    private final RecoveryInspector recovery;
    private volatile String projectRoot;
    private final ThreadLocal<String> currentToolCall = new ThreadLocal<>();
    private volatile List<ActiveProcessSupervisor.TerminationResult> lastResults = List.of();

    SupervisorCommandRunner(String projectRoot, boolean strict) {
        this(projectRoot, CapabilityDetector.detect(strict));
    }

    /** Test seam: inject an explicit capability (fake containment). */
    SupervisorCommandRunner(String projectRoot, CapabilityDetector.Result capability) {
        this(projectRoot, capability, ActiveProcessSupervisor.SYSTEM_CLOCK,
                ActiveProcessSupervisor.PhaseBudget.standard());
    }

    /** Test seam: deterministic clock and shortened (never extended) phase budgets. */
    SupervisorCommandRunner(String projectRoot, CapabilityDetector.Result capability,
                            ActiveProcessSupervisor.Clock clock,
                            ActiveProcessSupervisor.PhaseBudget budget) {
        this.projectRoot = projectRoot == null ? "." : projectRoot;
        this.capability = capability;
        this.supervisor = new ActiveProcessSupervisor(capability.containment(), clock, budget, null);
        this.recovery = new RecoveryInspector(supervisor, clock);
    }

    ActiveProcessSupervisor supervisor() { return supervisor; }

    void setProjectRoot(String projectRoot) {
        if (projectRoot != null && !projectRoot.isBlank()) this.projectRoot = projectRoot;
    }

    CapabilityDetector.Result capability() { return capability; }

    List<ActiveProcessSupervisor.TerminationResult> lastResults() { return lastResults; }

    @Override public void beginTurn(String turnId) { supervisor.beginTurn(turnId); }

    @Override public void beginToolCall(String toolCallId) { currentToolCall.set(toolCallId); }

    @Override public void endToolCall() { currentToolCall.remove(); }

    @Override public CommandOutcome execute(CommandSpec spec) {
        if (spec == null) return null;
        String turnId = supervisor.currentTurnId();
        if (turnId == null) return null; // local mode: keep ToolRegistry's own launcher
        String toolCallId = currentToolCall.get();
        String command = spec.command() == null ? "" : spec.command();
        String digest = sha256(command);
        String operationId = supervisor.registerStarting(new ActiveProcessSupervisor.RegisterRequest(
                turnId, toolCallId, digest, projectRoot, capability.level().wireValue(), command));
        String nonce = supervisor.nonce(operationId);
        List<String> base = spec.sandboxArguments() == null || spec.sandboxArguments().isEmpty()
                ? List.of(command) : spec.sandboxArguments();
        // ToolRegistry already resolved the shell that matches the command dialect (Git Bash /
        // WSL bash / native shell). Capability probing must not silently switch it and break
        // existing command semantics.
        String shell = spec.preferredShell() == null || spec.preferredShell().isBlank()
                ? capability.shellExecutable() : spec.preferredShell();
        ProcessContainment.StartRequest request = new ProcessContainment.StartRequest(
                operationId, turnId, toolCallId, nonce, command, base,
                spec.workingDirectory(), spec.environment(), projectRoot,
                shell, Math.max(1L, spec.timeoutMillis()));
        List<String> launched = capability.containment().wrapCommand(base, request);

        ProcessContainment.ContainedProcess handle;
        try {
            handle = supervisor.start(operationId, request);
        } catch (Exception e) {
            supervisor.failed(operationId, "启动失败：" + e.getMessage());
            return new CommandOutcome(ToolOutput.failure("执行命令失败: " + e.getMessage()),
                    0L, launched, null, "FAILED", "监管域启动失败");
        }
        if (handle == null) {
            return new CommandOutcome(ToolOutput.failure("用户取消了此次工具调用；命令未启动"),
                    0L, launched, null, "INTERRUPTED", "STARTING 阶段取消，未创建进程");
        }
        if (spec.onStarted() != null) {
            spec.onStarted().accept(new Started(handle.pid(), launched));
        }

        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, spec.timeoutMillis()));
        Integer exitCode = null;
        while (System.nanoTime() < deadlineNanos) {
            if (supervisor.isCancelled(operationId)) break;
            try {
                exitCode = supervisor.waitFor(operationId, POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (exitCode != null) break;
        }

        boolean timedOut = false;
        if (exitCode == null && !supervisor.isCancelled(operationId)) {
            timedOut = true;
            supervisor.terminate(operationId,
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                            ActiveProcessSupervisor.HARD_DEADLINE_MILLIS));
            supervisor.timedOut(operationId);
        }
        ActiveProcessSupervisor.Operation operation = supervisor.operation(operationId);
        ActiveProcessSupervisor.TerminationResult termination = operation == null ? null : operation.result();
        String output = readOutput(operationId);
        String containmentNote = termination == null ? "" : "；监管域=" + termination.state()
                + "/" + termination.terminationWire() + "/" + termination.containment().wireValue()
                + (termination.limitation() == null ? "" : "（" + termination.limitation() + "）");

        ToolOutput toolOutput;
        String outcome;
        if (supervisor.isCancelled(operationId)) {
            toolOutput = ToolOutput.failure("用户取消了此次工具调用" + containmentNote);
            outcome = "INTERRUPTED";
        } else if (timedOut) {
            toolOutput = ToolOutput.failure("命令执行超时（"
                    + TimeUnit.MILLISECONDS.toSeconds(spec.timeoutMillis()) + "秒），已强制终止"
                    + containmentNote);
            outcome = "TIMED_OUT";
        } else {
            String text = String.format("命令执行完成 (exit code: %d)%n%s", exitCode, output)
                    + containmentNote;
            toolOutput = exitCode != null && exitCode == 0
                    ? ToolOutput.text(text) : ToolOutput.failure(text);
            outcome = exitCode != null && exitCode == 0 ? "EXITED" : "FAILED";
            supervisor.complete(operationId, exitCode == null ? -1 : exitCode);
        }
        return new CommandOutcome(toolOutput, handle.pid(), launched, exitCode, outcome,
                termination == null ? null : termination.reason());
    }

    @Override public boolean cancelTurn(String turnId, long absoluteDeadlineNanos) {
        List<ActiveProcessSupervisor.TerminationResult> results =
                supervisor.cancelTurn(turnId, absoluteDeadlineNanos);
        lastResults = List.copyOf(results);
        // No active domain at all is not an "unknown" state; an unconfirmed one is.
        return results.stream().allMatch(ActiveProcessSupervisor.TerminationResult::confirmed);
    }

    @Override public String statusLine() {
        ActiveProcessSupervisor.Status status = supervisor.status();
        StringBuilder out = new StringBuilder("监管域级别：").append(status.containment().wireValue());
        if (status.containment().degraded()) {
            out.append("（降级，不能声称确定性清理）");
        }
        out.append("；活动 operation：").append(status.activeOperations())
                .append("；未确认：").append(status.unknownOperations());
        if (status.limitation() != null) out.append("；限制：").append(status.limitation());
        return out.toString();
    }

    @Override public List<String> unknownOperationIds() {
        return supervisor.unknownOperationIds();
    }

    @Override public void startRecovery(Runnable onConfirmed) {
        if (supervisor.unknownOperationIds().isEmpty()) {
            if (onConfirmed != null) onConfirmed.run();
            return;
        }
        recovery.start(supervisor.unknownOperationIds(), onConfirmed);
    }

    private String readOutput(String operationId) {
        String value;
        try {
            value = supervisor.readOutput(operationId);
        } catch (Exception e) {
            return "[输出不可读：" + e.getMessage() + "]";
        }
        if (value == null) return "";
        return value.length() > MAX_OUTPUT_CHARS ? value.substring(0, MAX_OUTPUT_CHARS) + "\n[输出已截断]" : value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    @Override public void close() {
        recovery.close();
        supervisor.close();
    }
}

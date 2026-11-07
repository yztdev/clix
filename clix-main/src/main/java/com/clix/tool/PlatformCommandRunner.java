package com.clix.tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Pluggable platform process launcher for {@code execute_command} (design doc "组件改造 /
 * ToolRegistry：抽出平台进程启动和终止接口").
 *
 * <p>Returning {@code null} from {@link #execute} keeps ToolRegistry's built-in ProcessBuilder
 * path, which is what local (non-remote) mode still uses. A remote client installs a runner
 * backed by {@code ActiveProcessSupervisor}, so its commands are registered before launch and
 * terminated by the bounded containment-aware path.</p>
 */
public interface PlatformCommandRunner {

    /** Reported the instant a process exists, before waiting for it to finish. */
    record Started(long pid, List<String> arguments) { }

    record CommandSpec(
            String command,
            /** Non-null when a command sandbox already produced the real argument vector. */
            List<String> sandboxArguments,
            Path workingDirectory,
            Map<String, String> environment,
            String projectRoot,
            long timeoutMillis,
            String preferredShell,
            java.util.function.Consumer<Started> onStarted) {
        public CommandSpec(String command, List<String> sandboxArguments, Path workingDirectory,
                           Map<String, String> environment, String projectRoot, long timeoutMillis,
                           String preferredShell) {
            this(command, sandboxArguments, workingDirectory, environment, projectRoot,
                    timeoutMillis, preferredShell, null);
        }
    }

    record CommandOutcome(
            ToolOutput output,
            long pid,
            List<String> arguments,
            Integer exitCode,
            /** One of EXITED / FAILED / TIMED_OUT / INTERRUPTED. */
            String outcome,
            String note) { }

    /** null = not applicable here, use the built-in launcher. */
    CommandOutcome execute(CommandSpec spec);

    void beginTurn(String turnId);

    /** Binds the tool_call_id for the duration of one invocation on this thread. */
    void beginToolCall(String toolCallId);

    void endToolCall();

    /** True only when every active containment domain was observed empty. */
    boolean cancelTurn(String turnId, long absoluteDeadlineNanos);

    String statusLine();

    List<String> unknownOperationIds();

    void startRecovery(Runnable onConfirmed);
}

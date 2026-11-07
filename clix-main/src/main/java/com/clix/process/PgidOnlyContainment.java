package com.clix.process;

import java.util.List;

/**
 * Explicit compatibility mode (design doc "PGID_ONLY 降级模式").
 *
 * <p>Signals go only to the process group registered at start, and only after nonce plus PID
 * start-time verification. It never follows a PPID chain, never scans or kills "suspected"
 * descendants, and reports CANCELLED_UNKNOWN as soon as a second setsid / unowned process is
 * observed. It is never presented as deterministic cleanup.</p>
 */
public final class PgidOnlyContainment extends AbstractProcessContainment {

    public static final String STANDARD_LIMITATION =
            "PGID_ONLY 兼容模式：未建立 cgroup / Job Object 监管域；"
                    + "二次 setsid、daemonize 或后台任务无法确认清理，结果记为 CANCELLED_UNKNOWN";

    private final String limitation;

    public PgidOnlyContainment(PlatformShell shell,
                               ProcessIdentityVerifier.ProcReader proc) {
        this(shell, proc, STANDARD_LIMITATION);
    }

    public PgidOnlyContainment(PlatformShell shell,
                               ProcessIdentityVerifier.ProcReader proc, String limitation) {
        super(shell, proc, true);
        this.limitation = limitation;
    }

    @Override public ContainmentLevel level() { return ContainmentLevel.PGID_ONLY; }

    @Override public String limitation() { return limitation; }

    @Override protected String platform() { return "pgid-only"; }

    /**
     * Starts the command in its own session so the registered PGID never includes the client
     * process. On a platform without setsid the command is launched as-is and the missing PGID
     * is reported honestly as CANCELLED_UNKNOWN instead of guessing descendants.
     */
    @Override public List<String> wrapCommand(List<String> baseArguments, StartRequest request) {
        if (baseArguments == null || baseArguments.isEmpty()) return baseArguments;
        if (baseArguments.size() > 1) return baseArguments; // sandbox invocation is already wrapped
        String shell = request.shellExecutable();
        if (shell == null || shell.isBlank()) return baseArguments;
        String command = baseArguments.get(0);
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).startsWith("windows");
        if (windows) {
            // On Windows the launched PID is a Win32 pid, not a Linux one. Changing the launcher
            // here would break WSL command semantics (and there is no /proc PGID to verify), so
            // keep the original shell invocation and report CANCELLED_UNKNOWN instead of
            // pretending a process group was supervised.
            return shell.toLowerCase().contains("cmd.exe")
                    ? List.of(shell, "/c", command) : List.of(shell, "-c", command);
        }
        // Native Linux: a fresh session prevents a group signal from ever reaching the client.
        String encoded = java.util.Base64.getEncoder().encodeToString(
                command.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String script = "setsid --wait /bin/sh -c \"$(printf %s " + encoded + " | base64 -d)\"\n";
        return List.of(shell, "-c", script);
    }

    @Override public boolean signal(ContainedProcess process, Signal signal) {
        if (!verifier.verify(process)) return false;
        long pgid = process.pgid();
        if (pgid <= 0) return false;
        String name = switch (signal) {
            case INT -> "INT";
            case TERM -> "TERM";
            case KILL -> "KILL";
        };
        // Only the registered group, never a PPID-chain walk or a descendant scan.
        String script = "kill -s " + name + " -- -" + pgid + " 2>/dev/null; exit 0\n";
        return probe(script, 1_200L) != null;
    }

    @Override public EmptyDomain checkEmpty(ContainedProcess process) {
        if (process.pgid() <= 0) return EmptyDomain.UNKNOWN;
        List<Long> members = proc.groupMembers(process.pgid());
        if (members == null) return EmptyDomain.UNKNOWN;
        return members.isEmpty() ? EmptyDomain.EMPTY : EmptyDomain.NOT_EMPTY;
    }

    @Override public boolean escapedDomain(ContainedProcess process) {
        return verifier.detectsEscape(process);
    }

    @Override public void release(ContainedProcess process) {
        super.release(process);
    }
}

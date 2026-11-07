package com.clix.process;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Capability probing performed once at remote-client start (design doc "强监管模式").
 *
 * <p>Strict production mode requires a strong domain. Compatibility mode never hides the
 * downgrade: it returns an explicitly labelled PGID_ONLY containment. WSL Linux processes are
 * never "contained" by a Windows Job Object, so a WSL shell without systemd/cgroup v2 degrades
 * to PGID_ONLY rather than claiming a Job Object cleaned up the Linux children.</p>
 */
public final class CapabilityDetector {
    private CapabilityDetector() { }

    public record Result(ProcessContainment containment, PlatformShell shell,
                         String shellExecutable, String note) {
        public ContainmentLevel level() { return containment.level(); }
        public boolean degraded() { return containment.level().degraded(); }
    }

    public static Result detect(boolean strict) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean windows = os.startsWith("windows");
        String bash = windows ? windowsWslBash() : null;
        PlatformShell shell;
        String shellExecutable;

        if (windows && bash != null) {
            shell = PlatformShell.wsl(bash);
            shellExecutable = bash;
        } else if (windows) {
            shell = PlatformShell.disabled("无 WSL bash");
            shellExecutable = "cmd.exe";
        } else {
            shell = PlatformShell.local();
            shellExecutable = "bash";
        }

        if (shell.available() && hasCgroupV2AndUserSystemd(shell)) {
            return new Result(new CgroupV2Containment(shell, ProcReaders.linux(shell), shellExecutable),
                    shell, shellExecutable, "cgroup v2 + systemd --user 可用，使用强监管域");
        }
        // Job Objects only contain a *native* Windows process tree. The remote client's command
        // dialect is bash (resolved by where.exe bash), so it is enabled explicitly rather than
        // silently changing the launcher and breaking existing commands.
        if (windows && Boolean.getBoolean("clix.remote.nativeWindowsJobObject")) {
            WindowsJobObjectContainment job = new WindowsJobObjectContainment();
            if (job.available()) {
                return new Result(job, shell, shellExecutable,
                        "原生 Windows 命令使用 Job Object（KILL_ON_JOB_CLOSE，禁止 breakaway）");
            }
        }
        if (strict) {
            throw new IllegalStateException("严格模式要求强监管域，但 cgroup v2 / systemd --user"
                    + "（或显式启用的 Windows Job Object）不可用；拒绝以 PGID_ONLY 启动生产远程模式");
        }
        String reason = windows
                ? "未能建立 cgroup v2 / systemd --user 强监管域；"
                        + (bash != null
                            ? "不能用 Windows Job Object 管理 WSL Linux 进程，"
                            : "当前命令为 bash 方言，Job Object 需显式启用，")
                        + "降级为 PGID_ONLY 兼容模式"
                : PgidOnlyContainment.STANDARD_LIMITATION;
        ProcessIdentityVerifier.ProcReader reader = windows && bash == null
                ? ProcReaders.windows() : ProcReaders.linux(shell);
        return new Result(new PgidOnlyContainment(shell, reader, reason), shell,
                shellExecutable, reason);
    }

    private static String windowsWslBash() {
        String root = System.getenv("SystemRoot");
        if (root == null) return null;
        Path candidate = Path.of(root, "System32", "bash.exe");
        return Files.exists(candidate) ? candidate.toString() : null;
    }

    /** A real probe, not a guess: the unit and cgroup.controllers must both be usable. */
    public static boolean hasCgroupV2AndUserSystemd(PlatformShell shell) {
        String result = shell.run(
                "test -f /sys/fs/cgroup/cgroup.controllers && "
                        + "systemctl --user show-environment >/dev/null 2>&1 && echo clix-ok\n", 3_000L);
        return result != null && result.contains("clix-ok");
    }
}

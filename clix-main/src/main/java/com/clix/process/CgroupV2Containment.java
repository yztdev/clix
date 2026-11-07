package com.clix.process;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Strong Linux/WSL containment: one transient user-level systemd scope per operation. */
public final class CgroupV2Containment extends AbstractProcessContainment {

    private static final int CAPTURE_ATTEMPTS = 12;
    private static final long CAPTURE_INTERVAL_MILLIS = 50L;
    private final String shellExecutable;

    public CgroupV2Containment(PlatformShell shell,
                               ProcessIdentityVerifier.ProcReader proc,
                               String shellExecutable) {
        super(shell, proc, false);
        this.shellExecutable = shellExecutable;
    }

    public static String unitName(String operationId) {
        String shortId = operationId == null ? "" : operationId.replaceAll("[^A-Za-z0-9_.-]", "");
        if (shortId.length() > 12) shortId = shortId.substring(0, 12);
        if (shortId.isBlank()) throw new IllegalArgumentException("operation_id 无效，不能生成 systemd unit");
        return "clix-op-" + shortId + ".scope";
    }

    @Override public ContainmentLevel level() { return ContainmentLevel.CGROUP; }
    @Override public boolean available() { return shell.available(); }
    @Override protected String platform() { return "linux-cgroup-v2"; }

    /**
     * Always put the command in its operation scope. A one-element vector is the unsandboxed
     * command string and therefore retains the selected shell's -c semantics. Multi-element
     * vectors are already executable argv (for example bubblewrap) and are passed through
     * unchanged; they are never interpreted or re-quoted by another shell.
     */
    @Override public List<String> wrapCommand(List<String> baseArguments, StartRequest request) {
        if (baseArguments == null || baseArguments.isEmpty()) return baseArguments;
        String unit = unitName(request.operationId());
        List<String> child = new ArrayList<>();
        if (baseArguments.size() == 1) {
            String shell = request.shellExecutable() == null || request.shellExecutable().isBlank()
                    ? shellExecutable : request.shellExecutable();
            child.add(shell);
            child.add("-c");
            child.add(baseArguments.get(0));
        } else {
            child.addAll(baseArguments);
        }
        List<String> wrapped = new ArrayList<>();
        wrapped.add("systemd-run");
        wrapped.add("--user");
        wrapped.add("--scope");
        wrapped.add("--unit=" + unit);
        wrapped.add("--collect");
        wrapped.add("--property=KillMode=control-group");
        wrapped.add("--");
        wrapped.addAll(child);
        return List.copyOf(wrapped);
    }

    @Override protected ContainedProcess decorate(ContainedProcess base, StartRequest request) {
        String unit = unitName(request.operationId());
        String cgroupPath = captureCgroupPath(unit);
        return new ContainedProcess(base.operationId(), base.nonce(), base.pid(), base.pgid(),
                base.sid(), base.pidStartTicks(), cgroupPath, unit,
                base.nativeHandle(), base.javaProcess(), base.outputFile(), base.level(),
                base.platform(), base.startedAtNanos());
    }

    private String captureCgroupPath(String unit) {
        for (int attempt = 0; attempt < CAPTURE_ATTEMPTS; attempt++) {
            UnitSnapshot snapshot = unitSnapshot(unit);
            if (snapshot != null && "loaded".equals(snapshot.loadState())
                    && validCgroupPath(snapshot.controlGroup())) {
                return snapshot.controlGroup();
            }
            try {
                Thread.sleep(CAPTURE_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        // Fail closed later: without a captured cgroup identity, cancellation cannot be confirmed.
        return null;
    }

    private record UnitSnapshot(String loadState, String activeState, String subState,
                                String controlGroup) { }

    private UnitSnapshot unitSnapshot(String unit) {
        if (!safeUnit(unit)) return null;
        String output = probe("systemctl --user show --no-pager --property=LoadState "
                + "--property=ActiveState --property=SubState --property=ControlGroup "
                + shellQuote(unit) + "\n", 300L);
        if (output == null) return null;
        Map<String, String> values = new java.util.HashMap<>();
        for (String line : output.lines().toList()) {
            int split = line.indexOf('=');
            if (split > 0) values.put(line.substring(0, split), line.substring(split + 1));
        }
        String load = values.get("LoadState");
        if (load == null) return null;
        return new UnitSnapshot(load, values.get("ActiveState"), values.get("SubState"),
                values.get("ControlGroup"));
    }

    private static boolean safeUnit(String unit) {
        return unit != null && unit.matches("[A-Za-z0-9_.-]+\\.scope");
    }

    private static boolean validCgroupPath(String path) {
        return path != null && path.startsWith("/") && !path.contains("..")
                && path.matches("/[A-Za-z0-9_./@:-]+");
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    @Override public boolean signal(ContainedProcess process, Signal signal) {
        String unit = process.unitName();
        if (!safeUnit(unit)) return false;
        String name = switch (signal) {
            case INT -> "SIGINT";
            case TERM -> "SIGTERM";
            case KILL -> "SIGKILL";
        };
        StringBuilder script = new StringBuilder("sent=0; systemctl --user kill --kill-whom=all -s ")
                .append(name).append(' ').append(shellQuote(unit))
                .append(" >/dev/null 2>&1 && sent=1; ");
        if (signal == Signal.KILL && validCgroupPath(process.cgroupPath())) {
            String killFile = "/sys/fs/cgroup" + process.cgroupPath() + "/cgroup.kill";
            script.append("if [ -w ").append(shellQuote(killFile)).append(" ]; then ")
                    .append("echo 1 > ").append(shellQuote(killFile)).append(" && sent=1; fi; ");
        }
        script.append("[ \"$sent\" -eq 1 ] && echo CLIX_SIGNAL_SENT\n");
        String result = probe(script.toString(), 1_500L);
        return result != null && result.contains("CLIX_SIGNAL_SENT");
    }

    @Override public EmptyDomain checkEmpty(ContainedProcess process) {
        String path = process.cgroupPath();
        String unit = process.unitName();
        if (!validCgroupPath(path) || !safeUnit(unit)) return EmptyDomain.UNKNOWN;

        String directory = "/sys/fs/cgroup" + path;
        String exists = probe("if [ -d " + shellQuote(directory)
                + " ]; then echo PRESENT; else echo MISSING; fi\n", 1_200L);
        if (exists == null) return EmptyDomain.UNKNOWN;
        if (exists.trim().equals("PRESENT")) {
            String procs = probe("cat " + shellQuote(directory + "/cgroup.procs") + "\n", 1_200L);
            String events = probe("cat " + shellQuote(directory + "/cgroup.events") + "\n", 1_200L);
            if (procs == null || events == null) return EmptyDomain.UNKNOWN;
            Boolean populated = events.lines()
                    .filter(line -> line.trim().startsWith("populated "))
                    .map(line -> line.trim().equals("populated 1") ? Boolean.TRUE
                            : line.trim().equals("populated 0") ? Boolean.FALSE : null)
                    .filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
            if (populated == null) return EmptyDomain.UNKNOWN;
            return procs.isBlank() && !populated ? EmptyDomain.EMPTY : EmptyDomain.NOT_EMPTY;
        }
        if (!exists.trim().equals("MISSING")) return EmptyDomain.UNKNOWN;

        // --collect removes the unit/cgroup after completion. Missing is EMPTY only when we
        // captured this exact cgroup at launch, systemd confirms the unit is gone, and the
        // systemd-run client has exited. Missing data or an ambiguous state is UNKNOWN.
        UnitSnapshot snapshot = unitSnapshot(unit);
        if (snapshot == null) return EmptyDomain.UNKNOWN;
        boolean launcherExited = process.javaProcess() != null && !process.javaProcess().isAlive();
        return "not-found".equals(snapshot.loadState()) && launcherExited
                ? EmptyDomain.EMPTY : EmptyDomain.UNKNOWN;
    }

    @Override public void release(ContainedProcess process) {
        String unit = process.unitName();
        if (safeUnit(unit)) {
            probe("systemctl --user stop " + shellQuote(unit) + " >/dev/null 2>&1 || true\n", 1_200L);
        }
        super.release(process);
    }
}

package com.clix.process;

/**
 * How a foreground command is owned while it runs.
 *
 * <p>Design doc "工具状态 / Linux 和 WSL 执行域": only CGROUP and JOB_OBJECT are strong
 * containment domains. PGID_ONLY is an explicitly labelled compatibility mode: it must never
 * be presented as deterministic cleanup, and its result is CANCELLED_UNKNOWN as soon as a
 * process escapes the registered process group.</p>
 */
public enum ContainmentLevel {
    CGROUP("cgroup", true),
    JOB_OBJECT("job_object", true),
    PGID_ONLY("pgid_only", false),
    NONE("none", false);

    private final String wireValue;
    private final boolean strong;

    ContainmentLevel(String wireValue, boolean strong) {
        this.wireValue = wireValue;
        this.strong = strong;
    }

    public String wireValue() { return wireValue; }

    /** Whether the domain inherits across setsid / daemonize / nested children. */
    public boolean strong() { return strong; }

    public boolean degraded() { return !strong; }

    public static ContainmentLevel fromWire(String value) {
        if (value != null) {
            for (ContainmentLevel level : values()) {
                if (level.wireValue.equals(value)) return level;
            }
        }
        return NONE;
    }
}

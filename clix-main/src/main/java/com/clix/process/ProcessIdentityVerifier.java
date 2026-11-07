package com.clix.process;

import java.util.List;
import java.util.Objects;

/**
 * Guards against PID reuse and stale records before any signal is sent.
 *
 * <p>Design doc "PGID_ONLY 降级模式" and "并发和安全规则": only a process group whose PID
 * start time and per-operation nonce both still verify may be signalled. On POSIX the nonce is
 * injected as {@link #NONCE_ENV} at launch and read back from /proc; the start-time tick comes
 * from /proc/{@code pid}/stat field 22. For a strong domain (cgroup / Job Object) the native
 * handle is authoritative and /proc proof is optional.</p>
 */
public final class ProcessIdentityVerifier {

    public static final String NONCE_ENV = "CLIX_OPERATION_NONCE";

    /** Injectable /proc reader, so identity rules can be unit-tested without a real OS. */
    public interface ProcReader {
        long startTimeTicks(long pid);
        long processGroupId(long pid);
        long sessionId(long pid);
        String environmentNonce(long pid);
        List<Long> groupMembers(long pgid);
    }

    private final ProcReader proc;
    private final boolean requireProcProof;

    public ProcessIdentityVerifier(ProcReader proc, boolean requireProcProof) {
        this.proc = Objects.requireNonNull(proc, "proc");
        this.requireProcProof = requireProcProof;
    }

    public boolean verify(ProcessContainment.ContainedProcess handle) {
        if (handle == null || handle.nonce() == null || handle.nonce().isBlank() || handle.pid() <= 0) {
            return false;
        }
        if (handle.javaProcess() == null && handle.nativeHandle() == null) return false;
        if (!requireProcProof) {
            // Strong domains own a kernel handle; the nonce binds the record to the operation.
            return true;
        }
        if (handle.pidStartTicks() <= 0) return false;
        long currentStart = proc.startTimeTicks(handle.pid());
        if (currentStart <= 0 || currentStart != handle.pidStartTicks()) return false;
        String observedNonce = proc.environmentNonce(handle.pid());
        return handle.nonce().equals(observedNonce);
    }

    /**
     * True when a member of the registered group belongs to a different session. This is an
     * observation only: PGID_ONLY must not signal such a process, nor walk its PPID chain.
     */
    public boolean detectsEscape(ProcessContainment.ContainedProcess handle) {
        if (handle == null || handle.pgid() <= 0 || handle.sid() <= 0) return true;
        List<Long> members = proc.groupMembers(handle.pgid());
        if (members == null) return true;
        for (long pid : members) {
            long sid = proc.sessionId(pid);
            if (sid <= 0 || sid != handle.sid()) return true;
        }
        return false;
    }
}

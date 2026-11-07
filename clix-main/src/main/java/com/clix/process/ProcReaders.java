package com.clix.process;

import java.util.ArrayList;
import java.util.List;

/** /proc-backed identity facts, read through an injectable {@link PlatformShell}. */
public final class ProcReaders {
    private ProcReaders() { }

    public static ProcessIdentityVerifier.ProcReader linux(PlatformShell shell) {
        return new LinuxProc(shell);
    }

    /**
     * Windows-native processes have no /proc. A Job Object containment therefore relies on its
     * kernel handle instead; every probe here deliberately returns "unknown" so a stale record
     * can never be mistaken for a verified identity.
     */
    public static ProcessIdentityVerifier.ProcReader windows() {
        return new ProcessIdentityVerifier.ProcReader() {
            @Override public long startTimeTicks(long pid) { return -1L; }
            @Override public long processGroupId(long pid) { return -1L; }
            @Override public long sessionId(long pid) { return -1L; }
            @Override public String environmentNonce(long pid) { return null; }
            @Override public List<Long> groupMembers(long pgid) { return null; }
        };
    }

    private static final class LinuxProc implements ProcessIdentityVerifier.ProcReader {
        private final PlatformShell shell;

        LinuxProc(PlatformShell shell) { this.shell = shell; }

        /** /proc/{@code pid}/stat fields after the ")" (field 3 is index 0). */
        private String[] stat(long pid) {
            if (pid <= 0) return null;
            String raw = shell.run("cat /proc/" + pid + "/stat 2>/dev/null\n", 1_500L);
            if (raw == null) return null;
            int close = raw.lastIndexOf(')');
            if (close < 0 || close + 2 >= raw.length()) return null;
            String[] fields = raw.substring(close + 2).trim().split("\\s+");
            return fields.length > 19 ? fields : null;
        }

        private static long parse(String value) {
            try { return Long.parseLong(value); } catch (RuntimeException e) { return -1L; }
        }

        @Override public long startTimeTicks(long pid) {
            String[] f = stat(pid);
            return f == null ? -1L : parse(f[19]); // field 22
        }

        @Override public long processGroupId(long pid) {
            String[] f = stat(pid);
            return f == null ? -1L : parse(f[2]); // field 5
        }

        @Override public long sessionId(long pid) {
            String[] f = stat(pid);
            return f == null ? -1L : parse(f[3]); // field 6
        }

        @Override public String environmentNonce(long pid) {
            if (pid <= 0) return null;
            String raw = shell.run("tr '\\0' '\\n' < /proc/" + pid
                    + "/environ 2>/dev/null | sed -n 's/^CLIX_OPERATION_NONCE=//p' | head -1\n", 1_500L);
            if (raw == null) return null;
            String value = raw.trim();
            return value.isEmpty() ? null : value;
        }

        /** Only exact members of the registered process group; never a PPID-chain scan. */
        @Override public List<Long> groupMembers(long pgid) {
            if (pgid <= 0) return null;
            String raw = shell.run("pgrep -g " + pgid + " 2>/dev/null || true\n", 1_500L);
            if (raw == null) return null;
            List<Long> members = new ArrayList<>();
            for (String line : raw.split("\\R")) {
                String value = line.trim();
                if (value.isEmpty()) continue;
                long pid = parse(value);
                if (pid > 0) members.add(pid);
            }
            return members;
        }
    }
}

package com.clix.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Bounded, non-interactive shell used for platform facts (cgroup state, /proc reads, signals).
 * Every probe has its own timeout so background diagnosis can never outlive its 60s budget.
 */
public interface PlatformShell {

    /** Returns combined stdout, or null when the probe failed / timed out. */
    String run(String script, long timeoutMillis);

    boolean available();

    static PlatformShell local() {
        return new Impl(List.of("/bin/sh", "-c"), false, false);
    }

    /** Windows/WSL: feed the script over stdin to the distro's bash, as the existing code does. */
    static PlatformShell wsl(String bashExecutable) {
        if (bashExecutable == null || bashExecutable.isBlank()) return disabled("未找到 WSL bash");
        return new Impl(List.of(bashExecutable, "-c", "bash"), true, true);
    }

    static PlatformShell disabled(String reason) {
        return new PlatformShell() {
            @Override public String run(String script, long timeoutMillis) { return null; }
            @Override public boolean available() { return false; }
            @Override public String toString() { return "disabled(" + reason + ")"; }
        };
    }

    final class Impl implements PlatformShell {
        private static final java.util.Map<String, Boolean> PROBE_CACHE =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final List<String> command;
        private final boolean stdinScript;
        private final boolean probeOnce;

        Impl(List<String> command, boolean stdinScript, boolean probeOnce) {
            this.command = command;
            this.stdinScript = stdinScript;
            this.probeOnce = probeOnce;
        }

        @Override public String run(String script, long timeoutMillis) {
            Path out = null;
            try {
                out = Files.createTempFile("clix-probe-", ".out");
                List<String> argv = new java.util.ArrayList<>(command);
                if (!stdinScript) argv.add(script);
                ProcessBuilder pb = new ProcessBuilder(argv);
                pb.redirectErrorStream(true).redirectOutput(out.toFile());
                Process process = pb.start();
                if (stdinScript) {
                    try (var input = process.getOutputStream()) {
                        input.write(script.getBytes(StandardCharsets.UTF_8));
                    }
                }
                boolean exited = process.waitFor(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
                if (!exited) {
                    process.destroyForcibly();
                    return null;
                }
                return process.exitValue() == 0 ? Files.readString(out, StandardCharsets.UTF_8) : null;
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return null;
            } finally {
                if (out != null) {
                    try { Files.deleteIfExists(out); } catch (IOException ignored) { }
                }
            }
        }

        @Override public boolean available() {
            if (!probeOnce) return true;
            String key = String.join(" ", command);
            Boolean cached = PROBE_CACHE.get(key);
            if (cached != null) return cached;
            boolean ok = run("exit 0\n", 2_000L) != null;
            PROBE_CACHE.put(key, ok);
            return ok;
        }
    }
}

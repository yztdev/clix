package com.clix.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Common launch/output plumbing. stdout+stderr go to a per-operation temp file, so no reader
 * thread can outlive cancellation and no pipe can keep a killed process alive.
 */
public abstract class AbstractProcessContainment implements ProcessContainment {

    protected final PlatformShell shell;
    protected final ProcessIdentityVerifier.ProcReader proc;
    protected final ProcessIdentityVerifier verifier;
    private final Map<String, Path> outputs = new ConcurrentHashMap<>();

    protected AbstractProcessContainment(PlatformShell shell,
                                         ProcessIdentityVerifier.ProcReader proc,
                                         boolean requireProcProof) {
        this.shell = Objects.requireNonNull(shell, "shell");
        this.proc = Objects.requireNonNull(proc, "proc");
        this.verifier = new ProcessIdentityVerifier(proc, requireProcProof);
    }

    protected abstract String platform();

    /** Strong domains override this to attach their cgroup / unit / native handle. */
    protected ContainedProcess decorate(ContainedProcess base, StartRequest request) {
        return base;
    }

    @Override public boolean available() { return true; }

    @Override public String limitation() { return null; }

    @Override
    public ContainedProcess start(StartRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        if (!available()) throw new IOException("监管域不可用：" + limitation());
        List<String> arguments = wrapCommand(request.baseArguments(), request);
        if (arguments == null || arguments.isEmpty()) throw new IOException("命令行为空");
        Path output = Files.createTempFile("clix-operation-", ".out");
        try {
            ProcessBuilder builder = new ProcessBuilder(arguments);
            if (request.workingDirectory() != null) {
                builder.directory(request.workingDirectory().toFile());
            }
            builder.redirectErrorStream(true).redirectOutput(output.toFile());
            if (request.environment() != null) builder.environment().putAll(request.environment());
            builder.environment().put(ProcessIdentityVerifier.NONCE_ENV, request.nonce());
            Process process = builder.start();
            long pid = process.pid();
            long startTicks = proc.startTimeTicks(pid);
            long pgid = proc.processGroupId(pid);
            long sid = proc.sessionId(pid);
            if (pgid <= 0) pgid = -1L;
            if (sid <= 0) sid = -1L;
            outputs.put(request.operationId(), output);
            ContainedProcess base = new ContainedProcess(request.operationId(), request.nonce(),
                    pid, pgid, sid, startTicks, null, null, null, process, output,
                    level(), platform(), System.nanoTime());
            return decorate(base, request);
        } catch (RuntimeException e) {
            try { Files.deleteIfExists(output); } catch (IOException ignored) { }
            throw new IOException("无法在监管域内启动命令: " + e.getMessage(), e);
        }
    }

    @Override public boolean identityVerified(ContainedProcess process) {
        return verifier.verify(process);
    }

    @Override public boolean escapedDomain(ContainedProcess process) {
        return false; // a strong domain inherits across setsid / daemonize
    }

    @Override public String readOutput(ContainedProcess process) throws IOException {
        Path output = outputs.get(process.operationId());
        if (output == null || !Files.exists(output)) return "";
        return Files.readString(output, StandardCharsets.UTF_8);
    }

    @Override public Integer waitFor(ContainedProcess process, long millis) throws InterruptedException {
        Process javaProcess = process.javaProcess();
        if (javaProcess == null) return null;
        if (!javaProcess.waitFor(Math.max(1L, millis), TimeUnit.MILLISECONDS)) return null;
        return javaProcess.exitValue();
    }

    @Override public void release(ContainedProcess process) {
        Path output = outputs.remove(process.operationId());
        if (output != null) {
            try { Files.deleteIfExists(output); } catch (IOException ignored) { }
        }
        Process javaProcess = process.javaProcess();
        if (javaProcess != null) {
            try { javaProcess.getInputStream().close(); } catch (IOException ignored) { }
        }
    }

    /** Executes a bounded platform probe; null means "unknown", never "empty". */
    protected String probe(String script, long timeoutMillis) {
        return shell.run(script, timeoutMillis);
    }

    @Override public void close() {
        for (Path output : outputs.values()) {
            try { Files.deleteIfExists(output); } catch (IOException ignored) { }
        }
        outputs.clear();
    }
}

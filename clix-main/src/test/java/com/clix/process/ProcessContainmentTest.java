package com.clix.process;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Design doc "PGID_ONLY 降级模式", "强监管模式" and capability probing. */
class ProcessContainmentTest {

    private static final class RecordingShell implements PlatformShell {
        final List<String> scripts = new ArrayList<>();
        String systemd = "LoadState=loaded\nActiveState=active\nSubState=running\n"
                + "ControlGroup=/user.slice/clix-test.scope\n";
        String procs = "";
        String events = "populated 0\n";
        String probePrefix = "clix-ok";
        String directoryState = "PRESENT";
        boolean fail = false;

        @Override public String run(String script, long timeoutMillis) {
            scripts.add(script);
            if (fail) return null;
            if (script.contains("cgroup.procs")) return procs;
            if (script.contains("cgroup.events")) return events;
            if (script.contains("systemctl --user show --no-pager")) return systemd;
            if (script.contains("echo PRESENT") || script.contains("echo MISSING")) return directoryState;
            if (script.contains("cgroup.controllers")) return probePrefix;
            return "";
        }

        @Override public boolean available() { return !fail; }
    }

    private static final class ExitedProcess extends Process {
        @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
        @Override public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
        @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) { return true; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { }
        @Override public Process destroyForcibly() { return this; }
        @Override public boolean isAlive() { return false; }
    }

    private static final class StubProc implements ProcessIdentityVerifier.ProcReader {
        long startTime = 999L;
        long session = 4242L;
        String nonce = "nonce-1";
        List<Long> members = List.of(1L, 2L);

        @Override public long startTimeTicks(long pid) { return startTime; }
        @Override public long processGroupId(long pid) { return 4242L; }
        @Override public long sessionId(long pid) { return session; }
        @Override public String environmentNonce(long pid) { return nonce; }
        @Override public List<Long> groupMembers(long pgid) { return members; }
    }

    private static ProcessContainment.StartRequest startRequest() {
        return new ProcessContainment.StartRequest("op-1", "t1", "call-1", "nonce-1",
                "echo hi", List.of("echo hi"), Path.of("."), Map.of(), "/project", "bash", 5_000L);
    }

    private static ProcessContainment.ContainedProcess handle(String nonce, long startTicks,
                                                               ContainmentLevel level) {
        return new ProcessContainment.ContainedProcess("op-1", nonce, 4242L, 4242L, 4242L,
                startTicks, "/user.slice/clix-test.scope", "clix-test.scope", new Object(), null,
                null, level, "test", 0L);
    }

    @Test void identityVerifierRejectsRecycledPidAndWrongNonce() {
        StubProc proc = new StubProc();
        ProcessIdentityVerifier verifier = new ProcessIdentityVerifier(proc, true);
        assertTrue(verifier.verify(handle("nonce-1", 999L, ContainmentLevel.PGID_ONLY)));
        assertFalse(verifier.verify(handle("nonce-1", 111L, ContainmentLevel.PGID_ONLY)));
        proc.nonce = "other";
        assertFalse(verifier.verify(handle("nonce-1", 999L, ContainmentLevel.PGID_ONLY)));
        proc.startTime = -1L;
        assertFalse(verifier.verify(handle("nonce-1", 999L, ContainmentLevel.PGID_ONLY)));
    }

    @Test void pgidOnlyNeverSignalsAnUnverifiedGroup() {
        RecordingShell shell = new RecordingShell();
        StubProc proc = new StubProc();
        proc.startTime = 7L; // PID was recycled
        PgidOnlyContainment containment = new PgidOnlyContainment(shell, proc);
        assertFalse(containment.signal(handle("nonce-1", 999L, ContainmentLevel.PGID_ONLY),
                ProcessContainment.Signal.INT));
        assertTrue(shell.scripts.isEmpty(), "身份校验失败时不得执行任何平台命令");
    }

    @Test void pgidOnlyNeverWalksPpidChainOrScansDescendants() {
        RecordingShell shell = new RecordingShell();
        PgidOnlyContainment containment = new PgidOnlyContainment(shell, new StubProc());
        assertTrue(containment.signal(handle("nonce-1", 999L, ContainmentLevel.PGID_ONLY),
                ProcessContainment.Signal.TERM));
        String script = String.join("\n", shell.scripts);
        assertTrue(script.contains("kill -s TERM -- -4242"), script);
        assertFalse(script.contains("ppid"), script);
        assertFalse(script.contains("descendants"), script);
        assertFalse(script.contains("pgrep -P"), script);
    }

    @Test void pgidOnlyDetectsSecondSetsidAsUnknown() {
        RecordingShell shell = new RecordingShell();
        StubProc proc = new StubProc();
        proc.session = 9999L; // a group member belongs to another session
        PgidOnlyContainment containment = new PgidOnlyContainment(shell, proc);
        ProcessContainment.ContainedProcess process =
                handle("nonce-1", 999L, ContainmentLevel.PGID_ONLY);
        assertTrue(containment.escapedDomain(process));
        assertEquals(ProcessContainment.EmptyDomain.NOT_EMPTY, containment.checkEmpty(process));
        proc.members = List.of();
        assertEquals(ProcessContainment.EmptyDomain.EMPTY, containment.checkEmpty(process));
        proc.members = null;
        assertEquals(ProcessContainment.EmptyDomain.UNKNOWN, containment.checkEmpty(process));
    }

    @Test void pgidOnlyDowngradeIsExplicitlyLabelled() {
        PgidOnlyContainment containment = new PgidOnlyContainment(new RecordingShell(), new StubProc());
        assertEquals(ContainmentLevel.PGID_ONLY, containment.level());
        assertTrue(containment.level().degraded());
        assertNotNull(containment.limitation());
        assertTrue(containment.limitation().contains("PGID_ONLY"));
        List<String> wrapped = containment.wrapCommand(List.of("echo hi"), startRequest());
        assertTrue(wrapped.contains("echo hi") || wrapped.stream()
                .anyMatch(argument -> argument.contains("printf %s")),
                "降级模式必须保留原始命令语义，不能改变命令内容");
    }

    @Test void cgroupWrapsOrdinaryAndSandboxArgvWithoutLosingArgumentBoundaries() {
        CgroupV2Containment containment = new CgroupV2Containment(
                new RecordingShell(), new StubProc(), "bash");
        List<String> ordinary = containment.wrapCommand(List.of("echo hello"), startRequest());
        assertEquals("systemd-run", ordinary.get(0));
        assertTrue(ordinary.contains("--scope"));
        assertTrue(ordinary.contains("--property=KillMode=control-group"));
        assertTrue(ordinary.contains("bash"));
        assertTrue(ordinary.contains("-c"));
        assertTrue(ordinary.contains("echo hello"));

        List<String> sandboxArgv = List.of("/usr/bin/bwrap", "--die-with-parent", "--", "/bin/bash", "-c", "echo hi there");
        List<String> wrappedSandbox = containment.wrapCommand(sandboxArgv, startRequest());
        assertEquals("systemd-run", wrappedSandbox.get(0));
        assertTrue(wrappedSandbox.subList(wrappedSandbox.indexOf("--") + 1, wrappedSandbox.size())
                .equals(sandboxArgv), wrappedSandbox.toString());
        assertEquals(ContainmentLevel.CGROUP, containment.level());
        assertNull(containment.limitation());
        assertFalse(containment.escapedDomain(handle("nonce-1", 999L, ContainmentLevel.CGROUP)));
    }

    @Test void cgroupEmptyDomainRequiresEmptyProcsAndPopulatedZero() {
        RecordingShell shell = new RecordingShell();
        CgroupV2Containment containment = new CgroupV2Containment(shell, new StubProc(), "bash");
        ProcessContainment.ContainedProcess process = handle("nonce-1", 999L, ContainmentLevel.CGROUP);
        assertEquals(ProcessContainment.EmptyDomain.EMPTY, containment.checkEmpty(process));
        shell.events = "populated 1\n";
        assertEquals(ProcessContainment.EmptyDomain.NOT_EMPTY, containment.checkEmpty(process));
        shell.events = "populated 0\n";
        shell.procs = "1234\n";
        assertEquals(ProcessContainment.EmptyDomain.NOT_EMPTY, containment.checkEmpty(process));
        shell.fail = true;
        assertEquals(ProcessContainment.EmptyDomain.UNKNOWN, containment.checkEmpty(process));
    }

    @Test void cgroupMissingDirectoryIsUnknownUnlessSystemdConfirmsUnitGoneAndLauncherExited() {
        RecordingShell shell = new RecordingShell();
        CgroupV2Containment containment = new CgroupV2Containment(shell, new StubProc(), "bash");
        ProcessContainment.ContainedProcess base = handle("nonce-1", 999L, ContainmentLevel.CGROUP);
        ProcessContainment.ContainedProcess noLauncher = new ProcessContainment.ContainedProcess(
                base.operationId(), base.nonce(), base.pid(), base.pgid(), base.sid(), base.pidStartTicks(),
                base.cgroupPath(), base.unitName(), base.nativeHandle(), null, base.outputFile(),
                base.level(), base.platform(), base.startedAtNanos());
        shell.directoryState = "MISSING";
        shell.systemd = "LoadState=loaded\nActiveState=inactive\nSubState=dead\nControlGroup=\n";
        assertEquals(ProcessContainment.EmptyDomain.UNKNOWN, containment.checkEmpty(noLauncher));
        shell.systemd = "LoadState=not-found\nActiveState=inactive\nSubState=dead\nControlGroup=\n";
        assertEquals(ProcessContainment.EmptyDomain.UNKNOWN, containment.checkEmpty(noLauncher));
        ProcessContainment.ContainedProcess exitedLauncher = new ProcessContainment.ContainedProcess(
                base.operationId(), base.nonce(), base.pid(), base.pgid(), base.sid(), base.pidStartTicks(),
                base.cgroupPath(), base.unitName(), base.nativeHandle(), new ExitedProcess(), base.outputFile(),
                base.level(), base.platform(), base.startedAtNanos());
        assertEquals(ProcessContainment.EmptyDomain.EMPTY, containment.checkEmpty(exitedLauncher));
    }

    @Test void cgroupRejectsMissingControlGroupPathAsUnknown() {
        RecordingShell shell = new RecordingShell();
        CgroupV2Containment containment = new CgroupV2Containment(shell, new StubProc(), "bash");
        ProcessContainment.ContainedProcess base = handle("nonce-1", 999L, ContainmentLevel.CGROUP);
        ProcessContainment.ContainedProcess noPath = new ProcessContainment.ContainedProcess(
                base.operationId(), base.nonce(), base.pid(), base.pgid(), base.sid(), base.pidStartTicks(),
                null, base.unitName(), base.nativeHandle(), null, base.outputFile(),
                base.level(), base.platform(), base.startedAtNanos());
        assertEquals(ProcessContainment.EmptyDomain.UNKNOWN, containment.checkEmpty(noPath));
    }

    @Test void capabilityProbeRequiresRealCgroupV2AndUserSystemd() {
        RecordingShell shell = new RecordingShell();
        assertTrue(CapabilityDetector.hasCgroupV2AndUserSystemd(shell));
        shell.probePrefix = "no systemd";
        assertFalse(CapabilityDetector.hasCgroupV2AndUserSystemd(shell));
        shell.fail = true;
        assertFalse(CapabilityDetector.hasCgroupV2AndUserSystemd(shell));
    }

    @Test void containmentLevelsClassifyStrongDomains() {
        assertTrue(ContainmentLevel.CGROUP.strong());
        assertTrue(ContainmentLevel.JOB_OBJECT.strong());
        assertTrue(ContainmentLevel.PGID_ONLY.degraded());
        assertTrue(ContainmentLevel.NONE.degraded());
        assertEquals(ContainmentLevel.PGID_ONLY, ContainmentLevel.fromWire("pgid_only"));
        assertEquals(ContainmentLevel.NONE, ContainmentLevel.fromWire("bogus"));
    }
}

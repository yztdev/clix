package com.clix.process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** In-memory platform facts for acceptance tests; no WSL, systemd or Windows console needed. */
public final class FakeContainment implements ProcessContainment {

    public static final class FakeClock implements ActiveProcessSupervisor.Clock {
        private long nanos = 1_000_000_000L;

        @Override public synchronized long nanoTime() { return nanos; }

        @Override public synchronized void sleep(long millis) {
            nanos += TimeUnit.MILLISECONDS.toNanos(Math.max(0L, millis));
        }

        public synchronized void advance(long millis) {
            nanos += TimeUnit.MILLISECONDS.toNanos(Math.max(0L, millis));
        }
    }

    private final ContainmentLevel level;
    public volatile boolean identityOk = true;
    public volatile boolean escaped = false;
    public volatile boolean signalOk = true;
    public volatile boolean neverEmpty = false;
    public volatile boolean alwaysUnknown = false;
    public volatile Signal emptyAfterSignal = null;
    public volatile String output = "fake output";
    public volatile Integer waitExit = 0;
    public volatile int startCount;
    public volatile int releaseCount;
    public final List<Signal> signals = Collections.synchronizedList(new ArrayList<>());

    public FakeContainment() { this(ContainmentLevel.CGROUP); }

    public FakeContainment(ContainmentLevel level) { this.level = level; }

    @Override public ContainmentLevel level() { return level; }

    @Override public boolean available() { return true; }

    @Override public String limitation() {
        return level.degraded() ? PgidOnlyContainment.STANDARD_LIMITATION : null;
    }

    @Override public List<String> wrapCommand(List<String> base, StartRequest request) { return base; }

    @Override public ContainedProcess start(StartRequest request) {
        startCount++;
        return new ContainedProcess(request.operationId(), request.nonce(), 4242L, 4242L, 4242L,
                999L, "/sys/fs/cgroup/clix-test", "clix-test.scope", new Object(), null, null,
                level, "fake", System.nanoTime());
    }

    @Override public boolean identityVerified(ContainedProcess process) { return identityOk; }

    @Override public boolean signal(ContainedProcess process, Signal signal) {
        signals.add(signal);
        return signalOk;
    }

    @Override public EmptyDomain checkEmpty(ContainedProcess process) {
        if (alwaysUnknown) return EmptyDomain.UNKNOWN;
        if (neverEmpty) return EmptyDomain.NOT_EMPTY;
        if (emptyAfterSignal != null && !signals.contains(emptyAfterSignal)) {
            return EmptyDomain.NOT_EMPTY;
        }
        return EmptyDomain.EMPTY;
    }

    @Override public boolean escapedDomain(ContainedProcess process) { return escaped; }

    @Override public String readOutput(ContainedProcess process) { return output; }

    @Override public Integer waitFor(ContainedProcess process, long millis) { return waitExit; }

    @Override public void release(ContainedProcess process) { releaseCount++; }

    @Override public void close() { }
}

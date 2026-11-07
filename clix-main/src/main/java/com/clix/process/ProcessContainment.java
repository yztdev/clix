package com.clix.process;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Platform boundary for one foreground command's containment domain.
 *
 * <p>The supervisor owns the lifecycle; this interface owns the platform facts. Implementations
 * must never kill "suspected descendants" and must never follow a PPID chain. Signals go only
 * to a domain that was registered at start and whose nonce / PID start-time still verifies.</p>
 */
public interface ProcessContainment extends AutoCloseable {

    enum Signal { INT, TERM, KILL }

    /** EMPTY is the only state that may produce CANCELLED_CONFIRMED. */
    enum EmptyDomain { EMPTY, NOT_EMPTY, UNKNOWN }

    record StartRequest(
            String operationId,
            String turnId,
            String toolCallId,
            String nonce,
            String command,
            List<String> baseArguments,
            Path workingDirectory,
            Map<String, String> environment,
            String projectRoot,
            String shellExecutable,
            long timeoutMillis) { }

    /**
     * Everything the platform knows about a started operation. {@code pidStartTicks} is the
     * Linux /proc/{@code pid}/stat field 22 (or the Windows process creation time) captured at
     * start; it exists so a recycled PID can never be signalled by a stale record.
     */
    record ContainedProcess(
            String operationId,
            String nonce,
            long pid,
            long pgid,
            long sid,
            long pidStartTicks,
            String cgroupPath,
            String unitName,
            Object nativeHandle,
            Process javaProcess,
            Path outputFile,
            ContainmentLevel level,
            String platform,
            long startedAtNanos) { }

    ContainmentLevel level();

    /** False when capability probing could not establish a strong domain. */
    boolean available();

    /** Null for a strong domain; otherwise the exact, user-visible downgrade reason. */
    String limitation();

    /**
     * Lets a strong domain inject its launcher (systemd-run scope, Job Object wrapper, ...)
     * before the process exists. The default performs no wrapping.
     */
    default List<String> wrapCommand(List<String> baseArguments, StartRequest request) {
        return baseArguments;
    }

    ContainedProcess start(StartRequest request) throws IOException;

    /** Nonce and PID start-time verification; must be re-checked immediately before signalling. */
    boolean identityVerified(ContainedProcess process);

    /** Sends one bounded signal to the registered domain only. */
    boolean signal(ContainedProcess process, Signal signal);

    EmptyDomain checkEmpty(ContainedProcess process);

    /** Combined stdout/stderr produced so far. */
    String readOutput(ContainedProcess process) throws IOException;

    /** Bounded wait; null means still running. */
    Integer waitFor(ContainedProcess process, long millis) throws InterruptedException;

    /** True when the command escaped the domain (setsid/daemonize/unowned child detected). */
    boolean escapedDomain(ContainedProcess process);

    void release(ContainedProcess process);

    @Override default void close() { }
}

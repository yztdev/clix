package com.clix.remote;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Out-of-band turn-cancel channel delivered by the native {@code clix.exe} launcher (stage four).
 *
 * <p>On a real Windows console (cmd / PowerShell / Windows Terminal) JLine's {@code SIGINT} hook is
 * unreliable, which is why the design replaces {@code clix.cmd} with a native launcher. The launcher
 * installs {@code SetConsoleCtrlHandler}, and on {@code CTRL_C_EVENT} only enqueues a Win32 event and
 * returns {@code TRUE} (suppressing the default Ctrl+C behaviour that would otherwise tear down the
 * JVM). A worker thread then writes a single byte to a named pipe whose path is passed to the JVM in
 * the {@link #ENV_PIPE} environment variable. This bridge reads that byte and invokes the cancel
 * action, which routes to {@link CurrentTurnController#requestCancel()}.
 *
 * <p>On POSIX the environment variable is unset, so {@link #startIfConfigured} returns {@code null}
 * and the bridge is a no-op: the existing JLine {@code Terminal.Signal.INT} handler stays in charge.
 * The bridge never throws into application code and never performs blocking work in the signal path.
 */
final class ConsoleCancelBridge implements Closeable {
    /** Pipe path the native launcher writes cancel bytes to; empty/unset means "use JLine instead". */
    static final String ENV_PIPE = "CLIX_CANCEL_PIPE";

    /** Invoked once per cancel byte received from the launcher. */
    interface CancelAction { void cancel(); }

    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "clix-console-cancel");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Stream currently being pumped; closed by {@link #close()} to unblock a pending read. */
    private volatile InputStream stream;

    /** Starts the bridge only when {@link #ENV_PIPE} is configured; returns {@code null} otherwise. */
    static ConsoleCancelBridge startIfConfigured(CancelAction action) {
        String spec = System.getenv(ENV_PIPE);
        if (spec == null || spec.isBlank()) return null;
        ConsoleCancelBridge bridge = new ConsoleCancelBridge();
        bridge.start(action, spec);
        return bridge;
    }

    private void start(CancelAction action, String spec) {
        reader.submit(() -> {
            try (InputStream in = open(spec)) {
                if (in == null) return;
                pump(in, action);
            } catch (IOException | RuntimeException ignored) {
                // Any failure degrades to no native cancel; the JLine path may still cover POSIX.
            }
        });
    }

    /**
     * Reads cancel bytes until EOF or {@link #close()}. Each byte triggers one {@link CancelAction},
     * so multiple Ctrl+C deliveries map to STARTED then FORCED as designed. Package-private so the
     * unit test can drive it with an in-memory pipe without the native launcher.
     */
    void pump(InputStream in, CancelAction action) throws IOException {
        stream = in;
        try {
            byte[] buf = new byte[1];
            while (!closed.get()) {
                int n = in.read(buf);
                if (n <= 0) break; // EOF: launcher exited or closed the pipe.
                action.cancel();
            }
        } finally {
            if (stream == in) stream = null;
        }
    }

    private static InputStream open(String spec) throws IOException {
        // Windows named pipe: \\.\pipe\clix-cancel-<id> (also matches the generic \\ prefix).
        if (spec.startsWith("\\\\.\\pipe\\") || spec.startsWith("\\\\")) {
            return new java.io.FileInputStream(spec);
        }
        // POSIX fallback: an ordinary fifo / file path.
        Path path = Paths.get(spec);
        if (Files.exists(path)) return Files.newInputStream(path);
        return null;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        // Closing the stream unblocks a pending read (which otherwise ignores thread interrupts on
        // pipes) so the reader thread can exit promptly when the session ends.
        InputStream in = stream;
        if (in != null) {
            try { in.close(); } catch (IOException ignored) { }
        }
        reader.shutdownNow();
    }
}

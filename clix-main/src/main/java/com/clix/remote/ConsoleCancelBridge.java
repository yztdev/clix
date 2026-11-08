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
import com.sun.jna.Native;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Out-of-band turn-cancel channel delivered by the native {@code clix.exe} launcher (stage four).
 *
 * <p>On Windows, this class registers {@code SetConsoleCtrlHandler} directly through JNA, so the
 * ordinary {@code clix.cmd} launcher can suppress the console's default Ctrl+C termination and
 * dispatch cancellation asynchronously. A named-pipe mode remains supported for the legacy native
 * launcher, but it is not required by the local-first CLI. On POSIX, JLine remains responsible for
 * terminal interrupts.
 *
 * <p>On POSIX the environment variable is unset, so {@link #startIfConfigured} returns {@code null}
 * and the bridge is a no-op: the existing JLine {@code Terminal.Signal.INT} handler stays in charge.
 * The bridge never throws into application code and never performs blocking work in the signal path.
 */
public final class ConsoleCancelBridge implements Closeable {
    /** Pipe path the native launcher writes cancel bytes to; empty/unset means "use JLine instead". */
    static final String ENV_PIPE = "CLIX_CANCEL_PIPE";

    /** Invoked once per cancel byte received from the launcher. */
    public interface CancelAction { void cancel(); }

    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "clix-console-cancel");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Stream currently being pumped; closed by {@link #close()} to unblock a pending read. */
    private volatile InputStream stream;
    private Kernel32Console.HandlerRoutine nativeHandler;
    private Kernel32Console nativeKernel;

    private static final int CTRL_C_EVENT = 0;
    private static final int CTRL_BREAK_EVENT = 1;

    private interface Kernel32Console extends StdCallLibrary {
        interface HandlerRoutine extends StdCallLibrary.StdCallCallback {
            boolean callback(int controlType);
        }
        boolean SetConsoleCtrlHandler(HandlerRoutine handler, boolean add);
    }

    /** Uses the legacy named pipe when supplied; otherwise installs a direct Windows console handler. */
    public static ConsoleCancelBridge startIfConfigured(CancelAction action) {
        String spec = System.getenv(ENV_PIPE);
        if (spec != null && !spec.isBlank()) {
            ConsoleCancelBridge bridge = new ConsoleCancelBridge();
            bridge.start(action, spec);
            return bridge;
        }
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
            return null;
        }
        ConsoleCancelBridge bridge = new ConsoleCancelBridge();
        if (!bridge.installWindowsHandler(action)) {
            bridge.close();
            return null;
        }
        return bridge;
    }

    private boolean installWindowsHandler(CancelAction action) {
        try {
            nativeKernel = Native.load("kernel32", Kernel32Console.class, W32APIOptions.UNICODE_OPTIONS);
            nativeHandler = controlType -> {
                if (controlType != CTRL_C_EVENT && controlType != CTRL_BREAK_EVENT) return false;
                if (!closed.get()) {
                    try { reader.execute(action::cancel); }
                    catch (java.util.concurrent.RejectedExecutionException ignored) { }
                }
                return true; // suppress the JVM's default console termination for Ctrl+C / Ctrl+Break
            };
            return nativeKernel.SetConsoleCtrlHandler(nativeHandler, true);
        } catch (LinkageError | RuntimeException e) {
            nativeHandler = null;
            nativeKernel = null;
            return false;
        }
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
        if (nativeKernel != null && nativeHandler != null) {
            try { nativeKernel.SetConsoleCtrlHandler(nativeHandler, false); }
            catch (RuntimeException ignored) { }
            nativeHandler = null;
            nativeKernel = null;
        }
        reader.shutdownNow();
    }
}

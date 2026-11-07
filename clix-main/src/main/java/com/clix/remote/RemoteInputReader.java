package com.clix.remote;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Line reader for the remote client whose wait is interruptible.
 *
 * <p>The HITL approval prompt blocks on one line of stdin. A plain BufferedReader does not
 * wake up on {@link Thread#interrupt()}, which would leave the shared local-tool worker stuck
 * after Ctrl+C. Here a daemon pump owns the real stream and hands complete lines to an
 * interruptible queue; the main REPL and approval prompts both read from that queue, so there
 * is exactly one reader of the underlying terminal input.
 */
final class RemoteInputReader extends BufferedReader {
    /** Identity marker: LinkedBlockingDeque does not accept null. */
    private static final String EOF = new String("clix-remote-input-eof");

    private final BlockingDeque<String> lines = new LinkedBlockingDeque<>();
    private final AtomicBoolean discardNextLine = new AtomicBoolean();
    private final AtomicBoolean pumpStarted = new AtomicBoolean();
    private final AtomicReference<Thread> pump = new AtomicReference<>();
    private final BufferedReader source;
    private volatile IOException failure;
    private volatile boolean closed;

    RemoteInputReader(BufferedReader source) {
        super(new Reader() {
            @Override public int read(char[] buffer, int offset, int length) { return -1; }
            @Override public void close() { }
        }, 1);
        this.source = source;
    }

    /**
     * Lazily start the pump on first read. JLine initializes the Windows console while the
     * remote command is being assembled; a pump already blocked on Console.reader() at that
     * point can prevent the terminal from ever becoming ready.
     */
    private void ensurePump() {
        if (!pumpStarted.compareAndSet(false, true)) return;
        Thread thread = new Thread(() -> pump(source), "clix-remote-input-pump");
        thread.setDaemon(true);
        pump.set(thread);
        thread.start();
    }

    private void pump(BufferedReader source) {
        try {
            String line;
            while (!closed && (line = source.readLine()) != null) {
                if (discardNextLine.compareAndSet(true, false)) continue;
                lines.addLast(line);
            }
        } catch (IOException e) {
            failure = e;
        } finally {
            lines.addLast(EOF);
        }
    }

    @Override public String readLine() throws IOException {
        ensurePump();
        String line;
        try {
            line = lines.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("输入等待已被中断", e);
        }
        if (line == EOF) {
            if (failure != null) throw failure;
            return null;
        }
        return line;
    }

    /** Drops the line the user is currently typing (Ctrl+C with no active turn). */
    void discardCurrentLine() {
        ensurePump();
        lines.removeIf(line -> line != EOF);
        discardNextLine.set(true);
    }

    /** Puts a synthetic line in front of the queue, ahead of anything buffered. */
    void inject(String line) {
        if (!closed && line != null) lines.addFirst(line);
    }

    int queuedLines() {
        return lines.size();
    }

    @Override public boolean ready() {
        return !lines.isEmpty();
    }

    @Override public int read() {
        return -1;
    }

    @Override public int read(char[] buffer, int offset, int length) {
        return -1;
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        lines.addFirst(EOF);
        Thread thread = pump.get();
        if (thread != null) thread.interrupt();
        super.close();
    }
}

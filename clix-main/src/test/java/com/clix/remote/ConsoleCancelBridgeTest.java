package com.clix.remote;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleCancelBridgeTest {

    @Test
    void startIfConfiguredUsesPlatformInterruptPathWithoutLegacyPipe() {
        // POSIX uses JLine; Windows installs a direct JNA console handler.
        var bridge = ConsoleCancelBridge.startIfConfigured(() -> { });
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
            assertNotNull(bridge);
            bridge.close();
        } else {
            assertNull(bridge);
        }
    }

    @Test
    void eachCancelByteInvokesTheActionOnce() throws Exception {
        var out = new PipedOutputStream();
        var in = new PipedInputStream(out);
        var calls = new AtomicInteger();
        var done = new CountDownLatch(1);
        var bridge = new ConsoleCancelBridge();
        var thread = new Thread(() -> {
            try {
                bridge.pump(in, calls::incrementAndGet);
            } catch (IOException ignored) {
            } finally {
                done.countDown();
            }
        });
        thread.start();

        // Three Ctrl+C deliveries -> STARTED, then FORCED, then FORCED again.
        out.write(new byte[] { 1, 1, 1 });
        Thread.sleep(50);
        out.close();

        assertTrue(done.await(2, TimeUnit.SECONDS), "pump should stop on EOF");
        assertEquals(3, calls.get(), "每个取消字节应触发一次 CancelAction");
    }

    @Test
    void closeStopsPumpEarly() throws Exception {
        var calls = new AtomicInteger();
        var done = new CountDownLatch(1);
        var bridge = new ConsoleCancelBridge();
        var blocking = new BlockingStream();
        var thread = new Thread(() -> {
            try {
                bridge.pump(blocking, calls::incrementAndGet);
            } catch (IOException ignored) {
            } finally {
                done.countDown();
            }
        });
        thread.start();

        Thread.sleep(50); // pump is now blocked in read().
        bridge.close();   // should close the stream, unblock read, and end the pump.

        assertTrue(done.await(2, TimeUnit.SECONDS), "close() 应提前结束 pump");
        assertEquals(0, calls.get(), "阻塞期间不应触发任何取消");
    }

    /** Read blocks until closed, then throws — lets us verify close() unblocks a pending read. */
    private static final class BlockingStream extends InputStream {
        private volatile boolean closed;
        @Override public int read() throws IOException {
            while (!closed) {
                try { Thread.sleep(10); } catch (InterruptedException e) { throw new IOException(e); }
            }
            throw new IOException("closed");
        }
        @Override public void close() { closed = true; }
    }
}

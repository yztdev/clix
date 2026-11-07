package com.clix.remote;

import com.clix.tool.ToolRegistry.ToolInvocation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Future;

/** Dispatches server tool requests locally without blocking the socket listener. */
public final class RemoteClientSession implements AutoCloseable {
    @FunctionalInterface public interface Sender { void send(String type, JsonNode payload); }
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RemoteLocalTools tools;
    private final Sender sender;
    private final java.util.concurrent.atomic.AtomicReference<Thread> workerThread = new java.util.concurrent.atomic.AtomicReference<>();
    /** Connection-level resource: a cancelled turn must never shut this executor down. */
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), r -> {
                Thread t = new Thread(r, "clix-remote-tools"); t.setDaemon(true); workerThread.set(t); return t;
            });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<String> activeTurn = new AtomicReference<>();
    private final AtomicReference<Future<?>> activeTask = new AtomicReference<>();

    public RemoteClientSession(RemoteLocalTools tools, Sender sender) {
        this.tools = java.util.Objects.requireNonNull(tools);
        this.sender = java.util.Objects.requireNonNull(sender);
    }

    /** Phase three: a tool.effect frame is an observation, never a request or a reply. */
    public void sendEffect(ObjectNode payload) {
        sender.send("tool.effect", payload);
    }

    public void onMessage(JsonNode message) {
        if (closed.get() || !"tool.request".equals(message.path("type").asText())) return;
        JsonNode payload = message.path("payload");
        String turnId = payload.path("turn_id").asText("");
        String batchId = payload.path("batch_id").asText("");
        if (!RemoteProtocol.isTurnId(turnId) || !turnId.equals(activeTurn.get())
                || batchId.isBlank() || batchId.length() > 128
                || !payload.path("calls").isArray()
                || payload.path("calls").size() > 100) {
            close();
            return;
        }
        try {
            List<ToolInvocation> calls = JSON.convertValue(payload.path("calls"), new TypeReference<>() {});
            Future<?> task = worker.submit(() -> {
                if (closed.get() || !turnId.equals(activeTurn.get())) return;
                try {
                    tools.setReviews(payload.path("reviews"));
                    var results = tools.execute(calls);
                    if (closed.get() || !turnId.equals(activeTurn.get())) return;
                    ObjectNode response = JSON.createObjectNode().put("turn_id", turnId)
                            .put("batch_id", batchId);
                    response.set("results", JSON.valueToTree(results));
                    sender.send("tool.result", response);
                } catch (RuntimeException e) {
                    if (!Thread.currentThread().isInterrupted()) close();
                } finally {
                    activeTask.set(null);
                    // This single worker is connection-level: an interrupt used to cancel one
                    // turn must not leak into the next turn's tool execution.
                    Thread.interrupted();
                }
            });
            activeTask.set(task);
        } catch (IllegalArgumentException | java.util.concurrent.RejectedExecutionException e) { close(); }
    }

    public void startTurn(String turnId) {
        if (closed.get()) throw new IllegalStateException("远程连接已关闭");
        if (!RemoteProtocol.isTurnId(turnId)) throw new IllegalArgumentException("Turn ID 无效");
        Future<?> previous = activeTask.get();
        if (previous != null && !previous.isDone()) {
            throw new IllegalStateException("上一轮本地工具仍在清理");
        }
        tools.startTurn(turnId);
        activeTurn.set(turnId);
    }

    public void cancelTurn(String turnId) {
        if (closed.get() || !RemoteProtocol.isTurnId(turnId)) return;
        // AtomicReference CAS is identity-based; compare by value, then CAS the stored instance.
        String active = activeTurn.get();
        if (active == null || !active.equals(turnId) || !activeTurn.compareAndSet(active, null)) return;
        // Interrupt a pending HITL approval first: otherwise the connection-level worker
        // would stay blocked on stdin and could not serve the next turn.
        tools.cancelPendingApproval();
        tools.cancelTurn(turnId);
        Future<?> task = activeTask.get();
        if (task != null) task.cancel(true);
    }

    /** Connection-level shutdown, explicitly separated from {@link #cancelTurn(String)}. */
    public void closeConnection() {
        close();
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            tools.disconnect();
            tools.cancelActiveCommands();
            worker.shutdownNow();
        }
        // close() can race between the Ctrl+C handler, the WebSocket listener and
        // RemoteClientConnection.close().  Every non-worker caller must wait for
        // the in-flight local tool to finish its cancellation cleanup, even when
        // another thread won the closed flag first.  Otherwise the last
        // non-daemon thread may return and terminate the JVM while WSL cleanup is
        // still running.
        if (Thread.currentThread() != workerThread.get()) {
            try { worker.awaitTermination(12, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}

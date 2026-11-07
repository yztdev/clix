package com.clix.remote;

import com.clix.agent.Agent;
import com.clix.effect.InterruptionContextBuilder;
import com.clix.hitl.ApprovalRequest;
import com.clix.hitl.ApprovalResult;
import com.clix.hitl.LlmApprovalClassifier;
import com.clix.history.ConversationLedger;
import com.clix.llm.LlmClient;
import com.clix.llm.ProviderOutputGate;
import com.clix.memory.LongTermMemory;
import com.clix.memory.MemoryManager;
import com.clix.render.Renderer;
import com.clix.render.StatusInfo;
import com.clix.runtime.CancellationContext;
import com.clix.runtime.CancellationToken;
import com.clix.tool.ToolRegistry.ToolExecutionResult;
import com.clix.tool.ToolRegistry.ToolInvocation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** A single authenticated server-side Agent and its pending client tool requests. */
public final class RemoteAgentSession implements RemoteSocketServer.RemoteSession {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RemoteSocketServer.Sender sender;
    private final LlmClient model;
    private final Agent agent;
    private final Path historyDir;
    private final LlmApprovalClassifier classifier;
    /** Cached pool: a new turn must be able to start even if an old provider call ignores cancellation. */
    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "clix-remote-agent"); t.setDaemon(true); return t;
    });
    /** Bounded 8-second terminalization timer, independent of the agent run thread. */
    private final ScheduledExecutorService terminalizer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "clix-remote-turn-terminalizer"); t.setDaemon(true); return t;
    });
    private final ConcurrentHashMap<String, CompletableFuture<List<ToolExecutionResult>>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Thread, ActiveTurn> runningByThread = new ConcurrentHashMap<>();
    private final Set<String> terminalTurnIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<String> terminalTurnOrder = new ConcurrentLinkedQueue<>();
    private final AtomicInteger lateOutputCount = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<ActiveTurn> activeTurn = new AtomicReference<>();
    /** Phase three: append-only ledger plus idempotent effect ingest and one-shot injection. */
    private final ConversationLedger ledger;
    private final RemoteEffectCoordinator effects;

    public RemoteAgentSession(String userId, Path dataRoot, LlmClient model, RemoteSocketServer.Sender sender)
            throws Exception {
        this(userId, dataRoot, model, sender, () -> null);
    }

    public RemoteAgentSession(String userId, Path dataRoot, LlmClient model, RemoteSocketServer.Sender sender,
                              java.util.function.Supplier<LlmClient> classifierClient) throws Exception {
        if (userId == null || !userId.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("用户 ID 无效");
        this.sender = java.util.Objects.requireNonNull(sender);
        this.model = java.util.Objects.requireNonNull(model);
        this.classifier = new LlmApprovalClassifier(classifierClient);
        Path userDir = dataRoot.toAbsolutePath().normalize().resolve(userId);
        historyDir = userDir.resolve("history").resolve("raw");
        Path project = userDir.resolve("server-workspace");
        Files.createDirectories(project);
        var registry = new RemoteAgentToolRegistry(this::dispatch);
        registry.setProjectPath(project.toString());
        var memory = new MemoryManager(model, new LongTermMemory(userDir.resolve("memory").toFile()));
        agent = new Agent(model, registry, memory, () -> "", false);
        ledger = ConversationLedger.open(userDir.resolve("history"), UUID.randomUUID().toString());
        agent.setConversationLedger(ledger);
        effects = new RemoteEffectCoordinator(ledger);
        // Request-scoped context: it is a system/context segment for exactly this turn's
        // model request, never an assistant message and never part of agentHistory.
        agent.setExternalContextSupplier(() -> {
            ActiveTurn current = runningByThread.get(Thread.currentThread());
            if (current == null) current = activeTurn.get();
            return current == null || current.interruptionContext == null
                    ? "" : current.interruptionContext;
        });
        agent.setRenderer(new EventRenderer());
        agent.setReturnFinalResponseWhenStreamed(true);
    }

    @Override public void onMessage(JsonNode message) {
        String type = message.path("type").asText();
        JsonNode payload = message.path("payload");
        if ("user.message".equals(type)) {
            String turnId = payload.path("turn_id").asText("");
            String text = payload.path("content").asText("");
            ActiveTurn turn = new ActiveTurn(turnId, "auto".equals(payload.path("mode").asText("ask")));
            if (!RemoteProtocol.isTurnId(turnId) || text.isBlank() || text.length() > 100_000
                    || closed.get() || terminalTurnIds.contains(turnId)
                    || !activeTurn.compareAndSet(null, turn)) {
                sendError(turnId, "任务内容无效、Turn ID 重复或已有任务正在执行");
                return;
            }
            classifier.startTurn(text);
            // Store only the raw user goal in the ledger; the generated note is context,
            // never the user's original target (V1.2 C).
            String goal = payload.path("user_message").asText("");
            effects.turnStarted(turnId, goal.isBlank() ? text : goal);
            boolean clientAttached = text.contains(InterruptionContextBuilder.OPEN);
            String context = effects.planInjection(turnId, effects.newRequestId(), clientAttached);
            if (context != null) turn.interruptionContext = context.isEmpty() ? null : context;
            worker.submit(() -> run(turn, text));
        } else if ("tool.effect".equals(type)) {
            // Late, out-of-order and conflicting observations are facts, never protocol
            // errors: ingest keeps every version and never closes the WebSocket (V1.2 E).
            if (payload.isObject()) effects.ingest((ObjectNode) payload.deepCopy());
        } else if ("tool.result".equals(type)) {
            String turnId = payload.path("turn_id").asText("");
            ActiveTurn turn = activeTurn.get();
            if (turn == null || !turn.id.equals(turnId) || turn.cancelRequested.get()
                    || terminalTurnIds.contains(turnId) || !turn.gate.acceptsOutput()) {
                lateOutputCount.incrementAndGet();
                return;
            }
            String id = payload.path("batch_id").asText("");
            CompletableFuture<List<ToolExecutionResult>> request = pending.remove(id);
            if (request == null) { sendError(turnId, "工具结果不属于当前请求"); return; }
            try {
                List<ToolExecutionResult> results = JSON.convertValue(payload.path("results"), new TypeReference<>() {});
                effects.toolsCompleted(turnId, payload.path("results"));
                request.complete(results);
            } catch (IllegalArgumentException e) { request.completeExceptionally(e); }
        } else if ("turn.cancel".equals(type)) {
            cancel(payload.path("turn_id").asText(""), payload.path("force").asBoolean(false),
                    payload.path("cancel_timeout_ms").asLong(RemoteProtocol.CANCEL_DEADLINE_MS));
        } else if ("history.list".equals(type)) {
            listHistory();
        } else if ("history.read".equals(type)) {
            readHistory(payload.path("id").asText(""));
        } else {
            sendError(payload.path("turn_id").asText(""), "远程模式不支持此消息");
        }
    }

    private void listHistory() {
        try (var files = Files.list(historyDir)) {
            var entries = JSON.createArrayNode();
            files.filter(path -> path.getFileName().toString().matches("[0-9a-fA-F-]{36}\\.jsonl"))
                    .filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .sorted(java.util.Comparator.comparingLong(RemoteAgentSession::modified).reversed())
                    .limit(20).forEach(path -> entries.add(JSON.createObjectNode()
                            .put("id", path.getFileName().toString().replaceFirst("\\.jsonl$", ""))
                            .put("modified", modified(path))));
            ObjectNode payload = JSON.createObjectNode(); payload.set("sessions", entries);
            sender.send("history.list", payload);
        } catch (Exception e) { sender.send("history.error", JSON.createObjectNode().put("error", "无法读取会话列表")); }
    }

    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path, java.nio.file.LinkOption.NOFOLLOW_LINKS).toMillis(); }
        catch (java.io.IOException e) { return 0; }
    }

    private void readHistory(String id) {
        if (!id.matches("[0-9a-fA-F-]{36}")) {
            sender.send("history.error", JSON.createObjectNode().put("error", "会话编号无效")); return;
        }
        Path file = historyDir.resolve(id + ".jsonl");
        try {
            if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1_000_000) {
                throw new java.io.IOException("missing or too large");
            }
            var messages = JSON.createArrayNode();
            for (var entry : ConversationLedger.readEntriesSkippingBrokenLines(file)) {
                var message = entry.message();
                if (message != null && ("user".equals(message.role()) || "assistant".equals(message.role()))
                        && message.content() != null && !message.content().isBlank()) {
                    messages.add(JSON.createObjectNode().put("role", message.role()).put("content", message.content()));
                    if (messages.size() == 100) break;
                }
            }
            ObjectNode result = JSON.createObjectNode().put("id", id); result.set("messages", messages);
            sender.send("history.entries", result);
        } catch (Exception e) { sender.send("history.error", JSON.createObjectNode().put("error", "会话不存在或无法读取")); }
    }

    private void run(ActiveTurn turn, String text) {
        // The worker pool reuses threads: an interrupt from a previous cancelled turn must not
        // make the new turn's CancellationToken look cancelled (doc test matrix item 2).
        Thread.interrupted();
        turn.thread = Thread.currentThread();
        runningByThread.put(turn.thread, turn);
        CancellationToken token = CancellationContext.startRun();
        turn.cancellation = token;
        try {
            sender.send("turn.started", turnPayload(turn.id));
            if (turn.cancelRequested.get()) token.cancel();
            String result = agent.run(text);
            turn.runReturned = true;
            // A returned model request is an observable send success for injection (V1.2 F).
            markModelRequestSent(turn);
            if (turn.cancelRequested.get() || token.isCancelled()) {
                terminalize(turn);
            } else {
                ObjectNode payload = turnPayload(turn.id).put("content", result == null ? "" : result);
                if (turn.terminalSent.compareAndSet(false, true)) {
                    closeGate(turn, "provider_stream_end");
                    // Provider protocol stream-end is the first-line gate's observable termination.
                    turn.adapterGate.providerStreamEnd();
                    rememberTerminalTurn(turn.id);
                    effects.turnCompleted(turn.id);
                    activeTurn.compareAndSet(turn, null);
                    sender.send("turn.completed", payload);
                }
            }
        } catch (Exception e) {
            turn.runReturned = true;
            if (turn.cancelRequested.get() || token.isCancelled()) terminalize(turn);
            else if (turn.terminalSent.compareAndSet(false, true)) {
                closeGate(turn, "provider_stream_end");
                turn.adapterGate.providerStreamEnd();
                rememberTerminalTurn(turn.id);
                activeTurn.compareAndSet(turn, null);
                sendError(turn.id, "任务执行失败");
            }
        } finally {
            CancellationContext.clear(token);
            turn.cancellation = null;
            runningByThread.remove(turn.thread);
            turn.thread = null;
            Thread.interrupted();
            cancelTerminalTask(turn);
            // If the 8s timer already fired, terminalize() is idempotent.
            if (turn.cancelRequested.get()) terminalize(turn);
            if (!turn.modelRequestSent.get()) effects.abandon(turn.id);
            activeTurn.compareAndSet(turn, null);
            turn.interruptionContext = null;
        }
    }

    private void markModelRequestSent(ActiveTurn turn) {
        if (turn.modelRequestSent.compareAndSet(false, true)) effects.markSent(turn.id);
    }

    private List<ToolExecutionResult> dispatch(List<ToolInvocation> calls) throws Exception {
        ActiveTurn turn = runningByThread.get(Thread.currentThread());
        if (turn == null) turn = activeTurn.get();
        if (closed.get() || turn == null || turn.cancelRequested.get()
                || terminalTurnIds.contains(turn.id) || !turn.gate.acceptsOutput()
                || !turn.adapterGate.acceptsChunk()) {
            throw new IllegalStateException("任务已取消或连接已关闭");
        }
        String id = UUID.randomUUID().toString();
        var future = new CompletableFuture<List<ToolExecutionResult>>();
        pending.put(id, future);
        effects.toolsRequested(turn.id, calls);
        ObjectNode payload = turnPayload(turn.id).put("batch_id", id);
        payload.set("calls", JSON.valueToTree(calls));
        try {
            if (turn.automaticMode) {
                var reviews = JSON.createArrayNode();
                for (ToolInvocation call : calls) {
                    if ("execute_command".equals(call.name())) {
                        var review = classifier.review(call.name(), call.argumentsJson());
                        reviews.add(JSON.createObjectNode().put("name", call.name()).put("arguments", call.argumentsJson())
                                .put("allowed", review.allowed()).put("reason", review.reason()));
                    }
                }
                payload.set("reviews", reviews);
            }
            if (closed.get() || turn.cancelRequested.get() || activeTurn.get() != turn) {
                throw new InterruptedException("任务已取消");
            }
            sender.send("tool.request", payload);
            return future.get(5, TimeUnit.MINUTES);
        } finally {
            pending.remove(id);
        }
    }

    private static ObjectNode turnPayload(String turnId) {
        return JSON.createObjectNode().put("turn_id", turnId == null ? "" : turnId);
    }

    private void sendError(String turnId, String error) {
        sender.send("turn.failed", turnPayload(turnId).put("error", error));
    }

    /**
     * Terminalizes a cancelled turn and emits turn.cancelled. Idempotent and callable from
     * either the agent thread or the 8-second timer thread.
     */
    private void terminalize(ActiveTurn turn) {
        if (!turn.terminalSent.compareAndSet(false, true)) return;
        cancelTerminalTask(turn);
        boolean transportConfirmed = turn.transportCancelConfirmed && turn.runReturned;
        if (transportConfirmed) {
            closeGate(turn, "request_destroyed_transport_closed");
            // Request object destroyed AND transport confirmed closed: observable termination.
            turn.adapterGate.requestDestroyedTransportClosed();
        } else {
            turn.gate.quarantine();
            // Keep the first-line gate quarantined; no provider end / transport-close evidence yet (V1.2 G).
            turn.adapterGate.onCancel();
        }
        ObjectNode payload = turnPayload(turn.id)
                .put("connection_reusable", true)
                // "confirmed" only when the run actually returned; otherwise the deadline fired.
                .put("termination", turn.runReturned ? "confirmed" : "unknown")
                // The server cannot observe the client-side workspace gate; never claim "safe".
                .put("workspace_guard", "unknown")
                .put("workspace_state", "needs_verification")
                .put("effect_audit", effects.view(turn.id).path("effect_audit").asText("unavailable"))
                .put("transport_cancel_status", transportConfirmed ? "confirmed" : "unconfirmed")
                // No provider in this codebase exposes a remote task-cancel acknowledgement.
                .put("provider_cancel_ack", "unsupported")
                .put("force", turn.forced)
                .put("late_output_count", lateOutputCount.get());
        turn.adapterGate.addDiagnostics(payload);
        // Append-only structured cancellation facts; also enriches the wire payload so the
        // client can build the deterministic interruption context from ledger-grade data.
        effects.cancelled(turn.id, payload);
        rememberTerminalTurn(turn.id);
        // Protocol-level readiness barrier: clear the active turn before sending the event.
        activeTurn.compareAndSet(turn, null);
        sender.send("turn.cancelled", payload);
    }

    private static void closeGate(ActiveTurn turn, String reason) {
        // Second-line server-side gate only. The first-line Provider Adapter gate is closed
        // through its dedicated observable-termination signals (providerStreamEnd /
        // requestDestroyedTransportClosed), never through a bare close() call (doc V1.2 G).
        if (!turn.cancelRequested.get()) turn.gate.close(reason);
    }

    /**
     * Phase-one cancellation: the output gate is switched before any cleanup, force is a
     * second Ctrl+C (it never extends the deadline), and the server terminalizes within its
     * own 8-second budget even when the provider call never returns.
     */
    private void cancel(String turnId, boolean force, long clientCancelTimeoutMs) {
        ActiveTurn turn = activeTurn.get();
        if (turn == null || !turn.id.equals(turnId) || terminalTurnIds.contains(turnId)) return;
        // First line: switch the Provider Adapter gate before any cleanup or business callback.
        turn.adapterGate.onCancel();
        turn.gate.quarantine();
        if (!turn.cancelRequested.compareAndSet(false, true)) {
            if (force) {
                turn.forced = true;
                terminalize(turn);
            }
            return;
        }
        turn.forced = force;
        CancellationToken token = turn.cancellation;
        if (token != null) token.cancel();
        Thread thread = turn.thread;
        if (thread != null) thread.interrupt();
        try {
            model.cancelInFlightCalls();
            turn.transportCancelConfirmed = true;
        } catch (RuntimeException ignored) {
            turn.transportCancelConfirmed = false;
        }
        pending.forEach((id, future) -> future.completeExceptionally(new IllegalStateException("任务已取消")));
        long budget = RemoteProtocol.serverTerminalizationMillis(clientCancelTimeoutMs);
        if (force || budget <= 0 || turn.runReturned) {
            terminalize(turn);
        } else {
            ScheduledFuture<?> task = terminalizer.schedule(() -> terminalize(turn), budget, TimeUnit.MILLISECONDS);
            turn.terminalTask.set(task);
        }
    }

    private static void cancelTerminalTask(ActiveTurn turn) {
        ScheduledFuture<?> task = turn.terminalTask.getAndSet(null);
        if (task != null) task.cancel(false);
    }

    private void rememberTerminalTurn(String turnId) {
        if (turnId == null || turnId.isBlank() || terminalTurnIds.contains(turnId)) return;
        terminalTurnIds.add(turnId);
        terminalTurnOrder.add(turnId);
        while (terminalTurnOrder.size() > RemoteProtocol.TERMINAL_TURN_HISTORY) {
            String oldest = terminalTurnOrder.poll();
            if (oldest != null) terminalTurnIds.remove(oldest);
        }
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            ActiveTurn turn = activeTurn.get();
            if (turn != null) cancel(turn.id, true, 0);
            worker.shutdownNow();
            terminalizer.shutdownNow();
        }
    }

    private static final class ActiveTurn {
        private final String id;
        private final boolean automaticMode;
        private final TurnOutputGate gate = new TurnOutputGate();
        /** V1.2 G first line: the Provider Adapter per-turn chunk gate, consulted before any business callback. */
        private final ProviderOutputGate adapterGate = new ProviderOutputGate();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final AtomicBoolean terminalSent = new AtomicBoolean();
        private final AtomicReference<ScheduledFuture<?>> terminalTask = new AtomicReference<>();
        private volatile CancellationToken cancellation;
        private volatile Thread thread;
        private volatile boolean runReturned;
        private volatile boolean transportCancelConfirmed;
        private volatile boolean forced;
        private volatile String interruptionContext;
        private final AtomicBoolean modelRequestSent = new AtomicBoolean();

        private ActiveTurn(String id, boolean automaticMode) {
            this.id = id;
            this.automaticMode = automaticMode;
        }
    }

    /** Streams only chunks belonging to the turn bound to the calling thread. */
    private final class EventRenderer implements Renderer {
        private final PrintStream discard = new PrintStream(OutputStream.nullOutputStream());
        public void start() { }
        public void close() { discard.close(); }
        public PrintStream stream() { return discard; }
        public boolean rendersReasoning() { return false; }
        public void appendAssistantContentDelta(String delta) {
            ActiveTurn turn = runningByThread.get(Thread.currentThread());
            if (turn == null) turn = activeTurn.get();
            if (turn == null) { lateOutputCount.incrementAndGet(); return; }
            // First line (V1.2 G): the Provider Adapter per-turn gate must intercept the chunk
            // before any business callback. After turn.cancel the gate is QUARANTINED; the chunk
            // text is never retained or dispatched, only counted and hashed.
            if (!turn.adapterGate.acceptsChunk()) {
                turn.adapterGate.recordQuarantinedChunk(delta);
                lateOutputCount.incrementAndGet();
                return;
            }
            if (!turn.gate.acceptsOutput() || turn.terminalSent.get() || terminalTurnIds.contains(turn.id)) {
                turn.gate.recordQuarantinedChunk(delta);
                lateOutputCount.incrementAndGet();
                return;
            }
            // First streamed delta is the earliest observable "model request was sent".
            markModelRequestSent(turn);
            sender.send("message.delta", turnPayload(turn.id).put("content", delta));
        }
        public void appendToolCalls(List<LlmClient.ToolCall> calls) { }
        public void appendDiff(String path, String before, String after) { }
        public void updateStatus(StatusInfo status) { }
        public ApprovalResult promptApproval(ApprovalRequest request) { return ApprovalResult.reject("无法远程确认"); }
        public int openPalette(String title, List<String> items) { return -1; }
    }
}

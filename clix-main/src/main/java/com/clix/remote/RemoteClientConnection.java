package com.clix.remote;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/** Client WebSocket connection; never reconnects or replays operations automatically. */
public final class RemoteClientConnection implements AutoCloseable {
    /** Design doc: CONNECTING → OPEN → CLOSING → CLOSED; cancelling a turn never closes it. */
    public enum ConnectionState { CONNECTING, OPEN, CLOSING, CLOSED }

    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final OkHttpClient http;
    private final RemoteClientSession tools;
    private final BiConsumer<String, JsonNode> events;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<String> activeTurnId = new AtomicReference<>();
    private final AtomicReference<String> cancellingTurnId = new AtomicReference<>();
    private final Set<String> terminalTurnIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<String> terminalTurnOrder = new ConcurrentLinkedQueue<>();
    private final AtomicInteger lateEventCount = new AtomicInteger();
    private volatile ConnectionState connectionState = ConnectionState.CONNECTING;
    private volatile WebSocket socket;
    private volatile String sessionId;
    /** Crash-recovered effect summaries produced before session.ready must not be lost. */
    private final ConcurrentLinkedQueue<ObjectNode> pendingEffects = new ConcurrentLinkedQueue<>();

    private boolean isReady() { return sessionId != null && !closed.get(); }

    private void drainPendingEffects() {
        ObjectNode payload;
        while ((payload = pendingEffects.poll()) != null) {
            try { tools.sendEffect(payload); }
            catch (RuntimeException ignored) { pendingEffects.add(payload); break; }
        }
    }

    public RemoteClientConnection(URI uri, String token, RemoteLocalTools localTools,
                                  BiConsumer<String, JsonNode> events) {
        this(uri, token, localTools, events, java.time.Duration.ofSeconds(20));
    }

    RemoteClientConnection(URI uri, String token, RemoteLocalTools localTools,
                           BiConsumer<String, JsonNode> events, java.time.Duration pingInterval) {
        validateUri(uri);
        this.http = new OkHttpClient.Builder().pingInterval(pingInterval.toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS).build();
        if (token == null || token.isBlank()) throw new IllegalArgumentException("远程凭证不能为空");
        this.events = java.util.Objects.requireNonNull(events);
        this.tools = new RemoteClientSession(localTools, this::send);
        localTools.connectEffectSink((type, payload) -> {
            if (isReady()) tools.sendEffect(payload);
            else pendingEffects.add(payload.deepCopy());
        });
        Request request = new Request.Builder().url(uri.toString()).header("Authorization", "Bearer " + token).build();
        socket = http.newWebSocket(request, new Listener());
    }

    static void validateUri(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || !("wss".equalsIgnoreCase(uri.getScheme())
                || ("ws".equalsIgnoreCase(uri.getScheme()) && isLoopback(uri.getHost())))) {
            throw new IllegalArgumentException("远程服务器必须使用 wss；仅本机测试可使用 ws");
        }
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    public void send(String type, JsonNode payload) {
        String id = sessionId;
        WebSocket current = socket;
        if (closed.get() || id == null || current == null) throw new IllegalStateException("远程连接尚未就绪或已关闭");
        ObjectNode frame = JSON.createObjectNode().put("version", RemoteProtocol.VERSION)
                .put("session_id", id).put("type", type);
        frame.set("payload", payload);
        if (!current.send(frame.toString())) { close(); throw new IllegalStateException("远程连接已关闭"); }
    }

    public void startTurn(String turnId) {
        if (closed.get()) throw new IllegalStateException("远程连接已关闭");
        if (!RemoteProtocol.isTurnId(turnId) || !activeTurnId.compareAndSet(null, turnId)) {
            throw new IllegalStateException("已有任务正在执行或 Turn ID 无效（当前 Turn："
                    + activeTurnId.get() + "，请求：" + turnId + "）");
        }
        try {
            tools.startTurn(turnId);
        } catch (RuntimeException e) {
            activeTurnId.compareAndSet(turnId, null);
            throw e;
        }
    }

    /** Cancels one turn while keeping the authenticated WebSocket and local tool service reusable. */
    public void cancelTurn(String turnId) {
        cancelTurn(turnId, false);
    }

    public void cancelTurn(String turnId, boolean force) {
        if (!RemoteProtocol.isTurnId(turnId)) throw new IllegalArgumentException("Turn ID 无效");
        if (!turnId.equals(activeTurnId.get())) return;
        if (!force && !cancellingTurnId.compareAndSet(null, turnId)) return;
        if (force) cancellingTurnId.compareAndSet(null, turnId);
        tools.cancelTurn(turnId);
        String id = sessionId;
        WebSocket current = socket;
        if (closed.get() || id == null || current == null) throw new IllegalStateException("远程连接已关闭");
        ObjectNode frame = JSON.createObjectNode().put("version", RemoteProtocol.VERSION)
                .put("session_id", id).put("type", "turn.cancel");
        frame.set("payload", JSON.createObjectNode().put("turn_id", turnId)
                .put("reason", "user_interrupt").put("force", force)
                .put("cancel_timeout_ms", RemoteProtocol.CANCEL_DEADLINE_MS));
        if (!current.send(frame.toString())) { close(); throw new IllegalStateException("远程连接已关闭"); }
    }

    /** Releases only the logical gate after the absolute client cancellation deadline. */
    public void cancelDeadlineExpired(String turnId) {
        if (!RemoteProtocol.isTurnId(turnId)) return;
        tools.cancelTurn(turnId);
        rememberTerminalTurn(turnId);
        clearTurnGate(turnId);
    }

    /**
     * AtomicReference.compareAndSet compares object identity. A turn_id parsed from a network
     * frame is never the same String instance as the one passed to startTurn(), so the gate
     * must be cleared by value while still using CAS on the exact stored reference.
     */
    private void clearTurnGate(String turnId) {
        String active = activeTurnId.get();
        if (active != null && active.equals(turnId)) activeTurnId.compareAndSet(active, null);
        String cancelling = cancellingTurnId.get();
        if (cancelling != null && cancelling.equals(turnId)) cancellingTurnId.compareAndSet(cancelling, null);
    }

    public ConnectionState connectionState() { return connectionState; }
    public int lateEventCount() { return lateEventCount.get(); }
    public boolean isTerminalTurn(String turnId) { return terminalTurnIds.contains(turnId); }

    /** Explicit connection-level shutdown; distinct from cancelling a single turn. */
    public void closeConnection() { close(); }

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
            connectionState = ConnectionState.CLOSING;
            tools.closeConnection();
            WebSocket current = socket;
            if (current != null) current.cancel();
            http.dispatcher().executorService().shutdownNow();
            http.connectionPool().evictAll();
            connectionState = ConnectionState.CLOSED;
        }
    }

    private final class Listener extends WebSocketListener {
        @Override public void onOpen(WebSocket socket, Response response) {
            RemoteClientConnection.this.socket = socket;
            connectionState = ConnectionState.OPEN;
        }
        @Override public void onMessage(WebSocket socket, String text) {
            try {
                if (text.length() > 1_000_000) throw new IllegalArgumentException("消息过大");
                JsonNode frame = JSON.readTree(text);
                if (frame == null || !frame.isObject() || !frame.path("version").isInt()
                        || frame.path("version").intValue() != RemoteProtocol.VERSION
                        || !frame.path("type").isTextual()) {
                    throw new IllegalArgumentException("消息格式错误");
                }
                String id = frame.path("session_id").asText("");
                String type = frame.path("type").asText();
                if (sessionId == null && "session.ready".equals(type) && !id.isBlank()) {
                    sessionId = id;
                    drainPendingEffects();
                } else if (sessionId == null || !sessionId.equals(id) || "session.ready".equals(type)) {
                    throw new IllegalArgumentException("会话不匹配");
                }
                JsonNode payload = frame.path("payload");
                if (isTurnEvent(type)) {
                    String turnId = payload.path("turn_id").asText("");
                    // Phase one rejection rule: events from an old or unknown turn never reach
                    // the next turn. Count them so /status can show that they were discarded.
                    if (!turnId.equals(activeTurnId.get()) || terminalTurnIds.contains(turnId)) {
                        lateEventCount.incrementAndGet();
                        return;
                    }
                    if ("tool.request".equals(type)) {
                        if (!turnId.equals(cancellingTurnId.get())) tools.onMessage(frame);
                        return;
                    }
                    if (isTerminal(type)) {
                        rememberTerminalTurn(turnId);
                        clearTurnGate(turnId);
                    }
                }
                events.accept(type, payload);
            } catch (Exception e) { close(); events.accept("connection.failed", JSON.createObjectNode().put("error", "远程消息无效")); }
        }
        @Override public void onClosed(WebSocket socket, int code, String reason) {
            close(); events.accept("connection.closed", JSON.createObjectNode().put("code", code)
                    .put("error", "连接已关闭（代码 " + code + "）"));
        }
        @Override public void onClosing(WebSocket socket, int code, String reason) {
            socket.close(code, null);
            close();
            events.accept("connection.closed", JSON.createObjectNode().put("code", code)
                    .put("error", "连接已关闭（代码 " + code + "）"));
        }
        @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
            close(); events.accept("connection.failed", JSON.createObjectNode().put("error", "无法连接服务器或认证失败"));
        }
    }

    private static boolean isTurnEvent(String type) {
        return "turn.started".equals(type) || "turn.completed".equals(type)
                || "turn.cancelled".equals(type) || "turn.failed".equals(type)
                || "message.delta".equals(type) || "tool.request".equals(type);
    }

    private static boolean isTerminal(String type) {
        return "turn.completed".equals(type) || "turn.cancelled".equals(type)
                || "turn.failed".equals(type);
    }
}

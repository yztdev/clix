package com.clix.remote;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Authenticated loopback-only WebSocket transport; TLS belongs at the reverse proxy. */
public final class RemoteSocketServer implements AutoCloseable {
    public interface RemoteSession extends AutoCloseable {
        void onMessage(JsonNode message);
        @Override void close();
    }
    @FunctionalInterface public interface SessionFactory {
        RemoteSession open(String userId, String sessionId, Sender sender) throws Exception;
    }
    @FunctionalInterface public interface Sender { void send(String type, JsonNode payload); }

    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final Server server = new Server();
    private final ServerConnector connector = new ServerConnector(server);

    public RemoteSocketServer(int port, RemoteIdentityStore identities, SessionFactory factory) {
        this(port, identities, factory, java.time.Duration.ofMinutes(5));
    }

    RemoteSocketServer(int port, RemoteIdentityStore identities, SessionFactory factory, java.time.Duration idleTimeout) {
        connector.setHost("127.0.0.1");
        connector.setPort(port);
        server.addConnector(connector);
        ContextHandler context = new ContextHandler("/");
        server.setHandler(context);
        WebSocketUpgradeHandler handler = WebSocketUpgradeHandler.from(server, context, container -> {
            container.setIdleTimeout(idleTimeout);
            container.setMaxTextMessageSize(1_000_000);
            container.setMaxBinaryMessageSize(1);
            container.addMapping("/v1/remote", (request, response, callback) -> {
                String auth = request.getHeaders().get("Authorization");
                String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : null;
                String user = identities.authenticate(token);
                if (user == null) { response.setStatus(401); callback.succeeded(); return null; }
                return new Endpoint(user, factory);
            });
        });
        context.setHandler(handler);
    }

    public void start() throws Exception { server.start(); }
    public int port() { return connector.getLocalPort(); }
    @Override public void close() throws Exception { server.stop(); }

    public static final class Endpoint implements Session.Listener.AutoDemanding {
        private final String user;
        private final String sessionId = UUID.randomUUID().toString();
        private final SessionFactory factory;
        private final Queue<String> outbound = new ArrayDeque<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private Session socket;
        private RemoteSession session;
        private boolean sending;
        private int queuedBytes;

        Endpoint(String user, SessionFactory factory) { this.user = user; this.factory = factory; }

        @Override public void onWebSocketOpen(Session socket) {
            this.socket = socket;
            try {
                session = factory.open(user, sessionId, this::send);
                send("session.ready", JSON.createObjectNode());
            } catch (Exception e) { socket.close(1011, "会话初始化失败", Callback.NOOP); }
        }

        @Override public void onWebSocketText(String text) {
            try {
                JsonNode message = JSON.readTree(text);
                if (message == null || !message.isObject() || !message.path("version").isInt()
                        || message.path("version").intValue() != RemoteProtocol.VERSION
                        || !sessionId.equals(message.path("session_id").asText())
                        || !message.path("type").isTextual() || session == null) {
                    socket.close(1008, "消息格式错误", Callback.NOOP);
                    return;
                }
                session.onMessage(message);
            } catch (Exception e) { socket.close(1008, "消息处理失败", Callback.NOOP); }
        }

        private synchronized void send(String type, JsonNode payload) {
            if (closed.get() || socket == null || !socket.isOpen()) return;
            ObjectNode frame = JSON.createObjectNode().put("version", RemoteProtocol.VERSION)
                    .put("session_id", sessionId).put("type", type);
            frame.set("payload", payload);
            String encoded = frame.toString();
            int bytes = encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > 1_000_000 || outbound.size() >= 1000 || queuedBytes + bytes > 8_000_000) {
                socket.close(1008, "客户端接收过慢或消息过大", Callback.NOOP);
                return;
            }
            outbound.add(encoded);
            queuedBytes += bytes;
            if (!sending) sendNext();
        }

        private synchronized void sendNext() {
            String text = outbound.poll();
            if (text == null || closed.get()) { sending = false; return; }
            queuedBytes -= text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            sending = true;
            socket.sendText(text, Callback.from(this::sendNext, error -> socket.disconnect()));
        }

        private void finish() {
            if (closed.compareAndSet(false, true) && session != null) session.close();
        }
        @Override public void onWebSocketClose(int code, String reason) { finish(); }
        @Override public void onWebSocketError(Throwable error) { finish(); }
    }
}

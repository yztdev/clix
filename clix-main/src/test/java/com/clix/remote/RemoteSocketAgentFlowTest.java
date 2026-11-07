package com.clix.remote;

import com.clix.hitl.ApprovalRequest;
import com.clix.hitl.ApprovalResult;
import com.clix.hitl.HitlHandler;
import com.clix.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RemoteSocketAgentFlowTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "test-token-123456789012345678901234567890";
    @TempDir Path root;

    @Test
    void agentUsesClientFilesAcrossAuthenticatedSocket() throws Exception {
        Path project = Files.createDirectory(root.resolve("client"));
        Files.writeString(project.resolve("input.txt"), "client content");
        HitlHandler handler = new HitlHandler() {
            public ApprovalResult requestApproval(ApprovalRequest request) { return ApprovalResult.approve(); }
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean enabled) { }
        };
        var local = new RemoteLocalTools(new RemoteClientToolRegistry(handler), project);
        try (var server = new RemoteSocketServer(0, new RemoteIdentityStore(Map.of("alice", TOKEN)),
                (user, id, sender) -> new RemoteAgentSession(user, root.resolve("data"), new Model(), sender))) {
            server.start();
            var done = new CountDownLatch(1);
            var session = new AtomicReference<String>();
            var error = new AtomicReference<String>();
            var client = new OkHttpClient();
            var endpoint = new AtomicReference<RemoteClientSession>();
            var socketRef = new AtomicReference<WebSocket>();
            var socket = client.newWebSocket(new Request.Builder().url("http://127.0.0.1:" + server.port() + "/v1/remote")
                    .header("Authorization", "Bearer " + TOKEN).build(), new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, okhttp3.Response response) { socketRef.set(ws); }
                @Override public void onMessage(WebSocket ws, String text) {
                    try {
                        JsonNode frame = JSON.readTree(text);
                        String type = frame.path("type").asText();
                        if ("session.ready".equals(type)) {
                            session.set(frame.path("session_id").asText());
                            endpoint.set(new RemoteClientSession(local, (kind, payload) -> send(ws, session.get(), kind, payload)));
                            String turnId = java.util.UUID.randomUUID().toString();
                            endpoint.get().startTurn(turnId);
                            send(ws, session.get(), "user.message", JSON.createObjectNode()
                                    .put("turn_id", turnId)
                                    .put("content", "请读取 input.txt 并创建 output.txt"));
                        } else if ("tool.request".equals(type)) endpoint.get().onMessage(frame);
                        else if ("turn.completed".equals(type)) done.countDown();
                        else if ("turn.failed".equals(type)) { error.set(frame.toString()); done.countDown(); }
                    } catch (Exception e) { error.set(e.toString()); done.countDown(); }
                }
                @Override public void onFailure(WebSocket ws, Throwable t, okhttp3.Response response) { error.set(t.toString()); done.countDown(); }
            });
            try {
                assertTrue(done.await(10, TimeUnit.SECONDS));
                assertNull(error.get());
                assertEquals("result", Files.readString(project.resolve("output.txt")));
                assertFalse(Files.exists(root.resolve("data/alice/server-workspace/output.txt")));
            } finally {
                if (endpoint.get() != null) endpoint.get().close();
                socket.cancel(); client.dispatcher().executorService().shutdownNow(); client.connectionPool().evictAll();
            }
        }
    }

    private static void send(WebSocket ws, String session, String type, JsonNode payload) {
        ObjectNode frame = JSON.createObjectNode().put("version", RemoteProtocol.VERSION)
                .put("session_id", session).put("type", type);
        frame.set("payload", payload);
        ws.send(frame.toString());
    }

    private static final class Model implements LlmClient {
        int step;
        public ChatResponse chat(List<Message> messages, List<Tool> tools) { return chat(messages, tools, StreamListener.NO_OP); }
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            step++;
            if (step == 1) return call("one", "read_file", "{\"path\":\"input.txt\"}");
            if (step == 2) {
                if (messages.stream().noneMatch(m -> m.content() != null && m.content().contains("client content")))
                    throw new IllegalStateException("missing client content");
                return call("two", "write_file", "{\"path\":\"output.txt\",\"content\":\"result\"}");
            }
            return new ChatResponse("assistant", "完成", null, 1, 1);
        }
        private ChatResponse call(String id, String name, String args) {
            return new ChatResponse("assistant", "", List.of(new ToolCall(id, new ToolCall.Function(name, args))), 1, 1);
        }
        public String getModelName() { return "test"; }
        public String getProviderName() { return "test"; }
    }
}

package com.clix.remote;

import com.clix.hitl.ApprovalRequest;
import com.clix.hitl.ApprovalResult;
import com.clix.hitl.HitlHandler;
import com.clix.hitl.SwitchableHitlHandler;
import com.clix.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class RemoteAutoReviewTest {
    @Test void serverReviewsTheCurrentUserRequestAndExactCommand(@TempDir Path root) throws Exception {
        var json = new ObjectMapper();
        var captured = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var asked = new CountDownLatch(1);
        var classifier = new LlmClient() {
            public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                assertNull(tools);
                assertTrue(messages.get(1).content().contains("查看状态"));
                assertTrue(messages.get(1).content().contains("echo hello"));
                return new ChatResponse("assistant", "{\"decision\":\"allow\",\"reason\":\"test\"}", null, 1, 1);
            }
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
            public String getModelName() { return "test"; }
            public String getProviderName() { return "test"; }
        };
        try (var session = new RemoteAgentSession("alice", root, new Model(), (type, payload) -> {
            if ("tool.request".equals(type)) { captured.set(payload); asked.countDown(); }
        }, () -> classifier)) {
            String turnId = java.util.UUID.randomUUID().toString();
            var input = json.createObjectNode().put("type", "user.message");
            input.set("payload", json.createObjectNode().put("turn_id", turnId)
                    .put("content", "查看状态").put("mode", "auto"));
            session.onMessage(input);
            assertTrue(asked.await(10, TimeUnit.SECONDS));
            var review = captured.get().path("reviews").get(0);
            assertTrue(review.path("allowed").asBoolean());
            assertEquals("{\"command\":\"echo hello\"}", review.path("arguments").asText());
        }
    }
    @Test void unavailableClassifierFailsClosedWithoutRunningCommand(@TempDir Path root) throws Exception {
        var handler = new SwitchableHitlHandler(new HitlHandler() {
            public ApprovalResult requestApproval(ApprovalRequest request) { throw new AssertionError("unexpected confirmation"); }
            public boolean isEnabled() { return false; }
            public void setEnabled(boolean enabled) { }
        });
        handler.setHighRiskConfirmationEnabled(true);
        var local = new RemoteLocalTools(new RemoteClientToolRegistry(handler), root);
        var json = new ObjectMapper();
        var done = new CountDownLatch(1);
        var model = new Model();
        var sessionRef = new AtomicReference<RemoteAgentSession>();
        var clientRef = new AtomicReference<RemoteClientSession>();
        try (var session = new RemoteAgentSession("alice", root.resolve("server"), model, (type, payload) -> {
            if ("tool.request".equals(type)) {
                var frame = json.createObjectNode().put("type", type); frame.set("payload", payload);
                clientRef.get().onMessage(frame);
            } else if ("turn.completed".equals(type) || "turn.failed".equals(type)) done.countDown();
        }); var client = new RemoteClientSession(local, (type, payload) -> {
            var frame = json.createObjectNode().put("type", type); frame.set("payload", payload);
            sessionRef.get().onMessage(frame);
        })) {
            sessionRef.set(session); clientRef.set(client);
            String turnId = java.util.UUID.randomUUID().toString();
            client.startTurn(turnId);
            var input = json.createObjectNode().put("type", "user.message");
            input.set("payload", json.createObjectNode().put("turn_id", turnId)
                    .put("content", "查看状态").put("mode", "auto"));
            session.onMessage(input);
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertTrue(model.sawDenied);
        }
    }
    private static final class Model implements LlmClient {
        int step; volatile boolean sawDenied;
        public ChatResponse chat(List<Message> messages, List<Tool> tools) { return chat(messages, tools, StreamListener.NO_OP); }
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            if (step++ == 0) return new ChatResponse("assistant", "", List.of(new ToolCall("one",
                    new ToolCall.Function("execute_command", "{\"command\":\"echo hello\"}"))), 1, 1);
            sawDenied = messages.stream().anyMatch(m -> m.content() != null && m.content().contains("[AUTO]"));
            return new ChatResponse("assistant", "done", null, 1, 1);
        }
        public String getModelName() { return "test"; }
        public String getProviderName() { return "test"; }
    }
}

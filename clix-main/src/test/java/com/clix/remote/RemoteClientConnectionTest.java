package com.clix.remote;

import com.clix.hitl.TerminalHitlHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteClientConnectionTest {
    @Test void cancellationKeepsSameWebSocketReusable(@TempDir Path root) throws Exception {
        String token = "token-123456789012345678901234567890123";
        String firstId = java.util.UUID.randomUUID().toString();
        String secondId = java.util.UUID.randomUUID().toString();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch firstCancelled = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        com.clix.llm.LlmClient model = new com.clix.llm.LlmClient() {
            public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
                return chat(messages, tools, StreamListener.NO_OP);
            }
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
                if (calls.incrementAndGet() == 1) {
                    firstEntered.countDown();
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("cancelled", e); }
                }
                return new ChatResponse("assistant", "next turn", null, 1, 1);
            }
            public String getModelName() { return "test"; }
            public String getProviderName() { return "test"; }
        };
        try (var server = new RemoteSocketServer(0, new RemoteIdentityStore(Map.of("alice", token)),
                (user, id, sender) -> new RemoteAgentSession(user, root.resolve("server"), model, sender))) {
            server.start();
            CountDownLatch ready = new CountDownLatch(1);
            var local = new RemoteLocalTools(new RemoteClientToolRegistry(new TerminalHitlHandler(true)), root);
            try (var connection = new RemoteClientConnection(
                    new URI("ws://127.0.0.1:" + server.port() + "/v1/remote"), token, local,
                    (type, payload) -> {
                        if ("session.ready".equals(type)) ready.countDown();
                        if ("turn.cancelled".equals(type) && firstId.equals(payload.path("turn_id").asText())) {
                            firstCancelled.countDown();
                        }
                        if ("turn.completed".equals(type) && secondId.equals(payload.path("turn_id").asText())) {
                            secondCompleted.countDown();
                        }
                    })) {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                connection.startTurn(firstId);
                connection.send("user.message", new ObjectMapper().createObjectNode()
                        .put("turn_id", firstId).put("content", "first"));
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
                connection.cancelTurn(firstId);
                assertTrue(firstCancelled.await(5, TimeUnit.SECONDS));

                connection.startTurn(secondId);
                connection.send("user.message", new ObjectMapper().createObjectNode()
                        .put("turn_id", secondId).put("content", "second"));
                assertTrue(secondCompleted.await(5, TimeUnit.SECONDS));
                assertEquals(2, calls.get());
            }
        }
    }

    @Test void keepalivePreservesIdleConnection(@TempDir Path root) throws Exception {
        String token = "token-123456789012345678901234567890123";
        CountDownLatch received = new CountDownLatch(1);
        try (var server = new RemoteSocketServer(0, new RemoteIdentityStore(Map.of("alice", token)),
                (user, id, sender) -> new RemoteSocketServer.RemoteSession() {
                    public void onMessage(com.fasterxml.jackson.databind.JsonNode message) { received.countDown(); }
                    public void close() { }
                }, java.time.Duration.ofMillis(500))) {
            server.start();
            CountDownLatch ready = new CountDownLatch(1);
            var tools = new RemoteLocalTools(new RemoteClientToolRegistry(new TerminalHitlHandler(true)), root);
            try (var connection = new RemoteClientConnection(new URI("ws://127.0.0.1:" + server.port() + "/v1/remote"),
                    token, tools, (type, payload) -> { if (type.equals("session.ready")) ready.countDown(); },
                    java.time.Duration.ofMillis(100))) {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                Thread.sleep(1500);
                connection.send("user.message", new ObjectMapper().createObjectNode().put("content", "hello"));
                assertTrue(received.await(5, TimeUnit.SECONDS));
            }
        }
    }
    @Test void connectsAndDeliversAuthenticatedMessages(@TempDir Path root) throws Exception {
        String token = "token-123456789012345678901234567890123";
        CountDownLatch received = new CountDownLatch(1);
        try (var server = new RemoteSocketServer(0, new RemoteIdentityStore(Map.of("alice", token)),
                (user, id, sender) -> new RemoteSocketServer.RemoteSession() {
                    public void onMessage(com.fasterxml.jackson.databind.JsonNode message) { received.countDown(); }
                    public void close() { }
                })) {
            server.start();
            CountDownLatch ready = new CountDownLatch(1);
            var registry = new RemoteClientToolRegistry(new TerminalHitlHandler(true));
            var tools = new RemoteLocalTools(registry, root);
            try (var connection = new RemoteClientConnection(new URI("ws://127.0.0.1:" + server.port() + "/v1/remote"),
                    token, tools, (type, payload) -> { if (type.equals("session.ready")) ready.countDown(); })) {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                connection.send("user.message", new ObjectMapper().createObjectNode().put("content", "hello"));
                assertTrue(received.await(5, TimeUnit.SECONDS));
            }
        }
    }
}

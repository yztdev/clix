package com.clix.remote;

import com.clix.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteAgentCancellationTest {
    @Test void cancellationInterruptsModelAndCompletesTurn(@TempDir Path root) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        LlmClient model = new LlmClient() {
            public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
                return chat(messages, tools, StreamListener.NO_OP);
            }
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("cancelled"); }
                throw new IOException("unexpected");
            }
            public String getModelName() { return "test"; }
            public String getProviderName() { return "test"; }
        };
        var json = new ObjectMapper();
        String turnId = java.util.UUID.randomUUID().toString();
        try (var session = new RemoteAgentSession("alice", root, model, (type, payload) -> {
            if ("turn.cancelled".equals(type) || "turn.failed".equals(type)) finished.countDown();
        })) {
            var input = json.createObjectNode().put("type", "user.message");
            input.set("payload", json.createObjectNode().put("turn_id", turnId).put("content", "hello"));
            session.onMessage(input);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var cancel = json.createObjectNode().put("type", "turn.cancel");
            cancel.set("payload", json.createObjectNode().put("turn_id", turnId));
            session.onMessage(cancel);
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }

    @Test void cancelledTurnKeepsSessionReusableAndStaleCancelCannotHitNextTurn(@TempDir Path root) throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        CountDownLatch firstCancelled = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        LlmClient model = new LlmClient() {
            public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
                return chat(messages, tools, StreamListener.NO_OP);
            }
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
                int call = calls.incrementAndGet();
                try {
                    if (call == 1) {
                        firstEntered.countDown();
                        new CountDownLatch(1).await();
                        throw new IOException("first turn unexpectedly resumed");
                    }
                    secondEntered.countDown();
                    releaseSecond.await();
                    return new ChatResponse("assistant", "second turn ok", null, 1, 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("cancelled", e);
                }
            }
            public String getModelName() { return "test"; }
            public String getProviderName() { return "test"; }
        };
        var json = new ObjectMapper();
        String firstId = java.util.UUID.randomUUID().toString();
        String secondId = java.util.UUID.randomUUID().toString();
        try (var session = new RemoteAgentSession("alice", root, model, (type, payload) -> {
            if ("turn.cancelled".equals(type) && firstId.equals(payload.path("turn_id").asText())) {
                assertTrue(payload.path("connection_reusable").asBoolean());
                firstCancelled.countDown();
            }
            if ("turn.completed".equals(type) && secondId.equals(payload.path("turn_id").asText())) {
                secondCompleted.countDown();
            }
        })) {
            session.onMessage(userMessage(json, firstId, "first"));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            session.onMessage(cancelMessage(json, firstId));
            assertTrue(firstCancelled.await(5, TimeUnit.SECONDS));

            session.onMessage(userMessage(json, secondId, "second"));
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
            session.onMessage(cancelMessage(json, firstId));
            releaseSecond.countDown();
            assertTrue(secondCompleted.await(5, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
        }
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode userMessage(
            ObjectMapper json, String turnId, String content) {
        var message = json.createObjectNode().put("type", "user.message");
        message.set("payload", json.createObjectNode().put("turn_id", turnId).put("content", content));
        return message;
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode cancelMessage(ObjectMapper json, String turnId) {
        var message = json.createObjectNode().put("type", "turn.cancel");
        message.set("payload", json.createObjectNode().put("turn_id", turnId));
        return message;
    }
}

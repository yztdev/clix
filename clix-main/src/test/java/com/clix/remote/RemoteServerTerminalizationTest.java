package com.clix.remote;

import com.clix.llm.LlmClient;
import com.clix.runtime.CancellationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Phase-one test matrix: bounded server terminalization, force, honest fields, late chunks. */
class RemoteServerTerminalizationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void serverTerminalizesWithinBudgetWithoutProviderCooperation(@TempDir Path root) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicReference<JsonNode> cancelPayload = new AtomicReference<>();
        LlmClient ignoring = ignoringModel(entered);
        String turnId = java.util.UUID.randomUUID().toString();
        try (var session = new RemoteAgentSession("alice", root, ignoring, (type, payload) -> {
            if ("turn.cancelled".equals(type)) { cancelPayload.set(payload.deepCopy()); cancelled.countDown(); }
        })) {
            session.onMessage(userMessage(turnId, "first"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            long started = System.nanoTime();
            session.onMessage(cancelMessage(turnId, false, 2_200L));
            assertTrue(cancelled.await(3, TimeUnit.SECONDS), "服务端必须在自己的预算内终态化");
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsed < 2_000, "终态化耗时 " + elapsed + " ms，不应等到 8 秒");

            JsonNode payload = cancelPayload.get();
            assertNotNull(payload);
            assertTrue(payload.path("connection_reusable").asBoolean());
            // The agent run never returned: nothing here may be presented as confirmed/safe.
            assertEquals("unknown", payload.path("termination").asText());
            assertEquals("unconfirmed", payload.path("transport_cancel_status").asText());
            assertEquals("unsupported", payload.path("provider_cancel_ack").asText());
            assertTrue(payload.path("provider_output_quarantined").asBoolean());
            assertEquals("unavailable", payload.path("effect_audit").asText());
            assertNotEquals("safe", payload.path("workspace_guard").asText());
            assertTrue(payload.has("quarantined_chunk_count"));
            assertTrue(payload.has("quarantine_window"));
        }
    }

    @Test void forceTerminalizesImmediatelyAndDoesNotWaitForEightSeconds(@TempDir Path root) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicReference<JsonNode> cancelPayload = new AtomicReference<>();
        String turnId = java.util.UUID.randomUUID().toString();
        try (var session = new RemoteAgentSession("alice", root, ignoringModel(entered), (type, payload) -> {
            if ("turn.cancelled".equals(type)) { cancelPayload.set(payload.deepCopy()); cancelled.countDown(); }
        })) {
            session.onMessage(userMessage(turnId, "first"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            long started = System.nanoTime();
            session.onMessage(cancelMessage(turnId, true, 10_000L));
            assertTrue(cancelled.await(1, TimeUnit.SECONDS), "force 必须立即终态化");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 900);
            assertTrue(cancelPayload.get().path("force").asBoolean());
        }
    }

    @Test void cancellationDoesNotContaminateNextTurnAndLateChunksAreQuarantined(@TempDir Path root) throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseLate = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        CountDownLatch firstCancelled = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);
        AtomicReference<LlmClient.StreamListener> firstListener = new AtomicReference<>();
        AtomicBoolean secondSawCancelled = new AtomicBoolean(true);
        List<String> events = new CopyOnWriteArrayList<>();
        var calls = new java.util.concurrent.atomic.AtomicInteger();

        LlmClient model = new LlmClient() {
            public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
                return chat(messages, tools, StreamListener.NO_OP);
            }
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
                if (calls.incrementAndGet() == 1) {
                    firstListener.set(listener);
                    firstEntered.countDown();
                    await(releaseLate);
                    // A provider that ignores cancellation may still deliver a late chunk.
                    listener.onContentDelta("LATE-CHUNK-MARKER");
                    awaitForever();
                }
                secondSawCancelled.set(CancellationContext.isCancelled());
                secondEntered.countDown();
                await(releaseSecond);
                return new ChatResponse("assistant", "second turn ok", null, 1, 1);
            }
            public String getModelName() { return "test"; }
            public String getProviderName() { return "test"; }
        };

        String firstId = java.util.UUID.randomUUID().toString();
        String secondId = java.util.UUID.randomUUID().toString();
        try (var session = new RemoteAgentSession("alice", root, model, (type, payload) -> {
            events.add(type + "|" + payload.toString());
            if ("turn.cancelled".equals(type) && firstId.equals(payload.path("turn_id").asText())) firstCancelled.countDown();
            if ("turn.completed".equals(type) && secondId.equals(payload.path("turn_id").asText())) secondCompleted.countDown();
        })) {
            session.onMessage(userMessage(firstId, "first"));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            session.onMessage(cancelMessage(firstId, false, 2_200L));
            assertTrue(firstCancelled.await(3, TimeUnit.SECONDS));

            session.onMessage(userMessage(secondId, "second"));
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
            assertFalse(secondSawCancelled.get(), "上一轮的取消不能污染下一 Turn 的 CancellationToken");

            releaseLate.countDown();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (events.stream().noneMatch(e -> e.contains("LATE-CHUNK-MARKER")) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertFalse(events.stream().anyMatch(e -> e.contains("LATE-CHUNK-MARKER")),
                    "旧 Turn 的迟到 chunk 不得进入任何发送到客户端的消息");
            assertFalse(events.stream().anyMatch(e -> e.startsWith("message.delta") && e.contains(secondId)
                    && e.contains("LATE")), "迟到 chunk 不得串进新 Turn");

            releaseSecond.countDown();
            assertTrue(secondCompleted.await(5, TimeUnit.SECONDS));
        }
    }

    private static LlmClient ignoringModel(CountDownLatch entered) {
        return new LlmClient() {
            public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
                return chat(messages, tools, StreamListener.NO_OP);
            }
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
                entered.countDown();
                awaitForever();
                return null;
            }
            public String getModelName() { return "test"; }
            public String getProviderName() { return "test"; }
        };
    }

    private static void await(CountDownLatch latch) {
        while (latch.getCount() > 0) {
            try { latch.await(); }
            catch (InterruptedException e) { Thread.interrupted(); }
        }
    }

    /** Simulates a provider that ignores interrupts and never closes its stream. */
    private static void awaitForever() {
        while (true) {
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) { Thread.interrupted(); }
        }
    }

    private static ObjectNode userMessage(String turnId, String content) {
        var message = JSON.createObjectNode().put("type", "user.message");
        message.set("payload", JSON.createObjectNode().put("turn_id", turnId).put("content", content));
        return message;
    }

    private static ObjectNode cancelMessage(String turnId, boolean force, long timeoutMs) {
        var message = JSON.createObjectNode().put("type", "turn.cancel");
        message.set("payload", JSON.createObjectNode().put("turn_id", turnId)
                .put("reason", "user_interrupt").put("force", force).put("cancel_timeout_ms", timeoutMs));
        return message;
    }
}

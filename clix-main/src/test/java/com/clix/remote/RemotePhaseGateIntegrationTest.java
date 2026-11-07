package com.clix.remote;

import com.clix.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RemotePhaseGateIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ---- I-15 + I-16: first-line gate quarantines post-cancel chunks end-to-end ----
    @Test void providerAdapterQuarantinesLateChunksAndRecordsThem(@TempDir Path root) throws Exception {
        CountDownLatch preDelivered = new CountDownLatch(3);
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch cancelSignaled = new CountDownLatch(1);
        AtomicBoolean producedDone = new AtomicBoolean();
        List<String> deltas = Collections.synchronizedList(new ArrayList<>());
        List<ObjectNode> cancelledPayloads = Collections.synchronizedList(new ArrayList<>());

        String turnId = UUID.randomUUID().toString();
        LlmClient model = streamingModel(cancelSignaled, producedDone, 3, 10);
        try (var session = new RemoteAgentSession("alice", root, model, (type, payload) -> {
            if ("message.delta".equals(type)) {
                String content = payload.path("content").asText();
                deltas.add(content);
                if (deltas.size() <= 3) preDelivered.countDown();
            } else if ("turn.cancelled".equals(type) && turnId.equals(payload.path("turn_id").asText())) {
                cancelledPayloads.add((ObjectNode) payload.deepCopy());
                cancelled.countDown();
            }
        })) {
            session.onMessage(userMessage(turnId, "task"));
            assertTrue(preDelivered.await(5, TimeUnit.SECONDS), "pre-cancel chunks should be delivered");
            // turn.cancel switches the provider adapter gate to QUARANTINED.
            session.onMessage(cancelMessage(turnId));
            cancelSignaled.countDown();
            assertTrue(cancelled.await(5, TimeUnit.SECONDS), "turn.cancelled must be emitted");
        }

        // Pre-cancel chunks were delivered normally...
        assertTrue(deltas.contains("pre1_1") && deltas.contains("pre1_2") && deltas.contains("pre1_3"));
        // ...but the 10 post-cancel chunks never reached the UI / ledger / history queue.
        for (int i = 1; i <= 10; i++) {
            assertFalse(deltas.contains("post1_" + i), "late chunk post1_" + i + " must not reach the UI");
        }

        assertEquals(1, cancelledPayloads.size());
        ObjectNode p = cancelledPayloads.get(0);
        assertTrue(p.path("provider_output_quarantined").asBoolean(), "gate must report it was quarantined");
        assertEquals(10, p.path("quarantined_chunk_count").asInt(), "all 10 late chunks counted as quarantined");
        // I-16: transport confirmation must NOT be misreported as provider confirmation.
        assertNotEquals("confirmed", p.path("provider_cancel_ack").asText(),
                "transport_cancel_status confirmed must not be rewritten as provider confirmed");
        assertTrue(p.path("connection_reusable").asBoolean());
    }

    // ---- I-19a: two normal turns reuse the connection ----
    @Test void normalCompletionKeepsConnectionReusable(@TempDir Path root) throws Exception {
        CountDownLatch secondCompleted = new CountDownLatch(1);
        List<ObjectNode> completes = Collections.synchronizedList(new ArrayList<>());
        List<String> deltas = Collections.synchronizedList(new ArrayList<>());

        String firstId = UUID.randomUUID().toString();
        String secondId = UUID.randomUUID().toString();
        LlmClient model = new SimpleModel();
        try (var session = new RemoteAgentSession("alice", root, model, (type, payload) -> {
            if ("message.delta".equals(type)) deltas.add(payload.path("content").asText());
            else if ("turn.completed".equals(type)) {
                completes.add((ObjectNode) payload.deepCopy());
                if (secondId.equals(payload.path("turn_id").asText())) secondCompleted.countDown();
            }
        })) {
            session.onMessage(userMessage(firstId, "first"));
            Thread.sleep(200);
            session.onMessage(userMessage(secondId, "second"));
            assertTrue(secondCompleted.await(5, TimeUnit.SECONDS));
        }

        assertEquals(2, completes.size());
        // Both turns delivered their deltas (each turn gets a fresh OPEN adapter gate).
        assertTrue(deltas.contains("content-1"));
        assertTrue(deltas.contains("content-2"));
    }

    // ---- I-19b: a cancelled turn is followed by a working second turn ----
    @Test void cancelledTurnThenSecondTurnReusesConnection(@TempDir Path root) throws Exception {
        CountDownLatch preDelivered = new CountDownLatch(3);
        CountDownLatch firstCancelled = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);
        CountDownLatch cancelSignaled = new CountDownLatch(1);
        AtomicBoolean producedDone = new AtomicBoolean();
        List<String> secondDeltas = Collections.synchronizedList(new ArrayList<>());

        String firstId = UUID.randomUUID().toString();
        String secondId = UUID.randomUUID().toString();
        LlmClient model = streamingModel(cancelSignaled, producedDone, 3, 10);
        try (var session = new RemoteAgentSession("alice", root, model, (type, payload) -> {
            if ("message.delta".equals(type)) {
                String turn = payload.path("turn_id").asText();
                if (firstId.equals(turn) && preDelivered.getCount() > 0) preDelivered.countDown();
                else if (secondId.equals(turn)) secondDeltas.add(payload.path("content").asText());
            } else if ("turn.cancelled".equals(type) && firstId.equals(payload.path("turn_id").asText())) {
                firstCancelled.countDown();
            } else if ("turn.completed".equals(type) && secondId.equals(payload.path("turn_id").asText())) {
                secondCompleted.countDown();
            }
        })) {
            session.onMessage(userMessage(firstId, "first"));
            assertTrue(preDelivered.await(5, TimeUnit.SECONDS));
            session.onMessage(cancelMessage(firstId));
            cancelSignaled.countDown();
            assertTrue(firstCancelled.await(5, TimeUnit.SECONDS));

            session.onMessage(userMessage(secondId, "second"));
            assertTrue(secondCompleted.await(5, TimeUnit.SECONDS));
        }
        // The new turn's first-line gate is freshly OPEN, so its deltas are delivered.
        assertTrue(secondDeltas.contains("turn2-content"),
                "second turn deltas must be delivered through a fresh OPEN gate: " + secondDeltas);
    }

    // ---- I-19c: repeated Ctrl+C (second one is force) keeps the connection reusable ----
    @Test void doubleCtrlCDoesNotBreakConnectionReuse(@TempDir Path root) throws Exception {
        CountDownLatch firstCancelled = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);
        CountDownLatch cancelSignaled = new CountDownLatch(1);
        AtomicBoolean producedDone = new AtomicBoolean();
        List<ObjectNode> firstCancelledPayloads = Collections.synchronizedList(new ArrayList<>());

        String firstId = UUID.randomUUID().toString();
        String secondId = UUID.randomUUID().toString();
        LlmClient model = new StreamingModel(cancelSignaled, producedDone, 1, 0);
        try (var session = new RemoteAgentSession("alice", root, model, (type, payload) -> {
            if ("turn.cancelled".equals(type) && firstId.equals(payload.path("turn_id").asText())) {
                firstCancelledPayloads.add((ObjectNode) payload.deepCopy());
                firstCancelled.countDown();
            } else if ("turn.completed".equals(type) && secondId.equals(payload.path("turn_id").asText())) {
                secondCompleted.countDown();
            }
        })) {
            session.onMessage(userMessage(firstId, "first"));
            Thread.sleep(150);
            session.onMessage(cancelMessage(firstId));          // first Ctrl+C
            ObjectNode force = JSON.createObjectNode().put("type", "turn.cancel");
            force.set("payload", JSON.createObjectNode().put("turn_id", firstId).put("force", true));
            session.onMessage(force);                           // second Ctrl+C (force)
            cancelSignaled.countDown();
            assertTrue(firstCancelled.await(5, TimeUnit.SECONDS));

            session.onMessage(userMessage(secondId, "second"));
            assertTrue(secondCompleted.await(5, TimeUnit.SECONDS));
        }
        assertEquals(1, firstCancelledPayloads.size(), "turn.cancelled must be emitted exactly once");
        assertTrue(firstCancelledPayloads.get(0).path("connection_reusable").asBoolean());
    }

    // ---- helpers ----

    private static ObjectNode userMessage(String turnId, String content) {
        ObjectNode message = JSON.createObjectNode().put("type", "user.message");
        message.set("payload", JSON.createObjectNode().put("turn_id", turnId).put("content", content));
        return message;
    }

    private static ObjectNode cancelMessage(String turnId) {
        ObjectNode message = JSON.createObjectNode().put("type", "turn.cancel");
        message.set("payload", JSON.createObjectNode().put("turn_id", turnId));
        return message;
    }

    /** Streams `preCount` chunks, waits for cancellation, then streams `postCount` and finishes. */
    private static LlmClient streamingModel(CountDownLatch cancelSignaled, AtomicBoolean producedDone,
                                            int preCount, int postCount) {
        return new StreamingModel(cancelSignaled, producedDone, preCount, postCount);
    }

    static final class StreamingModel implements LlmClient {
        final CountDownLatch cancelSignaled;
        final AtomicBoolean producedDone;
        final int preCount;
        final int postCount;
        final AtomicInteger calls = new AtomicInteger();

        StreamingModel(CountDownLatch cancelSignaled, AtomicBoolean producedDone, int preCount, int postCount) {
            this.cancelSignaled = cancelSignaled;
            this.producedDone = producedDone;
            this.preCount = preCount;
            this.postCount = postCount;
        }

        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            int n = calls.incrementAndGet();
            if (n == 1) {
                Thread producer = new Thread(() -> {
                    for (int i = 1; i <= preCount; i++) listener.onContentDelta("pre" + n + "_" + i);
                    try { cancelSignaled.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    for (int i = 1; i <= postCount; i++) listener.onContentDelta("post" + n + "_" + i);
                    producedDone.set(true);
                }, "fake-producer");
                producer.start();
                while (!producedDone.get()) {
                    try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return new ChatResponse("assistant", "done", null, 1, 1);
            }
            listener.onContentDelta("turn" + n + "-content");
            return new ChatResponse("assistant", "turn" + n, null, 1, 1);
        }

        public String getModelName() { return "test"; }
        public String getProviderName() { return "test"; }
    }

    static final class SimpleModel implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            int n = calls.incrementAndGet();
            listener.onContentDelta("content-" + n);
            return new ChatResponse("assistant", "resp-" + n, null, 1, 1);
        }
        public String getModelName() { return "test"; }
        public String getProviderName() { return "test"; }
    }
}

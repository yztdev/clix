package com.clix.remote;

import com.clix.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RemoteHistoryTest {
    @Test void userCannotReadAnotherUsersHistory(@TempDir Path root) throws Exception {
        var json = new ObjectMapper();
        var aliceResult = new AtomicReference<JsonNode>();
        var aliceType = new AtomicReference<String>();
        var bobType = new AtomicReference<String>();
        try (var alice = new RemoteAgentSession("alice", root, new Model(), (type, payload) -> {
                 aliceType.set(type);
                 if ("history.list".equals(type)) aliceResult.set(payload);
             }); var bob = new RemoteAgentSession("bob", root, new Model(), (type, payload) -> bobType.set(type))) {
            alice.onMessage(json.createObjectNode().put("type", "history.list"));
            String aliceId = aliceResult.get().path("sessions").get(0).path("id").asText();
            var request = json.createObjectNode().put("type", "history.read");
            request.set("payload", json.createObjectNode().put("id", aliceId));
            alice.onMessage(request);
            assertEquals("history.entries", aliceType.get());
            bob.onMessage(request);
            assertEquals("history.error", bobType.get());
            request.set("payload", json.createObjectNode().put("id", "../../alice"));
            bob.onMessage(request);
            assertEquals("history.error", bobType.get());
        }
    }
    private static final class Model implements LlmClient {
        public ChatResponse chat(List<Message> messages, List<Tool> tools) { return new ChatResponse("assistant", "ok", null, 1, 1); }
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
        public String getModelName() { return "test"; }
        public String getProviderName() { return "test"; }
    }
}

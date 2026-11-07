package com.clix.remote;

import com.clix.agent.Agent;
import com.clix.llm.LlmClient;
import com.clix.memory.LongTermMemory;
import com.clix.memory.MemoryManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RemoteAgentIsolationTest {
    @TempDir Path dir;

    @Test
    void isolatedAgentDoesNotLoadServerProjectMemoryOrReadLocalImages() throws Exception {
        Files.writeString(dir.resolve("CLIX.md"), "SERVER_PRIVATE_PROJECT_MEMORY");
        Files.write(dir.resolve("private.png"), new byte[]{(byte) 137, 80, 78, 71});
        var client = new RecordingClient();
        var registry = new RemoteAgentToolRegistry(calls -> { throw new AssertionError("no tool expected"); });
        registry.setProjectPath(dir.toString());
        var memory = new MemoryManager(client, new LongTermMemory(dir.resolve("isolated-memory").toFile()));
        var agent = new Agent(client, registry, memory, () -> "", false);
        agent.run("@image:private.png hello");
        assertFalse(client.messages.get(0).content().contains("SERVER_PRIVATE_PROJECT_MEMORY"));
        LlmClient.Message user = client.messages.stream().filter(m -> "user".equals(m.role())).findFirst().orElseThrow();
        assertTrue(user.content().contains("@image:private.png"));
        assertTrue(user.contentParts() == null || user.contentParts().stream().noneMatch(LlmClient.ContentPart::isImage));
    }

    private static final class RecordingClient implements LlmClient {
        private List<Message> messages;
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            this.messages = List.copyOf(messages);
            return new ChatResponse("assistant", "ok", null, 1, 1);
        }
        public String getModelName() { return "test"; }
        public String getProviderName() { return "test"; }
    }
}

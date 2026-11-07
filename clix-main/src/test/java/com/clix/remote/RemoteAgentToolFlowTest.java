package com.clix.remote;

import com.clix.agent.Agent;
import com.clix.hitl.ApprovalRequest;
import com.clix.hitl.ApprovalResult;
import com.clix.hitl.HitlHandler;
import com.clix.llm.LlmClient;
import com.clix.memory.LongTermMemory;
import com.clix.memory.MemoryManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RemoteAgentToolFlowTest {
    @TempDir Path root;

    @Test
    void agentReadsAndWritesClientProjectThroughRemoteDispatcher() throws Exception {
        Path clientRoot = Files.createDirectory(root.resolve("client"));
        Path serverRoot = Files.createDirectory(root.resolve("server"));
        Files.writeString(clientRoot.resolve("input.txt"), "client data");
        HitlHandler approval = new HitlHandler() {
            public ApprovalResult requestApproval(ApprovalRequest request) { return ApprovalResult.approve(); }
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean enabled) { }
        };
        var local = new RemoteLocalTools(new RemoteClientToolRegistry(approval), clientRoot);
        local.startTurn(java.util.UUID.randomUUID().toString());
        var tools = new RemoteAgentToolRegistry(local::execute);
        tools.setProjectPath(serverRoot.toString());
        var model = new ScriptedClient();
        var memory = new MemoryManager(model, new LongTermMemory(root.resolve("memory").toFile()));
        var agent = new Agent(model, tools, memory, () -> "", false);

        assertTrue(agent.run("请读取 input.txt 并创建 output.txt").contains("完成"));
        assertEquals("result", Files.readString(clientRoot.resolve("output.txt")));
        assertFalse(Files.exists(serverRoot.resolve("output.txt")));
        assertTrue(model.sawClientContent);
    }

    private static final class ScriptedClient implements LlmClient {
        private int step;
        private boolean sawClientContent;
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return chat(messages, tools, StreamListener.NO_OP);
        }
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            step++;
            if (step == 1) return response("one", "read_file", "{\"path\":\"input.txt\"}");
            if (step == 2) {
                sawClientContent = messages.stream().anyMatch(m -> m.content() != null && m.content().contains("client data"));
                return response("two", "write_file", "{\"path\":\"output.txt\",\"content\":\"result\"}");
            }
            return new ChatResponse("assistant", "完成", null, 1, 1);
        }
        private ChatResponse response(String id, String name, String args) {
            return new ChatResponse("assistant", "", List.of(new ToolCall(id, new ToolCall.Function(name, args))), 1, 1);
        }
        public String getModelName() { return "test"; }
        public String getProviderName() { return "test"; }
    }
}

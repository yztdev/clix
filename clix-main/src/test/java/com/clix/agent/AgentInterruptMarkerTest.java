package com.clix.agent;

import com.clix.llm.LlmClient;
import com.clix.memory.InternalUserMessages;
import com.clix.runtime.CancellationContext;
import com.clix.runtime.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户中断后要留一条内部标记：下一轮模型能看到“上一轮没有最终回复”，
 * 同时这条标记不能被当成新的用户请求参与压缩轮次。
 */
class AgentInterruptMarkerTest {

    @TempDir
    Path tempDir;

    @Test
    void cancelledRunAppendsInterruptMarkerForNextTurn() {
        String previousMemoryDir = System.getProperty("clix.memory.dir");
        System.setProperty("clix.memory.dir", tempDir.toString());
        CancellationToken token = CancellationContext.startRun();
        try {
            token.cancel();
            Agent agent = new Agent(new NoOpClient());

            String result = agent.run("做到一半的任务");

            assertEquals("⏹️ 已取消当前任务。", result);
            List<LlmClient.Message> history = agent.getConversationHistory();
            LlmClient.Message last = history.get(history.size() - 1);
            assertEquals("user", last.role(), "标记以 user 角色进入历史，保证各家接口都能接受");
            assertTrue(last.content().startsWith(InternalUserMessages.INTERRUPT_MARKER));
            assertTrue(last.content().contains("没有生成最终回复"));
            assertTrue(InternalUserMessages.isInternal(last), "标记不能被压缩器当成真实用户轮次");
        } finally {
            CancellationContext.clear(token);
            if (previousMemoryDir == null) {
                System.clearProperty("clix.memory.dir");
            } else {
                System.setProperty("clix.memory.dir", previousMemoryDir);
            }
        }
    }

    private static final class NoOpClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            throw new AssertionError("取消路径不应调用模型");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            throw new AssertionError("取消路径不应调用模型");
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}

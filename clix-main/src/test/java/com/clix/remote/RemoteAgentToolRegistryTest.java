package com.clix.remote;

import com.clix.tool.ToolRegistry.ToolExecutionResult;
import com.clix.tool.ToolRegistry.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RemoteAgentToolRegistryTest {
    @Test
    void exposesOnlySupportedToolsAndForwardsWithoutExecutingOnServer() {
        var count = new AtomicInteger();
        var registry = new RemoteAgentToolRegistry(calls -> {
            count.incrementAndGet();
            var call = calls.get(0);
            return List.of(new ToolExecutionResult(call.id(), call.name(), call.argumentsJson(),
                    "client result", 0, false, List.of(), true, List.of()));
        });
        assertEquals(7, registry.getToolDefinitions().size());
        assertTrue(registry.getToolDefinitions().stream().noneMatch(t -> t.name().equals("save_memory")));
        var result = registry.executeTools(List.of(new ToolInvocation("1", "read_file", "{}")));
        assertEquals("client result", result.get(0).result());
        assertEquals(1, count.get());
        assertFalse(registry.executeTools(List.of(new ToolInvocation("2", "execute_command_extra", "{}")))
                .get(0).successful());
        assertEquals(1, count.get());
    }

    @Test
    void rejectsResultsThatDoNotMatchTheOriginalRequest() {
        var registry = new RemoteAgentToolRegistry(calls -> List.of(new ToolExecutionResult("different",
                "write_file", "{}", "ok", 0, false, List.of(), true, List.of())));
        var result = registry.executeTools(List.of(new ToolInvocation("1", "write_file", "{}")));
        assertFalse(result.get(0).successful());
        assertTrue(result.get(0).result().contains("状态可能不明"));
    }
}

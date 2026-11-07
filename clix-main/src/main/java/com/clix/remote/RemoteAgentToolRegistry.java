package com.clix.remote;

import com.clix.llm.LlmClient;
import com.clix.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Server-side tool registry that never executes the client's tools on the server. */
public final class RemoteAgentToolRegistry extends ToolRegistry {
    private static final Set<String> SUPPORTED = Set.of("read_file", "list_dir", "glob_files", "grep_code",
            "write_file", "edit_file", "execute_command");

    @FunctionalInterface
    public interface Dispatcher {
        List<ToolExecutionResult> execute(List<ToolInvocation> invocations) throws Exception;
    }

    private final Dispatcher dispatcher;

    public RemoteAgentToolRegistry(Dispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    @Override
    public List<LlmClient.Tool> getToolDefinitions() {
        return super.getToolDefinitions().stream().filter(tool -> SUPPORTED.contains(tool.name())).toList();
    }

    @Override
    public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
        if (invocations == null || invocations.isEmpty()) return List.of();
        // Reject the whole malformed batch rather than forwarding a subset in a changed order.
        for (ToolInvocation invocation : invocations) {
            if (invocation == null || invocation.id() == null || invocation.id().isBlank()
                    || invocation.name() == null || !SUPPORTED.contains(invocation.name())) {
                return failedBatch(invocations, "远程模式收到不支持或无效的工具调用");
            }
        }
        try {
            List<ToolExecutionResult> results = dispatcher.execute(List.copyOf(invocations));
            if (results == null || results.size() != invocations.size()) {
                return failedBatch(invocations, "客户端工具结果不完整，操作状态可能不明");
            }
            for (int i = 0; i < results.size(); i++) {
                ToolInvocation call = invocations.get(i);
                ToolExecutionResult result = results.get(i);
                if (result == null || !Objects.equals(call.id(), result.id())
                        || !Objects.equals(call.name(), result.name())
                        || !Objects.equals(call.argumentsJson(), result.argumentsJson())) {
                    return failedBatch(invocations, "客户端工具结果与请求不匹配，操作状态可能不明");
                }
            }
            return List.copyOf(results);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return failedBatch(invocations, "客户端连接中断或工具结果不可用，操作状态可能不明");
        }
    }

    private static List<ToolExecutionResult> failedBatch(List<ToolInvocation> calls, String message) {
        List<ToolExecutionResult> results = new ArrayList<>();
        for (ToolInvocation call : calls) {
            results.add(new ToolExecutionResult(call == null ? null : call.id(),
                    call == null ? null : call.name(), call == null ? null : call.argumentsJson(),
                    message, 0, false, List.of(), false, List.of()));
        }
        return List.copyOf(results);
    }
}

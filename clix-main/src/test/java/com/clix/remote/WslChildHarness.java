package com.clix.remote;

import com.clix.hitl.ApprovalRequest;
import com.clix.hitl.ApprovalResult;
import com.clix.hitl.HitlHandler;
import com.clix.tool.ToolRegistry.ToolInvocation;
import java.nio.file.Path;
import java.util.List;

/** Separate JVM used to simulate an abruptly terminated client. */
public final class WslChildHarness {
    public static void main(String[] args) {
        HitlHandler handler = new HitlHandler() {
            public ApprovalResult requestApproval(ApprovalRequest request) { return ApprovalResult.approve(); }
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean enabled) { }
        };
        var tools = new RemoteLocalTools(new RemoteClientToolRegistry(handler), Path.of(args[0]));
        if (args.length > 1 && "console".equals(args[1])) {
            runConsole(tools);
            return;
        }
        tools.execute(List.of(new ToolInvocation("one", "execute_command",
                "{\"command\":\"printf started > ready.txt; sleep 5; printf done > marker.txt\"}")));
    }

    private static void runConsole(RemoteLocalTools tools) {
        var finished = new java.util.concurrent.CountDownLatch(1);
        try (var terminal = org.jline.terminal.TerminalBuilder.builder().system(true).nativeSignals(true).dumb(true).build();
             var client = new RemoteClientSession(tools, (type, payload) -> finished.countDown())) {
            terminal.handle(org.jline.terminal.Terminal.Signal.INT, signal -> { client.close(); finished.countDown(); });
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            String turnId = java.util.UUID.randomUUID().toString();
            client.startTurn(turnId);
            var payload = json.createObjectNode().put("turn_id", turnId)
                    .put("batch_id", "one");
            payload.set("calls", json.valueToTree(List.of(new ToolInvocation("one", "execute_command",
                    "{\"command\":\"printf started > ready.txt; sleep 5; printf done > marker.txt\"}"))));
            var frame = json.createObjectNode().put("type", "tool.request"); frame.set("payload", payload);
            client.onMessage(frame);
            finished.await(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}

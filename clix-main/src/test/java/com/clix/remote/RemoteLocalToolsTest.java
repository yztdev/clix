package com.clix.remote;

import com.clix.hitl.ApprovalRequest;
import com.clix.hitl.ApprovalResult;
import com.clix.hitl.HitlHandler;
import com.clix.tool.ToolRegistry.ToolInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RemoteLocalToolsTest {
    @TempDir Path root;

    private static final class RejectingHandler implements HitlHandler {
        private boolean enabled = true;
        public ApprovalResult requestApproval(ApprovalRequest request) { return ApprovalResult.reject("拒绝"); }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    @Test
    void readsProjectAndRejectsUnknownAndEscapingPaths() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "local content");
        var tools = new RemoteLocalTools(new RemoteClientToolRegistry(new RejectingHandler()), root);
        tools.startTurn(java.util.UUID.randomUUID().toString());
        var results = tools.execute(List.of(
                new ToolInvocation("1", "read_file", "{\"path\":\"hello.txt\"}"),
                new ToolInvocation("2", "read_file", "{\"path\":\"../secret.txt\"}"),
                new ToolInvocation("3", "browser_connect", "{}"),
                new ToolInvocation("4", "read_file", "{\"path\":\"C:\\\\secret.txt\"}")));
        assertTrue(results.get(0).successful());
        assertTrue(results.get(0).result().contains("local content"));
        assertFalse(results.get(1).successful());
        assertFalse(results.get(2).successful());
        assertFalse(results.get(3).successful());
    }

    @Test
    void rejectedWriteDoesNotChangeFileAndDisabledConfirmationFailsClosed() throws Exception {
        var handler = new RejectingHandler();
        var tools = new RemoteLocalTools(new RemoteClientToolRegistry(handler), root);
        tools.startTurn(java.util.UUID.randomUUID().toString());
        var write = new ToolInvocation("1", "write_file", "{\"path\":\"new.txt\",\"content\":\"data\"}");
        assertFalse(tools.execute(List.of(write)).get(0).successful());
        assertFalse(Files.exists(root.resolve("new.txt")));
        handler.setEnabled(false);
        assertFalse(tools.execute(List.of(new ToolInvocation("2", "read_file", "{\"path\":\"new.txt\"}")))
                .get(0).successful());
    }

    @Test
    void repeatedRequestsAndDisconnectedSessionDoNotRunAgain() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "data");
        var tools = new RemoteLocalTools(new RemoteClientToolRegistry(new RejectingHandler()), root);
        tools.startTurn(java.util.UUID.randomUUID().toString());
        var read = new ToolInvocation("one", "read_file", "{\"path\":\"hello.txt\"}");
        assertTrue(tools.execute(List.of(read)).get(0).successful());
        assertFalse(tools.execute(List.of(read)).get(0).successful());
        tools.disconnect();
        assertFalse(tools.execute(List.of(new ToolInvocation("two", "read_file", read.argumentsJson())))
                .get(0).successful());
    }

    @Test
    void disconnectWhileWaitingForApprovalStopsThatWriteAndQueuedWork() throws Exception {
        var waiting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        HitlHandler handler = new HitlHandler() {
            public ApprovalResult requestApproval(ApprovalRequest request) {
                waiting.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return ApprovalResult.skip(); }
                return ApprovalResult.approve();
            }
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean enabled) { }
        };
        var tools = new RemoteLocalTools(new RemoteClientToolRegistry(handler), root);
        tools.startTurn(java.util.UUID.randomUUID().toString());
        var pool = Executors.newSingleThreadExecutor();
        try {
            var result = pool.submit(() -> tools.execute(List.of(
                    new ToolInvocation("1", "write_file", "{\"path\":\"one.txt\",\"content\":\"data\"}"),
                    new ToolInvocation("2", "write_file", "{\"path\":\"two.txt\",\"content\":\"data\"}"))));
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            tools.disconnect();
            release.countDown();
            assertTrue(result.get(5, TimeUnit.SECONDS).stream().noneMatch(r -> r.successful()));
            assertFalse(Files.exists(root.resolve("one.txt")));
            assertFalse(Files.exists(root.resolve("two.txt")));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void danglingSymbolicLinkCannotCreateFileOutsideProject() throws Exception {
        Path outside = root.getParent().resolve("outside-" + java.util.UUID.randomUUID() + ".txt");
        try {
            Files.createSymbolicLink(root.resolve("link.txt"), outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.abort("符号链接不可用");
        }
        var tools = new RemoteLocalTools(new RemoteClientToolRegistry(new RejectingHandler()), root);
        tools.startTurn(java.util.UUID.randomUUID().toString());
        var write = new ToolInvocation("one", "write_file", "{\"path\":\"link.txt\",\"content\":\"data\"}");
        assertFalse(tools.execute(List.of(write)).get(0).successful());
        assertFalse(Files.exists(outside));
    }

    @Test
    void autoReviewMustMatchTheExactCommandArguments() throws Exception {
        var handler = new com.clix.hitl.SwitchableHitlHandler(new RejectingHandler());
        handler.setEnabled(false);
        handler.setHighRiskConfirmationEnabled(true);
        var tools = new RemoteLocalTools(new RemoteClientToolRegistry(handler), root);
        tools.startTurn(java.util.UUID.randomUUID().toString());
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var reviews = json.createArrayNode();
        reviews.add(json.createObjectNode().put("name", "execute_command")
                .put("arguments", "{\"command\":\"echo permitted\"}").put("allowed", true).put("reason", "ok"));
        tools.setReviews(reviews);
        var changed = new ToolInvocation("one", "execute_command", "{\"command\":\"echo different\"}");
        var result = tools.execute(List.of(changed)).get(0);
        assertFalse(result.successful());
        assertTrue(result.result().contains("[AUTO]"));
    }

    @Test
    void autoReviewCanAllowOnlyTheReviewedArguments() throws Exception {
        var handler = new com.clix.hitl.SwitchableHitlHandler(new RejectingHandler());
        handler.setEnabled(false);
        handler.setHighRiskConfirmationEnabled(true);
        var tools = new RemoteLocalTools(new RemoteClientToolRegistry(handler), root);
        tools.startTurn(java.util.UUID.randomUUID().toString());
        String arguments = "{\"command\":\"\"}";
        var reviews = new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
        reviews.add(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("name", "execute_command")
                .put("arguments", arguments).put("allowed", true).put("reason", "test"));
        tools.setReviews(reviews);
        var result = tools.execute(List.of(new ToolInvocation("one", "execute_command", arguments))).get(0);
        assertFalse(result.successful());
        assertFalse(result.result().contains("[AUTO]"));
    }
}

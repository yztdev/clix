package com.clix.remote;

import com.clix.effect.EffectRecorder;
import com.clix.policy.PathGuard;
import com.clix.tool.ToolRegistry.ToolExecutionResult;
import com.clix.tool.ToolRegistry.ToolInvocation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/** Client-side boundary for the tools supported by the first remote version. */
public final class RemoteLocalTools {
    private static final Set<String> SUPPORTED = Set.of("read_file", "list_dir", "glob_files", "grep_code",
            "write_file", "edit_file", "execute_command");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RemoteClientToolRegistry registry;
    private final PathGuard paths;
    private final RemoteToolRequestTracker requests;
    private final EffectRecorder effects;
    private volatile WorkspaceGuard workspaceGuard;
    private volatile BiConsumer<String, ObjectNode> effectSink = (type, payload) -> { };

    public RemoteLocalTools(RemoteClientToolRegistry registry, Path projectRoot) {
        this(registry, projectRoot, new EffectRecorder(projectRoot, EffectRecorder.defaultAuditFile(),
                (type, payload) -> { }));
    }

    /** Test seam: an isolated append-only audit file and a deterministic command observer. */
    public RemoteLocalTools(RemoteClientToolRegistry registry, Path projectRoot, EffectRecorder recorder) {
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
        this.requests = registry.requests();
        this.paths = new PathGuard(projectRoot.toAbsolutePath().normalize().toString());
        registry.setProjectPath(paths.getRootPath().toString());
        registry.setProjectRoot(paths.getRootPath().toString());
        this.effects = java.util.Objects.requireNonNull(recorder, "recorder");
        // Prefer the supervisor's real termination kind over a guessed "timeout"/"completed".
        recorder.setTerminationSupplier(() -> {
            for (var termination : registry.runner().lastResults()) {
                String wire = termination.terminationWire();
                if (wire != null && !wire.isBlank() && !"unknown".equals(wire)) return wire;
            }
            return "";
        });
    }

    /**
     * Installs the WebSocket sink. Unconfirmed effect_started records from a previous crash
     * are re-reported here, before any new turn starts, without replaying a tool.
     */
    public void connectEffectSink(BiConsumer<String, ObjectNode> sink) {
        effectSink = sink == null ? (type, payload) -> { } : sink;
        effects.setSink(effectSink);
        effects.replayUnfinished();
    }

    public EffectRecorder effectRecorder() { return effects; }

    public Set<String> unfinishedOperations() { return effects.unfinishedOperations(); }

    public List<ToolExecutionResult> execute(List<ToolInvocation> calls) {
        if (calls == null || calls.isEmpty()) return List.of();
        List<ToolExecutionResult> results = new ArrayList<>(java.util.Collections.nCopies(calls.size(), null));
        List<ToolInvocation> allowed = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            ToolInvocation call = calls.get(i);
            String error = validate(call);
            if (error == null) {
                WorkspaceGuard guard = workspaceGuard;
                if (guard != null && guard.isRestricted() && !guard.allows(call.name())) {
                    error = "工作区处于 " + guard.state() + "：取消状态未确认，暂时拒绝写入和命令，只允许只读核验";
                }
            }
            if (error == null) {
                var request = new RemoteToolRequestTracker.Request(call.id(), call.name(), call.argumentsJson());
                if (requests.receive(request) != RemoteToolRequestTracker.Receipt.NEW) {
                    error = "工具请求已接收或连接已关闭，不能重复执行";
                }
            }
            if (error != null) {
                results.set(i, new ToolExecutionResult(call.id(), call.name(), call.argumentsJson(),
                        error, 0, false, List.of(), false, List.of()));
            } else {
                allowed.add(call);
                indexes.add(i);
            }
        }
        if (!allowed.isEmpty()) {
            // Phase three: the operation lock is taken here and released only after the
            // terminal effect record has been flushed, covering the tool execution itself.
            List<EffectRecorder.Capture> captures = effects.before(allowed);
            List<ToolExecutionResult> executed = null;
            try {
                executed = registry.executeTools(allowed);
                for (int i = 0; i < indexes.size(); i++) {
                    ToolExecutionResult result = executed.get(i);
                    if (result.timedOut()) {
                        requests.markOutcomeUnknown(allowed.get(i).id());
                    } else {
                        requests.complete(allowed.get(i).id(), result.result());
                    }
                    results.set(indexes.get(i), result);
                }
            } catch (RuntimeException e) {
                requests.disconnect();
                throw e;
            } finally {
                effects.after(captures, executed);
                registry.endToolCall();
            }
        }
        return List.copyOf(results);
    }

    /** Prevents new work after the connection is lost; in-flight work remains uncertain. */
    public void disconnect() {
        requests.disconnect();
    }

    /** Stops in-flight OS commands without relying on executor interruption propagation. */
    public void cancelTurn(String turnId) { registry.cancelRemoteTurn(turnId); }

    /** Wakes a blocked HITL approval so the local tool worker can serve the next turn. */
    public void cancelPendingApproval() { registry.cancelPendingApproval(); }

    /** Installs the client-side workspace safety gate used after an unconfirmed cancellation. */
    public void setWorkspaceGuard(WorkspaceGuard guard) { this.workspaceGuard = guard; }

    public void cancelActiveCommands() { registry.cancelActiveCommands(); }

    public void startTurn(String turnId) {
        registry.startRemoteTurn(turnId);
        effects.beginTurn(turnId);
    }

    /** Approval results are scoped to one batch and bound to the exact tool and arguments. */
    void setReviews(JsonNode reviews) {
        var decisions = new java.util.HashMap<String, com.clix.hitl.AutoApprovalReviewer.Review>();
        if (reviews != null && reviews.isArray()) {
            for (JsonNode review : reviews) {
                if (!review.path("name").isTextual() || !review.path("arguments").isTextual()
                        || !review.path("allowed").isBoolean()) continue;
                String key = review.path("name").asText() + "\0" + review.path("arguments").asText();
                String reason = review.path("reason").asText("");
                if (reason.length() > 200) reason = reason.substring(0, 200);
                var decision = new com.clix.hitl.AutoApprovalReviewer.Review(review.path("allowed").booleanValue(), reason);
                if (decisions.containsKey(key)) decisions.put(key, com.clix.hitl.AutoApprovalReviewer.Review.ask("审查结果重复"));
                else decisions.put(key, decision);
            }
        }
        registry.setAutoApprovalReviewer((name, args) -> decisions.getOrDefault(name + "\0" + args,
                com.clix.hitl.AutoApprovalReviewer.Review.ask("自动审查不可用")));
    }

    private String validate(ToolInvocation call) {
        if (call == null || call.id() == null || call.id().isBlank()) {
            throw new IllegalArgumentException("工具请求缺少 ID");
        }
        if (call.name() == null || !SUPPORTED.contains(call.name())) return "远程模式不支持此工具";
        if (!registry.getHitlHandler().isConfirmationActive()) return "远程工具的确认机制未启用";
        if (!paths.getRootPath().equals(new PathGuard(registry.getProjectPath()).getRootPath())) {
            return "项目根目录已改变";
        }
        try {
            JsonNode args = JSON.readTree(call.argumentsJson());
            if (args == null || !args.isObject()) return "工具参数必须是 JSON 对象";
            JsonNode path = args.get("path");
            if (path != null && path.isTextual()) {
                String value = path.textValue();
                // The protocol uses local relative paths, regardless of the server OS.
                if (Path.of(value).isAbsolute() || value.startsWith("\\") || value.matches("^[A-Za-z]:.*")) {
                    return "远程工具路径必须是项目内相对路径";
                }
                // PathGuard handles existing links. Reject dangling links as well: a write
                // could otherwise follow one to a non-existent file outside the project.
                Path current = paths.getRootPath();
                for (Path segment : Path.of(value)) {
                    current = current.resolve(segment).normalize();
                    if (!current.startsWith(paths.getRootPath())) return "工具路径越出项目根目录";
                    if (Files.isSymbolicLink(current)) {
                        Path target = current.toRealPath();
                        if (!target.startsWith(paths.getRootPath())) return "工具路径越出项目根目录";
                        current = target;
                    }
                }
                paths.resolveSafe(value);
            }
        } catch (Exception e) {
            return "工具参数或项目路径无效";
        }
        return null;
    }
}

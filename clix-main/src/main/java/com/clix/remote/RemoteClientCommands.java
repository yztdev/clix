package com.clix.remote;

import com.clix.effect.EffectEvent;
import com.clix.effect.InterruptionContextBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Local (client-side) command parsing and phase-one session state.
 *
 * <p>Design doc "组件改造 / RemoteClientCommand": /continue, /status, /retry and the
 * quarantine release are parsed locally instead of being handed to the model.
 *
 * <p>The interruption note here is a minimal, deterministic summary built only from the
 * turn.cancelled payload. Phase three replaces it with InterruptionContextBuilder reading
 * structured ConversationLedger events. It is injected at most once, never written into
 * agentHistory, and never replays an old tool call.
 */
final class RemoteClientCommands {
    enum Kind {
        NONE, STATUS, CONTINUE, RETRY, QUARANTINE, QUARANTINE_UNLOCK,
        MODE, HISTORY, EXIT, UNKNOWN
    }

    private static final int MAX_GOAL_CHARS = 500;

    private final WorkspaceGuard guard;
    private volatile String lastGoal;
    private volatile String lastTurnId;
    private volatile String lastOutcome;
    private volatile String lastPhase;
    private volatile JsonNode lastCancellation;
    private volatile ObjectNode lastCancellationRecord;
    private volatile boolean interruptionPending;

    RemoteClientCommands(WorkspaceGuard guard) {
        this.guard = guard;
    }

    static Kind parse(String input) {
        if (input == null || input.isBlank()) return Kind.NONE;
        String line = input.trim();
        if (line.equals("/exit") || line.equals("/quit")) return Kind.EXIT;
        if (line.equals("/status")) return Kind.STATUS;
        if (line.equals("/continue")) return Kind.CONTINUE;
        if (line.equals("/retry")) return Kind.RETRY;
        if (line.equals("/quarantine") || line.equals("/quarantine status")) return Kind.QUARANTINE;
        if (line.equals("/quarantine unlock --force")) return Kind.QUARANTINE_UNLOCK;
        if (line.equals("/mode auto") || line.equals("/mode ask")) return Kind.MODE;
        if (line.equals("/history") || line.startsWith("/history ")) return Kind.HISTORY;
        if (line.startsWith("/")) return Kind.UNKNOWN;
        return Kind.NONE;
    }

    /** /continue and /retry must not overwrite the original user goal. */
    void onTurnStarted(String turnId) {
        lastTurnId = turnId;
    }

    void onTurnStarted(String turnId, String goal) {
        onTurnStarted(turnId);
        lastGoal = truncate(goal);
    }

    void onTurnFinished(String outcome, String phase) {
        lastOutcome = outcome;
        lastPhase = phase;
    }

    void onCancelled(JsonNode payload) { onCancelled(payload, Set.of()); }

    /**
     * Phase three: the server sends ledger-grade structured facts. Local unfinished
     * effect_started operations are merged in so a crash is never described as "no effect".
     */
    void onCancelled(JsonNode payload, Set<String> unfinishedOperations) {
        ObjectNode record = payload != null && payload.isObject()
                ? (ObjectNode) payload.deepCopy() : EffectEvent.JSON.createObjectNode();
        if (record.path("turn_id").asText("").isBlank() && lastTurnId != null) {
            record.put("turn_id", lastTurnId);
        }
        if (record.path("user_message").asText("").isBlank() && lastGoal != null) {
            record.put("user_message", lastGoal);
        }
        ArrayNode unfinished = EffectEvent.JSON.createArrayNode();
        if (unfinishedOperations != null) for (String id : unfinishedOperations) unfinished.add(id);
        record.set("unfinished_operations", unfinished);
        lastCancellation = record;
        lastCancellationRecord = record;
        interruptionPending = true;
    }

    boolean hasPendingInterruption() {
        return interruptionPending;
    }

    /** The raw user goal, never the generated interruption note (design doc V1.2 C). */
    String originalGoal() { return lastGoal; }

    /** Returns the note once; a second call returns null so it is never injected twice. */
    String consumeInterruptionNote() {
        if (!interruptionPending) return null;
        interruptionPending = false;
        return interruptionNote();
    }

    /** Deterministic, model-free context built only from structured ledger fields. */
    String interruptionNote() {
        ObjectNode record = lastCancellationRecord;
        if (record == null) return "";
        List<ObjectNode> effects = new ArrayList<>();
        JsonNode array = record.path("effects");
        if (array.isArray()) {
            for (JsonNode effect : array) {
                if (effect.isObject()) effects.add((ObjectNode) effect.deepCopy());
            }
        }
        int conflicts = record.path("conflict_count").asInt(0);
        int versions = record.path("version_count").asInt(0);
        return InterruptionContextBuilder.build(record, effects, conflicts, versions, false);
    }

    /** /continue creates a new turn and continues reasoning; it never replays an old tool call. */
    String continueContent() {
        String note = consumeInterruptionNote();
        if (note == null) return "请继续上一次对话，并在行动前先核验当前工作区状态。";
        return note + "\n请基于以上事实继续上一轮目标；先核验再行动，不要重放旧工具调用。";
    }

    /** /retry re-submits only the original user goal with a brand-new turn id. */
    String retryContent() {
        String goal = lastGoal;
        if (goal == null || goal.isBlank()) return null;
        String note = consumeInterruptionNote();
        return (note == null ? "" : note + "\n")
                + "用户原始目标（重新提交为新 Turn，不复用旧 tool_call_id / operation_id / 审批结果 / 工具参数）：\n"
                + goal;
    }

    String status(CurrentTurnController controller, RemoteClientConnection connection,
                  WorkspaceGuard workspace, int staleApprovals, String containmentLine) {
        StringBuilder out = new StringBuilder("── 远程会话状态 ──\n");
        out.append("连接：").append(connection == null ? "CLOSED" : connection.connectionState())
                .append("；当前 Turn 阶段：").append(controller == null ? "IDLE" : controller.phase()).append('\n');
        out.append("上一 Turn：").append(lastTurnId == null ? "（无）" : lastTurnId)
                .append("；结果：").append(lastOutcome == null ? "（无）" : lastOutcome)
                .append("；阶段：").append(lastPhase == null ? "（无）" : lastPhase).append('\n');
        out.append("迟到事件丢弃数：").append(connection == null ? 0 : connection.lateEventCount())
                .append("；待取消审批残留：").append(staleApprovals).append('\n');
        out.append("活动监管域：").append(containmentLine == null ? "不可用" : containmentLine).append('\n');
        ObjectNode record = lastCancellationRecord;
        if (record != null) {
            out.append("副作用审计：").append(text(record, "effect_audit", "unavailable"))
                    .append("（仅表示观察范围完整度，不代表工具成功或工作区一致）\n");
            out.append("冲突记录：").append(record.path("conflict_count").asInt(0))
                    .append(" 个，保留版本：").append(record.path("version_count").asInt(0))
                    .append(" 个，需重新核验：")
                    .append(record.path("needs_verification").asBoolean(false) ? "是" : "否").append('\n');
            List<String> observed = new ArrayList<>();
            JsonNode effects = record.path("effects");
            if (effects.isArray()) for (JsonNode effect : effects) {
                effect.path("observed_paths").forEach(node -> observed.add(node.asText("")));
                effect.path("files").forEach(node -> observed.add(
                        node.path("operation").asText("modify") + " " + node.path("path").asText("")));
            }
            out.append("观察到的变化（").append(observed.size()).append(" 条，仅声明范围，非全部副作用）：")
                    .append(observed.isEmpty() ? "无" : String.join("；", observed.subList(0, Math.min(observed.size(), 20))))
                    .append('\n');
            out.append("可能已执行但无终态记录：").append(record.path("unfinished_operations").size()).append(" 个\n");
            out.append("上次取消：termination=").append(text(record, "termination", "unknown"))
                    .append("，transport=").append(text(record, "transport_cancel_status", "unknown"))
                    .append("，provider_ack=").append(text(record, "provider_cancel_ack", "unsupported"))
                    .append("，effect_audit=").append(text(record, "effect_audit", "unavailable")).append('\n');
        } else {
            out.append("观察到的变化：无已记录的副作用事件\n");
        }
        out.append("工作区安全状态：").append(workspace.statusLine()).append('\n');
        out.append("待注入的中断说明：").append(interruptionPending ? "是（下次输入时注入一次）" : "否").append('\n');
        out.append("取消截止时间剩余：").append(controller == null ? 0 : controller.cancelDeadlineRemainingMillis())
                .append(" ms（第一次 Ctrl+C 后不可延长）");
        return out.toString();
    }

    String quarantineStatus() {
        return "工作区安全状态：" + guard.statusLine() + "\n"
                + "隔离版本：" + guard.quarantineVersion()
                + "；进入时间：" + (guard.quarantinedAt() == null ? "（无）" : guard.quarantinedAt()) + "\n"
                + "只读核验工具仍可用；写入和 execute_command 暂时拒绝。\n"
                + "如需强制解除：/quarantine unlock --force（两步确认，仅本地用户可完成）";
    }

    String beginForcedRelease(String project, String localUser) {
        return guard.beginForcedRelease(project, localUser);
    }

    boolean confirmForcedRelease(String input) {
        return guard.confirmForcedRelease(input);
    }

    private static String text(JsonNode payload, String field, String fallback) {
        String value = payload.path(field).asText("");
        return value.isBlank() ? fallback : value;
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() > MAX_GOAL_CHARS ? value.substring(0, MAX_GOAL_CHARS) + "…" : value;
    }
}

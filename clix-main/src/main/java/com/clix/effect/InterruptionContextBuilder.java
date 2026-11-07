package com.clix.effect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic, model-free interruption context (design doc "写入下一轮上下文" + V1.2 C/F).
 *
 * <p>Only trusted structured ledger fields are read. No stdout, web body, tool result text or
 * instruction from tool output is copied. The result is a request-level system/context
 * segment; it is never an assistant message and never enters agentHistory.
 */
public final class InterruptionContextBuilder {
    public static final String OPEN = "[CLIX_INTERRUPTED_TURN_CONTEXT]";
    public static final String CLOSE = "[/CLIX_INTERRUPTED_TURN_CONTEXT]";
    public static final String HARD_CONSTRAINT =
            "上一轮有副作用工具被中断或可能已执行。不要假设它们未发生，也不要自动重放。"
                    + "先重新读取相关文件和 git diff，再决定是否需要重新执行。";
    public static final int MAX_PATHS = 20;
    public static final int MAX_PATH_CHARS = 160;
    public static final int MAX_CHARS = 4_000;

    private InterruptionContextBuilder() { }

    public static String build(ObjectNode cancelled, List<ObjectNode> effects,
                               int conflicts, int versionCount, boolean duplicateHint) {
        StringBuilder out = new StringBuilder(OPEN).append('\n');
        if (duplicateHint) {
            out.append("提示：本说明可能是崩溃恢复后的重复注入；请以最新工作区状态为准，忽略重复的中断说明。\n");
        }
        out.append("上一轮任务被用户取消。\n");
        out.append("原始目标：").append(sanitize(text(cancelled, "user_message", "（未记录）"))).append('\n');
        out.append("上一轮 Turn：").append(sanitize(text(cancelled, "turn_id", "（未记录）")))
                .append("；阶段：").append(sanitize(text(cancelled, "stage", "tool_execution"))).append('\n');

        List<String> completed = new ArrayList<>();
        JsonNode tools = cancelled == null ? null : cancelled.path("completed_tools");
        if (tools != null && tools.isArray()) {
            for (JsonNode tool : tools) {
                String name = tool.path("name").asText("tool");
                StringBuilder line = new StringBuilder(name);
                JsonNode paths = tool.path("affected_paths");
                if (paths.isArray() && !paths.isEmpty()) {
                    line.append(" → ");
                    for (JsonNode path : paths) line.append(clip(path.asText(""))).append(' ');
                }
                completed.add(line.toString().trim());
            }
        }
        out.append("已经完成：").append(completed.isEmpty() ? "（无可确认的已完成操作）"
                : String.join("；", completed.subList(0, Math.min(completed.size(), MAX_PATHS)))).append('\n');

        JsonNode interrupted = cancelled == null ? null : cancelled.path("interrupted_tool");
        if (interrupted != null && interrupted.isObject() && !interrupted.isEmpty()) {
            out.append("被中断：").append(sanitize(interrupted.path("name").asText("unknown")))
                    .append(": ").append(sanitize(interrupted.path("command_summary").asText("（无摘要）")))
                    .append("（effect_audit=").append(sanitize(interrupted.path("effect_audit").asText("unavailable")))
                    .append(")\n");
        } else {
            out.append("被中断：（未记录到明确的中断工具；仍可能存在已启动但未返回的副作用操作）\n");
        }

        out.append("终止结果：").append(sanitize(text(cancelled, "termination", "unknown")))
                .append("；本地 transport 取消：")
                .append(sanitize(text(cancelled, "transport_cancel_status", "unknown")))
                .append("；供应商远端确认：")
                .append(sanitize(text(cancelled, "provider_cancel_ack", "unsupported"))).append('\n');

        Set<String> observed = new LinkedHashSet<>();
        boolean unavailable = false;
        if (effects != null) {
            for (ObjectNode effect : effects) {
                if ("unavailable".equals(effect.path("effect_audit").asText(""))) unavailable = true;
                JsonNode paths = effect.path("observed_paths");
                if (paths.isArray()) for (JsonNode path : paths) observed.add(clip(path.asText("")));
                JsonNode files = effect.path("files");
                if (files.isArray()) for (JsonNode file : files) {
                    observed.add(clip(file.path("operation").asText("modify") + " "
                            + file.path("path").asText("")));
                }
            }
        }
        out.append("观察到的变化：\n");
        if (observed.isEmpty()) out.append("- （无已记录的结构化变化）\n");
        else {
            int i = 0;
            for (String path : observed) {
                if (i++ >= MAX_PATHS) { out.append("- …（其余 ").append(observed.size() - MAX_PATHS)
                        .append(" 条已省略，详见 /status 与本地审计日志）\n"); break; }
                out.append("- ").append(path).append('\n');
            }
        }
        if (unavailable) {
            out.append("未观察文件变化；请手工检查。\n");
        }
        JsonNode unfinished = cancelled == null ? null : cancelled.path("unfinished_operations");
        if (unfinished != null && unfinished.isArray() && !unfinished.isEmpty()) {
            out.append("可能已执行但未确认的操作：").append(unfinished.size())
                    .append(" 个（本地审计日志保留 effect_started 记录；未观察文件变化；请手工检查）。\n");
        }
        if (conflicts > 0) {
            out.append("注意：检测到 ").append(conflicts).append(" 个副作用记录冲突，共 ")
                    .append(versionCount).append(" 个版本；这些记录互相不一致，必须重新核验当前文件，不能假定任一版本是磁盘现状。\n");
        }
        out.append("未知风险：Shell 命令可能还有未被项目观察器覆盖的副作用；监管域、外部网络/数据库操作与项目外路径无法完整枚举。\n");
        out.append("不要假定被中断的工具未执行或已成功；任何重试都必须重新审批。\n");
        out.append("继续前重新读取相关文件和 git diff。\n");
        out.append(HARD_CONSTRAINT).append('\n');
        out.append(CLOSE);
        String result = out.toString();
        if (result.length() <= MAX_CHARS) return result;
        // The hard constraint must survive truncation (V1.2 C).
        return result.substring(0, MAX_CHARS) + "\n" + HARD_CONSTRAINT + "\n" + CLOSE;
    }

    private static String text(ObjectNode node, String field, String fallback) {
        if (node == null) return fallback;
        String value = node.path(field).asText("");
        return value.isBlank() ? fallback : value;
    }

    private static String clip(String value) {
        String safe = sanitize(value);
        return safe.length() > MAX_PATH_CHARS ? safe.substring(0, MAX_PATH_CHARS) + "…" : safe;
    }

    /** Strip control characters and anything that could look like an injected instruction. */
    private static String sanitize(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cntrl}]+", " ").trim();
        clean = clean.replace("[CLIX_INTERRUPTED_TURN_CONTEXT]", "")
                .replace("[/CLIX_INTERRUPTED_TURN_CONTEXT]", "");
        return clean;
    }
}

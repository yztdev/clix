package com.clix.effect;

import com.clix.tool.ToolRegistry.ToolExecutionResult;
import com.clix.tool.ToolRegistry.ToolInvocation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

/**
 * Client-side orchestration of one operation's complete audit transaction (design doc
 * "原生文件工具" / "execute_command" / "本地待发送记录" and V1.2 A/B).
 *
 * <p>Held for the whole operation: one operation lock covers path normalization, the
 * before observation, the actual tool execution, the after observation and the terminal
 * JSONL append + flush. Every operation gets a fresh, never-reused operation id, and the
 * {@code effect_started} record is force-flushed before the tool is allowed to start.
 * A crash before the terminal record therefore leaves a "possibly executed" fact that
 * {@link #replayUnfinished()} can report later without replaying the tool.
 */
public final class EffectRecorder {
    public static final Set<String> SIDE_EFFECT_TOOLS =
            Set.of("write_file", "edit_file", "execute_command");
    public static final int MAX_DECLARED_PATHS = 20;

    /** Mutable per-operation state shared between before() and after(). */
    public static final class Capture {
        final String turnId;
        final String toolCallId;
        final String operationId;
        final String tool;
        final String argumentsJson;
        final ReentrantLock lock;
        boolean startedWritten;
        boolean startedWriteFailed;
        boolean pathRejected;
        String path = "";
        Path resolved;
        FileEffectProbe.State before = FileEffectProbe.State.failed();
        String command = "";
        List<String> outputPaths = List.of();
        CommandEffectProbe.Baseline baseline;

        Capture(String turnId, String toolCallId, String operationId, String tool,
                String argumentsJson, ReentrantLock lock) {
            this.turnId = turnId;
            this.toolCallId = toolCallId;
            this.operationId = operationId;
            this.tool = tool;
            this.argumentsJson = argumentsJson;
            this.lock = lock;
        }
    }

    private final Path root;
    private final Path auditFile;
    private final FileEffectProbe files;
    private final CommandEffectProbe commands;
    private volatile BiConsumer<String, ObjectNode> sink;
    private volatile java.util.function.Supplier<String> terminationSupplier = () -> "";
    private final AtomicReference<String> currentTurn = new AtomicReference<>();
    private final Set<String> usedOperations = ConcurrentHashMap.newKeySet();
    private volatile EffectAuditLog audit;
    private volatile boolean auditUnavailable;
    private boolean auditInitialized;

    public EffectRecorder(Path projectRoot, Path auditFile, BiConsumer<String, ObjectNode> sink) {
        this(projectRoot, auditFile, sink, CommandEffectProbe.systemRunner());
    }

    public EffectRecorder(Path projectRoot, Path auditFile, BiConsumer<String, ObjectNode> sink,
                          CommandEffectProbe.CommandRunner runner) {
        this.root = java.util.Objects.requireNonNull(projectRoot, "projectRoot")
                .toAbsolutePath().normalize();
        this.auditFile = java.util.Objects.requireNonNull(auditFile, "auditFile");
        this.sink = sink == null ? (type, payload) -> { } : sink;
        this.files = new FileEffectProbe(root);
        this.commands = new CommandEffectProbe(root, runner);
    }

    /** Default audit location stays outside the workspace so it never becomes a tool side effect. */
    public static Path defaultAuditFile() {
        return Path.of(System.getProperty("user.home", "."), ".clix", "remote-effects", "audit.jsonl");
    }

    private synchronized EffectAuditLog log() {
        if (!auditInitialized) {
            auditInitialized = true;
            try {
                audit = new EffectAuditLog(auditFile);
            } catch (IOException | RuntimeException e) {
                auditUnavailable = true;
            }
        }
        return audit;
    }

    public Path auditFile() { return auditFile; }
    public boolean auditUnavailable() { log(); return auditUnavailable; }

    /** Attached once the WebSocket exists; the sink is never required for local auditing. */
    public void setSink(BiConsumer<String, ObjectNode> sink) {
        this.sink = sink == null ? (type, payload) -> { } : sink;
    }

    /** Supplies the supervisor's real INT/TERM/KILL outcome for execute_command. */
    public void setTerminationSupplier(java.util.function.Supplier<String> supplier) {
        this.terminationSupplier = supplier == null ? () -> "" : supplier;
    }
    public String currentTurn() { return currentTurn.get(); }
    public void beginTurn(String turnId) { currentTurn.set(turnId); }

    /** True when a terminal record could not be flushed. Test seam lives on EffectAuditLog. */
    public boolean flushEnabled() { EffectAuditLog l = log(); return l == null || l.flushEnabled(); }

    public void setFlushEnabled(boolean enabled) {
        EffectAuditLog l = log();
        if (l != null) l.setFlushEnabled(enabled);
    }

    public Set<String> unfinishedOperations() {
        EffectAuditLog l = log();
        if (l == null) return Set.of();
        try { return l.unfinishedOperations(); }
        catch (IOException e) { return Set.of(); }
    }

    /**
     * Must be called while the caller still holds each returned capture's lock, which is
     * released by {@link #after(List, List)}. Non-side-effecting tools get a null slot.
     */
    public List<Capture> before(List<ToolInvocation> calls) {
        List<Capture> captures = new ArrayList<>(Collections.nCopies(calls.size(), null));
        String turnId = currentTurn.get();
        for (int i = 0; i < calls.size(); i++) {
            ToolInvocation call = calls.get(i);
            if (call == null || !SIDE_EFFECT_TOOLS.contains(call.name())) continue;
            String operationId = UUID.randomUUID().toString();
            while (!usedOperations.add(operationId)) operationId = UUID.randomUUID().toString();
            ReentrantLock lock = operationLock(operationId);
            lock.lock();
            Capture capture = new Capture(turnId == null ? "" : turnId, call.id(), operationId,
                    call.name(), call.argumentsJson(), lock);
            captures.set(i, capture);
            observeBefore(capture, call.argumentsJson());
            writeStarted(capture);
        }
        return Collections.unmodifiableList(captures);
    }

    private ReentrantLock operationLock(String operationId) {
        EffectAuditLog l = log();
        return l == null ? new ReentrantLock() : l.operationLock(operationId);
    }

    private void observeBefore(Capture capture, String argumentsJson) {
        JsonNode args = parse(argumentsJson);
        if ("execute_command".equals(capture.tool)) {
            capture.command = args.path("command").asText("");
            capture.outputPaths = declaredPaths(args);
            capture.baseline = commands.before(capture.command, capture.outputPaths);
            return;
        }
        capture.path = args.path("path").asText("");
        try {
            capture.resolved = files.resolve(capture.path);
            capture.before = files.capture(capture.resolved);
        } catch (FileEffectProbe.PathRejected e) {
            capture.pathRejected = true;
            capture.before = FileEffectProbe.State.failed();
        }
    }

    private static List<String> declaredPaths(JsonNode args) {
        JsonNode node = args.path("output_paths");
        if (!node.isArray()) return List.of();
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String value = item.asText("");
            if (!value.isBlank()) unique.add(value);
            if (unique.size() >= MAX_DECLARED_PATHS) break;
        }
        return List.copyOf(unique);
    }

    private void writeStarted(Capture capture) {
        ObjectNode started = EffectReport.payload(capture.turnId, capture.toolCallId,
                capture.operationId, capture.tool, "cancelled_unknown");
        started.put("audit_scope", "pending");
        started.put("path", capture.path);
        started.put("command_summary", CommandEffectProbe.summary(capture.command));
        EffectReport.putStrings(started, "declared_paths", capture.outputPaths);
        long sequence = nextSequence();
        EffectReport.finalize(started, sequence);
        started.put("event_type", EffectEvent.EFFECT_STARTED);
        EffectEvent event = EffectEvent.createWithHash(EffectEvent.EFFECT_STARTED,
                capture.turnId, capture.toolCallId, capture.operationId, sequence,
                body(started), started.path("content_hash").asText(""));
        if (!append(event)) capture.startedWriteFailed = true;
        capture.startedWritten = true;
    }

    /**
     * Terminalizes every capture. {@code results} may be null (crash, cancellation or a
     * thrown batch), in which case the outcome is honestly {@code cancelled_unknown}.
     */
    public void after(List<Capture> captures, List<ToolExecutionResult> results) {
        for (int i = 0; i < captures.size(); i++) {
            Capture capture = captures.get(i);
            if (capture == null) continue;
            try {
                ToolExecutionResult result = results == null || i >= results.size() ? null : results.get(i);
                terminalize(capture, result);
            } catch (RuntimeException e) {
                // An observer failure must never break the tool result path. The started
                // record remains and will be replayed as "possibly executed".
            } finally {
                capture.lock.unlock();
            }
        }
    }

    private void terminalize(Capture capture, ToolExecutionResult result) {
        String outcome = result == null || result.timedOut() ? "cancelled_unknown"
                : (result.successful() ? "completed" : "failed");
        ObjectNode wire = EffectReport.payload(capture.turnId, capture.toolCallId,
                capture.operationId, capture.tool, outcome);
        String grade;
        String scope;
        List<String> limitations = new ArrayList<>();
        List<String> observed = new ArrayList<>();
        boolean external = false;

        if ("execute_command".equals(capture.tool)) {
            CommandEffectProbe.Observation observation = capture.baseline == null
                    ? new CommandEffectProbe.Observation(List.of(), "unavailable", "none",
                    List.of("baseline_unavailable"), 0, false)
                    : commands.after(capture.baseline, capture.command);
            grade = observation.effectAudit();
            scope = observation.auditScope();
            limitations.addAll(observation.auditLimitations());
            observed.addAll(observation.observedPaths());
            external = observation.externalSideEffectsPossible();
            wire.put("command_digest", CommandEffectProbe.digest(capture.command));
            wire.put("command_summary", CommandEffectProbe.summary(capture.command));
            String observedTermination = terminationSupplier.get();
            wire.put("termination", observedTermination == null || observedTermination.isBlank()
                    ? (result == null ? "cancelled_unknown" : (result.timedOut() ? "timeout" : "completed"))
                    : observedTermination);
        } else if (capture.pathRejected || capture.resolved == null) {
            grade = "unavailable";
            scope = "none";
            limitations.add("path_normalization_failed");
        } else {
            FileEffectProbe.State after = files.capture(capture.resolved);
            boolean beforeOk = capture.before != null && capture.before.captured();
            boolean afterOk = after.captured();
            grade = beforeOk && afterOk ? "verified" : "partial";
            scope = "single_target_path";
            if (!beforeOk) limitations.add("before_state_unavailable");
            if (!afterOk) limitations.add("after_state_unavailable");
            limitations.add("lock_only_serializes_this_tool");
            long written = beforeOk && afterOk ? after.size() - capture.before.size() : 0L;
            ArrayNode entries = EffectEvent.JSON.createArrayNode();
            entries.add(EffectReport.fileEntry(capture.path,
                    "write_file".equals(capture.tool) ? "create" : "modify",
                    status(capture.before), capture.before == null ? "" : capture.before.sha256(),
                    status(after), after.sha256(), written, outcome));
            wire.set("files", entries);
            if (!java.util.Objects.equals(capture.before.sha256(), after.sha256())) {
                observed.add(capture.path);
            }
        }

        boolean flushFailed = capture.startedWriteFailed || !flushEnabled() || auditUnavailable();
        if (flushFailed) {
            grade = downgrade(grade);
            limitations.add("audit_flush_failed");
        }
        fill(wire, grade, scope, limitations, observed, external);
        long sequence = nextSequence();
        EffectReport.finalize(wire, sequence);
        if (!append(EffectReport.terminalEvent(wire, sequence)) && !flushFailed) {
            wire.put("effect_audit", downgrade(wire.path("effect_audit").asText("unavailable")));
            limitations.add("audit_flush_failed");
            fill(wire, wire.path("effect_audit").asText("unavailable"), scope, limitations, observed, external);
            long retrySequence = nextSequence();
            EffectReport.finalize(wire, retrySequence);
            append(EffectReport.terminalEvent(wire, retrySequence));
        }
        publish(wire);
    }

    private static void fill(ObjectNode wire, String grade, String scope,
                             List<String> limitations, List<String> observed, boolean external) {
        wire.put("effect_audit", grade);
        wire.put("audit_scope", scope);
        EffectReport.putStrings(wire, "audit_limitations", limitations);
        EffectReport.putStrings(wire, "observed_paths", observed);
        wire.put("external_side_effects_possible", external);
        if (!wire.has("files")) wire.set("files", EffectEvent.JSON.createArrayNode());
    }

    private static String downgrade(String grade) {
        return "verified".equals(grade) ? "partial" : "unavailable";
    }

    private static String status(FileEffectProbe.State state) {
        if (state == null) return "failed";
        return switch (state.status()) {
            case ABSENT -> "absent";
            case PRESENT -> "present";
            case FAILED -> "failed";
        };
    }

    /**
     * Crash recovery: report effect_started rows that never got a terminal record as an
     * unavailable observation. This never starts a tool and never replays an old request.
     */
    public int replayUnfinished() {
        EffectAuditLog l = log();
        if (l == null) return 0;
        List<EffectEvent> events;
        try { events = l.readWithinRetention(); }
        catch (IOException e) { return 0; }
        Set<String> unfinished;
        try { unfinished = l.unfinishedOperations(); }
        catch (IOException e) { return 0; }
        if (unfinished.isEmpty()) return 0;
        Map<String, EffectEvent> first = new java.util.LinkedHashMap<>();
        for (EffectEvent event : events) {
            if (EffectEvent.EFFECT_STARTED.equals(event.eventType())
                    && unfinished.contains(event.operationId())) {
                first.putIfAbsent(event.operationId(), event);
            }
        }
        int replayed = 0;
        for (EffectEvent started : first.values()) {
            Map<String, Object> body = started.payload();
            ObjectNode wire = EffectReport.payload(str(body.get("turn_id")), str(body.get("tool_call_id")),
                    started.operationId(), str(body.get("tool")), "cancelled_unknown");
            wire.put("effect_audit", "unavailable");
            wire.put("audit_scope", "none");
            EffectReport.putStrings(wire, "audit_limitations",
                    List.of("effect_started_without_terminal_record", "possibly_executed"));
            EffectReport.putStrings(wire, "observed_paths", List.of());
            wire.put("external_side_effects_possible", true);
            long sequence = nextSequence();
            EffectReport.finalize(wire, sequence);
            append(EffectReport.terminalEvent(wire, sequence));
            publish(wire);
            replayed++;
        }
        return replayed;
    }

    private void publish(ObjectNode wire) {
        try { sink.accept("tool.effect", wire); }
        catch (RuntimeException ignored) {
            // A lost WebSocket must not turn an observed effect into a claimed success.
        }
    }

    private boolean append(EffectEvent event) {
        EffectAuditLog l = log();
        if (l == null) return false;
        try { l.append(event); return true; }
        catch (IOException | RuntimeException e) { return false; }
    }

    private long nextSequence() {
        EffectAuditLog l = log();
        return l == null ? 0L : l.nextSequence();
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) return EffectEvent.JSON.createObjectNode();
        try {
            JsonNode node = EffectEvent.JSON.readTree(json);
            return node == null || !node.isObject() ? EffectEvent.JSON.createObjectNode() : node;
        } catch (IOException e) {
            return EffectEvent.JSON.createObjectNode();
        }
    }

    private static Map<String, Object> body(ObjectNode node) {
        return EffectEvent.JSON.convertValue(node, new TypeReference<Map<String, Object>>() { });
    }

    private static String str(Object value) { return value == null ? "" : value.toString(); }
}

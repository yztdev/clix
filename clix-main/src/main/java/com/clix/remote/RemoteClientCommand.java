package com.clix.remote;

import com.clix.hitl.RendererHitlHandler;
import com.clix.hitl.SwitchableHitlHandler;
import com.clix.render.PlainRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit experimental client. It requires confirmation for writes and commands. */
public final class RemoteClientCommand {
    private RemoteClientCommand() { }

    public static boolean matches(String[] args) {
        return args != null && args.length == 1 && "remote".equalsIgnoreCase(args[0]);
    }

    public static int run() {
        String address = System.getenv("CLIX_REMOTE_URL");
        String token = System.getenv("CLIX_REMOTE_TOKEN");
        if ((address == null || address.isBlank()) && (token == null || token.isBlank())) {
            try {
                var saved = RemoteLoginStore.defaultStore().load();
                address = saved.url(); token = saved.token();
            } catch (Exception e) {
                System.err.println("请先运行 clix login，或设置 CLIX_REMOTE_URL 和 CLIX_REMOTE_TOKEN");
                return 1;
            }
        }
        if (address == null || address.isBlank() || token == null || token.isBlank()) {
            System.err.println("请设置 CLIX_REMOTE_URL 和 CLIX_REMOTE_TOKEN");
            return 1;
        }
        var rawInput = terminalInput();
        var input = new RemoteInputReader(rawInput);
        var inputRef = new AtomicReference<>(input);
        var renderer = new PlainRenderer(System.out, input);
        var ready = new CountDownLatch(1);
        var history = new AtomicReference<CountDownLatch>();
        var failure = new AtomicReference<String>();
        var streamed = new java.util.concurrent.atomic.AtomicBoolean();
        var controllerRef = new AtomicReference<CurrentTurnController>();
        ConsoleCancelBridge cancelBridge = null;
        var exitArmedUntil = new long[]{0L};
        var handler = new SwitchableHitlHandler(new RendererHitlHandler(renderer, false));
        handler.setHighRiskConfirmationEnabled(true);
        var workspace = new WorkspaceGuard();
        var registry = new RemoteClientToolRegistry(handler);
        var tools = new RemoteLocalTools(registry, Path.of("."));
        tools.setWorkspaceGuard(workspace);
        var commands = new RemoteClientCommands(workspace);
        try (var terminal = TerminalBuilder.builder().system(true).nativeSignals(true).dumb(true).build();
             var connection = new RemoteClientConnection(URI.create(address), token, tools, (type, payload) -> {
            if ("session.ready".equals(type)) ready.countDown();
            else if ("message.delta".equals(type)) {
                CurrentTurnController controller = controllerRef.get();
                if (controller != null && controller.isCurrent(payload.path("turn_id").asText(""))) {
                    streamed.set(true); renderer.stream().print(payload.path("content").asText());
                }
            }
            else if ("turn.completed".equals(type)) {
                CurrentTurnController controller = controllerRef.get();
                String turnId = payload.path("turn_id").asText("");
                if (controller != null && controller.isCurrent(turnId)) {
                    renderer.stream().println(streamed.get() ? "" : payload.path("content").asText());
                    controller.complete(turnId, CurrentTurnController.Outcome.COMPLETED);
                }
            } else if ("turn.cancelled".equals(type)) {
                CurrentTurnController controller = controllerRef.get();
                String turnId = payload.path("turn_id").asText("");
                if (controller != null && controller.isCurrent(turnId)) {
                    if (streamed.get()) renderer.stream().println();
                    commands.onCancelled(payload, tools.unfinishedOperations());
                    renderer.stream().println("当前任务已取消，会话仍保持连接。");
                    renderer.stream().println("副作用记录：" + payload.path("effect_audit").asText("unavailable")
                            + "；冲突 " + payload.path("conflict_count").asInt(0)
                            + " 个；保留版本 " + payload.path("version_count").asInt(0) + " 个"
                            + (payload.path("needs_verification").asBoolean(false) ? "；需重新核验" : ""));
                    renderer.stream().println("终止：" + payload.path("termination").asText("unknown")
                            + "；本地 transport：" + payload.path("transport_cancel_status").asText("unknown")
                            + "；供应商远端确认：" + payload.path("provider_cancel_ack").asText("unsupported"));
                    renderer.stream().println("工作区可能包含部分修改；服务端无法确认客户端监管域状态，继续时会先显示核验信息。");
                    renderer.stream().println("本地监管域：" + registry.runner().statusLine());
                    renderer.stream().println("可继续输入，或使用 /continue、/status、/retry。");
                    controller.complete(turnId, CurrentTurnController.Outcome.CANCELLED);
                }
            } else if ("history.list".equals(type)) {
                payload.path("sessions").forEach(item -> renderer.stream().println(item.path("id").asText()));
                CountDownLatch latch = history.get(); if (latch != null) latch.countDown();
            } else if ("history.entries".equals(type)) {
                payload.path("messages").forEach(item -> renderer.stream().println(item.path("role").asText() + ": " + item.path("content").asText()));
                CountDownLatch latch = history.get(); if (latch != null) latch.countDown();
            } else if ("history.error".equals(type)) {
                renderer.stream().println(payload.path("error").asText());
                CountDownLatch latch = history.get(); if (latch != null) latch.countDown();
            } else if ("turn.failed".equals(type)) {
                CurrentTurnController controller = controllerRef.get();
                String turnId = payload.path("turn_id").asText("");
                if (controller != null && controller.isCurrent(turnId)) {
                    renderer.stream().println(payload.path("error").asText("任务执行失败"));
                    controller.complete(turnId, CurrentTurnController.Outcome.FAILED);
                }
            } else if (type.startsWith("connection.")) {
                failure.set(payload.path("error").asText("连接已关闭"));
                ready.countDown();
                CurrentTurnController controller = controllerRef.get();
                if (controller != null) controller.connectionClosed();
                CountDownLatch historyLatch = history.get(); if (historyLatch != null) historyLatch.countDown();
                // Wake the blocking prompt so the main loop can observe the failure.
                RemoteInputReader reader = inputRef.get();
                if (reader != null) { try { reader.close(); } catch (Exception ignored) { } }
            }
        })) {
            var controller = new CurrentTurnController(connection::cancelTurn, turnId -> {
                connection.cancelDeadlineExpired(turnId);
                // Phase two: quarantine only when a concrete containment domain is unconfirmed.
                // A bounded 60-second background inspection may return the workspace to SAFE.
                if (registry.runner().unknownOperationIds().isEmpty()) return;
                workspace.quarantine("取消达到 10 秒绝对上限，监管域状态未确认（"
                        + registry.runner().statusLine() + "）");
                registry.runner().startRecovery(() -> {
                    workspace.confirmSafe("后台诊断确认监管域为空");
                    renderer.stream().println("监管域已确认清空，工作区自动恢复 SAFE。");
                });
            });
            controllerRef.set(controller);
            // Stage four: the native clix.exe launcher delivers Ctrl+C out-of-band via a pipe. When
            // CLIX_CANCEL_PIPE is unset (POSIX) this returns null and the JLine SIGINT handler below
            // stays in charge, so existing behaviour is unchanged.
            cancelBridge = ConsoleCancelBridge.startIfConfigured(
                    () -> cancelCurrentTurn(controller, renderer, connection));
            // Orderly-exit crash verification: warn about operations that never reached a terminal
            // record. A hard JVM crash is recovered by EffectRecorder.replayUnfinished() on restart.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    var unfinished = tools.unfinishedOperations();
                    if (!unfinished.isEmpty()) {
                        System.err.println("[clix] 退出时仍有 " + unfinished.size()
                                + " 个未终态化的副作用操作；下次启动将核验且不重放工具。");
                    }
                } catch (RuntimeException ignored) { }
            }));
            terminal.handle(Terminal.Signal.INT, signal -> {
                if (controller.hasActiveTurn()) {
                    cancelCurrentTurn(controller, renderer, connection);
                    return;
                }
                // No active turn: clear whatever is being typed, and arm a two-press exit.
                input.discardCurrentLine();
                long now = System.nanoTime();
                if (exitArmedUntil[0] > now) {
                    renderer.stream().println("\n已确认退出。");
                    try { input.close(); } catch (Exception ignored) { }
                    connection.closeConnection();
                } else {
                    exitArmedUntil[0] = now + TimeUnit.MILLISECONDS.toNanos(RemoteProtocol.EXIT_ARM_WINDOW_MS);
                    renderer.stream().println("\n已清空当前输入；" + RemoteProtocol.EXIT_ARM_WINDOW_MS / 1000
                            + " 秒内再按一次 Ctrl+C 退出。");
                }
            });
            if (!ready.await(15, TimeUnit.SECONDS) || failure.get() != null) {
                renderer.stream().println("连接失败：" + failure.get()); return 1;
            }
            renderer.stream().println("clix 远程模式（开发中；/mode auto|ask；Ctrl+C 取消当前任务；/status /continue /retry）");
            while (failure.get() == null) {
                renderer.stream().print("> "); renderer.stream().flush();
                String inputLine;
                try { inputLine = input.readLine(); }
                catch (java.io.IOException e) { inputLine = null; }
                if (inputLine == null) break;
                if (inputLine.isBlank()) continue;
                if (commands.confirmForcedRelease(inputLine)) {
                    renderer.stream().println("工作区隔离已由本地用户强制解除；ask/deny 策略不变，仍需逐次确认。");
                    continue;
                }
                RemoteClientCommands.Kind kind = RemoteClientCommands.parse(inputLine);
                if (kind == RemoteClientCommands.Kind.EXIT) break;
                if (kind == RemoteClientCommands.Kind.STATUS) {
                    renderer.stream().println(commands.status(controller, connection, workspace,
                            registry.approvalHandler() == null ? 0 : registry.approvalHandler().staleApprovals(),
                            registry.runner().statusLine()));
                    continue;
                }
                if (kind == RemoteClientCommands.Kind.QUARANTINE) {
                    renderer.stream().println(commands.quarantineStatus());
                    continue;
                }
                if (kind == RemoteClientCommands.Kind.QUARANTINE_UNLOCK) {
                    renderer.stream().println(commands.beginForcedRelease(
                            Path.of(".").toAbsolutePath().normalize().toString(),
                            System.getProperty("user.name", "local-user")));
                    continue;
                }
                if (kind == RemoteClientCommands.Kind.MODE) {
                    handler.switchConfirmationMode(inputLine.endsWith("ask") ? "on" : "default");
                    renderer.stream().println("模式已切换：" + (handler.isEnabled() ? "ask" : "auto"));
                    continue;
                }
                if (kind == RemoteClientCommands.Kind.HISTORY) {
                    var done = new CountDownLatch(1); history.set(done);
                    var payload = new ObjectMapper().createObjectNode();
                    boolean listing = inputLine.equals("/history");
                    if (!listing) payload.put("id", inputLine.substring(9).trim());
                    connection.send(listing ? "history.list" : "history.read", payload);
                    if (!done.await(15, TimeUnit.SECONDS)) renderer.stream().println("读取会话超时");
                    history.compareAndSet(done, null);
                    continue;
                }
                if (kind == RemoteClientCommands.Kind.UNKNOWN) {
                    renderer.stream().println("远程模式暂不支持该命令；本地支持 /status /continue /retry /quarantine /history /mode");
                    continue;
                }
                String content;
                boolean recordGoal = kind == RemoteClientCommands.Kind.NONE;
                if (kind == RemoteClientCommands.Kind.CONTINUE) {
                    content = commands.continueContent();
                } else if (kind == RemoteClientCommands.Kind.RETRY) {
                    content = commands.retryContent();
                    if (content == null) { renderer.stream().println("没有可重试的原始用户目标。"); continue; }
                } else {
                    String note = commands.consumeInterruptionNote();
                    content = note == null ? inputLine : note + "\n用户新输入：" + inputLine;
                }
                CurrentTurnController.Turn current = controller.begin();
                streamed.set(false);
                try {
                    connection.startTurn(current.id());
                } catch (RuntimeException e) {
                    controller.complete(current.id(), CurrentTurnController.Outcome.FAILED);
                    controller.release(current);
                    renderer.stream().println("无法开始新 Turn：" + e.getMessage());
                    continue;
                }
                if (recordGoal) commands.onTurnStarted(current.id(), inputLine);
                else commands.onTurnStarted(current.id());
                connection.send("user.message", new ObjectMapper().createObjectNode()
                        .put("turn_id", current.id()).put("content", content)
                        .put("user_message", commands.originalGoal() == null ? "" : commands.originalGoal())
                        .put("mode", handler.isEnabled() ? "ask" : "auto"));
                CurrentTurnController.Outcome outcome = controller.await(current);
                commands.onTurnFinished(outcome == null ? "UNKNOWN" : outcome.name(), controller.phase().name());
                if (outcome == CurrentTurnController.Outcome.CANCEL_TIMEOUT) {
                    renderer.stream().println("取消已达到 10 秒上限；当前 Turn 已隔离，本地副作用状态尚未确认。");
                    renderer.stream().println("写入和 execute_command 暂时拒绝；可用 /status 查看，或用 /quarantine unlock --force 两步解除。");
                }
                controller.release(current);
            }
            if (failure.get() != null) { renderer.stream().println(failure.get()); return 1; }
            return 0;
        } catch (Exception e) {
            renderer.stream().println("远程客户端失败：" + e.getMessage());
            return 1;
        } finally {
            if (cancelBridge != null) cancelBridge.close();
            CurrentTurnController controller = controllerRef.get();
            if (controller != null) controller.close();
            registry.runner().close();
            try { input.close(); } catch (Exception ignored) { }
            renderer.close();
        }
    }

    /** Routes a Ctrl+C delivery to the current turn controller, shared by the JLine SIGINT hook and
     *  the native launcher's out-of-band bridge. A second delivery upgrades to FORCED. */
    private static void cancelCurrentTurn(CurrentTurnController controller, PlainRenderer renderer,
            RemoteClientConnection connection) {
        if (!controller.hasActiveTurn()) return;
        try {
            CurrentTurnController.CancelRequest request = controller.requestCancel();
            if (request == CurrentTurnController.CancelRequest.STARTED) {
                renderer.stream().println("\n正在取消当前任务…");
            } else if (request == CurrentTurnController.CancelRequest.FORCED) {
                renderer.stream().println("\n正在跳过协作等待并强制取消；原 10 秒截止时间不变…");
            }
        } catch (IllegalStateException ignored) {
            connection.closeConnection();
        }
    }

    private static BufferedReader terminalInput() {
        var console = System.console();
        if (console != null) return new BufferedReader(console.reader());
        String configured = System.getProperty("sun.stdin.encoding");
        Charset charset;
        try { charset = configured == null ? Charset.defaultCharset() : Charset.forName(configured); }
        catch (IllegalArgumentException e) { charset = Charset.defaultCharset(); }
        return inputReader(System.in, charset);
    }

    static BufferedReader inputReader(java.io.InputStream input, Charset charset) {
        return new BufferedReader(new InputStreamReader(input, charset));
    }
}

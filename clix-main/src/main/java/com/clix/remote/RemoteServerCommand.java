package com.clix.remote;

import com.clix.config.ClixConfig;
import com.clix.llm.LlmClient;
import com.clix.llm.LlmClientFactory;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** Explicit entry point for the remote server; listens on loopback only. */
public final class RemoteServerCommand {
    private RemoteServerCommand() { }

    public static boolean matches(String[] args) {
        return args != null && args.length >= 2 && "serve".equalsIgnoreCase(args[0])
                && "--remote".equalsIgnoreCase(args[1]);
    }

    public static int port(String[] args) {
        if (args.length == 2) return 8080;
        if (args.length != 4 || !"--port".equals(args[2])) throw new IllegalArgumentException("用法: serve --remote [--port 端口]");
        try {
            int port = Integer.parseInt(args[3]);
            if (port < 1 || port > 65535) throw new NumberFormatException();
            return port;
        } catch (NumberFormatException e) { throw new IllegalArgumentException("端口必须在 1 到 65535 之间"); }
    }

    public static int run(String[] args) {
        try {
            int port = port(args);
            String usersFile = System.getenv("CLIX_REMOTE_USERS_FILE");
            if (usersFile == null || usersFile.isBlank()) throw new IllegalArgumentException("请配置 CLIX_REMOTE_USERS_FILE");
            String configuredRoot = System.getenv("CLIX_REMOTE_DATA_DIR");
            Path dataRoot = configuredRoot == null || configuredRoot.isBlank()
                    ? Path.of(System.getProperty("user.home"), ".clix", "remote") : Path.of(configuredRoot);
            var identities = RemoteIdentityStore.load(Path.of(usersFile));
            ClixConfig config = ClixConfig.load();
            try (var server = new RemoteSocketServer(port, identities, (user, id, sender) -> {
                LlmClient model = LlmClientFactory.createFromConfig(config);
                if (model == null) throw new IllegalStateException("没有可用的模型配置");
                return new RemoteAgentSession(user, dataRoot, model, sender, () ->
                        LlmClientFactory.createApprovalClassifier(model.getProviderName(),
                                System.getenv("CLIX_AUTO_CLASSIFIER_MODEL"), config));
            })) {
                server.start();
                System.out.println("clix 远程服务（开发中，尚未完成 V1）正在监听 127.0.0.1:" + server.port());
                new CountDownLatch(1).await();
            }
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception e) {
            System.err.println("远程服务启动失败: " + e.getMessage());
            return 1;
        }
    }
}

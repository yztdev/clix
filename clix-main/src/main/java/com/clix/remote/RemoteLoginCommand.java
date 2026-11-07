package com.clix.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Interactive login validates the connection before storing an encrypted credential. */
public final class RemoteLoginCommand {
    private RemoteLoginCommand() { }
    public static boolean matches(String[] args) { return args != null && args.length == 1 && "login".equalsIgnoreCase(args[0]); }

    public static int run() {
        var console = System.console();
        if (console == null) { System.err.println("登录需要交互式终端，无法从管道读取凭证"); return 1; }
        String address = console.readLine("服务器 WebSocket 地址: ");
        char[] secret = console.readPassword("登录凭证: ");
        if (secret == null) return 1;
        try {
            var credentials = new RemoteLoginStore.Credentials(address == null ? "" : address.trim(), new String(secret));
            RemoteClientConnection.validateUri(URI.create(credentials.url()));
            if (!verify(credentials)) { console.writer().println("登录失败：服务器不可用或凭证无效"); return 1; }
            RemoteLoginStore.defaultStore().save(credentials);
            console.writer().println("登录成功，凭证已使用当前 Windows 用户加密保存。");
            return 0;
        } catch (Exception e) {
            console.writer().println("登录失败：" + e.getMessage());
            return 1;
        } finally { Arrays.fill(secret, '\0'); }
    }

    static boolean verify(RemoteLoginStore.Credentials credentials) throws InterruptedException {
        var client = new OkHttpClient();
        var completed = new CountDownLatch(1);
        var valid = new AtomicBoolean();
        var socket = client.newWebSocket(new Request.Builder().url(credentials.url())
                .header("Authorization", "Bearer " + credentials.token()).build(), new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, String text) {
                try {
                    var frame = new ObjectMapper().readTree(text);
                    valid.set(frame.path("version").isInt()
                            && frame.path("version").asInt() == RemoteProtocol.VERSION
                            && "session.ready".equals(frame.path("type").asText())
                            && !frame.path("session_id").asText().isBlank());
                } catch (Exception ignored) { }
                completed.countDown();
            }
            @Override public void onFailure(WebSocket ws, Throwable error, Response response) { completed.countDown(); }
            @Override public void onClosing(WebSocket ws, int code, String reason) { completed.countDown(); }
        });
        try { return completed.await(15, TimeUnit.SECONDS) && valid.get(); }
        finally { socket.cancel(); client.dispatcher().executorService().shutdownNow(); client.connectionPool().evictAll(); }
    }
}

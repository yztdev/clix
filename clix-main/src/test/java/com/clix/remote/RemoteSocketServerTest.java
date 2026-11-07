package com.clix.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RemoteSocketServerTest {
    private static final String TOKEN = "test-token-123456789012345678901234567890";

    @Test
    void rejectsUnknownCredentialsBeforeCreatingSession() throws Exception {
        var opened = new AtomicInteger();
        try (var server = new RemoteSocketServer(0, new RemoteIdentityStore(Map.of("alice", TOKEN)),
                (user, id, sender) -> { opened.incrementAndGet(); throw new AssertionError(); })) {
            server.start();
            var failed = new CountDownLatch(1);
            var status = new AtomicInteger();
            var client = new OkHttpClient();
            WebSocket socket = client.newWebSocket(new Request.Builder()
                    .url("http://127.0.0.1:" + server.port() + "/v1/remote")
                    .header("Authorization", "Bearer invalid").build(), new WebSocketListener() {
                @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
                    if (response != null) status.set(response.code());
                    failed.countDown();
                }
            });
            try {
                assertTrue(failed.await(5, TimeUnit.SECONDS));
                assertEquals(401, status.get());
                assertEquals(0, opened.get());
            } finally {
                socket.cancel(); client.dispatcher().executorService().shutdownNow(); client.connectionPool().evictAll();
            }
        }
    }

    @Test
    void authenticatesAndRejectsWrongSessionMessages() throws Exception {
        var calls = new AtomicInteger();
        try (var server = new RemoteSocketServer(0, new RemoteIdentityStore(Map.of("alice", TOKEN)),
                (user, id, sender) -> new RemoteSocketServer.RemoteSession() {
                    public void onMessage(JsonNode message) { calls.incrementAndGet(); }
                    public void close() { }
                })) {
            server.start();
            var ready = new CountDownLatch(1);
            var closed = new CountDownLatch(1);
            var session = new AtomicReference<String>();
            var failure = new AtomicReference<String>();
            var client = new OkHttpClient();
            WebSocket socket = client.newWebSocket(new Request.Builder()
                    .url("http://127.0.0.1:" + server.port() + "/v1/remote")
                    .header("Authorization", "Bearer " + TOKEN).build(), new WebSocketListener() {
                @Override public void onMessage(WebSocket webSocket, String text) {
                    try { session.set(new ObjectMapper().readTree(text).path("session_id").asText()); ready.countDown(); }
                    catch (Exception e) { throw new AssertionError(e); }
                }
                @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                    webSocket.close(code, reason); closed.countDown();
                }
                @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    failure.set(t.toString() + " status=" + (response == null ? "none" : response.code()));
                    closed.countDown();
                }
            });
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS), () -> "连接失败: " + failure.get());
                socket.send("{\"version\":" + RemoteProtocol.VERSION + ",\"session_id\":\""
                        + session.get() + "\",\"type\":\"user.message\"}");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (calls.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals(1, calls.get());
                socket.send("{\"version\":" + RemoteProtocol.VERSION
                        + ",\"session_id\":\"other\",\"type\":\"user.message\"}");
                assertTrue(closed.await(5, TimeUnit.SECONDS));
                assertEquals(1, calls.get());
            } finally {
                socket.cancel();
                client.dispatcher().executorService().shutdownNow();
                client.connectionPool().evictAll();
            }
        }
    }
}

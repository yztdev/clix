package com.clix.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RemoteLoginTest {
    @Test void encryptsCredentialsForCurrentWindowsUser(@TempDir Path root) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().startsWith("windows"));
        String token = "test-token-123456789012345678901234567890";
        var store = new RemoteLoginStore(root.resolve("login.json"));
        var input = new RemoteLoginStore.Credentials("wss://example.com/v1/remote", token);
        store.save(input);
        assertFalse(Files.readString(root.resolve("login.json")).contains(token));
        assertEquals(input, store.load());
    }

    @Test void checksConnectionBeforeSaving() throws Exception {
        String token = "test-token-123456789012345678901234567890";
        try (var server = new RemoteSocketServer(0, new RemoteIdentityStore(Map.of("alice", token)),
                (user, id, sender) -> new RemoteSocketServer.RemoteSession() {
                    public void onMessage(com.fasterxml.jackson.databind.JsonNode message) { }
                    public void close() { }
                })) {
            server.start();
            String url = "ws://127.0.0.1:" + server.port() + "/v1/remote";
            assertTrue(RemoteLoginCommand.verify(new RemoteLoginStore.Credentials(url, token)));
            assertFalse(RemoteLoginCommand.verify(new RemoteLoginStore.Credentials(url, "bad-token")));
        }
    }
}

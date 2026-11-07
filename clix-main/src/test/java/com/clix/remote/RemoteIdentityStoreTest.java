package com.clix.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RemoteIdentityStoreTest {
    private static final String ALICE = "alice-token-123456789012345678901234567890";
    private static final String BOB = "bob-token-12345678901234567890123456789012";

    @Test
    void credentialsResolveToDistinctUsersAndBadTokensFail() {
        var store = new RemoteIdentityStore(Map.of("alice", ALICE, "bob", BOB));
        assertEquals("alice", store.authenticate(ALICE));
        assertEquals("bob", store.authenticate(BOB));
        assertNull(store.authenticate("incorrect-123456789012345678901234567890"));
        assertNull(store.authenticate(null));
        assertThrows(IllegalArgumentException.class, () -> new RemoteIdentityStore(Map.of("a", ALICE, "b", ALICE)));
        assertThrows(IllegalArgumentException.class, () -> new RemoteIdentityStore(Map.of("../alice", ALICE)));
    }

    @Test
    void loadsCredentialFileWithoutExposingTokens(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("users.json");
        Files.writeString(file, "{\"alice\":\"" + ALICE + "\"}");
        assertEquals("alice", RemoteIdentityStore.load(file).authenticate(ALICE));
        Files.writeString(file, "{\"alice\":\"" + ALICE + "\",\"alice\":\"" + BOB + "\"}");
        assertThrows(java.io.IOException.class, () -> RemoteIdentityStore.load(file));
    }
}

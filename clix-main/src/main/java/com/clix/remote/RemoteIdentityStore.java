package com.clix.remote;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Maps per-user credentials to a server-side user ID without exposing credentials in logs. */
public final class RemoteIdentityStore {
    private record Identity(String userId, byte[] digest) { }
    private final List<Identity> identities;

    public RemoteIdentityStore(Map<String, String> credentials) {
        if (credentials == null || credentials.isEmpty()) throw new IllegalArgumentException("远程用户配置不能为空");
        List<Identity> entries = new ArrayList<>();
        Set<String> uniqueTokens = new HashSet<>();
        credentials.forEach((user, token) -> {
            if (user == null || !user.matches("[a-zA-Z0-9_-]{1,64}")
                    || token == null || token.length() < 32 || token.length() > 512 || token.isBlank()) {
                throw new IllegalArgumentException("用户 ID 或远程凭证格式无效");
            }
            if (!uniqueTokens.add(token)) throw new IllegalArgumentException("不同用户不能共用凭证");
            entries.add(new Identity(user, digest(token)));
        });
        identities = List.copyOf(entries);
    }

    public static RemoteIdentityStore load(Path file) throws IOException {
        if (Files.size(file) > 1024 * 1024) throw new IOException("远程用户配置过大");
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        Map<String, String> credentials = mapper.readValue(file.toFile(), new TypeReference<>() {});
        return new RemoteIdentityStore(credentials);
    }

    /** Returns the user ID for a valid bearer token, or null for an invalid credential. */
    public String authenticate(String token) {
        if (token == null || token.length() < 32 || token.length() > 512) return null;
        byte[] candidate = digest(token);
        String matched = null;
        for (Identity identity : identities) {
            if (MessageDigest.isEqual(identity.digest(), candidate)) matched = identity.userId();
        }
        return matched;
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

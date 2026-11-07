package com.clix.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.platform.win32.Crypt32Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;

/** Windows DPAPI binds the encrypted credential to the current operating-system user. */
public final class RemoteLoginStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    public record Credentials(String url, String token) { }
    private record Stored(String url, String protectedToken) { }
    private final Path file;

    public RemoteLoginStore(Path file) { this.file = file.toAbsolutePath().normalize(); }
    public static RemoteLoginStore defaultStore() {
        return new RemoteLoginStore(Path.of(System.getProperty("user.home"), ".clix", "remote-login.json"));
    }
    public boolean exists() { return Files.isRegularFile(file); }

    public void save(Credentials credentials) throws IOException {
        requireWindows();
        validate(credentials);
        byte[] plain = credentials.token().getBytes(StandardCharsets.UTF_8);
        byte[] encrypted;
        try { encrypted = Crypt32Util.cryptProtectData(plain); }
        catch (RuntimeException e) { throw new IOException("无法使用 Windows 用户加密凭证", e); }
        finally { java.util.Arrays.fill(plain, (byte) 0); }
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "remote-login-", ".tmp");
        try {
            JSON.writeValue(temporary.toFile(), new Stored(credentials.url(), Base64.getEncoder().encodeToString(encrypted)));
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    public Credentials load() throws IOException {
        requireWindows();
        if (Files.size(file) > 16384) throw new IOException("登录配置过大");
        Stored stored = JSON.readValue(file.toFile(), Stored.class);
        byte[] plain;
        try { plain = Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(stored.protectedToken())); }
        catch (RuntimeException e) { throw new IOException("无法解密登录凭证，请重新登录", e); }
        try {
            Credentials result = new Credentials(stored.url(), new String(plain, StandardCharsets.UTF_8));
            validate(result);
            return result;
        } finally { java.util.Arrays.fill(plain, (byte) 0); }
    }

    private static void validate(Credentials value) {
        if (value == null || value.url() == null || value.token() == null || value.token().length() < 32)
            throw new IllegalArgumentException("服务器地址或凭证无效");
        RemoteClientConnection.validateUri(java.net.URI.create(value.url()));
    }
    private static void requireWindows() throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows"))
            throw new IOException("保存登录目前仅支持 Windows");
    }
}

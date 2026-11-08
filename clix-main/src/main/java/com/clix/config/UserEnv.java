package com.clix.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** The single persistent environment file for a CLIX user: ~/.clix/.env. */
public final class UserEnv {
    private static final String TEMPLATE = """
            # CLIX global configuration. This file is shared by all projects for this OS user.
            # Choose a provider and fill in its API key. Never commit this file.
            LLM_PROVIDER=deepseek
            DEEPSEEK_API_KEY=
            # OPENAI_API_KEY=
            # ANTHROPIC_API_KEY=
            # GLM_API_KEY=
            # HUNYUAN_API_KEY=
            # STEP_API_KEY=
            # KIMI_API_KEY=
            # FREELLMAPI_API_KEY=
            # XFYUN_MAAS_API_KEY=
            """;

    private UserEnv() { }

    public static Path path() {
        return Path.of(System.getProperty("user.home"), ".clix", ".env").toAbsolutePath().normalize();
    }

    public static String get(String key) {
        if (key == null || key.isBlank()) return null;
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) return value.trim();
        value = System.getProperty(key);
        if (value != null && !value.isBlank()) return value.trim();
        return loadAll().get(key);
    }

    public static Map<String, String> loadAll() {
        Path file = path();
        if (!Files.isRegularFile(file)) return Map.of();
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("export ")) line = line.substring(7).trim();
                int separator = line.indexOf('=');
                if (separator <= 0) continue;
                String key = line.substring(0, separator).trim();
                if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
                String value = unquote(line.substring(separator + 1).trim());
                values.put(key, value);
            }
        } catch (IOException ignored) {
            return Map.of();
        }
        return Map.copyOf(values);
    }

    public static Path ensureExists() throws IOException {
        Path file = path();
        Files.createDirectories(file.getParent());
        if (Files.notExists(file)) {
            Files.writeString(file, TEMPLATE, StandardCharsets.UTF_8);
            restrictPermissions(file);
        }
        return file;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                value = value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static void restrictPermissions(Path file) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows ACLs inherit from the user's profile directory.
        }
    }
}

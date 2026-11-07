package com.clix.effect;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Exact before/after observation for native file tools (design doc V1.2 B). */
public final class FileEffectProbe {
    public enum Status { ABSENT, PRESENT, FAILED }

    public record State(Status status, String sha256, long size, long mtimeMillis) {
        public static State absent() { return new State(Status.ABSENT, null, 0L, 0L); }
        public static State failed() { return new State(Status.FAILED, null, 0L, 0L); }
        public boolean captured() { return status == Status.ABSENT || status == Status.PRESENT; }
    }

    public static final class PathRejected extends Exception {
        public PathRejected(String message) { super(message); }
    }

    private final Path root;

    public FileEffectProbe(Path root) {
        this.root = java.util.Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public Path root() { return root; }

    /**
     * Normalizes a project-relative path and rejects absolute paths, parent traversal,
     * duplicate segments and symlink escapes (including dangling links).
     */
    public Path resolve(String relative) throws PathRejected {
        if (relative == null || relative.isBlank()) throw new PathRejected("路径为空");
        String normalized = relative.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw new PathRejected("路径必须是项目内相对路径");
        }
        Path current = root;
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) throw new PathRejected("路径包含 .. 越界");
            current = current.resolve(segment).normalize();
            if (!current.startsWith(root)) throw new PathRejected("路径越出项目根目录");
            if (Files.isSymbolicLink(current)) {
                try {
                    Path target = current.toRealPath();
                    if (!target.startsWith(root.toRealPath())) throw new PathRejected("符号链接越出项目根目录");
                    current = target;
                } catch (IOException e) {
                    throw new PathRejected("符号链接目标不可解析");
                }
            }
        }
        if (!current.startsWith(root)) throw new PathRejected("路径越出项目根目录");
        return current;
    }

    /** Never uploads file content: only presence, size, mtime and SHA-256. */
    public State capture(Path absolute) {
        if (absolute == null) return State.failed();
        try {
            if (Files.isSymbolicLink(absolute)) return State.failed();
            if (Files.notExists(absolute, LinkOption.NOFOLLOW_LINKS)) return State.absent();
            if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) return State.failed();
            byte[] bytes = Files.readAllBytes(absolute);
            return new State(Status.PRESENT, "sha256:" + hex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    bytes.length, Files.getLastModifiedTime(absolute).toMillis());
        } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
            return State.failed();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format("%02x", b));
        return out.toString();
    }
}

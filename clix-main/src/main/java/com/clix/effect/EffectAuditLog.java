package com.clix.effect;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Client-side append-only JSONL for effect_started / tool.effect records (design doc
 * "本地待发送记录"). Records are written before the side-effecting tool starts and are
 * force-flushed before the operation lock is released, so a crash between execution and
 * reporting still leaves a "possibly executed" fact for the next session.
 */
public final class EffectAuditLog {
    public static final long RETENTION_DAYS = 30L;
    public static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    private final Path file;
    private final Clock clock;
    private final AtomicLong sequence;
    private final Map<String, ReentrantLock> operationLocks = new ConcurrentHashMap<>();
    private volatile boolean flushEnabled = true;
    private volatile boolean failFlush = false;

    public EffectAuditLog(Path file) throws IOException { this(file, Clock.systemUTC()); }

    public EffectAuditLog(Path file, Clock clock) throws IOException {
        this.file = java.util.Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        Path parent = this.file.getParent();
        if (parent != null) Files.createDirectories(parent);
        if (Files.notExists(this.file)) createSecureFile(this.file);
        long max = -1L;
        for (EffectEvent event : readAll()) max = Math.max(max, event.sequence());
        this.sequence = new AtomicLong(max + 1L);
    }

    private static void createSecureFile(Path path) throws IOException {
        try {
            Files.createFile(path,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(path);
        }
    }

    public Path file() { return file; }

    public long nextSequence() { return sequence.getAndIncrement(); }

    /**
     * The lock covers path normalization, before capture, tool execution, after capture,
     * JSONL append and flush (design doc V1.2 B). A reentrant lock is used because
     * {@code before} and {@code after} are separate calls that must share one critical
     * section; callers lock before {@code before()} and unlock after {@code after()}.
     */
    public ReentrantLock operationLock(String operationId) {
        return operationLocks.computeIfAbsent(operationId, ignored -> new ReentrantLock());
    }

    /** Test seam: disable force() to simulate a flush failure. */
    public void setFlushEnabled(boolean enabled) { this.flushEnabled = enabled; this.failFlush = !enabled; }

    public boolean flushEnabled() { return flushEnabled; }

    /** Appends one event and only returns after the bytes have been flushed to disk. */
    public void append(EffectEvent event) throws IOException {
        byte[] json = EffectEvent.JSON.writeValueAsBytes(event.toNode());
        ByteBuffer buffer = ByteBuffer.allocate(json.length + 1);
        buffer.put(json).put((byte) '\n').flip();
        synchronized (file) {
            try (FileChannel channel = FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                while (buffer.hasRemaining()) channel.write(buffer);
                if (failFlush) throw new IOException("effect audit flush failed");
                if (flushEnabled) channel.force(true);
            }
        }
    }

    public List<EffectEvent> readAll() throws IOException {
        if (!Files.exists(file)) return List.of();
        List<EffectEvent> events = new ArrayList<>();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = EffectEvent.JSON.readTree(line);
                    events.add(EffectEvent.fromNode(node));
                } catch (IOException ignored) {
                    // A crash can leave a half-written final line; skip it (never overwrite).
                }
            }
        }
        return List.copyOf(events);
    }

    /** effect_started records with no matching terminal record: "possibly executed". */
    public Set<String> unfinishedOperations() throws IOException {
        Set<String> started = new HashSet<>();
        Set<String> terminal = new HashSet<>();
        for (EffectEvent event : readAll()) {
            if (EffectEvent.EFFECT_STARTED.equals(event.eventType())) started.add(event.operationId());
            if (EffectEvent.TOOL_EFFECT.equals(event.eventType())) terminal.add(event.operationId());
        }
        started.removeAll(terminal);
        return started;
    }

    /**
     * Recovery view: records older than the retention window are ignored rather than
     * physically deleted, which preserves the append-only property of the file.
     */
    public List<EffectEvent> readWithinRetention() throws IOException {
        Instant cutoff = clock.instant().minus(Duration.ofDays(RETENTION_DAYS));
        List<EffectEvent> result = new ArrayList<>();
        for (EffectEvent event : readAll()) {
            try {
                if (!Instant.parse(event.occurredAt()).isBefore(cutoff)) result.add(event);
            } catch (RuntimeException ignored) {
                result.add(event);
            }
        }
        return List.copyOf(result);
    }

    public boolean exceedsCapacity() {
        try { return Files.exists(file) && Files.size(file) > MAX_FILE_BYTES; }
        catch (IOException e) { return false; }
    }
}

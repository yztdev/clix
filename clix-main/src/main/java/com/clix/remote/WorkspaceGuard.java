package com.clix.remote;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Client-side workspace safety gate (design doc "工作区安全状态", phase-one client part).
 *
 * <p>Phase two drives quarantine from real containment facts: the deadline expires with at
 * least one CANCELLED_UNKNOWN operation, so QUARANTINED_UNKNOWN refuses writes and commands
 * while keeping read-only verification available. RecoveryInspector may automatically return
 * the workspace to SAFE once the domain is observed empty; otherwise the local interactive
 * user can perform the two-step, time-limited release (which never becomes tool approval).
 *
 * <p>FORCED_RELEASED only lifts this gate. It never changes ask/auto/deny decisions.
 */
final class WorkspaceGuard {
    enum State { SAFE, QUIESCING, QUARANTINED_UNKNOWN, FORCED_RELEASED }

    static final Set<String> READ_ONLY_TOOLS = Set.of("read_file", "list_dir", "glob_files", "grep_code");
    private static final long PHRASE_TTL_MILLIS = 60_000L;

    private volatile State state = State.SAFE;
    private volatile String reason;
    private volatile String quarantinedAt;
    private volatile int quarantineVersion;
    private volatile String confirmationPhrase;
    private volatile long phraseExpiresAtMillis;
    private volatile String lastVerification;

    State state() { return state; }
    String reason() { return reason; }
    String quarantinedAt() { return quarantinedAt; }
    int quarantineVersion() { return quarantineVersion; }

    /** Deadline expired without a confirmed terminal state: never pretend the workspace is safe. */
    void quarantine(String cause) {
        state = State.QUARANTINED_UNKNOWN;
        reason = cause;
        quarantinedAt = Instant.now().toString();
        quarantineVersion++;
        confirmationPhrase = null;
        phraseExpiresAtMillis = 0L;
    }

    /**
     * Phase two: RecoveryInspector observed an empty containment domain. Only this automatic
     * verification (never a forced release) may return the workspace to SAFE.
     */
    void confirmSafe(String verificationNote) {
        state = State.SAFE;
        reason = null;
        lastVerification = verificationNote;
        confirmationPhrase = null;
        phraseExpiresAtMillis = 0L;
    }

    /** Read-only verification remains allowed in quarantine (doc completion condition 11). */
    boolean allows(String toolName) {
        if (state == State.SAFE || state == State.FORCED_RELEASED) return true;
        return toolName != null && READ_ONLY_TOOLS.contains(toolName);
    }

    boolean isRestricted() {
        return state != State.SAFE && state != State.FORCED_RELEASED;
    }

    /**
     * Step one of the forced release: shows the risk and creates a one-time phrase bound to
     * the project, the quarantine version and the local user. Returns the text to display.
     */
    String beginForcedRelease(String project, String localUser) {
        if (state != State.QUARANTINED_UNKNOWN && state != State.QUIESCING) {
            return "工作区当前不处于隔离状态，无需解除。";
        }
        String nonce = UUID.randomUUID().toString();
        String phrase = "unlock-" + sha256Prefix(project + "\0" + localUser + "\0" + quarantineVersion + "\0" + nonce, 12);
        confirmationPhrase = phrase;
        phraseExpiresAtMillis = System.currentTimeMillis() + PHRASE_TTL_MILLIS;
        return "工作区仍处于未知状态（" + (reason == null ? "取消超时" : reason) + "）。\n"
                + "隔离版本：" + quarantineVersion + "；项目：" + project + "\n"
                + "风险：未知旧进程可能仍在执行，新写入可能与它竞争；外部副作用也无法确认。\n"
                + "如确需解除，请在 60 秒内完整输入以下短语（模型或 auto 模式不能代替你确认）：\n  " + phrase;
    }

    /** Step two: only the exact, unexpired phrase typed by the local user releases the gate. */
    boolean confirmForcedRelease(String input) {
        String phrase = confirmationPhrase;
        if (phrase == null || phrase.isBlank() || System.currentTimeMillis() > phraseExpiresAtMillis) return false;
        if (!phrase.equals(input == null ? null : input.trim())) return false;
        confirmationPhrase = null;
        phraseExpiresAtMillis = 0L;
        state = State.FORCED_RELEASED;
        return true;
    }

    String statusLine() {
        return switch (state) {
            case SAFE -> lastVerification == null
                    ? "SAFE（未发现待核验的取消状态）"
                    : "SAFE（" + lastVerification + "）";
            case QUIESCING -> "QUIESCING（正在收尾）";
            case QUARANTINED_UNKNOWN -> "QUARANTINED_UNKNOWN（禁止写入与命令，仅允许只读核验）";
            case FORCED_RELEASED -> "FORCED_RELEASED（本地用户已强制解除，未经过监管域核验）";
        };
    }

    private static String sha256Prefix(String value, int length) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte item : bytes) {
                out.append(String.format("%02x", item & 0xff));
                if (out.length() >= length) break;
            }
            return out.substring(0, Math.min(length, out.length()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }
}

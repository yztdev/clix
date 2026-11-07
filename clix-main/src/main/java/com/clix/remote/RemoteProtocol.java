package com.clix.remote;

import java.util.UUID;

/** Shared wire-level invariants for the reusable remote session protocol. */
final class RemoteProtocol {
    static final int VERSION = 2;

    /** Absolute, non-extendable budget from the first Ctrl+C (design doc "取消时间预算"). */
    static final long CANCEL_DEADLINE_MS = 10_000L;
    /** The server must terminalize the old agent run before this, leaving ~2s for the client. */
    static final long SERVER_TERMINALIZATION_MS = 8_000L;
    static final long NETWORK_RESERVE_MS = 2_000L;
    /** Second Ctrl+C on an idle prompt exits only within this window. */
    static final long EXIT_ARM_WINDOW_MS = 5_000L;
    /** Bounded memory for terminal turn ids used to reject late events. */
    static final int TERMINAL_TURN_HISTORY = 64;

    /** The server never uses a budget later than the client's absolute deadline. */
    static long serverTerminalizationMillis(long clientCancelTimeoutMs) {
        if (clientCancelTimeoutMs <= 0) return SERVER_TERMINALIZATION_MS;
        long clientBudget = Math.max(0L, clientCancelTimeoutMs - NETWORK_RESERVE_MS);
        return Math.min(SERVER_TERMINALIZATION_MS, clientBudget);
    }

    private RemoteProtocol() { }

    static boolean isTurnId(String value) {
        if (value == null || value.length() > 64) return false;
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}

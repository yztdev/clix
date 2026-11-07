package com.clix.remote;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Tracks tool requests for one client connection; duplicate requests never start again. */
public final class RemoteToolRequestTracker {
    public enum State { RECEIVED, EXECUTING, COMPLETED, NOT_EXECUTED, OUTCOME_UNKNOWN }
    public enum Receipt { NEW, DUPLICATE, CONFLICT, CLOSED }

    public record Request(String id, String name, String argumentsJson) {
        public Request {
            if (id == null || id.isBlank() || name == null || name.isBlank() || argumentsJson == null) {
                throw new IllegalArgumentException("工具请求必须包含 ID、名称和参数");
            }
        }
    }

    public record Snapshot(Request request, State state, String result) {}

    private final Map<String, Snapshot> requests = new HashMap<>();
    private boolean closed;
    private String activeTurnId;
    private boolean turnCancelled;

    public synchronized void startTurn(String turnId) {
        if (closed) throw new IllegalStateException("连接已关闭");
        if (!RemoteProtocol.isTurnId(turnId)) throw new IllegalArgumentException("Turn ID 无效");
        activeTurnId = turnId;
        turnCancelled = false;
        requests.clear();
    }

    public synchronized void cancelTurn(String turnId) {
        if (!Objects.equals(activeTurnId, turnId) || turnCancelled) return;
        turnCancelled = true;
        requests.replaceAll((id, request) -> switch (request.state()) {
            case RECEIVED -> new Snapshot(request.request(), State.NOT_EXECUTED, null);
            case EXECUTING -> new Snapshot(request.request(), State.OUTCOME_UNKNOWN, null);
            default -> request;
        });
    }

    public synchronized boolean isTurnActive() {
        return !closed && activeTurnId != null && !turnCancelled;
    }

    /** A repeated ID with different contents is a protocol error, not a new request. */
    public synchronized Receipt receive(Request request) {
        Objects.requireNonNull(request, "request");
        if (closed || activeTurnId == null || turnCancelled) return Receipt.CLOSED;
        Snapshot previous = requests.get(request.id());
        if (previous != null) {
            return previous.request().equals(request) ? Receipt.DUPLICATE : Receipt.CONFLICT;
        }
        requests.put(request.id(), new Snapshot(request, State.RECEIVED, null));
        return Receipt.NEW;
    }

    /** Only one caller can transition a received request into execution. */
    public synchronized boolean start(String id) {
        Snapshot previous = requests.get(id);
        if (closed || turnCancelled || previous == null || previous.state() != State.RECEIVED) return false;
        requests.put(id, new Snapshot(previous.request(), State.EXECUTING, null));
        return true;
    }

    public synchronized boolean complete(String id, String result) {
        Snapshot previous = requests.get(id);
        if (previous == null || previous.state() != State.EXECUTING) return false;
        requests.put(id, new Snapshot(previous.request(), State.COMPLETED, Objects.requireNonNull(result)));
        return true;
    }

    public synchronized boolean markOutcomeUnknown(String id) {
        Snapshot previous = requests.get(id);
        if (previous == null || previous.state() != State.EXECUTING) return false;
        requests.put(id, new Snapshot(previous.request(), State.OUTCOME_UNKNOWN, null));
        return true;
    }

    public synchronized Snapshot get(String id) {
        return requests.get(id);
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    /** Closing prevents new work and records unfinished work as uncertain. */
    public synchronized void disconnect() {
        closed = true;
        requests.replaceAll((id, request) -> switch (request.state()) {
            case RECEIVED -> new Snapshot(request.request(), State.NOT_EXECUTED, null);
            case EXECUTING -> new Snapshot(request.request(), State.OUTCOME_UNKNOWN, null);
            default -> request;
        });
    }
}

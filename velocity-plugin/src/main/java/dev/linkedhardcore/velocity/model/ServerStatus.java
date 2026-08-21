package dev.linkedhardcore.velocity.model;

import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the lifecycle state of a single backend server.
 *
 * <p>State transitions are explicit and validated: illegal transitions throw
 * {@link IllegalStateException} rather than silently corrupting the model. This
 * is the "explicit state machine" the project deliberately favours over loose
 * boolean flags.
 *
 * <p>Threading: Velocity event handlers may fire on arbitrary threads. All
 * transitions are atomic via {@link AtomicReference}.
 */
public final class ServerStatus {

    private final String serverId;
    private final AtomicReference<ServerState> state;

    public ServerStatus(String serverId, ServerState initialState) {
        this.serverId = serverId;
        this.state = new AtomicReference<>(initialState);
    }

    public String serverId() {
        return serverId;
    }

    public ServerState state() {
        return state.get();
    }

    public boolean is(ServerState expected) {
        return state.get() == expected;
    }

    /**
     * Atomically transitions to {@code next}, validating the transition is legal.
     *
     * @return {@code true} if the transition happened, {@code false} if the state
     *         was already {@code next} (idempotent re-announcement, e.g. a retried
     *         RESET_COMPLETE).
     * @throws IllegalStateException if the transition is not allowed.
     */
    public boolean transition(ServerState next, Logger logger) {
        while (true) {
            ServerState current = state.get();
            if (current == next) {
                return false;
            }
            if (!isLegal(current, next)) {
                throw new IllegalStateException(
                    "Illegal server state transition for '" + serverId + "': " + current + " -> " + next);
            }
            if (state.compareAndSet(current, next)) {
                logger.info("[linkedhardcore] Server '{}': {} -> {}", serverId, current.wireName(), next.wireName());
                return true;
            }
        }
    }

    /**
     * Attempts a legal transition without throwing when another event won a
     * concurrent transition race or the transition is no longer appropriate.
     */
    public boolean tryTransition(ServerState next, Logger logger) {
        while (true) {
            ServerState current = state.get();
            if (current == next || !isLegal(current, next)) {
                return false;
            }
            if (state.compareAndSet(current, next)) {
                logger.info("[linkedhardcore] Server '{}': {} -> {}", serverId, current.wireName(), next.wireName());
                return true;
            }
        }
    }

    private static boolean isLegal(ServerState from, ServerState to) {
        return switch (from) {
            case UNAVAILABLE -> to == ServerState.READY || to == ServerState.LIVE || to == ServerState.RESETTING;
            case READY -> to == ServerState.LIVE || to == ServerState.RESETTING || to == ServerState.UNAVAILABLE;
            case LIVE -> to == ServerState.TRANSFERRING || to == ServerState.READY || to == ServerState.RESETTING
                || to == ServerState.UNAVAILABLE;
            case TRANSFERRING -> to == ServerState.RESETTING || to == ServerState.LIVE || to == ServerState.READY
                || to == ServerState.UNAVAILABLE;
            case RESETTING -> to == ServerState.READY || to == ServerState.UNAVAILABLE;
        };
    }

    @Override
    public String toString() {
        return serverId + "=" + state.get().wireName();
    }
}

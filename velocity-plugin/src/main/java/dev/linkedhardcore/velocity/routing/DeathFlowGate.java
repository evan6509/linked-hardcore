package dev.linkedhardcore.velocity.routing;

import java.util.concurrent.atomic.AtomicBoolean;

/** Allows only the first death notification to start a run-over flow. */
public final class DeathFlowGate {
    private final AtomicBoolean active = new AtomicBoolean();

    public boolean begin() {
        return active.compareAndSet(false, true);
    }

    public void complete() {
        active.set(false);
    }

    public boolean isActive() {
        return active.get();
    }
}

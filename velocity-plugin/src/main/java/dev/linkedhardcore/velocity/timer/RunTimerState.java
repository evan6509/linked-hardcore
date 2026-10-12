package dev.linkedhardcore.velocity.timer;

import java.util.function.LongSupplier;

/** One shared run, measured with a monotonic clock only while players are online. */
public final class RunTimerState {
    private final LongSupplier clockMillis;
    private long elapsedMillis;
    private Long runningSince;
    private boolean ended;

    public RunTimerState() {
        this(() -> System.nanoTime() / 1_000_000L);
    }

    RunTimerState(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    public synchronized void setPlayersOnline(boolean online) {
        if (online && !ended) {
            if (runningSince == null) {
                runningSince = clockMillis.getAsLong();
            }
        } else if (runningSince != null) {
            elapsedMillis = snapshot().elapsedMillis();
            runningSince = null;
        }
    }

    public synchronized void endRun() {
        setPlayersOnline(false);
        ended = true;
    }

    public synchronized void startNewRun(boolean playersOnline) {
        elapsedMillis = 0;
        runningSince = null;
        ended = false;
        setPlayersOnline(playersOnline);
    }

    /** Loaded runs stay paused until a player connects; downtime is never counted. */
    public synchronized void restore(Snapshot snapshot) {
        if (snapshot == null || snapshot.elapsedMillis() < 0) {
            throw new IllegalArgumentException("Invalid run timer snapshot");
        }
        elapsedMillis = snapshot.elapsedMillis();
        ended = snapshot.ended();
        runningSince = null;
    }

    public synchronized Snapshot snapshot() {
        long activeMillis = runningSince == null ? 0 : Math.max(0, clockMillis.getAsLong() - runningSince);
        return new Snapshot(elapsedMillis + activeMillis, ended);
    }

    public record Snapshot(long elapsedMillis, boolean ended) {
    }
}

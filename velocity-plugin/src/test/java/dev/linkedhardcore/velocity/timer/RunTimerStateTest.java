package dev.linkedhardcore.velocity.timer;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunTimerStateTest {
    private final AtomicLong clock = new AtomicLong(1000);
    private final RunTimerState state = new RunTimerState(clock::get);

    @Test
    void countsSharedPlayTimeOnceAndExcludesOfflineTime() {
        clock.addAndGet(5000);
        assertEquals(0, state.snapshot().elapsedMillis());

        state.setPlayersOnline(true);
        clock.addAndGet(1234);
        state.setPlayersOnline(true); // Another player joining does not restart the clock.
        clock.addAndGet(2000);
        state.setPlayersOnline(false);
        clock.addAndGet(60_000);
        assertEquals(3234, state.snapshot().elapsedMillis());

        state.setPlayersOnline(true);
        clock.addAndGet(500);
        assertEquals(3734, state.snapshot().elapsedMillis());
    }

    @Test
    void freezesOnDeathUntilANewRunStarts() {
        state.setPlayersOnline(true);
        clock.addAndGet(3210);
        state.endRun();
        state.setPlayersOnline(true); // Reconnecting during a transfer cannot resume a dead run.
        clock.addAndGet(60_000);
        assertEquals(3210, state.snapshot().elapsedMillis());
        assertTrue(state.snapshot().ended());

        state.startNewRun(true);
        assertEquals(0, state.snapshot().elapsedMillis());
        assertFalse(state.snapshot().ended());
        clock.addAndGet(1000);
        assertEquals(1000, state.snapshot().elapsedMillis());
    }

    @Test
    void restoresWithoutCountingProxyDowntimeOrLosingEndedState() {
        state.restore(new RunTimerState.Snapshot(2345, false));
        clock.addAndGet(86_400_000);
        assertEquals(2345, state.snapshot().elapsedMillis());
        state.setPlayersOnline(true);
        clock.addAndGet(100);
        assertEquals(2445, state.snapshot().elapsedMillis());

        state.restore(new RunTimerState.Snapshot(3456, true));
        state.setPlayersOnline(true);
        clock.addAndGet(1000);
        assertEquals(new RunTimerState.Snapshot(3456, true), state.snapshot());
    }
    @Test
    void replayedCommitDoesNotResetTheNewRunTimerAfterProxyRecovery() {
        state.startNewRun("run-id", true);
        clock.addAndGet(2345);
        var saved = state.snapshot();
        state.restore(saved);
        clock.addAndGet(1000);
        state.startNewRun("run-id", true);
        assertEquals(2345, state.snapshot().elapsedMillis());
        clock.addAndGet(500);
        assertEquals(2845, state.snapshot().elapsedMillis());
        state.startNewRun("different-run", true);
        assertEquals(0, state.snapshot().elapsedMillis());
    }

}

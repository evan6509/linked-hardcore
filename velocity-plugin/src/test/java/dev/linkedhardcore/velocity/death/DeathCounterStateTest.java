package dev.linkedhardcore.velocity.death;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeathCounterStateTest {

    @Test
    void incrementsOnlyThePlayerWhoseDeathTriggeredTheRun() {
        DeathCounterState state = new DeathCounterState();
        UUID triggerPlayer = UUID.randomUUID();
        UUID otherPlayer = UUID.randomUUID();

        assertEquals(1, state.recordDeath(triggerPlayer, "Trigger").deaths());
        assertEquals(1, state.count(triggerPlayer));
        assertEquals(0, state.count(otherPlayer));
    }
}

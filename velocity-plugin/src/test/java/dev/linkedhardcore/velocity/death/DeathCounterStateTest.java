package dev.linkedhardcore.velocity.death;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeathCounterStateTest {

    @Test
    void retainsTotalsAcrossNameChangesAndIncrementsOnlyOnDeath() {
        DeathCounterState state = new DeathCounterState();
        UUID player = UUID.randomUUID();

        state.ensurePlayer(player, "old-name");
        state.recordDeath(player, "old-name");
        state.ensurePlayer(player, "new-name");

        assertEquals(1, state.count(player));
        assertEquals("new-name", state.snapshot().getFirst().playerName());
        assertEquals(1, state.snapshot().getFirst().deaths());
    }
}

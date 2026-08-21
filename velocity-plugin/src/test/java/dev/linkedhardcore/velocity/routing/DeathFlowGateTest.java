package dev.linkedhardcore.velocity.routing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathFlowGateTest {
    @Test
    void acceptsOnlyOneDeathPerRun() {
        DeathFlowGate gate = new DeathFlowGate();
        assertTrue(gate.begin());
        assertFalse(gate.begin());
        gate.complete();
        assertTrue(gate.begin());
    }
}

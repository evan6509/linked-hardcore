package dev.linkedhardcore.velocity.model;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerStatusTest {

    @Test
    void followsTheSafeLifecycle() {
        ServerStatus status = new ServerStatus("a", ServerState.UNAVAILABLE);

        assertTrue(status.transition(ServerState.READY, LoggerFactory.getLogger("test")));
        assertTrue(status.transition(ServerState.LIVE, LoggerFactory.getLogger("test")));
        assertTrue(status.transition(ServerState.TRANSFERRING, LoggerFactory.getLogger("test")));
        assertTrue(status.transition(ServerState.RESETTING, LoggerFactory.getLogger("test")));
        assertTrue(status.transition(ServerState.READY, LoggerFactory.getLogger("test")));
        assertEquals(ServerState.READY, status.state());
    }

    @Test
    void rejectsRoutingTransitionsFromUnavailableOrReady() {
        ServerStatus status = new ServerStatus("a", ServerState.UNAVAILABLE);

        assertThrows(IllegalStateException.class,
            () -> status.transition(ServerState.TRANSFERRING, LoggerFactory.getLogger("test")));
        assertFalse(status.tryTransition(ServerState.TRANSFERRING, LoggerFactory.getLogger("test")));
        assertEquals(ServerState.UNAVAILABLE, status.state());
    }
}

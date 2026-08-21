package dev.linkedhardcore.velocity.death;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeathCounterStoreTest {

    @Test
    void persistsCountsAcrossStateReload(@TempDir Path tempDir) {
        Path file = tempDir.resolve("death-counters.json");
        DeathCounterStore store = new DeathCounterStore(file, LoggerFactory.getLogger("test"));
        UUID player = UUID.randomUUID();

        DeathCounterState first = new DeathCounterState();
        first.recordDeath(player, "Player");
        first.recordDeath(player, "Player");
        store.save(first);

        DeathCounterState second = new DeathCounterState();
        store.loadInto(second);
        assertEquals(2, second.count(player));
    }
}

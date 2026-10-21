package dev.linkedhardcore.fabric.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DeathOutboxTest {
    @TempDir Path directory;

    @Test
    void preparationIsIdempotentAndAckRemovalSurvivesReload() {
        Path file = directory.resolve("outbox.json");
        UUID player = UUID.randomUUID();
        DeathOutbox outbox = new DeathOutbox(file, "a");
        var event = outbox.prepare(player, "generation");
        assertEquals(event, outbox.prepare(player, "generation"));
        outbox = new DeathOutbox(file, "a");
        assertEquals(List.of(event), outbox.pending());
        assertFalse(outbox.acknowledge(player, "other-generation"));
        assertFalse(outbox.acknowledge(UUID.randomUUID(), "generation"));
        assertEquals(List.of(event), new DeathOutbox(file, "a").pending());
        assertTrue(outbox.acknowledge(player, "generation"));
        assertTrue(new DeathOutbox(file, "a").pending().isEmpty());
    }

    @Test
    void malformedLegacyAndForeignStateFailClosed() throws Exception {
        Path file = directory.resolve("outbox.json");
        for (String invalid : List.of("null", "{}", "[]", "{broken",
            "{\"schemaVersion\":1,\"protocolVersion\":3,\"serverId\":\"a\",\"deaths\":[{\"playerUuid\":\"" + UUID.randomUUID() + "\"}]}")) {
            Files.writeString(file, invalid);
            assertThrows(IllegalStateException.class, () -> new DeathOutbox(file, "a"));
            assertEquals(invalid, Files.readString(file));
        }
        Files.delete(file);
        new DeathOutbox(file, "a").prepare(UUID.randomUUID(), "generation");
        assertThrows(IllegalStateException.class, () -> new DeathOutbox(file, "b"));
    }

    @Test
    void failedAckIoKeepsTheCommittedEventRetryable() throws Exception {
        Path file = directory.resolve("outbox.json");
        DeathOutbox outbox = new DeathOutbox(file, "a");
        UUID player = UUID.randomUUID();
        var event = outbox.prepare(player, "generation");
        Path backup = directory.resolve("committed.json");
        Files.move(file, backup);
        Files.createDirectory(file);
        Files.writeString(file.resolve("blocker"), "fixture");
        assertThrows(IllegalStateException.class, () -> outbox.acknowledge(player, "generation"));
        Files.delete(file.resolve("blocker"));
        Files.delete(file);
        Files.move(backup, file);
        assertEquals(List.of(event), new DeathOutbox(file, "a").pending());
    }
    @Test
    void capacityFailureMustNotCommitAnUnreadableJournal() {
        Path file = directory.resolve("outbox.json");
        DeathOutbox outbox = new DeathOutbox(file, "a");
        String generation = "界".repeat(32_767);
        for (int i = 0; i < 10; i++) outbox.prepare(UUID.randomUUID(), generation);
        var committed = outbox.pending();
        assertThrows(IllegalStateException.class, () -> outbox.prepare(UUID.randomUUID(), generation));
        assertEquals(committed, new DeathOutbox(file, "a").pending(),
            "A refused new intent must preserve every previously committed report");
    }

}

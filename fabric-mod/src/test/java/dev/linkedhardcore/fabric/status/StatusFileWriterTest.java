package dev.linkedhardcore.fabric.status;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StatusFileWriterTest {
    @TempDir Path directory;

    @Test
    void unknownWorldIsPreservedAsUsedAcrossBootsAndLogout() throws Exception {
        StatusFileWriter writer = new StatusFileWriter("a", directory);
        writer.write(() -> 0);
        assertTrue(status().get("worldUsed").getAsBoolean());
        String generation = status().get("generationId").getAsString();
        String boot = status().get("bootId").getAsString();
        new StatusFileWriter("a", directory).write(() -> 0);
        assertEquals("live", status().get("state").getAsString());
        assertEquals(generation, status().get("generationId").getAsString());
        assertNotEquals(boot, status().get("bootId").getAsString());
    }

    @Test
    void firstJoinPersistsWorldUseAndDisconnectCannotRestorePreparation() throws Exception {
        preparedWorld();
        StatusFileWriter writer = new StatusFileWriter("a", directory);
        writer.write(() -> 0);
        assertEquals("ready", status().get("state").getAsString());
        writer.markWorldUsed();
        writer.write(() -> 1);
        writer.write(() -> 0);
        assertEquals("live", status().get("state").getAsString());
        assertTrue(JsonParser.parseString(Files.readString(directory.resolve("world-generation.json")))
            .getAsJsonObject().get("used").getAsBoolean());
        new StatusFileWriter("a", directory).write(() -> 0);
        assertEquals("live", status().get("state").getAsString());
    }

    @Test
    void concurrentWritesCaptureAndPublishInOrderWithoutSharedTemporaryFiles() throws Exception {
        preparedWorld();
        StatusFileWriter writer = new StatusFileWriter("a", directory);
        AtomicInteger captured = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var writes = new ArrayList<Future<?>>();
            for (int i = 0; i < 100; i++) writes.add(executor.submit(() -> writer.write(captured::incrementAndGet)));
            for (Future<?> write : writes) write.get();
        }
        assertEquals(100, status().get("playerCount").getAsInt());
        assertTrue(status().get("worldUsed").getAsBoolean());
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void pendingResetNeverFabricatesAnEmptyPlayerCount() throws Exception {
        preparedWorld();
        Files.writeString(directory.resolve("reset.request.json"), "{}");
        new StatusFileWriter("a", directory).write(() -> 3);
        assertEquals("resetting", status().get("state").getAsString());
        assertEquals(3, status().get("playerCount").getAsInt());
    }

    private JsonObject status() throws Exception {
        return JsonParser.parseString(Files.readString(directory.resolve("status.json"))).getAsJsonObject();
    }

    private void preparedWorld() throws Exception {
        Files.writeString(directory.resolve("world-generation.json"),
            "{\"schemaVersion\":2,\"serverId\":\"a\",\"generationId\":\"new-world\",\"used\":false}");
    }
}

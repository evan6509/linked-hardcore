package dev.linkedhardcore.fabric.status;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.linkedhardcore.fabric.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.time.Instant;
import java.util.UUID;
import java.util.function.IntSupplier;

/** Publishes ordered lifecycle snapshots and remembers world use outside the world directory. */
public final class StatusFileWriter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LoggerFactory.getLogger("linkedhardcore");
    private final String serverId;
    private final Path directory;
    private final String bootId = UUID.randomUUID().toString();
    private WorldGeneration world;

    public StatusFileWriter(ModConfig config) {
        this(config.serverId(), ModConfig.CONFIG_DIR);
    }

    public StatusFileWriter(String serverId, Path directory) {
        this.serverId = serverId;
        this.directory = directory;
    }

    /** Unknown/legacy worlds are preserved and classified as used, never as prepared. */
    private void initialize() throws IOException {
        if (world != null) return;
        Path path = directory.resolve("world-generation.json");
        if (Files.exists(path)) {
            try {
                world = GSON.fromJson(Files.readString(path), WorldGeneration.class);
                if (world == null || world.schemaVersion != 2 || !serverId.equals(world.serverId)
                    || world.generationId == null || world.generationId.isBlank() || world.used == null) {
                    world = null;
                    throw new IOException("Invalid world-generation.json; refusing to advertise readiness");
                }
            } catch (RuntimeException e) {
                throw new IOException("Invalid world-generation.json", e);
            }
        } else {
            world = new WorldGeneration(2, serverId, UUID.randomUUID().toString(), null, true);
            atomicWrite(path, GSON.toJson(world));
            LOGGER.warn("[linkedhardcore] Existing world has no preparation identity; preserving it as a used world");
        }
    }

    /** Called on the server thread before the first arriving player enters play. */
    public synchronized void markWorldUsed() {
        try {
            initialize();
            // Keep the in-memory latch set even if persistence fails; publication retries it.
            world = new WorldGeneration(2, serverId, world.generationId, world.completedResetId, true);
            atomicWrite(directory.resolve("world-generation.json"), GSON.toJson(world));
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist world use", e);
        }
    }

    /** Capture the player count inside the publication lock, never before queuing a write. */
    public synchronized void write(IntSupplier onlinePlayers) {
        try {
            initialize();
            int playerCount = onlinePlayers.getAsInt();
            if (playerCount > 0 && !world.used) markWorldUsed();
            // Retry a failed use checkpoint before publishing any heartbeat.
            atomicWrite(directory.resolve("world-generation.json"), GSON.toJson(world));
            boolean resetPending = Files.exists(directory.resolve("reset.request.json"));
            String state = resetPending ? "resetting" : world.used ? "live" : "ready";
            Status body = new Status(2, serverId, state, playerCount, Instant.now().toString(),
                bootId, world.generationId, world.completedResetId, world.used);
            atomicWrite(directory.resolve("status.json"), GSON.toJson(body));
        } catch (IOException e) {
            LOGGER.warn("[linkedhardcore] Failed to publish status: {}", e.toString());
        }
    }

    private static void atomicWrite(Path path, String json) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record WorldGeneration(int schemaVersion, String serverId, String generationId,
                                   String completedResetId, Boolean used) { }
    private record Status(int schemaVersion, String serverId, String state, int playerCount, String updatedAt,
                          String bootId, String generationId, String completedResetId, boolean worldUsed) { }
}

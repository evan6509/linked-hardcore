package dev.linkedhardcore.velocity.status;

import com.google.gson.Gson;
import dev.linkedhardcore.velocity.config.PluginConfig;
import dev.linkedhardcore.velocity.reset.ResetRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** A heartbeat is useful for routing only when it agrees with the durable world identity. */
public record BackendSnapshot(int schemaVersion, String serverId, String state, Integer playerCount,
                              String updatedAt, String bootId, String generationId,
                              String completedResetId, Boolean worldUsed) {
    private static final Gson GSON = new Gson();

    public static BackendSnapshot read(PluginConfig.BackendServer backend, int staleSeconds) {
        return read(backend, staleSeconds, true);
    }

    /** A reserved in-flight destination may be between its durable use write and heartbeat publication. */
    public static BackendSnapshot readContinuation(PluginConfig.BackendServer backend, int staleSeconds) {
        return read(backend, staleSeconds, false);
    }

    private static BackendSnapshot read(PluginConfig.BackendServer backend, int staleSeconds, boolean requireUseAgreement) {
        try {
            Path path = Path.of(backend.statusFile());
            BackendSnapshot snapshot = GSON.fromJson(Files.readString(path), BackendSnapshot.class);
            WorldGeneration world = GSON.fromJson(Files.readString(path.resolveSibling("world-generation.json")),
                WorldGeneration.class);
            Instant oldest = Instant.now().minusSeconds(staleSeconds);
            if (snapshot == null || world == null || snapshot.schemaVersion != 2 || world.schemaVersion != 2
                || !backend.serverId().equals(snapshot.serverId) || !backend.serverId().equals(world.serverId)
                || snapshot.playerCount == null || snapshot.playerCount < 0 || snapshot.worldUsed == null
                || snapshot.bootId == null || snapshot.bootId.isBlank()
                || snapshot.generationId == null || snapshot.generationId.isBlank()
                || !snapshot.generationId.equals(world.generationId)
                || !java.util.Objects.equals(snapshot.completedResetId, world.completedResetId)
                || world.used == null || (requireUseAgreement && !snapshot.worldUsed.equals(world.used))
                || !("ready".equals(snapshot.state) || "live".equals(snapshot.state) || "resetting".equals(snapshot.state))
                || Files.getLastModifiedTime(path).toInstant().isBefore(oldest)
                || Instant.parse(snapshot.updatedAt).isBefore(oldest)
                || Instant.parse(snapshot.updatedAt).isAfter(Instant.now().plusSeconds(5))) {
                return null;
            }
            return snapshot;
        } catch (Exception ignored) {
            return null; // Missing, legacy, partial or malformed evidence is never proof of freshness.
        }
    }

    /** Reset scheduling needs the old identity even when its last heartbeat has expired. */
    public static BackendSnapshot readIdentity(PluginConfig.BackendServer backend) {
        try {
            Path path = Path.of(backend.statusFile());
            BackendSnapshot snapshot = GSON.fromJson(Files.readString(path), BackendSnapshot.class);
            WorldGeneration world = GSON.fromJson(Files.readString(path.resolveSibling("world-generation.json")),
                WorldGeneration.class);
            if (snapshot == null || world == null || snapshot.schemaVersion != 2 || world.schemaVersion != 2
                || !backend.serverId().equals(snapshot.serverId) || !backend.serverId().equals(world.serverId)
                || snapshot.bootId == null || snapshot.bootId.isBlank() || snapshot.generationId == null
                || snapshot.generationId.isBlank() || !snapshot.generationId.equals(world.generationId)) return null;
            return snapshot;
        } catch (Exception ignored) {
            return null;
        }
    }

    public boolean prepared() {
        return "ready".equals(state) && playerCount == 0 && Boolean.FALSE.equals(worldUsed);
    }

    public boolean completes(ResetRequest request) {
        return prepared() && generationId.equals(request.requestId())
            && generationId.equals(completedResetId)
            && !generationId.equals(request.previousGenerationId())
            && !bootId.equals(request.previousBootId());
    }

    private record WorldGeneration(int schemaVersion, String serverId, String generationId,
                                   String completedResetId, Boolean used) { }
}

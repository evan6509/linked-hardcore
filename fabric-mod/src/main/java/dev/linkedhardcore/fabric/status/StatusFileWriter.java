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

/**
 * Writes {@code config/linkedhardcore/status.json} — the single file an external
 * reset agent ("Sisyphus") and the proxy poll to learn this server's state.
 *
 * <p>Schema (see {@code docs/RESET_CONTRACT.md}):
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "serverId": "a",
 *   "state": "ready" | "live" | "resetting",
 *   "playerCount": 0,
 *   "updatedAt": "2026-08-12T10:00:00Z"
 * }
 * }</pre>
 *
 * <p>This mod never touches world/playerdata files; it only reports state.
 */
public final class StatusFileWriter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Logger LOGGER = LoggerFactory.getLogger("linkedhardcore");

    private final ModConfig config;
    private final Path statusFile;

    public StatusFileWriter(ModConfig config) {
        this.config = config;
        this.statusFile = ModConfig.CONFIG_DIR.resolve("status.json");
    }

    /** Writes a status snapshot. Failures are logged, never fatal. */
    public void write(String state, int playerCount) {
        Status body = new Status(1, config.serverId(), state, playerCount, Instant.now().toString());
        try {
            Files.createDirectories(statusFile.getParent());
            Path temporary = statusFile.resolveSibling(statusFile.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(body), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, statusFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, statusFile, StandardCopyOption.REPLACE_EXISTING);
            }
            LOGGER.debug("[linkedhardcore] status.json -> {}", state);
        } catch (IOException e) {
            LOGGER.warn("[linkedhardcore] Failed to write {}: {}", statusFile, e.toString());
        }
    }

    private record Status(int schemaVersion, String serverId, String state, int playerCount, String updatedAt) {
    }
}

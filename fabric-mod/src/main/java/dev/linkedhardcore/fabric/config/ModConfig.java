package dev.linkedhardcore.fabric.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/**
 * Mod-side configuration, loaded from {@code config/linkedhardcore/config.json}.
 *
 * <p>All players form a single linked life pool — there are no groups. When any
 * player dies, the whole server's population is spectated, counted down, and
 * transferred by the proxy.
 *
 * <p>Example:
 * <pre>{@code
 * {
 *   "serverId": "a",
 *   "ackTimeoutSeconds": 10,
 *   "transferCountdownSeconds": 5
 * }
 * }</pre>
 */
public final class ModConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("linkedhardcore");
    public static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");

    private final String serverId;
    private final int ackTimeoutSeconds;
    private final int transferCountdownSeconds;

    public ModConfig(String serverId, int ackTimeoutSeconds, int transferCountdownSeconds) {
        this.serverId = serverId;
        this.ackTimeoutSeconds = ackTimeoutSeconds;
        this.transferCountdownSeconds = transferCountdownSeconds;
    }

    public String serverId() {
        return serverId;
    }

    public int ackTimeoutSeconds() {
        return ackTimeoutSeconds;
    }

    /** Seconds the on-screen "transferring" countdown runs before the proxy moves the group. */
    public int transferCountdownSeconds() {
        return transferCountdownSeconds;
    }

    /** Loads config, throwing with a clear message if missing or malformed. */
    public static ModConfig load() {
        if (!Files.isRegularFile(CONFIG_FILE)) {
            writeDefaultConfig();
            return new ModConfig("change-me", 10, 5);
        }
        try (Reader reader = Files.newBufferedReader(CONFIG_FILE)) {
            JsonConfig raw = GSON.fromJson(reader, JsonConfig.class);
            if (raw == null) {
                throw new JsonParseException("empty file");
            }
            String serverId = raw.serverId != null ? raw.serverId : "change-me";
            int ackTimeout = raw.ackTimeoutSeconds != null ? raw.ackTimeoutSeconds : 10;
            int countdown = raw.transferCountdownSeconds != null ? raw.transferCountdownSeconds : 5;
            if (serverId.isBlank() || ackTimeout <= 0 || countdown < 0) {
                throw new IllegalStateException("serverId must be non-blank, ackTimeoutSeconds must be positive, "
                    + "and transferCountdownSeconds cannot be negative");
            }
            return new ModConfig(serverId, ackTimeout, countdown);
        } catch (IOException | JsonParseException e) {
            throw new IllegalStateException("Malformed mod config " + CONFIG_FILE + ": " + e.getMessage(), e);
        }
    }

    private static void writeDefaultConfig() {
        try {
            Files.createDirectories(CONFIG_DIR);
            Files.writeString(CONFIG_FILE, GSON.toJson(new JsonConfig("change-me", 10, 5)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create mod config " + CONFIG_FILE + ": " + e.getMessage(), e);
        }
    }

    private record JsonConfig(String serverId, Integer ackTimeoutSeconds, Integer transferCountdownSeconds) {
    }
}

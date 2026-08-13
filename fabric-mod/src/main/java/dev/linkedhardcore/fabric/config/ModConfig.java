package dev.linkedhardcore.fabric.config;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

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
            throw new IllegalStateException(
                "Missing config file " + CONFIG_FILE + ". Create it (see README).");
        }
        try (Reader reader = Files.newBufferedReader(CONFIG_FILE)) {
            JsonConfig raw = new Gson().fromJson(reader, JsonConfig.class);
            if (raw == null) {
                throw new JsonParseException("empty file");
            }
            String serverId = raw.serverId != null ? raw.serverId : "unknown";
            int ackTimeout = raw.ackTimeoutSeconds != null ? raw.ackTimeoutSeconds : 10;
            int countdown = raw.transferCountdownSeconds != null ? raw.transferCountdownSeconds : 5;
            return new ModConfig(serverId, ackTimeout, countdown);
        } catch (IOException | JsonParseException e) {
            throw new IllegalStateException("Malformed mod config " + CONFIG_FILE + ": " + e.getMessage(), e);
        }
    }

    private static final class JsonConfig {
        String serverId;
        Integer ackTimeoutSeconds;
        Integer transferCountdownSeconds;
    }
}

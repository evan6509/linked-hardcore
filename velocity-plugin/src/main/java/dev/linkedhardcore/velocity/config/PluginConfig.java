package dev.linkedhardcore.velocity.config;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Proxy-side configuration, loaded from {@code plugins/linkedhardcore/config.json}.
 *
 * <p>All players form a single linked life pool, so there are no group
 * definitions here — just the two backend servers.
 *
 * <p>Example:
 * <pre>{@code
 * {
 *   "backendServers": {
 *     "a": { "serverId": "a", "statusFile": "run/server-a/config/linkedhardcore/status.json" },
 *     "b": { "serverId": "b", "statusFile": "run/server-b/config/linkedhardcore/status.json" }
 *   }
 * }
 * }</pre>
 *
 * <p>The {@code statusFile} path is read (poll) by the proxy to learn a reset
 * finished; the proxy writes a {@code reset.request.json} into the same
 * directory when it wants Sisyphus to wipe that server.
 */
public final class PluginConfig {

    /** velocity server name -> backend config. */
    private final Map<String, BackendServer> backendServers;

    public PluginConfig(Map<String, BackendServer> backendServers) {
        this.backendServers = Map.copyOf(backendServers);
    }

    public Map<String, BackendServer> backendServers() {
        return backendServers;
    }

    /**
     * Reverse-lookup: given a logical {@code serverId} (as reported by a mod in
     * RESET_COMPLETE), return the velocity server name.
     */
    public Optional<String> velocityNameForServerId(String serverId) {
        return backendServers.entrySet().stream()
            .filter(e -> e.getValue().serverId().equals(serverId))
            .map(Map.Entry::getKey)
            .findFirst();
    }

    public static PluginConfig load(Path configFile, Logger logger) throws IOException {
        if (!Files.isRegularFile(configFile)) {
            throw new IOException("Proxy config not found: " + configFile
                + ". Create plugins/linkedhardcore/config.json (see README).");
        }
        try (Reader reader = Files.newBufferedReader(configFile)) {
            JsonConfig raw = new Gson().fromJson(reader, JsonConfig.class);
            if (raw == null) {
                throw new IOException("Empty proxy config: " + configFile);
            }
            Map<String, BackendServer> servers = new LinkedHashMap<>();
            if (raw.backendServers != null) {
                raw.backendServers.forEach((name, b) -> servers.put(name, new BackendServer(name, b.serverId, b.statusFile)));
            }
            logger.info("[linkedhardcore] Loaded proxy config: {} backend server(s)", servers.size());
            return new PluginConfig(servers);
        } catch (JsonParseException e) {
            throw new IOException("Malformed proxy config " + configFile + ": " + e.getMessage(), e);
        }
    }

    /** One backend behind Velocity. */
    public record BackendServer(String name, String serverId, String statusFile) {
        public BackendServer {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(serverId, "serverId");
            Objects.requireNonNull(statusFile, "statusFile");
        }
    }

    private static final class JsonConfig {
        Map<String, JsonBackend> backendServers;
    }

    private static final class JsonBackend {
        String serverId;
        String statusFile;
    }
}

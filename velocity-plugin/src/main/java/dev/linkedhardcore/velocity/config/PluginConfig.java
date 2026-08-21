package dev.linkedhardcore.velocity.config;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Proxy-side configuration, loaded from {@code plugins/linkedhardcore/config.json}.
 *
 * <p>All players form a single linked life pool, so there are no group
 * definitions here — just the backend servers (any number of them).
 *
 * <p>Example:
 * <pre>{@code
 * {
 *   "backendServers": {
 *     "a": { "serverId": "a", "statusFile": "run/server-a/config/linkedhardcore/status.json" },
 *     "b": { "serverId": "b", "statusFile": "run/server-b/config/linkedhardcore/status.json" }
 *   },
 *   "statusPollSeconds": 1,
 *   "statusStaleSeconds": 5
 * }
 * }</pre>
 *
 * <p>The {@code statusFile} path is polled by the proxy to learn when a reset
 * finished (state {@code ready}); the proxy writes a {@code reset.request.json}
 * into the same directory when it wants Sisyphus to wipe that server. A backend
 * is unavailable if that status heartbeat becomes older than
 * {@code statusStaleSeconds}.
 */
public final class PluginConfig {

    private static final int DEFAULT_STATUS_POLL_SECONDS = 1;
    private static final int DEFAULT_STATUS_STALE_SECONDS = 5;

    /** velocity server name -> backend config. */
    private final Map<String, BackendServer> backendServers;
    private final int statusPollSeconds;
    private final int statusStaleSeconds;

    public PluginConfig(Map<String, BackendServer> backendServers, int statusPollSeconds) {
        this(backendServers, statusPollSeconds, DEFAULT_STATUS_STALE_SECONDS);
    }

    public PluginConfig(Map<String, BackendServer> backendServers, int statusPollSeconds, int statusStaleSeconds) {
        this.backendServers = Collections.unmodifiableMap(new LinkedHashMap<>(backendServers));
        this.statusPollSeconds = statusPollSeconds > 0 ? statusPollSeconds : DEFAULT_STATUS_POLL_SECONDS;
        this.statusStaleSeconds = statusStaleSeconds > 0 ? statusStaleSeconds : DEFAULT_STATUS_STALE_SECONDS;
    }

    public Map<String, BackendServer> backendServers() {
        return backendServers;
    }

    /** Seconds between status.json polls (1 by default). */
    public int statusPollSeconds() {
        return statusPollSeconds;
    }

    /** Maximum age of a backend status file before that backend is unavailable. */
    public int statusStaleSeconds() {
        return statusStaleSeconds;
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
            if (raw.backendServers == null || raw.backendServers.isEmpty()) {
                throw new IOException("Proxy config must define at least one backend server: " + configFile);
            }
            for (Map.Entry<String, JsonBackend> entry : raw.backendServers.entrySet()) {
                String name = entry.getKey();
                JsonBackend backend = entry.getValue();
                if (name == null || name.isBlank() || backend == null || backend.serverId == null || backend.serverId.isBlank()
                    || backend.statusFile == null || backend.statusFile.isBlank()) {
                    throw new IOException("Every backend requires a non-blank name, serverId, and statusFile: " + configFile);
                }
                servers.put(name, new BackendServer(name, backend.serverId, backend.statusFile));
            }
            if (servers.values().stream().map(BackendServer::serverId).collect(java.util.stream.Collectors.toSet()).size()
                != servers.size()) {
                throw new IOException("Backend serverId values must be unique: " + configFile);
            }
            logger.info("[linkedhardcore] Loaded proxy config: {} backend server(s)", servers.size());
            int pollSeconds = raw.statusPollSeconds != null && raw.statusPollSeconds > 0
                ? raw.statusPollSeconds : DEFAULT_STATUS_POLL_SECONDS;
            int staleSeconds = raw.statusStaleSeconds != null && raw.statusStaleSeconds > 0
                ? raw.statusStaleSeconds : DEFAULT_STATUS_STALE_SECONDS;
            return new PluginConfig(servers, pollSeconds, staleSeconds);
        } catch (JsonParseException | IllegalArgumentException e) {
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
        Integer statusPollSeconds;
        Integer statusStaleSeconds;
    }

    private static final class JsonBackend {
        String serverId;
        String statusFile;
    }
}

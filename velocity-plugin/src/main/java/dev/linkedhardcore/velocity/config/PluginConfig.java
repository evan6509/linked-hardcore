package dev.linkedhardcore.velocity.config;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dev.linkedhardcore.velocity.model.Group;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Proxy-side configuration, loaded from {@code plugins/linkedhardcore/config.json}.
 *
 * <p>Example:
 * <pre>{@code
 * {
 *   "backendServers": {
 *     "server-a": { "serverId": "a", "statusFile": "run/server-a/config/linkedhardcore/status.json" },
 *     "server-b": { "serverId": "b", "statusFile": "run/server-b/config/linkedhardcore/status.json" }
 *   },
 *   "groups": [
 *     { "id": "pair1", "members": ["<uuid>", "<uuid>"] }
 *   ]
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
    private final List<Group> groups;

    public PluginConfig(Map<String, BackendServer> backendServers, List<Group> groups) {
        this.backendServers = Map.copyOf(backendServers);
        this.groups = List.copyOf(groups);
    }

    public Map<String, BackendServer> backendServers() {
        return backendServers;
    }

    public List<Group> groups() {
        return groups;
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
            List<Group> groups = new ArrayList<>();
            if (raw.groups != null) {
                for (JsonGroup g : raw.groups) {
                    List<UUID> members = new ArrayList<>();
                    if (g.members != null) {
                        for (String uuid : g.members) {
                            try {
                                members.add(UUID.fromString(uuid));
                            } catch (IllegalArgumentException e) {
                                throw new JsonParseException("Invalid member UUID '" + uuid + "' in group '" + g.id + "'", e);
                            }
                        }
                    }
                    groups.add(new Group(g.id, Set.copyOf(members)));
                }
            }
            logger.info("[linkedhardcore] Loaded proxy config: {} backend server(s), {} group(s)",
                servers.size(), groups.size());
            return new PluginConfig(servers, groups);
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
        List<JsonGroup> groups;
    }

    private static final class JsonBackend {
        String serverId;
        String statusFile;
    }

    private static final class JsonGroup {
        String id;
        List<String> members;
    }
}

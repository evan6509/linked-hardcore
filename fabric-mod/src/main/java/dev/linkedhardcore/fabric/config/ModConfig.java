package dev.linkedhardcore.fabric.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mod-side configuration, loaded from {@code config/linkedhardcore/config.json}.
 *
 * <p>Mirrors the proxy's group definitions — the mod must know which players are
 * tracked and which group each belongs to so it can (a) send the correct groupId
 * in PLAYER_DIED and (b) eliminate the right members on GROUP_ELIMINATED. The two
 * configs are intentionally separate to keep the modules decoupled; a future
 * dynamic-pair pass would centralize this on the proxy and push membership down.
 *
 * <p>Example:
 * <pre>{@code
 * {
 *   "serverId": "a",
 *   "ackTimeoutSeconds": 10,
 *   "eliminationMode": "spectator",
 *   "groups": [
 *     { "id": "pair1", "members": ["<uuid>", "<uuid>"] }
 *   ]
 * }
 * }</pre>
 */
public final class ModConfig {

    /** How remaining group members are handled on GROUP_ELIMINATED. */
    public enum EliminationMode {
        /** Set game mode to spectator (vanilla-hardcore-flavoured). Default. */
        SPECTATOR,
        /** Ban the player from the server. */
        BAN
    }

    public static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("linkedhardcore");
    public static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");

    private final String serverId;
    private final int ackTimeoutSeconds;
    private final EliminationMode eliminationMode;
    private final Map<String, Group> groupsById = new ConcurrentHashMap<>();
    private final Map<UUID, Group> groupByMember = new ConcurrentHashMap<>();

    public ModConfig(String serverId, int ackTimeoutSeconds, EliminationMode eliminationMode, List<Group> groups) {
        this.serverId = serverId;
        this.ackTimeoutSeconds = ackTimeoutSeconds;
        this.eliminationMode = eliminationMode;
        for (Group group : groups) {
            groupsById.put(group.id(), group);
            for (UUID member : group.members()) {
                groupByMember.put(member, group);
            }
        }
    }

    public String serverId() {
        return serverId;
    }

    public int ackTimeoutSeconds() {
        return ackTimeoutSeconds;
    }

    public EliminationMode eliminationMode() {
        return eliminationMode;
    }

    /** Returns the group a player belongs to, if any. */
    public Optional<Group> groupOf(UUID playerUuid) {
        return Optional.ofNullable(groupByMember.get(playerUuid));
    }

    public Optional<Group> groupById(String groupId) {
        return Optional.ofNullable(groupsById.get(groupId));
    }

    /** Loads config, falling back to defaults (with a warning) if missing/malformed. */
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
            EliminationMode mode = raw.eliminationMode != null
                ? EliminationMode.valueOf(raw.eliminationMode.toUpperCase()) : EliminationMode.SPECTATOR;

            List<Group> groups = new ArrayList<>();
            if (raw.groups != null) {
                for (JsonGroup g : raw.groups) {
                    List<UUID> members = new ArrayList<>();
                    if (g.members != null) {
                        for (String uuid : g.members) {
                            members.add(UUID.fromString(uuid));
                        }
                    }
                    groups.add(new Group(g.id, List.copyOf(members)));
                }
            }
            return new ModConfig(serverId, ackTimeout, mode, groups);
        } catch (IOException | JsonParseException | IllegalArgumentException e) {
            throw new IllegalStateException("Malformed mod config " + CONFIG_FILE + ": " + e.getMessage(), e);
        }
    }

    /** One shared-life group (same structure as the proxy's config). */
    public record Group(String id, List<UUID> members) {
        public Group {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("group id must not be blank");
            }
        }
    }

    private static final class JsonConfig {
        String serverId;
        Integer ackTimeoutSeconds;
        String eliminationMode;
        List<JsonGroup> groups;
    }

    private static final class JsonGroup {
        String id;
        List<String> members;
    }
}

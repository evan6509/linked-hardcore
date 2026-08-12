package dev.linkedhardcore.velocity.model;

import dev.linkedhardcore.velocity.config.PluginConfig;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of all shared-life groups known to the proxy.
 *
 * <p>Currently backed by {@link PluginConfig} (config-defined pairs), but the
 * interface is intentionally collection-based so a future dynamic source
 * (e.g. a {@code /pair} command) can replace the backing store without touching
 * call sites.
 */
public final class GroupRegistry {

    private final Map<String, Group> byId = new ConcurrentHashMap<>();
    private final Map<UUID, Group> byMember = new ConcurrentHashMap<>();

    public GroupRegistry(PluginConfig config) {
        config.groups().forEach(this::register);
    }

    private void register(Group group) {
        byId.put(group.id(), group);
        for (UUID member : group.members()) {
            byMember.put(member, group);
        }
    }

    public Optional<Group> byId(String groupId) {
        return Optional.ofNullable(byId.get(groupId));
    }

    public Optional<Group> byMember(UUID playerUuid) {
        return Optional.ofNullable(byMember.get(playerUuid));
    }

    public Collection<Group> all() {
        return byId.values();
    }

    public int size() {
        return byId.size();
    }

    public void logSummary(Logger logger) {
        byId.values().forEach(g -> logger.info("[linkedhardcore] Registered group '{}' with {} member(s)", g.id(), g.members().size()));
    }
}

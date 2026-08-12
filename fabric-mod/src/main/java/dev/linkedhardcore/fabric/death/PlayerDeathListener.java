package dev.linkedhardcore.fabric.death;

import dev.linkedhardcore.fabric.config.ModConfig;
import dev.linkedhardcore.fabric.net.ProxyMessenger;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side death hook: on a tracked player's death, notify the proxy via
 * {@code PLAYER_DIED} so it can eliminate the group and orchestrate the transfer.
 *
 * <p>Uses {@code ServerLivingEntityEvents.AFTER_DEATH} (fired post-death, cannot
 * be cancelled) — the correct hook for "a player died, react now". Only players
 * that are members of a configured group are reported; ordinary deaths of
 * untracked players are ignored.
 */
public final class PlayerDeathListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("linkedhardcore");

    private final ModConfig config;
    private final ProxyMessenger messenger;

    public PlayerDeathListener(ModConfig config, ProxyMessenger messenger) {
        this.config = config;
        this.messenger = messenger;
    }

    public void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!(entity instanceof ServerPlayer player)) {
                return; // only players carry group membership
            }
            config.groupOf(player.getUUID()).ifPresentOrElse(
                group -> messenger.sendPlayerDied(player, group.id()),
                () -> LOGGER.debug("[linkedhardcore] Player {} died but is not in any tracked group",
                    player.getName().getString()));
        });
    }
}

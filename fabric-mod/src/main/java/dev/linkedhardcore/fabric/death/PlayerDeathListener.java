package dev.linkedhardcore.fabric.death;

import dev.linkedhardcore.fabric.net.ProxyMessenger;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side death hook: on ANY player's death, notify the proxy via
 * {@code PLAYER_DIED} so it can spectate the whole server's population and
 * orchestrate the transfer.
 *
 * <p>Uses {@code ServerLivingEntityEvents.AFTER_DEATH} (fired post-death, cannot
 * be cancelled) — the correct hook for "a player died, react now". All players
 * are linked into a single life pool, so there is no group membership filter.
 */
public final class PlayerDeathListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("linkedhardcore");

    private final ProxyMessenger messenger;

    public PlayerDeathListener(ProxyMessenger messenger) {
        this.messenger = messenger;
    }

    public void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!(entity instanceof ServerPlayer player)) {
                return; // only players participate in the linked life pool
            }
            messenger.sendPlayerDied(player);
        });
    }
}

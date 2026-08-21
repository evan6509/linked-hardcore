package dev.linkedhardcore.fabric.death;

import dev.linkedhardcore.fabric.net.ProxyMessenger;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerPlayer;

/** Sends exactly one death event for the player whose entity actually died. */
public final class PlayerDeathListener {
    private final ProxyMessenger messenger;

    public PlayerDeathListener(ProxyMessenger messenger) {
        this.messenger = messenger;
    }

    public void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayer player) {
                messenger.sendPlayerDied(player);
            }
        });
    }
}

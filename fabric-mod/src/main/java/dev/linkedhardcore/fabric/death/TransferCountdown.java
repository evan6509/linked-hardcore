package dev.linkedhardcore.fabric.death;

import dev.linkedhardcore.fabric.config.ModConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the on-screen "transferring" countdown for an eliminated group.
 *
 * <p>On {@code PREPARE_TRANSFER} from the proxy, every online member of the group
 * on this server sees a countdown in the action bar. When it reaches zero, the
 * mod tells the proxy via {@code TRANSFER_READY} that it may transfer the group
 * to the other backend. Nobody is set to spectator and nobody is banned.
 *
 * <p>The countdown is tick-based ({@link #tick(MinecraftServer)}), driven by the
 * mod's server-tick hook so it advances even if players move between servers.
 */
public final class TransferCountdown {

    private static final Logger LOGGER = LoggerFactory.getLogger("linkedhardcore");

    private final ModConfig config;
    private final TransferReadyNotifier notifier;

    /** groupId -> remaining seconds (integer, ticked down by {@link #tick}). */
    private final Map<String, Integer> countdowns = new ConcurrentHashMap<>();

    public TransferCountdown(ModConfig config, TransferReadyNotifier notifier) {
        this.config = config;
        this.notifier = notifier;
    }

    /**
     * Starts the countdown for {@code groupId}. Called on the server thread when
     * a {@code PREPARE_TRANSFER} arrives from the proxy.
     */
    public void start(MinecraftServer server, String groupId) {
        ModConfig.Group group = config.groupById(groupId).orElse(null);
        if (group == null) {
            LOGGER.warn("[linkedhardcore] PREPARE_TRANSFER for unknown group '{}' (not in local config)", groupId);
            return;
        }
        countdowns.put(groupId, config.transferCountdownSeconds());
        LOGGER.info("[linkedhardcore] Starting transfer countdown for group '{}' ({}s)",
            groupId, config.transferCountdownSeconds());
    }

    /**
     * Called on every server tick: decrements active countdowns, updates the
     * action-bar text for online members, and fires {@code TRANSFER_READY} when
     * a countdown finishes.
     */
    public void tick(MinecraftServer server) {
        if (countdowns.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Integer> entry : countdowns.entrySet()) {
            String groupId = entry.getKey();
            int remaining = entry.getValue() - 1;
            if (remaining <= 0) {
                countdowns.remove(groupId);
                LOGGER.info("[linkedhardcore] Countdown finished for group '{}'; notifying proxy", groupId);
                notifier.notifyTransferReady(server, groupId);
                continue;
            }
            entry.setValue(remaining);
            showCountdown(server, groupId, remaining);
        }
    }

    private void showCountdown(MinecraftServer server, String groupId, int remaining) {
        ModConfig.Group group = config.groupById(groupId).orElse(null);
        if (group == null) {
            countdowns.remove(groupId);
            return;
        }
        Component text = Component.literal("§cTransferring in §e" + remaining + "§c...");
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (group.members().contains(player.getUUID())) {
                player.connection.send(new ClientboundSetActionBarTextPacket(text));
            }
        }
    }
}

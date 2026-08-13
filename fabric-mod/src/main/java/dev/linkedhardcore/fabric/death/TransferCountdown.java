package dev.linkedhardcore.fabric.death;

import dev.linkedhardcore.fabric.config.ModConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the on-screen "transferring" countdown after a death.
 *
 * <p>On {@code PREPARE_TRANSFER} from the proxy, EVERY online player on this
 * server is set to spectator and sees a countdown in the action bar. When it
 * reaches zero, the mod tells the proxy via {@code TRANSFER_READY} that it may
 * transfer everyone to the other backend. Players respawn into play on arrival
 * at the destination (see {@link #respawnOnJoin}).
 *
 * <p>The countdown is driven by wall-clock time ({@link System#currentTimeMillis})
 * sampled each server tick, so it reflects real seconds regardless of tick rate.
 * Only one countdown can be active at a time (any number of players, one pool).
 */
public final class TransferCountdown {

    private static final Logger LOGGER = LoggerFactory.getLogger("linkedhardcore");

    private final ModConfig config;
    private final TransferReadyNotifier notifier;

    /** Absolute end time (millis) the active countdown finishes at; null when idle. */
    private volatile Long endTime;

    public TransferCountdown(ModConfig config, TransferReadyNotifier notifier) {
        this.config = config;
        this.notifier = notifier;
    }

    /**
     * Starts the countdown: spectates every online player and shows the countdown.
     * Called on the server thread when a {@code PREPARE_TRANSFER} arrives.
     */
    public void start(MinecraftServer server) {
        long end = System.currentTimeMillis() + config.transferCountdownSeconds() * 1000L;
        endTime = end;
        LOGGER.info("[linkedhardcore] Starting transfer countdown ({}s); spectating {} player(s)",
            config.transferCountdownSeconds(), server.getPlayerCount());
        spectateAll(server);
        showCountdown(server, config.transferCountdownSeconds());
    }

    /**
     * Called on every server tick: updates the action-bar text for online players
     * from the remaining wall-clock seconds, and fires {@code TRANSFER_READY} when
     * the countdown finishes.
     */
    public void tick(MinecraftServer server) {
        Long end = endTime;
        if (end == null) {
            return;
        }
        long remainingMillis = end - System.currentTimeMillis();
        if (remainingMillis <= 0) {
            endTime = null;
            LOGGER.info("[linkedhardcore] Countdown finished; notifying proxy");
            notifier.notifyTransferReady(server);
            return;
        }
        showCountdown(server, (int) Math.ceil(remainingMillis / 1000.0));
    }

    /**
     * Respawns a player into normal play (survival) when they join, undoing the
     * spectator state applied before the transfer. Called on the JOIN event.
     */
    public void respawnOnJoin(ServerPlayer player) {
        if (player.gameMode() == GameType.SPECTATOR) {
            player.setGameMode(GameType.SURVIVAL);
            LOGGER.info("[linkedhardcore] Respawning {} into play", player.getName().getString());
        }
    }

    private void spectateAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.setGameMode(GameType.SPECTATOR);
            LOGGER.info("[linkedhardcore] Spectating {}", player.getName().getString());
        }
    }

    private void showCountdown(MinecraftServer server, int remaining) {
        Component text = Component.literal("§cTransferring in §e" + remaining + "§c...");
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(new ClientboundSetActionBarTextPacket(text));
        }
    }
}

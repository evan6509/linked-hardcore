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
 * Handles the post-death flow for the whole linked player pool: everyone is put
 * into spectator, then either waits for an available server or runs the on-screen
 * transfer countdown once one is ready.
 *
 * <p>Flow:
 * <ol>
 *   <li>{@code WAIT_FOR_SERVER} from the proxy → spectate everyone, respawn any
 *       dead player, and show "Waiting for available server" until told otherwise.</li>
 *   <li>{@code PREPARE_TRANSFER} from the proxy → spectate everyone and run the
 *       action-bar countdown. When it reaches zero, tell the proxy via
 *       {@code TRANSFER_READY} that it may transfer everyone.</li>
 * </ol>
 *
 * <p>Players respawn into normal play (survival) when they arrive at the
 * destination ({@link #respawnOnJoin}). The countdown is driven by wall-clock
 * time sampled each server tick.
 */
public final class TransferCountdown {

    private static final Logger LOGGER = LoggerFactory.getLogger("linkedhardcore");

    private static final Component WAITING_TEXT = Component.literal("§eWaiting for available server...");
    private static final Component COUNTDOWN_PREFIX = Component.literal("§cTransferring in §e");

    private final ModConfig config;
    private final TransferReadyNotifier notifier;

    /** Absolute end time (millis) the active countdown finishes at; null when idle. */
    private volatile Long countdownEnd;
    /** Whether we are waiting for an available server (showing the waiting bar). */
    private volatile boolean waitingForServer;

    public TransferCountdown(ModConfig config, TransferReadyNotifier notifier) {
        this.config = config;
        this.notifier = notifier;
    }

    /**
     * Enters the "waiting for an available server" state: spectates everyone,
     * respawns any dead player, and shows the waiting message until the proxy
     * sends PREPARE_TRANSFER. Called on the server thread.
     */
    public void waitForServer(MinecraftServer server) {
        countdownEnd = null;
        waitingForServer = true;
        LOGGER.info("[linkedhardcore] Waiting for an available server; spectating {} player(s)", server.getPlayerCount());
        spectateAll(server);
        showBar(server, WAITING_TEXT);
    }

    /**
     * Starts the transfer countdown: spectates everyone (idempotent) and runs the
     * on-screen countdown. Called on the server thread when {@code PREPARE_TRANSFER}
     * arrives from the proxy (i.e. a destination server is ready).
     */
    public void start(MinecraftServer server) {
        waitingForServer = false;
        countdownEnd = System.currentTimeMillis() + config.transferCountdownSeconds() * 1000L;
        LOGGER.info("[linkedhardcore] Starting transfer countdown ({}s); spectating {} player(s)",
            config.transferCountdownSeconds(), server.getPlayerCount());
        spectateAll(server);
        showBar(server, countdownText(config.transferCountdownSeconds()));
    }

    /**
     * Called on every server tick: shows the waiting message or updates the
     * countdown from the remaining wall-clock seconds, and fires
     * {@code TRANSFER_READY} when the countdown finishes.
     */
    public void tick(MinecraftServer server) {
        if (waitingForServer) {
            showBar(server, WAITING_TEXT);
            return;
        }
        Long end = countdownEnd;
        if (end == null) {
            return;
        }
        long remainingMillis = end - System.currentTimeMillis();
        if (remainingMillis <= 0) {
            countdownEnd = null;
            LOGGER.info("[linkedhardcore] Countdown finished; notifying proxy");
            notifier.notifyTransferReady(server);
            return;
        }
        showBar(server, countdownText((int) Math.ceil(remainingMillis / 1000.0)));
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

    private static Component countdownText(int remaining) {
        return COUNTDOWN_PREFIX.copy().append(Component.literal(String.valueOf(remaining)))
            .append(Component.literal("§c..."));
    }

    private static void showBar(MinecraftServer server, Component text) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(new ClientboundSetActionBarTextPacket(text));
        }
    }
}

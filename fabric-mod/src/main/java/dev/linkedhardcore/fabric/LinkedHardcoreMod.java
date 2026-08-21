package dev.linkedhardcore.fabric;

import dev.linkedhardcore.fabric.config.ModConfig;
import dev.linkedhardcore.fabric.death.PlayerDeathListener;
import dev.linkedhardcore.fabric.death.TransferCountdown;
import dev.linkedhardcore.fabric.net.ProxyMessenger;
import dev.linkedhardcore.fabric.status.StatusFileWriter;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the Linked Hardcore mod, installed identically on both backend
 * servers (A and B).
 *
 * <p>Responsibilities (all reporting, no world mutation):
 * <ul>
 *   <li>Notify the proxy of any player death (PLAYER_DIED).</li>
 *   <li>On PREPARE_TRANSFER, spectate everyone and run the on-screen countdown,
 *       then signal TRANSFER_READY so the proxy moves everyone.</li>
 *   <li>Respawn players into survival when they join after a transfer.</li>
 *   <li>Write {@code status.json} for the external reset agent + proxy to poll.</li>
 *   <li>Report RESET_COMPLETE once ready again after a reset.</li>
 * </ul>
 *
 * <p>This mod deliberately has zero compile-time dependency on Velocity APIs;
 * it only speaks the documented plugin-messaging protocol.
 */
public final class LinkedHardcoreMod implements DedicatedServerModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("linkedhardcore");

    @Override
    public void onInitializeServer() {
        ModConfig config = ModConfig.load();
        LOGGER.info("[linkedhardcore] Initializing for server '{}'", config.serverId());

        ProxyMessenger messenger = new ProxyMessenger(config);
        TransferCountdown transferCountdown = new TransferCountdown(config, messenger);
        messenger.setTransferCountdown(transferCountdown);
        StatusFileWriter statusWriter = new StatusFileWriter(config);

        messenger.register();
        new PlayerDeathListener(messenger).register();

        // A one-second heartbeat makes status.json a liveness signal as well as a
        // lifecycle snapshot. The proxy will never route a transfer to a stale file.
        final int[] statusHeartbeatTicks = {0};
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            boolean resetPending = writeCurrentStatus(server, statusWriter);
            if (!resetPending) {
                messenger.sendResetComplete(server, config.serverId());
            }
            LOGGER.info("[linkedhardcore] Server started; status written");
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            // Players arrive as spectator after a transfer; respawn them into play.
            transferCountdown.respawnOnJoin(handler.player);
            writeCurrentStatus(server, statusWriter);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            writeCurrentStatus(server, statusWriter);
        });

        // Per-tick: warn on un-acked deaths, advance the transfer countdown, and
        // refresh the liveness heartbeat every second.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            messenger.checkPendingAcks();
            transferCountdown.tick(server);
            if (++statusHeartbeatTicks[0] >= 20) {
                statusHeartbeatTicks[0] = 0;
                writeCurrentStatus(server, statusWriter);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
            LOGGER.info("[linkedhardcore] Server stopping."));
    }

    /** Writes the real current player count; reset requests never fabricate an empty server. */
    private static boolean writeCurrentStatus(net.minecraft.server.MinecraftServer server, StatusFileWriter writer) {
        java.nio.file.Path request = ModConfig.CONFIG_DIR.resolve("reset.request.json");
        boolean resetPending = java.nio.file.Files.isRegularFile(request);
        int playerCount = server.getPlayerCount();
        String state = resetPending ? "resetting" : playerCount == 0 ? "ready" : "live";
        writer.write(state, playerCount);
        return resetPending;
    }
}

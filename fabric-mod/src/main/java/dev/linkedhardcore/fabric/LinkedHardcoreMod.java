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
 *   <li>Persist world use and publish the executor's world identity plus a new boot identity.</li>
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

        StatusFileWriter statusWriter = new StatusFileWriter(config);
        ProxyMessenger messenger = new ProxyMessenger(config, statusWriter::generationId);
        TransferCountdown transferCountdown = new TransferCountdown(config, messenger);
        messenger.setTransferCountdown(transferCountdown);

        messenger.register();
        new PlayerDeathListener(messenger).register();

        // A one-second heartbeat makes status.json a liveness signal as well as a
        // lifecycle snapshot. The proxy will never route a transfer to a stale file.
        final int[] statusHeartbeatTicks = {0};
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            writeCurrentStatus(server, statusWriter);
            LOGGER.info("[linkedhardcore] Server started; publishing status");
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            server.execute(() -> {
                statusWriter.markWorldUsed();
                // Persist world use before respawning an arriving player into play.
                transferCountdown.respawnOnJoin(handler.player);
                statusWriter.write(server::getPlayerCount);
            });
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            writeCurrentStatus(server, statusWriter);
        });

        // Per-tick: retry unacknowledged deaths, advance the transfer countdown, and
        // refresh the liveness heartbeat every second.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            messenger.checkPendingAcks(server);
            transferCountdown.tick(server);
            if (++statusHeartbeatTicks[0] >= 20) {
                statusHeartbeatTicks[0] = 0;
                writeCurrentStatus(server, statusWriter);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
            LOGGER.info("[linkedhardcore] Server stopping."));
    }

    /** Queue both snapshot capture and publication on the Minecraft server thread. */
    private static void writeCurrentStatus(net.minecraft.server.MinecraftServer server, StatusFileWriter writer) {
        server.execute(() -> writer.write(server::getPlayerCount));
    }
}

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

/** Linked Hardcore backend mod. */
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

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            statusWriter.write("ready", server.getPlayerCount());
            LOGGER.info("[linkedhardcore] Server started; state=ready");
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            transferCountdown.respawnOnJoin(handler.player);
            statusWriter.write("live", server.getPlayerCount());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            int count = server.getPlayerCount();
            boolean resetPending = java.nio.file.Files.isRegularFile(ModConfig.CONFIG_DIR.resolve("reset.request.json"));
            statusWriter.write(count == 0 && resetPending ? "resetting" : count == 0 ? "ready" : "live", count);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            messenger.checkPendingAcks();
            transferCountdown.tick(server);
            checkResetRequest(statusWriter);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> LOGGER.info("[linkedhardcore] Server stopping."));
    }

    private static void checkResetRequest(StatusFileWriter writer) {
        if (java.nio.file.Files.isRegularFile(ModConfig.CONFIG_DIR.resolve("reset.request.json"))) {
            writer.write("resetting", 0);
        }
    }
}

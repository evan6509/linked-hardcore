package dev.linkedhardcore.fabric;

import dev.linkedhardcore.fabric.config.ModConfig;
import dev.linkedhardcore.fabric.death.GroupEliminator;
import dev.linkedhardcore.fabric.death.PlayerDeathListener;
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
 *   <li>Notify the proxy of tracked player deaths (PLAYER_DIED).</li>
 *   <li>Handle GROUP_ELIMINATED from the proxy (eliminate remaining group members).</li>
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
        LOGGER.info("[linkedhardcore] Initializing for server '{}', elimination mode {}",
            config.serverId(), config.eliminationMode());

        GroupEliminator eliminator = new GroupEliminator(config);
        ProxyMessenger messenger = new ProxyMessenger(config, eliminator);
        StatusFileWriter statusWriter = new StatusFileWriter(config);

        messenger.register();
        new PlayerDeathListener(config, messenger).register();

        // status.json lifecycle: report ready once the world is up, live once a
        // player joins, ready again when the server empties, and resetting when the
        // proxy flags us via a reset request (checked on tick).
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            statusWriter.write("ready", server.getPlayerCount());
            LOGGER.info("[linkedhardcore] Server started; state=ready");
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            statusWriter.write("live", server.getPlayerCount()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            int count = server.getPlayerCount();
            statusWriter.write(count == 0 ? "ready" : "live", count);
        });

        // Per-tick: warn on un-acked deaths, and detect an external reset request
        // so we can report state=resetting for Sisyphus.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            messenger.checkPendingAcks();
            checkResetRequest(statusWriter);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
            LOGGER.info("[linkedhardcore] Server stopping."));
    }

    /** If the proxy (or Sisyphus) dropped a reset.request.json, report resetting. */
    private static void checkResetRequest(StatusFileWriter writer) {
        // Placeholder seam: the proxy writes reset.request.json into this same
        // directory. When present, we mirror it into status.json as "resetting".
        java.nio.file.Path request = ModConfig.CONFIG_DIR.resolve("reset.request.json");
        if (java.nio.file.Files.isRegularFile(request)) {
            writer.write("resetting", 0);
        }
    }
}

package dev.linkedhardcore.velocity.status;

import com.google.gson.Gson;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import dev.linkedhardcore.velocity.config.PluginConfig;
import dev.linkedhardcore.velocity.model.ServerState;
import dev.linkedhardcore.velocity.model.ServerStatus;
import dev.linkedhardcore.velocity.routing.TransferHandler;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Polls each backend's {@code status.json} (written by the Fabric mod) to learn
 * when a server has finished resetting and is ready again.
 *
 * <p>The file contract is documented in {@code docs/RESET_CONTRACT.md}. When a
 * server whose proxy state is {@code RESETTING} reports {@code state: "ready"}
 * with {@code playerCount: 0}, we flip it to {@code READY} and notify
 * {@link TransferHandler} so any pending transfer can resume.
 *
 * <p>Poll interval is configurable via {@code statusPollSeconds} (default 1s).
 */
public final class StatusPoller {

    private final ProxyServer proxy;
    private final PluginConfig config;
    private final Map<String, ServerStatus> servers;
    private final TransferHandler transferHandler;
    private final Logger logger;
    private final Object plugin;
    private final Gson gson = new Gson();

    private volatile ScheduledTask task;

    public StatusPoller(Object plugin, ProxyServer proxy, PluginConfig config, Map<String, ServerStatus> servers,
                        TransferHandler transferHandler, Logger logger) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.config = config;
        this.servers = servers;
        this.transferHandler = transferHandler;
        this.logger = logger;
    }

    /** Starts the periodic poll. Call once during proxy init. */
    public void start() {
        Scheduler.TaskBuilder builder = proxy.getScheduler().buildTask(plugin, this::poll)
            .delay(1, TimeUnit.SECONDS)
            .repeat(config.statusPollSeconds(), TimeUnit.SECONDS);
        task = builder.schedule();
        logger.info("[linkedhardcore] Status poller started (every {}s)", config.statusPollSeconds());
    }

    /** Stops the poller. Call on proxy shutdown. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void poll() {
        for (Map.Entry<String, PluginConfig.BackendServer> entry : config.backendServers().entrySet()) {
            String name = entry.getKey();
            PluginConfig.BackendServer backend = entry.getValue();
            ServerStatus status = servers.get(name);
            if (status == null) {
                continue;
            }
            StatusFile file = readStatus(Path.of(backend.statusFile()));
            if (file == null) {
                continue;
            }
            if ("ready".equals(file.state) && file.playerCount == 0 && status.is(ServerState.RESETTING)) {
                logger.info("[linkedhardcore] Status poll: server '{}' is ready again", name);
                transferHandler.onServerReady(name);
            }
        }
    }

    private StatusFile readStatus(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return null;
            }
            return gson.fromJson(Files.readString(path), StatusFile.class);
        } catch (Exception e) {
            logger.debug("[linkedhardcore] Could not read status file {}: {}", path, e.toString());
            return null;
        }
    }

    private static final class StatusFile {
        String state;
        int playerCount;
    }
}

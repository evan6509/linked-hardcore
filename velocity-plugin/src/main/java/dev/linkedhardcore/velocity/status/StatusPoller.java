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
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Polls each backend's {@code status.json} (written by the Fabric mod) to learn
 * when a server has finished resetting and is ready again.
 *
 * <p>The file contract is documented in {@code docs/RESET_CONTRACT.md}. A
 * backend is routable only while its status file is fresh and reports
 * {@code state: "ready"} with {@code playerCount: 0}. Stale or unreadable files
 * make the backend {@code UNAVAILABLE}; a fresh ready report notifies
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
        Scheduler.TaskBuilder builder = proxy.getScheduler().buildTask(plugin, this::pollOnce)
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

    /** Runs one poll cycle. Package-visible for focused status-file tests. */
    void pollOnce() {
        for (Map.Entry<String, PluginConfig.BackendServer> entry : config.backendServers().entrySet()) {
            String name = entry.getKey();
            PluginConfig.BackendServer backend = entry.getValue();
            ServerStatus status = servers.get(name);
            if (status == null) {
                continue;
            }
            Path path = Path.of(backend.statusFile());
            StatusFile file = readStatus(path);
            if (file == null || isStale(path)) {
                status.tryTransition(ServerState.UNAVAILABLE, logger);
                continue;
            }
            if ("ready".equals(file.state) && file.playerCount == 0) {
                transferHandler.onServerReady(name);
            } else if ("live".equals(file.state) || file.playerCount > 0) {
                if (!status.is(ServerState.TRANSFERRING) && !status.is(ServerState.RESETTING)) {
                    status.tryTransition(ServerState.LIVE, logger);
                }
            } else if ("resetting".equals(file.state) && !status.is(ServerState.TRANSFERRING)) {
                status.tryTransition(ServerState.RESETTING, logger);
            } else {
                status.tryTransition(ServerState.UNAVAILABLE, logger);
            }
        }
    }

    private boolean isStale(Path path) {
        try {
            FileTime modified = Files.getLastModifiedTime(path);
            return modified.toMillis() < System.currentTimeMillis() - config.statusStaleSeconds() * 1000L;
        } catch (Exception e) {
            return true;
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

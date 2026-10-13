package dev.linkedhardcore.velocity.status;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import dev.linkedhardcore.velocity.config.PluginConfig;
import dev.linkedhardcore.velocity.model.ServerStatus;
import dev.linkedhardcore.velocity.routing.TransferHandler;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Polls each backend's {@code status.json} (written by the Fabric mod) to learn
 * when a server has finished resetting and is ready again.
 *
 * <p>The file contract is documented in {@code docs/RESET_CONTRACT.md}. A
 * backend is a new-run destination only while its fresh heartbeat agrees with
 * its durable unused world identity and any outstanding reset request.
 * {@link TransferHandler} validates this evidence under its transfer lock.
 *
 * <p>Poll interval is configurable via {@code statusPollSeconds} (default 1s).
 */
public final class StatusPoller {

    private final ProxyServer proxy;
    private final PluginConfig config;
    private final TransferHandler transferHandler;
    private final Logger logger;
    private final Object plugin;

    private volatile ScheduledTask task;

    public StatusPoller(Object plugin, ProxyServer proxy, PluginConfig config, Map<String, ServerStatus> servers,
                        TransferHandler transferHandler, Logger logger) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.config = config;
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

    /** Refreshes under the transfer lock so no status publication can cancel a scheduled reset. */
    void pollOnce() {
        for (String name : config.backendServers().keySet()) {
            try {
                transferHandler.onServerReady(name);
            } catch (RuntimeException e) {
                logger.error("[linkedhardcore] Lifecycle checkpoint failed; will retry without admitting the ended run", e);
            }
        }
    }
}

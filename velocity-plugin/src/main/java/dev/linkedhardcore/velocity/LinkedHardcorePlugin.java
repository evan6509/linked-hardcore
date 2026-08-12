package dev.linkedhardcore.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.linkedhardcore.velocity.command.StatusCommand;
import dev.linkedhardcore.velocity.config.PluginConfig;
import dev.linkedhardcore.velocity.model.GroupRegistry;
import dev.linkedhardcore.velocity.model.ServerState;
import dev.linkedhardcore.velocity.model.ServerStatus;
import dev.linkedhardcore.velocity.net.Protocol;
import dev.linkedhardcore.velocity.net.ProxyMessageListener;
import dev.linkedhardcore.velocity.reset.FileResetSignaller;
import dev.linkedhardcore.velocity.reset.ResetSignaller;
import dev.linkedhardcore.velocity.routing.TransferHandler;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Velocity plugin for Linked Hardcore.
 *
 * <p>Tracks the lifecycle of the two backend servers (which is live vs
 * resetting), holds shared-life group membership, and routes group elimination +
 * server switching based on {@code PLAYER_DIED} events from the Fabric mods.
 */
@Plugin(
    id = "linkedhardcore",
    name = "Linked Hardcore",
    version = "0.1.0",
    description = "Paired-server hardcore: shared-life groups across two Fabric backends behind Velocity.",
    authors = {"linked-hardcore"}
)
public final class LinkedHardcorePlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private Map<String, ServerStatus> servers;
    private TransferHandler transferHandler;

    @Inject
    public LinkedHardcorePlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        // Register the messaging channel so backend->proxy plugin messages fire our listener.
        proxy.getChannelRegistrar().register(Protocol.CHANNEL);

        PluginConfig config;
        try {
            config = PluginConfig.load(dataDirectory.resolve("config.json"), logger);
        } catch (IOException e) {
            logger.error("[linkedhardcore] Fatal: could not load plugin config. Plugin will not start routing.", e);
            return;
        }

        // One state machine per configured backend server, initialised READY.
        Map<String, ServerStatus> serverStatuses = new LinkedHashMap<>();
        config.backendServers().keySet().forEach(name ->
            serverStatuses.put(name, new ServerStatus(name, ServerState.READY)));
        this.servers = serverStatuses;

        GroupRegistry groupRegistry = new GroupRegistry(config);
        groupRegistry.logSummary(logger);

        ResetSignaller resetSignaller = new FileResetSignaller(config, logger);
        this.transferHandler = new TransferHandler(proxy, config, groupRegistry, servers, resetSignaller, logger);

        proxy.getEventManager().register(this, new ProxyMessageListener(transferHandler, logger));

        registerCommand(config, groupRegistry);

        logger.info("[linkedhardcore] Linked Hardcore initialized. Backends: {}", servers.keySet());
    }

    private void registerCommand(PluginConfig config, GroupRegistry groupRegistry) {
        CommandMeta meta = proxy.getCommandManager().metaBuilder("linkedhardcore")
            .aliases("lh")
            .plugin(this)
            .build();
        proxy.getCommandManager().register(meta, new StatusCommand(proxy, config, groupRegistry, servers));
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("[linkedhardcore] Shutting down.");
    }
}

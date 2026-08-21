package dev.linkedhardcore.velocity.routing;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.linkedhardcore.velocity.config.PluginConfig;
import dev.linkedhardcore.velocity.model.ServerState;
import dev.linkedhardcore.velocity.model.ServerStatus;
import dev.linkedhardcore.velocity.death.DeathCounterBroadcaster;
import dev.linkedhardcore.velocity.death.DeathCounterState;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Keeps each backend's {@link ServerStatus} in sync with player activity so the
 * transfer state machine is correct:
 * <ul>
 *   <li>a server hosting any player is {@code LIVE} (initial join OR transfer
 *       arrival — {@code ServerConnectedEvent});</li>
 *   <li>a {@code LIVE} server whose last player left becomes {@code READY} again
 *       ({@code DisconnectEvent}). A {@code RESETTING} server is never touched —
 *       it stays {@code RESETTING} until the reset poller/agent flips it.</li>
 * </ul>
 *
 * <p>Without this, every backend would remain {@code READY} forever and the
 * {@code LIVE -&gt; RESETTING} transition (used when a group transfers out) would
 * throw, silently skipping the reset signal.
 */
public final class ServerLifecycleListener {

    private final ProxyServer proxy;
    private final PluginConfig config;
    private final Map<String, ServerStatus> servers;
    private final Logger logger;
    private final DeathCounterState deathCounters;

    public ServerLifecycleListener(ProxyServer proxy, PluginConfig config,
                                   Map<String, ServerStatus> servers, Logger logger, DeathCounterState deathCounters) {
        this.proxy = proxy;
        this.config = config;
        this.servers = servers;
        this.logger = logger;
        this.deathCounters = deathCounters;
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        markLive(event.getServer());
        DeathCounterBroadcaster.send(event.getServer(), deathCounters);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        // DisconnectEvent fires after the player is removed from the proxy, so each
        // backend's getPlayersConnected() is already final. Flip any LIVE backend
        // that just became empty back to READY.
        for (String name : config.backendServers().keySet()) {
            ServerStatus status = servers.get(name);
            if (status == null || !status.is(ServerState.LIVE)) {
                continue;
            }
            RegisteredServer server = proxy.getServer(name).orElse(null);
            if (server != null && server.getPlayersConnected().isEmpty()) {
                status.transition(ServerState.READY, logger);
            }
        }
    }

    private void markLive(RegisteredServer server) {
        ServerStatus status = servers.get(server.getServerInfo().getName());
        if (status != null && status.is(ServerState.READY)) {
            status.transition(ServerState.LIVE, logger);
        }
    }
}

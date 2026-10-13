package dev.linkedhardcore.velocity.routing;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import dev.linkedhardcore.velocity.death.DeathCounterBroadcaster;
import dev.linkedhardcore.velocity.death.DeathCounterState;

/** Reconnect to the current run, and never infer world preparation from a disconnect. */
public final class ServerLifecycleListener {
    private final TransferHandler transferHandler;
    private final DeathCounterState deathCounters;

    public ServerLifecycleListener(TransferHandler transferHandler, DeathCounterState deathCounters) {
        this.transferHandler = transferHandler;
        this.deathCounters = deathCounters;
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (!event.getResult().isAllowed()) return;
        String requested = event.getResult().getServer().orElse(event.getOriginalServer()).getServerInfo().getName();
        event.setResult(transferHandler.connectionServer(event.getPlayer().getCurrentServer().isEmpty(), requested)
            .map(ServerPreConnectEvent.ServerResult::allowed).orElseGet(ServerPreConnectEvent.ServerResult::denied));
    }

    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        event.getPlayer().getCurrentServer().ifPresent(connection -> {
            if (!transferHandler.onBackendJoined(connection.getServerInfo().getName())) {
                event.getPlayer().disconnect(net.kyori.adventure.text.Component.text(
                    "This run has ended. Reconnect to join the current run."));
                return;
            }
            // Post-connect guarantees a player-backed route for the first arrival.
            DeathCounterBroadcaster.send(connection, deathCounters);
        });
    }
}

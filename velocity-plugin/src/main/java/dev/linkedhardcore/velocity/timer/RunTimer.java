package dev.linkedhardcore.velocity.timer;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import dev.linkedhardcore.velocity.config.PluginConfig;
import dev.linkedhardcore.velocity.net.Protocol;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Persists and broadcasts one proxy-authoritative run timer to occupied backends. */
public final class RunTimer {
    private final ProxyServer proxy;
    private final PluginConfig config;
    private final RunTimerState state;
    private final RunTimerStore store;
    private final Set<UUID> onlinePlayers = new HashSet<>();
    private ScheduledTask task;
    private boolean stopped;

    public RunTimer(ProxyServer proxy, PluginConfig config, RunTimerState state, RunTimerStore store) {
        this.proxy = proxy;
        this.config = config;
        this.state = state;
        this.store = store;
    }

    public synchronized void start(Object plugin) {
        for (var player : proxy.getAllPlayers()) {
            if (player.getCurrentServer().filter(connection ->
                config.backendServers().containsKey(connection.getServerInfo().getName())).isPresent()) {
                onlinePlayers.add(player.getUniqueId());
            }
        }
        state.setPlayersOnline(!onlinePlayers.isEmpty());
        task = proxy.getScheduler().buildTask(plugin, this::tick)
            .delay(1, TimeUnit.SECONDS).repeat(1, TimeUnit.SECONDS).schedule();
    }

    @Subscribe
    public synchronized void onServerConnected(ServerConnectedEvent event) {
        if (stopped) {
            return;
        }
        if (config.backendServers().containsKey(event.getServer().getServerInfo().getName())) {
            onlinePlayers.add(event.getPlayer().getUniqueId());
        } else {
            onlinePlayers.remove(event.getPlayer().getUniqueId());
        }
        state.setPlayersOnline(!onlinePlayers.isEmpty());
        saveAndBroadcast();
    }

    @Subscribe
    public synchronized void onDisconnect(DisconnectEvent event) {
        if (stopped) {
            return;
        }
        onlinePlayers.remove(event.getPlayer().getUniqueId());
        state.setPlayersOnline(!onlinePlayers.isEmpty());
        saveAndBroadcast();
    }

    public synchronized void endRun() {
        if (!stopped) {
            state.endRun();
            saveAndBroadcast();
        }
    }

    public synchronized void startNewRun() {
        if (!stopped) {
            state.startNewRun(!onlinePlayers.isEmpty());
            saveAndBroadcast();
        }
    }

    private synchronized void tick() {
        if (stopped || onlinePlayers.isEmpty()) {
            return;
        }
        var snapshot = state.snapshot();
        // Checkpoint active play every second for recovery after a proxy crash.
        if (!snapshot.ended()) {
            store.save(snapshot);
        }
        broadcast(snapshot);
    }

    public synchronized void stop() {
        stopped = true;
        if (task != null) {
            task.cancel();
            task = null;
        }
        state.setPlayersOnline(false);
        store.save(state.snapshot());
    }

    private void saveAndBroadcast() {
        var snapshot = state.snapshot();
        store.save(snapshot);
        broadcast(snapshot);
    }

    private void broadcast(RunTimerState.Snapshot snapshot) {
        byte[] frame = Protocol.encodeRunTimer(snapshot.elapsedMillis(), snapshot.ended());
        for (String name : config.backendServers().keySet()) {
            proxy.getServer(name).filter(server -> !server.getPlayersConnected().isEmpty())
                .ifPresent(server -> server.sendPluginMessage(Protocol.CHANNEL, frame));
        }
    }
}

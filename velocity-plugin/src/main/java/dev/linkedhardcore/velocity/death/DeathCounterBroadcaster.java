package dev.linkedhardcore.velocity.death;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.linkedhardcore.velocity.net.Protocol;

/** Sends the complete authoritative counter snapshot to a backend server. */
public final class DeathCounterBroadcaster {
    private DeathCounterBroadcaster() {}

    public static void send(RegisteredServer server, DeathCounterState state) {
        var snapshot = state.snapshot().stream()
            .map(record -> new Protocol.DeathCounter(record.playerUuid(), record.playerName(), record.deaths()))
            .toList();
        server.sendPluginMessage(Protocol.CHANNEL, Protocol.encodeDeathCounters(snapshot));
    }
}

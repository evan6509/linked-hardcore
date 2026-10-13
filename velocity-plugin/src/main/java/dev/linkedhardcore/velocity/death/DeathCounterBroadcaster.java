package dev.linkedhardcore.velocity.death;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.ServerConnection;
import dev.linkedhardcore.velocity.net.Protocol;

/** Sends the complete authoritative counter snapshot to a backend server. */
public final class DeathCounterBroadcaster {
    private DeathCounterBroadcaster() {
    }

    public static void send(RegisteredServer server, DeathCounterState state) {
        server.sendPluginMessage(Protocol.CHANNEL, encode(state));
    }

    public static void send(ServerConnection connection, DeathCounterState state) {
        connection.sendPluginMessage(Protocol.CHANNEL, encode(state));
    }

    private static byte[] encode(DeathCounterState state) {
        var snapshot = state.snapshot().stream()
            .map(record -> new Protocol.DeathCounter(record.playerUuid(), record.playerName(), record.deaths()))
            .toList();
        return Protocol.encodeDeathCounters(snapshot);
    }
}

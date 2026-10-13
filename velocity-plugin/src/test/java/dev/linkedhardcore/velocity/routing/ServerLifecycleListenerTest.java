package dev.linkedhardcore.velocity.routing;

import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import dev.linkedhardcore.velocity.death.DeathCounterState;
import dev.linkedhardcore.velocity.net.Protocol;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ServerLifecycleListenerTest {
    @Test
    void firstArrivalReceivesPersistedCountersThroughItsActiveConnection() {
        Player player = mock(Player.class);
        ServerConnection connection = mock(ServerConnection.class);
        RegisteredServer destination = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);
        when(info.getName()).thenReturn("beta");
        when(connection.getServerInfo()).thenReturn(info);
        when(player.getCurrentServer()).thenReturn(Optional.of(connection));
        when(destination.getPlayersConnected()).thenReturn(List.of());
        TransferHandler handler = mock(TransferHandler.class);
        when(handler.onBackendJoined("beta")).thenReturn(true);
        DeathCounterState counters = new DeathCounterState();
        UUID uuid = UUID.randomUUID();
        counters.restore(new DeathCounterState.DeathRecord(uuid, "player", 7));
        new ServerLifecycleListener(handler, counters).onServerConnected(new ServerPostConnectEvent(player, null));
        verify(handler).onBackendJoined("beta");
        ArgumentCaptor<byte[]> frame = ArgumentCaptor.forClass(byte[].class);
        verify(connection).sendPluginMessage(eq(Protocol.CHANNEL), frame.capture());
        assertArrayEquals(Protocol.encodeDeathCounters(List.of(new Protocol.DeathCounter(uuid, "player", 7))), frame.getValue());
        verify(destination, never()).sendPluginMessage(any(), any(byte[].class));
    }
    @Test
    void aPreapprovedLoginThatArrivesAtARetiredSourceIsDisconnected() {
        Player player = mock(Player.class);
        ServerConnection connection = mock(ServerConnection.class);
        ServerInfo info = mock(ServerInfo.class);
        when(info.getName()).thenReturn("retired");
        when(connection.getServerInfo()).thenReturn(info);
        when(player.getCurrentServer()).thenReturn(Optional.of(connection));
        TransferHandler handler = mock(TransferHandler.class);
        when(handler.onBackendJoined("retired")).thenReturn(false);
        new ServerLifecycleListener(handler, new DeathCounterState()).onServerConnected(new ServerPostConnectEvent(player, null));
        verify(player).disconnect(any());
        verify(connection, never()).sendPluginMessage(any(), any(byte[].class));
    }

}

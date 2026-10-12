package dev.linkedhardcore.velocity.timer;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import dev.linkedhardcore.velocity.config.PluginConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunTimerTest {
    @TempDir Path directory;

    @Test
    void pausesOnlyAfterTheLastLinkedPlayerLeavesAndPersistsTheRun() {
        AtomicLong clock = new AtomicLong();
        RunTimerState state = new RunTimerState(clock::get);
        RunTimerStore store = new RunTimerStore(directory.resolve("run-timer.json"), LoggerFactory.getLogger("test"));
        ProxyServer proxy = mock(ProxyServer.class);
        RegisteredServer backend = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);
        when(info.getName()).thenReturn("alpha");
        when(backend.getServerInfo()).thenReturn(info);
        when(backend.getPlayersConnected()).thenReturn(List.of());
        when(proxy.getServer("alpha")).thenReturn(Optional.of(backend));
        PluginConfig config = new PluginConfig(Map.of("alpha",
            new PluginConfig.BackendServer("alpha", "a", directory.resolve("status.json").toString())), 1);
        RunTimer timer = new RunTimer(proxy, config, state, store);
        Player first = player();
        Player second = player();

        timer.onServerConnected(join(first, backend));
        clock.addAndGet(1000);
        timer.onServerConnected(join(second, backend));
        clock.addAndGet(1000);
        timer.onDisconnect(disconnect(first));
        clock.addAndGet(1000);
        timer.onDisconnect(disconnect(second));
        clock.addAndGet(60_000);
        assertEquals(3000, state.snapshot().elapsedMillis());

        RunTimerState restored = new RunTimerState(clock::get);
        store.loadInto(restored);
        clock.addAndGet(60_000);
        assertEquals(state.snapshot(), restored.snapshot());

        timer.onServerConnected(join(first, backend));
        clock.addAndGet(500);
        timer.endRun();
        timer.onServerConnected(join(second, backend));
        clock.addAndGet(5000);
        assertEquals(new RunTimerState.Snapshot(3500, true), state.snapshot());
        store.loadInto(restored);
        assertEquals(state.snapshot(), restored.snapshot());

        timer.startNewRun();
        clock.addAndGet(250);
        timer.stop();
        clock.addAndGet(1000);
        store.loadInto(restored);
        assertEquals(new RunTimerState.Snapshot(250, false), restored.snapshot());
    }

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }

    private static ServerConnectedEvent join(Player player, RegisteredServer server) {
        ServerConnectedEvent event = mock(ServerConnectedEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getServer()).thenReturn(server);
        return event;
    }

    private static DisconnectEvent disconnect(Player player) {
        DisconnectEvent event = mock(DisconnectEvent.class);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }
}

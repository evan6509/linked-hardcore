package dev.linkedhardcore.velocity.routing;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import dev.linkedhardcore.velocity.config.PluginConfig;
import dev.linkedhardcore.velocity.model.ServerState;
import dev.linkedhardcore.velocity.model.ServerStatus;
import dev.linkedhardcore.velocity.reset.ResetSignaller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransferHandlerTest {

    private final ProxyServer proxy = mock(ProxyServer.class);
    private final RegisteredServer alpha = mock(RegisteredServer.class);
    private final RegisteredServer beta = mock(RegisteredServer.class);
    private final RegisteredServer gamma = mock(RegisteredServer.class);
    private final ServerConnection source = mock(ServerConnection.class);
    private final Player player = mock(Player.class);
    private final ConnectionRequestBuilder request = mock(ConnectionRequestBuilder.class);
    private final ResetSignaller resetSignaller = mock(ResetSignaller.class);
    private final AtomicReference<Collection<Player>> alphaPlayers = new AtomicReference<>();
    private final AtomicReference<Collection<Player>> betaPlayers = new AtomicReference<>();

    private TransferHandler handler;
    private Map<String, ServerStatus> statuses;

    @BeforeEach
    void setUp() {
        ServerInfo alphaInfo = mock(ServerInfo.class);
        ServerInfo betaInfo = mock(ServerInfo.class);
        ServerInfo gammaInfo = mock(ServerInfo.class);
        when(alphaInfo.getName()).thenReturn("alpha");
        when(betaInfo.getName()).thenReturn("beta");
        when(gammaInfo.getName()).thenReturn("gamma");
        when(alpha.getServerInfo()).thenReturn(alphaInfo);
        when(beta.getServerInfo()).thenReturn(betaInfo);
        when(gamma.getServerInfo()).thenReturn(gammaInfo);
        when(source.getServerInfo()).thenReturn(alphaInfo);
        when(proxy.getServer("alpha")).thenReturn(Optional.of(alpha));
        when(proxy.getServer("beta")).thenReturn(Optional.of(beta));
        when(proxy.getServer("gamma")).thenReturn(Optional.of(gamma));

        alphaPlayers.set(List.of(player));
        betaPlayers.set(List.of());
        when(alpha.getPlayersConnected()).thenAnswer(ignored -> alphaPlayers.get());
        when(beta.getPlayersConnected()).thenAnswer(ignored -> betaPlayers.get());
        when(gamma.getPlayersConnected()).thenReturn(List.of());
        when(proxy.getAllPlayers()).thenReturn(List.of(player));
        when(player.getCurrentServer()).thenReturn(Optional.of(source));
        when(player.getUsername()).thenReturn("test-player");
        when(player.createConnectionRequest(beta)).thenReturn(request);

        LinkedHashMap<String, PluginConfig.BackendServer> backends = new LinkedHashMap<>();
        backends.put("alpha", new PluginConfig.BackendServer("alpha", "logical-a", Path.of("/tmp/a-status.json").toString()));
        backends.put("beta", new PluginConfig.BackendServer("beta", "logical-b", Path.of("/tmp/b-status.json").toString()));
        backends.put("gamma", new PluginConfig.BackendServer("gamma", "logical-c", Path.of("/tmp/c-status.json").toString()));
        statuses = new LinkedHashMap<>();
        statuses.put("alpha", new ServerStatus("alpha", ServerState.LIVE));
        statuses.put("beta", new ServerStatus("beta", ServerState.READY));
        statuses.put("gamma", new ServerStatus("gamma", ServerState.READY));
        handler = new TransferHandler(proxy, new PluginConfig(backends, 1), statuses, resetSignaller,
            LoggerFactory.getLogger("test"));
    }

    @Test
    void failedConnectionsNeverSignalAReset() throws Exception {
        CompletableFuture<ConnectionRequestBuilder.Result> connection = new CompletableFuture<>();
        when(request.connect()).thenReturn(connection);

        handler.onPlayerDied(source, UUID.randomUUID());
        handler.onTransferReady(source);
        connection.complete(mock(ConnectionRequestBuilder.Result.class));

        verify(resetSignaller, never()).signalReset(any());
        assertEquals(ServerState.LIVE, statuses.get("alpha").state());
        assertEquals(ServerState.READY, statuses.get("beta").state());
    }

    @Test
    void successfulConnectionsResetUsingLogicalServerId() throws Exception {
        CompletableFuture<ConnectionRequestBuilder.Result> connection = new CompletableFuture<>();
        when(request.connect()).thenReturn(connection);
        ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
        when(result.isSuccessful()).thenReturn(true);

        handler.onPlayerDied(source, UUID.randomUUID());
        handler.onTransferReady(source);
        alphaPlayers.set(List.of());
        connection.complete(result);

        verify(resetSignaller).signalReset("logical-a");
        assertEquals(ServerState.RESETTING, statuses.get("alpha").state());
        assertEquals(ServerState.LIVE, statuses.get("beta").state());
    }

    @Test
    void ignoresASecondDeathWhileCountdownIsActive() {
        handler.onPlayerDied(source, UUID.randomUUID());
        handler.onPlayerDied(source, UUID.randomUUID());

        // One death-counter snapshot and one PREPARE_TRANSFER frame; the duplicate
        // death must not trigger either frame again.
        verify(alpha, org.mockito.Mockito.times(2)).sendPluginMessage(any(), any(byte[].class));
        assertEquals(ServerState.TRANSFERRING, statuses.get("alpha").state());
        assertTrue(statuses.get("beta").is(ServerState.READY));
    }

    @Test
    void preparesEveryBackendThatCurrentlyHostsLinkedPlayers() {
        betaPlayers.set(List.of(mock(Player.class)));

        handler.onPlayerDied(source, UUID.randomUUID());

        // Each occupied source receives a snapshot plus PREPARE_TRANSFER.
        verify(alpha, org.mockito.Mockito.times(2)).sendPluginMessage(any(), any(byte[].class));
        verify(beta, org.mockito.Mockito.times(2)).sendPluginMessage(any(), any(byte[].class));
        assertEquals(ServerState.TRANSFERRING, statuses.get("alpha").state());
        assertEquals(ServerState.TRANSFERRING, statuses.get("beta").state());
        assertEquals(ServerState.READY, statuses.get("gamma").state());
    }
}

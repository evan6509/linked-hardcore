package dev.linkedhardcore.velocity.routing;

import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import dev.linkedhardcore.velocity.config.PluginConfig;
import dev.linkedhardcore.velocity.death.DeathCounterState;
import dev.linkedhardcore.velocity.model.ServerState;
import dev.linkedhardcore.velocity.model.ServerStatus;
import dev.linkedhardcore.velocity.net.Protocol;
import dev.linkedhardcore.velocity.reset.ResetSignaller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import dev.linkedhardcore.velocity.status.BackendFixture;
import dev.linkedhardcore.velocity.reset.ResetRequest;
import dev.linkedhardcore.velocity.reset.FileResetSignaller;
import java.nio.file.Files;
import com.google.gson.Gson;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.argThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    private final AtomicInteger endedRuns = new AtomicInteger();
    private final AtomicInteger newRuns = new AtomicInteger();

    @TempDir Path directory;
    private PluginConfig config;
    private TransferHandler handler;
    private Map<String, ServerStatus> statuses;

    @BeforeEach
    void setUp() throws Exception {
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
        backends.put("alpha", new PluginConfig.BackendServer("alpha", "logical-a", directory.resolve("a/config/linkedhardcore/status.json").toString()));
        backends.put("beta", new PluginConfig.BackendServer("beta", "logical-b", directory.resolve("b/config/linkedhardcore/status.json").toString()));
        backends.put("gamma", new PluginConfig.BackendServer("gamma", "logical-c", directory.resolve("c/config/linkedhardcore/status.json").toString()));
        statuses = new LinkedHashMap<>();
        statuses.put("alpha", new ServerStatus("alpha", ServerState.LIVE));
        statuses.put("beta", new ServerStatus("beta", ServerState.READY));
        statuses.put("gamma", new ServerStatus("gamma", ServerState.READY));
        config = new PluginConfig(backends, 1);
        BackendFixture.publish(directory.resolve("a/config/linkedhardcore"), "logical-a", "world-a", "boot-a", true, 1, null);
        BackendFixture.publish(directory.resolve("b/config/linkedhardcore"), "logical-b", "world-b", "boot-b", false, 0, null);
        BackendFixture.publish(directory.resolve("c/config/linkedhardcore"), "logical-c", "world-c", "boot-c", false, 0, null);
        handler = new TransferHandler(proxy, config, statuses, resetSignaller,
            LoggerFactory.getLogger("test"), new DeathCounterState(), ignored -> { },
            endedRuns::incrementAndGet, newRuns::incrementAndGet);
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
        assertEquals(1, endedRuns.get());
        assertEquals(0, newRuns.get());
    }

    @Test
    void successfulConnectionsResetUsingLogicalServerId() throws Exception {
        CompletableFuture<ConnectionRequestBuilder.Result> connection = new CompletableFuture<>();
        when(request.connect()).thenReturn(connection);
        ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
        when(result.isSuccessful()).thenReturn(true);

        handler.onPlayerDied(source, UUID.randomUUID());
        handler.onTransferReady(source);
        assertEquals(1, endedRuns.get());
        assertEquals(0, newRuns.get());
        alphaPlayers.set(List.of());
        connection.complete(result);

        verify(resetSignaller).signalReset(argThat(reset -> reset.serverId().equals("logical-a")
            && reset.previousGenerationId().equals("world-a") && reset.previousBootId().equals("boot-a")));
        assertEquals(ServerState.RESETTING, statuses.get("alpha").state());
        assertEquals(ServerState.LIVE, statuses.get("beta").state());
        assertEquals(1, newRuns.get());
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
        assertEquals(1, endedRuns.get());
        assertEquals(0, newRuns.get());
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

    @Test
    void resetSchedulingGapCannotPromoteTheOldEmptySnapshot() throws Exception {
        doAnswer(invocation -> {
            ResetRequest reset = invocation.getArgument(0);
            Files.createDirectories(directory.resolve("a/config/linkedhardcore"));
            Files.writeString(directory.resolve("a/config/linkedhardcore/reset.pending.json"), new Gson().toJson(reset));
            BackendFixture.publish(directory.resolve("a/config/linkedhardcore"), "logical-a", "world-a", "boot-a", false, 0, null);
            handler.onServerReady("alpha");
            assertFalse(statuses.get("alpha").is(ServerState.READY));
            Files.writeString(directory.resolve("a/config/linkedhardcore/reset.request.json"), new Gson().toJson(reset));
            return null;
        }).when(resetSignaller).signalReset(any());
        completeSuccessfulTransfer();
        assertFalse(statuses.get("alpha").is(ServerState.READY));
        handler.onResetComplete("logical-a");
        assertFalse(statuses.get("alpha").is(ServerState.READY));
    }

    @Test
    void usedStandbyIsResetBeforeItCanReceiveANewRun() throws Exception {
        BackendFixture.publish(directory.resolve("b/config/linkedhardcore"), "logical-b", "used-b", "boot-b", true, 0, null);
        BackendFixture.publish(directory.resolve("c/config/linkedhardcore"), "logical-c", "used-c", "boot-c", true, 0, null);
        handler.onPlayerDied(source, UUID.randomUUID());
        verify(resetSignaller).signalReset(argThat(reset -> reset.serverId().equals("logical-b")));
        handler.onTransferReady(source);
        verify(player, never()).createConnectionRequest(any());
    }

    @Test
    void durableResetSurvivesUnavailableAndProxyRestart() throws Exception {
        ResetRequest expected = ResetRequest.create("logical-b", "world-b", "boot-b");
        new FileResetSignaller(config, LoggerFactory.getLogger("test")).signalReset(expected);
        Files.delete(directory.resolve("b/config/linkedhardcore/reset.request.json"));
        handler.onServerReady("beta");
        assertFalse(statuses.get("beta").is(ServerState.READY));
        Files.delete(directory.resolve("b/config/linkedhardcore/status.json"));
        handler.onServerReady("beta");
        assertEquals(ServerState.UNAVAILABLE, statuses.get("beta").state());
        TransferHandler restarted = new TransferHandler(proxy, config, statuses, resetSignaller, LoggerFactory.getLogger("test"));
        BackendFixture.publish(directory.resolve("b/config/linkedhardcore"), "logical-b", "world-b", "boot-b", false, 0, null);
        restarted.onResetComplete("logical-b");
        assertFalse(statuses.get("beta").is(ServerState.READY));
        BackendFixture.publish(directory.resolve("b/config/linkedhardcore"), "logical-b", expected.requestId(), "new-boot", false, 0, expected.requestId());
        restarted.onServerReady("beta");
        assertEquals(ServerState.READY, statuses.get("beta").state());
        assertFalse(Files.exists(directory.resolve("b/config/linkedhardcore/reset.pending.json")));
    }

    @Test
    void oldCompletionOrSameBootCannotSatisfyANewerReset() throws Exception {
        ResetRequest expected = ResetRequest.create("logical-b", "world-b", "boot-b");
        new FileResetSignaller(config, LoggerFactory.getLogger("test")).signalReset(expected);
        Files.delete(directory.resolve("b/config/linkedhardcore/reset.request.json"));
        BackendFixture.publish(directory.resolve("b/config/linkedhardcore"), "logical-b", "older-reset", "older-boot", false, 0, "older-reset");
        handler.onResetComplete("logical-b");
        assertFalse(statuses.get("beta").is(ServerState.READY));
        Files.deleteIfExists(directory.resolve("b/config/linkedhardcore/reset.request.json"));
        BackendFixture.publish(directory.resolve("b/config/linkedhardcore"), "logical-b", expected.requestId(), "boot-b", false, 0, expected.requestId());
        handler.onResetComplete("logical-b");
        assertFalse(statuses.get("beta").is(ServerState.READY));
        assertTrue(Files.exists(directory.resolve("b/config/linkedhardcore/reset.pending.json")));
    }

    @Test
    void completionIsRejectedWhileRequestFileStillExists() throws Exception {
        ResetRequest expected = ResetRequest.create("logical-b", "world-b", "boot-b");
        new FileResetSignaller(config, LoggerFactory.getLogger("test")).signalReset(expected);
        BackendFixture.publish(directory.resolve("b/config/linkedhardcore"), "logical-b", expected.requestId(), "new-boot", false, 0, expected.requestId());
        handler.onServerReady("beta");
        assertFalse(statuses.get("beta").is(ServerState.READY));
    }

    @Test
    void reconnectResumesTheCurrentWorldAfterLogoutAndProxyRestart() throws Exception {
        Path currentWorld = directory.resolve("current-world.json");
        handler = new TransferHandler(proxy, config, statuses, resetSignaller, LoggerFactory.getLogger("test"),
            new DeathCounterState(), ignored -> { }, () -> { }, () -> { }, currentWorld);
        BackendFixture.publish(directory.resolve("b/config/linkedhardcore"), "logical-b", "world-b", "boot-b", true, 0, null);
        handler.onBackendJoined("beta");
        alphaPlayers.set(List.of());
        BackendFixture.publish(directory.resolve("a/config/linkedhardcore"), "logical-a", "world-a", "boot-a", true, 0, null);
        handler = new TransferHandler(proxy, config, statuses, resetSignaller, LoggerFactory.getLogger("test"),
            new DeathCounterState(), ignored -> { }, () -> { }, () -> { }, currentWorld);
        assertEquals(Optional.of(beta), handler.initialServer());
        assertEquals(ServerState.LIVE, statuses.get("beta").state());
        verify(resetSignaller, never()).signalReset(any());
        Files.delete(directory.resolve("b/config/linkedhardcore/status.json"));
        assertTrue(handler.initialServer().isEmpty());
    }

    @Test
    void backendJoinLatchesUseAgainstALaggingEmptyHeartbeat() {
        handler.onBackendJoined("beta");
        handler.onServerReady("beta");
        assertEquals(ServerState.LIVE, statuses.get("beta").state());
    }

    @Test
    void unavailableDestinationIsRecheckedWhenCountdownFinishes() throws Exception {
        handler.onPlayerDied(source, UUID.randomUUID());
        Files.delete(directory.resolve("b/config/linkedhardcore/status.json"));
        handler.onTransferReady(source);
        verify(player, never()).createConnectionRequest(any());
    }

    @Test
    void transferToBResetAThenReturnToVerifiedNewA() throws Exception {
        handler = new TransferHandler(proxy, config, statuses, new FileResetSignaller(config, LoggerFactory.getLogger("test")),
            LoggerFactory.getLogger("test"));
        Files.createDirectories(directory.resolve("a/world"));
        Files.writeString(directory.resolve("a/world/previous-run"), "old A world");
        ConnectionRequestBuilder.Result success = mock(ConnectionRequestBuilder.Result.class);
        when(success.isSuccessful()).thenReturn(true);
        when(request.connect()).thenAnswer(ignored -> {
            alphaPlayers.set(List.of());
            betaPlayers.set(List.of(player));
            return CompletableFuture.completedFuture(success);
        });
        handler.onPlayerDied(source, UUID.randomUUID());
        handler.onTransferReady(source);
        assertEquals(ServerState.RESETTING, statuses.get("alpha").state());
        ResetRequest resetA = new Gson().fromJson(Files.readString(directory.resolve("a/config/linkedhardcore/reset.pending.json")), ResetRequest.class);
        BackendFixture.publish(directory.resolve("a/config/linkedhardcore"), "logical-a", "world-a", "boot-a", false, 0, null);
        handler.onResetComplete("logical-a");
        assertFalse(statuses.get("alpha").is(ServerState.READY));
        runFixtureExecutor("a", "logical-a");
        assertFalse(Files.exists(directory.resolve("a/world/previous-run")));
        BackendFixture.publish(directory.resolve("a/config/linkedhardcore"), "logical-a", resetA.requestId(), "new-boot-a", false, 0, resetA.requestId());
        handler.onServerReady("alpha");
        assertEquals(ServerState.READY, statuses.get("alpha").state());
        assertFalse(Files.exists(directory.resolve("a/config/linkedhardcore/reset.pending.json")));

        BackendFixture.publish(directory.resolve("b/config/linkedhardcore"), "logical-b", "world-b", "boot-b", true, 1, null);
        Files.createDirectories(directory.resolve("b/world"));
        Files.writeString(directory.resolve("b/world/previous-run"), "old B world");
        ServerConnection sourceB = mock(ServerConnection.class);
        ServerInfo sourceBInfo = beta.getServerInfo();
        when(sourceB.getServerInfo()).thenReturn(sourceBInfo);
        when(player.getCurrentServer()).thenReturn(Optional.of(sourceB));
        ConnectionRequestBuilder returnRequest = mock(ConnectionRequestBuilder.class);
        when(player.createConnectionRequest(alpha)).thenReturn(returnRequest);
        when(returnRequest.connect()).thenAnswer(ignored -> {
            betaPlayers.set(List.of());
            alphaPlayers.set(List.of(player));
            return CompletableFuture.completedFuture(success);
        });
        handler.onPlayerDied(sourceB, UUID.randomUUID());
        handler.onTransferReady(sourceB);
        verify(player).createConnectionRequest(alpha);
        assertEquals(ServerState.LIVE, statuses.get("alpha").state());
        assertEquals(ServerState.RESETTING, statuses.get("beta").state());
        runFixtureExecutor("b", "logical-b");
        assertFalse(Files.exists(directory.resolve("b/world/previous-run")));
    }

    @Test
    void conflictingDurableExpectationCannotBeRemovedByAnOldCompletion() throws Exception {
        ResetRequest old = ResetRequest.create("logical-b", "world-b", "boot-b");
        FileResetSignaller signaller = new FileResetSignaller(config, LoggerFactory.getLogger("test"));
        signaller.signalReset(old);
        handler.onServerReady("beta");
        ResetRequest newer = ResetRequest.create("logical-b", "world-b", "boot-b");
        signaller.signalReset(newer);
        Files.delete(directory.resolve("b/config/linkedhardcore/reset.request.json"));
        BackendFixture.publish(directory.resolve("b/config/linkedhardcore"), "logical-b", old.requestId(), "new-boot", false, 0, old.requestId());
        handler.onResetComplete("logical-b");
        assertFalse(statuses.get("beta").is(ServerState.READY));
        ResetRequest retained = new Gson().fromJson(Files.readString(directory.resolve("b/config/linkedhardcore/reset.pending.json")), ResetRequest.class);
        assertEquals(newer, retained);
    }

    private void runFixtureExecutor(String directoryName, String logicalId) throws Exception {
        Path script = Path.of("../docker/entrypoint.sh").toAbsolutePath().normalize();
        assertTrue(Files.exists(script));
        Process process = new ProcessBuilder("bash", "-c",
            "source \"$1\"; SERVER_ID=\"$2\"; complete_world_reset \"$3\"", "fixture", script.toString(),
            logicalId, directory.resolve(directoryName).toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    @Test
    void everyoneLoggingOutDuringCountdownCanEnterTheFreshNextRun() throws Exception {
        Path lifecycle = directory.resolve("current-world.json");
        handler = durableHandler(lifecycle, new DeathCounterState(), resetSignaller);
        assertEquals(Optional.of(alpha), handler.initialServer());
        handler.onPlayerDied(source, UUID.randomUUID());
        alphaPlayers.set(List.of());
        // The joining player may already exist on the proxy while not on any backend.
        assertEquals(Optional.of(beta), handler.initialServer());
        assertEquals(1, newRuns.get());
        assertEquals(ServerState.RESETTING, statuses.get("alpha").state());
        assertFalse(new Gson().fromJson(Files.readString(lifecycle), com.google.gson.JsonObject.class)
            .has("pendingTransfer"));
        verify(player, never()).createConnectionRequest(any());
    }

    @Test
    void restartDuringWaitingNeverReopensTheEndedWorld() throws Exception {
        Path lifecycle = directory.resolve("current-world.json");
        UUID deceased = UUID.randomUUID();
        handler = durableHandler(lifecycle, new DeathCounterState(), new FileResetSignaller(config, LoggerFactory.getLogger("test")));
        handler.initialServer();
        Files.delete(directory.resolve("b/config/linkedhardcore/status.json"));
        Files.delete(directory.resolve("c/config/linkedhardcore/status.json"));
        handler.onPlayerDied(source, deceased);
        alphaPlayers.set(List.of());
        DeathCounterState restoredCounters = new DeathCounterState();
        handler = durableHandler(lifecycle, restoredCounters, new FileResetSignaller(config, LoggerFactory.getLogger("test")));
        assertTrue(handler.initialServer().isEmpty());
        assertEquals(1, restoredCounters.count(deceased));
        BackendFixture.publish(directory.resolve("b/config/linkedhardcore"), "logical-b", "world-b", "boot-b", false, 0, null);
        assertEquals(Optional.of(beta), handler.initialServer());
        assertEquals(1, restoredCounters.count(deceased));
        assertEquals(1, newRuns.get());
    }

    @Test
    void restartDuringCountdownFinishesTheFreshNextRunWithoutAPlayerCarrier() throws Exception {
        Path lifecycle = directory.resolve("current-world.json");
        handler = durableHandler(lifecycle, new DeathCounterState(), resetSignaller);
        handler.initialServer();
        handler.onPlayerDied(source, UUID.randomUUID());
        assertEquals("COUNTDOWN", new Gson().fromJson(Files.readString(lifecycle), com.google.gson.JsonObject.class)
            .getAsJsonObject("pendingTransfer").get("phase").getAsString());
        alphaPlayers.set(List.of());
        handler = durableHandler(lifecycle, new DeathCounterState(), resetSignaller);
        assertEquals(Optional.of(beta), handler.initialServer());
        assertEquals(1, newRuns.get());
    }

    @Test
    void restartDuringConnectionsResumesTheReservedWorldEvenAfterItWasUsed() throws Exception {
        Path lifecycle = directory.resolve("current-world.json");
        handler = durableHandler(lifecycle, new DeathCounterState(), resetSignaller);
        handler.initialServer();
        when(request.connect()).thenReturn(new CompletableFuture<>());
        handler.onPlayerDied(source, UUID.randomUUID());
        handler.onTransferReady(source);
        alphaPlayers.set(List.of());
        BackendFixture.publish(directory.resolve("b/config/linkedhardcore"), "logical-b", "world-b", "boot-b", true, 0, null);
        handler = durableHandler(lifecycle, new DeathCounterState(), resetSignaller);
        assertEquals(Optional.of(beta), handler.initialServer());
        assertEquals(1, newRuns.get());
    }

    @Test
    void resetPublicationFailureDoesNotLoseCommittedSourceCleanupOnRestart() throws Exception {
        Path lifecycle = directory.resolve("current-world.json");
        org.mockito.Mockito.doThrow(new java.io.IOException("fixture publication failure"))
            .when(resetSignaller).signalReset(any());
        handler = durableHandler(lifecycle, new DeathCounterState(), resetSignaller);
        handler.initialServer();
        completeSuccessfulTransfer();
        var saved = new Gson().fromJson(Files.readString(lifecycle), com.google.gson.JsonObject.class);
        assertEquals("world-a", saved.getAsJsonObject("resetAfterTransfer").get("alpha").getAsString());
        handler = durableHandler(lifecycle, new DeathCounterState(), new FileResetSignaller(config, LoggerFactory.getLogger("test")));
        handler.onServerReady("alpha");
        assertTrue(Files.exists(directory.resolve("a/config/linkedhardcore/reset.pending.json")));
        assertEquals(Optional.of(beta), handler.initialServer());
    }

    @Test
    void corruptLifecycleCannotSilentlyReopenAnOldRun() throws Exception {
        Path lifecycle = directory.resolve("current-world.json");
        Files.writeString(lifecycle, "{\"schemaVersion\":1,\"serverName\":\"unknown\",\"generationId\":\"old\"}");
        assertThrows(IllegalStateException.class, () -> durableHandler(lifecycle, new DeathCounterState(), resetSignaller));
    }

    @Test
    void anotherConnectingPlayerIsAdmittedDuringTheFirstArrivalsUseCheckpoint() throws Exception {
        when(request.connect()).thenReturn(new CompletableFuture<>());
        handler.onPlayerDied(source, UUID.randomUUID());
        handler.onTransferReady(source);
        Path metadata = directory.resolve("b/config/linkedhardcore/world-generation.json");
        var world = new Gson().fromJson(Files.readString(metadata), com.google.gson.JsonObject.class);
        world.addProperty("used", true);
        Files.writeString(metadata, world.toString());
        assertEquals(Optional.of(beta), handler.connectionServer(false, "beta"));
    }

    @Test
    void offlineCountdownCanRetargetAFailedDestinationToAnotherPreparedWorld() throws Exception {
        Path lifecycle = directory.resolve("current-world.json");
        handler = durableHandler(lifecycle, new DeathCounterState(), resetSignaller);
        handler.initialServer();
        handler.onPlayerDied(source, UUID.randomUUID());
        Files.delete(directory.resolve("b/config/linkedhardcore/status.json"));
        alphaPlayers.set(List.of());
        assertEquals(Optional.of(gamma), handler.initialServer());
        assertEquals(1, newRuns.get());
        var saved = new Gson().fromJson(Files.readString(lifecycle), com.google.gson.JsonObject.class);
        assertEquals("gamma", saved.get("serverName").getAsString());
        assertFalse(saved.has("pendingTransfer"));
    }

    @Test
    void upgradingAnEndedFlatCurrentWorldDoesNotResumeThatWorld() throws Exception {
        Path lifecycle = directory.resolve("current-world.json");
        Files.writeString(lifecycle, "{\"serverName\":\"alpha\",\"generationId\":\"world-a\"}");
        handler = durableHandler(lifecycle, new DeathCounterState(), resetSignaller);
        handler.recoverEndedRun(true);
        alphaPlayers.set(List.of());
        assertEquals(Optional.of(beta), handler.initialServer());
        assertEquals(1, newRuns.get());
    }

    @Test
    void restartInCommitWindowPreservesTheTargetAndRetriesCleanup() throws Exception {
        Path lifecycle = directory.resolve("current-world.json");
        handler = new TransferHandler(proxy, config, statuses, resetSignaller, LoggerFactory.getLogger("test"),
            new DeathCounterState(), ignored -> { }, () -> { },
            (java.util.function.Consumer<String>) id -> { throw new IllegalStateException("simulated commit crash"); }, lifecycle);
        handler.initialServer();
        completeSuccessfulTransfer(); // The simulated process interruption occurs in the connection completion future.
        var saved = new Gson().fromJson(Files.readString(lifecycle), com.google.gson.JsonObject.class);
        assertEquals("COMMITTING", saved.getAsJsonObject("pendingTransfer").get("phase").getAsString());
        assertEquals("beta", saved.get("serverName").getAsString());
        handler = durableHandler(lifecycle, new DeathCounterState(), new FileResetSignaller(config, LoggerFactory.getLogger("test")));
        assertEquals(Optional.of(beta), handler.initialServer());
        ResetRequest reset = new Gson().fromJson(Files.readString(directory.resolve("a/config/linkedhardcore/reset.pending.json")), ResetRequest.class);
        assertEquals("logical-a", reset.serverId());
        assertEquals("world-a", reset.previousGenerationId());
        verify(resetSignaller, never()).signalReset(any());
    }

    @Test
    void lateArrivalAtARetiredSourceCannotReplaceTheCommittedCurrentWorld() throws Exception {
        Path lifecycle = directory.resolve("current-world.json");
        handler = durableHandler(lifecycle, new DeathCounterState(), resetSignaller);
        handler.initialServer();
        completeSuccessfulTransfer();
        assertFalse(handler.onBackendJoined("alpha"));
        var saved = new Gson().fromJson(Files.readString(lifecycle), com.google.gson.JsonObject.class);
        assertEquals("beta", saved.get("serverName").getAsString());
    }

    @Test
    void lateSourceArrivalAfterFanOutIsDisconnectedAndDoesNotBlockCommit() throws Exception {
        Path lifecycle = directory.resolve("current-world.json");
        handler = durableHandler(lifecycle, new DeathCounterState(), resetSignaller);
        handler.initialServer();
        CompletableFuture<ConnectionRequestBuilder.Result> connection = new CompletableFuture<>();
        when(request.connect()).thenReturn(connection);
        handler.onPlayerDied(source, UUID.randomUUID());
        handler.onTransferReady(source);

        Player latePlayer = mock(Player.class);
        ServerConnection lateConnection = mock(ServerConnection.class);
        ServerInfo sourceInfo = alpha.getServerInfo();
        when(lateConnection.getServerInfo()).thenReturn(sourceInfo);
        when(latePlayer.getCurrentServer()).thenReturn(Optional.of(lateConnection));
        alphaPlayers.set(List.of(latePlayer));
        doAnswer(ignored -> {
            alphaPlayers.set(List.of());
            return null;
        }).when(latePlayer).disconnect(any());
        new ServerLifecycleListener(handler, new DeathCounterState()).onServerConnected(
            new ServerPostConnectEvent(latePlayer, null));

        verify(latePlayer).disconnect(any());
        verify(lateConnection, never()).sendPluginMessage(any(), any(byte[].class));
        ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
        when(result.isSuccessful()).thenReturn(true);
        connection.complete(result);
        assertEquals(1, newRuns.get());
        assertEquals(ServerState.RESETTING, statuses.get("alpha").state());
        var saved = new Gson().fromJson(Files.readString(lifecycle), com.google.gson.JsonObject.class);
        assertEquals("beta", saved.get("serverName").getAsString());
        assertFalse(saved.has("pendingTransfer"));
    }

    @Test
    void sourceArrivalsAreRejectedDuringRecoveryButDestinationArrivalsRemainAllowed() throws Exception {
        CompletableFuture<ConnectionRequestBuilder.Result> connection = new CompletableFuture<>();
        when(request.connect()).thenReturn(connection);
        handler.onPlayerDied(source, UUID.randomUUID());
        handler.onTransferReady(source);
        connection.complete(mock(ConnectionRequestBuilder.Result.class));

        assertFalse(handler.onBackendJoined("alpha"));
        assertTrue(handler.onBackendJoined("beta"));
        assertEquals(0, newRuns.get());
        verify(resetSignaller, never()).signalReset(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sourceArrivalsBeforeFanOutRemainInTheWaitOrCountdownFlow(boolean waiting) throws Exception {
        if (waiting) {
            Files.delete(directory.resolve("b/config/linkedhardcore/status.json"));
            Files.delete(directory.resolve("c/config/linkedhardcore/status.json"));
        }
        handler.onPlayerDied(source, UUID.randomUUID());
        clearInvocations(alpha);

        assertTrue(handler.onBackendJoined("alpha"));
        ArgumentCaptor<byte[]> frame = ArgumentCaptor.forClass(byte[].class);
        verify(alpha).sendPluginMessage(any(), frame.capture());
        assertArrayEquals(waiting ? Protocol.encodeWaitForServer() : Protocol.encodePrepareTransfer(), frame.getValue());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 7})
    void failedDeathCheckpointDoesNotAcceptOrCountTheDeathAndCanBeRetried(int previousDeaths) throws Exception {
        Path lifecycle = directory.resolve("current-world.json");
        UUID deceased = UUID.randomUUID();
        DeathCounterState counters = new DeathCounterState();
        if (previousDeaths > 0) counters.restore(new DeathCounterState.DeathRecord(deceased, "old-name", previousDeaths));
        List<DeathCounterState.DeathRecord> before = counters.snapshot();
        AtomicInteger persistedCounters = new AtomicInteger();
        handler = new TransferHandler(proxy, config, statuses, resetSignaller, LoggerFactory.getLogger("test"),
            counters, ignored -> persistedCounters.incrementAndGet(), endedRuns::incrementAndGet,
            newRuns::incrementAndGet, lifecycle);
        handler.initialServer();
        Path backup = directory.resolve("previous-current-world.json");
        Files.move(lifecycle, backup);
        String previousCheckpoint = Files.readString(backup);
        // A nonempty directory forces the real atomic replacement to fail on all platforms.
        Files.createDirectory(lifecycle);
        Path blocker = lifecycle.resolve("blocker");
        Files.writeString(blocker, "fixture");
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThrows(IllegalStateException.class, () -> handler.onPlayerDied(source, deceased));
            assertEquals(before, counters.snapshot());
            assertEquals(ServerState.LIVE, statuses.get("alpha").state());
            assertEquals(0, endedRuns.get());
            assertEquals(0, persistedCounters.get());
            verify(source, never()).sendPluginMessage(any(), any(byte[].class));
            verify(alpha, never()).sendPluginMessage(any(), any(byte[].class));
            assertEquals(previousCheckpoint, Files.readString(backup));
        }

        Files.delete(blocker);
        Files.delete(lifecycle);
        Files.move(backup, lifecycle);
        handler.onPlayerDied(source, deceased);
        handler.onPlayerDied(source, deceased); // A durable duplicate ACK must not count twice.
        assertEquals(previousDeaths + 1, counters.count(deceased));
        assertEquals(1, endedRuns.get());
        assertEquals(1, persistedCounters.get());
        verify(source, times(2)).sendPluginMessage(any(), any(byte[].class));
        var saved = new Gson().fromJson(Files.readString(lifecycle), com.google.gson.JsonObject.class);
        assertEquals(previousDeaths + 1, saved.getAsJsonObject("pendingTransfer")
            .getAsJsonObject("deathCounter").get("deaths").getAsInt());
        DeathCounterState restored = new DeathCounterState();
        durableHandler(lifecycle, restored, resetSignaller);
        assertEquals(previousDeaths + 1, restored.count(deceased));
    }

    @Test
    void staleEndedTimerFromTheSourceCannotEndTheAlreadyCommittedNewWorld() throws Exception {
        Path lifecycle = directory.resolve("current-world.json");
        handler = durableHandler(lifecycle, new DeathCounterState(), resetSignaller);
        handler.initialServer();
        completeSuccessfulTransfer();
        String committedRunId = new Gson().fromJson(Files.readString(lifecycle), com.google.gson.JsonObject.class)
            .get("runId").getAsString();
        AtomicReference<String> repairedTimerRunId = new AtomicReference<>();
        handler = new TransferHandler(proxy, config, statuses, resetSignaller, LoggerFactory.getLogger("test"),
            new DeathCounterState(), ignored -> { }, () -> { },
            (java.util.function.Consumer<String>) repairedTimerRunId::set, lifecycle);
        handler.recoverRunState(true, "old-source-run");
        assertEquals(committedRunId, repairedTimerRunId.get());
        assertEquals(Optional.of(beta), handler.initialServer());
        assertFalse(new Gson().fromJson(Files.readString(lifecycle), com.google.gson.JsonObject.class).has("pendingTransfer"));
    }

    private TransferHandler durableHandler(Path lifecycle, DeathCounterState counters, ResetSignaller signaller) {
        return new TransferHandler(proxy, config, statuses, signaller, LoggerFactory.getLogger("test"), counters,
            ignored -> { }, endedRuns::incrementAndGet, newRuns::incrementAndGet, lifecycle);
    }

    private void completeSuccessfulTransfer() {
        ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
        when(result.isSuccessful()).thenReturn(true);
        when(request.connect()).thenAnswer(ignored -> {
            alphaPlayers.set(List.of());
            return CompletableFuture.completedFuture(result);
        });
        handler.onPlayerDied(source, UUID.randomUUID());
        handler.onTransferReady(source);
    }

}

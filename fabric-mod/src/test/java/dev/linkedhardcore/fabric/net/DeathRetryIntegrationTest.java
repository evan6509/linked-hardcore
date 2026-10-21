package dev.linkedhardcore.fabric.net;

import com.google.gson.Gson;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import dev.linkedhardcore.fabric.config.ModConfig;
import dev.linkedhardcore.velocity.config.PluginConfig;
import dev.linkedhardcore.velocity.death.DeathCounterState;
import dev.linkedhardcore.velocity.death.DeathCounterStore;
import dev.linkedhardcore.velocity.model.ServerState;
import dev.linkedhardcore.velocity.model.ServerStatus;
import dev.linkedhardcore.velocity.net.ProxyMessageListener;
import dev.linkedhardcore.velocity.routing.TransferHandler;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeathRetryIntegrationTest {
    @TempDir Path directory;

    @BeforeAll
    static void initializeMinecraft(@TempDir Path configDirectory) throws Exception {
        try (var loader = mockStatic(FabricLoader.class)) {
            FabricLoader fabric = mock(FabricLoader.class);
            loader.when(FabricLoader::getInstance).thenReturn(fabric);
            when(fabric.getConfigDir()).thenReturn(configDirectory);
            Class.forName(ModConfig.class.getName());
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    void senderRetriesRejectedDeathAndDelayedFramesCannotEndTheNextRun(boolean restart, boolean missingIdentity) throws Exception {
        var logger = LoggerFactory.getLogger("test");
        ProxyServer proxy = mock(ProxyServer.class);
        RegisteredServer alpha = mock(RegisteredServer.class);
        RegisteredServer beta = mock(RegisteredServer.class);
        ServerInfo alphaInfo = new ServerInfo("alpha", new java.net.InetSocketAddress("localhost", 25566));
        ServerInfo betaInfo = new ServerInfo("beta", new java.net.InetSocketAddress("localhost", 25567));
        when(alpha.getServerInfo()).thenReturn(alphaInfo);
        when(beta.getServerInfo()).thenReturn(betaInfo);
        when(proxy.getServer("alpha")).thenReturn(Optional.of(alpha));
        when(proxy.getServer("beta")).thenReturn(Optional.of(beta));
        Player player = mock(Player.class);
        ServerConnection source = mock(ServerConnection.class);
        when(source.getServerInfo()).thenReturn(alphaInfo);
        when(source.getPlayer()).thenReturn(player);
        when(player.getCurrentServer()).thenReturn(Optional.of(source));
        when(player.getUsername()).thenReturn("deceased");
        AtomicReference<List<Player>> sourcePlayers = new AtomicReference<>(List.of(player));
        when(alpha.getPlayersConnected()).thenAnswer(ignored -> sourcePlayers.get());
        when(beta.getPlayersConnected()).thenReturn(List.of());
        when(proxy.getAllPlayers()).thenReturn(List.of(player));
        LinkedHashMap<String, PluginConfig.BackendServer> backends = new LinkedHashMap<>();
        for (String name : List.of("alpha", "beta")) {
            Path dir = directory.resolve(name);
            publishWorld(dir, name, "world-" + name, name.equals("alpha"));
            backends.put(name, new PluginConfig.BackendServer(name, name, dir.resolve("status.json").toString()));
        }
        PluginConfig config = new PluginConfig(backends, 1, 60);
        Map<String, ServerStatus> statuses = new LinkedHashMap<>();
        backends.keySet().forEach(name -> statuses.put(name, new ServerStatus(name, ServerState.UNAVAILABLE)));
        DeathCounterStore store = new DeathCounterStore(directory.resolve("counters.json"), logger);
        DeathCounterState counters = new DeathCounterState();
        AtomicInteger newRuns = new AtomicInteger();
        Path lifecycle = directory.resolve("current-world.json");
        TransferHandler handler = new TransferHandler(proxy, config, statuses, ignored -> { }, logger,
            counters, store::save, () -> { }, newRuns::incrementAndGet, lifecycle);
        handler.initialServer();
        AtomicReference<ProxyMessageListener> receiver = new AtomicReference<>(new ProxyMessageListener(handler, logger));
        String previous = Files.readString(lifecycle);
        Path worldIdentity = directory.resolve("alpha/world-generation.json");
        String previousIdentity = Files.readString(worldIdentity);
        if (missingIdentity) {
            Files.delete(worldIdentity);
        } else {
            Files.delete(lifecycle);
            Files.createDirectory(lifecycle);
            Files.writeString(lifecycle.resolve("blocker"), "fixture");
        }

        ModConfig modConfig = new ModConfig("alpha", 10, 5);
        ServerPlayer deceased = mock(ServerPlayer.class);
        UUID playerId = UUID.randomUUID();
        when(deceased.getUUID()).thenReturn(playerId);
        when(deceased.getName()).thenReturn(Component.literal("deceased"));
        AtomicLong clock = new AtomicLong();
        ProxyMessenger sender = new ProxyMessenger(modConfig, () -> "world-alpha", clock::get);
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList playerList = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(playerList);
        when(playerList.getPlayers()).thenReturn(List.of(deceased));
        when(proxy.getPlayer(playerId)).thenReturn(Optional.of(player));
        AtomicReference<byte[]> lastFrame = new AtomicReference<>();
        AtomicInteger rejected = new AtomicInteger();
        try (var networking = mockStatic(ServerPlayNetworking.class)) {
            networking.when(() -> ServerPlayNetworking.send(any(ServerPlayer.class), any(LinkedHardcorePayload.class)))
                .thenAnswer(invocation -> {
                    byte[] frame = ((LinkedHardcorePayload) invocation.getArgument(1)).data();
                    lastFrame.set(frame);
                    try {
                        receiver.get().onPluginMessage(new PluginMessageEvent(source, player,
                            dev.linkedhardcore.velocity.net.Protocol.CHANNEL, frame));
                    } catch (IllegalStateException expected) {
                        rejected.incrementAndGet(); // Receiver failures do not propagate back over the transport.
                    }
                    return null;
                });
            sender.sendPlayerDied(deceased);
            assertEquals(missingIdentity ? 0 : 1, rejected.get());
            assertEquals(0, counters.count(playerId));
            verify(source, never()).sendPluginMessage(any(), any(byte[].class));
            if (missingIdentity) {
                Files.writeString(worldIdentity, previousIdentity);
            } else {
                Files.delete(lifecycle.resolve("blocker"));
                Files.delete(lifecycle);
                Files.writeString(lifecycle, previous);
            }
            clock.set(10_001);
            sender.checkPendingAcks(server);
            assertEquals(1, counters.count(playerId));
            verify(source).sendPluginMessage(any(), any(byte[].class));
            // Lose the ACK and retry before handoff: the accepted death remains unique.
            clock.set(20_002);
            sender.checkPendingAcks(server);
            assertEquals(1, counters.count(playerId));

            ConnectionRequestBuilder connection = mock(ConnectionRequestBuilder.class);
            when(player.createConnectionRequest(beta)).thenReturn(connection);
            when(connection.connect()).thenAnswer(ignored -> {
                sourcePlayers.set(List.of());
                ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
                when(result.isSuccessful()).thenReturn(true);
                return CompletableFuture.completedFuture(result);
            });
            handler.onTransferReady(source);
            assertEquals(1, newRuns.get());
            if (restart) {
                counters = new DeathCounterState();
                store.loadInto(counters);
                handler = new TransferHandler(proxy, config, statuses, ignored -> { }, logger,
                    counters, store::save, () -> { }, newRuns::incrementAndGet, lifecycle);
                receiver.set(new ProxyMessageListener(handler, logger));
            }
            // A frame already in flight may arrive after commit, including after a proxy restart.
            receiver.get().onPluginMessage(new PluginMessageEvent(source, player,
                dev.linkedhardcore.velocity.net.Protocol.CHANNEL, lastFrame.get()));
            assertEquals(1, counters.count(playerId));
            assertFalse(new Gson().fromJson(Files.readString(lifecycle), com.google.gson.JsonObject.class)
                .has("pendingTransfer"));
        }
    }

    private static void publishWorld(Path dir, String id, String generation, boolean used) throws Exception {
        Files.createDirectories(dir);
        Gson gson = new Gson();
        Files.writeString(dir.resolve("world-generation.json"), gson.toJson(Map.of(
            "schemaVersion", 2, "serverId", id, "generationId", generation, "used", used)));
        Files.writeString(dir.resolve("status.json"), gson.toJson(Map.of(
            "schemaVersion", 2, "serverId", id, "generationId", generation, "worldUsed", used,
            "state", used ? "live" : "ready", "playerCount", used ? 1 : 0,
            "bootId", "boot-" + id, "updatedAt", Instant.now().toString())));
    }
}

package dev.linkedhardcore.fabric.net;

import dev.linkedhardcore.fabric.config.ModConfig;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProxyMessengerTest {
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

    @Test
    void unacknowledgedDeathIsRetriedInsteadOfDiscarded() {
        AtomicLong clock = new AtomicLong();
        ServerPlayer player = player(UUID.randomUUID());
        MinecraftServer server = server(player);
        ProxyMessenger messenger = messenger(clock);
        try (var networking = mockStatic(ServerPlayNetworking.class)) {
            messenger.sendPlayerDied(player);
            clock.set(10_001);
            messenger.checkPendingAcks(server);
            clock.set(20_002);
            messenger.checkPendingAcks(server);
            networking.verify(() -> ServerPlayNetworking.send(eq(player), any(LinkedHardcorePayload.class)), times(3));
            var frame = org.mockito.ArgumentCaptor.forClass(LinkedHardcorePayload.class);
            networking.verify(() -> ServerPlayNetworking.send(eq(player), frame.capture()), times(3));
            for (LinkedHardcorePayload payload : frame.getAllValues()) {
                assertArrayEquals(Protocol.encodePlayerDied(player.getUUID(), "world-a"), payload.data());
            }
        }
    }

    @Test
    void onlyMatchingWorldAckStopsRetries() {
        AtomicLong clock = new AtomicLong();
        ServerPlayer player = player(UUID.randomUUID());
        MinecraftServer server = server(player);
        ProxyMessenger messenger = messenger(clock);
        try (var networking = mockStatic(ServerPlayNetworking.class)) {
            messenger.sendPlayerDied(player);
            messenger.handleInbound(server, ack(player.getUUID(), "old-world"));
            messenger.handleInbound(server, ack(UUID.randomUUID(), "world-a"));
            messenger.handleInbound(server, ByteBuffer.allocate(17).put(Protocol.OP_ACK)
                .putLong(player.getUUID().getMostSignificantBits()).putLong(player.getUUID().getLeastSignificantBits()).array());
            clock.set(10_001);
            messenger.checkPendingAcks(server);
            networking.verify(() -> ServerPlayNetworking.send(eq(player), any(LinkedHardcorePayload.class)), times(2));
            messenger.handleInbound(server, ack(player.getUUID(), "world-a"));
            clock.set(20_002);
            messenger.checkPendingAcks(server);
            networking.verify(() -> ServerPlayNetworking.send(eq(player), any(LinkedHardcorePayload.class)), times(2));
        }
    }

    @Test
    void retryUsesReplacementPlayerAndRetainsEventsWithoutACarrier() {
        AtomicLong clock = new AtomicLong();
        UUID uuid = UUID.randomUUID();
        ServerPlayer deceased = player(uuid);
        ServerPlayer respawned = player(uuid);
        ServerPlayer survivor = player(UUID.randomUUID());
        MinecraftServer server = server(respawned);
        ProxyMessenger messenger = messenger(clock);
        try (var networking = mockStatic(ServerPlayNetworking.class)) {
            messenger.sendPlayerDied(deceased);
            clock.set(10_001);
            messenger.checkPendingAcks(server);
            networking.verify(() -> ServerPlayNetworking.send(eq(respawned), any(LinkedHardcorePayload.class)));
            when(server.getPlayerList().getPlayers()).thenReturn(List.of());
            clock.set(20_002);
            messenger.checkPendingAcks(server);
            when(server.getPlayerList().getPlayers()).thenReturn(List.of(survivor));
            clock.set(30_003);
            messenger.checkPendingAcks(server);
            networking.verify(() -> ServerPlayNetworking.send(eq(deceased), any(LinkedHardcorePayload.class)));
            networking.verify(() -> ServerPlayNetworking.send(eq(survivor), any(LinkedHardcorePayload.class)));
        }
    }

    @Test
    void retryWaitsForTheConfiguredTimeout() {
        AtomicLong clock = new AtomicLong();
        ServerPlayer player = player(UUID.randomUUID());
        MinecraftServer server = server(player);
        ProxyMessenger messenger = messenger(clock);
        try (var networking = mockStatic(ServerPlayNetworking.class)) {
            messenger.sendPlayerDied(player);
            clock.set(5_000);
            messenger.checkPendingAcks(server);
            clock.set(10_001);
            messenger.checkPendingAcks(server);
            clock.set(10_002);
            messenger.checkPendingAcks(server);
            networking.verify(() -> ServerPlayNetworking.send(eq(player), any(LinkedHardcorePayload.class)), times(2));
        }
    }

    @Test
    void preparedDeathSurvivesCrashBeforeFirstSendAndWaitsForReconnect() {
        AtomicLong clock = new AtomicLong();
        UUID deceased = UUID.randomUUID();
        ProxyMessenger original = messenger(clock);
        MinecraftServer server = server();
        ServerPlayer carrier = player(UUID.randomUUID());
        try (var networking = mockStatic(ServerPlayNetworking.class)) {
            original.preparePlayerDeath(deceased); // No send has occurred at this crash point.
            ProxyMessenger restarted = messenger(clock);
            restarted.checkPendingAcks(server);
            networking.verifyNoInteractions();
            when(server.getPlayerList().getPlayers()).thenReturn(List.of(carrier));
            restarted.checkPendingAcks(server); // Reconnect need not wait for a new death callback.
            networking.verify(() -> ServerPlayNetworking.send(eq(carrier), argThat((LinkedHardcorePayload payload) ->
                java.util.Arrays.equals(Protocol.encodePlayerDied(deceased, "world-a"), payload.data()))));
        }
    }

    @Test
    void ordinaryDeathAndMatchingAckRemainDurableAcrossRestarts() {
        AtomicLong clock = new AtomicLong();
        ServerPlayer player = player(UUID.randomUUID());
        MinecraftServer server = server(player);
        try (var networking = mockStatic(ServerPlayNetworking.class)) {
            messenger(clock).sendPlayerDied(player);
            ProxyMessenger restarted = messenger(clock);
            restarted.handleInbound(server, ack(player.getUUID(), "wrong-world"));
            restarted.handleInbound(server, ack(UUID.randomUUID(), "world-a"));
            restarted.handleInbound(server, dev.linkedhardcore.velocity.net.Protocol.encodeAck(player.getUUID()));
            restarted = messenger(clock); // A mismatched ACK must not alter disk either.
            restarted.checkPendingAcks(server);
            networking.verify(() -> ServerPlayNetworking.send(eq(player), any(LinkedHardcorePayload.class)), times(2));
            restarted.handleInbound(server, ack(player.getUUID(), "world-a"));
            clock.set(99_999);
            messenger(clock).checkPendingAcks(server);
            networking.verify(() -> ServerPlayNetworking.send(eq(player), any(LinkedHardcorePayload.class)), times(2));
        }
    }

    @Test
    void retiredGenerationIsQuarantinedAndCannotClearANewerDeath() {
        AtomicLong clock = new AtomicLong();
        ServerPlayer player = player(UUID.randomUUID());
        MinecraftServer server = server(player);
        messenger(clock).preparePlayerDeath(player.getUUID());
        ProxyMessenger nextWorld = new ProxyMessenger(new ModConfig("a", 10, 5), () -> "world-b",
            clock::get, directory.resolve("outbox.json"));
        try (var networking = mockStatic(ServerPlayNetworking.class)) {
            nextWorld.checkPendingAcks(server);
            nextWorld.handleInbound(server, ack(player.getUUID(), "world-a"));
            networking.verifyNoInteractions();
            nextWorld.sendPlayerDied(player);
            nextWorld.handleInbound(server, ack(player.getUUID(), "world-a"));
            clock.set(10_001);
            nextWorld.checkPendingAcks(server);
            networking.verify(() -> ServerPlayNetworking.send(eq(player), argThat((LinkedHardcorePayload payload) ->
                java.util.Arrays.equals(Protocol.encodePlayerDied(player.getUUID(), "world-b"), payload.data()))), times(2));
            assertEquals(2, new DeathOutbox(directory.resolve("outbox.json"), "a").pending().size());
        }
    }

    @Test
    void failedPrepareDoesNotPermitFirstSend() throws Exception {
        ProxyMessenger sender = messenger(new AtomicLong());
        java.nio.file.Files.createDirectory(directory.resolve("outbox.json"));
        java.nio.file.Files.writeString(directory.resolve("outbox.json/blocker"), "fixture");
        try (var networking = mockStatic(ServerPlayNetworking.class)) {
            assertThrows(IllegalStateException.class, () -> sender.sendPlayerDied(player(UUID.randomUUID())));
            networking.verifyNoInteractions();
        }
    }

    private ProxyMessenger messenger(AtomicLong clock) {
        return new ProxyMessenger(new ModConfig("a", 10, 5), () -> "world-a", clock::get, directory.resolve("outbox.json"));
    }

    private static ServerPlayer player(UUID uuid) {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(uuid);
        when(player.getName()).thenReturn(Component.literal("player"));
        return player;
    }

    private static MinecraftServer server(ServerPlayer... players) {
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList playerList = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(playerList);
        when(playerList.getPlayers()).thenReturn(List.of(players));
        return server;
    }

    private static byte[] ack(UUID player, String generation) {
        byte[] bytes = generation.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(18 + bytes.length).put(Protocol.OP_WORLD_ACK)
            .putLong(player.getMostSignificantBits()).putLong(player.getLeastSignificantBits())
            .put((byte) bytes.length).put(bytes).array();
    }
}

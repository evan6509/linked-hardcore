package dev.linkedhardcore.fabric.death;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class TransferCountdownTest {
    @BeforeAll
    static void initializeMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void deadPlayerUsesVanillaRespawnAndSpectatesTheReplacementEntity() {
        ServerPlayer dead = mock(ServerPlayer.class);
        ServerPlayer respawned = mock(ServerPlayer.class);
        ServerGamePacketListenerImpl connection = mock(ServerGamePacketListenerImpl.class);
        dead.connection = connection;
        connection.player = dead;
        when(dead.getHealth()).thenReturn(0f);
        when(respawned.getName()).thenReturn(Component.literal("player"));
        doAnswer(invocation -> { connection.player = respawned; return null; })
            .when(connection).handleClientCommand(any());
        TransferCountdown.spectatePlayer(dead);
        ArgumentCaptor<ServerboundClientCommandPacket> packet = ArgumentCaptor.forClass(ServerboundClientCommandPacket.class);
        verify(connection).handleClientCommand(packet.capture());
        assertEquals(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN, packet.getValue().getAction());
        verify(respawned).setGameMode(GameType.SPECTATOR);
        verify(dead, never()).setGameMode(any());
    }

    @Test
    void livingPlayerIsSpectatedWithoutReplacingOrRespawningIt() {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerGamePacketListenerImpl connection = mock(ServerGamePacketListenerImpl.class);
        player.connection = connection;
        when(player.getHealth()).thenReturn(20f);
        when(player.getName()).thenReturn(Component.literal("player"));
        TransferCountdown.spectatePlayer(player);
        verify(player).setGameMode(GameType.SPECTATOR);
        verify(connection, never()).handleClientCommand(any());
    }
}

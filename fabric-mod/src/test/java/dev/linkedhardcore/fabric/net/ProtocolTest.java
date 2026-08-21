package dev.linkedhardcore.fabric.net;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolTest {

    @Test
    void encodesPlayerDeathsInTheSharedWireFormat() {
        UUID player = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        byte[] expected = ByteBuffer.allocate(17)
            .put(Protocol.OP_PLAYER_DIED)
            .putLong(player.getMostSignificantBits())
            .putLong(player.getLeastSignificantBits())
            .array();

        assertArrayEquals(expected, Protocol.encodePlayerDied(player));
    }

    @Test
    void decodesProxyAckFrames() {
        UUID player = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        byte[] frame = ByteBuffer.allocate(17)
            .put(Protocol.OP_ACK)
            .putLong(player.getMostSignificantBits())
            .putLong(player.getLeastSignificantBits())
            .array();

        Protocol.Inbound inbound = Protocol.decode(frame);

        assertTrue(inbound.isAck());
        assertEquals(player, inbound.playerUuid());
    }

    @Test
    void rejectsMalformedFramesWithoutThrowing() {
        assertFalse(Protocol.decode(new byte[0]).isAck());
        assertFalse(Protocol.decode(new byte[] {Protocol.OP_ACK}).isAck());
    }

    @Test
    void decodesProxyDeathCounterSnapshots() {
        UUID player = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        byte[] name = "player-one".getBytes(StandardCharsets.UTF_8);
        byte[] frame = ByteBuffer.allocate(1 + 1 + 16 + 1 + name.length + 1)
            .put(Protocol.OP_DEATH_COUNTERS)
            .put((byte) 1)
            .putLong(player.getMostSignificantBits())
            .putLong(player.getLeastSignificantBits())
            .put((byte) name.length)
            .put(name)
            .put((byte) 3)
            .array();

        Protocol.Inbound inbound = Protocol.decode(frame);

        assertTrue(inbound.isDeathCounters());
        assertEquals(List.of(new Protocol.DeathCounter(player, "player-one", 3)), inbound.deathCounters());
    }
}

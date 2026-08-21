package dev.linkedhardcore.fabric.net;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
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
}

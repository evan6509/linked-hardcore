package dev.linkedhardcore.velocity.net;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolTest {

    private static final UUID PLAYER = UUID.fromString("12345678-1234-5678-9abc-def012345678");

    @Test
    void encodesRunTimeAsMillisecondsAndAnEndedFlag() {
        byte[] expected = ByteBuffer.allocate(10).put(Protocol.OP_RUN_TIMER)
            .putLong(3_723_456L).put((byte) 1).array();
        assertArrayEquals(expected, Protocol.encodeRunTimer(3_723_456L, true));
        assertArrayEquals(ByteBuffer.allocate(10).put(Protocol.OP_RUN_TIMER)
            .putLong(0).put((byte) 0).array(), Protocol.encodeRunTimer(0, false));
        assertThrows(IllegalArgumentException.class, () -> Protocol.encodeRunTimer(-1, false));
    }

    @Test
    void decodesTheFabricPlayerDiedFrame() {
        byte[] frame = ByteBuffer.allocate(17)
            .put(Protocol.OP_PLAYER_DIED)
            .putLong(PLAYER.getMostSignificantBits())
            .putLong(PLAYER.getLeastSignificantBits())
            .array();

        Protocol.Inbound inbound = Protocol.decodeInbound(frame).orElseThrow();

        assertTrue(inbound.isPlayerDied());
        assertEquals(PLAYER, inbound.playerUuid());
    }

    @Test
    void encodesAckAsTheDocumentedRawUuidFrame() {
        byte[] expected = ByteBuffer.allocate(17)
            .put(Protocol.OP_ACK)
            .putLong(PLAYER.getMostSignificantBits())
            .putLong(PLAYER.getLeastSignificantBits())
            .array();

        assertArrayEquals(expected, Protocol.encodeAck(PLAYER));
    }

    @Test
    void decodesVarIntResetCompleteAndRejectsMalformedFrames() {
        byte[] serverId = "alpha".getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[serverId.length + 2];
        frame[0] = Protocol.OP_RESET_COMPLETE;
        frame[1] = (byte) serverId.length;
        System.arraycopy(serverId, 0, frame, 2, serverId.length);

        Protocol.Inbound inbound = Protocol.decodeInbound(frame).orElseThrow();

        assertTrue(inbound.isResetComplete());
        assertEquals("alpha", inbound.serverId());
        assertFalse(Protocol.decodeInbound(new byte[0]).isPresent());
        assertFalse(Protocol.decodeInbound(new byte[] {Protocol.OP_PLAYER_DIED}).isPresent());
    }
}

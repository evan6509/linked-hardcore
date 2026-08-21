package dev.linkedhardcore.velocity.net;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Wire protocol between the Velocity plugin and the Fabric mod. */
public final class Protocol {
    public static final String CHANNEL_NAME = "linkedhardcore:main";
    public static final ChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(CHANNEL_NAME);

    public static final byte OP_PLAYER_DIED = 0x01;
    public static final byte OP_PREPARE_TRANSFER = 0x02;
    public static final byte OP_RESET_COMPLETE = 0x03;
    public static final byte OP_ACK = 0x04;
    public static final byte OP_TRANSFER_READY = 0x05;
    public static final byte OP_WAIT_FOR_SERVER = 0x06;
    public static final byte OP_DEATH_COUNTERS = 0x07;

    private Protocol() {}

    public record DeathCounter(UUID playerUuid, String playerName, int deaths) {}

    public record Inbound(byte opcode, UUID playerUuid, String serverId, List<DeathCounter> deathCounters) {
        public boolean isPlayerDied() { return opcode == OP_PLAYER_DIED; }
        public boolean isResetComplete() { return opcode == OP_RESET_COMPLETE; }
        public boolean isTransferReady() { return opcode == OP_TRANSFER_READY; }
    }

    public static Optional<Inbound> decodeInbound(byte[] data) {
        if (data == null || data.length < 1) return Optional.empty();
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            byte opcode = in.readByte();
            return switch (opcode) {
                case OP_PLAYER_DIED -> Optional.of(new Inbound(opcode, readUuid(in), null, List.of()));
                case OP_RESET_COMPLETE -> Optional.of(new Inbound(opcode, null, readString(in), List.of()));
                case OP_TRANSFER_READY -> Optional.of(new Inbound(opcode, null, null, List.of()));
                default -> Optional.empty();
            };
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public static byte[] encodePrepareTransfer() { return opcode(OP_PREPARE_TRANSFER); }
    public static byte[] encodeWaitForServer() { return opcode(OP_WAIT_FOR_SERVER); }

    public static byte[] encodeAck(UUID playerUuid) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(OP_ACK);
        writeUuid(out, playerUuid);
        return out.toByteArray();
    }

    public static byte[] encodeDeathCounters(List<DeathCounter> counters) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(OP_DEATH_COUNTERS);
        writeVarInt(out, counters.size());
        for (DeathCounter counter : counters) {
            writeUuid(out, counter.playerUuid());
            writeString(out, counter.playerName());
            writeVarInt(out, counter.deaths());
        }
        return out.toByteArray();
    }

    private static byte[] opcode(byte opcode) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(opcode);
        return out.toByteArray();
    }

    private static void writeUuid(ByteArrayDataOutput out, UUID uuid) {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void writeString(ByteArrayDataOutput out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        if (length < 0 || length > 32767) throw new IOException("invalid string length");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeVarInt(ByteArrayDataOutput out, int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            out.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.writeByte(remaining);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        while (shift < 35) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
        }
        throw new IOException("VarInt too big");
    }
}

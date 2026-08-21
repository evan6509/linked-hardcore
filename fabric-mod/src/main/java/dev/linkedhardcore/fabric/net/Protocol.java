package dev.linkedhardcore.fabric.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Wire protocol between the Fabric mod and the Velocity plugin. */
public final class Protocol {
    public static final String CHANNEL_NAME = "linkedhardcore:main";
    public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("linkedhardcore", "main");

    public static final byte OP_PLAYER_DIED = 0x01;
    public static final byte OP_PREPARE_TRANSFER = 0x02;
    public static final byte OP_RESET_COMPLETE = 0x03;
    public static final byte OP_ACK = 0x04;
    public static final byte OP_TRANSFER_READY = 0x05;
    public static final byte OP_WAIT_FOR_SERVER = 0x06;
    public static final byte OP_DEATH_COUNTERS = 0x07;

    private Protocol() {}

    public record DeathCounter(UUID playerUuid, String playerName, int deaths) {}

    public static byte[] encodePlayerDied(UUID playerUuid) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(OP_PLAYER_DIED);
        buf.writeUUID(playerUuid);
        return drain(buf);
    }

    public static byte[] encodeResetComplete(String serverId) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(OP_RESET_COMPLETE);
        buf.writeUtf(serverId);
        return drain(buf);
    }

    public static byte[] encodeTransferReady() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(OP_TRANSFER_READY);
        return drain(buf);
    }

    public static byte[] encodeDeathCounters(List<DeathCounter> counters) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(OP_DEATH_COUNTERS);
        buf.writeVarInt(counters.size());
        for (DeathCounter counter : counters) {
            buf.writeUUID(counter.playerUuid());
            buf.writeUtf(counter.playerName());
            buf.writeVarInt(counter.deaths());
        }
        return drain(buf);
    }

    public record Inbound(byte opcode, UUID playerUuid, List<DeathCounter> deathCounters) {
        public boolean isPrepareTransfer() { return opcode == OP_PREPARE_TRANSFER; }
        public boolean isWaitForServer() { return opcode == OP_WAIT_FOR_SERVER; }
        public boolean isAck() { return opcode == OP_ACK; }
        public boolean isDeathCounters() { return opcode == OP_DEATH_COUNTERS; }
    }

    public static Inbound decode(byte[] data) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            byte opcode = buf.readByte();
            return switch (opcode) {
                case OP_PREPARE_TRANSFER, OP_WAIT_FOR_SERVER -> new Inbound(opcode, null, List.of());
                case OP_ACK -> new Inbound(opcode, buf.readUUID(), List.of());
                case OP_DEATH_COUNTERS -> {
                    int count = buf.readVarInt();
                    if (count < 0 || count > 10000) throw new IllegalArgumentException("invalid counter count");
                    List<DeathCounter> counters = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        counters.add(new DeathCounter(buf.readUUID(), buf.readUtf(32767), buf.readVarInt()));
                    }
                    yield new Inbound(opcode, null, counters);
                }
                default -> new Inbound(opcode, null, List.of());
            };
        } finally {
            buf.release();
        }
    }

    private static byte[] drain(FriendlyByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }
}

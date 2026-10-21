package dev.linkedhardcore.fabric.net;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Wire protocol between the Fabric mod and the Velocity plugin.
 *
 * <p>This is THE seam between the two codebases: the byte layout is implemented
 * independently on each side (zero compile-time coupling). Any change here MUST
 * be mirrored in {@code velocity-plugin/.../net/Protocol.java} and documented in
 * {@code docs/PROTOCOL.md}.
 *
 * <p>All players form a single linked life pool — there are no groups, so the
 * messages carry no group identifier.
 *
 * <p>Encoding follows Minecraft conventions (matches the plugin's {@code Protocol}):
 * <ul>
 *   <li>Integers are big-endian.</li>
 *   <li>Strings are {@code varint length + UTF-8 bytes}.</li>
 *   <li>UUIDs are 16 raw bytes: 8-byte most-significant half, then 8-byte
 *       least-significant half.</li>
 * </ul>
 *
 * <pre>
 *  Channel: linkedhardcore:main
 *
 *  [0x01] PLAYER_DIED       mod -> proxy
 *      byte  opcode = 0x01
 *      byte[16] playerUuid
 *
 *  [0x02] PREPARE_TRANSFER  proxy -> mod
 *      byte  opcode = 0x02
 *
 *  [0x03] RESET_COMPLETE    mod -> proxy
 *      byte  opcode = 0x03
 *      varint + utf8  serverId
 *
 *  [0x04] ACK               proxy -> mod
 *      byte  opcode = 0x04
 *      byte[16] playerUuid
 *
 *  [0x05] TRANSFER_READY    mod -> proxy
 *      byte  opcode = 0x05
 *
 *  [0x06] WAIT_FOR_SERVER   proxy -> mod
 *      byte  opcode = 0x06
 *
 *  [0x07] DEATH_COUNTERS    proxy -> mod
 *      varint count, then UUID + UTF-8 name + varint deaths per player
 *
 *  [0x08] RUN_TIMER         proxy -> mod
 *      long elapsedMillis, byte ended (0 or 1)
 * </pre>
 */
public final class Protocol {

    public static final String CHANNEL_NAME = "linkedhardcore:main";
    public static final int PROTOCOL_VERSION = 2;
    public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("linkedhardcore", "main");

    public static final byte OP_PLAYER_DIED = 0x01;
    public static final byte OP_PREPARE_TRANSFER = 0x02;
    public static final byte OP_RESET_COMPLETE = 0x03;
    public static final byte OP_ACK = 0x04;
    public static final byte OP_TRANSFER_READY = 0x05;
    public static final byte OP_WAIT_FOR_SERVER = 0x06;
    public static final byte OP_DEATH_COUNTERS = 0x07;
    public static final byte OP_RUN_TIMER = 0x08;
    public static final byte OP_WORLD_PLAYER_DIED = 0x09;
    public static final byte OP_WORLD_ACK = 0x0a;

    private Protocol() {
    }

    // ---- Encoders (mod -> proxy) ---------------------------------------------

    /** PLAYER_DIED: opcode + 16-byte UUID. */
    public static byte[] encodePlayerDied(UUID playerUuid) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(OP_PLAYER_DIED);
        buf.writeLong(playerUuid.getMostSignificantBits());
        buf.writeLong(playerUuid.getLeastSignificantBits());
        return drain(buf);
    }

    /** Retry identity: the deceased player and the exact ended world generation. */
    public static byte[] encodePlayerDied(UUID playerUuid, String generationId) {
        if (generationId == null || generationId.isBlank() || generationId.length() > 32_767) {
            throw new IllegalArgumentException("Invalid death world generation");
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(OP_WORLD_PLAYER_DIED);
        buf.writeUUID(playerUuid);
        buf.writeUtf(generationId);
        return drain(buf);
    }

    /** RESET_COMPLETE: opcode + varint-string serverId. */
    public static byte[] encodeResetComplete(String serverId) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(OP_RESET_COMPLETE);
        buf.writeUtf(serverId);
        return drain(buf);
    }

    /** TRANSFER_READY: opcode only. Sent after the countdown. */
    public static byte[] encodeTransferReady() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(OP_TRANSFER_READY);
        return drain(buf);
    }

    // ---- Decoders (proxy -> mod) ---------------------------------------------

    /** Result of decoding an inbound proxy message. */
    /** A proxy-authoritative death total for one player. */
    public record DeathCounter(UUID playerUuid, String playerName, int deaths) {
    }

    public record RunTimer(long elapsedMillis, boolean ended) {
    }

    public record Inbound(byte opcode, UUID playerUuid, List<DeathCounter> deathCounters, RunTimer runTimer,
                          String generationId) {
        public Inbound(byte opcode, UUID playerUuid, List<DeathCounter> deathCounters, RunTimer runTimer) {
            this(opcode, playerUuid, deathCounters, runTimer, null);
        }
        public boolean isPrepareTransfer() {
            return opcode == OP_PREPARE_TRANSFER;
        }

        public boolean isWaitForServer() {
            return opcode == OP_WAIT_FOR_SERVER;
        }

        public boolean isAck() {
            return opcode == OP_ACK || opcode == OP_WORLD_ACK;
        }

        public boolean isDeathCounters() {
            return opcode == OP_DEATH_COUNTERS;
        }

        public boolean isRunTimer() {
            return opcode == OP_RUN_TIMER;
        }
    }

    /** Decodes a raw payload frame received from the proxy. */
    public static Inbound decode(byte[] data) {
        if (data == null || data.length == 0) {
            return new Inbound((byte) 0, null, List.of(), null);
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            byte opcode = buf.readByte();
            return switch (opcode) {
                case OP_PREPARE_TRANSFER -> new Inbound(opcode, null, List.of(), null);
                case OP_WAIT_FOR_SERVER -> new Inbound(opcode, null, List.of(), null);
                case OP_ACK -> buf.readableBytes() == 16
                    ? new Inbound(opcode, buf.readUUID(), List.of(), null) : new Inbound((byte) 0, null, List.of(), null);
                case OP_WORLD_ACK -> {
                    UUID player = buf.readUUID();
                    String generation = buf.readUtf();
                    yield !generation.isBlank() && buf.readableBytes() == 0
                        ? new Inbound(opcode, player, List.of(), null, generation)
                        : new Inbound((byte) 0, null, List.of(), null);
                }
                case OP_DEATH_COUNTERS -> {
                    int count = buf.readVarInt();
                    if (count < 0 || count > 10_000) {
                        yield new Inbound((byte) 0, null, List.of(), null);
                    }
                    List<DeathCounter> counters = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        counters.add(new DeathCounter(buf.readUUID(), buf.readUtf(32_767), buf.readVarInt()));
                    }
                    yield new Inbound(opcode, null, List.copyOf(counters), null);
                }
                case OP_RUN_TIMER -> {
                    if (buf.readableBytes() != 9) {
                        yield new Inbound((byte) 0, null, List.of(), null);
                    }
                    long elapsedMillis = buf.readLong();
                    int ended = buf.readUnsignedByte();
                    yield elapsedMillis >= 0 && ended <= 1
                        ? new Inbound(opcode, null, List.of(), new RunTimer(elapsedMillis, ended == 1))
                        : new Inbound((byte) 0, null, List.of(), null);
                }
                default -> new Inbound(opcode, null, List.of(), null);
            };
        } catch (IndexOutOfBoundsException | IllegalArgumentException | DecoderException e) {
            return new Inbound((byte) 0, null, List.of(), null);
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

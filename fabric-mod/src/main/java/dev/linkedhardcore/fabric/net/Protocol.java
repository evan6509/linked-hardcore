package dev.linkedhardcore.fabric.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * Wire protocol between the Fabric mod and the Velocity plugin.
 *
 * <p>This is THE seam between the two codebases: the byte layout is implemented
 * independently on each side (zero compile-time coupling). Any change here MUST
 * be mirrored in {@code velocity-plugin/.../net/Protocol.java} and documented in
 * {@code docs/PROTOCOL.md}.
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
 *  [0x01] PLAYER_DIED      mod -> proxy
 *      byte  opcode = 0x01
 *      byte[16] playerUuid
 *      varint + utf8  groupId
 *
 *  [0x02] GROUP_ELIMINATED proxy -> mod
 *      byte  opcode = 0x02
 *      varint + utf8  groupId
 *
 *  [0x03] RESET_COMPLETE   mod -> proxy
 *      byte  opcode = 0x03
 *      varint + utf8  serverId
 *
 *  [0x04] ACK              proxy -> mod
 *      byte  opcode = 0x04
 *      byte[16] playerUuid
 * </pre>
 */
public final class Protocol {

    public static final String CHANNEL_NAME = "linkedhardcore:main";
    public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("linkedhardcore", "main");

    public static final byte OP_PLAYER_DIED = 0x01;
    public static final byte OP_GROUP_ELIMINATED = 0x02;
    public static final byte OP_RESET_COMPLETE = 0x03;
    public static final byte OP_ACK = 0x04;

    private Protocol() {
    }

    // ---- Encoders (mod -> proxy) ---------------------------------------------

    /** PLAYER_DIED: opcode + 16-byte UUID + varint-string groupId. */
    public static byte[] encodePlayerDied(UUID playerUuid, String groupId) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(OP_PLAYER_DIED);
        buf.writeLong(playerUuid.getMostSignificantBits());
        buf.writeLong(playerUuid.getLeastSignificantBits());
        buf.writeUtf(groupId);
        return drain(buf);
    }

    /** RESET_COMPLETE: opcode + varint-string serverId. */
    public static byte[] encodeResetComplete(String serverId) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(OP_RESET_COMPLETE);
        buf.writeUtf(serverId);
        return drain(buf);
    }

    // ---- Decoders (proxy -> mod) ---------------------------------------------

    /** Result of decoding an inbound proxy message. */
    public record Inbound(byte opcode, UUID playerUuid, String string) {
        public boolean isGroupEliminated() {
            return opcode == OP_GROUP_ELIMINATED;
        }

        public boolean isAck() {
            return opcode == OP_ACK;
        }
    }

    /** Decodes a raw payload frame received from the proxy. */
    public static Inbound decode(byte[] data) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        byte opcode = buf.readByte();
        return switch (opcode) {
            case OP_GROUP_ELIMINATED -> new Inbound(opcode, null, buf.readUtf());
            case OP_ACK -> new Inbound(opcode, buf.readUUID(), null);
            default -> new Inbound(opcode, null, null);
        };
    }

    private static byte[] drain(FriendlyByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }
}

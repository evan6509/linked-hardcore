package dev.linkedhardcore.velocity.net;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Wire protocol between the Velocity plugin and the Fabric mod.
 *
 * <p>This is THE seam between the two codebases: both sides implement the exact
 * byte layout below independently (the modules have zero compile-time coupling).
 * Any change here MUST be mirrored in {@code fabric-mod/.../net/Protocol.java}
 * and documented in {@code docs/PROTOCOL.md}.
 *
 * <p>All players form a single linked life pool — there are no groups, so the
 * messages carry no group identifier.
 *
 * <p>Encoding follows Minecraft conventions:
 * <ul>
 *   <li>Integers are big-endian.</li>
 *   <li>Strings are {@code varint length + UTF-8 bytes} (Minecraft's
 *       {@code String} encoding).</li>
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
 *
 *  [0x02] PREPARE_TRANSFER proxy -> mod
 *      byte  opcode = 0x02
 *
 *  [0x03] RESET_COMPLETE   mod -> proxy
 *      byte  opcode = 0x03
 *      varint + utf8  serverId
 *
 *  [0x04] ACK              proxy -> mod
 *      byte  opcode = 0x04
 *      byte[16] playerUuid
 *
 *  [0x05] TRANSFER_READY   mod -> proxy
 *      byte  opcode = 0x05
 * </pre>
 */
public final class Protocol {

    public static final String CHANNEL_NAME = "linkedhardcore:main";
    public static final ChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(CHANNEL_NAME);

    public static final byte OP_PLAYER_DIED = 0x01;
    public static final byte OP_PREPARE_TRANSFER = 0x02;
    public static final byte OP_RESET_COMPLETE = 0x03;
    public static final byte OP_ACK = 0x04;
    public static final byte OP_TRANSFER_READY = 0x05;

    private Protocol() {
    }

    /** A decoded inbound message from a backend server. */
    public record Inbound(byte opcode, UUID playerUuid, String serverId) {
        public boolean isPlayerDied() {
            return opcode == OP_PLAYER_DIED;
        }

        public boolean isResetComplete() {
            return opcode == OP_RESET_COMPLETE;
        }

        public boolean isTransferReady() {
            return opcode == OP_TRANSFER_READY;
        }
    }

    /** Tries to decode a raw plugin-message payload. Returns empty if malformed or unknown opcode. */
    public static Optional<Inbound> decodeInbound(byte[] data) {
        if (data == null || data.length < 1) {
            return Optional.empty();
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            byte opcode = in.readByte();
            return switch (opcode) {
                case OP_PLAYER_DIED -> Optional.of(new Inbound(opcode, readUuid(in), null));
                case OP_RESET_COMPLETE -> Optional.of(new Inbound(opcode, null, readString(in)));
                case OP_TRANSFER_READY -> Optional.of(new Inbound(opcode, null, null));
                default -> {
                    // Unknown opcode — protocol drift between mod and plugin. Surface it rather than silently ignore.
                    yield Optional.empty();
                }
            };
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Encodes PREPARE_TRANSFER (proxy -> mod). */
    public static byte[] encodePrepareTransfer() {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(OP_PREPARE_TRANSFER);
        return out.toByteArray();
    }

    /** Encodes ACK for a received PLAYER_DIED (proxy -> mod), correlated by playerUuid. */
    public static byte[] encodeAck(UUID playerUuid) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(OP_ACK);
        writeUuid(out, playerUuid);
        return out.toByteArray();
    }

    private static void writeUuid(ByteArrayDataOutput out, UUID uuid) {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        long msb = in.readLong();
        long lsb = in.readLong();
        return new UUID(msb, lsb);
    }

    private static void writeString(ByteArrayDataOutput out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Minecraft varint (7 bits per byte, high bit = continuation). */
    private static void writeVarInt(ByteArrayDataOutput out, int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            out.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.writeByte(remaining);
    }

    /** Minecraft varint. Returns -1 if the stream ended mid-varint. */
    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        while (shift < 35) {
            int b = in.readByte() & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IOException("VarInt too big");
    }
}

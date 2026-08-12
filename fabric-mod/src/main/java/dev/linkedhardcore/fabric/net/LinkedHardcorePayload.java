package dev.linkedhardcore.fabric.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Transport payload for the {@code linkedhardcore:main} channel.
 *
 * <p>The payload is a raw-byte passthrough: it carries the fully-encoded protocol
 * frame (opcode + fields, produced by {@link Protocol}). The StreamCodec writes
 * the frame bytes verbatim (no length prefix — the enclosing custom-payload
 * packet provides framing), and the proxy reads exactly these bytes via
 * {@code PluginMessageEvent#getData()}.
 *
 * <p>Registered in both the serverbound and clientbound play registries, so the
 * same class is used to send PLAYER_DIED/RESET_COMPLETE to the proxy and to
 * receive GROUP_ELIMINATED/ACK from it.
 */
public record LinkedHardcorePayload(byte[] data) implements CustomPacketPayload {

    public static final Type<LinkedHardcorePayload> TYPE = new Type<>(Protocol.CHANNEL);

    /** Writes/reads the raw frame bytes. The identifier itself is handled by the packet codec. */
    public static final StreamCodec<FriendlyByteBuf, LinkedHardcorePayload> STREAM_CODEC =
        new StreamCodec<>() {
            @Override
            public LinkedHardcorePayload decode(FriendlyByteBuf buf) {
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                return new LinkedHardcorePayload(data);
            }

            @Override
            public void encode(FriendlyByteBuf buf, LinkedHardcorePayload payload) {
                buf.writeBytes(payload.data());
            }
        };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

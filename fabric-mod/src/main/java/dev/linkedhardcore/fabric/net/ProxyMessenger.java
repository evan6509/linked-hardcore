package dev.linkedhardcore.fabric.net;

import dev.linkedhardcore.fabric.config.ModConfig;
import dev.linkedhardcore.fabric.death.TransferCountdown;
import dev.linkedhardcore.fabric.death.TransferReadyNotifier;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles all communication with the Velocity proxy over the
 * {@code linkedhardcore:main} channel.
 *
 * <p>Two responsibilities:
 * <ol>
 *   <li>Send PLAYER_DIED (death event), RESET_COMPLETE (after reset), and
 *       TRANSFER_READY (countdown finished) to the proxy.</li>
 *   <li>Receive PREPARE_TRANSFER (start the on-screen countdown) and ACK.</li>
 * </ol>
 *
 * <p><b>Reliability:</b> {@code ServerPlayNetworking#send} does NOT verify the
 * receiving side declared the channel — a misconfigured channel fails silently.
 * To avoid debugging a black hole, every PLAYER_DIED is tracked as pending; if
 * the proxy doesn't ACK within {@code ackTimeoutSeconds}, we log a warning naming
 * the likely misconfiguration. This single round-trip proves both directions:
 * mod-&gt;proxy (payload registered here, channel registered on the proxy) and
 * proxy-&gt;mod (receiver registered here, proxy send).
 */
public final class ProxyMessenger implements TransferReadyNotifier {

    private static final Logger LOGGER = LoggerFactory.getLogger("linkedhardcore");

    private final ModConfig config;
    private final Map<UUID, Long> pendingAcks = new ConcurrentHashMap<>();
    private volatile TransferCountdown transferCountdown;

    public ProxyMessenger(ModConfig config) {
        this.config = config;
    }

    /**
     * Wires the countdown back-reference (the countdown is constructed with this
     * messenger as its notifier). Called once during mod init.
     */
    public void setTransferCountdown(TransferCountdown transferCountdown) {
        this.transferCountdown = transferCountdown;
    }

    /** Registers payload types + the inbound receiver. Call once during mod init. */
    public void register() {
        // Outbound (mod -> proxy): PLAYER_DIED, RESET_COMPLETE, TRANSFER_READY.
        PayloadTypeRegistry.clientboundPlay().register(LinkedHardcorePayload.TYPE, LinkedHardcorePayload.STREAM_CODEC);
        // Inbound (proxy -> mod): PREPARE_TRANSFER, ACK.
        PayloadTypeRegistry.serverboundPlay().register(LinkedHardcorePayload.TYPE, LinkedHardcorePayload.STREAM_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(LinkedHardcorePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = ((ServerLevel) player.level()).getServer();
            server.execute(() -> handleInbound(server, payload.data()));
        });
    }

    /**
     * Reports a player death to the proxy. The frame travels over the dead
     * player's own connection (which terminates at Velocity). We do NOT gate on
     * {@code canSend} — the vanilla client will never declare our channel, but
     * the proxy intercepts registered channels regardless. Instead, the pending
     * ACK entry is our failure detector.
     */
    public void sendPlayerDied(ServerPlayer player) {
        byte[] frame = Protocol.encodePlayerDied(player.getUUID());
        pendingAcks.put(player.getUUID(), System.currentTimeMillis());
        ServerPlayNetworking.send(player, new LinkedHardcorePayload(frame));
        LOGGER.info("[linkedhardcore] PLAYER_DIED sent for {}", player.getName().getString());
    }

    /**
     * Reports that the transfer countdown finished; the proxy may now transfer
     * everyone to the other backend.
     *
     * <p>Best-effort: requires at least one connected player to carry the frame.
     * If nobody is connected, the proxy's transfer never fires.
     */
    @Override
    public void notifyTransferReady(MinecraftServer server) {
        byte[] frame = Protocol.encodeTransferReady();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, new LinkedHardcorePayload(frame));
            LOGGER.info("[linkedhardcore] TRANSFER_READY sent");
            return;
        }
        LOGGER.warn("[linkedhardcore] No connected player to carry TRANSFER_READY; transfer will not happen");
    }

    /**
     * Reports that this server finished resetting and is ready again.
     *
     * <p>Best-effort: if no player is connected there is no live connection to
     * carry our channel to the proxy, so the message may not arrive. The proxy
     * ALSO polls {@code status.json}, so a missed RESET_COMPLETE is not fatal.
     */
    public void sendResetComplete(MinecraftServer server, String serverId) {
        byte[] frame = Protocol.encodeResetComplete(serverId);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, new LinkedHardcorePayload(frame));
            LOGGER.info("[linkedhardcore] RESET_COMPLETE sent for server '{}'", serverId);
            return;
        }
        LOGGER.info("[linkedhardcore] No connected client to carry RESET_COMPLETE for '{}'; proxy will detect readiness via status.json", serverId);
    }

    /**
     * Called on each server tick: warns once per un-acked PLAYER_DIED after the
     * configured timeout, then drops the entry so the warning isn't spammed.
     */
    public void checkPendingAcks() {
        long now = System.currentTimeMillis();
        long timeout = config.ackTimeoutSeconds() * 1000L;
        for (Map.Entry<UUID, Long> entry : pendingAcks.entrySet()) {
            if (now - entry.getValue() > timeout) {
                LOGGER.error(
                    "[linkedhardcore] PLAYER_DIED for {} was never acknowledged by the proxy within {}s. "
                        + "The channel is likely misconfigured. Check that (1) the Velocity plugin registered '{}' "
                        + "in its ChannelRegistrar, and (2) this mod registered the payload in "
                        + "PayloadTypeRegistry clientboundPlay/serveboundPlay.",
                    entry.getKey(), config.ackTimeoutSeconds(), Protocol.CHANNEL_NAME);
                pendingAcks.remove(entry.getKey());
            }
        }
    }

    private void handleInbound(MinecraftServer server, byte[] data) {
        Protocol.Inbound msg = Protocol.decode(data);
        if (msg.isAck() && msg.playerUuid() != null) {
            Long sentAt = pendingAcks.remove(msg.playerUuid());
            if (sentAt != null) {
                long rtt = System.currentTimeMillis() - sentAt;
                LOGGER.info("[linkedhardcore] ACK received for {} (round-trip {}ms)", msg.playerUuid(), rtt);
            }
            return;
        }
        if (msg.isPrepareTransfer()) {
            LOGGER.info("[linkedhardcore] PREPARE_TRANSFER received");
            transferCountdown.start(server);
            return;
        }
        LOGGER.warn("[linkedhardcore] Dropped unrecognized proxy message: opcode=0x{}", Integer.toHexString(msg.opcode()));
    }
}

package dev.linkedhardcore.fabric.net;

import dev.linkedhardcore.fabric.config.ModConfig;
import dev.linkedhardcore.fabric.death.DeathCounterDisplay;
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

/** Handles communication with the Velocity proxy over linkedhardcore:main. */
public final class ProxyMessenger implements TransferReadyNotifier {
    private static final Logger LOGGER = LoggerFactory.getLogger("linkedhardcore");

    private final ModConfig config;
    private final Map<UUID, Long> pendingAcks = new ConcurrentHashMap<>();
    private volatile TransferCountdown transferCountdown;

    public ProxyMessenger(ModConfig config) {
        this.config = config;
    }

    public void setTransferCountdown(TransferCountdown transferCountdown) {
        this.transferCountdown = transferCountdown;
    }

    public void register() {
        PayloadTypeRegistry.clientboundPlay().register(LinkedHardcorePayload.TYPE, LinkedHardcorePayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(LinkedHardcorePayload.TYPE, LinkedHardcorePayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(LinkedHardcorePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = ((ServerLevel) player.level()).getServer();
            server.execute(() -> handleInbound(server, payload.data()));
        });
    }

    public void sendPlayerDied(ServerPlayer player) {
        byte[] frame = Protocol.encodePlayerDied(player.getUUID());
        pendingAcks.put(player.getUUID(), System.currentTimeMillis());
        ServerPlayNetworking.send(player, new LinkedHardcorePayload(frame));
        LOGGER.info("[linkedhardcore] PLAYER_DIED sent for {}", player.getName().getString());
    }

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

    public void sendResetComplete(MinecraftServer server, String serverId) {
        byte[] frame = Protocol.encodeResetComplete(serverId);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, new LinkedHardcorePayload(frame));
            LOGGER.info("[linkedhardcore] RESET_COMPLETE sent for server '{}'", serverId);
            return;
        }
        LOGGER.info("[linkedhardcore] No connected client to carry RESET_COMPLETE for '{}'; proxy will detect readiness via status.json", serverId);
    }

    public void checkPendingAcks() {
        long now = System.currentTimeMillis();
        long timeout = config.ackTimeoutSeconds() * 1000L;
        for (Map.Entry<UUID, Long> entry : pendingAcks.entrySet()) {
            if (now - entry.getValue() > timeout) {
                LOGGER.error("[linkedhardcore] PLAYER_DIED for {} was never acknowledged by the proxy within {}s. Check the Velocity channel registration.",
                    entry.getKey(), config.ackTimeoutSeconds());
                pendingAcks.remove(entry.getKey());
            }
        }
    }

    private void handleInbound(MinecraftServer server, byte[] data) {
        Protocol.Inbound msg = Protocol.decode(data);
        if (msg.isAck() && msg.playerUuid() != null) {
            Long sentAt = pendingAcks.remove(msg.playerUuid());
            if (sentAt != null) {
                LOGGER.info("[linkedhardcore] ACK received for {} (round-trip {}ms)", msg.playerUuid(), System.currentTimeMillis() - sentAt);
            }
            return;
        }
        if (msg.isDeathCounters()) {
            DeathCounterDisplay.apply(server, msg.deathCounters());
            LOGGER.info("[linkedhardcore] Updated death counter display with {} record(s)", msg.deathCounters().size());
            return;
        }
        if (msg.isWaitForServer()) {
            transferCountdown.waitForServer(server);
            return;
        }
        if (msg.isPrepareTransfer()) {
            transferCountdown.start(server);
            return;
        }
        LOGGER.warn("[linkedhardcore] Dropped unrecognized proxy message: opcode=0x{}", Integer.toHexString(msg.opcode()));
    }
}

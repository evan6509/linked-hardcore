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
import java.util.function.LongSupplier;
import java.util.function.Supplier;

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
 * Every world-scoped death stays pending until its matching ACK. After
 * {@code ackTimeoutSeconds}, retry the same event over a current player connection.
 * This round-trip proves both directions:
 * mod-&gt;proxy (payload registered here, channel registered on the proxy) and
 * proxy-&gt;mod (receiver registered here, proxy send).
 */
public final class ProxyMessenger implements TransferReadyNotifier {

    private static final Logger LOGGER = LoggerFactory.getLogger("linkedhardcore");

    private final ModConfig config;
    private final Supplier<String> generationId;
    private final LongSupplier clock;
    private final Map<UUID, PendingDeath> pendingAcks = new ConcurrentHashMap<>();
    private volatile TransferCountdown transferCountdown;

    public ProxyMessenger(ModConfig config, Supplier<String> generationId) {
        this(config, generationId, System::currentTimeMillis);
    }

    ProxyMessenger(ModConfig config, Supplier<String> generationId, LongSupplier clock) {
        this.config = config;
        this.generationId = generationId;
        this.clock = clock;
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
        String generation = generationId.get();
        byte[] frame = Protocol.encodePlayerDied(player.getUUID(), generation);
        pendingAcks.put(player.getUUID(), new PendingDeath(generation, frame, clock.getAsLong()));
        ServerPlayNetworking.send(player, new LinkedHardcorePayload(frame));
        LOGGER.info("[linkedhardcore] PLAYER_DIED sent for {}", player.getName().getString());
    }

    /**
     * Reports that the transfer countdown finished; the proxy may now transfer
     * everyone to the other backend.
     *
     * <p>Best-effort: requires at least one connected player to carry the frame.
     * Without a carrier, the proxy progresses offline handoffs through polling.
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
     * Retry expired deaths on a current connection. Respawn replaces the original
     * player entity, and another player can carry the event after a disconnect.
     */
    public void checkPendingAcks(MinecraftServer server) {
        long now = clock.getAsLong();
        long timeout = config.ackTimeoutSeconds() * 1000L;
        for (Map.Entry<UUID, PendingDeath> entry : pendingAcks.entrySet()) {
            PendingDeath death = entry.getValue();
            if (now - death.sentAt() > timeout) {
                if (!pendingAcks.replace(entry.getKey(), death, new PendingDeath(death.generationId(), death.frame(), now))) continue;
                var players = server.getPlayerList().getPlayers();
                ServerPlayer carrier = players.stream().filter(player -> entry.getKey().equals(player.getUUID()))
                    .findFirst().orElse(players.isEmpty() ? null : players.getFirst());
                LOGGER.warn("[linkedhardcore] Death for {} in world '{}' remains unacknowledged; {}",
                    entry.getKey(), death.generationId(), carrier == null ? "waiting for a connected carrier" : "retrying");
                if (carrier != null) ServerPlayNetworking.send(carrier, new LinkedHardcorePayload(death.frame()));
            }
        }
    }

    void handleInbound(MinecraftServer server, byte[] data) {
        Protocol.Inbound msg = Protocol.decode(data);
        if (msg.isAck() && msg.playerUuid() != null) {
            PendingDeath death = pendingAcks.get(msg.playerUuid());
            if (death != null && death.generationId().equals(msg.generationId()) && pendingAcks.remove(msg.playerUuid(), death)) {
                long rtt = clock.getAsLong() - death.sentAt();
                LOGGER.info("[linkedhardcore] ACK received for {} (round-trip {}ms)", msg.playerUuid(), rtt);
            }
            return;
        }
        if (msg.isDeathCounters()) {
            DeathCounterDisplay.apply(server, msg.deathCounters());
            LOGGER.debug("[linkedhardcore] Updated death counter display with {} record(s)", msg.deathCounters().size());
            return;
        }
        if (msg.isRunTimer()) {
            DeathCounterDisplay.applyRunTimer(server, msg.runTimer());
            return;
        }
        if (msg.isWaitForServer()) {
            LOGGER.info("[linkedhardcore] WAIT_FOR_SERVER received");
            transferCountdown.waitForServer(server);
            return;
        }
        if (msg.isPrepareTransfer()) {
            LOGGER.info("[linkedhardcore] PREPARE_TRANSFER received");
            transferCountdown.start(server);
            return;
        }
        LOGGER.warn("[linkedhardcore] Dropped unrecognized proxy message: opcode=0x{}", Integer.toHexString(msg.opcode()));
    }

    private record PendingDeath(String generationId, byte[] frame, long sentAt) { }
}

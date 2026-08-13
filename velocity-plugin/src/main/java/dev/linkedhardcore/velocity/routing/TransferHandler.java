package dev.linkedhardcore.velocity.routing;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.linkedhardcore.velocity.config.PluginConfig;
import dev.linkedhardcore.velocity.model.Group;
import dev.linkedhardcore.velocity.model.GroupRegistry;
import dev.linkedhardcore.velocity.model.ServerState;
import dev.linkedhardcore.velocity.model.ServerStatus;
import dev.linkedhardcore.velocity.net.Protocol;
import dev.linkedhardcore.velocity.reset.ResetSignaller;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Core routing: turns a {@code PLAYER_DIED} notification into a countdown +
 * transfer, and marks the vacated server for reset.
 *
 * <p>Flow:
 * <ol>
 *   <li>{@code PLAYER_DIED} from a backend → ACK, then tell that server to run
 *       the on-screen transfer countdown ({@code PREPARE_TRANSFER}).</li>
 *   <li>The Fabric mod shows the countdown, then replies {@code TRANSFER_READY}.</li>
 *   <li>{@link #onTransferReady} transfers every group member to the OTHER backend
 *       and flags the vacated server for reset.</li>
 * </ol>
 */
public final class TransferHandler {

    private final ProxyServer proxy;
    private final PluginConfig config;
    private final GroupRegistry groups;
    private final Map<String, ServerStatus> servers;
    private final ResetSignaller resetSignaller;
    private final Logger logger;

    public TransferHandler(ProxyServer proxy, PluginConfig config, GroupRegistry groups,
                           Map<String, ServerStatus> servers, ResetSignaller resetSignaller, Logger logger) {
        this.proxy = proxy;
        this.config = config;
        this.groups = groups;
        this.servers = servers;
        this.resetSignaller = resetSignaller;
        this.logger = logger;
    }

    /**
     * Handles an inbound {@code PLAYER_DIED} from a backend.
     *
     * <ol>
     *   <li>ACK the originating server immediately (even for unknown groups —
     *       the mod's ack-timeout must still resolve).</li>
     *   <li>Tell the originating server to run the transfer countdown
     *       ({@code PREPARE_TRANSFER}). The mod will reply {@code TRANSFER_READY}
     *       when the countdown finishes; the actual transfer happens there.</li>
     * </ol>
     */
    public void onPlayerDied(ServerConnection source, UUID playerUuid, String groupId) {
        String fromServerName = source.getServerInfo().getName();
        logger.info("[linkedhardcore] PLAYER_DIED from '{}': player={}, group={}",
            fromServerName, playerUuid, groupId);

        // 1. ACK — do this first, unconditionally, so the mod's pending-ack tracker resolves.
        source.sendPluginMessage(Protocol.CHANNEL, Protocol.encodeAck(playerUuid));

        Optional<Group> groupOpt = groups.byId(groupId);
        if (groupOpt.isEmpty()) {
            logger.warn("[linkedhardcore] Death reported for unknown group '{}' (player {}). No transfer.",
                groupId, playerUuid);
            return;
        }
        Group group = groupOpt.get();

        // 2. Ask the originating server to run the on-screen countdown.
        source.sendPluginMessage(Protocol.CHANNEL, Protocol.encodePrepareTransfer(group.id()));
        logger.info("[linkedhardcore] PREPARE_TRANSFER sent to '{}' for group '{}'", fromServerName, group.id());
    }

    /**
     * Handles {@code TRANSFER_READY} from a backend: the countdown for the group
     * finished on that server, so now move every group member to the OTHER
     * backend and flag the vacated server for reset.
     */
    public void onTransferReady(ServerConnection source, String groupId) {
        String fromServerName = source.getServerInfo().getName();
        logger.info("[linkedhardcore] TRANSFER_READY from '{}' for group '{}'", fromServerName, groupId);

        Optional<Group> groupOpt = groups.byId(groupId);
        if (groupOpt.isEmpty()) {
            logger.warn("[linkedhardcore] TRANSFER_READY for unknown group '{}'.", groupId);
            return;
        }
        Group group = groupOpt.get();

        // Destination: whichever registered server is NOT the one that sent this.
        Optional<RegisteredServer> destination = findOtherBackend(fromServerName);
        if (destination.isEmpty()) {
            logger.error("[linkedhardcore] No alternate backend configured to transfer group '{}' to (from '{}').",
                group.id(), fromServerName);
            return;
        }
        RegisteredServer to = destination.get();

        // Transfer all group members currently connected to the proxy.
        Set<Player> transferred = new HashSet<>();
        for (UUID memberUuid : group.members()) {
            Optional<Player> member = proxy.getPlayer(memberUuid);
            if (member.isPresent()) {
                Player player = member.get();
                player.createConnectionRequest(to).connect().whenComplete((result, error) -> {
                    if (error != null || result == null || !result.isSuccessful()) {
                        logger.warn("[linkedhardcore] Failed to transfer player {} to {}: {}", memberUuid, to.getServerInfo().getName(), error != null ? error : result);
                    } else {
                        logger.info("[linkedhardcore] Transferred {} to {}", memberUuid, to.getServerInfo().getName());
                    }
                });
                transferred.add(player);
            } else {
                logger.info("[linkedhardcore] Group member {} not online on the proxy; nothing to transfer.", memberUuid);
            }
        }

        // The originating server is now empty (all its group members transferred) -> RESETTING,
        // and the destination is LIVE.
        ServerStatus fromStatus = servers.get(fromServerName);
        ServerStatus toStatus = servers.get(to.getServerInfo().getName());
        if (fromStatus != null) {
            fromStatus.transition(ServerState.RESETTING, logger);
        }
        if (toStatus != null) {
            toStatus.transition(ServerState.LIVE, logger);
        }

        // Signal the external agent to wipe the vacated server.
        try {
            resetSignaller.signalReset(fromServerName);
        } catch (Exception e) {
            logger.error("[linkedhardcore] Failed to signal reset for '{}': {}", fromServerName, e.toString());
        }
    }

    /**
     * Handles a {@code RESET_COMPLETE} from a backend: the server reports it is
     * ready again, so flip it back to READY (idempotent).
     *
     * @param serverId the logical server id reported by the mod (e.g. {@code a} or {@code b})
     */
    public void onResetComplete(String serverId) {
        Optional<String> velocityName = config.velocityNameForServerId(serverId);
        if (velocityName.isEmpty()) {
            logger.warn("[linkedhardcore] RESET_COMPLETE for unconfigured serverId '{}'", serverId);
            return;
        }
        ServerStatus status = servers.get(velocityName.get());
        if (status == null) {
            logger.warn("[linkedhardcore] RESET_COMPLETE for server '{}' with no tracked status", velocityName.get());
            return;
        }
        status.transition(ServerState.READY, logger);
    }

    private Optional<RegisteredServer> findOtherBackend(String currentServerName) {
        for (String name : config.backendServers().keySet()) {
            if (!name.equals(currentServerName)) {
                Optional<RegisteredServer> server = proxy.getServer(name);
                if (server.isPresent()) {
                    return server;
                }
            }
        }
        return Optional.empty();
    }
}

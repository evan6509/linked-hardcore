package dev.linkedhardcore.velocity.routing;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.linkedhardcore.velocity.config.PluginConfig;
import dev.linkedhardcore.velocity.model.ServerState;
import dev.linkedhardcore.velocity.model.ServerStatus;
import dev.linkedhardcore.velocity.net.Protocol;
import dev.linkedhardcore.velocity.reset.ResetSignaller;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core routing: turns a {@code PLAYER_DIED} notification into a countdown +
 * transfer of the entire linked player pool, and marks the vacated server for
 * reset. Supports any number of backend servers.
 *
 * <p>All players on the backends share one life pool — there are no groups.
 * Flow:
 * <ol>
 *   <li>{@code PLAYER_DIED} from a backend → ACK.</li>
 *   <li>Pick a destination: the first server that is {@code READY} and not the
 *       source. If one exists, send {@code PREPARE_TRANSFER} (start the
 *       countdown). If none does, send {@code WAIT_FOR_SERVER} and record a
 *       pending transfer.</li>
 *   <li>The Fabric mod shows the countdown, then replies {@code TRANSFER_READY}.</li>
 *   <li>{@link #onTransferReady} transfers every player on the proxy to the
 *       chosen destination and flags the vacated server for reset.</li>
 *   <li>When a server later becomes {@code READY} (status poll or RESET_COMPLETE),
 *       {@link #onServerReady} resumes any pending transfer for it.</li>
 * </ol>
 */
public final class TransferHandler {

    private final ProxyServer proxy;
    private final PluginConfig config;
    private final Map<String, ServerStatus> servers;
    private final ResetSignaller resetSignaller;
    private final Logger logger;

    /** A transfer waiting for an available destination: (source server name, chosen destination name). */
    private final AtomicReference<PendingTransfer> pending = new AtomicReference<>();

    public TransferHandler(ProxyServer proxy, PluginConfig config,
                           Map<String, ServerStatus> servers, ResetSignaller resetSignaller, Logger logger) {
        this.proxy = proxy;
        this.config = config;
        this.servers = servers;
        this.resetSignaller = resetSignaller;
        this.logger = logger;
    }

    /**
     * Handles an inbound {@code PLAYER_DIED} from a backend.
     *
     * <ol>
     *   <li>ACK the originating server immediately (the mod's ack-timeout must
     *       resolve even for unexpected deaths).</li>
     *   <li>Pick a READY destination: send {@code PREPARE_TRANSFER} if one is
     *       available, otherwise send {@code WAIT_FOR_SERVER} and hold the
     *       transfer pending until a server becomes ready.</li>
     * </ol>
     */
    public void onPlayerDied(ServerConnection source, UUID playerUuid) {
        String fromServerName = source.getServerInfo().getName();
        logger.info("[linkedhardcore] PLAYER_DIED from '{}': player={}", fromServerName, playerUuid);

        // 1. ACK — do this first, unconditionally, so the mod's pending-ack tracker resolves.
        source.sendPluginMessage(Protocol.CHANNEL, Protocol.encodeAck(playerUuid));

        // 2. Try to transfer now if a ready destination exists.
        attemptTransfer(fromServerName);
    }

    /**
     * Handles {@code TRANSFER_READY} from a backend: the countdown finished on
     * that server, so now move every player on the proxy to the destination and
     * flag the vacated server for reset.
     */
    public void onTransferReady(ServerConnection source) {
        String fromServerName = source.getServerInfo().getName();
        PendingTransfer current = pending.getAndSet(null);
        String destinationName = current != null ? current.destinationName() : findReadyBackendExcept(fromServerName)
            .map(s -> s.getServerInfo().getName()).orElse(null);

        logger.info("[linkedhardcore] TRANSFER_READY from '{}'; destination={}", fromServerName, destinationName);

        if (destinationName == null) {
            logger.error("[linkedhardcore] No ready backend to transfer to (from '{}').", fromServerName);
            return;
        }
        Optional<RegisteredServer> destination = proxy.getServer(destinationName);
        if (destination.isEmpty()) {
            logger.error("[linkedhardcore] Destination server '{}' is not registered.", destinationName);
            return;
        }
        RegisteredServer to = destination.get();

        // Transfer every player currently connected to the proxy.
        int transferred = 0;
        for (Player player : proxy.getAllPlayers()) {
            String currentServer = player.getCurrentServer().map(sc -> sc.getServerInfo().getName()).orElse(null);
            if (destinationName.equals(currentServer)) {
                logger.info("[linkedhardcore] Player {} already on {}; skipping", player.getUsername(), destinationName);
                continue;
            }
            player.createConnectionRequest(to).connect().whenComplete((result, error) -> {
                if (error != null || result == null || !result.isSuccessful()) {
                    logger.warn("[linkedhardcore] Failed to transfer player {} to {}: {}", player.getUsername(), destinationName, error != null ? error : result);
                } else {
                    logger.info("[linkedhardcore] Transferred {} to {}", player.getUsername(), destinationName);
                }
            });
            transferred++;
        }
        logger.info("[linkedhardcore] Transfer initiated for {} player(s) from '{}' to '{}'", transferred, fromServerName, destinationName);

        // The originating server is now empty (everyone transferred) -> RESETTING,
        // and the destination is LIVE. Guarded so an unexpected transition can never
        // abort the reset signal below.
        ServerStatus fromStatus = servers.get(fromServerName);
        ServerStatus toStatus = servers.get(destinationName);
        try {
            if (fromStatus != null) {
                fromStatus.transition(ServerState.RESETTING, logger);
            }
        } catch (IllegalStateException e) {
            logger.warn("[linkedhardcore] Could not mark '{}' RESETTING ({}); continuing to signal reset.", fromServerName, e.getMessage());
        }
        try {
            if (toStatus != null) {
                toStatus.transition(ServerState.LIVE, logger);
            }
        } catch (IllegalStateException e) {
            logger.warn("[linkedhardcore] Could not mark '{}' LIVE ({}); continuing to signal reset.", destinationName, e.getMessage());
        }

        // Signal the external agent to wipe the vacated server.
        try {
            resetSignaller.signalReset(fromServerName);
        } catch (Exception e) {
            logger.error("[linkedhardcore] Failed to signal reset for '{}': {}", fromServerName, e.toString());
        }
    }

    /**
     * Handles a {@code RESET_COMPLETE} from a backend, or a status poll reporting
     * the server ready: flip it back to READY (idempotent) and resume any pending
     * transfer that was waiting for it.
     *
     * @param serverName the velocity server name that became ready
     */
    public void onServerReady(String serverName) {
        ServerStatus status = servers.get(serverName);
        if (status != null) {
            status.transition(ServerState.READY, logger);
        }
        // If a transfer is pending and this server can be its destination (either
        // it was already chosen, or we were waiting for any ready server), kick it off.
        PendingTransfer current = pending.get();
        if (current != null && (current.destinationName() == null || serverName.equals(current.destinationName()))) {
            logger.info("[linkedhardcore] Server '{}' ready; resuming pending transfer from '{}'",
                serverName, current.sourceName());
            pending.set(null);
            attemptTransfer(current.sourceName());
        }
    }

    /**
     * Handles {@code RESET_COMPLETE} (logical serverId form). Delegates to
     * {@link #onServerReady}.
     *
     * @param serverId the logical server id reported by the mod (e.g. {@code a} or {@code b})
     */
    public void onResetComplete(String serverId) {
        Optional<String> velocityName = config.velocityNameForServerId(serverId);
        if (velocityName.isEmpty()) {
            logger.warn("[linkedhardcore] RESET_COMPLETE for unconfigured serverId '{}'", serverId);
            return;
        }
        onServerReady(velocityName.get());
    }

    /**
     * Tries to start a transfer from {@code sourceName}: if a READY destination
     * exists, send PREPARE_TRANSFER; otherwise tell the source to wait.
     */
    private void attemptTransfer(String sourceName) {
        Optional<RegisteredServer> ready = findReadyBackendExcept(sourceName);
        if (ready.isPresent()) {
            String destinationName = ready.get().getServerInfo().getName();
            pending.set(new PendingTransfer(sourceName, destinationName));
            Optional<RegisteredServer> source = proxy.getServer(sourceName);
            if (source.isPresent()) {
                source.get().sendPluginMessage(Protocol.CHANNEL, Protocol.encodePrepareTransfer());
                logger.info("[linkedhardcore] PREPARE_TRANSFER sent to '{}' (dest '{}')", sourceName, destinationName);
            } else {
                logger.error("[linkedhardcore] Source server '{}' not registered; cannot start transfer", sourceName);
            }
        } else {
            pending.set(new PendingTransfer(sourceName, null));
            Optional<RegisteredServer> source = proxy.getServer(sourceName);
            if (source.isPresent()) {
                source.get().sendPluginMessage(Protocol.CHANNEL, Protocol.encodeWaitForServer());
                logger.info("[linkedhardcore] No ready destination; WAIT_FOR_SERVER sent to '{}'", sourceName);
            }
        }
    }

    /** First registered server (in config order) that is READY and not {@code excludeName}. */
    private Optional<RegisteredServer> findReadyBackendExcept(String excludeName) {
        for (String name : config.backendServers().keySet()) {
            if (name.equals(excludeName)) {
                continue;
            }
            ServerStatus status = servers.get(name);
            if (status != null && status.is(ServerState.READY)) {
                Optional<RegisteredServer> server = proxy.getServer(name);
                if (server.isPresent()) {
                    return server;
                }
            }
        }
        return Optional.empty();
    }

    /** A transfer awaiting its destination. destinationName may be null while still choosing. */
    private record PendingTransfer(String sourceName, String destinationName) {
    }
}

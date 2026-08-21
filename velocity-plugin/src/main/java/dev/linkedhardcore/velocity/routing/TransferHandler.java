package dev.linkedhardcore.velocity.routing;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.linkedhardcore.velocity.config.PluginConfig;
import dev.linkedhardcore.velocity.death.DeathCounterBroadcaster;
import dev.linkedhardcore.velocity.death.DeathCounterState;
import dev.linkedhardcore.velocity.model.ServerState;
import dev.linkedhardcore.velocity.model.ServerStatus;
import dev.linkedhardcore.velocity.net.Protocol;
import dev.linkedhardcore.velocity.reset.ResetSignaller;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Routes the one shared life pool through a safe transfer lifecycle.
 *
 * <p>Only one linked-life transfer may be active at a time. A transfer is held
 * while no destination is ready, then every occupied backend is put into the
 * same spectator/countdown flow. The vacated worlds are reset only after every
 * requested Velocity connection succeeds and every source backend is empty.
 */
public final class TransferHandler {

    private final ProxyServer proxy;
    private final PluginConfig config;
    private final Map<String, ServerStatus> servers;
    private final ResetSignaller resetSignaller;
    private final Logger logger;
    private final DeathCounterState deathCounters;
    private final Consumer<DeathCounterState> persistDeathCounters;

    private final Object transferLock = new Object();
    private PendingTransfer pending;

    public TransferHandler(ProxyServer proxy, PluginConfig config,
                           Map<String, ServerStatus> servers, ResetSignaller resetSignaller, Logger logger) {
        this(proxy, config, servers, resetSignaller, logger, new DeathCounterState(), ignored -> { });
    }

    public TransferHandler(ProxyServer proxy, PluginConfig config,
                           Map<String, ServerStatus> servers, ResetSignaller resetSignaller, Logger logger,
                           DeathCounterState deathCounters, Consumer<DeathCounterState> persistDeathCounters) {
        this.proxy = proxy;
        this.config = config;
        this.servers = servers;
        this.resetSignaller = resetSignaller;
        this.logger = logger;
        this.deathCounters = deathCounters;
        this.persistDeathCounters = persistDeathCounters;
    }

    /** ACKs a death and starts one serialized transfer for the shared life pool. */
    public void onPlayerDied(ServerConnection source, UUID playerUuid) {
        String fromServerName = source.getServerInfo().getName();
        logger.info("[linkedhardcore] PLAYER_DIED from '{}': player={}", fromServerName, playerUuid);
        source.sendPluginMessage(Protocol.CHANNEL, Protocol.encodeAck(playerUuid));

        boolean deathRecorded = false;
        synchronized (transferLock) {
            if (!servers.containsKey(fromServerName)) {
                logger.warn("[linkedhardcore] Ignoring death from unconfigured backend '{}'", fromServerName);
                return;
            }
            if (pending != null) {
                logger.info("[linkedhardcore] Ignoring PLAYER_DIED from '{}'; transfer already active for {}",
                    fromServerName, pending.sourceNames);
                return;
            }

            Set<String> sourceNames = activeBackendServers();
            sourceNames.add(fromServerName);
            if (!markSourcesTransferringLocked(sourceNames)) {
                logger.warn("[linkedhardcore] Cannot start transfer from '{}'; a source is resetting", fromServerName);
                restoreSourceStatesLocked(sourceNames);
                return;
            }

            pending = new PendingTransfer(sourceNames);
            Player player = source.getPlayer();
            String playerName = player == null ? playerUuid.toString() : player.getUsername();
            deathCounters.recordDeath(playerUuid, playerName);
            deathRecorded = true;
            attemptTransferLocked();
        }

        if (deathRecorded) {
            persistDeathCounters.accept(deathCounters);
            broadcastCounters();
        }
    }

    /**
     * Starts proxy connections after a countdown response from one participating
     * backend. Responses from stale or unrelated countdowns are ignored.
     */
    public void onTransferReady(ServerConnection source) {
        String fromServerName = source.getServerInfo().getName();
        PendingTransfer transfer;
        String destinationName;

        synchronized (transferLock) {
            if (pending == null || pending.phase != TransferPhase.COUNTDOWN
                || !pending.sourceNames.contains(fromServerName)) {
                logger.warn("[linkedhardcore] Ignoring stale TRANSFER_READY from '{}'", fromServerName);
                return;
            }

            destinationName = pending.destinationName;
            ServerStatus destinationStatus = destinationName == null ? null : servers.get(destinationName);
            if (destinationStatus == null || !destinationStatus.is(ServerState.READY)) {
                logger.warn("[linkedhardcore] Countdown destination '{}' is no longer ready; returning to wait state",
                    destinationName);
                pending.destinationName = null;
                pending.phase = TransferPhase.WAITING_FOR_DESTINATION;
                attemptTransferLocked();
                return;
            }

            pending.phase = TransferPhase.CONNECTING;
            transfer = pending;
        }

        beginConnections(transfer, destinationName);
    }

    /** Called by the status poller when a fresh status file reports an empty ready backend. */
    public void onServerReady(String serverName) {
        ServerStatus status = servers.get(serverName);
        if (status != null && !status.is(ServerState.TRANSFERRING)) {
            status.tryTransition(ServerState.READY, logger);
        }

        synchronized (transferLock) {
            if (pending != null && pending.phase == TransferPhase.WAITING_FOR_DESTINATION) {
                logger.info("[linkedhardcore] Server '{}' ready; resuming transfer for {}", serverName, pending.sourceNames);
                attemptTransferLocked();
            }
        }
    }

    /** Handles RESET_COMPLETE using the logical server id carried by the protocol. */
    public void onResetComplete(String serverId) {
        Optional<String> velocityName = config.velocityNameForServerId(serverId);
        if (velocityName.isEmpty()) {
            logger.warn("[linkedhardcore] RESET_COMPLETE for unconfigured serverId '{}'", serverId);
            return;
        }
        onServerReady(velocityName.get());
    }

    /** Chooses a ready destination and sends a message to every occupied source backend. */
    private void attemptTransferLocked() {
        if (pending == null) {
            return;
        }

        Set<String> newlyActive = activeBackendServers();
        newlyActive.removeAll(pending.sourceNames);
        if (!newlyActive.isEmpty()) {
            if (!markSourcesTransferringLocked(newlyActive)) {
                logger.error("[linkedhardcore] Could not include active backends in the linked transfer: {}", newlyActive);
            } else {
                pending.sourceNames.addAll(newlyActive);
            }
        }

        Optional<RegisteredServer> ready = findReadyBackendExcept(pending.sourceNames);
        if (ready.isPresent()) {
            pending.destinationName = ready.get().getServerInfo().getName();
            pending.phase = TransferPhase.COUNTDOWN;
            sendToSourcesLocked(Protocol.encodePrepareTransfer(), "PREPARE_TRANSFER");
        } else {
            pending.destinationName = null;
            pending.phase = TransferPhase.WAITING_FOR_DESTINATION;
            sendToSourcesLocked(Protocol.encodeWaitForServer(), "WAIT_FOR_SERVER");
        }
    }

    private void beginConnections(PendingTransfer transfer, String destinationName) {
        Optional<RegisteredServer> destination = proxy.getServer(destinationName);
        if (destination.isEmpty()) {
            completeTransfer(transfer, destinationName, false);
            return;
        }

        List<CompletableFuture<Boolean>> outcomes = new ArrayList<>();
        for (Player player : proxy.getAllPlayers()) {
            String currentServer = player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse(null);
            if (destinationName.equals(currentServer)) {
                continue;
            }
            outcomes.add(player.createConnectionRequest(destination.get()).connect().handle((result, error) -> {
                boolean successful = error == null && result != null && result.isSuccessful();
                if (successful) {
                    logger.info("[linkedhardcore] Transferred {} to {}", player.getUsername(), destinationName);
                } else {
                    logger.warn("[linkedhardcore] Failed to transfer {} to {}: {}", player.getUsername(), destinationName,
                        error != null ? error : result);
                }
                return successful;
            }));
        }

        CompletableFuture.allOf(outcomes.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
            boolean allSuccessful = error == null && outcomes.stream().allMatch(outcome -> Boolean.TRUE.equals(outcome.getNow(false)));
            completeTransfer(transfer, destinationName, allSuccessful);
        });
    }

    private void completeTransfer(PendingTransfer transfer, String destinationName, boolean connectionsSucceeded) {
        Set<String> resetTargets = Set.of();
        synchronized (transferLock) {
            if (pending != transfer || pending.phase != TransferPhase.CONNECTING) {
                return;
            }

            boolean sourcesEmpty = transfer.sourceNames.stream().allMatch(this::isServerEmpty);
            if (!connectionsSucceeded || !sourcesEmpty) {
                logger.error("[linkedhardcore] Transfer to '{}' did not complete safely (connectionsSucceeded={}, sourcesEmpty={}). "
                    + "No reset will be signalled; operator intervention may be required.",
                    destinationName, connectionsSucceeded, sourcesEmpty);
                restoreSourceStatesLocked(transfer.sourceNames);
                pending = null;
                return;
            }

            LinkedHashSet<String> targets = new LinkedHashSet<>();
            for (String sourceName : transfer.sourceNames) {
                ServerStatus sourceStatus = servers.get(sourceName);
                if (sourceStatus != null) {
                    sourceStatus.tryTransition(ServerState.RESETTING, logger);
                    if (sourceStatus.is(ServerState.RESETTING)) {
                        targets.add(sourceName);
                    }
                }
            }
            ServerStatus destinationStatus = servers.get(destinationName);
            if (destinationStatus != null) {
                destinationStatus.tryTransition(ServerState.LIVE, logger);
            }
            pending = null;
            resetTargets = targets;
        }

        for (String sourceName : resetTargets) {
            PluginConfig.BackendServer backend = config.backendServers().get(sourceName);
            if (backend == null) {
                logger.error("[linkedhardcore] No backend config for reset target '{}'", sourceName);
                continue;
            }
            try {
                resetSignaller.signalReset(backend.serverId());
            } catch (Exception e) {
                logger.error("[linkedhardcore] Failed to signal reset for '{}': {}", sourceName, e.toString());
            }
        }
    }

    private Set<String> activeBackendServers() {
        LinkedHashSet<String> active = new LinkedHashSet<>();
        for (String name : config.backendServers().keySet()) {
            proxy.getServer(name).filter(server -> !server.getPlayersConnected().isEmpty()).ifPresent(server -> active.add(name));
        }
        return active;
    }

    private boolean markSourcesTransferringLocked(Set<String> sourceNames) {
        for (String sourceName : sourceNames) {
            ServerStatus status = servers.get(sourceName);
            if (status == null || status.is(ServerState.RESETTING)) {
                return false;
            }
            if (!status.is(ServerState.TRANSFERRING)) {
                status.tryTransition(ServerState.LIVE, logger);
                if (!status.is(ServerState.LIVE) || !status.tryTransition(ServerState.TRANSFERRING, logger)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void restoreSourceStatesLocked(Set<String> sourceNames) {
        for (String sourceName : sourceNames) {
            ServerStatus status = servers.get(sourceName);
            if (status != null && status.is(ServerState.TRANSFERRING)) {
                status.tryTransition(isServerEmpty(sourceName) ? ServerState.READY : ServerState.LIVE, logger);
            }
        }
    }

    private boolean isServerEmpty(String serverName) {
        return proxy.getServer(serverName).map(server -> server.getPlayersConnected().isEmpty()).orElse(true);
    }

    private void broadcastCounters() {
        for (String name : config.backendServers().keySet()) {
            proxy.getServer(name).ifPresent(server -> DeathCounterBroadcaster.send(server, deathCounters));
        }
    }

    /** First configured backend that is READY and does not currently host linked players. */
    private Optional<RegisteredServer> findReadyBackendExcept(Set<String> excludedNames) {
        for (String name : config.backendServers().keySet()) {
            if (excludedNames.contains(name)) {
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

    private void sendToSourcesLocked(byte[] frame, String messageName) {
        int sent = 0;
        for (String sourceName : pending.sourceNames) {
            Optional<RegisteredServer> source = proxy.getServer(sourceName);
            if (source.isPresent() && !source.get().getPlayersConnected().isEmpty()) {
                source.get().sendPluginMessage(Protocol.CHANNEL, frame);
                sent++;
            }
        }
        if (sent == 0) {
            logger.warn("[linkedhardcore] {} could not be delivered: no source backend has a player connection", messageName);
        } else {
            logger.info("[linkedhardcore] {} sent to {} source backend(s); destination={}",
                messageName, sent, pending.destinationName);
        }
    }

    private enum TransferPhase {
        WAITING_FOR_DESTINATION,
        COUNTDOWN,
        CONNECTING
    }

    private static final class PendingTransfer {
        private final LinkedHashSet<String> sourceNames;
        private String destinationName;
        private TransferPhase phase;

        private PendingTransfer(Set<String> sourceNames) {
            this.sourceNames = new LinkedHashSet<>(sourceNames);
            this.phase = TransferPhase.WAITING_FOR_DESTINATION;
        }
    }
}

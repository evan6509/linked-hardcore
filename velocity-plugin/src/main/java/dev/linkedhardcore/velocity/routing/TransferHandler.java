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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Routes the linked player pool and starts reset only after transfer completion. */
public final class TransferHandler {
    private final ProxyServer proxy;
    private final PluginConfig config;
    private final Map<String, ServerStatus> servers;
    private final ResetSignaller resetSignaller;
    private final Logger logger;
    private final DeathCounterState deathCounters;
    private final java.util.function.Consumer<DeathCounterState> persistDeathCounters;
    private final AtomicReference<PendingTransfer> pending = new AtomicReference<>();
    private final AtomicBoolean transferInProgress = new AtomicBoolean();
    private final DeathFlowGate deathFlowGate = new DeathFlowGate();

    public TransferHandler(ProxyServer proxy, PluginConfig config,
                           Map<String, ServerStatus> servers, ResetSignaller resetSignaller,
                           Logger logger, DeathCounterState deathCounters,
                           java.util.function.Consumer<DeathCounterState> persistDeathCounters) {
        this.proxy = proxy;
        this.config = config;
        this.servers = servers;
        this.resetSignaller = resetSignaller;
        this.logger = logger;
        this.deathCounters = deathCounters;
        this.persistDeathCounters = persistDeathCounters;
    }

    public void onPlayerDied(ServerConnection source, UUID playerUuid) {
        if (!deathFlowGate.begin()) {
            logger.warn("[linkedhardcore] Ignoring additional death while the current run-over flow is active");
            return;
        }
        String sourceName = source.getServerInfo().getName();
        Player player = source.getPlayer();
        DeathCounterState.DeathRecord record = deathCounters.recordDeath(playerUuid, player.getUsername());
        persistDeathCounters.accept(deathCounters);
        broadcastCounters();
        logger.info("[linkedhardcore] PLAYER_DIED from '{}': {} now has {} death(s)", sourceName, player.getUsername(), record.deaths());
        source.sendPluginMessage(Protocol.CHANNEL, Protocol.encodeAck(playerUuid));
        attemptTransfer(sourceName);
    }

    public void onTransferReady(ServerConnection source) {
        if (!transferInProgress.compareAndSet(false, true)) {
            logger.warn("[linkedhardcore] Ignoring duplicate TRANSFER_READY from '{}'", source.getServerInfo().getName());
            return;
        }

        String sourceName = source.getServerInfo().getName();
        PendingTransfer current = pending.getAndSet(null);
        String destinationName = current != null ? current.destinationName() : findReadyBackendExcept(sourceName)
            .map(s -> s.getServerInfo().getName()).orElse(null);
        if (destinationName == null) {
            transferInProgress.set(false);
            deathFlowGate.complete();
            logger.error("[linkedhardcore] No ready backend to transfer to (from '{}')", sourceName);
            return;
        }

        Optional<RegisteredServer> destination = proxy.getServer(destinationName);
        if (destination.isEmpty()) {
            transferInProgress.set(false);
            deathFlowGate.complete();
            logger.error("[linkedhardcore] Destination server '{}' is not registered", destinationName);
            return;
        }

        List<CompletableFuture<Boolean>> transfers = new ArrayList<>();
        for (Player player : proxy.getAllPlayers()) {
            String currentServer = player.getCurrentServer().map(sc -> sc.getServerInfo().getName()).orElse(null);
            if (destinationName.equals(currentServer)) {
                continue;
            }
            CompletableFuture<Boolean> result = player.createConnectionRequest(destination.get()).connect()
                .handle((connectionResult, error) -> {
                    boolean success = error == null && connectionResult != null && connectionResult.isSuccessful();
                    if (!success) {
                        logger.warn("[linkedhardcore] Failed to transfer {} to {}: {}", player.getUsername(), destinationName,
                            error != null ? error : connectionResult);
                    } else {
                        logger.info("[linkedhardcore] Transferred {} to {}", player.getUsername(), destinationName);
                    }
                    return success;
                });
            transfers.add(result);
        }

        logger.info("[linkedhardcore] Waiting for {} player transfer(s) before preparing '{}'", transfers.size(), sourceName);
        TransferCompletion.allSuccessful(transfers).thenAccept(success -> {
            if (success) {
                completeTransfer(sourceName, destinationName);
            } else {
                transferInProgress.set(false);
                deathFlowGate.complete();
                logger.error("[linkedhardcore] Transfer to '{}' was incomplete; '{}' will NOT be prepared", destinationName, sourceName);
            }
        });
    }

    private void broadcastCounters() {
        for (String name : config.backendServers().keySet()) {
            proxy.getServer(name).ifPresent(server -> DeathCounterBroadcaster.send(server, deathCounters));
        }
    }

    private void completeTransfer(String sourceName, String destinationName) {
        ServerStatus fromStatus = servers.get(sourceName);
        ServerStatus toStatus = servers.get(destinationName);
        try {
            boolean sourceEmpty = proxy.getServer(sourceName)
                .map(server -> server.getPlayersConnected().isEmpty())
                .orElse(false);
            if (!sourceEmpty) {
                logger.error("[linkedhardcore] Source '{}' still has players; refusing to prepare it", sourceName);
                return;
            }
            if (fromStatus != null) {
                fromStatus.markResettingAfterTransfer(logger);
            }
            if (toStatus != null && toStatus.is(ServerState.READY)) {
                toStatus.transition(ServerState.LIVE, logger);
            }
            resetSignaller.signalReset(sourceName);
            logger.info("[linkedhardcore] All players transferred to '{}'; reset preparation started for '{}'", destinationName, sourceName);
        } catch (Exception e) {
            logger.error("[linkedhardcore] Could not prepare '{}' after transfer: {}", sourceName, e.toString());
        } finally {
            transferInProgress.set(false);
            deathFlowGate.complete();
        }
    }

    public void onServerReady(String serverName) {
        ServerStatus status = servers.get(serverName);
        if (status != null && status.is(ServerState.RESETTING)) {
            status.transition(ServerState.READY, logger);
        }
        PendingTransfer current = pending.get();
        if (current != null && (current.destinationName() == null || serverName.equals(current.destinationName()))) {
            pending.set(null);
            attemptTransfer(current.sourceName());
        }
    }

    public void onResetComplete(String serverId) {
        config.velocityNameForServerId(serverId).ifPresentOrElse(this::onServerReady,
            () -> logger.warn("[linkedhardcore] RESET_COMPLETE for unconfigured serverId '{}'", serverId));
    }

    private void attemptTransfer(String sourceName) {
        if (transferInProgress.get()) {
            return;
        }
        Optional<RegisteredServer> ready = findReadyBackendExcept(sourceName);
        if (ready.isPresent()) {
            String destinationName = ready.get().getServerInfo().getName();
            pending.set(new PendingTransfer(sourceName, destinationName));
            proxy.getServer(sourceName).ifPresent(server ->
                server.sendPluginMessage(Protocol.CHANNEL, Protocol.encodePrepareTransfer()));
            logger.info("[linkedhardcore] PREPARE_TRANSFER sent to '{}' (destination '{}')", sourceName, destinationName);
        } else {
            pending.set(new PendingTransfer(sourceName, null));
            proxy.getServer(sourceName).ifPresent(server ->
                server.sendPluginMessage(Protocol.CHANNEL, Protocol.encodeWaitForServer()));
            logger.info("[linkedhardcore] No ready destination; WAIT_FOR_SERVER sent to '{}'", sourceName);
        }
    }

    private Optional<RegisteredServer> findReadyBackendExcept(String excludeName) {
        for (String name : config.backendServers().keySet()) {
            if (name.equals(excludeName)) continue;
            ServerStatus status = servers.get(name);
            if (status != null && status.is(ServerState.READY)) {
                Optional<RegisteredServer> server = proxy.getServer(name);
                if (server.isPresent()) return server;
            }
        }
        return Optional.empty();
    }

    private record PendingTransfer(String sourceName, String destinationName) {}
}

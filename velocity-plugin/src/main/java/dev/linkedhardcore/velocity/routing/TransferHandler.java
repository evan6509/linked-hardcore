package dev.linkedhardcore.velocity.routing;

import com.google.gson.Gson;
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
import dev.linkedhardcore.velocity.reset.FileResetSignaller;
import dev.linkedhardcore.velocity.reset.ResetRequest;
import dev.linkedhardcore.velocity.reset.ResetSignaller;
import dev.linkedhardcore.velocity.status.BackendSnapshot;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
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
    private final Runnable onRunEnded;
    private final Consumer<String> onNewRun;

    private final Object transferLock = new Object();
    private PendingTransfer pending;
    private final Map<String, ResetRequest> pendingResets = new HashMap<>();
    private final Set<String> publishingResets = new LinkedHashSet<>();
    private final Map<String, String> consumedGenerations = new HashMap<>();
    private final Map<String, String> resetAfterTransfer = new LinkedHashMap<>();
    private final Path currentWorldFile;
    private CurrentWorld currentWorld;
    private String currentRunId;
    private static final Gson GSON = new Gson();

    public TransferHandler(ProxyServer proxy, PluginConfig config,
                           Map<String, ServerStatus> servers, ResetSignaller resetSignaller, Logger logger) {
        this(proxy, config, servers, resetSignaller, logger, new DeathCounterState(), ignored -> { });
    }

    public TransferHandler(ProxyServer proxy, PluginConfig config,
                           Map<String, ServerStatus> servers, ResetSignaller resetSignaller, Logger logger,
                           DeathCounterState deathCounters, Consumer<DeathCounterState> persistDeathCounters) {
        this(proxy, config, servers, resetSignaller, logger, deathCounters, persistDeathCounters,
            () -> { }, () -> { });
    }

    public TransferHandler(ProxyServer proxy, PluginConfig config,
                           Map<String, ServerStatus> servers, ResetSignaller resetSignaller, Logger logger,
                           DeathCounterState deathCounters, Consumer<DeathCounterState> persistDeathCounters,
                           Runnable onRunEnded, Runnable onNewRun) {
        this(proxy, config, servers, resetSignaller, logger, deathCounters, persistDeathCounters,
            onRunEnded, onNewRun, null);
    }

    public TransferHandler(ProxyServer proxy, PluginConfig config,
                           Map<String, ServerStatus> servers, ResetSignaller resetSignaller, Logger logger,
                           DeathCounterState deathCounters, Consumer<DeathCounterState> persistDeathCounters,
                           Runnable onRunEnded, Runnable onNewRun, Path currentWorldFile) {
        this(proxy, config, servers, resetSignaller, logger, deathCounters, persistDeathCounters,
            onRunEnded, ignored -> onNewRun.run(), currentWorldFile);
    }

    public TransferHandler(ProxyServer proxy, PluginConfig config,
                           Map<String, ServerStatus> servers, ResetSignaller resetSignaller, Logger logger,
                           DeathCounterState deathCounters, Consumer<DeathCounterState> persistDeathCounters,
                           Runnable onRunEnded, Consumer<String> onNewRun, Path currentWorldFile) {
        this.currentWorldFile = currentWorldFile;
        this.proxy = proxy;
        this.config = config;
        this.servers = servers;
        this.resetSignaller = resetSignaller;
        this.logger = logger;
        this.deathCounters = deathCounters;
        this.persistDeathCounters = persistDeathCounters;
        this.onRunEnded = onRunEnded;
        this.onNewRun = onNewRun;
        loadLifecycle();
        if (pending != null && pending.phase != TransferPhase.COMMITTING) {
            if (pending.deathCounter != null) {
                deathCounters.restore(pending.deathCounter);
                persistDeathCounters.accept(deathCounters);
            }
            onRunEnded.run();
        }
    }

    /** Recover an ended timer from the previous flat current-world format during upgrade. */
    public void recoverEndedRun(boolean ended) {
        recoverRunState(ended, null);
    }

    public void recoverRunState(boolean ended, String timerRunId) {
        synchronized (transferLock) {
            if (pending != null) return;
            if (currentRunId != null && !currentRunId.equals(timerRunId)) {
                // A crash can leave the timer checkpoint behind the committed world.
                // Repair that epoch; do not interpret the old source's ended timer as a death in this world.
                onNewRun.accept(currentRunId);
                return;
            }
            if (!ended) return;
            if (currentWorld == null) throw new IllegalStateException("Ended run has no world identity; restore its current-world.json before routing");
            pending = new PendingTransfer(Set.of(currentWorld.serverName));
            pending.sourceGenerations.put(currentWorld.serverName, currentWorld.generationId);
            checkpointLifecycle();
            onRunEnded.run();
        }
    }

    /** ACKs a death and starts one serialized transfer for the shared life pool. */
    public void onPlayerDied(ServerConnection source, UUID playerUuid) {
        String fromServerName = source.getServerInfo().getName();
        logger.info("[linkedhardcore] PLAYER_DIED from '{}': player={}", fromServerName, playerUuid);

        boolean deathRecorded = false;
        synchronized (transferLock) {
            if (!servers.containsKey(fromServerName)) {
                logger.warn("[linkedhardcore] Ignoring death from unconfigured backend '{}'", fromServerName);
                return;
            }
            if (pending != null) {
                source.sendPluginMessage(Protocol.CHANNEL, Protocol.encodeAck(playerUuid));
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

            PendingTransfer accepted = new PendingTransfer(sourceNames);
            for (String name : sourceNames) {
                BackendSnapshot identity = BackendSnapshot.readIdentity(config.backendServers().get(name));
                if (identity == null) {
                    restoreSourceStatesLocked(sourceNames);
                    logger.error("[linkedhardcore] Cannot record the ended world's identity for '{}'", name);
                    return;
                }
                accepted.sourceGenerations.put(name, identity.generationId());
            }
            Player player = source.getPlayer();
            String playerName = player == null ? playerUuid.toString() : player.getUsername();
            accepted.deathCounter = new DeathCounterState.DeathRecord(playerUuid, playerName,
                deathCounters.count(playerUuid) + 1);
            try {
                // Publish in-memory acceptance only after the prospective transfer is durable.
                checkpointLifecycle(accepted);
            } catch (IllegalStateException e) {
                restoreSourceStatesLocked(sourceNames);
                throw e;
            }
            pending = accepted;
            deathCounters.restore(accepted.deathCounter);
            persistDeathCounters.accept(deathCounters);
            source.sendPluginMessage(Protocol.CHANNEL, Protocol.encodeAck(playerUuid));
            deathRecorded = true;
            onRunEnded.run();
            attemptTransferLocked();
        }

        if (deathRecorded) {
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
            if (destinationStatus == null || !isPreparedDestinationLocked(destinationName)) {
                logger.warn("[linkedhardcore] Countdown destination '{}' is no longer ready; returning to wait state",
                    destinationName);
                pending.destinationName = null;
                pending.phase = TransferPhase.WAITING_FOR_DESTINATION;
                attemptTransferLocked();
                return;
            }

            BackendSnapshot destinationWorld = snapshot(destinationName);
            if (destinationWorld == null || !destinationWorld.generationId().equals(pending.destinationGenerationId)) {
                pending.phase = TransferPhase.WAITING_FOR_DESTINATION;
                attemptTransferLocked();
                return;
            }
            pending.phase = TransferPhase.CONNECTING;
            checkpointLifecycle(); // Recovery may continue this exact generation even after its first arrival.
            transfer = pending;
        }

        beginConnections(transfer, destinationName);
    }

    /** All readiness paths, including legacy protocol hints, validate the same durable evidence. */
    public void onServerReady(String serverName) {
        synchronized (transferLock) {
            processResetCleanupLocked();
            refreshServerLocked(serverName);
            advancePendingLocked();
        }
    }

    /** The old message carries no generation identity; it is only a request to re-read the files. */
    public void onResetComplete(String serverId) {
        config.velocityNameForServerId(serverId).ifPresent(this::onServerReady);
    }

    private void refreshServerLocked(String name) {
        ServerStatus status = servers.get(name);
        PluginConfig.BackendServer backend = config.backendServers().get(name);
        if (status == null || backend == null || status.is(ServerState.TRANSFERRING)) return;
        if (resetAfterTransfer.containsKey(name) && !pendingResets.containsKey(name)) {
            status.tryTransition(ServerState.RESETTING, logger);
            return;
        }
        Path dir = Path.of(backend.statusFile()).getParent();
        Path pendingFile = dir.resolve("reset.pending.json");
        ResetRequest expected = pendingResets.get(name);
        if (Files.exists(pendingFile)) {
            try {
                ResetRequest durable = GSON.fromJson(Files.readString(pendingFile), ResetRequest.class);
                if (durable == null || !durable.validFor(backend.serverId())
                    || (expected != null && !expected.equals(durable))) throw new IOException("Invalid or conflicting reset expectation");
                expected = durable;
                pendingResets.put(name, expected);
            } catch (Exception e) {
                status.tryTransition(ServerState.UNAVAILABLE, logger);
                return;
            }
        }
        BackendSnapshot snapshot = BackendSnapshot.read(backend, config.statusStaleSeconds());
        if (snapshot == null) {
            status.tryTransition(ServerState.UNAVAILABLE, logger);
            return;
        }
        boolean requestPresent = Files.exists(dir.resolve("reset.request.json"));
        if (expected != null) {
            if (requestPresent || !snapshot.completes(expected) || !isServerEmpty(name)) {
                status.tryTransition(ServerState.RESETTING, logger);
                // Retry a failed publication with the same identity. Never replace a
                // newer on-disk request, or interrupt an executor that has acknowledged it.
                if (!requestPresent && !snapshot.generationId().equals(expected.requestId()) && !publishingResets.contains(name)) {
                    publishResetLocked(name, expected);
                }
                return;
            }
            try {
                Files.deleteIfExists(pendingFile);
            } catch (IOException e) {
                logger.error("[linkedhardcore] Could not acknowledge reset for '{}'", name, e);
                return;
            }
            pendingResets.remove(name);
        } else if (requestPresent || "resetting".equals(snapshot.state())) {
            status.tryTransition(ServerState.RESETTING, logger);
            return;
        }
        if (snapshot.prepared() && !snapshot.generationId().equals(consumedGenerations.get(name)) && isServerEmpty(name)) {
            status.tryTransition(ServerState.READY, logger);
        } else {
            // A used empty world stays resumable. Logging out never prepares or wipes it.
            status.tryTransition(ServerState.LIVE, logger);
        }
    }

    private boolean isPreparedDestinationLocked(String name) {
        refreshServerLocked(name);
        ServerStatus status = servers.get(name);
        BackendSnapshot snapshot = snapshot(name);
        return status != null && status.is(ServerState.READY) && snapshot != null && snapshot.prepared()
            && !snapshot.generationId().equals(consumedGenerations.get(name)) && isServerEmpty(name)
            && !resetAfterTransfer.containsKey(name)
            && !pendingResets.containsKey(name) && !resetFileExists(name, "reset.pending.json")
            && !resetFileExists(name, "reset.request.json");
    }

    private BackendSnapshot snapshot(String name) {
        PluginConfig.BackendServer backend = config.backendServers().get(name);
        return backend == null ? null : BackendSnapshot.read(backend, config.statusStaleSeconds());
    }

    private boolean resetFileExists(String name, String file) {
        return Files.exists(Path.of(config.backendServers().get(name).statusFile()).resolveSibling(file));
    }

    private void scheduleResetLocked(String name) {
        if (pendingResets.containsKey(name) || resetFileExists(name, "reset.pending.json")
            || resetFileExists(name, "reset.request.json")) return;
        PluginConfig.BackendServer backend = config.backendServers().get(name);
        BackendSnapshot old = BackendSnapshot.readIdentity(backend);
        if (old == null) {
            servers.get(name).tryTransition(ServerState.UNAVAILABLE, logger);
            logger.error("[linkedhardcore] Cannot identify the old world/boot for reset of '{}'", name);
            return;
        }
        ResetRequest request = ResetRequest.create(backend.serverId(), old.generationId(), old.bootId());
        // Install the latch BEFORE state changes or publication. The poller uses
        // this same lock, and the durable copy survives UNAVAILABLE and restarts.
        pendingResets.put(name, request);
        servers.get(name).tryTransition(ServerState.RESETTING, logger);
        publishResetLocked(name, request);
    }

    private void publishResetLocked(String name, ResetRequest request) {
        publishingResets.add(name);
        try {
            resetSignaller.signalReset(request);
        } catch (IOException e) {
            logger.error("[linkedhardcore] Failed to publish reset for '{}'; backend remains ineligible", name, e);
        } finally {
            publishingResets.remove(name);
        }
    }

    public boolean onBackendJoined(String name) {
        synchronized (transferLock) {
            // A login approved before a death can finish after the handoff commits.
            // It must not replace the committed pointer with the retired source.
            if (resetAfterTransfer.containsKey(name) || pendingResets.containsKey(name)
                || (config.backendServers().containsKey(name) && (resetFileExists(name, "reset.pending.json")
                    || resetFileExists(name, "reset.request.json")))
                || (pending == null && currentWorld != null && !currentWorld.serverName.equals(name))) return false;
            // Connection futures are fixed once fan-out starts; later source arrivals cannot join them.
            if (pending != null && pending.sourceNames.contains(name)
                && pending.phase != TransferPhase.WAITING_FOR_DESTINATION
                && pending.phase != TransferPhase.COUNTDOWN) return false;
            if (pending != null && !pending.sourceNames.contains(name)
                && !(name.equals(pending.destinationName) && (pending.phase == TransferPhase.CONNECTING
                    || pending.phase == TransferPhase.RECOVERING || pending.phase == TransferPhase.COMMITTING))) return false;
            ServerStatus status = servers.get(name);
            PluginConfig.BackendServer backend = config.backendServers().get(name);
            BackendSnapshot snapshot = backend == null ? null : BackendSnapshot.readIdentity(backend);
            if (status == null) return false;
            if (snapshot != null) {
                consumedGenerations.put(name, snapshot.generationId());
                if (pending == null) saveCurrentWorld(name, snapshot.generationId());
            }
            if (!status.is(ServerState.RESETTING) && !status.is(ServerState.TRANSFERRING)) {
                status.tryTransition(ServerState.LIVE, logger);
            }
            if (pending != null && pending.sourceNames.contains(name)) {
                byte[] frame = pending.phase == TransferPhase.COUNTDOWN
                    ? Protocol.encodePrepareTransfer() : Protocol.encodeWaitForServer();
                proxy.getServer(name).ifPresent(server -> server.sendPluginMessage(Protocol.CHANNEL, frame));
            }
            return true;
        }
    }

    /** Resume the persisted current run, including after everybody logs out or the proxy restarts. */
    public Optional<RegisteredServer> initialServer() {
        synchronized (transferLock) {
            processResetCleanupLocked();
            advancePendingLocked();
            if (pending != null) return Optional.empty();
            for (String name : config.backendServers().keySet()) refreshServerLocked(name);
            if (currentWorld != null) {
                BackendSnapshot snapshot = snapshot(currentWorld.serverName);
                ServerStatus status = servers.get(currentWorld.serverName);
                if (snapshot != null && status != null && (status.is(ServerState.LIVE) || status.is(ServerState.READY))
                    && snapshot.generationId().equals(currentWorld.generationId)) {
                    return proxy.getServer(currentWorld.serverName);
                }
                // Do not silently start elsewhere when the resumable world is temporarily offline.
                return Optional.empty();
            }
            for (String name : config.backendServers().keySet()) {
                if (servers.get(name).is(ServerState.LIVE) && !isServerEmpty(name)) return selectInitialServerLocked(name);
            }
            for (String name : config.backendServers().keySet()) {
                if (servers.get(name).is(ServerState.LIVE)) return selectInitialServerLocked(name);
            }
            return findReadyBackendExcept(Set.of())
                .flatMap(server -> selectInitialServerLocked(server.getServerInfo().getName()));
        }
    }

    private Optional<RegisteredServer> selectInitialServerLocked(String name) {
        BackendSnapshot snapshot = snapshot(name);
        Optional<RegisteredServer> server = proxy.getServer(name);
        if (snapshot == null || server.isEmpty()) return Optional.empty();
        saveCurrentWorld(name, snapshot.generationId());
        return server;
    }

    /** Gate every new backend connection so resetting worlds cannot acquire players. */
    public Optional<RegisteredServer> connectionServer(boolean initialConnection, String requestedName) {
        synchronized (transferLock) {
            if (initialConnection) return initialServer();
            if (pending != null) {
                if (pending.phase == TransferPhase.CONNECTING && requestedName.equals(pending.destinationName)
                    && !pendingResets.containsKey(requestedName)
                    && !resetFileExists(requestedName, "reset.pending.json")
                    && !resetFileExists(requestedName, "reset.request.json")) {
                    BackendSnapshot destination = BackendSnapshot.readContinuation(config.backendServers().get(requestedName), config.statusStaleSeconds());
                    if (destination != null && !"resetting".equals(destination.state())
                        && destination.generationId().equals(pending.destinationGenerationId)) {
                        return proxy.getServer(requestedName);
                    }
                }
                return Optional.empty();
            }
            // Ordinary reconnects/server commands stay in the current run.
            return initialServer().filter(server -> requestedName.equals(server.getServerInfo().getName()));
        }
    }

    private void saveCurrentWorld(String name, String generationId) {
        currentWorld = new CurrentWorld(name, generationId);
        checkpointLifecycle();
    }

    private void checkpointLifecycle() {
        checkpointLifecycle(pending);
    }

    private void checkpointLifecycle(PendingTransfer transfer) {
        if (currentWorldFile == null) return;
        SavedLifecycle body = new SavedLifecycle(1, currentWorld == null ? null : currentWorld.serverName,
            currentWorld == null ? null : currentWorld.generationId,
            transfer == null ? null : transfer.save(), Map.copyOf(resetAfterTransfer), currentRunId);
        try {
            FileResetSignaller.writeAtomic(currentWorldFile, GSON.toJson(body));
        } catch (IOException e) {
            throw new IllegalStateException("Could not checkpoint linked-run lifecycle; routing must wait", e);
        }
    }

    private void loadLifecycle() {
        if (currentWorldFile == null || !Files.exists(currentWorldFile)) return;
        try {
            SavedLifecycle saved = GSON.fromJson(Files.readString(currentWorldFile), SavedLifecycle.class);
            if (saved == null || (saved.schemaVersion != 0 && saved.schemaVersion != 1)) throw new IOException("Invalid lifecycle schema");
            if (saved.serverName != null || saved.generationId != null) {
                if (!config.backendServers().containsKey(saved.serverName) || saved.generationId == null
                    || saved.generationId.isBlank()) throw new IOException("Invalid current world identity");
                currentWorld = new CurrentWorld(saved.serverName, saved.generationId);
            }
            currentRunId = saved.runId;
            if (currentRunId != null && currentRunId.isBlank()) throw new IOException("Invalid current run ID");
            if (saved.pendingTransfer != null) {
                SavedTransfer transfer = saved.pendingTransfer;
                if (transfer.runId == null || transfer.runId.isBlank() || transfer.sourceGenerations == null
                    || transfer.sourceGenerations.isEmpty() || !validGenerations(transfer.sourceGenerations)
                    || transfer.phase == null) throw new IOException("Invalid pending transfer");
                if (transfer.phase != TransferPhase.WAITING_FOR_DESTINATION
                    && (transfer.destinationName == null || !config.backendServers().containsKey(transfer.destinationName)
                        || transfer.sourceGenerations.containsKey(transfer.destinationName)
                        || transfer.destinationGenerationId == null || transfer.destinationGenerationId.isBlank())) {
                    throw new IOException("Invalid saved destination");
                }
                if (transfer.phase == TransferPhase.COMMITTING && (currentWorld == null
                    || !currentWorld.serverName.equals(transfer.destinationName)
                    || !currentWorld.generationId.equals(transfer.destinationGenerationId))) {
                    throw new IOException("Committed destination does not match the current world");
                }
                if (transfer.phase == TransferPhase.COMMITTING) {
                    if (currentRunId != null && !currentRunId.equals(transfer.runId)) throw new IOException("Committed run ID disagrees with current run");
                    currentRunId = transfer.runId;
                }
                pending = new PendingTransfer(transfer.sourceGenerations.keySet());
                pending.runId = transfer.runId;
                pending.sourceGenerations.putAll(transfer.sourceGenerations);
                pending.destinationName = transfer.destinationName;
                pending.destinationGenerationId = transfer.destinationGenerationId;
                pending.deathCounter = transfer.deathCounter;
                pending.phase = switch (transfer.phase) {
                    case COUNTDOWN -> TransferPhase.WAITING_FOR_DESTINATION;
                    case CONNECTING -> TransferPhase.RECOVERING;
                    default -> transfer.phase;
                };
            }
            if (saved.resetAfterTransfer != null) {
                if (!validGenerations(saved.resetAfterTransfer)) throw new IOException("Invalid reset cleanup");
                resetAfterTransfer.putAll(saved.resetAfterTransfer);
            }
            if (currentWorld == null && pending == null) throw new IOException("Missing current run");
        } catch (Exception e) {
            throw new IllegalStateException("Could not restore linked-run lifecycle " + currentWorldFile, e);
        }
    }

    private boolean validGenerations(Map<String, String> generations) {
        return generations.entrySet().stream().allMatch(entry -> config.backendServers().containsKey(entry.getKey())
            && entry.getValue() != null && !entry.getValue().isBlank());
    }

    /** Progress an ended run without requiring a player to carry a countdown response. */
    private void advancePendingLocked() {
        if (pending == null) return;
        if (pending.phase == TransferPhase.COMMITTING) {
            finishCommitLocked();
            return;
        }
        if (pending.phase == TransferPhase.CONNECTING) return; // Active connection futures own completion.
        if (pending.phase == TransferPhase.WAITING_FOR_DESTINATION) attemptTransferLocked();
        if (pending == null || !activeBackendServers().isEmpty()) return;
        if (pending.phase == TransferPhase.COUNTDOWN) {
            BackendSnapshot destination = snapshot(pending.destinationName);
            if (!isPreparedDestinationLocked(pending.destinationName) || destination == null
                || !destination.generationId().equals(pending.destinationGenerationId)) {
                pending.phase = TransferPhase.WAITING_FOR_DESTINATION;
                pending.destinationName = null;
                pending.destinationGenerationId = null;
                checkpointLifecycle();
                attemptTransferLocked();
                if (pending == null) return;
                destination = pending.phase == TransferPhase.COUNTDOWN ? snapshot(pending.destinationName) : null;
            }
            if (destination != null && destination.prepared()
                && destination.generationId().equals(pending.destinationGenerationId)) {
                pending.phase = TransferPhase.CONNECTING;
                checkpointLifecycle();
                completeTransfer(pending, pending.destinationName, true);
            }
        } else if (pending.phase == TransferPhase.RECOVERING) {
            BackendSnapshot destination = snapshot(pending.destinationName);
            if (destination != null && destination.playerCount() == 0 && !"resetting".equals(destination.state())
                && destination.generationId().equals(pending.destinationGenerationId)
                && !resetFileExists(pending.destinationName, "reset.pending.json")
                && !resetFileExists(pending.destinationName, "reset.request.json")) {
                pending.phase = TransferPhase.CONNECTING;
                checkpointLifecycle();
                completeTransfer(pending, pending.destinationName, true);
            }
        }
    }

    private void finishCommitLocked() {
        PendingTransfer completed = pending;
        // Idempotent timer start prevents a restart in this commit window from zeroing an active new run.
        onNewRun.accept(completed.runId);
        consumedGenerations.put(completed.destinationName, completed.destinationGenerationId);
        resetAfterTransfer.putAll(completed.sourceGenerations);
        pending = null;
        checkpointLifecycle();
        processResetCleanupLocked();
    }

    private void processResetCleanupLocked() {
        boolean changed = false;
        for (String name : List.copyOf(resetAfterTransfer.keySet())) {
            if (!isServerEmpty(name)) continue;
            BackendSnapshot identity = BackendSnapshot.readIdentity(config.backendServers().get(name));
            if (identity == null) continue; // A booting source is retried when it publishes its actual boot identity.
            if (!identity.generationId().equals(resetAfterTransfer.get(name))) {
                resetAfterTransfer.remove(name); // The old generation has already been retired.
                changed = true;
                continue;
            }
            scheduleResetLocked(name);
            if (resetFileExists(name, "reset.pending.json")) {
                resetAfterTransfer.remove(name);
                changed = true;
            }
        }
        if (changed) checkpointLifecycle();
    }

    private record CurrentWorld(String serverName, String generationId) { }
    private record SavedLifecycle(int schemaVersion, String serverName, String generationId,
                                  SavedTransfer pendingTransfer, Map<String, String> resetAfterTransfer, String runId) { }
    private record SavedTransfer(String runId, TransferPhase phase, Map<String, String> sourceGenerations,
                                 String destinationName, String destinationGenerationId,
                                 DeathCounterState.DeathRecord deathCounter) { }

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
                return;
            } else {
                for (String name : newlyActive) {
                    BackendSnapshot identity = BackendSnapshot.readIdentity(config.backendServers().get(name));
                    if (identity == null) return;
                    pending.sourceGenerations.put(name, identity.generationId());
                }
                pending.sourceNames.addAll(newlyActive);
            }
        }

        Optional<RegisteredServer> ready = findReadyBackendExcept(pending.sourceNames);
        BackendSnapshot candidate = ready.map(server -> snapshot(server.getServerInfo().getName())).orElse(null);
        if (candidate != null && candidate.prepared()) {
            pending.destinationName = ready.get().getServerInfo().getName();
            pending.destinationGenerationId = candidate.generationId();
            pending.phase = TransferPhase.COUNTDOWN;
            checkpointLifecycle();
            sendToSourcesLocked(Protocol.encodePrepareTransfer(), "PREPARE_TRANSFER");
        } else {
            // Old standby worlds are prepared only when a new run needs one,
            // never merely because their last player logged out.
            for (String name : config.backendServers().keySet()) {
                BackendSnapshot standby = snapshot(name);
                if (!pending.sourceNames.contains(name) && isServerEmpty(name)
                    && standby != null && standby.playerCount() == 0 && Boolean.TRUE.equals(standby.worldUsed())
                    && !pendingResets.containsKey(name) && !resetFileExists(name, "reset.pending.json")
                    && !resetFileExists(name, "reset.request.json")) {
                    scheduleResetLocked(name);
                    break;
                }
            }
            pending.destinationName = null;
            pending.phase = TransferPhase.WAITING_FOR_DESTINATION;
            checkpointLifecycle();
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
                pending.phase = TransferPhase.RECOVERING;
                checkpointLifecycle();
                return;
            }

            currentWorld = new CurrentWorld(destinationName, transfer.destinationGenerationId);
            currentRunId = transfer.runId;
            pending.phase = TransferPhase.COMMITTING;
            checkpointLifecycle();
            ServerStatus destinationStatus = servers.get(destinationName);
            if (destinationStatus != null) destinationStatus.tryTransition(ServerState.LIVE, logger);
            finishCommitLocked();
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
            if (status == null || status.is(ServerState.RESETTING) || pendingResets.containsKey(sourceName)
                || resetFileExists(sourceName, "reset.pending.json") || resetFileExists(sourceName, "reset.request.json")) {
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
                status.tryTransition(ServerState.LIVE, logger);
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
            if (status != null && isPreparedDestinationLocked(name)) {
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
        CONNECTING,
        RECOVERING,
        COMMITTING
    }

    private static final class PendingTransfer {
        private final LinkedHashSet<String> sourceNames;
        private final Map<String, String> sourceGenerations = new LinkedHashMap<>();
        private String runId = UUID.randomUUID().toString();
        private DeathCounterState.DeathRecord deathCounter;
        private String destinationName;
        private String destinationGenerationId;
        private TransferPhase phase;

        private SavedTransfer save() {
            return new SavedTransfer(runId, phase, Map.copyOf(sourceGenerations), destinationName,
                destinationGenerationId, deathCounter);
        }

        private PendingTransfer(Set<String> sourceNames) {
            this.sourceNames = new LinkedHashSet<>(sourceNames);
            this.phase = TransferPhase.WAITING_FOR_DESTINATION;
        }
    }
}

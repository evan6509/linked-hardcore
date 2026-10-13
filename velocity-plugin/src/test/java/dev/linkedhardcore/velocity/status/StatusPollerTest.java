package dev.linkedhardcore.velocity.status;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.linkedhardcore.velocity.config.PluginConfig;
import dev.linkedhardcore.velocity.model.ServerState;
import dev.linkedhardcore.velocity.model.ServerStatus;
import dev.linkedhardcore.velocity.routing.TransferHandler;
import dev.linkedhardcore.velocity.reset.FileResetSignaller;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatusPollerTest {
    @TempDir Path directory;
    private final Map<String, ServerStatus> statuses = new LinkedHashMap<>();

    @Test
    void freshPreparedStatusMakesAnUnavailableBackendRoutable() throws Exception {
        BackendFixture.publish(directory, "a", "world-a", "boot-a", false, 0, null);
        poller(ServerState.UNAVAILABLE, 5).pollOnce();
        assertEquals(ServerState.READY, statuses.get("alpha").state());
    }

    @Test
    void staleStatusMakesAFormerlyReadyBackendUnavailable() throws Exception {
        BackendFixture.publish(directory, "a", "world-a", "boot-a", false, 0, null);
        Files.setLastModifiedTime(directory.resolve("status.json"), FileTime.fromMillis(System.currentTimeMillis() - 5_000));
        poller(ServerState.READY, 1).pollOnce();
        assertEquals(ServerState.UNAVAILABLE, statuses.get("alpha").state());
    }

    @Test
    void usedEmptyWorldAfterOrdinaryRestartStaysResumable() throws Exception {
        BackendFixture.publish(directory, "a", "used-world", "new-boot", true, 0, null);
        poller(ServerState.UNAVAILABLE, 5).pollOnce();
        assertEquals(ServerState.LIVE, statuses.get("alpha").state());
    }

    @Test
    void legacyReadyHeartbeatDoesNotProvePreparation() throws Exception {
        Files.writeString(directory.resolve("status.json"), "{\"state\":\"ready\",\"playerCount\":0}");
        poller(ServerState.READY, 5).pollOnce();
        assertEquals(ServerState.UNAVAILABLE, statuses.get("alpha").state());
    }

    @Test
    void delayedUnusedSnapshotCannotOverwriteDurableWorldUse() throws Exception {
        BackendFixture.publish(directory, "a", "world-a", "boot-a", false, 0, null);
        String oldStatus = Files.readString(directory.resolve("status.json"));
        BackendFixture.publish(directory, "a", "world-a", "boot-a", true, 0, null);
        Files.writeString(directory.resolve("status.json"), oldStatus);
        poller(ServerState.READY, 5).pollOnce();
        assertEquals(ServerState.UNAVAILABLE, statuses.get("alpha").state());
    }

    @Test
    void wrongServerIdentityIsRejected() throws Exception {
        BackendFixture.publish(directory, "other-server", "world-a", "boot-a", false, 0, null);
        poller(ServerState.READY, 5).pollOnce();
        assertEquals(ServerState.UNAVAILABLE, statuses.get("alpha").state());
    }

    private StatusPoller poller(ServerState state, int staleSeconds) {
        ProxyServer proxy = mock(ProxyServer.class);
        RegisteredServer server = mock(RegisteredServer.class);
        when(server.getPlayersConnected()).thenReturn(List.of());
        when(proxy.getServer("alpha")).thenReturn(Optional.of(server));
        Map<String, PluginConfig.BackendServer> backends = new LinkedHashMap<>();
        backends.put("alpha", new PluginConfig.BackendServer("alpha", "a", directory.resolve("status.json").toString()));
        PluginConfig config = new PluginConfig(backends, 1, staleSeconds);
        statuses.put("alpha", new ServerStatus("alpha", state));
        var logger = LoggerFactory.getLogger("test");
        TransferHandler handler = new TransferHandler(proxy, config, statuses, new FileResetSignaller(config, logger), logger);
        return new StatusPoller(this, proxy, config, statuses, handler, logger);
    }
}

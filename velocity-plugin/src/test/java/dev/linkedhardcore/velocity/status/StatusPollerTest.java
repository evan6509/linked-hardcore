package dev.linkedhardcore.velocity.status;

import com.velocitypowered.api.proxy.ProxyServer;
import dev.linkedhardcore.velocity.config.PluginConfig;
import dev.linkedhardcore.velocity.model.ServerState;
import dev.linkedhardcore.velocity.model.ServerStatus;
import dev.linkedhardcore.velocity.routing.TransferHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StatusPollerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void freshReadyStatusMakesAnUnavailableBackendRoutable() throws Exception {
        Path statusFile = temporaryDirectory.resolve("status.json");
        Files.writeString(statusFile, "{\"state\":\"ready\",\"playerCount\":0}");
        Map<String, ServerStatus> statuses = statuses(ServerState.UNAVAILABLE);
        TransferHandler transferHandler = mock(TransferHandler.class);

        poller(statusFile, statuses, transferHandler, 5).pollOnce();

        verify(transferHandler).onServerReady("alpha");
    }

    @Test
    void staleStatusMakesAFormerlyReadyBackendUnavailable() throws Exception {
        Path statusFile = temporaryDirectory.resolve("status.json");
        Files.writeString(statusFile, "{\"state\":\"ready\",\"playerCount\":0}");
        Files.setLastModifiedTime(statusFile, FileTime.fromMillis(System.currentTimeMillis() - 5_000));
        Map<String, ServerStatus> statuses = statuses(ServerState.READY);

        poller(statusFile, statuses, mock(TransferHandler.class), 1).pollOnce();

        assertEquals(ServerState.UNAVAILABLE, statuses.get("alpha").state());
    }

    private StatusPoller poller(Path statusFile, Map<String, ServerStatus> statuses,
                                TransferHandler transferHandler, int staleSeconds) {
        Map<String, PluginConfig.BackendServer> backends = new LinkedHashMap<>();
        backends.put("alpha", new PluginConfig.BackendServer("alpha", "a", statusFile.toString()));
        return new StatusPoller(this, mock(ProxyServer.class), new PluginConfig(backends, 1, staleSeconds), statuses,
            transferHandler, LoggerFactory.getLogger("test"));
    }

    private static Map<String, ServerStatus> statuses(ServerState state) {
        Map<String, ServerStatus> statuses = new LinkedHashMap<>();
        statuses.put("alpha", new ServerStatus("alpha", state));
        return statuses;
    }
}

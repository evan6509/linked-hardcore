package dev.linkedhardcore.velocity.routing;

import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.linkedhardcore.velocity.LinkedHardcorePlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class StartupRoutingTest {
    @TempDir Path directory;
    @Test
    void failedInitializationDoesNotFallBackIntoAnOldBackendWorld() {
        var plugin = new LinkedHardcorePlugin(mock(ProxyServer.class), LoggerFactory.getLogger("test"), directory);
        var event = new ServerPreConnectEvent(mock(Player.class), mock(RegisteredServer.class));
        plugin.onServerPreConnect(event);
        assertFalse(event.getResult().isAllowed());
    }
}

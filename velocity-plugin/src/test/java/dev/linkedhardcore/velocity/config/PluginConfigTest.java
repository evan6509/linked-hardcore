package dev.linkedhardcore.velocity.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginConfigTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void retainsConfiguredBackendOrderAndLoadsStaleTimeout() throws IOException {
        Path configFile = temporaryDirectory.resolve("config.json");
        Files.writeString(configFile, """
            {
              "backendServers": {
                "zeta": { "serverId": "z", "statusFile": "/tmp/z-status.json" },
                "alpha": { "serverId": "a", "statusFile": "/tmp/a-status.json" }
              },
              "statusPollSeconds": 2,
              "statusStaleSeconds": 9
            }
            """);

        PluginConfig config = PluginConfig.load(configFile, LoggerFactory.getLogger("test"));

        assertEquals(java.util.List.of("zeta", "alpha"), new ArrayList<>(config.backendServers().keySet()));
        assertEquals(2, config.statusPollSeconds());
        assertEquals(9, config.statusStaleSeconds());
        assertEquals("alpha", config.velocityNameForServerId("a").orElseThrow());
    }

    @Test
    void rejectsDuplicateLogicalServerIds() throws IOException {
        Path configFile = temporaryDirectory.resolve("config.json");
        Files.writeString(configFile, """
            {
              "backendServers": {
                "one": { "serverId": "same", "statusFile": "/tmp/one.json" },
                "two": { "serverId": "same", "statusFile": "/tmp/two.json" }
              }
            }
            """);

        assertThrows(IOException.class, () -> PluginConfig.load(configFile, LoggerFactory.getLogger("test")));
    }
}

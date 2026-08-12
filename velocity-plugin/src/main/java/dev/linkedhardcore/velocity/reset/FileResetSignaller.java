package dev.linkedhardcore.velocity.reset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.linkedhardcore.velocity.config.PluginConfig;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link ResetSignaller} that writes a {@code reset.request.json} file next to
 * the backend's {@code status.json} (i.e. into
 * {@code <server>/config/linkedhardcore/}).
 *
 * <p>The external reset agent (Sisyphus) is contracted to watch for this file
 * (see {@code docs/RESET_CONTRACT.md}). The file format:
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "serverId": "a",
 *   "requestedAt": "2026-08-12T10:00:00Z"
 * }
 * }</pre>
 *
 * <p>Alternative (not implemented): a local webhook POST for cross-host setups.
 */
public final class FileResetSignaller implements ResetSignaller {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, Path> serverDirectories;
    private final Logger logger;

    public FileResetSignaller(PluginConfig config, Logger logger) {
        // Derive each server's config/linkedhardcore dir from its statusFile path.
        this.serverDirectories = config.backendServers().values().stream()
            .collect(Collectors.toMap(
                PluginConfig.BackendServer::serverId,
                b -> Path.of(b.statusFile()).getParent()));
        this.logger = logger;
    }

    @Override
    public void signalReset(String serverId) throws IOException {
        Path dir = serverDirectories.get(serverId);
        if (dir == null) {
            throw new IOException("No configured backend for serverId '" + serverId + "'");
        }
        Files.createDirectories(dir);
        Path target = dir.resolve("reset.request.json");
        ResetRequest body = new ResetRequest(1, serverId, Instant.now().toString());
        Files.writeString(target, GSON.toJson(body), StandardCharsets.UTF_8);
        logger.info("[linkedhardcore] Wrote reset request for '{}' -> {}", serverId, target);
    }

    private record ResetRequest(int schemaVersion, String serverId, String requestedAt) {
    }
}

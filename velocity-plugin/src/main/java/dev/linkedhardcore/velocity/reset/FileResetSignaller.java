package dev.linkedhardcore.velocity.reset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.linkedhardcore.velocity.config.PluginConfig;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link ResetSignaller} that writes a {@code reset.request.json} file next to
 * the backend's {@code status.json} (i.e. into
 * {@code <server>/config/linkedhardcore/}).
 *
 * <p>The schema-2 request includes a unique request ID and the previous
 * boot/world identities. The expectation is persisted before request publication.
 * See {@code docs/RESET_CONTRACT.md} for the executor and completion sequence.
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
    public synchronized void signalReset(ResetRequest request) throws IOException {
        String serverId = request.serverId();
        if (!request.validFor(serverId)) throw new IOException("Invalid reset request for '" + serverId + "'");
        Path dir = serverDirectories.get(serverId);
        if (dir == null) {
            throw new IOException("No configured backend for serverId '" + serverId + "'");
        }
        Files.createDirectories(dir);
        Path target = dir.resolve("reset.request.json");
        // Persist the expectation first. A crash or a poll between these writes
        // cannot admit the old ready heartbeat, even after a proxy restart.
        writeAtomic(dir.resolve("reset.pending.json"), GSON.toJson(request));
        writeAtomic(target, GSON.toJson(request));
        logger.info("[linkedhardcore] Wrote reset request for '{}' -> {}", serverId, target);
    }

    public static void writeAtomic(Path target, String json) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

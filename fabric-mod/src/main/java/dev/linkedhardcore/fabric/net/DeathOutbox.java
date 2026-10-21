package dev.linkedhardcore.fabric.net;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Atomic, process-crash-safe death intent journal, outside resettable worlds.
 * No fsync/power-loss guarantee. All callers run on the backend server thread.
 * Disk is authoritative, including when another messenger instance recovers it.
 */
public final class DeathOutbox {
    private static final Gson GSON = new Gson();
    private static final int MAX_JOURNAL_BYTES = 1_048_576;
    private final Path file;
    private final String serverId;

    public DeathOutbox(Path file, String serverId) {
        this.file = file;
        this.serverId = serverId;
        pending(); // Refuse malformed/foreign state before accepting any deaths.
    }

    public record Death(UUID playerUuid, String generationId) {
        private boolean valid() {
            return playerUuid != null && generationId != null && !generationId.isBlank()
                && generationId.length() <= 32_767;
        }
    }

    public synchronized List<Death> pending() {
        if (Files.notExists(file)) return List.of();
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_JOURNAL_BYTES) throw new IOException("Invalid outbox file");
            Saved saved = GSON.fromJson(Files.readString(file), Saved.class);
            if (saved == null || saved.schemaVersion != 1 || saved.protocolVersion != Protocol.PROTOCOL_VERSION
                || !serverId.equals(saved.serverId) || saved.deaths == null
                || saved.deaths.stream().anyMatch(death -> death == null || !death.valid())
                || saved.deaths.stream().distinct().count() != saved.deaths.size()) {
                throw new IOException("Invalid outbox identity/schema; preserve it for operator recovery");
            }
            return List.copyOf(saved.deaths);
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Could not load death outbox " + file, error);
        }
    }

    /** Commit BEFORE a companion hook may save a completion latch or send a frame. */
    public synchronized Death prepare(UUID player, String generation) {
        Death event = new Death(player, generation);
        if (!event.valid()) throw new IllegalArgumentException("Death requires player and world identity");
        List<Death> deaths = new ArrayList<>(pending());
        if (!deaths.contains(event)) {
            deaths.add(event);
            save(deaths);
        }
        return event;
    }

    /** Publish removal before forgetting the event; an interrupted removal only causes replay. */
    public synchronized boolean acknowledge(UUID player, String generation) {
        List<Death> deaths = new ArrayList<>(pending());
        if (!deaths.remove(new Death(player, generation))) return false;
        save(deaths);
        return true;
    }

    private void save(List<Death> deaths) {
        Path temporary = null;
        try {
            byte[] data = GSON.toJson(new Saved(1, Protocol.PROTOCOL_VERSION, serverId, deaths))
                .getBytes(StandardCharsets.UTF_8);
            if (data.length > MAX_JOURNAL_BYTES) throw new IOException("Outbox capacity exceeded; preserving committed deaths");
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, "death-outbox-", ".tmp");
            Files.write(temporary, data);
            // Fail closed on filesystems without atomic replacement. A torn journal
            // must never be mistaken for an empty queue after a process restart.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            throw new IllegalStateException("Could not commit death outbox " + file, error);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* Unpublished temp is never read. */ }
            }
        }
    }

    private record Saved(int schemaVersion, int protocolVersion, String serverId, List<Death> deaths) { }
}

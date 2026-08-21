package dev.linkedhardcore.velocity.death;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Persists the proxy death totals outside backend world directories. */
public final class DeathCounterStore {
    private static final Type RECORD_LIST = new TypeToken<List<DeathCounterState.DeathRecord>>() {}.getType();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;

    public DeathCounterStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public void loadInto(DeathCounterState state) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<DeathCounterState.DeathRecord> records = GSON.fromJson(Files.readString(file), RECORD_LIST);
            if (records != null) {
                records.forEach(state::restore);
            }
            logger.info("[linkedhardcore] Loaded {} death-counter record(s) from {}", records == null ? 0 : records.size(), file);
        } catch (Exception e) {
            logger.warn("[linkedhardcore] Could not load death counters from {}: {}", file, e.toString());
        }
    }

    public synchronized void save(DeathCounterState state) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(state.snapshot(), RECORD_LIST), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.warn("[linkedhardcore] Could not save death counters to {}: {}", file, e.toString());
        }
    }
}

package dev.linkedhardcore.velocity.timer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Keeps the timer outside resettable backend worlds. */
public final class RunTimerStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Logger logger;

    public RunTimerStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public void loadInto(RunTimerState state) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            state.restore(GSON.fromJson(Files.readString(file), RunTimerState.Snapshot.class));
        } catch (Exception e) {
            logger.warn("[linkedhardcore] Could not load run timer from {}: {}", file, e.toString());
        }
    }

    public synchronized void save(RunTimerState.Snapshot snapshot) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(snapshot), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.warn("[linkedhardcore] Could not save run timer to {}: {}", file, e.toString());
        }
    }
}

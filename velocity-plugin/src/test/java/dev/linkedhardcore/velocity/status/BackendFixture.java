package dev.linkedhardcore.velocity.status;

import com.google.gson.Gson;
import dev.linkedhardcore.velocity.reset.FileResetSignaller;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BackendFixture {
    private BackendFixture() { }
    public static void publish(Path directory, String id, String generation, String boot, boolean used,
                               int players, String completed) throws Exception {
        Map<String, Object> world = new LinkedHashMap<>();
        world.put("schemaVersion", 2);
        world.put("serverId", id);
        world.put("generationId", generation);
        world.put("used", used);
        if (completed != null) world.put("completedResetId", completed);
        Gson gson = new Gson();
        FileResetSignaller.writeAtomic(directory.resolve("world-generation.json"), gson.toJson(world));
        Map<String, Object> status = new LinkedHashMap<>(world);
        status.remove("used");
        status.put("worldUsed", used);
        status.put("state", used ? "live" : "ready");
        status.put("playerCount", players);
        status.put("bootId", boot);
        status.put("updatedAt", Instant.now().toString());
        FileResetSignaller.writeAtomic(directory.resolve("status.json"), gson.toJson(status));
    }
}

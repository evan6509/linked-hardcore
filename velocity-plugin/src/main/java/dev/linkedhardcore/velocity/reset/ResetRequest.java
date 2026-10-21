package dev.linkedhardcore.velocity.reset;

import java.time.Instant;
import java.util.UUID;

/** The same request is retained in reset.pending.json until its new world is acknowledged. */
public record ResetRequest(int schemaVersion, String serverId, String requestId, String requestedAt,
                           String previousGenerationId, String previousBootId) {
    public static ResetRequest create(String serverId, String generationId, String bootId) {
        return new ResetRequest(2, serverId, UUID.randomUUID().toString(), Instant.now().toString(), generationId, bootId);
    }

    public ResetRequest withPreviousBootId(String bootId) {
        return new ResetRequest(schemaVersion, serverId, requestId, requestedAt, previousGenerationId, bootId);
    }

    public boolean validFor(String id) {
        return schemaVersion == 2 && id.equals(serverId) && requestId != null && !requestId.isBlank()
            && previousGenerationId != null && !previousGenerationId.isBlank()
            && previousBootId != null && !previousBootId.isBlank() && !requestId.equals(previousGenerationId);
    }
}

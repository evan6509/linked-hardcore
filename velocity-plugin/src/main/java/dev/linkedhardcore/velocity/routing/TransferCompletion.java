package dev.linkedhardcore.velocity.routing;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Combines per-player transfer results before the source server is reset. */
public final class TransferCompletion {
    private TransferCompletion() {}

    public static CompletableFuture<Boolean> allSuccessful(List<CompletableFuture<Boolean>> transfers) {
        if (transfers.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }
        CompletableFuture<?> all = CompletableFuture.allOf(transfers.toArray(CompletableFuture[]::new));
        return all.handle((ignored, error) -> {
            if (error != null) {
                return false;
            }
            return transfers.stream().allMatch(future -> Boolean.TRUE.equals(future.join()));
        });
    }
}

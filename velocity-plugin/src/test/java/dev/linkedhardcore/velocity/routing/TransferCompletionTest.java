package dev.linkedhardcore.velocity.routing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferCompletionTest {

    @Test
    void doesNotCompleteUntilEveryPlayerTransferSucceeds() {
        CompletableFuture<Boolean> first = new CompletableFuture<>();
        CompletableFuture<Boolean> second = new CompletableFuture<>();
        CompletableFuture<Boolean> batch = TransferCompletion.allSuccessful(List.of(first, second));

        first.complete(true);
        assertFalse(batch.isDone());

        second.complete(true);
        assertTrue(batch.join());
    }

    @Test
    void reportsFailureWhenAnyPlayerTransferFails() {
        CompletableFuture<Boolean> first = CompletableFuture.completedFuture(true);
        CompletableFuture<Boolean> second = CompletableFuture.completedFuture(false);

        assertFalse(TransferCompletion.allSuccessful(List.of(first, second)).join());
    }
}

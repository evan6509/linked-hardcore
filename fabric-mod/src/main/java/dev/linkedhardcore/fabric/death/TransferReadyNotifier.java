package dev.linkedhardcore.fabric.death;

import net.minecraft.server.MinecraftServer;

/**
 * Implemented by whoever can deliver a {@code TRANSFER_READY} frame to the proxy.
 * Breaks the otherwise-circular dependency between {@link TransferCountdown} and
 * the networking messenger.
 */
public interface TransferReadyNotifier {

    /** Tells the proxy the countdown finished; it may transfer everyone now. */
    void notifyTransferReady(MinecraftServer server);
}

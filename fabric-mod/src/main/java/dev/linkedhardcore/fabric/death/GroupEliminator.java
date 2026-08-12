package dev.linkedhardcore.fabric.death;

import dev.linkedhardcore.fabric.config.ModConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;

/**
 * Handles a {@code GROUP_ELIMINATED} instruction from the proxy: every member of
 * the specified group currently online on this server is dealt with, respecting
 * the configured {@link ModConfig.EliminationMode}.
 *
 * <p>The player who actually died has already gone through vanilla death handling
 * by this point; the other members are the ones we handle here.
 */
public final class GroupEliminator {

    private static final Logger LOGGER = LoggerFactory.getLogger("linkedhardcore");

    private final ModConfig config;

    public GroupEliminator(ModConfig config) {
        this.config = config;
    }

    /**
     * Eliminates all online members of {@code groupId} on this server.
     * Runs on the server thread.
     */
    public void eliminate(MinecraftServer server, String groupId) {
        ModConfig.Group group = config.groupById(groupId).orElse(null);
        if (group == null) {
            LOGGER.warn("[linkedhardcore] GROUP_ELIMINATED for unknown group '{}' (not in local config)", groupId);
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (group.members().contains(player.getUUID())) {
                LOGGER.info("[linkedhardcore] Eliminating group member {} (group {})", player.getName().getString(), groupId);
                apply(player);
            }
        }    }

    private void apply(ServerPlayer player) {
        switch (config.eliminationMode()) {
            case SPECTATOR -> {
                player.setGameMode(GameType.SPECTATOR);
                player.sendSystemMessage(Component.literal("Your group was eliminated. You are now spectating."));
            }
            case BAN -> ban(player);
        }
    }

    private void ban(ServerPlayer player) {
        NameAndId nameAndId = new NameAndId(player.getGameProfile());
        UserBanListEntry entry = new UserBanListEntry(nameAndId, new Date(),
            "Linked Hardcore", null, "Group eliminated.");
        ((ServerLevel) player.level()).getServer().getPlayerList().getBans().add(entry);
        player.connection.disconnect(Component.literal("Your group was eliminated."));
        LOGGER.info("[linkedhardcore] Banned {} for group elimination", player.getName().getString());
    }
}

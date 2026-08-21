package dev.linkedhardcore.fabric.death;

import dev.linkedhardcore.fabric.net.Protocol;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Renders the proxy-authoritative death totals in the in-game sidebar. */
public final class DeathCounterDisplay {
    private static final String OBJECTIVE_NAME = "lh_deaths";

    private DeathCounterDisplay() {
    }

    public static void apply(MinecraftServer server, List<Protocol.DeathCounter> counters) {
        Scoreboard scoreboard = server.getScoreboard();
        Objective objective = scoreboard.getObjective(OBJECTIVE_NAME);
        if (objective == null) {
            objective = scoreboard.addObjective(
                OBJECTIVE_NAME,
                ObjectiveCriteria.DUMMY,
                Component.literal("Deaths"),
                ObjectiveCriteria.RenderType.INTEGER,
                false,
                null);
        }
        if (scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) != objective) {
            scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, objective);
        }

        Set<String> currentNames = new HashSet<>();
        for (Protocol.DeathCounter counter : counters) {
            currentNames.add(counter.playerName());
            scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly(counter.playerName()), objective)
                .set(Math.max(0, counter.deaths()));
        }
        List<String> staleNames = new ArrayList<>();
        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
            if (!currentNames.contains(entry.owner())) {
                staleNames.add(entry.owner());
            }
        }
        for (String staleName : staleNames) {
            scoreboard.resetSinglePlayerScore(ScoreHolder.forNameOnly(staleName), objective);
        }
    }
}

package dev.linkedhardcore.fabric.death;

import dev.linkedhardcore.fabric.net.Protocol;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
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
import java.util.Locale;
import java.util.Set;

/** Renders the proxy-authoritative death totals and current run in the sidebar. */
public final class DeathCounterDisplay {
    private static final String OBJECTIVE_NAME = "lh_deaths";
    // A colon cannot occur in a Minecraft username, so this row cannot collide
    // with a player's death total. Avoid '#' because clients hide those holders.
    private static final String RUN_TIMER_ROW = "lh:run_timer";

    private DeathCounterDisplay() {
    }

    public static void apply(MinecraftServer server, List<Protocol.DeathCounter> counters) {
        Scoreboard scoreboard = server.getScoreboard();
        Objective objective = objective(scoreboard);

        Set<String> currentNames = new HashSet<>();
        currentNames.add(RUN_TIMER_ROW);
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

    public static void applyRunTimer(MinecraftServer server, Protocol.RunTimer timer) {
        Scoreboard scoreboard = server.getScoreboard();
        Objective objective = objective(scoreboard);
        var score = scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly(RUN_TIMER_ROW), objective);
        score.set(Integer.MAX_VALUE);
        score.numberFormatOverride(BlankFormat.INSTANCE);
        long seconds = timer.elapsedMillis() / 1000L;
        String duration = String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        score.display(Component.literal((timer.ended() ? "§cRun ended: " : "§eRun: ") + duration));
    }

    private static Objective objective(Scoreboard scoreboard) {
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

        return objective;
    }
}

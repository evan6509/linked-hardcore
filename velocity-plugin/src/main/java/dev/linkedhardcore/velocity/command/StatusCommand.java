package dev.linkedhardcore.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.linkedhardcore.velocity.config.PluginConfig;
import dev.linkedhardcore.velocity.death.DeathCounterState;
import dev.linkedhardcore.velocity.model.ServerStatus;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Map;

public final class StatusCommand implements SimpleCommand {
    private final ProxyServer proxy;
    private final PluginConfig config;
    private final Map<String, ServerStatus> servers;
    private final DeathCounterState deathCounters;

    public StatusCommand(ProxyServer proxy, PluginConfig config, Map<String, ServerStatus> servers,
                         DeathCounterState deathCounters) {
        this.proxy = proxy;
        this.config = config;
        this.servers = servers;
        this.deathCounters = deathCounters;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        source.sendMessage(Component.text("Linked Hardcore status").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        source.sendMessage(Component.text("--- Backend servers ---").color(NamedTextColor.AQUA));
        config.backendServers().forEach((name, backend) -> {
            ServerStatus status = servers.get(name);
            String state = status == null ? "?" : status.state().wireName();
            int online = proxy.getServer(name).map(s -> s.getPlayersConnected().size()).orElse(-1);
            source.sendMessage(Component.text("  " + name + " (id=" + backend.serverId() + "): ")
                .color(NamedTextColor.GRAY)
                .append(Component.text(state).color(stateColor(state)))
                .append(Component.text(" | " + online + " online").color(NamedTextColor.DARK_GRAY)));
        });

        source.sendMessage(Component.text("--- Deaths ---").color(NamedTextColor.AQUA));
        deathCounters.snapshot().stream()
            .sorted(java.util.Comparator.comparing(DeathCounterState.DeathRecord::playerName))
            .forEach(record -> source.sendMessage(Component.text("  " + record.playerName() + ": " + record.deaths())
                .color(NamedTextColor.GRAY)));
        source.sendMessage(Component.text("  " + proxy.getPlayerCount() + " player(s) on proxy").color(NamedTextColor.GRAY));
    }

    private static NamedTextColor stateColor(String state) {
        return switch (state) {
            case "live" -> NamedTextColor.GREEN;
            case "ready" -> NamedTextColor.DARK_GREEN;
            case "resetting" -> NamedTextColor.RED;
            default -> NamedTextColor.WHITE;
        };
    }
}

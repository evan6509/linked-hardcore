package dev.linkedhardcore.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.linkedhardcore.velocity.config.PluginConfig;
import dev.linkedhardcore.velocity.model.ServerStatus;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Map;

/**
 * {@code /linkedhardcore status} — quick debugging view of the proxy's view of
 * the world: per-server state and who's online.
 *
 * <p>All players share one linked life pool, so there are no groups to list.
 */
public final class StatusCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final PluginConfig config;
    private final Map<String, ServerStatus> servers;

    public StatusCommand(ProxyServer proxy, PluginConfig config, Map<String, ServerStatus> servers) {
        this.proxy = proxy;
        this.config = config;
        this.servers = servers;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        source.sendMessage(Component.text("Linked Hardcore status").color(NamedTextColor.GOLD)
            .decorate(TextDecoration.BOLD));

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

        source.sendMessage(Component.text("--- Linked pool ---").color(NamedTextColor.AQUA));
        source.sendMessage(Component.text("  " + proxy.getPlayerCount() + " player(s) on proxy")
            .color(NamedTextColor.GRAY));
    }

    private static NamedTextColor stateColor(String state) {
        return switch (state) {
            case "live" -> NamedTextColor.GREEN;
            case "transferring" -> NamedTextColor.YELLOW;
            case "ready" -> NamedTextColor.DARK_GREEN;
            case "resetting" -> NamedTextColor.RED;
            case "unavailable" -> NamedTextColor.DARK_GRAY;
            default -> NamedTextColor.WHITE;
        };
    }
}

package dev.linkedhardcore.velocity.net;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import dev.linkedhardcore.velocity.routing.TransferHandler;
import org.slf4j.Logger;

import java.util.Optional;
/**
 * Listens for plugin messages from the backend Fabric mods on
 * {@code linkedhardcore:main}.
 *
 * <p>Both directions are gatekept by the proxy's {@code ChannelRegistrar}: only
 * messages whose channel is registered here fire this event (see
 * {@link dev.linkedhardcore.velocity.LinkedHardcorePlugin}).
 */
public final class ProxyMessageListener {

    private final TransferHandler transferHandler;
    private final Logger logger;

    public ProxyMessageListener(TransferHandler transferHandler, Logger logger) {
        this.transferHandler = transferHandler;
        this.logger = logger;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!Protocol.CHANNEL.equals(event.getIdentifier())) {
            return;
        }

        // Always consume the message: it must not leak onward (either to the client
        // for backend->proxy messages, or to a backend for anything else).
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        // Only backend servers are valid sources for our protocol. This also stops a
        // malicious client from impersonating the proxy to the backend servers.
        if (!(event.getSource() instanceof ServerConnection source)) {
            logger.warn("[linkedhardcore] Ignoring message on {} from non-backend source: {}",
                Protocol.CHANNEL_NAME, event.getSource().getClass().getSimpleName());
            return;
        }

        byte[] data = event.getData();
        Optional<Protocol.Inbound> decoded = Protocol.decodeInbound(data);
        if (decoded.isEmpty()) {
            logger.warn("[linkedhardcore] Dropped malformed payload on {} from server '{}' ({} bytes)",
                Protocol.CHANNEL_NAME, source.getServerInfo().getName(), data == null ? 0 : data.length);
            return;
        }

        Protocol.Inbound msg = decoded.get();
        if (msg.isPlayerDied()) {
            transferHandler.onPlayerDied(source, msg.playerUuid(), msg.groupIdOrServerId());
        } else if (msg.isTransferReady()) {
            transferHandler.onTransferReady(source, msg.groupIdOrServerId());
        } else if (msg.isResetComplete()) {
            transferHandler.onResetComplete(msg.groupIdOrServerId());
        } else {
            logger.warn("[linkedhardcore] Unhandled opcode 0x{} from server '{}'",
                Integer.toHexString(msg.opcode()), source.getServerInfo().getName());
        }
    }
}

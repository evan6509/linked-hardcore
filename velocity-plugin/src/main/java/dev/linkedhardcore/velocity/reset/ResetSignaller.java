package dev.linkedhardcore.velocity.reset;

import java.io.IOException;

/**
 * Signals an external reset agent ("Sisyphus") that a backend server should be
 * wiped and regenerated.
 *
 * <p>The proxy NEVER deletes world/playerdata files itself — that is done by the
 * external agent. This interface is the proxy's outbound notification hook.
 *
 * <p>Two implementations are possible and both are valid:
 * <ul>
 *   <li>{@link FileResetSignaller} — writes {@code reset.request.json} into the
 *       server's {@code config/linkedhardcore/} directory. Simplest to scaffold;
 *       requires the proxy host to have filesystem access to both server dirs.</li>
 *   <li>A local HTTP/webhook POST — for when the proxy and servers are on
 *       different hosts. Left as a future implementation; the contract is the
 *       same ({@link #signalReset(String)}).</li>
 * </ul>
 */
public interface ResetSignaller {

    /**
     * Signals that {@code serverId} has been vacated and should be reset.
     *
     * @param serverId the server id (e.g. {@code a} or {@code b})
     * @throws IOException if the signal could not be written/delivered
     */
    void signalReset(String serverId) throws IOException;
}

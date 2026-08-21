package dev.linkedhardcore.velocity.model;

/**
 * Lifecycle state of a single backend server, tracked by the proxy.
 *
 * <p>Transitions are strictly ordered (see {@link ServerStatus}):
 * <pre>
 *   UNAVAILABLE --fresh status--> READY
 *   READY --player joins--> LIVE
 *   LIVE  --transfer begins--> TRANSFERRING
 *   TRANSFERRING --all players moved--> RESETTING
 *   RESETTING --reset complete--> READY
 * </pre>
 *
 * <p>This mirrors the states the Fabric mod reports in its {@code status.json}
 * file ({@code ready|live|resetting}); the proxy keeps its own authoritative
 * copy so routing decisions don't depend on reading a file per event.
 */
public enum ServerState {
    /**
     * The proxy has not recently observed a healthy status file for this backend.
     * An unavailable backend must never be selected as a transfer destination.
     */
    UNAVAILABLE,

    /** Idle and empty; a freshly reset (or never-used) server. Ready to receive a transfer. */
    READY,

    /** Currently hosting one or more active pairs. */
    LIVE,

    /** A linked-life transfer is in progress; lifecycle events must not make this server READY. */
    TRANSFERRING,

    /** Empty again; the external reset agent (Sisyphus) has been signalled to wipe and regenerate. */
    RESETTING;

    /** Human-readable state string used in status files, configs, and the status command. */
    public String wireName() {
        return name().toLowerCase();
    }
}

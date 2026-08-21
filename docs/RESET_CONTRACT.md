# Reset Contract (status file schema)

The world reset is performed **outside** Minecraft by an external automation agent
("Sisyphus"). This document defines the exact filesystem contract that agent (and
the Velocity plugin) rely on, so it can be implemented against without touching
this repo's code.

## Files

All files live in each backend server's **config directory**:
`config/linkedhardcore/` (i.e. `<server-dir>/config/linkedhardcore/`).

| File                  | Written by          | Read by              | Purpose |
|-----------------------|---------------------|----------------------|---------|
| `status.json`         | Fabric mod          | Sisyphus, proxy      | The server's authoritative state + player count |
| `reset.request.json`  | Velocity plugin     | Sisyphus             | Signals "this server must be wiped and regenerated" |

## `status.json`

Written atomically by the Fabric mod (see `StatusFileWriter`) on server lifecycle
events and refreshed every second as a liveness heartbeat. Its player count is
always the actual online count, including while a reset request is pending.

```json
{
  "schemaVersion": 1,
  "serverId": "a",
  "state": "ready",
  "playerCount": 0,
  "updatedAt": "2026-08-12T10:00:00Z"
}
```

### Fields

| Field          | Type   | Values            | Meaning |
|----------------|--------|-------------------|---------|
| `schemaVersion`| int    | `1`               | Schema version; bump only on breaking format changes |
| `serverId`     | string | e.g. `"a"`, `"b"` | Logical id of this backend (matches the proxy config) |
| `state`        | string | `"ready"` `"live"` `"resetting"` | Lifecycle state |
| `playerCount`  | int    | ≥ 0               | Online players **on this server** |
| `updatedAt`    | string | ISO-8601 UTC      | Last write time |

### State meanings

| State       | Meaning |
|-------------|---------|
| `ready`     | Server is up, empty (or empty-able), and safe to receive transfers |
| `live`      | Server is currently hosting active players |
| `resetting` | Server has been vacated and is scheduled for a wipe |

## `reset.request.json`

Written by the Velocity plugin when it flags a server for reset (see
`FileResetSignaller`).

```json
{
  "schemaVersion": 1,
  "serverId": "a",
  "requestedAt": "2026-08-12T10:00:00Z"
}
```

## Sisyphus contract

1. **Poll** `status.json` for `state: "resetting"` AND `playerCount: 0` before
   acting on a `reset.request.json`. The request alone is not proof that all
   connections have completed.
2. **Stop** the server process.
3. **Delete** the world and player data:
   - `world/`
   - `world_nether/`
   - `world_the_end/`
   - `playerdata/`
   - `stats/`
   - `advancements/`
   - Optionally also delete `reset.request.json` (so a stale request doesn't
     retrigger after the next wipe).
4. **Restart** the server process.
5. **Wait** for the Fabric mod to write `status.json` with `state: "ready"`
   (the mod writes this on `SERVER_STARTED`). This is the signal that the wipe
   is complete and the server is safe to receive transfers again.
6. **The proxy notices**: the proxy polls each backend's `status.json` (default
   every 1s, configurable via `statusPollSeconds`). When a `RESETTING` server
   reports `state: "ready"` + `playerCount: 0`, the proxy flips it back to
   `READY` and resumes any pending transfer. `RESET_COMPLETE` (best-effort,
   requires a connected player) is an additional, faster path with the same
   effect.

### Explicitly NOT Sisyphus's job

- None of the above is done by the Minecraft/Java process. The mod and plugin
  only write files; they never delete worlds or manage the process.
- Sisyphus does not talk to the proxy or the mod over the messaging channel.

## Local-development note

For a single-host scaffold the proxy reads/writes these files directly from the
server directories configured in its `config.json` (`statusFile` paths). In a
deploy where the proxy cannot reach the server filesystems, replace
`FileResetSignaller` with a webhook implementation of the same `ResetSignaller`
interface, and have Sisyphus (or an HTTP bridge) perform step 6.

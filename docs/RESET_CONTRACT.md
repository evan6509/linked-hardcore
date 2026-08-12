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

Written by the Fabric mod (see `StatusFileWriter`). Written on: server start
(`ready`), player join (`live`), player leave (`live` or `ready` when last player
leaves), and when a `reset.request.json` is detected (`resetting`).

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

1. **Poll** `status.json` (or watch for `reset.request.json`) for
   `state: "resetting"` AND `playerCount: 0` (or the presence of a fresh
   `reset.request.json`).
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
6. **Notify the proxy**: the proxy is responsible for flipping the server back to
   `READY` in its own state machine. It does so via `RESET_COMPLETE` (best-effort,
   requires a connected player) **and/or** by polling `status.json` for
   `state: "ready"` — implement at least the file-poll as the reliable path.

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

# Reset contract (schema 2)

The external executor stops and wipes the backend. Java reports world use and
readiness; it never deletes worlds. An empty process is not proof of a new world.
All backend files below live in `<server>/config/linkedhardcore/`, outside the
resettable world directories.

| File | Owner | Purpose |
|------|-------|---------|
| `death-outbox.json` | Fabric | Durable death intents/ACK removals; preserve across boot and reset |
| `world-generation.json` | Executor creates; Fabric records use | Persistent world identity and first-use latch |
| `status.json` | Fabric | Fresh heartbeat, real player count, boot and world identities |
| `reset.pending.json` | Proxy | Durable expected reset, retained until verified completion |
| `reset.request.json` | Proxy publishes; executor consumes | Request to stop, wipe and prepare a specific generation |

The proxy also persists `plugins/linkedhardcore/current-world.json`, containing
`serverName` and `generationId`, plus the committed run ID and any pending handoff.
Reconnects resume the current run after logout; a proxy restart recovers an ended
run's handoff instead of reopening its source. See [lifecycle recovery](LIFECYCLE_RECOVERY.md). If that world is temporarily unavailable, reconnects wait for it
instead of silently entering a different run.

## World identity and use

```json
{
  "schemaVersion": 2,
  "serverId": "a",
  "generationId": "912df9d6-246a-407f-ac1d-23e9f058a319",
  "completedResetId": "912df9d6-246a-407f-ac1d-23e9f058a319",
  "used": false
}
```

For a reset, `generationId` and `completedResetId` both equal the request ID.
Only the executor creates an unused generation, after completing the wipe with
the JVM stopped. On the first join, Fabric atomically sets `used: true` before
respawning the player. This latch survives logout and ordinary restarts. Java
never clears it. World identity files must not be copied from a different world.

On a genuinely first container boot, with none of the world/player-data directories
present, the entrypoint creates a unique unused generation without wiping anything;
`completedResetId` is absent. Existing worlds missing metadata are preserved and
classified as used. A non-container executor must establish initial preparation
under the same conditions; Fabric alone conservatively treats missing identity as
an existing used world. Invalid metadata cannot advertise readiness.

## Heartbeat

```json
{
  "schemaVersion": 2,
  "serverId": "a",
  "state": "ready",
  "playerCount": 0,
  "updatedAt": "2026-10-13T05:00:00Z",
  "bootId": "da2c7322-644f-44fc-9384-3d46019852a2",
  "generationId": "912df9d6-246a-407f-ac1d-23e9f058a319",
  "completedResetId": "912df9d6-246a-407f-ac1d-23e9f058a319",
  "worldUsed": false
}
```

Fabric chooses a new `bootId` for each JVM. Status capture and publication run on
the server thread and are serialized; atomic replacement uses unique temporary
files. The heartbeat refreshes every second. Both `updatedAt` and file modification
time must be fresh (default maximum age five seconds), and the identity and use
flag must agree with `world-generation.json`.

| State | Meaning |
|-------|---------|
| `ready` | An unused prepared world, with zero players |
| `live` | A used current/resumable world; it may have zero players |
| `resetting` | A request exists; `playerCount` remains the actual online count |

**Set `pause-when-empty-seconds=0` in every backend's `server.properties`.** The
container entrypoint enforces this setting on every boot. Manual installations
must set it too: tick-based heartbeats require empty servers to keep ticking.

## Reset request and durable expectation

`reset.pending.json` and `reset.request.json` contain the same request:

```json
{
  "schemaVersion": 2,
  "serverId": "a",
  "requestId": "912df9d6-246a-407f-ac1d-23e9f058a319",
  "requestedAt": "2026-10-13T04:59:00Z",
  "previousGenerationId": "fc11920a-40c1-439f-a78e-686f9bb51c2f",
  "previousBootId": "7015912b-07d5-4e88-90fa-598e312244f3"
}
```

The proxy installs its in-memory reset latch under the transfer lock, atomically
writes the durable expectation, then atomically publishes the request. An old
heartbeat cannot cancel the latch during this gap, while the backend is unavailable,
or after a proxy restart. Failed request publication is retried with the same ID.
A conflicting or malformed durable expectation prevents readiness.

If a backend restarts between identity capture and request publication, the proxy
may rebind only `previousBootId` to its replacement boot. This requires a fresh,
empty `resetting` heartbeat for the same previous generation, an empty proxy-side
backend, and pending/request files agreeing on every field except the boot ID.
The request ID, previous generation, and request time stay unchanged. The pending
expectation is written first; interrupted request publication is retried with that
same reset identity. Changed worlds, occupied or stale backends, and conflicting
requests never qualify. The executor still requires an exact boot match.

## Executor sequence

1. Require a schema-2 request for this backend. Before stopping a running JVM,
   require its fresh `resetting` heartbeat, `playerCount: 0`, and the request's
   previous generation and boot identities. Never wipe a running occupied world.
2. Stop the JVM and wait for it to exit.
3. Delete `world/`, `world_nether/`, `world_the_end/`, `playerdata/`, `stats/`, and
   `advancements/`. Preserve `config/linkedhardcore/`, including `death-outbox.json`,
   and the proxy's persisted state. Retired death reports remain quarantined by generation.
4. Atomically write `world-generation.json` with `used: false` and both identity
   fields equal to the request ID. Remove the previous `status.json`, then remove
   `reset.request.json`. **Retain `reset.pending.json`.**
5. Start the JVM. Fabric loads the identity and publishes its new boot's heartbeat
   only after `SERVER_STARTED`. With `level-seed=` empty, Minecraft chooses a new
   random seed; a deliberately configured fixed seed remains respected.
6. The proxy requires a fresh, empty, unused `ready` snapshot that matches the
   durable world identity, exactly matches the expected request, differs from the
   previous boot and world, and has no remaining request file. It then removes
   `reset.pending.json`, promotes the backend to `READY`, and resumes the wait.

The container implements steps 1–4 in `docker/entrypoint.sh`. Its restart policy
performs step 5. A valid request remaining at container boot is completed before
launching a JVM; interruption during wipe/preparation therefore retries safely.
The id-only `RESET_COMPLETE` protocol message merely triggers the same file
validation. It cannot independently acknowledge a reset.

A used empty standby is prepared when a death needs a fresh destination. Logout
alone never schedules a wipe. Sources are scheduled only after all transfers
succeed and each source is empty.

## Upgrading and custom executors

Update the proxy, every backend mod, and the executor together. Drain outstanding
schema-1 resets with the previous executor before upgrading; schema-1 status or
reset files do not prove schema-2 completion. Do not clear pending files or relabel
an existing world as unused to bypass the gate. Existing worlds are preserved on
ordinary startup; their next requested reset establishes schema-2 preparation.
If an upgrade preserves multiple used worlds and has no saved current-world
pointer, choose the intended resumable backend before reopening the proxy:
write `plugins/linkedhardcore/current-world.json` with its Velocity `serverName`
and the actual `generationId` from its `world-generation.json`. Without that
pointer, bootstrap prefers an occupied backend, then the first used backend in
configured order. After the first join/transfer the pointer is saved automatically.

For different hosts, a future webhook implementation must carry this same request
identity and durable expectation, and expose equivalent verified completion.

## Isolated validation

```sh
./gradlew --no-daemon --max-workers=1 build
bash docker/tests/reset_contract_test.sh
bash docker/tests/proxy_persistence_test.sh
# Requires an already cached Fabric installation; creates a disposable localhost backend:
python3 scripts/reset_smoke.py --runtime /path/to/cached/fabric-install
```

The smoke test checks ordinary restart preservation, heartbeat freshness after
65 empty seconds, a killed disposable JVM and recovery with a pending death/no
carrier, outbox preservation through the actual executor wipe, sentinel removal,
a new boot/generation, and a changed Minecraft-generated seed. It models prior
world use with the JVM stopped. Unit tests cover player-arrival use persistence, A-to-B-to-new-A routing,
pending-reset races, stale completion, reconnect routing, and concurrent writes.
These checks do not substitute for an in-game player transfer test.

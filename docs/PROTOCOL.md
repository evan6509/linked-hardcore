# Protocol: Fabric mod ↔ Velocity plugin

This document is the single source of truth for the plugin-messaging wire
protocol between the Linked Hardcore Fabric mod and the Linked Hardcore Velocity
plugin. Both codebases implement this byte layout **independently** (they have
zero compile-time coupling), so any change here MUST be mirrored in both:

- `fabric-mod/src/main/java/dev/linkedhardcore/fabric/net/Protocol.java`
- `velocity-plugin/src/main/java/dev/linkedhardcore/velocity/net/Protocol.java`

## Channel

| Field  | Value              |
|--------|--------------------|
| Name   | `linkedhardcore:main` |
| Identifier (Fabric) | `Identifier.fromNamespaceAndPath("linkedhardcore", "main")` |
| Identifier (Velocity) | `MinecraftChannelIdentifier.from("linkedhardcore:main")` |

The channel is registered on the proxy's `ChannelRegistrar`; without that the
proxy does not fire `PluginMessageEvent` for it.

## Transport & framing

- Direction is defined by Minecraft's custom-payload semantics:
  - **Mod → proxy** (`PLAYER_DIED`, `RESET_COMPLETE`, `TRANSFER_READY`): clientbound
    custom payload (registered in `PayloadTypeRegistry.clientboundPlay()`).
  - **Proxy → mod** (`PREPARE_TRANSFER`, `WAIT_FOR_SERVER`, `ACK`, `DEATH_COUNTERS`, `RUN_TIMER`): serverbound custom
    payload (registered in `PayloadTypeRegistry.serverboundPlay()`).
- The proxy intercepts these at `PluginMessageEvent`; `getData()` returns the
  raw frame bytes exactly as described below. On the Fabric side the payload is a
  raw-byte passthrough (`LinkedHardcorePayload`) so the frame bytes are untouched.
- The proxy sets `ForwardResult.handled()` for any message on this channel so
  nothing leaks onward to the actual client, and it only accepts messages whose
  source is a backend `ServerConnection` (a client can't impersonate the proxy).

## Common encoding

| Type   | Encoding |
|--------|----------|
| opcode | 1 byte |
| UUID   | 16 raw bytes, big-endian: 8-byte most-significant half then 8-byte least-significant half |
| string | Minecraft varint (7 bits/byte, high bit = continuation) length, then UTF-8 bytes |

All integers are big-endian.

## Messages

> All players on both backends form a **single linked life pool** — there are no
> groups, so the messages carry no group identifier.

### `PLAYER_DIED` — opcode `0x01` (mod → proxy)

Legacy unscoped death. The decoder still recognizes this layout, but the proxy
message listener refuses it without ACK, counting, transfer, or reset. A delayed
frame cannot prove which world ended. Update old senders; current mods send
`WORLD_PLAYER_DIED` below.

```
byte[1]   opcode = 0x01
byte[16]  playerUuid (the player who died)
```

Proxy behaviour for **world-scoped** deaths (`TransferHandler#onPlayerDied`):
1. Checkpoints the ended world, pending transfer, and accepted death before replying `ACK`.
   A rejected checkpoint or missing world identity receives no ACK and can be retried.
2. Records the proxy-authoritative death total and broadcasts `DEATH_COUNTERS`. The
   accepted record stays in the lifecycle journal until the counter store is durable.
3. Includes every occupied backend in one transfer; if no empty `READY` backend exists,
   sends `WAIT_FOR_SERVER` and holds the transfer pending.
4. On `TRANSFER_READY` (sent after the countdown), transfers every player to the chosen
   destination, waits for every connection to succeed, and verifies every source is empty.
5. Only then flags every vacated source for reset.

### `PREPARE_TRANSFER` — opcode `0x02` (proxy → mod)

Tells the Fabric mod to spectate everyone on this server and start the on-screen
transfer countdown.

```
byte[1]   opcode = 0x02
```

The mod sets every online player to spectator and shows a countdown in the action
bar. When it finishes, the mod sends `TRANSFER_READY`; the proxy then moves
everyone. Players respawn into play (survival) on arrival at the other server.

### `RESET_COMPLETE` — opcode `0x03` (mod → proxy)

Legacy best-effort hint that asks the proxy to re-read the backend files. It
contains no generation identity and cannot independently prove reset completion.

```
byte[1]   opcode = 0x03
varint    serverId (e.g. "a" or "b")
```

The proxy resolves `serverId` → velocity server name and revalidates the backend's
files before accepting readiness.

> Note: this message is best-effort. When a freshly-reset server has zero players
> there is no live connection carrying our channel, so the message may not arrive.
> The proxy polls and validates the complete schema-2 reset/world identity as the
> reliable path (see `RESET_CONTRACT.md`).

### `ACK` — opcode `0x04` (proxy → mod)

Acknowledges a received `PLAYER_DIED`, correlated by the original player's UUID.

```
byte[1]   opcode = 0x04
byte[16]  playerUuid (the player from the PLAYER_DIED being acked)
```

### `WORLD_PLAYER_DIED` — opcode `0x09` (mod → proxy)

Updated mods report and retry a death with its world generation:

```
byte[1]   opcode = 0x09
byte[16]  playerUuid
varint    generationId UTF-8 byte length
byte[]    generationId UTF-8 bytes
```

The generation is nonblank, at most 32,767 characters, and must match the backend's
durable identity. Malformed or trailing data is rejected. Missing identity or a
failed lifecycle checkpoint leaves the event unacknowledged. Retries for an accepted
source world are acknowledged without recounting. Delayed events from retired or
replaced generations cannot end the new run, including after proxy restart.
An event from the destination while the previous handoff is still active waits
unacknowledged until that handoff commits.

### `WORLD_ACK` — opcode `0x0a` (proxy → mod)

```
byte[1]   opcode = 0x0a
byte[16]  playerUuid
varint    generationId UTF-8 byte length
byte[]    generationId UTF-8 bytes
```

Only an ACK matching both the pending player's UUID and generation, with that
generation still current on the receiving backend, removes a durable event.
Removal itself is atomic and precedes forgetting it. A legacy `ACK` cannot clear
a world-scoped event; an ACK for a retired generation cannot clear anything.

### `TRANSFER_READY` — opcode `0x05` (mod → proxy)

Sent when the on-screen countdown finishes; signals the proxy to transfer everyone
now.

```
byte[1]   opcode = 0x05
```

Proxy behaviour (`TransferHandler#onTransferReady`): transfers every player on the
proxy to the chosen destination, then resets only the sources that are confirmed empty.

### `WAIT_FOR_SERVER` — opcode `0x06` (proxy → mod)

Tells the Fabric mod that no destination server is ready yet; the mod should keep
everyone in spectator and show "Waiting for available server" until a server
becomes ready (the proxy then sends `PREPARE_TRANSFER`).

```
byte[1]   opcode = 0x06
```

### `DEATH_COUNTERS` — opcode `0x07` (proxy → mod)

Broadcast after an accepted death and sent when a player joins a backend. The proxy
persists these totals outside resettable world directories; the Fabric mod renders
the snapshot in the sidebar.

```
byte[1]   opcode = 0x07
varint    counterCount (0–10,000)
repeat counterCount times:
  byte[16] playerUuid
  varint   playerName UTF-8 length
  byte[]   playerName UTF-8 bytes
  varint   deaths
```

### `RUN_TIMER` — opcode `0x08` (proxy → mod)

Sent once per second to occupied configured backends and immediately on joins,
disconnects, accepted deaths, and successful transfers. The Fabric mod displays
the shared run time above the player death totals without using the action bar.

```
byte[1]   opcode = 0x08
byte[8]   elapsedMillis (non-negative signed long, big-endian)
byte[1]   ended (0 = current run, 1 = ended by a death)
```

The frame must be exactly ten bytes, with an ended flag of 0 or 1. The proxy
measures elapsed play with a monotonic clock, pauses when no linked players are
online, and persists snapshots in `run-timer.json`. A restored timer stays paused
until a player joins; downtime is excluded. Death freezes the duration, and only
a successfully completed transfer resets it to zero. Duplicate deaths and failed
transfers do not reset the timer.

## Reliability / failure detection

`ServerPlayNetworking#send` does **not** verify the receiving side registered the
channel — a misconfigured channel fails silently. To avoid a silent black hole:

- Before sending, the mod atomically commits the deceased UUID and generation to
  `config/linkedhardcore/death-outbox.json`, outside resettable worlds. Schema 1
  records `protocolVersion: 3`, `serverId`, and a `deaths` list of
  `{playerUuid, generationId}`. The event identity is `(backend, generation, player)`;
  encoding it again produces exactly the same `0x09` bytes. A world generation has
  one linked-run termination, so repeated reports for a player in that world are
  the same event, rather than separate deaths to count.
- `LinkedHardcoreMod.preparePlayerDeath(UUID)` is the companion API. It returns the
  committed generation without sending. Manhunt calls it **before** saving its
  completed runner and transfer latch, in either death-listener order. The normal
  sender uses the same idempotent preparation, including ordinary non-Manhunt deaths.
- The outbox is validated on construction and read as the authoritative queue.
  A fresh messenger after JVM restart immediately retries pending current-generation
  reports over a current player connection. The deceased player's replacement is
  preferred, but any connected player on that backend may carry the original frame.
  With no carrier, nothing is removed or marked sent; reconnect enables retry.
- After a send, tick retries wait `ackTimeoutSeconds` (default 10). Only send times
  are volatile. A matching `WORLD_ACK` atomically removes the entry; restart during
  removal leaves either the event to retry or its committed removal.
- Proxy acceptance checkpoints source generations and the absolute accepted death
  count in `current-world.json` before ACK or transfer. Pending-source replays ACK
  without recounting; recovery restores that absolute count, including when the
  separate counter save failed. Commit retains the journal until the count is saved.
  After commit, source cleanup/reset expectations or a different on-disk generation
  reject/ACK retired reports without ending the destination's run.
- Reports for retired generations remain quarantined in the backend journal and
  are never sent into the replacement world. Do not delete journals to force a reset.
  Invalid schema, missing identity, a foreign server ID, or persistence failure
  fails closed. The journal is limited to 1 MiB; refusing an additional intent
  preserves all committed entries and prevents its caller's completion latch/send.
  Unpublished temporary files are never used as recovery input.

### Failure model and upgrade

The guarantee covers backend/proxy **process crashes**, lost/duplicate plugin
messages, and ordinary restarts with completed file replacements retained. It
assumes one JVM owns each backend directory, matching world-generation metadata,
and local filesystems with atomic replacement. The outbox refuses non-atomic
replacement. File writes close before rename; neither this outbox nor the existing
proxy lifecycle/counter stores provides an end-to-end file+directory fsync
protocol. **Power loss, disk corruption, independent/partial backup rollback,
manual journal removal, and resetting a running world are outside this guarantee.**

Update Manhunt, the companion on every backend, and the proxy together. Scoped
v2 frames/ACKs retain their byte layout and remain interoperable; v1 unscoped
reports are deliberately refused in v3. Old in-memory backend queues cannot be
recovered after they have already vanished. Drain outstanding work with the old
versions before upgrading. An old Manhunt runner-death latch without
`linked_death_generation` does not prove whether the report was lost or ACKed:
new Manhunt rejects startup, preserving the world for explicit operator
reconciliation of its completion and proxy lifecycle/counter journals. Never
invent a generation, clear the latch, or relabel the source fresh. Old standalone
saves without a pending linked runner death remain readable. A proxy lifecycle
missing source-generation identities is also rejected by its existing loader.

A single ack round-trip therefore proves **both** directions are healthy:
- mod→proxy (payload registered in `clientboundPlay`; channel in proxy `ChannelRegistrar`)
- proxy→mod (payload registered in `serverboundPlay`; proxy's send path works)

## Versioning

Opcodes are stable and must never be reused for a different message. If the
protocol changes incompatibly, bump a `PROTOCOL_VERSION` constant on both sides
and document it here.

`PROTOCOL_VERSION` is now 3 on both sides: durable outbox/ACK semantics and refusal
of unsafe unscoped reports. No opcode was reused or added; `0x09`/`0x0a` keep the
v2 byte layout. The local outbox schema starts at 1. The companion preparation
API and Manhunt's generation proof must be installed together as described above.

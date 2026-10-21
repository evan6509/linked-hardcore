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

Sent when any player dies on this server.
This legacy frame remains readable; updated mods send `WORLD_PLAYER_DIED` below.

```
byte[1]   opcode = 0x01
byte[16]  playerUuid (the player who died)
```

Proxy behaviour (`TransferHandler#onPlayerDied`):
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

Only an ACK matching both the pending player's UUID and generation clears a
world-scoped death. A legacy `ACK` cannot clear a world-scoped event.

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

- The mod retains each `WORLD_PLAYER_DIED` frame, its generation, and last send time.
- On a matching `WORLD_ACK` it clears the entry and logs the round-trip time.
- On each server tick (`ServerTickEvents.END_SERVER_TICK`) entries older than
  `ackTimeoutSeconds` (default 10, configurable) are retried with the same identity
  and bytes. A currently connected player carries the retry, including after the
  original player respawns or disconnects. If no carrier exists, the entry remains
  pending until one is available. This queue is in memory for the backend JVM's
  lifetime; it does not survive a backend restart before proxy acceptance.

A single ack round-trip therefore proves **both** directions are healthy:
- mod→proxy (payload registered in `clientboundPlay`; channel in proxy `ChannelRegistrar`)
- proxy→mod (payload registered in `serverboundPlay`; proxy's send path works)

## Versioning

Opcodes are stable and must never be reused for a different message. If the
protocol changes incompatibly, bump a `PROTOCOL_VERSION` constant on both sides
and document it here.

`PROTOCOL_VERSION` is now 2. Legacy opcodes remain readable. Upgrade the proxy and
all backend mods together to enable world-scoped death retry and acknowledgement.

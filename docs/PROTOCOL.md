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
  - **Proxy → mod** (`PREPARE_TRANSFER`, `ACK`): serverbound custom payload
    (registered in `PayloadTypeRegistry.serverboundPlay()`).
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

```
byte[1]   opcode = 0x01
byte[16]  playerUuid (the player who died)
```

Proxy behaviour (`TransferHandler#onPlayerDied`):
1. Replies `ACK` on the same connection (unconditionally).
2. Sends `PREPARE_TRANSFER` back to the originating server.
3. Waits for `TRANSFER_READY` (sent after the mod's on-screen countdown), then
   transfers every player on the proxy to the other backend server.
4. Flags the vacated server for reset.

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

Sent after a server has finished resetting and reports itself ready again.

```
byte[1]   opcode = 0x03
varint    serverId (e.g. "a" or "b")
```

The proxy resolves `serverId` → velocity server name via its config and flips the
server state back to `READY` (idempotent).

> Note: this message is best-effort. When a freshly-reset server has zero players
> there is no live connection carrying our channel, so the message may not arrive.
> The proxy ALSO polls each backend's `status.json` for `state: "ready"` as the
> reliable path (see `RESET_CONTRACT.md`).

### `ACK` — opcode `0x04` (proxy → mod)

Acknowledges a received `PLAYER_DIED`, correlated by the original player's UUID.

```
byte[1]   opcode = 0x04
byte[16]  playerUuid (the player from the PLAYER_DIED being acked)
```

### `TRANSFER_READY` — opcode `0x05` (mod → proxy)

Sent when the on-screen countdown finishes; signals the proxy to transfer everyone
now.

```
byte[1]   opcode = 0x05
```

Proxy behaviour (`TransferHandler#onTransferReady`): transfers every player on the
proxy to the OTHER backend (whichever is not the sender), marks the sender
`RESETTING` and the destination `LIVE`, and flags the vacated server for reset.

## Reliability / failure detection

`ServerPlayNetworking#send` does **not** verify the receiving side registered the
channel — a misconfigured channel fails silently. To avoid a silent black hole:

- The mod records every `PLAYER_DIED` (playerUuid → sent timestamp) as pending.
- On `ACK` receipt it clears the entry and logs the round-trip time.
- On each server tick (`ServerTickEvents.END_SERVER_TICK`) entries older than
  `ackTimeoutSeconds` (default 10, configurable) trigger a log error naming the
  likely misconfiguration and are then dropped (warn once, not every tick).

A single ack round-trip therefore proves **both** directions are healthy:
- mod→proxy (payload registered in `clientboundPlay`; channel in proxy `ChannelRegistrar`)
- proxy→mod (payload registered in `serverboundPlay`; proxy's send path works)

## Versioning

Opcodes are stable and must never be reused for a different message. If the
protocol changes incompatibly, bump a `PROTOCOL_VERSION` constant on both sides
and document it here.

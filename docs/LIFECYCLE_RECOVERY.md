# Linked-run recovery

The proxy checkpoints the current world and the pending handoff together in
`plugins/linkedhardcore/current-world.json`. This proxy-owned file uses lifecycle
schema 1; backend world/status/reset files continue to use reset schema 2.
The old flat `{serverName, generationId}` format remains readable.

The lifecycle records:

- The current Velocity server name, world generation, and committed run ID.
- A pending next-run ID, phase, source generations, selected destination generation,
  and the accepted death-counter record.
- Source generations still awaiting publication of their reset requests.

An accepted death is checkpointed before the proxy publishes its pending transfer,
increments the death counter, ACKs it, or starts a countdown. If that initial write
fails, the source state is restored and the death can be retried without double counting.
The source remains an ended run until a handoff commits. A proxy restart restores
that pending handoff and the accepted counter record instead of reopening the
source world in survival.

| Phase | Recovery |
|-------|----------|
| Waiting | Re-evaluate verified unused destinations; prepare a used empty standby when needed. |
| Countdown | On proxy restart, return to waiting and replay preparation. If everybody is offline, commit a verified fresh destination without requiring a plugin-message carrier. An unavailable reservation is released so another fresh destination can be selected. |
| Connecting | On restart, enter recovery and retain the exact reserved destination generation; players may already have entered it. |
| Recovering | Preserve worlds while players remain connected. Once all configured backends are empty, finish the handoff to the reserved generation when its heartbeat is healthy. |
| Committing | Restore the matching current-world pointer, start the same timer run ID idempotently, and persist source-reset cleanup. |

The timer checkpoint also carries the run ID. Replaying a commit for that same ID
preserves elapsed time. If the timer file belongs to the previous source run, the
committed lifecycle identifies the new epoch and repairs the timer without treating
the source's old ended flag as a death in the new world. During upgrade, an ended
legacy timer with a known current-world pointer is converted into a pending handoff;
an ended run with no world identity blocks routing until its pointer is restored.

Source cleanup checks the exact ended generation and emptiness before requesting a
reset. Missing/booting status is retried when the source publishes its actual boot
identity. Cleanup remains durable until `reset.pending.json` is published; a source
already changed to another generation is not wiped again.

A connection approved just before a death may finish after connection fan-out starts.
The post-connect handler rejects that late source arrival, with a message to reconnect
into the current run, because it is outside the captured connection futures. Source
arrivals remain rejected during recovery and after retirement; they cannot change
the saved pointer back to the ended world. Before fan-out, source arrivals are put
back into the wait/countdown flow.

The first arrival receives counters through its active `ServerConnection` after
`ServerPostConnectEvent`, avoiding the earlier event where the backend had no
registered carrier. WAIT/PREPARE respawns a dead player through Minecraft's vanilla
`PERFORM_RESPAWN` handler, then spectates the replacement entity. Iteration uses a
snapshot because respawn replaces the player in the server's player list.

## Container persistence and upgrade

The default proxy runtime directory is now `/lh/proxy`, inside the named volume.
The image's Velocity install remains `/opt/linkedhardcore/proxy`; startup copies
its executable into the runtime directory. Ordinary restart, image rebuild, and
container recreation therefore retain the lifecycle, timer, and death totals.
Backend reset routines never delete this proxy directory.

**Before recreating an older proxy container**, copy its three state files from
`/opt/linkedhardcore/proxy/plugins/linkedhardcore/` into the volume's
`/lh/proxy/plugins/linkedhardcore/`:

- `current-world.json`
- `run-timer.json`
- `death-counters.json`

Copy a consistent snapshot with the old proxy stopped. Keep the old container until
the backup has been verified. The entrypoint imports old-location state when it is
still available and no volume copy exists; a fresh replacement image cannot recover
the removed container's writable layer. Existing volume state always takes precedence.
Do not delete the volume during this upgrade. If no current-world file exists and
multiple used worlds are preserved, select the intended resumable backend as described
in [the reset contract](RESET_CONTRACT.md).

Update the proxy, every backend mod, and the executor together. Partial connection
failures still preserve occupied sources rather than wiping them. Recovery proceeds
when all players disconnect and the reserved destination is healthy; source/destination
generation conflicts require investigation rather than silently selecting another
already-used world.

```sh
bash docker/tests/proxy_persistence_test.sh
bash docker/tests/reset_contract_test.sh
./gradlew build
```

The storage fixture models replacement of the image directory while retaining the
same volume. It does not claim a real Docker recreation or live multiplayer test.

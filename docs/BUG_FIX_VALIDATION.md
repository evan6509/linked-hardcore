# Reset and lifecycle bug-fix validation

Fixes prevent used empty worlds and pre-reset heartbeats from becoming new-run destinations. World use survives ordinary restarts and logout; expected reset IDs survive unavailable states and proxy restarts. Status writes are serialized, and container startup disables idle pausing.

The follow-up lifecycle review found five additional defects, all repaired:

1. Accepted deaths and pending handoffs are checkpointed before ACK/preparation and restored after proxy restart. Timer run IDs make commit replay idempotent.
2. If everyone disconnects during countdown, a verified fresh destination can commit without a plugin-message carrier. An unavailable reservation can be replaced by another verified fresh backend.
3. Proxy lifecycle, timer, and counter files live under `/lh/proxy` in the persistent volume. Existing volume state takes precedence over old image-local files.
4. First-arrival counter synchronization runs after `ServerPostConnectEvent`, using the player's active backend connection.
5. Dead players respawn through Minecraft's vanilla command handler before the replacement player is made a spectator.

Additional regressions cover source-reset publication retries, stale arrivals after handoff, and recovery when the timer still contains the previous run's ended checkpoint. See [linked-run recovery](LIFECYCLE_RECOVERY.md) for phases and upgrade steps.

## Java build and regressions

A Java 25 Gradle build completed successfully using temporary build outputs. Workspace build directories repeatedly acquired duplicate generated test classes (`ProtocolTest 3.class`, for example), so validation isolated its outputs instead of filtering or skipping tests. The final build recompiled the latest repairs, and its resulting JARs were copied back into the normal `build/libs` paths.

| Module | Tests | Failures | Errors | Skipped |
|--------|-------|----------|--------|---------|
| fabric-mod | 11 | 0 | 0 | 0 |
| velocity-plugin | 56 | 0 | 0 | 0 |

Regressions cover used empty worlds after startup, current-world reconnects after proxy restart, the request-publication gap, unavailable resets, delayed/id-only completion messages, conflicting reset expectations, remaining request files, player-arrival use persistence, concurrent ordered status publication, and A-to-B-to-regenerated-A transfers. The last test invokes the real shell executor on disposable world directories and verifies removal of old-world sentinels.

Lifecycle tests cover restart during waiting/countdown/connecting/commit, offline handoffs and destination replacement, cleanup retry, corrupt checkpoint admission rejection, stale timer epochs, late source arrivals, and first-carrier counter delivery. Respawn tests verify the vanilla handler invocation and spectator mode on the replacement entity. Inspection of the pinned Minecraft/Velocity bytecode confirms the corresponding respawn and connection/event ordering; it does not substitute for gameplay.

PR review regressions also verify that source arrivals are disconnected once connection fan-out begins, allowing the captured transfer to commit; recovery rejects new source arrivals while retaining destination arrivals. Waiting/countdown source arrivals remain supported. A real atomic-write failure leaves the pending transfer unpublished, preserves both absent and existing death-counter records, restores source state, and sends no ACK. Repeated failures followed by a successful retry count the death exactly once, with the accepted record recoverable after restart.

Final runtime JAR SHA-256 values:

| Artifact | SHA-256 |
|----------|---------|
| `fabric-mod/build/libs/fabric-mod-0.1.0.jar` | `96646dda4a4266654793bc3faefdb74bd661f160de3320ac8a8800bcf09a4710` |
| `velocity-plugin/build/libs/velocity-plugin-0.1.0.jar` | `fca3432236738207db213483ff2622b02bfd111cb86a30385c25e2756b186fdb` |

## Executor and real Minecraft smoke

`bash docker/tests/reset_contract_test.sh` passed. It checks occupied/stale/wrong-boot rejection, preservation of existing worlds, the enforced idle setting, generation publication, request consumption, and retention of the pending expectation.

`bash docker/tests/proxy_persistence_test.sh` passed. It models image replacement with a retained volume, checks migration of the three old state files, prevents stale image-local state from overwriting volume state, and supports matching install/runtime directories. A real Docker container recreation was not performed.

`python3 scripts/reset_smoke.py` passed with the final Fabric JAR on a disposable localhost Minecraft 26.2 / Fabric 0.19.3 backend. Existing worlds, configurations, and servers were not modified. Prior player use was modeled while the fixture JVM was stopped; real startup, heartbeat, shutdown, wipe, and regeneration then executed.

- Ordinary restart preserved the generation, world sentinel, and seed.
- After 65 empty seconds, the heartbeat still refreshed within the five-second freshness limit.
- The request produced a fresh resetting heartbeat before shutdown.
- The actual executor removed the old world sentinel and request, retained the pending expectation, and published the requested new generation.
- The restarted mod reported a new boot and an unused ready world with the matching completion identity.
- Minecraft-generated seed changed from `3026380067561327950` to `-5047452241645650580` with an empty `level-seed` setting.

Local smoke results and logs are retained under `.test-server/reset-smoke/`; Java result XMLs are under `.test-server/validation/` (both gitignored). Shell syntax, Python syntax, `git diff --check`, and nested FabricProxy-Lite JAR contents were verified.

## Deployment boundary

Production deployment and an in-game multiplayer transfer have not been performed. Live death-screen respawn, client counter display, and multiplayer connection timing remain unverified. Upgrade the proxy, all backend mods, and the executor together according to [the schema-2 reset contract](RESET_CONTRACT.md). Drain old-schema pending resets before upgrading. Before recreating an older proxy container, preserve its three state files using [the migration procedure](LIFECYCLE_RECOVERY.md#container-persistence-and-upgrade). When preserving multiple used worlds without a saved current-world pointer, select the intended resumable backend as described in the reset contract.

Existing unrelated Docker count/memory edits were preserved. The download issue report was read as diagnostic input and was not modified.

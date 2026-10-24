# Fabric 26.2 and 26.3 compatibility validation

Validated locally on 2026-10-24 with the exported JDK 25.0.5+8. This extends
`1cfea6be9fe04cc2b24765b33ec333382f936a21` on `codex/development`.

## Targets and source evidence

| Minecraft | Fabric API | Loader | Java | Artifact Minecraft predicate |
|-----------|------------|--------|------|------------------------------|
| 26.2 (default) | 0.156.0+26.2 | 0.19.3 | 25 | `~26.2` |
| 26.3 | 0.165.0+26.3 | 0.19.3 | 25 | `~26.3` |

The [official Fabric Maven metadata](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml)
contains stable `0.165.0+26.3`. Inspection of the downloaded API JARs' own
`fabric.mod.json` found:

- [0.165.0+26.3](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.165.0+26.3/fabric-api-0.165.0+26.3.jar):
  Loader `>=0.19.3`, Java `>=25`, Minecraft `~26.3-`.
- [0.156.0+26.2](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.156.0+26.2/fabric-api-0.156.0+26.2.jar):
  Loader `>=0.18.4`, Java `>=25`, Minecraft `~26.2-`.

The existing Loader 0.19.3 pin satisfies both minima and is listed for 26.3 by
[Fabric's loader metadata](https://meta.fabricmc.net/v2/versions/loader/26.3).
Mojang's official release metadata for
[26.2](https://piston-meta.mojang.com/v1/packages/bdfd9856166b7171857c491f15479762f3abda99/26.2.json)
and [26.3](https://piston-meta.mojang.com/v1/packages/61fff1316bd50312624a858bbb5eb18fa45208ed/26.3.json)
requires Java 25. No separate mappings dependency is needed.

Inspection with `javap` of the actual Loom 26.3 mapped game JAR and Fabric API
nested modules verified `ServerPlayer.gameMode`/`setGameMode`, scoreboard APIs,
`ServerLivingEntityEvents.AfterDeath`, lifecycle/tick events, payload registries
and `ServerPlayNetworking` signatures used here. Inspection of the 26.3 vanilla
`handleClientCommand` bytecode confirmed that the dead-player respawn path
replaces `connection.player`, resets position and restarts the client load timer.
Both target compilations then checked all production and test API references.
No production Java, Velocity, protocol, FabricProxy-Lite, or Docker change was
required. Bundled FabricProxy-Lite 2.12.0 declares Minecraft `>=26.1`, Loader
`>=0.18.5`, API `>=0.144.3+26.1`, and Java `>=25`.

## Regression sequence and results

Before implementation, `scripts/check_fabric_jar.py` ran against the existing
built JAR: the 26.3 contract failed its Minecraft predicate and API minimum
checks (2 failures of 5 tests); the 26.2 contract failed its Minecraft range
check (1 failure of 5). The implementation selects a complete tuple and expands
target-specific metadata. JUnit additionally evaluates these predicates using
Fabric Loader against the actual mapped Minecraft version on the classpath,
rejecting the other target, older targets, future minor lines and dependencies
below the minima.

Final commands ran sequentially in this checkout:

```sh
bash scripts/package_target.sh 26.3
bash scripts/package_target.sh 26.2
bash docker/tests/reset_contract_test.sh
bash docker/tests/proxy_persistence_test.sh
python3 docker/tests/proxy_config_test.py
python3 docker/tests/build_prerequisites_test.py
```

Each package command invokes Gradle `build --no-daemon --max-workers=1` with its
Minecraft property, then checks the installable JAR and its nested forwarder.
26.3 has separate Fabric outputs under `fabric-mod/build/26.3/`. The default
26.2 path is retained for Docker. Both builds resolved Loom 1.17.21 from the
existing 1.17-SNAPSHOT declaration.

| Check | Tests | Failures / errors / skipped |
|-------|------:|----------------------------|
| Fabric 26.2 JUnit | 31 | 0 / 0 / 0 |
| Fabric 26.3 JUnit | 31 | 0 / 0 / 0 |
| Velocity JUnit | 73 | 0 / 0 / 0 |
| 26.2 installable JAR | 5 | 0 / 0 / 0 |
| 26.3 installable JAR | 5 | 0 / 0 / 0 |
| Docker proxy configuration Python | 2 | 0 / 0 / 0 |
| Docker build prerequisites Python | 1 | 0 / 0 / 0 |
| GameTests | 0 | Not configured or run |

Velocity tests executed in the initial full 26.3 build; subsequent builds reused
the successful unchanged result. The two shell fixture suites passed on disposable
directories. Gradle configuration also correctly rejected unsupported target
26.4 and a 26.3 target paired explicitly with API 0.156.0+26.2. Shell syntax,
workflow YAML parsing and `git diff --check` passed.

## Installable artifacts and EULA-gated loading

| Artifact | SHA-256 |
|----------|---------|
| `fabric-mod/build/libs/fabric-mod-0.1.0.jar` | `7425e9ed22b6cf7082fc179129d418c15d8ed663c8088b5be6befeda3bb422da` |
| `fabric-mod/build/26.3/libs/fabric-mod-mc26.3-0.1.0.jar` | `4f33831d3ea1af3d8743ac6b44630e3b82388282dec11b0f10e1f5cbc57be308` |
| `velocity-plugin/build/libs/velocity-plugin-0.1.0.jar` | `d592933404ab49011a0f2c390278b0011222f89a76b38b22c19f0cf50323d269` |

Fabric installer 1.1.2 created two disposable installations under
`.test-server/compat/install-26.2` and `install-26.3`, each with Loader 0.19.3,
the matching packaged JAR and matching external Fabric API. Both launched with
`java -Xmx1G -jar fabric-server-launch.jar nogui`, resolved the nested forwarder,
initialized Linked Hardcore and exited successfully at Minecraft's EULA gate.
Both retained `eula=false` and created no world directory. This checks loading,
mod initialization and startup mixin application; it is not a running-server
or connected-client test.

Local build logs, red-test evidence, official metadata, API inspection output,
installer/launch logs and disposable installations are retained under the
ignored `.test-server/compat/`. JUnit XML is in each module's build directory.

No deployment, live server/world access, reset smoke, connected-client gameplay,
multiplayer transfer or client display validation was performed. GitHub Actions
has not been observed running this new workflow. No release is published by it.

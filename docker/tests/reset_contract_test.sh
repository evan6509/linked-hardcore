#!/usr/bin/env bash
# Exercises the actual executor functions using disposable directories only.
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
source "$repo/docker/entrypoint.sh"
fixture=$(mktemp -d "${TMPDIR:-/tmp}/linked-hardcore-reset-test.XXXXXX")
trap 'rm -rf "$fixture"' EXIT
SERVER_ID=a
data="$fixture/server-a"
mkdir -p "$data/config/linkedhardcore"
dir="$data/config/linkedhardcore"
initialize_world_identity "$data"
jq -e '.used == false and .schemaVersion == 2' "$dir/world-generation.json" >/dev/null
first=$(jq -r '.generationId' "$dir/world-generation.json")
mkdir -p "$data/world"
printf 'old world sentinel\n' > "$data/world/sentinel"
jq -n --arg generation "$first" '{schemaVersion:1, protocolVersion:3, serverId:"a",
    deaths:[{playerUuid:"12345678-1234-5678-9abc-def012345678", generationId:$generation}]}' > "$dir/death-outbox.json"
cp "$dir/death-outbox.json" "$fixture/committed-death.json"
initialize_world_identity "$data"
[ "$(jq -r '.generationId' "$dir/world-generation.json")" = "$first" ]
[ -f "$data/world/sentinel" ]
cmp "$fixture/committed-death.json" "$dir/death-outbox.json"

printf 'pause-when-empty-seconds=60\nlevel-seed=\n' > "$data/server.properties"
ensure_server_properties "$data"
[ "$(grep -c '^pause-when-empty-seconds=0$' "$data/server.properties")" = 1 ]
ensure_server_properties "$data"
[ "$(grep -c '^pause-when-empty-seconds=0$' "$data/server.properties")" = 1 ]

jq -n --arg old "$first" '{schemaVersion:2, serverId:"a", requestId:"next-generation",
    previousGenerationId:$old, previousBootId:"old-boot"}' > "$dir/reset.request.json"
cp "$dir/reset.request.json" "$dir/reset.pending.json"
jq -n --arg old "$first" --arg now "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '{schemaVersion:2,
    serverId:"a", state:"resetting", playerCount:1, updatedAt:$now, generationId:$old, bootId:"old-boot"}' > "$dir/status.json"
if reset_is_safe "$data"; then echo 'occupied world accepted for reset' >&2; exit 1; fi
jq '.playerCount=0 | .bootId="different-boot"' "$dir/status.json" > "$dir/test.tmp"
mv "$dir/test.tmp" "$dir/status.json"
if reset_is_safe "$data"; then echo 'wrong boot accepted for reset' >&2; exit 1; fi
jq '.bootId="old-boot" | .updatedAt="2000-01-01T00:00:00Z"' "$dir/status.json" > "$dir/test.tmp"
mv "$dir/test.tmp" "$dir/status.json"
if reset_is_safe "$data"; then echo 'stale heartbeat accepted for reset' >&2; exit 1; fi
jq --arg now "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '.updatedAt=$now' "$dir/status.json" > "$dir/test.tmp"
mv "$dir/test.tmp" "$dir/status.json"
reset_is_safe "$data"
complete_world_reset "$data"
[ ! -e "$data/world/sentinel" ]
[ ! -e "$dir/status.json" ]
[ ! -e "$dir/reset.request.json" ]
[ -e "$dir/reset.pending.json" ]
# Even an unacknowledged old-world report survives replacement for quarantine.
cmp "$fixture/committed-death.json" "$dir/death-outbox.json"
jq -e '.generationId == "next-generation" and .completedResetId == "next-generation" and .used == false' "$dir/world-generation.json" >/dev/null

# Legacy existing worlds must remain resumable through an ordinary boot.
data="$fixture/legacy"
mkdir -p "$data/world"
printf 'keep this world\n' > "$data/world/sentinel"
initialize_world_identity "$data"
jq -e '.used == true' "$data/config/linkedhardcore/world-generation.json" >/dev/null
[ -f "$data/world/sentinel" ]
printf 'Reset executor fixture tests passed.\n'

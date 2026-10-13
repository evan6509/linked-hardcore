#!/usr/bin/env bash
# Model image recreation against the same volume; never start a proxy or read live state.
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
source "$repo/docker/entrypoint.sh"
fixture=$(mktemp -d "${TMPDIR:-/tmp}/linked-hardcore-proxy-storage.XXXXXX")
trap 'rm -rf "$fixture"' EXIT
PROXY_INSTALL_DIR="$fixture/image-proxy"
SHARED_DIR="$fixture/volume"
PROXY_DIR="$SHARED_DIR/proxy"
mkdir -p "$PROXY_INSTALL_DIR/plugins/linkedhardcore"
printf 'image-one\n' > "$PROXY_INSTALL_DIR/velocity.jar"
for state in current-world.json run-timer.json death-counters.json; do
    printf '{"fixture":"preserve-%s"}\n' "$state" > "$PROXY_INSTALL_DIR/plugins/linkedhardcore/$state"
done
prepare_proxy_storage
for state in current-world.json run-timer.json death-counters.json; do
    cmp "$PROXY_INSTALL_DIR/plugins/linkedhardcore/$state" "$PROXY_DIR/plugins/linkedhardcore/$state"
done
# A rebuilt image no longer has the old container's writable layer. The volume
# retains the state already migrated/copied into it.
rm -rf "$PROXY_INSTALL_DIR"
mkdir -p "$PROXY_INSTALL_DIR"
printf 'image-two\n' > "$PROXY_INSTALL_DIR/velocity.jar"
prepare_proxy_storage
[ "$(cat "$PROXY_DIR/velocity.jar")" = image-two ]
for state in current-world.json run-timer.json death-counters.json; do
    grep -q "preserve-$state" "$PROXY_DIR/plugins/linkedhardcore/$state"
done
# Older state in an image/container must never overwrite newer volume state.
mkdir -p "$PROXY_INSTALL_DIR/plugins/linkedhardcore"
printf '{"fixture":"stale"}\n' > "$PROXY_INSTALL_DIR/plugins/linkedhardcore/current-world.json"
prepare_proxy_storage
grep -q 'preserve-current-world.json' "$PROXY_DIR/plugins/linkedhardcore/current-world.json"
# An explicitly shared install/runtime directory must not try copying the jar onto itself.
PROXY_INSTALL_DIR="$PROXY_DIR"
prepare_proxy_storage
printf 'Proxy storage recreation fixture tests passed.\n'

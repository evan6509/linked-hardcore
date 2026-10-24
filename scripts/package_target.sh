#!/usr/bin/env bash
# Sequential local invocations preserve the default Docker artifact path.
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:-26.2}" in
  26.2) target=26.2; jar=fabric-mod/build/libs/fabric-mod-0.1.0.jar ;;
  26.3) target=26.3; jar=fabric-mod/build/26.3/libs/fabric-mod-mc26.3-0.1.0.jar ;;
  *) echo 'Usage: bash scripts/package_target.sh [26.2|26.3]' >&2; exit 2 ;;
esac
./gradlew --no-daemon --max-workers=1 "-Pminecraft_version=$target" build
python3 scripts/check_fabric_jar.py --target "$target" "$jar"

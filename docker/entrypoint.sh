#!/usr/bin/env bash
#
# Linked Hardcore container entrypoint.
#
# MODE=proxy  -> run the Velocity proxy (controller). Templates velocity.toml +
#                the plugin config, installs the plugin jar, then execs Velocity.
# MODE=server -> run a Fabric backend. Templates the mod's config.json and
#                server.properties, injects the forwarding secret via env, then
#                runs the server while watching for reset.request.json. On a reset
#                request the server JVM is stopped cleanly, the world is wiped, and
#                the entrypoint exits so the container orchestrator (docker
#                restart: unless-stopped) brings the container back fresh. This is
#                Sisyphus-in-a-container and keeps the bare-metal reset contract
#                (docs/RESET_CONTRACT.md) intact for external agents.
#
# The shared volume is mounted at /lh; each backend owns /lh/server-<SERVER_ID>.
set -euo pipefail

log() { printf '[linkedhardcore] %s\n' "$*"; }

# ---- environment (with sane defaults) ----
MODE="${MODE:-server}"
SERVER_ID="${SERVER_ID:-a}"
FORWARDING_SECRET="${FORWARDING_SECRET:-linkedhardcore-dev-secret-change-me}"
MC_MEMORY="${MC_MEMORY:-3G}"
TRANSFER_COUNTDOWN_SECONDS="${TRANSFER_COUNTDOWN_SECONDS:-5}"
VELOCITY_PORT="${VELOCITY_PORT:-25577}"

INSTALL_DIR="${INSTALL_DIR:-/opt/linkedhardcore/server}"
PROXY_DIR="${PROXY_DIR:-/opt/linkedhardcore/proxy}"
PLUGIN_JAR="${PLUGIN_JAR:-/opt/linkedhardcore/velocity-plugin-0.1.0.jar}"
SHARED_DIR="${SHARED_DIR:-/lh}"

# ---------------------------------------------------------------------------
# server mode
# ---------------------------------------------------------------------------

# Directories listed in docs/RESET_CONTRACT.md that a wipe must remove.
world_dirs=(world world_nether world_the_end playerdata stats advancements)

wipe_world() {
    local data_dir="$1"
    for d in "${world_dirs[@]}"; do
        if [ -e "$data_dir/$d" ]; then
            log "wiping $data_dir/$d"
            rm -rf "$data_dir/$d"
        fi
    done
}

ensure_eula() {
    local eula="$1/eula.txt"
    if [ ! -f "$eula" ]; then
        printf 'eula=true\n' > "$eula"
        log "wrote $eula (eula accepted)"
    fi
}

# server.properties: the backend must run offline-mode (Velocity does the auth /
# modern forwarding via FabricProxy-Lite) and listen on 25565 internally (the
# proxy reaches it by service name over the compose network).
ensure_server_properties() {
    local sp="$1/server.properties"
    touch "$sp"
    sed -i 's/^online-mode=.*/online-mode=false/; s/^server-port=.*/server-port=25565/; s/^level-name=.*/level-name=world/' "$sp"
    grep -qx 'online-mode=false' "$sp" || printf 'online-mode=false\n' >> "$sp"
    grep -qx 'server-port=25565' "$sp" || printf 'server-port=25565\n' >> "$sp"
    grep -qx 'level-name=world' "$sp" || printf 'level-name=world\n' >> "$sp"
}

# config/linkedhardcore/config.json: the mod refuses to start without it. Create
# it if missing, then jq-update the env-driven fields while preserving anything
# else the operator has set (e.g. ackTimeoutSeconds).
ensure_mod_config() {
    local dir="$1/config/linkedhardcore"
    local cfg="$dir/config.json"
    mkdir -p "$dir"
    [ -f "$cfg" ] || printf '{}\n' > "$cfg"
    jq --arg id "$SERVER_ID" --argjson cd "$TRANSFER_COUNTDOWN_SECONDS" \
        '.serverId = $id | .transferCountdownSeconds = $cd' "$cfg" > "$cfg.tmp" \
        && mv "$cfg.tmp" "$cfg"
    log "mod config -> serverId=$SERVER_ID transferCountdownSeconds=$TRANSFER_COUNTDOWN_SECONDS ($cfg)"
}

run_server() {
    local data_dir="$SHARED_DIR/server-$SERVER_ID"
    local reset_file="$data_dir/config/linkedhardcore/reset.request.json"
    mkdir -p "$data_dir"

    # Populate the data dir from the baked install. First boot copies everything;
    # later boots merge (image wins for baked files only — server.properties,
    # config/ and world/ are not part of the bake and are never touched here).
    cp -a "$INSTALL_DIR/." "$data_dir/"

    # A reset request left over from a previous run (e.g. the container was
    # stopped between kill and wipe) must still be honoured before we boot.
    if [ -f "$reset_file" ]; then
        log "reset request present at boot; wiping world before start"
        wipe_world "$data_dir"
        rm -f "$reset_file"
    fi

    # Never boot with a stale status: the proxy's poller must not act on a state
    # from a previous process. The mod rewrites status.json on SERVER_STARTED.
    rm -f "$data_dir/config/linkedhardcore/status.json"

    ensure_eula "$data_dir"
    ensure_server_properties "$data_dir"
    ensure_mod_config "$data_dir"

    # FabricProxy-Lite reads the forwarding secret straight from the environment
    # (its own FABRIC_PROXY_SECRET knob) — no toml post-editing needed.
    export FABRIC_PROXY_SECRET="$FORWARDING_SECRET"
    export FABRIC_PROXY_HACK_ONLINE_MODE="true"

    log "starting Fabric server '$SERVER_ID' (Xmx=$MC_MEMORY, port=25565)"
    cd "$data_dir"
    java -Xmx"$MC_MEMORY" -jar fabric-server-launch.jar nogui &
    local server_pid=$!
    log "server pid $server_pid"

    # Forward TERM/INT (e.g. `docker compose down`) to the JVM and exit cleanly.
    trap 'log "signal received; stopping server"; kill -TERM "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true; exit 0' TERM INT

    # Watch for reset.request.json (written by the proxy's FileResetSignaller) and
    # for the server exiting on its own. Either way we exit; the docker restart
    # policy is the orchestrator that brings the container back.
    while true; do
        if [ -f "$reset_file" ]; then
            log "reset requested; stopping server JVM"
            kill -TERM "$server_pid" 2>/dev/null || true
            wait "$server_pid" 2>/dev/null || true
            wipe_world "$data_dir"
            rm -f "$reset_file"
            log "world wiped; exiting for restart"
            exit 0
        fi
        if ! kill -0 "$server_pid" 2>/dev/null; then
            local code=0
            wait "$server_pid" 2>/dev/null || code=$?
            log "server exited on its own (code $code); exiting for restart"
            exit "$code"
        fi
        sleep 1
    done
}

# ---------------------------------------------------------------------------
# proxy mode
# ---------------------------------------------------------------------------

run_proxy() {
    mkdir -p "$PROXY_DIR/plugins/linkedhardcore"

    cat > "$PROXY_DIR/velocity.toml" <<EOF
# Generated by the linked-hardcore entrypoint from environment variables.
config-version = "2.8"
bind = "0.0.0.0:${VELOCITY_PORT}"
motd = "<#09add3>A Linked Hardcore proxy"
show-max-players = 500
online-mode = false
force-key-authentication = false
prevent-client-proxy-connections = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
announce-forge = false
kick-existing-players = false
ping-passthrough = "DISABLED"
sample-players-in-ping = false
enable-player-address-logging = true

[packet-limiter]
interval = 7
packets-per-second = -1
bytes-per-second = -1
decompressed-bytes-per-second = 5242880

[servers]
a = "server-a:25565"
b = "server-b:25565"
try = ["a", "b"]

[forced-hosts]

[advanced]
compression-threshold = 256
compression-level = -1
login-ratelimit = 3000
connection-timeout = 5000
read-timeout = 30000
haproxy-protocol = false
tcp-fast-open = false
bungee-plugin-message-channel = true
show-ping-requests = false
failover-on-unexpected-server-disconnect = true
announce-proxy-commands = true
log-command-executions = false
log-player-connections = true
accepts-transfers = false
enable-reuse-port = false
command-rate-limit = 50
forward-commands-if-rate-limited = true
kick-after-rate-limited-commands = 0
tab-complete-rate-limit = 10
kick-after-rate-limited-tab-completes = 0

[query]
enabled = false
port = ${VELOCITY_PORT}
map = "Linked Hardcore"
show-plugins = false
EOF
    log "wrote $PROXY_DIR/velocity.toml (bind 0.0.0.0:$VELOCITY_PORT)"

    # Velocity 4.x reads the modern-forwarding secret from a file, not inline.
    printf '%s\n' "$FORWARDING_SECRET" > "$PROXY_DIR/forwarding.secret"
    chmod 600 "$PROXY_DIR/forwarding.secret"

    cp -f "$PLUGIN_JAR" "$PROXY_DIR/plugins/velocity-plugin-0.1.0.jar"

    cat > "$PROXY_DIR/plugins/linkedhardcore/config.json" <<EOF
{
  "backendServers": {
    "a": { "serverId": "a", "statusFile": "/lh/server-a/config/linkedhardcore/status.json" },
    "b": { "serverId": "b", "statusFile": "/lh/server-b/config/linkedhardcore/status.json" }
  },
  "statusPollSeconds": 1
}
EOF
    log "wrote $PROXY_DIR/plugins/linkedhardcore/config.json"

    log "starting Velocity proxy (Xmx=$MC_MEMORY)"
    cd "$PROXY_DIR"
    exec java -Xmx"$MC_MEMORY" -jar velocity.jar
}

# ---------------------------------------------------------------------------

case "$MODE" in
    proxy)  run_proxy ;;
    server) run_server ;;
    *)      log "unknown MODE '$MODE' (expected 'proxy' or 'server')"; exit 1 ;;
esac

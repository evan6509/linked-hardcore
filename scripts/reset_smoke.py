#!/usr/bin/env python3
"""Exercise real Fabric startup, idle heartbeat and world reset in a disposable backend.

Requires an already installed, cached MC 26.2 / Fabric 0.19.3 server directory.
No existing world or server configuration is copied or modified. Player arrival
is covered by the unit suite; this smoke models a used world while its JVM is stopped.
"""
import argparse
import gzip
import json
import os
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parent.parent


def executor(function, directory):
    subprocess.run(["bash", "-c", 'source "$1"; SERVER_ID=a; "$2" "$3"', "fixture",
                    str(ROOT / "docker/entrypoint.sh"), function, str(directory)], check=True)


def read_json(path):
    return json.loads(path.read_text())


def seed(directory):
    data = gzip.decompress((directory / "world/data/minecraft/world_gen_settings.dat").read_bytes())
    tag = b"\x04\x00\x04seed"
    if data.count(tag) != 1:
        raise AssertionError("Expected exactly one NBT long seed tag in world_gen_settings.dat")
    offset = data.index(tag) + len(tag)
    return struct.unpack(">q", data[offset:offset + 8])[0]


class Backend:
    def __init__(self, directory, reports):
        self.directory, self.reports = directory, reports
        self.process = self.log = None
        self.boots = 0

    def start(self, expected):
        self.boots += 1
        self.log = (self.reports / f"boot-{self.boots}.log").open("w")
        environment = dict(os.environ, FABRIC_PROXY_SECRET="isolated-fixture-secret", FABRIC_PROXY_HACK_ONLINE_MODE="true")
        self.process = subprocess.Popen(["java", "-Xmx768M", "-jar", "server.jar", "nogui"],
                                        cwd=self.directory, stdin=subprocess.PIPE, stdout=self.log,
                                        stderr=subprocess.STDOUT, text=True, env=environment)
        return self.wait_status(expected)

    def wait_status(self, expected, timeout=90):
        deadline = time.monotonic() + timeout
        path = self.directory / "config/linkedhardcore/status.json"
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError(f"Server exited: see {self.log.name}")
            try:
                status = read_json(path)
                if status["state"] == expected and status["playerCount"] == 0:
                    return status
            except (OSError, ValueError, KeyError):
                pass
            time.sleep(0.25)
        raise TimeoutError(f"No {expected} status: see {self.log.name}")

    def stop(self):
        if self.process is not None and self.process.poll() is None:
            self.process.stdin.write("stop\n")
            self.process.stdin.flush()
            try:
                self.process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=10)
                raise RuntimeError("Fixture JVM did not stop cleanly")
        if self.log:
            self.log.close()
        self.process = self.log = None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, default=ROOT / ".test-server/server-a")
    parser.add_argument("--idle-seconds", type=int, default=65)
    args = parser.parse_args()
    if args.idle_seconds < 61:
        parser.error("--idle-seconds must exceed the former 60-second pause threshold")
    jar = ROOT / "fabric-mod/build/libs/fabric-mod-0.1.0.jar"
    if not jar.is_file() or not (args.runtime / "server.jar").is_file():
        parser.error("Build the mod and provide a cached Fabric runtime with server.jar")
    reports = ROOT / ".test-server/reset-smoke"
    reports.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="linked-hardcore-smoke-") as temporary:
        directory = Path(temporary)
        for name in ("libraries", "versions"):
            shutil.copytree(args.runtime / name, directory / name)
        shutil.copytree(args.runtime / ".fabric/server", directory / ".fabric/server")
        shutil.copy2(args.runtime / "server.jar", directory / "server.jar")
        (directory / "mods").mkdir()
        shutil.copy2(jar, directory / "mods" / jar.name)
        api = list((args.runtime / "mods").glob("fabric-api-*.jar"))
        if len(api) != 1:
            raise RuntimeError("Expected one cached Fabric API jar")
        shutil.copy2(api[0], directory / "mods" / api[0].name)
        executor("ensure_mod_config", directory)
        executor("ensure_eula", directory)
        executor("ensure_server_properties", directory)
        executor("initialize_world_identity", directory)
        # Override the container's fixed port for a loopback-only disposable process.
        with socket.socket() as reserved:
            reserved.bind(("127.0.0.1", 0))
            port = reserved.getsockname()[1]
        properties = directory / "server.properties"
        properties.write_text(properties.read_text().replace("server-port=25565", f"server-port={port}")
                              + "server-ip=127.0.0.1\nlevel-seed=\nview-distance=2\nsimulation-distance=2\n")
        backend = Backend(directory, reports)
        metadata = directory / "config/linkedhardcore"
        try:
            print("Starting disposable backend with an unused world.", flush=True)
            initial = backend.start("ready")
            backend.stop()
            old_seed = seed(directory)
            sentinel = directory / "world/previous-run-sentinel"
            sentinel.write_text("previous run\n")
            world = read_json(metadata / "world-generation.json")
            world["used"] = True  # Isolated fixture models prior play; the JVM is stopped.
            (metadata / "world-generation.json").write_text(json.dumps(world))
            (metadata / "status.json").unlink()
            print("Restarting the used world; checking idle heartbeat for 65+ seconds.", flush=True)
            resumed = backend.start("live")
            assert resumed["generationId"] == initial["generationId"]
            assert resumed["bootId"] != initial["bootId"]
            before = resumed["updatedAt"]
            time.sleep(args.idle_seconds)
            after = backend.wait_status("live")
            assert after["updatedAt"] != before
            assert time.time() - (metadata / "status.json").stat().st_mtime < 5
            assert sentinel.exists() and seed(directory) == old_seed
            print("Idle heartbeat and ordinary-restart preservation passed; requesting a reset.", flush=True)
            request_id = str(uuid.uuid4())
            request = {"schemaVersion": 2, "serverId": "a", "requestId": request_id,
                       "requestedAt": after["updatedAt"], "previousGenerationId": after["generationId"],
                       "previousBootId": after["bootId"]}
            for name in ("reset.pending.json", "reset.request.json"):
                (metadata / name).write_text(json.dumps(request))
            resetting = backend.wait_status("resetting")
            executor("reset_is_safe", directory)
            backend.stop()
            executor("complete_world_reset", directory)
            assert not sentinel.exists() and not (metadata / "status.json").exists()
            assert not (metadata / "reset.request.json").exists()
            assert (metadata / "reset.pending.json").exists()
            regenerated = backend.start("ready")
            assert regenerated["generationId"] == regenerated["completedResetId"] == request_id
            assert regenerated["bootId"] != resetting["bootId"]
            assert regenerated["worldUsed"] is False
            backend.stop()
            new_seed = seed(directory)
            assert new_seed != old_seed
            result = {"passed": True, "idleSeconds": args.idle_seconds, "oldSeed": old_seed,
                      "newSeed": new_seed, "oldGeneration": initial["generationId"], "newGeneration": request_id,
                      "ordinaryRestartPreservedWorld": True, "oldSentinelRemoved": True,
                      "heartbeatStayedFresh": True}
            (reports / "result.json").write_text(json.dumps(result, indent=2) + "\n")
            print(json.dumps(result, indent=2), flush=True)
        finally:
            backend.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

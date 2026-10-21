#!/usr/bin/env python3
"""Template the proxy with a stub JVM; never start a server or read live state."""
import os
from pathlib import Path
import subprocess
import tempfile
import tomllib
import unittest

import yaml

REPO = Path(__file__).resolve().parents[2]


class ProxyConfigTest(unittest.TestCase):
    def render(self, port):
        compose = yaml.safe_load((REPO / "docker-compose.yml").read_text())
        proxy = compose["services"]["proxy"]
        environment = proxy["environment"]
        # Resolve the Compose default-value interpolation used by this service.
        environment = {key: str(value).replace("${VELOCITY_PORT:-25577}", str(port))
                       for key, value in environment.items()}
        with tempfile.TemporaryDirectory(prefix="proxy-config-") as fixture:
            root = Path(fixture)
            (root / "install").mkdir()
            (root / "install/velocity.jar").touch()
            (root / "plugin.jar").touch()
            (root / "bin").mkdir()
            java = root / "bin/java"
            java.write_text("#!/bin/sh\nexit 0\n")
            java.chmod(0o755)
            environment.update(PROXY_INSTALL_DIR=str(root / "install"),
                               PROXY_DIR=str(root / "runtime"),
                               PLUGIN_JAR=str(root / "plugin.jar"),
                               PATH=str(root / "bin") + os.pathsep + os.environ["PATH"])
            subprocess.run(["bash", str(REPO / "docker/entrypoint.sh")],
                           env=os.environ | environment, check=True, capture_output=True)
            config = tomllib.loads((root / "runtime/velocity.toml").read_text())
        mapping = proxy["ports"][0].replace("${VELOCITY_PORT:-25577}", str(port))
        return config, mapping

    def test_default_proxy_authenticates_players(self):
        config, _ = self.render(25577)
        self.assertTrue(config["online-mode"])

    def test_nondefault_host_port_reaches_container_listener(self):
        config, mapping = self.render(25565)
        host, container = mapping.split(":")
        self.assertEqual("25565", host)
        self.assertEqual(int(container), int(config["bind"].rsplit(":", 1)[1]))


if __name__ == "__main__":
    unittest.main()

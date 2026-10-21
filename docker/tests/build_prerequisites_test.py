#!/usr/bin/env python3
"""Check dependencies of the executor-backed tests in the Gradle image stage."""
from pathlib import Path
import re
import unittest


class BuildPrerequisitesTest(unittest.TestCase):
    def test_reset_executor_dependency_is_installed_before_gradle_tests(self):
        dockerfile = (Path(__file__).resolve().parents[2] / "Dockerfile").read_text()
        instructions = dockerfile.replace("\\\n", " ").splitlines()
        in_build = False
        installed = set()
        for instruction in instructions:
            if instruction.startswith("FROM "):
                in_build = instruction.endswith(" AS build")
            if not in_build or not instruction.startswith("RUN "):
                continue
            for command in instruction[4:].split("&&"):
                if re.search(r"\bapt-get\s+install\b", command):
                    installed.update(command.split())
                if "./gradlew" in command:
                    self.assertIn("jq", installed,
                                  "Gradle tests invoke the reset executor, which requires jq")
                    return
        self.fail("Gradle image build step was not found")


if __name__ == "__main__":
    unittest.main()

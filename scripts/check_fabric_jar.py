#!/usr/bin/env python3
"""Installable artifact contract: check a built JAR, including its bundled proxy mod."""
import argparse
import json
from pathlib import Path
import unittest
from zipfile import ZipFile
from io import BytesIO

parser = argparse.ArgumentParser()
parser.add_argument('--target', choices=('26.2', '26.3'), required=True)
parser.add_argument('jar', type=Path)
args = parser.parse_args()
API = {'26.2': '0.156.0+26.2', '26.3': '0.165.0+26.3'}


class InstallableJarTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.jar = ZipFile(args.jar)
        cls.metadata = json.loads(cls.jar.read('fabric.mod.json'))

    @classmethod
    def tearDownClass(cls):
        cls.jar.close()

    def test_minecraft_is_limited_to_built_target(self):
        self.assertEqual(self.metadata['depends']['minecraft'], '~' + args.target)

    def test_target_api_minimum(self):
        self.assertEqual(self.metadata['depends']['fabric-api'], '>=' + API[args.target])

    def test_loader_and_java_minimum(self):
        self.assertEqual(self.metadata['depends']['fabricloader'], '>=0.19.3')
        self.assertEqual(self.metadata['depends']['java'], '>=25')

    def test_server_entrypoint_is_present(self):
        self.assertEqual(self.metadata['environment'], 'server')
        for entrypoint in self.metadata['entrypoints']['server']:
            self.assertIn(entrypoint.replace('.', '/') + '.class', self.jar.namelist())

    def test_bundled_forwarder_is_installable(self):
        nested = self.metadata['jars']
        self.assertEqual(len(nested), 1)
        with ZipFile(BytesIO(self.jar.read(nested[0]['file']))) as proxy:
            metadata = json.loads(proxy.read('fabric.mod.json'))
        self.assertEqual(metadata['id'], 'fabricproxy-lite')
        self.assertEqual(metadata['version'], '2.12.0')
        self.assertEqual(metadata['depends']['minecraft'], '>=26.1')


if __name__ == '__main__':
    unittest.main(argv=[__file__])

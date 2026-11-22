"""Channel guard tests plus optional verification with real signed APK fixtures.

For real APKs set UPDATE_APK, PREVIOUS_APK and ANDROID_BUILD_TOOLS.
UPDATE_CHANNEL / UPDATE_VERSION / UPDATE_CODE default to legacy / 0.11.0-local / 11.
"""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


SCRIPT = Path(__file__).with_name('prepare-update.py')
spec = importlib.util.spec_from_file_location('prepare_update', SCRIPT)
prepare_update = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare_update)


class PrepareUpdateChannelTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.output = self.root / 'manifest.json'
        self.previous = self.root / 'previous.apk'
        self.previous.write_bytes(b'previous package')
        self.signer = 'a' * 64

    def prepare(self, package, version, channel=None, previous_package=None,
                code=32, previous_code=31, signer=None, tag=None):
        apk = self.root / f'hongguotv-{version}-android8.apk'
        apk.write_bytes(b'candidate package bytes')
        command = ['--apk', str(apk), '--previous-apk', str(self.previous),
                   '--build-tools', str(self.root / 'build-tools'),
                   '--tag', tag or f'tv-v{version}', '--output', str(self.output)]
        if channel is not None:
            command += ['--channel', channel]
        metadata = [(package, code, version, 26, [signer or self.signer]),
                    (previous_package or package, previous_code, 'previous', 26, [self.signer])]
        # Only APK inspection and git are stubbed here. Channel/package/signer/version
        # guards and the written manifest run through the real command entry point.
        with mock.patch.object(prepare_update, 'metadata', side_effect=metadata), \
                mock.patch.object(prepare_update.subprocess, 'check_output', return_value='b' * 40), \
                contextlib.redirect_stdout(io.StringIO()):
            prepare_update.main(command)
        return json.loads(self.output.read_text())

    def test_legacy_is_default_and_keeps_the_existing_manifest_schema(self):
        manifest = self.prepare('com.hongguotv.nativeapp', '0.14.17-local')
        self.assertEqual('com.hongguotv.nativeapp', manifest['packageName'])
        self.assertEqual(1, manifest['schema'])
        self.assertEqual(32, manifest['versionCode'])
        self.assertNotIn('channel', manifest)

    def test_playbackfix_requires_explicit_channel_and_writes_its_actual_package(self):
        with self.assertRaisesRegex(ValueError, 'legacy channel'):
            self.prepare('com.hongguotv.nativeapp.playbackfix', '0.14.17-playbackfix')
        self.assertFalse(self.output.exists())
        manifest = self.prepare('com.hongguotv.nativeapp.playbackfix', '0.14.17-playbackfix', 'playbackfix')
        self.assertEqual('com.hongguotv.nativeapp.playbackfix', manifest['packageName'])
        self.assertEqual('tv-v0.14.17-playbackfix', manifest['tag'])
        self.assertEqual('hongguotv-0.14.17-playbackfix-android8.apk', manifest['apk']['name'])
        self.assertEqual(hashlib.sha256(b'candidate package bytes').hexdigest(), manifest['apk']['sha256'])

    def test_channel_cannot_use_a_previous_apk_from_the_other_package(self):
        for package, previous, channel in (
                ('com.hongguotv.nativeapp.playbackfix', 'com.hongguotv.nativeapp', 'playbackfix'),
                ('com.hongguotv.nativeapp', 'com.hongguotv.nativeapp.playbackfix', 'legacy')):
            with self.subTest(channel=channel), self.assertRaisesRegex(ValueError, 'package name'):
                self.prepare(package, '0.14.17-local', channel, previous_package=previous)
            self.assertFalse(self.output.exists())

    def test_both_channels_reject_a_changed_signer(self):
        for channel, package in prepare_update.CHANNEL_PACKAGES.items():
            with self.subTest(channel=channel), self.assertRaisesRegex(ValueError, 'signing certificate'):
                self.prepare(package, '0.14.17-local', channel, signer='c' * 64)
            self.assertFalse(self.output.exists())

    def test_both_channels_reject_equal_and_lower_versions(self):
        for channel, package in prepare_update.CHANNEL_PACKAGES.items():
            for code in (30, 31):
                with self.subTest(channel=channel, code=code), self.assertRaisesRegex(ValueError, 'versionCode'):
                    self.prepare(package, '0.14.17-local', channel, code=code)
                self.assertFalse(self.output.exists())

    def test_unsafe_tag_never_writes_a_manifest(self):
        with self.assertRaisesRegex(ValueError, 'Invalid versionName or tag'):
            self.prepare('com.hongguotv.nativeapp.playbackfix', '0.14.17-playbackfix',
                         'playbackfix', tag='tv-v../other')
        self.assertFalse(self.output.exists())


@unittest.skipUnless(all(os.environ.get(k) for k in ('UPDATE_APK', 'PREVIOUS_APK', 'ANDROID_BUILD_TOOLS')),
                     'Real APK fixtures were not supplied')
class PrepareUpdateTest(unittest.TestCase):
    def test_manifest_matches_verified_apk_and_rejects_rollback_or_unsafe_tag(self):
        apk = Path(os.environ['UPDATE_APK']).resolve()
        previous = Path(os.environ['PREVIOUS_APK']).resolve()
        channel = os.environ.get('UPDATE_CHANNEL', 'legacy')
        version = os.environ.get('UPDATE_VERSION', '0.11.0-local')
        code = int(os.environ.get('UPDATE_CODE', '11'))
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / 'update.json'
            command = [sys.executable, str(Path(__file__).with_name('prepare-update.py')),
                       '--apk', str(apk), '--previous-apk', str(previous),
                       '--build-tools', os.environ['ANDROID_BUILD_TOOLS'],
                       '--channel', channel, '--tag', f'tv-v{version}', '--output', str(output)]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            manifest = json.loads(output.read_text())
            package = 'com.hongguotv.nativeapp.playbackfix' if channel == 'playbackfix' else 'com.hongguotv.nativeapp'
            self.assertEqual((package, code, version, 26),
                             tuple(manifest[k] for k in ('packageName', 'versionCode', 'versionName', 'minSdk')))
            self.assertEqual(apk.name, manifest['apk']['name'])
            self.assertEqual(apk.stat().st_size, manifest['apk']['size'])
            self.assertEqual(hashlib.sha256(apk.read_bytes()).hexdigest(), manifest['apk']['sha256'])
            output.unlink()
            command[command.index('--previous-apk') + 1] = str(apk)
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('versionCode', result.stderr)
            self.assertFalse(output.exists())
            command[command.index('--previous-apk') + 1] = str(previous)
            command[command.index('--tag') + 1] = 'tv-v../other'
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()

"""Release transaction regressions using a stateful fake GitHub API; no remote writes."""
import base64
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock
from urllib.parse import parse_qs, urlsplit

spec = importlib.util.spec_from_file_location('publish_playbackfix', Path(__file__).with_name('publish-playbackfix.py'))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)
p = publisher


def sha(value):
    return hashlib.sha1(json.dumps(value, sort_keys=True).encode()).hexdigest()


class FakeGitHub:
    def __init__(self):
        self.events = []
        self.release = None
        self.assets = {}
        self.downloads = {}
        self.head = None
        self.commits = {}
        self.trees = {}
        self.blobs = {}
        self.tag_sha = None
        self.latest = {'id': 1, 'tag_name': 'tv-v0.14.15'}
        self.bad_asset = None
        self.bad_public = None
        self.fail_upload = None
        self.change_latest = False
        self.ref_race = False
        self.runs = [{'id': 45040475551, 'head_sha': p.SOURCE_COMMIT, 'created_at': '2026-11-22T00:00:00Z',
                      'path': p.WORKFLOW, 'repository': {'full_name': p.REPOSITORY},
                      'status': 'completed', 'conclusion': 'success'}]
        self.remote_tree = p.SOURCE_TREE

    def seed_updates(self, manifest):
        legacy_sha = sha('legacy file')
        self.blobs[legacy_sha] = b'legacy file stays untouched\n'
        entries = [{'path': 'update.json', 'mode': '100644', 'type': 'blob', 'sha': legacy_sha}]
        if manifest is not None:
            raw = p.json_bytes(manifest)
            blob_sha = sha(raw.decode())
            self.blobs[blob_sha] = raw
            entries.append({'path': p.MANIFEST_NAME, 'mode': '100644', 'type': 'blob', 'sha': blob_sha})
        tree_sha = sha(entries)
        self.trees[tree_sha] = entries
        self.head = sha({'tree': tree_sha, 'seed': True})
        self.commits[self.head] = {'tree': {'sha': tree_sha}, 'parents': []}

    def current_manifest(self):
        if self.head is None:
            return None
        entries = self.trees[self.commits[self.head]['tree']['sha']]
        matches = [e for e in entries if e['path'] == p.MANIFEST_NAME]
        return json.loads(self.blobs[matches[0]['sha']]) if matches else None

    def public_digest(self, name, size):
        self.events.append(('PUBLIC', name, None))
        data = self.downloads[name]
        return len(data), '0' * 64 if name == self.bad_public else hashlib.sha256(data).hexdigest()

    def request(self, method, path, data=None, binary=None, upload=False):
        assert path.startswith(p.API_PREFIX + '/')
        suffix = path[len(p.API_PREFIX):]
        self.events.append((method, suffix, copy.deepcopy(data)))
        route = suffix.split('?')[0]
        if method == 'GET':
            if route == '/git/commits/' + p.SOURCE_COMMIT:
                return {'sha': p.SOURCE_COMMIT, 'tree': {'sha': self.remote_tree}}
            if route == '/actions/workflows/kotlin-native.yml/runs':
                query = parse_qs(urlsplit(suffix).query)
                assert query['head_sha'] == [p.SOURCE_COMMIT]
                assert 'status' not in query  # A newer queued/failed run must not be hidden.
                return {'workflow_runs': copy.deepcopy(self.runs)}
            if route == '/git/ref/heads/updates':
                if self.head is None:
                    raise p.ApiError(404, route)
                return {'ref': 'refs/heads/updates', 'object': {'type': 'commit', 'sha': self.head}}
            if route.startswith('/git/commits/'):
                return copy.deepcopy(self.commits[route.rsplit('/', 1)[1]])
            if route.startswith('/git/trees/'):
                return {'tree': copy.deepcopy(self.trees[route.rsplit('/', 1)[1]]), 'truncated': False}
            if route.startswith('/git/blobs/'):
                raw = self.blobs[route.rsplit('/', 1)[1]]
                return {'encoding': 'base64', 'size': len(raw), 'content': base64.b64encode(raw).decode()}
            if route == '/git/ref/tags/' + p.TAG:
                if self.tag_sha is None:
                    raise p.ApiError(404, route)
                return {'object': {'type': 'commit', 'sha': self.tag_sha}}
            if route == '/releases/latest':
                return copy.deepcopy(self.latest)
            if route == '/releases/tags/' + p.TAG:
                if self.release is None or self.release['draft']:
                    raise p.ApiError(404, route)
                return copy.deepcopy(self.release)
            if route == '/releases':
                return [] if self.release is None else [copy.deepcopy(self.release)]
            if route == '/releases/30/assets':
                return copy.deepcopy(list(self.assets.values()))
            if route.startswith('/releases/assets/'):
                asset_id = int(route.rsplit('/', 1)[1])
                item = next(a for a in self.assets.values() if a['id'] == asset_id)
                result = copy.deepcopy(item)
                if result['name'] == self.bad_asset:
                    result['digest'] = 'sha256:' + '0' * 64
                return result
        if method == 'POST' and route == '/releases':
            assert data['make_latest'] == 'false'
            assert data['draft'] is True
            self.release = dict(data, id=30)
            return copy.deepcopy(self.release)
        if method == 'POST' and route == '/releases/30/assets':
            assert upload is True
            assert self.release['draft'] is True
            name = parse_qs(urlsplit(suffix).query)['name'][0]
            if name == self.fail_upload:
                self.fail_upload = None
                raise p.ApiError(503, 'upload')
            assert name not in self.assets  # No replacing/clobbering existing assets.
            self.downloads[name] = binary
            item = {'id': len(self.assets) + 100, 'name': name, 'state': 'uploaded', 'size': len(binary),
                    'digest': 'sha256:' + hashlib.sha256(binary).hexdigest(),
                    'browser_download_url': p.PUBLIC_PREFIX + name}
            self.assets[name] = item
            return copy.deepcopy(item)
        if method == 'PATCH' and route == '/releases/30':
            assert data == {'draft': False, 'make_latest': 'false'}
            self.release.update(data)
            self.tag_sha = p.SOURCE_COMMIT
            if self.change_latest:
                self.latest = {'id': 30, 'tag_name': p.TAG}
            return copy.deepcopy(self.release)
        if method == 'POST' and route == '/git/trees':
            entries = copy.deepcopy(self.trees[data['base_tree']]) if 'base_tree' in data else []
            for item in data['tree']:
                assert item['path'] == p.MANIFEST_NAME
                raw = item['content'].encode()
                blob_sha = sha(raw.decode())
                self.blobs[blob_sha] = raw
                entries = [e for e in entries if e['path'] != item['path']]
                entries.append({'path': item['path'], 'mode': item['mode'], 'type': 'blob', 'sha': blob_sha})
            tree_sha = sha(entries)
            self.trees[tree_sha] = entries
            return {'sha': tree_sha}
        if method == 'POST' and route == '/git/commits':
            commit_sha = sha(data)
            self.commits[commit_sha] = {'tree': {'sha': data['tree']}, 'parents': data['parents']}
            return {'sha': commit_sha}
        if method == 'POST' and route == '/git/refs':
            assert data['ref'] == 'refs/heads/updates'
            if self.head is not None:
                raise p.ApiError(422, route)
            assert self.commits[data['sha']]['parents'] == []
            self.head = data['sha']
            return {'object': {'sha': self.head}}
        if method == 'PATCH' and route == '/git/refs/heads/updates':
            assert data['force'] is False
            if self.ref_race:
                self.ref_race = False
                advanced = p.candidate_manifest()
                advanced['versionCode'] += 1
                self.seed_updates(advanced)
                raise p.ApiError(422, 'concurrent ref update')
            if self.commits[data['sha']]['parents'] != [self.head]:
                raise p.ApiError(409, route)
            self.head = data['sha']
            return {'object': {'sha': self.head}}
        raise AssertionError((method, route))


class PublisherTransactionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.assets = {}
        for name in p.ASSET_NAMES:
            path = self.root / name
            path.write_bytes(p.json_bytes(p.candidate_manifest()) if name == p.MANIFEST_NAME else name.encode())
            size, digest = p.digest_file(path)
            self.assets[name] = {'path': path, 'size': size, 'sha256': digest}
        self.api = FakeGitHub()

    def assert_no_manifest_write(self):
        self.assertFalse(any(method in ('POST', 'PATCH') and route.startswith('/git/')
                             for method, route, _ in self.api.events))

    def test_draft_verified_upload_public_readback_then_orphan_update_branch(self):
        result = p.publish(self.api, self.assets)
        events = self.api.events
        published = next(i for i, e in enumerate(events) if e[:2] == ('PATCH', '/releases/30'))
        metadata_reads = [i for i, e in enumerate(events) if e[0] == 'GET' and e[1].startswith('/releases/assets/')]
        public_reads = [i for i, e in enumerate(events) if e[0] == 'PUBLIC']
        update_writes = [i for i, e in enumerate(events) if e[0] == 'POST' and e[1].startswith('/git/')]
        self.assertEqual(4, len(metadata_reads))
        self.assertLess(max(metadata_reads), published)
        self.assertEqual(4, len(public_reads))
        self.assertLess(published, min(public_reads))
        self.assertLess(max(public_reads), min(update_writes))
        self.assertEqual(p.candidate_manifest(), self.api.current_manifest())
        self.assertEqual({'id': 1, 'tag': 'tv-v0.14.15'}, result['previousLatest'])
        self.assertTrue(result['latestUnchanged'])

    def test_asset_digest_mismatch_stays_draft_without_publication_or_manifest(self):
        self.api.bad_asset = p.APK_NAME
        with self.assertRaisesRegex(ValueError, 'digest mismatch'):
            p.publish(self.api, self.assets)
        self.assertTrue(self.api.release['draft'])
        self.assertFalse(any(e[0] == 'PUBLIC' for e in self.api.events))
        self.assert_no_manifest_write()

    def test_failed_public_readback_never_promotes_manifest(self):
        self.api.bad_public = p.APK_NAME
        with self.assertRaisesRegex(ValueError, 'public asset digest mismatch'):
            p.publish(self.api, self.assets)
        self.assertFalse(self.api.release['draft'])
        self.assert_no_manifest_write()

    def test_rollback_and_same_version_mutation_refused_before_release_mutation(self):
        for change in ('newer', 'different-apk'):
            with self.subTest(change=change):
                self.api = FakeGitHub()
                current = p.candidate_manifest()
                if change == 'newer':
                    current['versionCode'] += 1
                else:
                    current['apk']['sha256'] = '0' * 64
                self.api.seed_updates(current)
                with self.assertRaisesRegex(ValueError, 'rollback|Same-version'):
                    p.publish(self.api, self.assets)
                self.assertFalse(any(e[0] in ('POST', 'PATCH') for e in self.api.events))

    def test_published_same_release_is_idempotent_without_upload_or_mutation(self):
        first = p.publish(self.api, self.assets)
        self.api.events.clear()
        second = p.publish(self.api, self.assets)
        self.assertEqual(first, second)
        self.assertEqual(4, sum(e[0] == 'PUBLIC' for e in self.api.events))
        self.assertFalse(any(e[0] in ('POST', 'PATCH', 'DELETE') for e in self.api.events))

    def test_incomplete_draft_resumes_without_replacing_uploaded_assets(self):
        self.api.fail_upload = p.MANIFEST_NAME
        with self.assertRaises(p.ApiError):
            p.publish(self.api, self.assets)
        self.assertTrue(self.api.release['draft'])
        self.assertEqual({p.APK_NAME, p.SOURCE_NAME}, set(self.api.assets))
        self.api.events.clear()
        p.publish(self.api, self.assets)
        uploads = [e[1] for e in self.api.events if e[0] == 'POST' and '/assets?' in e[1]]
        self.assertEqual(2, len(uploads))
        self.assertFalse(any(p.APK_NAME in path or p.SOURCE_NAME in path for path in uploads))

    def test_latest_change_stops_before_update_manifest(self):
        self.api.change_latest = True
        with self.assertRaisesRegex(ValueError, 'latest release|Latest release'):
            p.publish(self.api, self.assets)
        self.assert_no_manifest_write()

    def test_existing_tag_and_release_source_cannot_be_retargeted(self):
        self.api.tag_sha = 'a' * 40
        with self.assertRaisesRegex(ValueError, 'different source'):
            p.publish(self.api, self.assets)
        self.assertFalse(any(e[0] in ('POST', 'PATCH') for e in self.api.events))
        self.api = FakeGitHub()
        p.publish(self.api, self.assets)
        self.api.release['target_commitish'] = 'main'
        self.api.events.clear()
        with self.assertRaisesRegex(ValueError, 'no clobber'):
            p.publish(self.api, self.assets)
        self.assertFalse(any(e[0] in ('POST', 'PATCH') for e in self.api.events))

    def test_nonforced_branch_race_rechecks_newer_version_and_preserves_it(self):
        older = p.candidate_manifest()
        older['versionCode'] -= 1
        self.api.seed_updates(older)
        self.api.ref_race = True
        with self.assertRaisesRegex(ValueError, 'rollback'):
            p.publish(self.api, self.assets)
        self.assertEqual(p.VERSION_CODE + 1, self.api.current_manifest()['versionCode'])
        updates = [e for e in self.api.events if e[:2] == ('PATCH', '/git/refs/heads/updates')]
        self.assertEqual(1, len(updates))
        self.assertIs(updates[0][2]['force'], False)

    def test_existing_updates_tree_retains_legacy_file(self):
        self.api.seed_updates(None)
        prior_head = self.api.head
        p.publish(self.api, self.assets)
        commit = self.api.commits[self.api.head]
        self.assertEqual([prior_head], commit['parents'])
        legacy = next(e for e in self.api.trees[commit['tree']['sha']] if e['path'] == 'update.json')
        self.assertEqual(b'legacy file stays untouched\n', self.api.blobs[legacy['sha']])


class PublisherValidationTest(unittest.TestCase):
    def test_latest_source_ci_cannot_be_substituted_by_older_success_or_another_tree(self):
        client = FakeGitHub()
        with mock.patch.object(p.subprocess, 'check_output', side_effect=[p.SOURCE_COMMIT, p.SOURCE_TREE]):
            self.assertEqual(45040475551, p.verify_source_and_ci(client, Path('/trusted')))
        failed = dict(client.runs[0], id=45040475552, created_at='2026-11-23T00:00:00Z',
                      status='in_progress', conclusion=None)
        client.runs.append(failed)
        with mock.patch.object(p.subprocess, 'check_output', side_effect=[p.SOURCE_COMMIT, p.SOURCE_TREE]), \
                self.assertRaisesRegex(ValueError, 'Latest Android workflow'):
            p.verify_source_and_ci(client, Path('/trusted'))
        client.remote_tree = '0' * 40
        with mock.patch.object(p.subprocess, 'check_output', side_effect=[p.SOURCE_COMMIT, p.SOURCE_TREE]), \
                self.assertRaisesRegex(ValueError, 'Remote source commit/tree'):
            p.verify_source_and_ci(client, Path('/trusted'))

    def test_apk_requires_exact_hash_package_certificate_and_v2(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / p.APK_NAME
            apk.write_bytes(b'fixture')
            signature = ('Verified using v2 scheme (APK Signature Scheme v2): true\n'
                         'Signer #1 certificate SHA-256 digest: ' + p.CERT_SHA256 + '\n')
            badging = (f"package: name='{p.PACKAGE}' versionCode='{p.VERSION_CODE}' versionName='{p.VERSION}'\n"
                       f"sdkVersion:'{p.MIN_SDK}'\n")
            for invalid in ('hash', 'package', 'certificate', 'v2'):
                with self.subTest(invalid=invalid):
                    digest = (p.APK_SIZE, '0' * 64 if invalid == 'hash' else p.APK_SHA256)
                    sig = signature.replace(p.CERT_SHA256, '0' * 64) if invalid == 'certificate' else signature
                    if invalid == 'v2':
                        sig = sig.replace(': true', ': false')
                    badge = badging.replace(p.PACKAGE, 'com.hongguotv.nativeapp') if invalid == 'package' else badging
                    with mock.patch.object(p, 'digest_file', return_value=digest), \
                            mock.patch.object(p.subprocess, 'check_output', side_effect=[sig, badge]), \
                            self.assertRaises(ValueError):
                        p.verify_apk(apk, Path('/tools'))
            with mock.patch.object(p, 'digest_file', return_value=(p.APK_SIZE, p.APK_SHA256)), \
                    mock.patch.object(p.subprocess, 'check_output', side_effect=[signature, badging]):
                p.verify_apk(apk, Path('/tools'))

    def test_archive_uses_fixed_source_and_validate_only_cannot_publish(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            input_dir, output = root / 'input', root / 'release'
            input_dir.mkdir()
            raw = b'reviewed fixture bytes'
            (input_dir / p.APK_NAME).write_bytes(raw)
            commands = []
            def archive(command, **kwargs):
                commands.append(command)
                Path(next(x for x in command if x.startswith('--output=')).split('=', 1)[1]).write_bytes(b'fixed archive')
            with mock.patch.object(p, 'verify_apk'), mock.patch.object(p, 'verify_source_and_ci', return_value=42), \
                    mock.patch.object(p.subprocess, 'run', side_effect=archive), \
                    mock.patch.object(p, 'APK_SIZE', len(raw)), \
                    mock.patch.object(p, 'APK_SHA256', hashlib.sha256(raw).hexdigest()):
                assets = p.prepare(input_dir, Path('/tools'), output, root, FakeGitHub())
            self.assertEqual(p.SOURCE_COMMIT, commands[0][-1])
            self.assertNotIn('HEAD', commands[0])
            self.assertEqual(p.SOURCE_COMMIT, json.loads((output / p.MANIFEST_NAME).read_text())['sourceCommit'])
            with mock.patch.dict(os.environ, {}, clear=True), mock.patch.object(p, 'prepare', return_value=assets), \
                    mock.patch.object(p, 'publish') as publish, mock.patch('sys.stdout', new=io.StringIO()):
                p.main(['--input', str(input_dir), '--build-tools', '/tools', '--output', str(output), '--validate-only'])
            publish.assert_not_called()

    def test_public_download_uses_no_authorization_and_api_hosts_are_fixed(self):
        client = p.GitHub('not-a-real-token')
        client.auth_opener = mock.Mock()
        client.auth_opener.open.return_value = io.BytesIO(b'{}')
        client.request('GET', p.API_PREFIX + '/releases/latest')
        authenticated = client.auth_opener.open.call_args.args[0]
        self.assertEqual('api.github.com', urlsplit(authenticated.full_url).hostname)
        self.assertEqual('Bearer not-a-real-token', authenticated.get_header('Authorization'))
        client.public_opener = mock.Mock()
        client.public_opener.open.return_value = io.BytesIO(b'apk')
        self.assertEqual((3, hashlib.sha256(b'apk').hexdigest()), client.public_digest(p.APK_NAME, 3))
        public = client.public_opener.open.call_args.args[0]
        self.assertEqual(p.PUBLIC_PREFIX + p.APK_NAME, public.full_url)
        self.assertIsNone(public.get_header('Authorization'))
        with self.assertRaises(ValueError):
            client.request('POST', '/repos/elsewhere/repo/releases', data={})
        for url in ('http://github.com/unsafe', 'https://github-releases.githubusercontent.com/unsupported'):
            with self.subTest(url=url), self.assertRaises(ValueError):
                p.PublicRedirect().redirect_request(public, None, 302, '', {}, url)


if __name__ == '__main__':
    unittest.main()

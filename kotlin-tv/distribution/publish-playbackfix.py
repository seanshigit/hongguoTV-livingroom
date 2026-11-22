#!/usr/bin/env python3
"""Publish one reviewed prebuilt playbackfix release; never build, sign, or replace assets.

The candidate constants below are deliberately not CLI inputs. A later release requires
another reviewed change. GitHub mutations occur only after local/source/CI validation.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

REPOSITORY = 'seanshigit/hongguoTV-livingroom'
PACKAGE = 'com.hongguotv.nativeapp.playbackfix'
VERSION = '0.14.18-playbackfix'
VERSION_CODE = 33
MIN_SDK = 26
TAG = 'tv-v' + VERSION
APK_NAME = 'hongguotv-' + VERSION + '-android8.apk'
APK_SIZE = 9540418
APK_SHA256 = '3983497cf74adf58832c41f6f58844b79496b4f3be47cd81e5722c8755a8eb10'
CERT_SHA256 = 'dd624bea3c779236a29c418b7a429fdc9bf1252545f9343d264b00e09802f2df'
SOURCE_COMMIT = '823eb97916f3878cd3402e0e006d8cce909114dd'
SOURCE_TREE = '6e8f4aa8be6bb39f829ec2eb5e31ac0c6bfffea1'
WORKFLOW = '.github/workflows/kotlin-native.yml'
SOURCE_NAME = 'hongguotv-' + VERSION + '-source.zip'
MANIFEST_NAME = 'playbackfix.json'
ASSET_NAMES = (APK_NAME, SOURCE_NAME, MANIFEST_NAME, 'SHA256SUMS')
API_PREFIX = '/repos/' + REPOSITORY
PUBLIC_PREFIX = 'https://github.com/' + REPOSITORY + '/releases/download/' + TAG + '/'
NOTES = ('修复下一集预加载失败后的连播恢复；为仅返回 ByteVC2 的分集追加一次有界兼容取流。'
         '此版本使用独立 playbackfix 包名、签名与更新渠道。已安装 playbackfix 版本可同签名覆盖安装；'
         '原版签名已丢失，原包用户须手动安装独立版本，本机记录不会自动跨包迁移。')
RELEASE_BODY = ('## 连播修复与独立更新渠道\n\n' + NOTES + '\n\n'
                '本次发布已验证的预构建 APK，发布任务不重新构建或签名。\n\n'
                '233 项核心测试与真实 ByteVC2 → HEVC 兼容回退验证已通过；尚未完成电视实机连播验收。'
                '0.14.16-playbackfix 需手动覆盖安装本版一次，随后使用独立更新渠道。\n\n'
                f'[本版说明](https://github.com/{REPOSITORY}/blob/{SOURCE_COMMIT}/kotlin-tv/RELEASE_0.14.18-playbackfix.md)'
                f' · [迁移步骤](https://github.com/{REPOSITORY}/blob/main/kotlin-tv/MIGRATION_TO_PLAYBACKFIX.md)\n\n'
                f'- Package: `{PACKAGE}`\n- Version: `{VERSION}` / `{VERSION_CODE}`\n'
                f'- Minimum SDK: `{MIN_SDK}`\n- Source commit: `{SOURCE_COMMIT}`\n'
                f'- Source tree: `{SOURCE_TREE}`\n- APK SHA-256: `{APK_SHA256}`\n'
                f'- Signing certificate SHA-256: `{CERT_SHA256}`\n\n'
                '更新清单：`updates:playbackfix.json`。此 Release 不设为 latest，原渠道保持独立。\n')


class ApiError(RuntimeError):
    def __init__(self, status, operation):
        self.status = status
        super().__init__(f'GitHub HTTP {status}: {operation}')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Authenticated requests must never forward the token to a redirected host.
        return None


class PublicRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urllib.parse.urlsplit(newurl)
        if (parsed.scheme != 'https' or parsed.username or parsed.password or parsed.port not in (None, 443)
                or parsed.hostname not in {'github.com', 'release-assets.githubusercontent.com',
                                           'objects.githubusercontent.com'}):
            raise ValueError('Public asset download redirected outside GitHub HTTPS')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class GitHub:
    def __init__(self, token=None):
        self.token = token
        self.auth_opener = urllib.request.build_opener(NoRedirect())
        self.public_opener = urllib.request.build_opener(PublicRedirect())

    def request(self, method, path, data=None, binary=None, upload=False):
        if not path.startswith(API_PREFIX + '/') or '\\' in path or '..' in path:
            raise ValueError('API path is outside the fixed repository')
        host = 'https://uploads.github.com' if upload else 'https://api.github.com'
        if upload and not re.fullmatch(API_PREFIX + r'/releases/[0-9]+/assets\?name=[A-Za-z0-9._%-]+', path):
            raise ValueError('Unexpected upload endpoint')
        headers = {'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28',
                   'User-Agent': 'hongguotv-playbackfix-publisher'}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        payload = binary if binary is not None else (json.dumps(data).encode() if data is not None else None)
        if payload is not None:
            headers['Content-Type'] = 'application/octet-stream' if binary is not None else 'application/json'
        request = urllib.request.Request(host + path, data=payload, headers=headers, method=method)
        try:
            with self.auth_opener.open(request, timeout=90) as response:
                raw = response.read(4 * 1024 * 1024 + 1)
                if len(raw) > 4 * 1024 * 1024:
                    raise ValueError('GitHub JSON response exceeds limit')
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            # Do not print response bodies, headers, credentials, or redirected URLs.
            raise ApiError(error.code, method + ' ' + path.split('?')[0]) from None
        except urllib.error.URLError:
            raise RuntimeError('GitHub API transport failed') from None

    def public_digest(self, name, size):
        if name not in ASSET_NAMES:
            raise ValueError('Unexpected public asset name')
        request = urllib.request.Request(PUBLIC_PREFIX + name,
                                         headers={'User-Agent': 'hongguotv-playbackfix-publisher'})
        # This opener and request never receive Authorization, cookies, or the API token.
        for attempt in range(3):
            try:
                with self.public_opener.open(request, timeout=90) as response:
                    digest = hashlib.sha256()
                    count = 0
                    while True:
                        block = response.read(min(1024 * 1024, size + 1 - count))
                        if not block:
                            break
                        count += len(block)
                        if count > size:
                            raise ValueError('Public asset exceeds its expected size: ' + name)
                        digest.update(block)
                    return count, digest.hexdigest()
            except urllib.error.HTTPError as error:
                if error.code not in (404, 429, 500, 502, 503, 504) or attempt == 2:
                    raise ApiError(error.code, 'anonymous public download ' + name) from None
            except urllib.error.URLError:
                if attempt == 2:
                    raise RuntimeError('Anonymous public asset download failed: ' + name) from None
            time.sleep(2 * (attempt + 1))


def api(api_client, method, suffix, data=None):
    return api_client.request(method, API_PREFIX + suffix, data=data)


def optional(api_client, suffix):
    try:
        return api(api_client, 'GET', suffix)
    except ApiError as error:
        if error.status == 404:
            return None
        raise


def object_sha(value):
    if not isinstance(value, str) or not re.fullmatch('[0-9a-f]{40}', value):
        raise ValueError('GitHub returned an invalid Git object SHA')
    return value


def object_id(value):
    if type(value) is not int or value <= 0:
        raise ValueError('GitHub returned an invalid numeric object ID')
    return value


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def digest_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return path.stat().st_size, digest.hexdigest()


def candidate_manifest():
    return {'schema': 1, 'packageName': PACKAGE, 'versionCode': VERSION_CODE, 'versionName': VERSION,
            'minSdk': MIN_SDK, 'tag': TAG, 'sourceCommit': SOURCE_COMMIT,
            'apk': {'name': APK_NAME, 'size': APK_SIZE, 'sha256': APK_SHA256}, 'notes': NOTES}


def verify_apk(apk, build_tools):
    if apk.is_symlink() or not apk.is_file() or apk.name != APK_NAME:
        raise ValueError('Expected a regular candidate APK with the fixed filename')
    if digest_file(apk) != (APK_SIZE, APK_SHA256):
        raise ValueError('Candidate APK size or SHA-256 does not match the reviewed binary')
    signature = subprocess.check_output([str(build_tools / 'apksigner'), 'verify', '--verbose',
                                         '--print-certs', str(apk)], text=True, stderr=subprocess.STDOUT)
    signers = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$', signature, re.M)
    if signers != [CERT_SHA256] or not re.search(r'^Verified using v2 scheme .*: true$', signature, re.M):
        raise ValueError('APK must verify with v2 and exactly the reviewed signing certificate')
    badging = subprocess.check_output([str(build_tools / 'aapt'), 'dump', 'badging', str(apk)],
                                     text=True, stderr=subprocess.STDOUT)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    sdk = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    if not package or not sdk or (package[1], int(package[2]), package[3], int(sdk[1])) != (
            PACKAGE, VERSION_CODE, VERSION, MIN_SDK):
        raise ValueError('APK package, version, or minSdk differs from the reviewed candidate')


def verify_source_and_ci(api_client, repository):
    actual_commit = subprocess.check_output(['git', '-C', str(repository), 'rev-parse', '--verify',
                                            SOURCE_COMMIT + '^{commit}'], text=True).strip()
    actual_tree = subprocess.check_output(['git', '-C', str(repository), 'rev-parse', '--verify',
                                          SOURCE_COMMIT + '^{tree}'], text=True).strip()
    if (actual_commit, actual_tree) != (SOURCE_COMMIT, SOURCE_TREE):
        raise ValueError('Local source commit/tree does not match the reviewed source')
    remote = api(api_client, 'GET', '/git/commits/' + SOURCE_COMMIT)
    if remote.get('sha') != SOURCE_COMMIT or remote.get('tree', {}).get('sha') != SOURCE_TREE:
        raise ValueError('Remote source commit/tree does not match the reviewed source')
    runs = []
    for page in range(1, 11):
        result = api(api_client, 'GET', '/actions/workflows/kotlin-native.yml/runs?' +
                     urllib.parse.urlencode({'head_sha': SOURCE_COMMIT, 'per_page': 100, 'page': page}))
        batch = result.get('workflow_runs', [])
        runs.extend(batch)
        if len(batch) < 100:
            break
    else:
        raise ValueError('Too many source workflow runs to establish the latest run')
    if not runs or any(run.get('head_sha') != SOURCE_COMMIT for run in runs):
        raise ValueError('No unambiguous Android workflow run exists for the fixed source commit')
    latest = max(runs, key=lambda run: (run.get('created_at', ''), object_id(run.get('id')),
                                       run.get('run_attempt', 1)))
    if (latest.get('path', '').split('@')[0] != WORKFLOW
            or latest.get('repository', {}).get('full_name') != REPOSITORY
            or latest.get('status') != 'completed' or latest.get('conclusion') != 'success'):
        raise ValueError('Latest Android workflow for the fixed source is not completed successfully')
    return object_id(latest['id'])


def prepare(input_dir, build_tools, output, repository, api_client):
    apk = input_dir / APK_NAME
    verify_apk(apk, build_tools)
    ci_run = verify_source_and_ci(api_client, repository)
    output.mkdir(parents=True, exist_ok=True)
    if output.resolve() == input_dir.resolve():
        raise ValueError('Release output must be separate from the staging input')
    shutil.copyfile(apk, output / APK_NAME)
    # -0 gives stable stored ZIP entries; the commit supplies the archive timestamps.
    subprocess.run(['git', '-C', str(repository), '-c', 'core.autocrlf=false', 'archive', '--format=zip',
                    '-0', '--prefix=hongguotv-' + VERSION + '/', '--output=' + str((output / SOURCE_NAME).resolve()),
                    SOURCE_COMMIT], check=True)
    (output / MANIFEST_NAME).write_bytes(json_bytes(candidate_manifest()))
    sums = ''.join(digest_file(output / name)[1] + '  ' + name + '\n' for name in ASSET_NAMES[:-1])
    (output / 'SHA256SUMS').write_text(sums, encoding='utf-8')
    assets = {name: {'path': output / name, 'size': digest_file(output / name)[0],
                     'sha256': digest_file(output / name)[1]} for name in ASSET_NAMES}
    if (assets[APK_NAME]['size'], assets[APK_NAME]['sha256']) != (APK_SIZE, APK_SHA256):
        raise ValueError('Candidate APK changed during preparation')
    if any(not 0 < item['size'] <= 80 * 1024 * 1024 for item in assets.values()):
        raise ValueError('Release asset exceeds the bounded upload size')
    (output / 'verification.json').write_bytes(json_bytes({
        'repository': REPOSITORY, 'sourceCommit': SOURCE_COMMIT, 'sourceTree': SOURCE_TREE,
        'androidWorkflowRun': ci_run, 'packageName': PACKAGE, 'versionName': VERSION,
        'versionCode': VERSION_CODE, 'minSdk': MIN_SDK, 'signingCertificateSha256': CERT_SHA256,
        'verifiedV2': True, 'assets': {name: {k: v for k, v in item.items() if k != 'path'}
                                     for name, item in assets.items()}}))
    return assets


def current_updates(api_client):
    ref = optional(api_client, '/git/ref/heads/updates')
    if ref is None:
        return None, None, None
    if ref.get('ref') != 'refs/heads/updates' or ref.get('object', {}).get('type') != 'commit':
        raise ValueError('Unexpected updates branch reference')
    head = object_sha(ref['object']['sha'])
    commit = api(api_client, 'GET', '/git/commits/' + head)
    tree_sha = object_sha(commit.get('tree', {}).get('sha'))
    tree = api(api_client, 'GET', '/git/trees/' + tree_sha)
    if tree.get('truncated'):
        raise ValueError('Updates branch tree is truncated')
    entries = [entry for entry in tree.get('tree', []) if entry.get('path') == MANIFEST_NAME]
    if not entries:
        return head, tree_sha, None
    if len(entries) != 1 or entries[0].get('type') != 'blob' or entries[0].get('mode') != '100644':
        raise ValueError('Existing playbackfix manifest is not a regular file')
    blob = api(api_client, 'GET', '/git/blobs/' + object_sha(entries[0].get('sha')))
    if blob.get('encoding') != 'base64' or not 0 < blob.get('size', 0) <= 65536:
        raise ValueError('Existing playbackfix manifest blob is invalid')
    raw = base64.b64decode(''.join(blob.get('content', '').split()), validate=True)
    if len(raw) != blob['size']:
        raise ValueError('Existing playbackfix manifest blob size differs')
    return head, tree_sha, json.loads(raw)


def guard_manifest(current):
    if current is None:
        return
    if (not isinstance(current, dict) or type(current.get('schema')) is not int
            or current['schema'] != 1 or current.get('packageName') != PACKAGE
            or type(current.get('versionCode')) is not int):
        raise ValueError('Existing manifest is not the playbackfix schema/channel')
    code = current['versionCode']
    if code > VERSION_CODE:
        raise ValueError('Refusing update manifest version rollback')
    if code == VERSION_CODE:
        if current != candidate_manifest():
            raise ValueError('Same-version update manifest contains different APK or metadata')
        return
    apk = current.get('apk', {})
    if (code < 1 or not isinstance(apk, dict) or type(apk.get('size')) is not int
            or not 0 < apk['size'] <= 80 * 1024 * 1024
            or not isinstance(apk.get('sha256'), str)
            or not re.fullmatch('[0-9a-f]{64}', apk['sha256'])):
        raise ValueError('Existing lower-version manifest is malformed')


def latest_identity(api_client):
    release = optional(api_client, '/releases/latest')
    if release is None or release.get('tag_name') == TAG:
        raise ValueError('An existing separate latest release is required')
    return {'id': object_id(release.get('id')), 'tag': release.get('tag_name')}


def check_latest(api_client, expected):
    if latest_identity(api_client) != expected:
        raise ValueError('Latest release changed; refusing to promote the update manifest')


def check_tag(api_client, required=False):
    ref = optional(api_client, '/git/ref/tags/' + TAG)
    if ref is None:
        if required:
            raise ValueError('Published release tag is missing')
        return
    obj = ref.get('object', {})
    for _ in range(5):
        sha = object_sha(obj.get('sha'))
        if obj.get('type') == 'commit':
            if sha != SOURCE_COMMIT:
                raise ValueError('Release tag points to a different source commit')
            return
        if obj.get('type') != 'tag':
            break
        obj = api(api_client, 'GET', '/git/tags/' + sha).get('object', {})
    raise ValueError('Release tag cannot be resolved to the fixed source commit')


def find_release(api_client):
    release = optional(api_client, '/releases/tags/' + TAG)
    if release is not None:
        return release
    # The tag endpoint can omit drafts; a failed upload must remain safely resumable.
    for page in range(1, 11):
        batch = api(api_client, 'GET', f'/releases?per_page=100&page={page}')
        matches = [item for item in batch if item.get('tag_name') == TAG]
        if len(matches) > 1:
            raise ValueError('Multiple releases exist for the fixed tag')
        if matches:
            return matches[0]
        if len(batch) < 100:
            return None
    raise ValueError('Release history exceeds lookup limit')


def check_release(release):
    object_id(release.get('id'))
    if (release.get('tag_name') != TAG or release.get('target_commitish') != SOURCE_COMMIT
            or release.get('name') != VERSION or release.get('body') != RELEASE_BODY
            or release.get('prerelease') is not False or type(release.get('draft')) is not bool):
        raise ValueError('Existing release differs from the fixed candidate; no clobber is allowed')


def verify_asset(metadata, name, expected):
    object_id(metadata.get('id'))
    if (metadata.get('name') != name or metadata.get('state') != 'uploaded'
            or metadata.get('size') != expected['size']
            or metadata.get('digest') != 'sha256:' + expected['sha256']
            or metadata.get('browser_download_url') != PUBLIC_PREFIX + name):
        raise ValueError('Release asset metadata or digest mismatch: ' + name)


def release_assets(api_client, release_id):
    items = api(api_client, 'GET', f'/releases/{release_id}/assets?per_page=100')
    names = [item.get('name') for item in items]
    if len(items) >= 100 or len(set(names)) != len(names) or any(name not in ASSET_NAMES for name in names):
        raise ValueError('Release has duplicate or unexpected assets')
    return {item['name']: item for item in items}


def promote_manifest(api_client):
    """Parent-bound commits plus non-forced ref updates reject concurrent sibling writes."""
    for _ in range(3):
        head, tree, current = current_updates(api_client)
        guard_manifest(current)
        if current == candidate_manifest():
            return head
        payload = {'tree': [{'path': MANIFEST_NAME, 'mode': '100644', 'type': 'blob',
                             'content': json_bytes(candidate_manifest()).decode('utf-8')}]}
        if tree is not None:
            payload['base_tree'] = tree
        new_tree = api(api_client, 'POST', '/git/trees', payload)
        new_commit = api(api_client, 'POST', '/git/commits', {
            'message': 'Publish playbackfix update ' + VERSION,
            'tree': object_sha(new_tree.get('sha')), 'parents': [] if head is None else [head]})
        new_sha = object_sha(new_commit.get('sha'))
        # An explicit reread avoids unnecessary failed writes. force=false also closes
        # the race after this check: a concurrent sibling is not an ancestor of new_sha.
        observed = optional(api_client, '/git/ref/heads/updates')
        observed_head = None if observed is None else observed.get('object', {}).get('sha')
        if observed_head != head:
            continue
        try:
            if head is None:
                api(api_client, 'POST', '/git/refs', {'ref': 'refs/heads/updates', 'sha': new_sha})
            else:
                api(api_client, 'PATCH', '/git/refs/heads/updates', {'sha': new_sha, 'force': False})
        except ApiError as error:
            if error.status in (409, 422):
                continue
            raise
        actual_head, _, actual_manifest = current_updates(api_client)
        guard_manifest(actual_manifest)
        if actual_manifest != candidate_manifest():
            raise ValueError('Updates ref moved without retaining the verified manifest')
        return actual_head
    raise ValueError('Updates branch changed concurrently; rerun after reviewing its current manifest')


def publish(api_client, assets):
    if set(assets) != set(ASSET_NAMES):
        raise ValueError('Expected exactly the four fixed release assets')
    # Recheck local bytes before any mutation, including an already prepared directory.
    for name, item in assets.items():
        if digest_file(item['path']) != (item['size'], item['sha256']):
            raise ValueError('Prepared release asset changed: ' + name)
    guard_manifest(current_updates(api_client)[2])
    previous_latest = latest_identity(api_client)
    check_tag(api_client)
    release = find_release(api_client)
    if release is None:
        release = api(api_client, 'POST', '/releases', {
            'tag_name': TAG, 'target_commitish': SOURCE_COMMIT, 'name': VERSION, 'body': RELEASE_BODY,
            'draft': True, 'prerelease': False, 'make_latest': 'false'})
    check_release(release)
    release_id = release['id']
    existing = release_assets(api_client, release_id)
    if not release['draft'] and set(existing) != set(ASSET_NAMES):
        raise ValueError('Published release assets are incomplete; no clobber is allowed')
    for name in ASSET_NAMES:
        if name not in existing:
            # Only a draft can acquire missing assets. Failed/inconsistent assets are
            # never deleted or replaced; the mismatch is left for manual review.
            uploaded = api_client.request('POST', API_PREFIX + f'/releases/{release_id}/assets?name=' +
                                          urllib.parse.quote(name, safe=''),
                                          binary=assets[name]['path'].read_bytes(), upload=True)
            existing[name] = uploaded
        metadata = api(api_client, 'GET', '/releases/assets/' + str(object_id(existing[name].get('id'))))
        verify_asset(metadata, name, assets[name])
    # A second listing catches a concurrent extra upload before publication.
    final_assets = release_assets(api_client, release_id)
    if set(final_assets) != set(ASSET_NAMES):
        raise ValueError('Release assets changed during upload')
    for name in ASSET_NAMES:
        verify_asset(final_assets[name], name, assets[name])
    check_latest(api_client, previous_latest)
    if release['draft']:
        release = api(api_client, 'PATCH', f'/releases/{release_id}',
                      {'draft': False, 'make_latest': 'false'})
        check_release(release)
        if release['draft']:
            raise ValueError('Release remained a draft')
    check_tag(api_client, required=True)
    check_latest(api_client, previous_latest)
    for name in ASSET_NAMES:
        if api_client.public_digest(name, assets[name]['size']) != (assets[name]['size'], assets[name]['sha256']):
            raise ValueError('Anonymous public asset digest mismatch: ' + name)
    check_latest(api_client, previous_latest)
    updates_commit = promote_manifest(api_client)
    check_latest(api_client, previous_latest)
    return {'tag': TAG, 'sourceCommit': SOURCE_COMMIT, 'releaseId': release_id,
            'previousLatest': previous_latest, 'latestUnchanged': True,
            'publicAssetsVerified': list(ASSET_NAMES), 'updatesCommit': updates_commit}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True, help='Directory containing the fixed reviewed APK')
    parser.add_argument('--build-tools', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--repo-root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--validate-only', action='store_true', help='Only verify and prepare; no remote writes')
    args = parser.parse_args(argv)
    token = os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN')
    if not args.validate_only and not token:
        raise ValueError('Publishing requires GH_TOKEN or GITHUB_TOKEN')
    client = GitHub(token)
    assets = prepare(args.input.resolve(), args.build_tools.resolve(), args.output.resolve(),
                     args.repo_root.resolve(), client)
    print(f'Verified {TAG}: fixed APK, signing certificate, source tree, and latest Android CI')
    if args.validate_only:
        print('Validation only: release files prepared; no remote writes')
        return
    result = publish(client, assets)
    (args.output / 'publish-result.json').write_bytes(json_bytes(result))
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as error:
        # CalledProcessError does not expose captured tool output (which is not needed
        # to publish). No command contains tokens or signing private-key arguments.
        sys.exit(str(error))

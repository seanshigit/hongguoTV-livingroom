#!/usr/bin/env python3
"""Mirror a verified GitHub TV release without replacing the last good update."""

import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import urllib.parse
import urllib.request

RELEASES = 'https://github.com/seanminjie-del/hongguoTV-livingroom/releases'
ALLOWED_HOSTS = {'github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com'}
MAX_APK_BYTES = 80 * 1024 * 1024


def trusted_url(url):
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != 'https' or parsed.hostname not in ALLOWED_HOSTS or
            parsed.username is not None or parsed.password is not None or parsed.port not in (None, 443)):
        raise ValueError('Unsupported update source')


class TrustedRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        trusted_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def trusted_open(url):
    trusted_url(url)
    opener = urllib.request.build_opener(TrustedRedirect())
    return opener.open(urllib.request.Request(url, headers={'User-Agent': 'HongguoTV-Mirror'}), timeout=30)


def parse_manifest(data):
    try:
        manifest = json.loads(data)
        apk = manifest['apk']
        code, minimum, size = manifest['versionCode'], manifest['minSdk'], apk['size']
        valid = (manifest['schema'] == 1 and manifest['packageName'] == 'com.hongguotv.nativeapp' and
                 type(code) is int and 1 <= code <= 2147483647 and type(minimum) is int and 26 <= minimum <= 100 and
                 type(size) is int and 1 <= size <= MAX_APK_BYTES and
                 re.fullmatch(r'[0-9][0-9A-Za-z.+-]{0,63}', manifest['versionName']) and
                 re.fullmatch(r'(?:tv|kotlin)-v[0-9][0-9A-Za-z.+-]{0,80}', manifest['tag']) and
                 re.fullmatch(r'hongguotv-(?:kotlin-)?[0-9][0-9A-Za-z.+-]{0,80}-android8\.apk', apk['name']) and
                 re.fullmatch(r'[0-9a-f]{64}', apk['sha256']))
        if not valid: raise ValueError('Invalid update manifest')
        return manifest
    except (KeyError, TypeError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError('Invalid update manifest') from exc


def matches(path, apk):
    if not path.is_file() or path.stat().st_size != apk['size']: return False
    digest = hashlib.sha256()
    with path.open('rb') as source:
        while chunk := source.read(65536): digest.update(chunk)
    return digest.hexdigest() == apk['sha256']


def sync(root, open_url=trusted_open):
    root = Path(root)
    with open_url(RELEASES + '/latest/download/update.json') as response:
        raw = response.read(65537)
    if len(raw) > 65536: raise ValueError('Update manifest too large')
    manifest = parse_manifest(raw)
    current = root / 'update.json'
    if current.exists():
        old = parse_manifest(current.read_bytes())
        if manifest['versionCode'] < old['versionCode']: raise ValueError('Update would downgrade the mirror')
        if manifest['versionCode'] == old['versionCode']:
            fields = ('versionName', 'minSdk', 'tag', 'apk')
            if any(manifest[k] != old[k] for k in fields): raise ValueError('Published version was changed')

    apk = manifest['apk']
    release = root / 'releases' / manifest['tag']
    release.mkdir(parents=True, exist_ok=True)
    destination = release / apk['name']
    if not matches(destination, apk):
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(dir=release, prefix='.download-', delete=False) as out:
                temporary = Path(out.name)
                digest, total = hashlib.sha256(), 0
                with open_url(RELEASES + '/download/' + manifest['tag'] + '/' + apk['name']) as response:
                    while chunk := response.read(65536):
                        total += len(chunk)
                        if total > apk['size']: raise ValueError('Package size mismatch')
                        digest.update(chunk)
                        out.write(chunk)
                if total != apk['size'] or digest.hexdigest() != apk['sha256']:
                    raise ValueError('Package checksum mismatch')
                out.flush(); os.fsync(out.fileno()); os.fchmod(out.fileno(), 0o644)
            os.replace(temporary, destination)
        finally:
            if temporary is not None: temporary.unlink(missing_ok=True)

    if not current.exists() or current.read_bytes() != raw:
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(dir=root, prefix='.update-', delete=False) as out:
                temporary = Path(out.name)
                out.write(raw); out.flush(); os.fsync(out.fileno()); os.fchmod(out.fileno(), 0o644)
            os.replace(temporary, current)
        finally:
            if temporary is not None: temporary.unlink(missing_ok=True)
    return manifest['versionName']


if __name__ == '__main__':
    print('Mirrored', sync('/srv/hongguotv-update/www'))

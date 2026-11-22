#!/usr/bin/env python3
"""Prepare public update metadata from verified, monotonically versioned, same-signer APKs."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


CHANNEL_PACKAGES = {
    'legacy': 'com.hongguotv.nativeapp',
    'playbackfix': 'com.hongguotv.nativeapp.playbackfix',
}


def metadata(apk, tools):
    signature = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
    signers = sorted(re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})', signature))
    if not signers:
        raise ValueError('APK has no verified signer')
    badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(apk)], text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    sdk = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    if not package or not sdk:
        raise ValueError('Missing APK package/version/minSdk metadata')
    name, code, version = package.groups()
    return name, int(code), version, int(sdk[1]), signers


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--channel', choices=CHANNEL_PACKAGES, default='legacy',
                        help='Update channel; legacy remains the default. Playbackfix uses its own manifest, not Releases latest.')
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--previous-apk', type=Path, required=True)
    parser.add_argument('--build-tools', type=Path, required=True)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--notes-file', type=Path)
    args = parser.parse_args(argv)
    name, code, version, sdk, signers = metadata(args.apk, args.build_tools)
    old_name, old_code, _, _, old_signers = metadata(args.previous_apk, args.build_tools)
    expected_package = CHANNEL_PACKAGES[args.channel]
    if name != expected_package or old_name != name:
        raise ValueError(f'APK package name does not match {args.channel} channel ({expected_package}) and previous release')
    if signers != old_signers:
        raise ValueError('APK signing certificate differs from previous release')
    if not old_code < code <= 2147483647:
        raise ValueError('versionCode must increase from previous release')
    if not re.fullmatch(r'[0-9][0-9A-Za-z.+-]{0,63}', version) or args.tag != f'tv-v{version}':
        raise ValueError('Invalid versionName or tag; expected tv-vVERSION')
    if args.apk.name != f'hongguotv-{version}-android8.apk' or not 26 <= sdk <= 100:
        raise ValueError('Invalid APK filename or minSdk')
    size = args.apk.stat().st_size
    if not 0 < size <= 80 * 1024 * 1024:
        raise ValueError('APK exceeds updater size limit')
    notes = args.notes_file.read_text() if args.notes_file else '自动检查、下载并校验更新；确认系统安装后保留本机记录。'
    if len(notes) > 1500:
        raise ValueError('Update notes exceed 1500 characters')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    manifest = {'schema': 1, 'packageName': name, 'versionCode': code, 'versionName': version,
                'minSdk': sdk, 'tag': args.tag, 'sourceCommit': commit,
                'apk': {'name': args.apk.name, 'size': size, 'sha256': hashlib.sha256(args.apk.read_bytes()).hexdigest()},
                'notes': notes}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    print(f'Prepared {args.output}: {version}, versionCode {code}, channel {args.channel}, same signer as previous APK')
    if args.channel == 'playbackfix':
        print('Publish the Release with latest=false, then update only updates:playbackfix.json; leave the legacy manifest and mirror unchanged.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))

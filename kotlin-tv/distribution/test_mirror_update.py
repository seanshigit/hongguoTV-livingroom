import hashlib
import http.server
import json
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.parse
import urllib.request

from mirror_update import TrustedRedirect, sync


class MirrorUpdateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.payload = b'a valid release package'
        self.server = http.server.HTTPServer(('127.0.0.1', 0), self.handler())
        self.server.files = {}
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)

    def handler(self):
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                data = self.server.files.get(self.path)
                if data is None:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            def log_message(self, *args):
                pass
        return Handler

    def open(self, url):
        path = urllib.parse.urlsplit(url).path
        return urllib.request.urlopen(f'http://127.0.0.1:{self.server.server_port}{path}', timeout=5)

    def manifest(self, code, version, data=None):
        data = self.payload if data is None else data
        return {'schema': 1, 'packageName': 'com.hongguotv.nativeapp', 'versionCode': code,
                'versionName': version, 'minSdk': 26, 'tag': 'tv-v' + version,
                'apk': {'name': 'hongguotv-' + version + '-android8.apk', 'size': len(data),
                        'sha256': hashlib.sha256(data).hexdigest()}, 'notes': ''}

    def remote(self, manifest, apk=None):
        prefix = '/seanminjie-del/hongguoTV-livingroom/releases'
        self.server.files[prefix + '/latest/download/update.json'] = json.dumps(manifest).encode()
        self.server.files[prefix + '/download/' + manifest['tag'] + '/' + manifest['apk']['name']] = self.payload if apk is None else apk

    def existing(self, manifest):
        release = self.root / 'releases' / manifest['tag']
        release.mkdir(parents=True)
        (release / manifest['apk']['name']).write_bytes(self.payload)
        (self.root / 'update.json').write_text(json.dumps(manifest))

    def test_downloads_valid_release_before_advertising_it(self):
        manifest = self.manifest(29, '0.14.14-local')
        self.remote(manifest)
        sync(self.root, self.open)
        self.assertTrue((self.root / 'update.json').exists())
        self.assertEqual(manifest, json.loads((self.root / 'update.json').read_text()))
        self.assertEqual(self.payload, (self.root / 'releases' / manifest['tag'] / manifest['apk']['name']).read_bytes())

    def test_corrupt_new_package_preserves_previous_update(self):
        old = self.manifest(28, '0.14.13-local')
        new = self.manifest(29, '0.14.14-local')
        self.existing(old)
        self.remote(new, b'x' * len(self.payload))
        with self.assertRaises(ValueError):
            sync(self.root, self.open)
        self.assertEqual(old, json.loads((self.root / 'update.json').read_text()))
        self.assertFalse((self.root / 'releases' / new['tag'] / new['apk']['name']).exists())

    def test_older_release_cannot_replace_newer_advertised_version(self):
        old = self.manifest(29, '0.14.14-local')
        self.existing(old)
        self.remote(self.manifest(28, '0.14.13-local'))
        with self.assertRaises(ValueError):
            sync(self.root, self.open)
        self.assertEqual(old, json.loads((self.root / 'update.json').read_text()))

    def test_manifest_path_traversal_is_rejected(self):
        bad = self.manifest(29, '0.14.14-local')
        bad['tag'] = '../outside'
        self.remote(bad)
        with self.assertRaises(ValueError):
            sync(self.root, self.open)
        self.assertFalse((self.root / 'update.json').exists())

    def test_same_version_cannot_change_package_hash(self):
        old = self.manifest(28, '0.14.13-local')
        self.existing(old)
        changed = self.manifest(28, '0.14.13-local', b'a changed package')
        self.remote(changed, b'a changed package')
        with self.assertRaises(ValueError):
            sync(self.root, self.open)
        self.assertEqual(old, json.loads((self.root / 'update.json').read_text()))

    def test_redirect_to_untrusted_or_cleartext_host_is_blocked(self):
        redirect = TrustedRedirect()
        request = urllib.request.Request('https://github.com/test')
        for target in ('http://github.com/test', 'https://example.com/test'):
            with self.subTest(target=target), self.assertRaises(ValueError):
                redirect.redirect_request(request, None, 302, 'Found', {}, target)

if __name__ == '__main__':
    unittest.main()

"""Serve immutable, already downloaded artifacts to the new Gradle cache.

Only binds loopback. Reads old files without executing old build scripts or
copying their cache state. Records every artifact actually used under Memento.
"""
from __future__ import annotations
from datetime import datetime, timezone
from hashlib import sha256
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import posixpath
from pathlib import Path
from threading import Lock
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path('L:/Words/.tools/gradle-home/caches/modules-2/files-2.1')
INDEX = {}
for file in CACHE.glob('*/*/*/*/*'):
    if file.is_file() and file.suffix in ('.pom', '.jar', '.aar', '.module'):
        group, artifact, version, _, filename = file.relative_to(CACHE).parts
        key = '/'.join((group.replace('.', '/'), artifact, version, filename))
        INDEX.setdefault(key, file)
# Gradle module metadata separates a local artifact name from its Maven URL.
# Resolve those aliases from the real metadata, without rewriting either file.
for key, file in list(INDEX.items()):
    if file.suffix == '.module':
        metadata = json.loads(file.read_text(encoding='utf-8'))
        parent = key.rsplit('/', 1)[0]
        for variant in metadata.get('variants', []):
            for artifact in variant.get('files', []):
                cached = INDEX.get(parent + '/' + artifact['name'])
                if cached is not None:
                    INDEX[posixpath.normpath(parent + '/' + artifact['url'])] = cached
manifest = ROOT / 'data/manifests/resolved-build-artifacts.json'
USED = json.loads(manifest.read_text(encoding='utf-8')).get('artifacts', {}) if manifest.exists() else {}
LOCK = Lock()


class Handler(BaseHTTPRequestHandler):
    def handle_request(self, body: bool):
        key = unquote(urlsplit(self.path).path).lstrip('/')
        file = INDEX.get(key)
        if file is None:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header('Content-Length', str(file.stat().st_size))
        self.end_headers()
        if body:
            digest = sha256()
            with file.open('rb') as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(block)
                    self.wfile.write(block)
            with LOCK:
                USED[key] = {'source': str(file), 'bytes': file.stat().st_size, 'sha256': digest.hexdigest()}
                destination = ROOT / 'data/manifests/resolved-build-artifacts.json'
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_text(json.dumps({'recorded_at_utc': datetime.now(timezone.utc).isoformat(),
                                                   'artifacts': USED}, indent=2) + '\n', encoding='utf-8')

    def do_GET(self):
        self.handle_request(True)

    def do_HEAD(self):
        self.handle_request(False)

    def log_message(self, *_):
        pass


if __name__ == '__main__':
    ThreadingHTTPServer(('127.0.0.1', 18763), Handler).serve_forever()

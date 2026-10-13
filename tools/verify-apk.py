from hashlib import sha256
import argparse
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--stage',choices=['p1','p2','p3','p4','p5','p6','p7','p8'],default='p8')
parser.add_argument('--evidence-directory',help='Memento-relative output directory; omit for the original stage directory')
args=parser.parse_args()
apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
baseline = json.loads((ROOT / 'docs/qa/baseline/reference-audit.json').read_text(encoding='utf-8'))
checks = {}
contracts = {}
licenses = {}
manifest_check = None
import_classes = {}
extension_classes = {}
fixture_isolation = True
with zipfile.ZipFile(apk) as package:
    if args.stage in ('p6','p7','p8'):
        dex=[package.read(name) for name in package.namelist() if name.startswith('classes') and name.endswith('.dex')]
        for descriptor in ('data/migration/LegacySource','data/migration/LegacyImporter','data/migration/LegacyDocuments','ui/ImportPaneKt'):
            marker=('Lcom/scott/memento/'+descriptor+';').encode('utf-8')
            import_classes[descriptor]=any(marker in data for data in dex)
    if args.stage in ('p7','p8'):
        for descriptor in ('domain/LanguageCapabilities','data/content/ContentCatalog','ui/WordReadingsKt'):
            marker=('Lcom/scott/memento/'+descriptor+';').encode('utf-8')
            extension_classes[descriptor]=any(marker in data for data in dex)
    if args.stage in ('p4','p5','p6','p7','p8'):
        fixture_isolation=all(not name.startswith('assets/p7/') and name not in ('assets/manifest.json','assets/search-vectors.json','assets/de.db','assets/nl.db','assets/ja.db','assets/ko.db','assets/de.json','assets/nl.json','assets/ja.json','assets/ko.json') for name in package.namelist())
        manifest_bytes=package.read('assets/content/manifest.json')
        manifest=json.loads(manifest_bytes)
        manifest_check=sha256(manifest_bytes).hexdigest()==sha256((ROOT/'data/manifests/content-packages.json').read_bytes()).hexdigest()
        for language,row in manifest['packages'].items():
            actual=sha256(package.read('assets/'+row['asset'])).hexdigest()
            checks[language]={'sha256':actual,'matches_verified_package':actual==row['sha256'],
                             'source_db_sha256':row['source_db_sha256'],
                             'matches_audited_source_identity':row['source_db_sha256']==baseline['projects'][language]['formal_content']['sha256']}
        if set(checks)!={'fr','es'}: raise ValueError('Unexpected APK target languages')
        for name,row in manifest['licenses'].items():
            actual=sha256(package.read('assets/licenses/'+name)).hexdigest()
            licenses[name]={'sha256':actual,'matches_notice':actual==row['sha256']}
        if any('assets/'+filename in package.namelist() for filename in ['french_content.db','spanish_content.db']):
            raise ValueError('Transitional databases are still bundled')
    for language, filename in ([] if args.stage in ('p4','p5','p6','p7','p8') else [('fr', 'french_content.db'), ('es', 'spanish_content.db')]):
        digest = sha256()
        with package.open('assets/' + filename) as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(block)
        checks[language] = {'sha256': digest.hexdigest(), 'matches_formal_input': digest.hexdigest() == baseline['projects'][language]['formal_content']['sha256']}
    if args.stage in ('p2','p3','p4','p5','p6','p7','p8'):
        filenames=['user-v1.sql','content-v1.sql','content-intermediate-v1.schema.json']
        if args.stage in ('p3','p4','p5','p6','p7','p8'): filenames.append('user-v2-upgrade.sql')
        if args.stage in ('p6','p7','p8'): filenames.append('user-v3-upgrade.sql')
        for filename in filenames:
            actual=sha256(package.read('assets/'+filename)).hexdigest()
            expected=sha256((ROOT/'schemas'/filename).read_bytes()).hexdigest()
            contracts[filename]={'sha256':actual,'matches_source':actual==expected}
result = {'apk': str(apk), 'bytes': apk.stat().st_size, 'apk_sha256': sha256(apk.read_bytes()).hexdigest(),'embedded_import_classes':import_classes,'embedded_extension_classes':extension_classes,'test_fixtures_excluded':fixture_isolation,
          'embedded_formal_assets': checks, 'embedded_contracts': contracts,'embedded_licenses':licenses,
          'unified_manifest_matches_verified_output':manifest_check,
          'passed': all((row['matches_verified_package'] and row['matches_audited_source_identity']) if args.stage in ('p4','p5','p6','p7','p8') else row['matches_formal_input'] for row in checks.values()) and all(row['matches_source'] for row in contracts.values()) and all(row['matches_notice'] for row in licenses.values()) and (args.stage not in ('p4','p5','p6','p7','p8') or manifest_check) and all(import_classes.values()) and all(extension_classes.values()) and fixture_isolation}
directory=(ROOT / args.evidence_directory).resolve() if args.evidence_directory else ROOT / 'docs/qa' / args.stage
directory.relative_to(ROOT.resolve())
destination = directory / 'apk-assets.json'
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
print(json.dumps(result, indent=2))
if not result['passed']:
    raise SystemExit(1)

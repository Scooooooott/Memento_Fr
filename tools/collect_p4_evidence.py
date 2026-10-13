"""Record P4's current artifacts, comparisons and execution evidence."""
from hashlib import sha256
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from check_content_assets import check_assets

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/qa/p4'

def digest(path):
    result=sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):
            result.update(block)
    return result.hexdigest()

manifest=check_assets()
comparisons={target:json.loads((OUT/f'{target}-content-diff.json').read_text(encoding='utf-8')) for target in manifest['packages']}
jvm=[]
for path in sorted((ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')):
    suite=ET.parse(path).getroot()
    jvm.append({key:suite.attrib[key] for key in ('name','tests','failures','errors','time','timestamp')})
instrument=(OUT/'instrumentation.log').read_text(encoding='utf-8-sig')
android=re.search(r'OK \((\d+) tests?\)',instrument)
desktop=json.loads((OUT/'conversion-tests.json').read_text(encoding='utf-8'))
lint=(ROOT/'app/build/reports/lint-results-debug.txt').read_text(encoding='utf-8')
lint_count=re.search(r'(\d+) errors, (\d+) warnings',lint)
apk=json.loads((OUT/'apk-assets.json').read_text(encoding='utf-8'))
signature=(OUT/'signature.txt').read_text(encoding='utf-8-sig')
signature_ok='Verifies' in signature and 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature and 'CN=Android Debug' in signature
cert=re.search(r'certificate SHA-256 digest: ([0-9a-f]+)',signature)
badging=(OUT/'package.txt').read_text(encoding='utf-8-sig')
release=ROOT/'releases/Memento-P4-0.1.0-debug.apk'
smoke=(OUT/'install-smoke.txt').read_text(encoding='utf-8-sig')
preservation=json.loads((ROOT/'docs/qa/baseline/reference-preservation.json').read_text(encoding='utf-8'))
delivery={
    'stage':'P4','application_id':'com.scott.memento','version_code':1,'version_name':'0.1.0',
    'apk':str(release),'bytes':release.stat().st_size,'sha256':digest(release),
    'signing':{'type':'Android Debug / APK v2','passed':signature_ok,'certificate_sha256':cert[1] if cert else None},
    'jvm':jvm,'android':{'tests':int(android[1]) if android else 0,'passed':bool(android)},
    'desktop_conversion':desktop,'lint':{'errors':int(lint_count[1]),'warnings':int(lint_count[2])},
    'content':{target:{'passed':report['passed'],'source_sha256':report['source_sha256'],
                     'package_sha256':report['package_sha256'],'counts':report['counts'],'coverage':report['coverage']}
               for target,report in comparisons.items()},
    'embedded_assets_passed':apk['passed'],'install_smoke':smoke,'reference_preservation':preservation,
}
delivery['passed']=bool(jvm) and all(int(row['failures'])==0 and int(row['errors'])==0 for row in jvm) and bool(android) and desktop['passed'] and int(lint_count[1])==0 and all(report['passed'] for report in comparisons.values()) and apk['passed'] and delivery['sha256']==apk['apk_sha256'] and signature_ok and "name='com.scott.memento'" in badging and 'Status: ok' in smoke and preservation['passed']
files=set()
for directory in ['app/src/main','app/src/test','app/src/androidTest','schemas','data/source-snapshots','data/normalized','data/content-packages']:
    files.update(path for path in (ROOT/directory).rglob('*') if path.is_file())
for path in (ROOT/'tools').glob('*'):
    if path.is_file():files.add(path)
for filename in ['build.gradle.kts','settings.gradle.kts','gradle.properties','app/build.gradle.kts','AGENTS.md','data/manifests/content-packages.json']:
    files.add(ROOT/filename)
source_manifest={str(path.relative_to(ROOT)).replace('\\','/'):{'bytes':path.stat().st_size,'sha256':digest(path)} for path in sorted(files)}
(OUT/'source-manifest.json').write_text(json.dumps(source_manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(OUT/'delivery.json').write_text(json.dumps(delivery,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'passed':delivery['passed'],'apk':str(release),'sha256':delivery['sha256'],
                  'android_tests':delivery['android']['tests'],'jvm_tests':sum(int(row['tests']) for row in jvm),
                  'desktop_tests':desktop['tests'],'content_lexemes':{target:report['counts']['lexeme'] for target,report in comparisons.items()}},indent=2))
raise SystemExit(0 if delivery['passed'] else 1)

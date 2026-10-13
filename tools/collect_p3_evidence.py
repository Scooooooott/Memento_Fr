"""Collect freshly produced P3 evidence without executing historical suites."""
from hashlib import sha256
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/qa/p3'
jvm=[]
for path in sorted((ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')):
    suite=ET.parse(path).getroot()
    jvm.append({key:suite.attrib[key] for key in ('name','tests','failures','errors','time')})
instrument=(OUT/'instrumentation.log').read_text(encoding='utf-8-sig')
android=re.search(r'OK \((\d+) tests?\)',instrument)
lint=(ROOT/'app/build/reports/lint-results-debug.txt').read_text(encoding='utf-8')
lint_count=re.search(r'(\d+) errors, (\d+) warnings',lint)
desktop=json.loads((OUT/'schema-upgrade.json').read_text(encoding='utf-8'))
apk=json.loads((OUT/'apk-assets.json').read_text(encoding='utf-8'))
release=ROOT/'releases/Memento-P3-0.1.0-debug.apk'
delivery={
    'stage':'P3','application_id':'com.scott.memento','version_code':1,'version_name':'0.1.0','signing':'Android Debug / APK v2',
    'apk':str(release),'bytes':release.stat().st_size,'sha256':sha256(release.read_bytes()).hexdigest(),
    'jvm':jvm,'android':{'tests':int(android[1]) if android else 0,'passed':bool(android)},
    'desktop_sqlite':desktop,'lint':{'errors':int(lint_count[1]),'warnings':int(lint_count[2])},
    'embedded_assets_passed':apk['passed'],'install_smoke':(OUT/'install-smoke.txt').read_text(encoding='utf-8-sig'),
}
delivery['passed']=bool(jvm) and all(int(row['failures'])==0 and int(row['errors'])==0 for row in jvm) and bool(android) and desktop['passed'] and int(lint_count[1])==0 and apk['passed'] and delivery['sha256']==apk['apk_sha256'] and 'Status: ok' in delivery['install_smoke']
files=set()
for directory in ['app/src/main','app/src/test','app/src/androidTest','schemas']:
    files.update(path for path in (ROOT/directory).rglob('*') if path.is_file())
for filename in ['build.gradle.kts','settings.gradle.kts','gradle.properties','app/build.gradle.kts','AGENTS.md',
                 'tools/build.ps1','tools/project-env.ps1','tools/verify-device.ps1','tools/verify-apk.py',
                 'tools/validate_p3_contracts.py','tools/collect_p3_evidence.py']:
    files.add(ROOT/filename)
manifest={str(path.relative_to(ROOT)).replace('\\','/'):{'bytes':path.stat().st_size,'sha256':sha256(path.read_bytes()).hexdigest()} for path in sorted(files)}
(OUT/'source-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(OUT/'delivery.json').write_text(json.dumps(delivery,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(delivery,ensure_ascii=False,indent=2))
raise SystemExit(0 if delivery['passed'] else 1)

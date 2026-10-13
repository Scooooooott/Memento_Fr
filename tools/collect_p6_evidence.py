"""P6 delivery gate. Uses current execution output, with P4 content comparison explicitly historical."""
from hashlib import sha256
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from check_content_assets import check_assets

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/qa/p6'

def digest(path):
    value=sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''): value.update(block)
    return value.hexdigest()

def cases(path):
    result={}; fields={}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key,_,value=line[len('INSTRUMENTATION_STATUS: '):].partition('=');fields[key]=value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code=int(line.split(': ',1)[1])
            if code!=1 and 'class' in fields and 'test' in fields:result[fields['class']+'.'+fields['test']]=code==0
            fields={}
    return result

manifest=check_assets()
jvm=[{key:ET.parse(path).getroot().attrib[key] for key in ('name','tests','failures','errors','time','timestamp')}
     for path in sorted((ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))]
android_log=OUT/'accepted/instrumentation.log'
android=cases(android_log)
expected=int(re.search(r'INSTRUMENTATION_STATUS: numtests=(\d+)',android_log.read_text(encoding='utf-8-sig'))[1])
passed=bool(re.search(r'OK \((\d+) tests?\)',android_log.read_text(encoding='utf-8-sig')))
lint=re.search(r'(\d+) errors, (\d+) warnings',(ROOT/'app/build/reports/lint-results-debug.txt').read_text(encoding='utf-8'))
apk=json.loads((OUT/'apk-assets.json').read_text(encoding='utf-8'))
signature=(OUT/'signature.txt').read_text(encoding='utf-8-sig')
badging=(OUT/'package.txt').read_text(encoding='utf-8-sig')
smoke=(OUT/'install-smoke.txt').read_text(encoding='utf-8-sig')
preservation=json.loads((OUT/'reference-preservation.json').read_text(encoding='utf-8'))
release=ROOT/'releases/Memento-P6-0.1.0-debug.apk'
delivery={
    'stage':'P6','application_id':'com.scott.memento','version_code':1,'version_name':'0.1.0','user_schema_version':3,
    'apk':str(release),'bytes':release.stat().st_size,'sha256':digest(release),
    'signing':{'type':'Android Debug / APK v2','passed':'Verifies' in signature and 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature and 'CN=Android Debug' in signature},
    'jvm':jvm,'android':{'tests':len(android),'expected':expected,'passed':passed and len(android)==expected and all(android.values()),'cases':android},
    'lint':{'errors':int(lint[1]),'warnings':int(lint[2])},'embedded_assets_passed':apk['passed'],
    'content':{'current_hash_gate_passed':True,'counts':{key:row['counts'] for key,row in manifest['packages'].items()},'unchanged_full_comparison_stage':'P4'},
    'install_smoke':smoke,'reference_preservation':preservation,
    'limitations':['No real user backup acquired or migrated','No large user-backup capacity test','Offline French/Spanish speech playback remains environment-limited','P7 and P8 remain pending'],
}
delivery['passed']=bool(jvm) and all(int(row['errors'])==0 and int(row['failures'])==0 for row in jvm) and delivery['android']['passed'] and int(lint[1])==0 and apk['passed'] and delivery['sha256']==apk['apk_sha256'] and delivery['signing']['passed'] and "name='com.scott.memento'" in badging and 'Status: ok' in smoke and preservation['passed']
files=set()
for directory in ('app/src/main','app/src/debug','app/src/test','app/src/androidTest','schemas'):
    files.update(path for path in (ROOT/directory).rglob('*') if path.is_file())
files.update(path for path in (ROOT/'tools').glob('*') if path.is_file())
files.update(ROOT/name for name in ('build.gradle.kts','settings.gradle.kts','gradle.properties','app/build.gradle.kts','AGENTS.md','README.md','MULTILINGUAL_REFACTOR_PLAN.md','docs/DEVELOPMENT_PHASES.md','docs/decisions/PRODUCT_RULES.md','docs/migration/LEGACY_IMPORT.md','docs/qa/p6/ACCEPTANCE.md','data/manifests/content-packages.json'))
source={str(path.relative_to(ROOT)).replace('\\','/'):{'bytes':path.stat().st_size,'sha256':digest(path)} for path in sorted(files)}
(OUT/'source-manifest.json').write_text(json.dumps(source,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(OUT/'delivery.json').write_text(json.dumps(delivery,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'passed':delivery['passed'],'android':len(android),'jvm':sum(int(row['tests']) for row in jvm),'apk':str(release),'sha256':delivery['sha256']},indent=2))
raise SystemExit(0 if delivery['passed'] else 1)

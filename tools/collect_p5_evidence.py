"""Collect freshly executed P5 evidence; historical conversion results remain explicitly P4."""
from hashlib import sha256
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from check_content_assets import check_assets

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/qa/p5'

def digest(path):
    result=sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):
            result.update(block)
    return result.hexdigest()

manifest=check_assets()
jvm=[]
for path in sorted((ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')):
    suite=ET.parse(path).getroot()
    jvm.append({key:suite.attrib[key] for key in ('name','tests','failures','errors','time','timestamp')})
def cases(path):
    result={}
    fields={}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key,_,value=line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            fields[key]=value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code=int(line.split(': ',1)[1])
            if code!=1 and 'class' in fields and 'test' in fields:
                result[fields['class']+'.'+fields['test']]=code==0
            fields={}
    return result

all_log=OUT/'instrumentation-all.log'
focused_log=OUT/'final-ui/instrumentation.log'
base_cases=cases(all_log)
focused_cases=cases(focused_log)
dialog_log=OUT/'dialog-ui/instrumentation.log'
dialog_cases=cases(dialog_log)
android_cases={**base_cases,**focused_cases,**dialog_cases}
expected=int(re.search(r'INSTRUMENTATION_STATUS: numtests=(\d+)',all_log.read_text(encoding='utf-8-sig'))[1])
focused_ok=bool(re.search(r'OK \((\d+) tests?\)',focused_log.read_text(encoding='utf-8-sig')))
font_log=(OUT/'font-150/instrumentation.log').read_text(encoding='utf-8-sig')
font_ok=bool(re.search(r'OK \((\d+) tests?\)',font_log))
font_restore=json.loads((OUT/'font-150/font-scale.json').read_text(encoding='utf-8-sig'))
lint=(ROOT/'app/build/reports/lint-results-debug.txt').read_text(encoding='utf-8')
lint_count=re.search(r'(\d+) errors, (\d+) warnings',lint)
apk=json.loads((OUT/'apk-assets.json').read_text(encoding='utf-8'))
signature=(OUT/'signature.txt').read_text(encoding='utf-8-sig')
signature_ok='Verifies' in signature and 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature and 'CN=Android Debug' in signature
cert=re.search(r'certificate SHA-256 digest: ([0-9a-f]+)',signature)
badging=(OUT/'package.txt').read_text(encoding='utf-8-sig')
release=ROOT/'releases/Memento-P5-0.1.0-debug.apk'
smoke=(OUT/'install-smoke.txt').read_text(encoding='utf-8-sig')
preservation=json.loads((ROOT/'docs/qa/baseline/reference-preservation.json').read_text(encoding='utf-8'))
voice=json.loads((OUT/'screenshots/p5-offline-voice-probe.json').read_text(encoding='utf-8'))
delivery={
    'stage':'P5','application_id':'com.scott.memento','version_code':1,'version_name':'0.1.0',
    'apk':str(release),'bytes':release.stat().st_size,'sha256':digest(release),
    'signing':{'type':'Android Debug / APK v2','passed':signature_ok,'certificate_sha256':cert[1] if cert else None},
    'jvm':jvm,'android':{'tests':len(android_cases),'passed':len(android_cases)==expected and all(android_cases.values()) and focused_ok,
        'full_run':{'tests':len(base_cases),'passed':sum(base_cases.values()),'failed':len(base_cases)-sum(base_cases.values())},
        'focused_retest':focused_cases,'dialog_retest':dialog_cases,'cases':android_cases},
    'font_150':{'passed':font_ok and font_restore['restored'],'scope':'MainActivity learning/recreation/translation/target switch; one UI case','device_setting':font_restore},
    'lint':{'errors':int(lint_count[1]),'warnings':int(lint_count[2])},
    'content':{'current_asset_gate_passed':True,'counts':{target:row['counts'] for target,row in manifest['packages'].items()},
        'unchanged_full_comparison_stage':'P4','reports':{target:digest(ROOT/f'docs/qa/p4/{target}-content-diff.json') for target in ('fr','es')}},
    'offline_voice_probe':voice,'embedded_assets_passed':apk['passed'],'install_smoke':smoke,'reference_preservation':preservation,
}
delivery['passed']=bool(jvm) and all(int(row['failures'])==0 and int(row['errors'])==0 for row in jvm) and delivery['android']['passed'] and delivery['font_150']['passed'] and int(lint_count[1])==0 and apk['passed'] and delivery['sha256']==apk['apk_sha256'] and signature_ok and "name='com.scott.memento'" in badging and 'Status: ok' in smoke and preservation['passed']
files=set()
for directory in ['app/src/main','app/src/test','app/src/androidTest','schemas']:
    files.update(path for path in (ROOT/directory).rglob('*') if path.is_file())
files.update(path for path in (ROOT/'tools').glob('*') if path.is_file())
for filename in ['build.gradle.kts','settings.gradle.kts','gradle.properties','app/build.gradle.kts','AGENTS.md','data/manifests/content-packages.json']:
    files.add(ROOT/filename)
source_manifest={str(path.relative_to(ROOT)).replace('\\','/'):{'bytes':path.stat().st_size,'sha256':digest(path)} for path in sorted(files)}
(OUT/'source-manifest.json').write_text(json.dumps(source_manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(OUT/'delivery.json').write_text(json.dumps(delivery,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'passed':delivery['passed'],'apk':str(release),'sha256':delivery['sha256'],'android_tests':delivery['android']['tests'],
    'jvm_tests':sum(int(row['tests']) for row in jvm),'offline_voice_probe':voice},indent=2))
raise SystemExit(0 if delivery['passed'] else 1)

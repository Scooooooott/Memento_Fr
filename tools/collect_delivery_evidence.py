"""Final delivery gate: fresh execution records, final APK and current source hashes."""
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from check_content_assets import check_assets

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/qa/p8'

def digest(path):
    value=sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):value.update(block)
    return value.hexdigest()

def read(name):return json.loads((OUT/name).read_text(encoding='utf-8-sig'))

def instrumentation(name):
    log=(OUT/name).read_text(encoding='utf-8-sig')
    fields={};cases={}
    for line in log.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key,_,value=line[len('INSTRUMENTATION_STATUS: '):].partition('=');fields[key]=value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code=int(line.split(': ',1)[1])
            if code!=1 and 'class' in fields and 'test' in fields:cases[fields['class']+'.'+fields['test']]=code==0
            fields={}
    match=re.search(r'OK \((\d+) tests?\)',log)
    expected=re.search(r'INSTRUMENTATION_STATUS: numtests=(\d+)',log)
    tests=int(expected[1]) if expected else 0
    return {'tests':len(cases),'expected':tests,'passed':bool(match) and int(match[1])==tests==len(cases) and all(cases.values()),'cases':cases,
            'seconds':float(re.search(r'Time: ([\d.]+)',log)[1]) if 'Time: ' in log else None}

manifest=check_assets()
android=instrumentation('accepted/instrumentation.log')
desktop=read('desktop-contracts.json')
content={target:read(f'{target}-content-diff.json') for target in ('fr','es')}
jvm=[{key:ET.parse(path).getroot().attrib[key] for key in ('name','tests','failures','errors','time','timestamp')}
     for path in sorted((ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))]
lint=re.search(r'(\d+) errors, (\d+) warnings',(ROOT/'app/build/reports/lint-results-debug.txt').read_text(encoding='utf-8'))
apk=read('apk-assets.json')
signature=(OUT/'signature.txt').read_text(encoding='utf-8-sig')
badging=(OUT/'package.txt').read_text(encoding='utf-8-sig')
smoke=(OUT/'install-smoke.txt').read_text(encoding='utf-8-sig')
install=read('install.json')
preservation=read('reference-preservation.json')
process=read('process-recovery/result.json')
font=read('large-font/result.json')
font['instrumentation']=instrumentation('large-font/instrumentation.log')
release=ROOT/'releases/Memento-0.1.0-debug.apk'
checksum=digest(release)
for row in process['cases']:
    name=row['case']
    row['prepare']=instrumentation(f'process-recovery/{name}-prepare.log')
    row['verify']=instrumentation(f'process-recovery/{name}-verify.log')
    row['verification']=read(f'process-recovery/{name}-verified.json')
process['passed']=process['passed'] and len(process['cases'])==4 and process['uses_force_stop'] and not process['clears_default_database'] and process['application_apk_sha256']==checksum and all(
    row['stopped_pid_empty'] and row['before_pid']!=row['after_pid'] and row['prepare']['passed'] and row['verify']['passed'] and row['verification']['passed'] for row in process['cases'])
picker='com.scott.memento.LegacyImportUiInstrumentedTest.actualSystemPickerSelectsBackupPreviewsAndCommitsWithoutChangingSource'
delivery={
    'stage':'P8','verified_at_utc':datetime.now(timezone.utc).isoformat(),'application_id':'com.scott.memento',
    'version_code':1,'version_name':'0.1.0','min_sdk':26,'target_sdk':35,'user_schema_version':3,
    'apk':str(release),'bytes':release.stat().st_size,'sha256':checksum,
    'signing':{'type':'Android Debug / APK v2','passed':'Verifies' in signature and 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature and 'CN=Android Debug' in signature},
    'android':android,'jvm':jvm,'desktop':desktop,'lint':{'errors':int(lint[1]),'warnings':int(lint[2])},
    'process_recovery':process,'actual_system_picker_import_passed':android['cases'].get(picker,False),'large_font':font,
    'content':{'full_current_comparison':{target:{'passed':row['passed'],'source_sha256':row['source_sha256'],'package_sha256':row['package_sha256'],'coverage':row['coverage'],'counts':row['counts']} for target,row in content.items()}},
    'embedded_assets_passed':apk['passed'],'install':install,'install_smoke':smoke,'reference_preservation':preservation,
    'limitations':['No real user private backup acquired or migrated','Standalone consistent SQLite backups required; a single selected file cannot expose missing WAL data',
                   'Large backup capacity and OEM/physical-device compatibility are not verified','No installed offline French/Spanish voice in the emulator; actual speech playback remains unverified',
                   'Existing machine-assisted content provenance retained; no fresh linguistic review','APK is debug-signed; no release signing, public publication, commit or tag'],
}
delivery['passed']=all((
    desktop['passed'],desktop['tests']==37,bool(jvm),sum(int(row['tests']) for row in jvm)==21,
    all(int(row['errors'])==0 and int(row['failures'])==0 for row in jvm),android['passed'],android['tests']==78,
    delivery['actual_system_picker_import_passed'],process['passed'],font['passed'],font['font_scale']==1.5,
    font['application_apk_sha256']==checksum,font['instrumentation']['passed'],font['restored_font_scale']==font['previous_font_scale'],
    int(lint[1])==0,apk['passed'],checksum==apk['apk_sha256'],delivery['signing']['passed'],
    "name='com.scott.memento'" in badging,"versionCode='1'" in badging,"versionName='0.1.0'" in badging,
    'Status: ok' in smoke,install['passed'],install['ui_ready'],install['installed_apk_sha256']==checksum,install['default_database_preserved_during_install'],not install['clears_data'],not install['uses_qa_extras'],preservation['passed'],
    all(row['passed'] and row['package_sha256']==manifest['packages'][target]['sha256'] for target,row in content.items()),
    'BUILD SUCCESSFUL' in (ROOT/'.tools/p8-final-build.log').read_text(encoding='utf-8-sig'),
    'BUILD SUCCESSFUL' in (OUT/'test-rebuild.log').read_text(encoding='utf-8-sig'),
))
files=set()
for directory in ('app/src/main','app/src/debug','app/src/test','app/src/androidTest','schemas'):
    files.update(path for path in (ROOT/directory).rglob('*') if path.is_file())
files.update(path for path in (ROOT/'tools').glob('*') if path.is_file())
files.update(path for path in (ROOT/'gradle/wrapper').glob('*') if path.is_file())
files.update(ROOT/name for name in ('gradlew','gradlew.bat','data/manifests/resolved-build-artifacts.json'))
files.update(ROOT/name for name in ('build.gradle.kts','settings.gradle.kts','gradle.properties','app/build.gradle.kts','AGENTS.md','README.md','MULTILINGUAL_REFACTOR_PLAN.md','docs/DEVELOPMENT_PHASES.md','docs/decisions/PRODUCT_RULES.md','docs/migration/LEGACY_IMPORT.md','docs/qa/p8/ACCEPTANCE.md','docs/content/EXTENSION_FIXTURES.md','data/manifests/content-packages.json'))
files.update(path for path in (ROOT/'data/fixtures/p7/generated').glob('*') if path.is_file())
source={str(path.relative_to(ROOT)).replace('\\','/'):{'bytes':path.stat().st_size,'sha256':digest(path)} for path in sorted(files)}
(OUT/'source-manifest.json').write_text(json.dumps(source,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(OUT/'delivery.json').write_text(json.dumps(delivery,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'passed':delivery['passed'],'android':android['tests'],'jvm':sum(int(row['tests']) for row in jvm),'desktop':desktop['tests'],
                  'process_scenarios':len(process['cases']),'apk':str(release),'sha256':checksum},indent=2))
raise SystemExit(0 if delivery['passed'] else 1)

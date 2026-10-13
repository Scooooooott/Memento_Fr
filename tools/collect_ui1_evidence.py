"""Associate the UI1 development APK with current inputs and phase evidence."""
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/qa/ui-restoration/ui1'


def digest(path):
    value = sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def read(name):
    return json.loads((OUT / name).read_text(encoding='utf-8-sig'))


def instrumentation(path):
    log = path.read_text(encoding='utf-8-sig')
    fields, cases = {}, {}
    for line in log.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, _, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            fields[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.split(': ', 1)[1])
            if code != 1 and 'class' in fields and 'test' in fields:
                cases[fields['class'] + '.' + fields['test']] = code == 0
            fields = {}
    match = re.search(r'OK \((\d+) tests?\)', log)
    expected = re.search(r'INSTRUMENTATION_STATUS: numtests=(\d+)', log)
    count = int(expected[1]) if expected else 0
    return {'tests': len(cases), 'expected': count,
            'passed': bool(match) and int(match[1]) == count == len(cases) and all(cases.values()),
            'cases': cases}


def collect():
    apk_path = OUT / 'Memento-ui1-debug.apk'
    checksum = digest(apk_path)
    targeted, assets = read('accepted/result.json'), read('apk-assets.json')
    process, install = read('process-recovery/result.json'), read('install/install.json')
    preservation = read('reference-preservation.json')
    signature = (OUT / 'signature.txt').read_text(encoding='utf-8-sig')
    badging = (OUT / 'package.txt').read_text(encoding='utf-8-sig')
    lint = re.search(r'(\d+) errors, (\d+) warnings', (OUT / 'lint.txt').read_text(encoding='utf-8-sig'))
    classes = []
    evidence_paths = [OUT / name for name in (
        'accepted/result.json', 'apk-assets.json', 'process-recovery/result.json',
        'install/install.json', 'reference-preservation.json', 'signature.txt',
        'package.txt', 'lint.txt', 'jvm-results.json', 'build-verified.log',
        'test-rebuild.log', 'screenshot-test-rebuild.log', 'navigation-visual-rerun.log',
        'install/default-launch.xml', 'install/default-launch.png')]
    for row in targeted['classes']:
        path = ROOT / row['log']
        result = instrumentation(path)
        classes.append({'class': row['class'], 'declared_tests': row['tests'], **result})
        evidence_paths.append(path)
    jvm = []
    for path in sorted((OUT / 'jvm').glob('TEST-*.xml')):
        attrs = ET.parse(path).getroot().attrib
        jvm.append({key: attrs[key] for key in ('name', 'tests', 'failures', 'errors')})
        evidence_paths.append(path)
    jvm_summary = read('jvm-results.json')
    jvm_match = sorted((r['name'], int(r['tests']), int(r['failures']), int(r['errors'])) for r in jvm) == sorted(
        (r['name'], int(r['tests']), int(r['failures']), int(r['errors'])) for r in jvm_summary)
    recovery = []
    for row in process['cases']:
        name = row['case']
        prepare_path = OUT / f'process-recovery/{name}-prepare.log'
        verify_path = OUT / f'process-recovery/{name}-verify.log'
        verification_path = OUT / f'process-recovery/{name}-verified.json'
        prepare, verify = instrumentation(prepare_path), instrumentation(verify_path)
        verification = read(f'process-recovery/{name}-verified.json')
        recovery.append({'case': name, 'before_pid': row['before_pid'], 'after_pid': row['after_pid'],
                         'passed': row['stopped_pid_empty'] and row['before_pid'] != row['after_pid']
                         and prepare['passed'] and verify['passed'] and verification['passed']})
        evidence_paths.extend((prepare_path, verify_path, verification_path))
    snapshots = ['accepted/HomeNavigationInstrumentedTest/screenshots/ui1-fr-home.png',
                 'accepted/HomeNavigationInstrumentedTest/screenshots/ui1-es-home.png',
                 'accepted/HomeNavigationInstrumentedTest/screenshots/ui1-study-layout.png']
    evidence_paths.extend(OUT / name for name in snapshots)
    checks = {
        'targeted_android': targeted['passed'] and targeted['tests'] == 29 and len(classes) == 7
        and sum(r['tests'] for r in classes) == 29
        and all(r['passed'] and r['tests'] == r['declared_tests'] for r in classes),
        'navigation_visual_rerun': any(r['class'] == 'HomeNavigationInstrumentedTest' and r['passed']
                                      and r['tests'] == 9 for r in classes)
        and bool(re.search(r'OK \(9 tests\)', (OUT / 'navigation-visual-rerun.log').read_text(encoding='utf-8-sig'))),
        'jvm': bool(jvm) and jvm_match and sum(int(r['tests']) for r in jvm) == 21
        and all(int(r['failures']) == int(r['errors']) == 0 for r in jvm),
        'lint': lint is not None and int(lint[1]) == 0,
        'builds': all('BUILD SUCCESSFUL' in (OUT / name).read_text(encoding='utf-8-sig')
                      for name in ('build-verified.log', 'test-rebuild.log', 'screenshot-test-rebuild.log')),
        'process_recovery': process['passed'] and len(recovery) == 4 and process['instrumentation_invocations'] == 8
        and process['uses_force_stop'] and not process['clears_default_database'] and all(r['passed'] for r in recovery),
        'formal_install': install['passed'] and install['ui_ready'] and install['default_database_preserved_during_install']
        and not install['clears_data'] and not install['uses_qa_extras'],
        'apk_association': checksum == targeted['apk_sha256'] == assets['apk_sha256']
        == process['application_apk_sha256'] == install['apk_sha256'] == install['installed_apk_sha256']
        == digest(ROOT / 'app/build/outputs/apk/debug/app-debug.apk')
        == (OUT / 'Memento-ui1-debug.apk.sha256').read_text().split()[0],
        'signature': 'Verifies' in signature and 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
        and 'CN=Android Debug' in signature,
        'identity': all(value in badging for value in ("name='com.scott.memento'", "versionCode='1'",
                       "versionName='0.1.0'", "sdkVersion:'26'", "targetSdkVersion:'35'")),
        'embedded_assets': assets['passed'] and assets['test_fixtures_excluded'],
        'registered_reference_preservation': preservation['passed'],
        'screenshots_present': all((OUT / name).stat().st_size > 0 for name in snapshots),
    }
    files = set()
    for directory in ('app/src/main', 'app/src/debug', 'app/src/test', 'app/src/androidTest', 'schemas', 'gradle/wrapper'):
        files.update(p for p in (ROOT / directory).rglob('*') if p.is_file())
    files.update(p for p in (ROOT / 'tools').glob('*') if p.is_file())
    files.update(ROOT / name for name in (
        'gradlew', 'gradlew.bat', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'app/build.gradle.kts',
        'AGENTS.md', 'README.md', 'MULTILINGUAL_REFACTOR_PLAN.md', 'docs/DEVELOPMENT_PHASES.md',
        'docs/decisions/PRODUCT_RULES.md', 'docs/ui/LEGACY_LAYOUT_RESTORATION_PLAN.md',
        'docs/qa/ui-restoration/ui1/ACCEPTANCE.md', 'data/manifests/resolved-build-artifacts.json',
        'data/manifests/content-packages.json'))
    manifest = {p.relative_to(ROOT).as_posix(): {'bytes': p.stat().st_size, 'sha256': digest(p)} for p in sorted(files)}
    manifest_path = OUT / 'source-manifest.json'
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    delivery = {
        'stage': 'UI1', 'verified_at_utc': datetime.now(timezone.utc).isoformat(),
        'scope': 'UI1 targeted phase acceptance; separate Android runners, not the complete release suite',
        'application_id': 'com.scott.memento', 'version_name': '0.1.0', 'version_code': 1,
        'min_sdk': 26, 'target_sdk': 35, 'signing': 'Android Debug / APK v2',
        'apk': str(apk_path), 'bytes': apk_path.stat().st_size, 'sha256': checksum,
        'checks': checks, 'passed': all(checks.values()), 'android': {'tests': 29, 'classes': classes},
        'jvm': {'tests': sum(int(r['tests']) for r in jvm), 'classes': jvm},
        'lint': {'errors': int(lint[1]), 'warnings': int(lint[2])} if lint else None,
        'process_recovery': {'cases': recovery, 'runner_invocations': 8,
                             'test_apk_sha256_at_execution': process['test_apk_sha256']},
        'source_manifest': {'path': str(manifest_path), 'files': len(manifest), 'sha256': digest(manifest_path)},
        'evidence_manifest': {p.relative_to(ROOT).as_posix(): {'bytes': p.stat().st_size, 'sha256': digest(p)}
                              for p in sorted(set(evidence_paths))},
        'limitations': ['UI2 through UI7 are pending; language colors deferred',
                       'Complete Android suite, desktop full content comparison and font matrix not rerun for UI1',
                       'Registered legacy inputs and Git-visible baseline checked; ignored caches/history not exhaustively scanned',
                       'No physical/OEM device, real user private backup migration or offline French/Spanish voice playback verified',
                       'Development candidate retains 0.1.0/versionCode 1; no release signing, commit, push or publication'],
    }
    (OUT / 'delivery.json').write_text(json.dumps(delivery, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'passed': delivery['passed'], 'checks': checks, 'source_files': len(manifest),
                      'android': 29, 'jvm': delivery['jvm']['tests'], 'process_cases': len(recovery),
                      'sha256': checksum}, indent=2))
    return 0 if delivery['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(collect())

"""Associate the UI2 candidate with actual targeted checks and current source inputs.

Run only after the UI2 report and all evidence gates have been completed. This
collector reads existing evidence; it does not build, install, test or publish.
"""
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/qa/ui-restoration/ui2'
EXPECTED_APK_SHA256 = '6cb9344092e24cbd09db7d3b2cdf63437d834a6efe45b43543644ef82945c617'
EXPECTED_APK_BYTES = 45056486
EXPECTED_CLASSES = {
    'PlanSummaryInstrumentedTest': 10,
    'StudyLayoutInstrumentedTest': 10,
    'HomeNavigationInstrumentedTest': 9,
    'StudyUiInstrumentedTest': 1,
    'MementoTimeUiInstrumentedTest': 2,
    'MementoUiInstrumentedTest': 2,
    'CatalogInstrumentedTest': 2,
    'LegacyImportUiInstrumentedTest': 3,
    'ExtensionUiInstrumentedTest': 2,
}
EXPECTED_PROCESS_CASES = {'pending', 'completed', 'extra', 'grace'}
SNAPSHOT_NAMES = (
    'ui2-fr-front', 'ui2-fr-back', 'ui2-fr-summary',
    'ui2-es-front', 'ui2-es-back', 'ui2-es-summary',
    'ui2-large-font-rating-grid',
)


def digest(path):
    value = sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def read(name):
    return json.loads((OUT / name).read_text(encoding='utf-8-sig'))


def rooted(path):
    resolved = (ROOT / path).resolve()
    if not resolved.is_relative_to(ROOT.resolve()):
        raise ValueError(f'Evidence must stay in Memento: {path}')
    return resolved


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
    return {
        'tests': len(cases), 'expected': count,
        'passed': bool(match) and int(match[1]) == count == len(cases) and all(cases.values()),
        'cases': cases,
    }


def png_info(path):
    with path.open('rb') as stream:
        header = stream.read(24)
    valid = len(header) == 24 and header[:8] == b'\x89PNG\r\n\x1a\n' and header[12:16] == b'IHDR'
    width, height = struct.unpack('>II', header[16:24]) if valid else (0, 0)
    return {'path': path.relative_to(ROOT).as_posix(), 'width': width, 'height': height,
            'passed': valid and width > 0 and height > 0 and path.stat().st_size > 24}


def collect():
    apk_path = OUT / 'Memento-ui2-debug.apk'
    checksum = digest(apk_path)
    targeted, assets = read('accepted/result.json'), read('apk-assets.json')
    process, install = read('process-recovery/result.json'), read('install/install.json')
    preservation = read('reference-preservation.json')
    signature = (OUT / 'signature.txt').read_text(encoding='utf-8-sig')
    badging = (OUT / 'package.txt').read_text(encoding='utf-8-sig')
    lint = re.search(r'(\d+) errors, (\d+) warnings', (OUT / 'lint.txt').read_text(encoding='utf-8-sig'))
    build = (OUT / 'build.log').read_text(encoding='utf-8-sig')
    evidence_paths = [OUT / name for name in (
        'accepted/result.json', 'apk-assets.json', 'process-recovery/result.json',
        'install/install.json', 'reference-preservation.json', 'signature.txt',
        'package.txt', 'lint.txt', 'jvm-results.json', 'build.log',
        'Memento-ui2-debug.apk.sha256', 'install/default-launch.xml', 'install/default-launch.png',
    )]
    classes = []
    for row in targeted['classes']:
        path = rooted(row['log'])
        expected_path = OUT / 'accepted' / row['class'] / 'instrumentation.log'
        result = instrumentation(path)
        classes.append({'class': row['class'], 'declared_tests': int(row['tests']),
                        'expected_tests': EXPECTED_CLASSES.get(row['class']),
                        'expected_log_path': path == expected_path.resolve(), **result})
        evidence_paths.append(path)
    class_names = [row['class'] for row in classes]
    all_android_cases = [case for row in classes for case in row['cases']]

    jvm = []
    for path in sorted((OUT / 'jvm').glob('TEST-*.xml')):
        suite = ET.parse(path).getroot()
        attrs = suite.attrib
        jvm.append({key: attrs[key] for key in ('name', 'tests', 'failures', 'errors')})
        evidence_paths.append(path)
    jvm_summary = read('jvm-results.json')
    jvm_match = sorted((row['name'], int(row['tests']), int(row['failures']), int(row['errors'])) for row in jvm) == sorted(
        (row['name'], int(row['tests']), int(row['failures']), int(row['errors'])) for row in jvm_summary)

    recovery = []
    for row in process['cases']:
        name = row['case']
        if name not in EXPECTED_PROCESS_CASES:
            raise ValueError(f'Unexpected process recovery case: {name}')
        prepare_path = OUT / f'process-recovery/{name}-prepare.log'
        verify_path = OUT / f'process-recovery/{name}-verify.log'
        verification_path = OUT / f'process-recovery/{name}-verified.json'
        prepare, verify = instrumentation(prepare_path), instrumentation(verify_path)
        verification = read(f'process-recovery/{name}-verified.json')
        before_pid, after_pid = str(row['before_pid']), str(row['after_pid'])
        valid_pids = before_pid.isdigit() and after_pid.isdigit() and int(before_pid) > 0 and int(after_pid) > 0
        recovery.append({
            'case': name, 'before_pid': before_pid, 'after_pid': after_pid,
            'prepare': prepare, 'verify': verify,
            'all_user_tables_equal': verification['all_user_tables_equal_before_further_actions'],
            'passed': row['stopped_pid_empty'] and valid_pids and before_pid != after_pid
            and prepare['passed'] and verify['passed'] and prepare['tests'] == verify['tests'] == 1
            and verification['passed'] and verification['case'] == name
            and verification['all_user_tables_equal_before_further_actions'],
        })
        evidence_paths.extend((prepare_path, verify_path, verification_path))

    snapshots = []
    screenshot_dir = OUT / 'accepted/StudyLayoutInstrumentedTest/screenshots'
    for name in SNAPSHOT_NAMES:
        path = screenshot_dir / f'{name}.png'
        tree_path = screenshot_dir / f'{name}-tree.txt'
        snapshots.append(png_info(path))
        evidence_paths.extend((path, tree_path))

    projects, core = preservation['projects'], preservation['memento_preserved_core']
    project_counts = {target: project['unchanged_files'] for target, project in projects.items()}
    reference_rows_valid = all(
        project['passed'] and project['all_registered_files_unchanged'] and project['git_matches_baseline']
        and len(project['files']) == project['registered_files'] == project['unchanged_files']
        and project['git_current']['exit_code'] == 0
        and project['git_current']['porcelain'] == project['git_baseline']['porcelain']
        and all(row['exists'] and row['unchanged'] and row['bytes'] == row['baseline_bytes']
                and row['sha256'] == row['baseline_sha256'] for row in project['files'])
        for project in projects.values()
    )
    protected_rows_valid = all(
        row['exists'] and row['unchanged'] and row['bytes'] == row['baseline_bytes']
        and row['sha256'] == row['baseline_sha256']
        for row in core['files'] + core['converted_content_packages']
    )
    formal_assets = assets['embedded_formal_assets']
    asset_rows_valid = set(formal_assets) == {'fr', 'es'} and all(
        row['matches_verified_package'] and row['matches_audited_source_identity']
        for row in formal_assets.values()
    ) and all(row['matches_source'] for row in assets['embedded_contracts'].values()) \
        and all(row['matches_notice'] for row in assets['embedded_licenses'].values())
    apk_hashes = {
        'candidate': checksum, 'expected_ui2': EXPECTED_APK_SHA256,
        'targeted_android': targeted['apk_sha256'], 'embedded_assets': assets['apk_sha256'],
        'process_recovery': process['application_apk_sha256'], 'install_input': install['apk_sha256'],
        'installed_device_apk': install['installed_apk_sha256'],
        'current_build': digest(ROOT / 'app/build/outputs/apk/debug/app-debug.apk'),
        'checksum_file': (OUT / 'Memento-ui2-debug.apk.sha256').read_text(encoding='utf-8-sig').split()[0],
    }
    checks = {
        'targeted_android': targeted['passed'] and int(targeted['tests']) == 41
        and len(classes) == len(EXPECTED_CLASSES) == len(set(class_names))
        and set(class_names) == set(EXPECTED_CLASSES)
        and sum(row['tests'] for row in classes) == 41
        and len(all_android_cases) == len(set(all_android_cases)) == 41
        and all(row['passed'] and row['expected_log_path']
                and row['tests'] == row['declared_tests'] == row['expected_tests']
                and all(case.startswith('com.scott.memento.' + row['class'] + '.') for case in row['cases'])
                for row in classes),
        'jvm': bool(jvm) and jvm_match and sum(int(row['tests']) for row in jvm) == 21
        and all(int(row['failures']) == int(row['errors']) == 0 for row in jvm),
        'lint': lint is not None and int(lint[1]) == 0,
        'build': 'BUILD SUCCESSFUL' in build and 'BUILD FAILED' not in build,
        'process_recovery': process['passed'] and len(recovery) == 4
        and {row['case'] for row in recovery} == EXPECTED_PROCESS_CASES
        and process['instrumentation_invocations'] == 8 and process['uses_force_stop']
        and not process['clears_default_database'] and all(row['passed'] for row in recovery),
        'formal_install': install['passed'] and install['ui_ready']
        and install['application_id'] == 'com.scott.memento' and install['avd'] == 'MementoApi35'
        and install['default_database_existed_before'] and install['default_database_preserved_during_install']
        and bool(install['default_database_before_install_sha256'])
        and install['default_database_before_install_sha256'] == install['default_database_after_install_sha256']
        and not install['clears_data'] and not install['uses_qa_extras'],
        'apk_association': set(apk_hashes.values()) == {EXPECTED_APK_SHA256}
        and apk_path.stat().st_size == assets['bytes'] == EXPECTED_APK_BYTES,
        'signature': 'Verifies' in signature
        and 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature and 'CN=Android Debug' in signature,
        'identity': all(value in badging for value in ("name='com.scott.memento'", "versionCode='1'",
                       "versionName='0.1.0'", "sdkVersion:'26'", "targetSdkVersion:'35'")),
        'embedded_assets': assets['passed'] and assets['test_fixtures_excluded'] and asset_rows_valid,
        'registered_reference_preservation': preservation['passed'] and preservation['stage'] == 'UI2'
        and project_counts == {'fr': 84, 'es': 68} and reference_rows_valid,
        'core_preservation': core['passed'] and core['unchanged_files'] == len(core['files']) == 15
        and len(core['converted_content_packages']) == 2 and core['schema_file_set_matches_p8']
        and core['schema_current_paths'] == core['schema_baseline_paths'] and protected_rows_valid,
        'fresh_ui2_screenshots': len(snapshots) == 7 and all(row['passed'] for row in snapshots)
        and all((screenshot_dir / f'{name}-tree.txt').stat().st_size > 0 for name in SNAPSHOT_NAMES),
    }

    files = set()
    for directory in ('app/src', 'schemas', 'gradle', 'tools'):
        files.update(path for path in (ROOT / directory).rglob('*')
                     if path.is_file() and '__pycache__' not in path.parts and path.suffix != '.pyc')
    files.update(ROOT / name for name in (
        'gradlew', 'gradlew.bat', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
        'local.properties', 'app/build.gradle.kts', '.gitignore', 'AGENTS.md', 'README.md',
        'MULTILINGUAL_REFACTOR_PLAN.md', 'docs/DEVELOPMENT_PHASES.md', 'docs/decisions/PRODUCT_RULES.md',
        'docs/ui/LEGACY_LAYOUT_RESTORATION_PLAN.md', 'docs/qa/ui-restoration/ui2/ACCEPTANCE.md',
        'data/manifests/resolved-build-artifacts.json', 'data/manifests/content-packages.json',
    ))
    manifest = {path.relative_to(ROOT).as_posix(): {'bytes': path.stat().st_size, 'sha256': digest(path)}
                for path in sorted(files)}
    manifest_path = OUT / 'source-manifest.json'
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    delivery = {
        'stage': 'UI2', 'verified_at_utc': datetime.now(timezone.utc).isoformat(),
        'scope': 'UI2 targeted phase acceptance; nine separate Android runners, not the complete release suite',
        'application_id': 'com.scott.memento', 'version_name': '0.1.0', 'version_code': 1,
        'min_sdk': 26, 'target_sdk': 35, 'signing': 'Android Debug / APK v2',
        'apk': str(apk_path), 'bytes': apk_path.stat().st_size, 'sha256': checksum,
        'apk_association': apk_hashes, 'checks': checks, 'passed': all(checks.values()),
        'android': {'tests': sum(row['tests'] for row in classes), 'runner_invocations': len(classes),
                    'new_tests': 20, 'classes': classes},
        'jvm': {'tests': sum(int(row['tests']) for row in jvm), 'classes': jvm},
        'lint': {'errors': int(lint[1]), 'warnings': int(lint[2])} if lint else None,
        'process_recovery': {'cases': recovery, 'runner_invocations': process['instrumentation_invocations'],
                             'test_apk_sha256_at_execution': process['test_apk_sha256']},
        'reference_preservation': {'old_registered_files': project_counts,
                                   'protected_memento_files': core['unchanged_files'], 'converted_packages': 2},
        'screenshots': snapshots,
        'source_manifest': {'path': str(manifest_path), 'files': len(manifest), 'sha256': digest(manifest_path)},
        'evidence_manifest': {path.relative_to(ROOT).as_posix(): {'bytes': path.stat().st_size, 'sha256': digest(path)}
                              for path in sorted(set(evidence_paths))},
        'limitations': [
            'UI3 through UI7 remain pending; language colors deferred',
            'Complete Android release suite and desktop full content comparison not rerun for UI2',
            'Font scale 1.6 verified only in the rating component test, not a complete typography/device matrix',
            'Screenshot evidence is restricted to seven UI2 captures from StudyLayoutInstrumentedTest; other pulled files are historical',
            'Registered legacy inputs and Git-visible baseline checked; ignored caches/history not exhaustively scanned',
            'No physical/OEM device, real user private backup migration or offline French/Spanish voice playback verified',
            'Development candidate retains 0.1.0/versionCode 1; no release signing, commit, push or publication',
        ],
    }
    (OUT / 'delivery.json').write_text(json.dumps(delivery, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'passed': delivery['passed'], 'checks': checks, 'source_files': len(manifest),
                      'android': delivery['android']['tests'], 'jvm': delivery['jvm']['tests'],
                      'process_cases': len(recovery), 'sha256': checksum}, indent=2))
    return 0 if delivery['passed'] else 1


if __name__ == '__main__':
    try:
        raise SystemExit(collect())
    except (OSError, KeyError, ValueError, ET.ParseError) as error:
        print(json.dumps({'passed': False, 'error': str(error),
                          'instruction': 'Complete or repair all UI2 evidence gates before running this collector.'}, indent=2))
        raise SystemExit(1)

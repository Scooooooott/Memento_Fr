"""Associate the UI3 candidate with actual targeted checks and current source inputs.

Run only after the UI3 report and all evidence gates have been completed. This
collector reads existing evidence; it does not build, install, test or publish.
"""
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET
from zipfile import BadZipFile, ZipFile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/qa/ui-restoration/ui3'
EXPECTED_APK_SHA256 = '32ef911a6e2f9d146042eabc902dd2cd0f0aad711b1b6ddb45361dbbb52760a2'
EXPECTED_APK_BYTES = 45062378
EXPECTED_CLASSES = {
    'DetailLayoutInstrumentedTest': 11,
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
EXPECTED_JVM_CLASSES = {
    'com.scott.memento.domain.fsrs.FsrsSchedulerTest': 5,
    'com.scott.memento.domain.LanguageCapabilitiesTest': 5,
    'com.scott.memento.domain.LanguageRegistryTest': 6,
    'com.scott.memento.domain.SearchKeyTest': 2,
    'com.scott.memento.domain.VoiceSelectionTest': 3,
    'com.scott.memento.ui.language.GrammarTableModelsTest': 9,
}
SNAPSHOT_NAMES = (
    'ui3-fr-grammar', 'ui3-fr-forms', 'ui3-es-grammar', 'ui3-es-regional',
    'ui3-tab-restore', 'ui3-nonverb', 'ui3-long-note',
)
SNAPSHOT_TAGS = {
    name: ('word_detail', 'grammar_table') for name in SNAPSHOT_NAMES
    if name not in {'ui3-fr-forms', 'ui3-nonverb'}
}
SNAPSHOT_TAGS.update({
    'ui3-fr-forms': ('word_detail', 'detail_forms', 'detail_feature_auxiliary'),
    'ui3-nonverb': ('word_detail', 'grammar_empty'),
})
FORMAL_SAMPLES = {
    'fr': {'fr:\u00eatre:verb:1': (3, 18), 'fr:avoir:verb:1': (3, 18)},
    'es': {'es:ser:verb:1': (11, 64), 'es:hablar:verb:1': (13, 66)},
}


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
    fields, cases, duplicate_cases = {}, {}, []
    for line in log.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, _, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            fields[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.split(': ', 1)[1])
            if code != 1 and 'class' in fields and 'test' in fields:
                case = fields['class'] + '.' + fields['test']
                if case in cases:
                    duplicate_cases.append(case)
                cases[case] = code == 0 and cases.get(case, True)
            fields = {}
    match = re.search(r'OK \((\d+) tests?\)', log)
    expected = re.search(r'INSTRUMENTATION_STATUS: numtests=(\d+)', log)
    count = int(expected[1]) if expected else 0
    return {
        'tests': len(cases), 'expected': count,
        'passed': bool(match) and int(match[1]) == count == len(cases) and all(cases.values())
        and not duplicate_cases and 'FAILURES!!!' not in log and 'INSTRUMENTATION_FAILED' not in log,
        'cases': cases, 'duplicate_cases': duplicate_cases,
    }


def png_info(path):
    with path.open('rb') as stream:
        header = stream.read(24)
    valid = len(header) == 24 and header[:8] == b'\x89PNG\r\n\x1a\n' and header[12:16] == b'IHDR'
    width, height = struct.unpack('>II', header[16:24]) if valid else (0, 0)
    return {'path': path.relative_to(ROOT).as_posix(), 'width': width, 'height': height,
            'passed': valid and width > 0 and height > 0 and path.stat().st_size > 24}


def collect():
    # Clear a previous success before collecting, including when an input is missing.
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / 'delivery.json').write_text(json.dumps({
        'stage': 'UI3', 'passed': False, 'status': 'collecting',
    }, indent=2) + '\n', encoding='utf-8')
    apk_path = OUT / 'Memento-ui3-debug.apk'
    checksum = digest(apk_path)
    targeted, assets = read('accepted/result.json'), read('apk-assets.json')
    process, install = read('process-recovery/result.json'), read('install/install.json')
    preservation = read('reference-preservation.json')
    grammar = read('grammar-reference.json')
    signature = (OUT / 'signature.txt').read_text(encoding='utf-8-sig')
    badging = (OUT / 'package.txt').read_text(encoding='utf-8-sig')
    lint = re.search(r'(\d+) errors, (\d+) warnings', (OUT / 'lint.txt').read_text(encoding='utf-8-sig'))
    build = (OUT / 'build.log').read_text(encoding='utf-8-sig')
    evidence_paths = [OUT / name for name in (
        'accepted/result.json', 'apk-assets.json', 'process-recovery/result.json',
        'install/install.json', 'reference-preservation.json', 'signature.txt',
        'package.txt', 'lint.txt', 'jvm-results.json', 'build.log',
        'Memento-ui3-debug.apk.sha256', 'install/default-launch.xml', 'install/default-launch.png',
        'grammar-reference.json', 'GRAMMAR_REFERENCE.md', 'ACCEPTANCE.md',
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
        jvm[-1]['actual_cases'] = len(suite.findall('testcase'))
        jvm[-1]['skipped'] = int(attrs.get('skipped', 0))
        jvm[-1]['case_failures'] = sum(bool(case.findall('failure') or case.findall('error')
                                           or case.findall('skipped')) for case in suite.findall('testcase'))
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
            and verification['all_user_tables_equal_before_further_actions']
            and row['database'] == 'memento-test-process-recovery.db',
        })
        evidence_paths.extend((prepare_path, verify_path, verification_path))

    snapshots = []
    screenshot_dir = OUT / 'accepted/DetailLayoutInstrumentedTest/screenshots'
    for name in SNAPSHOT_NAMES:
        path = screenshot_dir / f'{name}.png'
        tree_path = screenshot_dir / f'{name}-tree.txt'
        snapshot = png_info(path)
        tree = tree_path.read_text(encoding='utf-8-sig')
        snapshot['tree_path'] = tree_path.relative_to(ROOT).as_posix()
        snapshot['expected_tags'] = list(SNAPSHOT_TAGS[name])
        snapshot['expected_tags_present'] = all(tag in tree for tag in SNAPSHOT_TAGS[name])
        snapshot['source'] = 'independent synthetic renderer fixture' if name == 'ui3-long-note' else 'formal content / real Activity'
        snapshot['passed'] = snapshot['passed'] and snapshot['expected_tags_present']
        snapshots.append(snapshot)
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
        and rooted(row['path']).stat().st_size == row['baseline_bytes']
        and digest(rooted(row['path'])) == row['baseline_sha256']
        for row in core['files'] + core['converted_content_packages']
    )
    formal_assets = assets['embedded_formal_assets']
    formal_embedded_hashes = {}
    with ZipFile(apk_path) as archive:
        for target in FORMAL_SAMPLES:
            value = sha256()
            with archive.open(f'assets/content/{target}.db') as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    value.update(block)
            formal_embedded_hashes[target] = value.hexdigest()
    asset_rows_valid = set(formal_assets) == {'fr', 'es'} and all(
        row['matches_verified_package'] and row['matches_audited_source_identity']
        for row in formal_assets.values()
    ) and all(row['matches_source'] for row in assets['embedded_contracts'].values()) \
        and all(row['matches_notice'] for row in assets['embedded_licenses'].values())
    apk_hashes = {
        'candidate': checksum, 'expected_ui3': EXPECTED_APK_SHA256,
        'targeted_android': targeted['apk_sha256'], 'embedded_assets': assets['apk_sha256'],
        'process_recovery': process['application_apk_sha256'], 'install_input': install['apk_sha256'],
        'installed_device_apk': install['installed_apk_sha256'],
        'current_build': digest(ROOT / 'app/build/outputs/apk/debug/app-debug.apk'),
        'checksum_file': (OUT / 'Memento-ui3-debug.apk.sha256').read_text(encoding='utf-8-sig').split()[0],
    }
    test_apk_hashes = {
        'process_recovery': process['test_apk_sha256'],
        'current_build': digest(ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'),
    }
    sample_counts = {
        target: {
            uid: (sample['paradigm_count'], sample['conjugated_form_count'])
            for uid, sample in package['samples'].items()
        } for target, package in grammar['packages'].items()
    }
    grammar_packages_valid = all(
        package['database_unchanged'] and package['asset_matches_content_package']
        and package['query_only'] == 1
        and package['sha256_before'] == package['sha256_after'] == package['asset_sha256']
        == digest(rooted(package['path'])) == digest(rooted(package['asset_path']))
        == formal_assets[target]['sha256'] == formal_embedded_hashes[target]
        for target, package in grammar['packages'].items()
    )
    checks = {
        'targeted_android': targeted['passed'] and int(targeted['tests']) == 42
        and len(classes) == len(EXPECTED_CLASSES) == len(set(class_names))
        and set(class_names) == set(EXPECTED_CLASSES)
        and sum(row['tests'] for row in classes) == 42
        and len(all_android_cases) == len(set(all_android_cases)) == 42
        and all(row['passed'] and row['expected_log_path']
                and row['tests'] == row['declared_tests'] == row['expected_tests']
                and all(case.startswith('com.scott.memento.' + row['class'] + '.') for case in row['cases'])
                for row in classes),
        'jvm': bool(jvm) and jvm_match and sum(int(row['tests']) for row in jvm) == 30
        and len(jvm) == len(EXPECTED_JVM_CLASSES)
        and {row['name']: int(row['tests']) for row in jvm} == EXPECTED_JVM_CLASSES
        and all(int(row['failures']) == int(row['errors']) == row['skipped'] == row['case_failures'] == 0
                and row['actual_cases'] == int(row['tests']) for row in jvm),
        'lint': lint is not None and int(lint[1]) == 0 and int(lint[2]) == 5,
        'build': 'BUILD SUCCESSFUL' in build and 'BUILD FAILED' not in build,
        'process_recovery': process['passed'] and len(recovery) == 4
        and {row['case'] for row in recovery} == EXPECTED_PROCESS_CASES
        and process['instrumentation_invocations'] == 8 and process['uses_force_stop']
        and process['avd'] == 'MementoApi35' and process['emulator'] == 'emulator-5562'
        and not process['clears_default_database'] and all(row['passed'] for row in recovery),
        'formal_install': install['passed'] and install['ui_ready']
        and install['application_id'] == 'com.scott.memento' and install['avd'] == 'MementoApi35'
        and install['serial'] == 'emulator-5562'
        and install['default_database_existed_before'] and install['default_database_preserved_during_install']
        and bool(install['default_database_before_install_sha256'])
        and install['default_database_before_install_sha256'] == install['default_database_after_install_sha256']
        and not install['clears_data'] and not install['uses_qa_extras'],
        'apk_association': set(apk_hashes.values()) == {EXPECTED_APK_SHA256}
        and re.fullmatch(r'[0-9a-f]{64}', checksum) is not None
        and apk_path.stat().st_size == assets['bytes'] == EXPECTED_APK_BYTES,
        'test_apk_association': len(set(test_apk_hashes.values())) == 1,
        'signature': 'Verifies' in signature
        and 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature and 'CN=Android Debug' in signature,
        'identity': all(value in badging for value in ("name='com.scott.memento'", "versionCode='1'",
                       "versionName='0.1.0'", "sdkVersion:'26'", "targetSdkVersion:'35'")),
        'embedded_assets': assets['passed'] and assets['test_fixtures_excluded'] and asset_rows_valid,
        'registered_reference_preservation': preservation['passed'] and preservation['stage'] == 'UI3'
        and project_counts == {'fr': 84, 'es': 68} and reference_rows_valid,
        'core_preservation': core['passed'] and core['unchanged_files'] == len(core['files']) == 15
        and len(core['converted_content_packages']) == 2 and core['schema_file_set_matches_p8']
        and core['schema_current_paths'] == core['schema_baseline_paths'] and protected_rows_valid,
        'fresh_ui3_screenshots': len(snapshots) == 7 and all(row['passed'] for row in snapshots)
        and all((screenshot_dir / f'{name}-tree.txt').stat().st_size > 0 for name in SNAPSHOT_NAMES),
        'formal_grammar_reference': grammar['stage'] == 'UI3' and grammar['read_boundary_passed']
        and set(grammar['packages']) == {'fr', 'es'} and grammar_packages_valid
        and sample_counts == FORMAL_SAMPLES
        and grammar['packages']['es']['imperativo_yo_count'] == 0
        and grammar['packages']['es']['unknown_subject_keys'] == []
        and grammar['packages']['es']['reflexive_feature_distribution'] == [{'value': '0', 'n': 2234}]
        and len(grammar['packages']['es']['vos_entries']) == 2
        and len(grammar['known_content_findings']) == 2,
    }

    files = set()
    for directory in ('app/src', 'schemas', 'gradle', 'tools'):
        files.update(path for path in (ROOT / directory).rglob('*')
                     if path.is_file() and '__pycache__' not in path.parts and path.suffix != '.pyc')
    files.update(ROOT / name for name in (
        'gradlew', 'gradlew.bat', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
        'local.properties', 'app/build.gradle.kts', '.gitignore', 'AGENTS.md', 'README.md',
        'MULTILINGUAL_REFACTOR_PLAN.md', 'docs/DEVELOPMENT_PHASES.md', 'docs/decisions/PRODUCT_RULES.md',
        'docs/ui/LEGACY_LAYOUT_RESTORATION_PLAN.md', 'docs/qa/ui-restoration/ui3/ACCEPTANCE.md',
        'docs/qa/ui-restoration/ui3/GRAMMAR_REFERENCE.md', 'docs/qa/ui-restoration/ui3/grammar-reference.json',
        'data/manifests/resolved-build-artifacts.json', 'data/manifests/content-packages.json',
    ))
    manifest = {path.relative_to(ROOT).as_posix(): {'bytes': path.stat().st_size, 'sha256': digest(path)}
                for path in sorted(files)}
    manifest_path = OUT / 'source-manifest.json'
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    delivery = {
        'stage': 'UI3', 'verified_at_utc': datetime.now(timezone.utc).isoformat(),
        'scope': 'UI3 targeted phase acceptance; nine separate Android runners, not the complete release suite',
        'application_id': 'com.scott.memento', 'version_name': '0.1.0', 'version_code': 1,
        'min_sdk': 26, 'target_sdk': 35, 'signing': 'Android Debug / APK v2',
        'apk': str(apk_path), 'bytes': apk_path.stat().st_size, 'sha256': checksum,
        'apk_association': apk_hashes, 'test_apk_association': test_apk_hashes,
        'checks': checks, 'passed': all(checks.values()),
        'android': {'tests': sum(row['tests'] for row in classes), 'runner_invocations': len(classes),
                    'new_tests': 11, 'classes': classes},
        'jvm': {'tests': sum(int(row['tests']) for row in jvm), 'new_tests': 9, 'classes': jvm},
        'lint': {'errors': int(lint[1]), 'warnings': int(lint[2])} if lint else None,
        'process_recovery': {'cases': recovery, 'runner_invocations': process['instrumentation_invocations'],
                             'test_apk_sha256_at_execution': process['test_apk_sha256']},
        'reference_preservation': {'old_registered_files': project_counts,
                                   'protected_memento_files': core['unchanged_files'], 'converted_packages': 2},
        'screenshots': snapshots,
        'grammar_reference': {
            'formal_samples': sample_counts, 'formal_package_apk_hashes': formal_embedded_hashes,
            'independent_fixture_only': grammar['independent_fixture_only'],
            'known_content_findings': grammar['known_content_findings'],
            'semantic_validation': grammar['semantic_validation'],
        },
        'source_manifest': {'path': str(manifest_path), 'files': len(manifest), 'sha256': digest(manifest_path)},
        'evidence_manifest': {path.relative_to(ROOT).as_posix(): {'bytes': path.stat().st_size, 'sha256': digest(path)}
                              for path in sorted(set(evidence_paths))},
        'limitations': [
            'UI4 through UI7 remain pending; language colors deferred',
            'Complete Android release suite and desktop full content comparison not rerun for UI3',
            'Whole-detail enlarged-font, landscape and OEM/device matrix remain pending',
            'Screenshot evidence is restricted to seven UI3 captures from DetailLayoutInstrumentedTest; other pulled files are historical',
            'Official vos coverage is two hablar rows only; reflexive and unknown-key coverage uses independent synthetic fixtures',
            'Source-cell equality proves rendering fidelity, not language semantics; known avoir/etre compound auxiliary inconsistencies remain unmodified',
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
    except (OSError, KeyError, ValueError, ET.ParseError, BadZipFile) as error:
        failure = {'stage': 'UI3', 'passed': False, 'status': 'incomplete-evidence', 'error': str(error),
                   'instruction': 'Complete or repair all UI3 evidence gates before running this collector.'}
        (OUT / 'delivery.json').write_text(json.dumps(failure, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(failure, indent=2))
        raise SystemExit(1)

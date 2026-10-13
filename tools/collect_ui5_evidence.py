"""Associate the UI5 development candidate with this phase's actual checks.

Run after the UI5 report and all evidence gates have been completed. This
collector reads existing evidence; it does not build, install, test or publish.
Missing evidence clears an earlier success and fails the phase gate.
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
OUT = ROOT / 'docs/qa/ui-restoration/ui5'
EXPECTED_CLASSES = {
    'SettingsLayoutInstrumentedTest': 14,
    'LibraryLayoutInstrumentedTest': 11,
    'LibraryQueryInstrumentedTest': 5,
    'DetailLayoutInstrumentedTest': 11,
    'StudyLayoutInstrumentedTest': 10,
    'HomeNavigationInstrumentedTest': 9,
    'StudyUiInstrumentedTest': 1,
    'MementoTimeUiInstrumentedTest': 3,
    'MementoUiInstrumentedTest': 2,
    'CatalogInstrumentedTest': 2,
    'LegacyImportUiInstrumentedTest': 3,
    'ExtensionUiInstrumentedTest': 2,
}
EXPECTED_JVM_CLASSES = {
    'com.scott.memento.domain.fsrs.FsrsSchedulerTest': 5,
    'com.scott.memento.domain.LanguageCapabilitiesTest': 5,
    'com.scott.memento.domain.LanguageRegistryTest': 6,
    'com.scott.memento.domain.SearchKeyTest': 2,
    'com.scott.memento.domain.VoiceSelectionTest': 3,
    'com.scott.memento.ui.language.GrammarTableModelsTest': 9,
    'com.scott.memento.ui.library.LibraryPagerTest': 10,
}
EXPECTED_PROCESS_CASES = {'pending', 'completed', 'extra', 'grace'}
EXPECTED_LINT_WARNINGS = 5
SNAPSHOT_TAGS = {
    'ui5-fr-settings': ('settings_form', 'settings_language_section', 'settings_plan_section', 'study_save'),
    'ui5-es-settings': ('settings_form', 'settings_language_section', 'settings_plan_section', 'study_save'),
    'ui5-fr-books': ('book_picker', 'book_selection_screen', 'save_book', 'cancel_book'),
    'ui5-es-books': ('book_picker', 'book_selection_screen', 'save_book', 'cancel_book'),
    'ui5-import': ('legacy_import', 'import_source_section', 'import_choose_file', 'import_back'),
    'ui5-onboarding': ('onboarding', 'settings_target_fr', 'settings_target_es', 'study_save'),
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


def source_descriptor(class_name, tree):
    if re.fullmatch(r'com\.scott\.memento(?:\.[A-Za-z_]\w*)+', class_name) is None:
        raise ValueError(f'Unexpected test source identity: {class_name}')
    path = ROOT / 'app/src' / tree / 'java' / Path(*class_name.split('.')).with_suffix('.kt')
    text = path.read_text(encoding='utf-8-sig')
    methods = re.findall(r'@Test\s+fun\s+(\w+)\s*\(', text)
    return {'path': path.relative_to(ROOT).as_posix(), 'sha256': digest(path),
            'declared_test_methods': methods, 'duplicate_methods': len(methods) != len(set(methods))}


def collect():
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / 'delivery.json').write_text(json.dumps({
        'stage': 'UI5', 'passed': False, 'status': 'collecting',
    }, indent=2) + '\n', encoding='utf-8')
    apk_path = OUT / 'Memento-ui5-debug.apk'
    checksum_path = OUT / 'Memento-ui5-debug.apk.sha256'
    expected_checksum = checksum_path.read_text(encoding='utf-8-sig').split()[0].lower()
    if re.fullmatch(r'[0-9a-f]{64}', expected_checksum) is None:
        raise ValueError('UI5 checksum file must contain a SHA-256 value')
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
        'Memento-ui5-debug.apk.sha256', 'install/default-launch.xml', 'install/default-launch.png',
        'SETTINGS_REFERENCE.md', 'ACCEPTANCE.md',
    )]
    evidence_paths.extend(sorted((OUT / 'initial-attempts').glob('*.log')))
    evidence_paths.extend(OUT / name for name in ('diagnostics/library-target/instrumentation.log', 'diagnostics/catalog/instrumentation.log', 'diagnostics/settings-metadata-logcat.txt'))
    optional_build_logs = sorted(OUT.glob('*rebuild*.log'))
    evidence_paths.extend(optional_build_logs)
    # Keep failed historical attempts as evidence without treating them as the
    # accepted build. A final rebuild log, when present, must itself succeed.
    accepted_rebuild_logs = [OUT / name for name in (
        'test-rebuild.log', 'app-rebuild.log', 'final-rebuild.log',
    ) if (OUT / name).is_file()]
    source_tests = {}
    classes = []
    app_build = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    test_build = ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    test_checksum = digest(test_build)
    for row in targeted['classes']:
        name = row['class']
        source = source_descriptor('com.scott.memento.' + name, 'androidTest')
        source_tests[name] = source
        path = rooted(row['log'])
        expected_path = OUT / 'accepted' / name / 'instrumentation.log'
        result = instrumentation(path)
        methods = {case.rsplit('.', 1)[1] for case in result['cases']}
        classes.append({'class': name, 'declared_tests': int(row['tests']),
                        'expected_tests': EXPECTED_CLASSES.get(name),
                        'expected_log_path': path == expected_path.resolve(),
                        'runner_declared_passed': row['passed'],
                        'source_methods_match': methods == set(source['declared_test_methods'])
                        and not source['duplicate_methods'],
                        'application_apk_sha256': row['application_apk_sha256'],
                        'application_apk_sha256_after': row['application_apk_sha256_after'],
                        'test_apk_sha256': row['test_apk_sha256'],
                        'test_apk_sha256_after': row['test_apk_sha256_after'], **result})
        evidence_paths.append(path)
    class_names = [row['class'] for row in classes]
    all_android_cases = [case for row in classes for case in row['cases']]

    jvm = []
    for path in sorted((OUT / 'jvm').glob('TEST-*.xml')):
        suite = ET.parse(path).getroot()
        attrs = suite.attrib
        source = source_descriptor(attrs['name'], 'test')
        source_tests[attrs['name']] = source
        methods = [case.attrib['name'] for case in suite.findall('testcase')]
        jvm.append({key: attrs[key] for key in ('name', 'tests', 'failures', 'errors')})
        jvm[-1].update(actual_cases=len(methods), skipped=int(attrs.get('skipped', 0)),
                       source_methods_match=set(methods) == set(source['declared_test_methods'])
                       and len(methods) == len(set(methods)) and not source['duplicate_methods'],
                       case_failures=sum(bool(case.findall('failure') or case.findall('error')
                                              or case.findall('skipped')) for case in suite.findall('testcase')))
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
    screenshot_dir = OUT / 'accepted/SettingsLayoutInstrumentedTest/screenshots'
    for name, tags in SNAPSHOT_TAGS.items():
        path = screenshot_dir / f'{name}.png'
        tree_path = screenshot_dir / f'{name}-tree.txt'
        snapshot = png_info(path)
        tree = tree_path.read_text(encoding='utf-8-sig')
        snapshot.update(tree_path=tree_path.relative_to(ROOT).as_posix(), expected_tags=list(tags),
                        expected_tags_present=all(tag in tree for tag in tags),
                        source='production MainActivity, formal registry, named isolated test database')
        snapshot['passed'] = snapshot['passed'] and snapshot['expected_tags_present'] and bool(tree)
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
        for target in ('fr', 'es'):
            value = sha256()
            with archive.open(f'assets/content/{target}.db') as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    value.update(block)
            formal_embedded_hashes[target] = value.hexdigest()
    asset_rows_valid = set(formal_assets) == {'fr', 'es'} and all(
        row['matches_verified_package'] and row['matches_audited_source_identity']
        and row['sha256'] == formal_embedded_hashes[target]
        for target, row in formal_assets.items()
    ) and all(row['matches_source'] for row in assets['embedded_contracts'].values()) \
        and all(row['matches_notice'] for row in assets['embedded_licenses'].values())
    apk_hashes = {
        'candidate': checksum, 'checksum_file': expected_checksum,
        'targeted_android': targeted['apk_sha256'], 'embedded_assets': assets['apk_sha256'],
        'process_recovery': process['application_apk_sha256'], 'install_input': install['apk_sha256'],
        'installed_device_apk': install['installed_apk_sha256'], 'current_build': digest(app_build),
        **{'runner_' + row['class']: row['application_apk_sha256'] for row in classes},
        **{'runner_' + row['class'] + '_after': row['application_apk_sha256_after'] for row in classes},
    }
    test_apk_hashes = {
        'targeted_android': targeted['test_apk_sha256'],
        'process_recovery': process['test_apk_sha256'], 'current_build': test_checksum,
        **{'runner_' + row['class']: row['test_apk_sha256'] for row in classes},
        **{'runner_' + row['class'] + '_after': row['test_apk_sha256_after'] for row in classes},
    }
    settings_sources = {
        name: {'path': name, 'sha256': digest(ROOT / name)} for name in (
            'app/src/main/java/com/scott/memento/ui/SettingsPane.kt',
            'app/src/main/java/com/scott/memento/ui/settings/SettingsDraft.kt',
            'app/src/main/java/com/scott/memento/ui/settings/BookPicker.kt',
            'app/src/main/java/com/scott/memento/ui/ImportPane.kt',
            'app/src/main/java/com/scott/memento/ui/MementoApp.kt',
            'app/src/main/java/com/scott/memento/ui/MementoController.kt',
            'docs/qa/ui-restoration/ui5/SETTINGS_REFERENCE.md',
        )
    }
    checks = {
        'targeted_android': targeted['passed'] and 'UI5' in targeted['scope'] and int(targeted['tests']) == 73
        and len(classes) == len(EXPECTED_CLASSES) == len(set(class_names))
        and set(class_names) == set(EXPECTED_CLASSES)
        and sum(row['tests'] for row in classes) == 73
        and len(all_android_cases) == len(set(all_android_cases)) == 73
        and all(row['passed'] and row['runner_declared_passed'] and row['expected_log_path']
                and row['source_methods_match'] and row['tests'] == row['declared_tests'] == row['expected_tests']
                and all(case.startswith('com.scott.memento.' + row['class'] + '.') for case in row['cases'])
                for row in classes),
        'jvm': bool(jvm) and jvm_match and sum(int(row['tests']) for row in jvm) == 40
        and len(jvm) == len(EXPECTED_JVM_CLASSES)
        and {row['name']: int(row['tests']) for row in jvm} == EXPECTED_JVM_CLASSES
        and all(int(row['failures']) == int(row['errors']) == row['skipped'] == row['case_failures'] == 0
                and row['actual_cases'] == int(row['tests']) and row['source_methods_match'] for row in jvm),
        'lint': lint is not None and int(lint[1]) == 0 and int(lint[2]) == EXPECTED_LINT_WARNINGS,
        'build': 'BUILD SUCCESSFUL' in build and 'BUILD FAILED' not in build
        and all('BUILD SUCCESSFUL' in path.read_text(encoding='utf-8-sig')
                and 'BUILD FAILED' not in path.read_text(encoding='utf-8-sig') for path in accepted_rebuild_logs),
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
        'apk_association': set(apk_hashes.values()) == {expected_checksum}
        and apk_path.stat().st_size == assets['bytes'],
        'test_apk_association': set(test_apk_hashes.values()) == {test_checksum}
        and re.fullmatch(r'[0-9a-f]{64}', test_checksum) is not None,
        'runner_apk_stability': all(
            row['application_apk_sha256'] == row['application_apk_sha256_after'] == apk_hashes['current_build']
            and row['test_apk_sha256'] == row['test_apk_sha256_after'] == test_checksum
            for row in classes),
        'signature': 'Verifies' in signature
        and 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature and 'CN=Android Debug' in signature,
        'identity': all(value in badging for value in ("name='com.scott.memento'", "versionCode='1'",
                       "versionName='0.1.0'", "sdkVersion:'26'", "targetSdkVersion:'35'")),
        'embedded_assets': assets['passed'] and assets['test_fixtures_excluded'] and asset_rows_valid,
        'registered_reference_preservation': preservation['passed'] and preservation['stage'] == 'UI5'
        and project_counts == {'fr': 84, 'es': 68} and reference_rows_valid,
        'core_preservation': core['passed'] and core['unchanged_files'] == len(core['files']) == 15
        and len(core['converted_content_packages']) == 2 and core['schema_file_set_matches_p8']
        and core['schema_current_paths'] == core['schema_baseline_paths'] and protected_rows_valid,
        'fresh_ui5_screenshots': len(snapshots) == 6 and all(row['passed'] for row in snapshots),
        'settings_test_source_association': source_tests['SettingsLayoutInstrumentedTest']['declared_test_methods']
        and len(source_tests['SettingsLayoutInstrumentedTest']['declared_test_methods']) == 14
        and all(row['source_methods_match'] for row in classes if row['class']=='SettingsLayoutInstrumentedTest'),
    }

    files = set()
    for directory in ('app/src', 'schemas', 'gradle', 'tools'):
        files.update(path for path in (ROOT / directory).rglob('*')
                     if path.is_file() and '__pycache__' not in path.parts and path.suffix != '.pyc')
    files.update(ROOT / name for name in (
        'gradlew', 'gradlew.bat', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
        'local.properties', 'app/build.gradle.kts', '.gitignore', 'AGENTS.md', 'README.md',
        'MULTILINGUAL_REFACTOR_PLAN.md', 'docs/DEVELOPMENT_PHASES.md', 'docs/decisions/PRODUCT_RULES.md',
        'docs/ui/LEGACY_LAYOUT_RESTORATION_PLAN.md', 'docs/qa/ui-restoration/ui5/ACCEPTANCE.md',
        'docs/qa/ui-restoration/ui5/SETTINGS_REFERENCE.md',
        'data/manifests/resolved-build-artifacts.json', 'data/manifests/content-packages.json',
    ))
    manifest = {path.relative_to(ROOT).as_posix(): {'bytes': path.stat().st_size, 'sha256': digest(path)}
                for path in sorted(files)}
    manifest_path = OUT / 'source-manifest.json'
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    delivery = {
        'stage': 'UI5', 'verified_at_utc': datetime.now(timezone.utc).isoformat(),
        'scope': 'UI5 targeted phase acceptance; twelve separate Android runners, not the complete release suite',
        'application_id': 'com.scott.memento', 'version_name': '0.1.0', 'version_code': 1,
        'min_sdk': 26, 'target_sdk': 35, 'signing': 'Android Debug / APK v2',
        'apk': str(apk_path), 'bytes': apk_path.stat().st_size, 'sha256': checksum,
        'apk_association': apk_hashes, 'test_apk_association': test_apk_hashes,
        'checks': checks, 'passed': all(checks.values()),
        'android': {'tests': sum(row['tests'] for row in classes), 'runner_invocations': len(classes),
                    'new_tests': 15, 'classes': classes},
        'jvm': {'tests': sum(int(row['tests']) for row in jvm), 'new_tests': 0, 'classes': jvm},
        'lint': {'errors': int(lint[1]), 'warnings': int(lint[2])} if lint else None,
        'process_recovery': {'cases': recovery, 'runner_invocations': process['instrumentation_invocations'],
                             'test_apk_sha256_at_execution': process['test_apk_sha256']},
        'reference_preservation': {'old_registered_files': project_counts,
                                   'protected_memento_files': core['unchanged_files'], 'converted_packages': 2,
                                   'content_query_source_unchanged_is_not_claimed': True},
        'screenshots': snapshots,
        'settings_reference': {
            'source_descriptors': settings_sources,
            'actual_test_source_descriptors': source_tests,
            'formal_content_boundary': 'Per-book progress uses formal membership intersected with authoritative last_review_at; grading/import core and formal assets are unchanged',
            'independent_fixture_boundary': 'Named test databases and system-picker fixtures validate draft and transaction behavior, not real user private backup migration',
            'known_content_findings': 'UI3 reported legacy avoir/etre compound inconsistencies; UI5 does not repair or semantically revalidate them',
        },
        'source_manifest': {'path': str(manifest_path), 'files': len(manifest), 'sha256': digest(manifest_path)},
        'evidence_manifest': {path.relative_to(ROOT).as_posix(): {'bytes': path.stat().st_size, 'sha256': digest(path)}
                              for path in sorted(set(evidence_paths))},
        'limitations': [
            'UI6 through UI7 remain pending; language colors deferred',
            'Two earlier settings-read timeouts were not reliably reproduced; final grouped passes do not establish all original causes',
            'Activity draft recreation is covered; forced-process recovery does not claim durable restoration of every unsaved UI draft',
            'Complete Android release suite and desktop full content comparison not rerun for UI5',
            'Whole-app enlarged-font, landscape and OEM/device matrix remain pending',
            'Screenshot evidence is restricted to six UI5 captures from SettingsLayoutInstrumentedTest; other pulled history, regression and diagnostic files are excluded from the six new visual captures',
            'Failure and delayed-response fixtures supplement formal SQL and UI checks, not real network or content corruption validation',
            'Existing avoir/etre compound inconsistencies remain unmodified; UI5 is not a language-semantic audit',
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
    except (OSError, KeyError, ValueError, IndexError, TypeError, ET.ParseError, BadZipFile) as error:
        OUT.mkdir(parents=True, exist_ok=True)
        failure = {'stage': 'UI5', 'passed': False, 'status': 'incomplete-evidence', 'error': str(error),
                   'instruction': 'Complete or repair all UI5 evidence gates before running this collector.'}
        (OUT / 'delivery.json').write_text(json.dumps(failure, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(failure, indent=2))
        raise SystemExit(1)

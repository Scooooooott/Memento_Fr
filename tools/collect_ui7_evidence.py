"""Associate UI7's versioned Debug APK with current source and completed evidence.

This reads reports and logs only. It never builds, controls a device, runs tests,
or publishes. Missing evidence clears an earlier success and fails the gate.
"""
import ast
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import BadZipFile, ZipFile

from collect_ui6_evidence import digest, png_info, rooted, source_descriptor
from verify_ui7_full_suite import EXCLUDED_CLASS, instrumentation, source_methods

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/qa/ui-restoration/ui7'
EXPECTED_JVM_CLASSES = {
    'com.scott.memento.domain.fsrs.FsrsSchedulerTest': 5,
    'com.scott.memento.domain.LanguageCapabilitiesTest': 5,
    'com.scott.memento.domain.LanguageRegistryTest': 6,
    'com.scott.memento.domain.SearchKeyTest': 2,
    'com.scott.memento.domain.VoiceSelectionTest': 3,
    'com.scott.memento.ui.language.GrammarTableModelsTest': 9,
    'com.scott.memento.ui.library.LibraryPagerTest': 10,
    'com.scott.memento.StatisticsWindowTest': 4,
}
EXPECTED_DESKTOP_CLASSES = {
    'validate_data_contracts.Contracts', 'validate_p3_contracts.UpgradeContracts',
    'test_content_contract.Contract', 'test_content_conversion.Conversion',
    'test_extension_packages.ExtensionPackages',
}
EXPECTED_PROCESS_CASES = {'pending', 'completed', 'extra', 'grace'}
PREFIX = 'app/src/main/java/com/scott/memento/'
STUDY = PREFIX + 'data/user/StudyRepository.kt'
IMPORTER = PREFIX + 'data/migration/LegacyImporter.kt'
HELPER = PREFIX + 'data/user/SqliteWrites.kt'
SNAPSHOT_TAGS = {
    'ui6-fr-statistics': ('StatisticsLayoutInstrumentedTest', ('statistics', 'statistics_today', 'statistics_window_title')),
    'ui6-es-statistics': ('StatisticsLayoutInstrumentedTest', ('statistics', 'statistics_date')),
    'ui6-fr-statistics-coverage': ('StatisticsLayoutInstrumentedTest', ('statistics', 'statistics_coverage_count', 'statistics_unavailable')),
    'ui6-es-statistics-30-days': ('StatisticsLayoutInstrumentedTest', ('statistics', 'history_kinds_2026-10-02')),
    'ui6-statistics-grace': ('StatisticsTimeUiInstrumentedTest', ('statistics', 'statistics_grace')),
    'ui6-statistics-two-am': ('StatisticsTimeUiInstrumentedTest', ('statistics', 'statistics_date')),
}
PAGE_TAGS = {
    'home': 'home_screen', 'speech': 'speech_status', 'study': 'study_screen', 'grammar': 'grammar_rows',
    'books': 'book_picker', 'settings': 'settings_form', 'library': 'search', 'stats7': 'statistics',
    'stats30': 'statistics', 'onboarding': 'onboarding', 'summary': 'summary_screen', 'import': 'legacy_import',
}


def read(name):
    return json.loads((OUT / name).read_text(encoding='utf-8-sig'))


def text(path):
    return path.read_text(encoding='utf-8-sig')


def descriptor(path):
    return {'bytes': path.stat().st_size, 'sha256': digest(path)}


def runner(path):
    return instrumentation(text(path))


def before_method(text_value, start, end):
    first = text_value.index(start)
    last = text_value.index(end, first)
    return text_value[:first] + text_value[last:]


def current_desktop_source(row):
    path = rooted(row['path'])
    module, _, name = row['class'].rpartition('.')
    if path != (ROOT / 'tools' / (module + '.py')).resolve():
        raise ValueError('Unexpected desktop test source path: ' + row['path'])
    nodes = [node for node in ast.parse(text(path)).body if isinstance(node, ast.ClassDef) and node.name == name]
    if len(nodes) != 1:
        raise ValueError('Desktop test class is missing or ambiguous: ' + row['class'])
    methods = sorted(node.name for node in nodes[0].body
                     if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name.startswith('test'))
    return {'class': row['class'], 'path': path.relative_to(ROOT).as_posix(), 'sha256': digest(path),
            'declared_test_methods': methods,
            'expected_cases': [row['class'] + '.' + method for method in methods]}


def archive_digest(archive, name):
    value = sha256()
    with archive.open(name) as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def legacy_file_matches(row, target):
    path = Path(row['path']).resolve()
    allowed = Path('L:/Words' if target == 'fr' else 'L:/Words_esp').resolve()
    return path.is_relative_to(allowed) and path.is_file() \
        and path.stat().st_size == row['baseline_bytes'] and digest(path) == row['baseline_sha256']


def collect():
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / 'delivery.json').write_text(json.dumps({'stage': 'UI7', 'passed': False, 'status': 'collecting'}, indent=2) + '\n', encoding='utf-8')
    release = ROOT / 'releases/Memento-0.2.0-debug.apk'
    candidate = OUT / 'Memento-ui7-debug.apk'
    checksum_path = OUT / 'Memento-ui7-debug.apk.sha256'
    release_checksum_path = ROOT / 'releases/Memento-0.2.0-debug.apk.sha256'
    checksum = digest(release)
    expected_checksum = text(checksum_path).split()[0].lower()
    release_checksum = text(release_checksum_path).split()[0].lower()
    if re.fullmatch(r'[0-9a-f]{64}', expected_checksum) is None:
        raise ValueError('UI7 checksum must contain a SHA-256 value')
    app_build = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    test_build = ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    test_checksum = digest(test_build)
    full = read('full-suite/result.json')
    frozen = read('full-suite/expected-source.json')
    actual = runner(OUT / 'full-suite/instrumentation.log')
    current = source_methods()
    assets = read('apk-assets.json')
    process = read('process-recovery/result.json')
    install = read('install/install.json')
    matrix = read('matrix/result.json')
    preservation = read('reference-preservation.json')
    compatibility = read('compatibility-scope.json')
    old_sqlite = read('sqlite-318/result.json')
    desktop = read('desktop/desktop-contracts.json')
    content = {target: read(f'desktop/{target}-content-diff.json') for target in ('fr', 'es')}
    packages = json.loads(text(ROOT / 'data/manifests/content-packages.json'))['packages']
    baseline = json.loads(text(ROOT / 'docs/qa/ui-restoration/ui6/source-manifest.json'))
    signature = text(OUT / 'signature.txt')
    badging = text(OUT / 'package.txt')
    build = text(OUT / 'build.log')
    lint = re.search(r'(\d+) errors, (\d+) warnings', text(OUT / 'lint.txt'))
    evidence_paths = [OUT / name for name in (
        'full-suite/result.json', 'full-suite/expected-source.json', 'full-suite/instrumentation.log',
        'full-suite/source-identity-correction.json',
        'full-suite/initial-source-parse/expected-source.json', 'full-suite/initial-source-parse/result.json',
        'apk-assets.json', 'process-recovery/result.json', 'install/install.json', 'matrix/result.json',
        'reference-preservation.json', 'compatibility-scope.json', 'sql-compatibility.patch',
        'preflight-source-association.json',
        'baseline-source/StudyRepository.kt', 'baseline-source/LegacyImporter.kt',
        'sqlite-318/result.json', 'desktop/desktop-contracts.json', 'desktop/fr-content-diff.json',
        'desktop/es-content-diff.json', 'signature.txt', 'package.txt', 'lint.txt', 'lint.xml',
        'jvm-results.json', 'build.log', 'Memento-ui7-debug.apk.sha256', 'ACCEPTANCE.md', 'VISUAL_REVIEW.md',
        'install/default-launch.xml', 'install/default-launch.png',
    )]
    evidence_paths.extend((checksum_path, release_checksum_path))
    # The collector's redirected stdout can still be open while hashes are frozen.
    # Its own console log is not an independent execution evidence gate.
    evidence_paths.extend(path for path in OUT.rglob('*.log') if path.is_file()
                          and not (path.parent == OUT and path.name.startswith(('delivery', 'collect'))))
    evidence_paths.extend(path for path in (OUT / 'sqlite-318').rglob('*') if path.is_file())
    evidence_paths.extend(path for path in (OUT / 'process-recovery').glob('*') if path.is_file())

    expected_cases = set(current['cases'])
    correction = read('full-suite/source-identity-correction.json')
    original_snapshot = read('full-suite/initial-source-parse/expected-source.json')
    original_result = read('full-suite/initial-source-parse/result.json')
    frozen_fields = ('path', 'sha256', 'declared_test_methods')
    source_correction_valid = correction['source_paths_hashes_methods_unchanged'] \
        and correction['reuses_original_runner_log'] and correction['runner_invocations'] == 1 \
        and correction['original_snapshot_sha256'] == digest(OUT / 'full-suite/initial-source-parse/expected-source.json') \
        and correction['original_result_sha256'] == digest(OUT / 'full-suite/initial-source-parse/result.json') \
        and original_snapshot['tests'] == current['tests'] == 182 and original_snapshot['classes'] == current['classes'] == 27 \
        and [{key: row[key] for key in frozen_fields} for row in original_snapshot['sources']] \
            == [{key: row[key] for key in frozen_fields} for row in current['sources']] \
        and [{'path': after['path'], 'before': before['class'], 'after': after['class'], 'sha256': after['sha256']}
             for before, after in zip(original_snapshot['sources'], current['sources'])
             if before['class'] != after['class']] == correction['changed_metadata'] \
        and correction['changed_metadata'] == [{'path': 'app/src/androidTest/java/com/scott/memento/LibraryQueryInstrumentedTest.kt',
            'before': 'com.scott.memento.never', 'after': 'com.scott.memento.LibraryQueryInstrumentedTest',
            'sha256': digest(ROOT / 'app/src/androidTest/java/com/scott/memento/LibraryQueryInstrumentedTest.kt')}] \
        and original_result['runner_completed'] and original_result['instrumentation'] == actual \
        and original_result['log_sha256'] == full['log_sha256'] == digest(OUT / 'full-suite/instrumentation.log') \
        and correction['application_apk_sha256'] == original_result['application_apk_sha256'] == full['application_apk_sha256'] \
        and correction['test_apk_sha256'] == original_result['test_apk_sha256'] == full['test_apk_sha256']
    source_tests = {row['class'].rsplit('.', 1)[1]: row for row in current['sources']}
    source_tests['ProcessRecoveryInstrumentedTest'] = source_descriptor(EXCLUDED_CLASS, 'androidTest')
    class_names = [row['class'] for row in full['classes']]
    classes_valid = len(class_names) == len(set(class_names)) == 27 and set(class_names) == {row['class'] for row in current['sources']}
    for row in full['classes']:
        source = next((item for item in current['sources'] if item['class'] == row['class']), None)
        methods = set(source['declared_test_methods']) if source else set()
        expected_group = {row['class'] + '#' + method for method in methods}
        actual_group = {case: passed for case, passed in actual['cases'].items() if case.startswith(row['class'] + '#')}
        classes_valid = classes_valid and row['passed'] and row['source'] == source \
            and row['source_sha256'] == source['sha256'] and row['source_path'] == source['path'] \
            and set(row['declared_test_methods']) == methods and row['declared_tests'] == row['tests'] == len(methods) \
            and set(row['expected_cases']) == expected_group and row['actual_cases'] == actual_group \
            and set(actual_group) == expected_group and all(actual_group.values())

    jvm = []
    for path in sorted((OUT / 'jvm').glob('TEST-*.xml')):
        suite = ET.parse(path).getroot()
        attrs = suite.attrib
        source = source_descriptor(attrs['name'], 'test')
        source_tests[attrs['name']] = source
        methods = [case.attrib['name'] for case in suite.findall('testcase')]
        row = {key: attrs[key] for key in ('name', 'tests', 'failures', 'errors')}
        row.update(actual_cases=len(methods), skipped=int(attrs.get('skipped', 0)),
                   current_build_xml_match=path.read_bytes() == (ROOT / 'app/build/test-results/testDebugUnitTest' / path.name).read_bytes(),
                   source_methods_match=set(methods) == set(source['declared_test_methods'])
                   and len(methods) == len(set(methods)) and not source['duplicate_methods'],
                   case_failures=sum(bool(case.findall('failure') or case.findall('error') or case.findall('skipped'))
                                     for case in suite.findall('testcase')))
        jvm.append(row)
        evidence_paths.append(path)
    jvm_summary = read('jvm-results.json')
    jvm_match = sorted((row['name'], int(row['tests']), int(row['failures']), int(row['errors'])) for row in jvm) == sorted(
        (row['name'], int(row['tests']), int(row['failures']), int(row['errors'])) for row in jvm_summary)

    desktop_sources = [current_desktop_source(row) for row in desktop['source_descriptors']]
    desktop_expected = [case for row in desktop_sources for case in row['expected_cases']]
    desktop_group_names = [row['class'] for row in desktop['classes']]
    desktop_groups = []
    for row in desktop['classes']:
        source = next((item for item in desktop_sources if item['class'] == row['class']), None)
        wanted = source['expected_cases'] if source else []
        desktop_groups.append(row['passed'] and len(wanted) == row['tests'] == row['declared_tests']
                              and set(row['successful_cases']) == set(wanted))
    desktop_valid = desktop['passed'] and int(desktop['tests']) == 37 \
        and desktop['failures'] == desktop['errors'] == 0 \
        and desktop_sources == desktop['source_descriptors'] \
        and {row['class'] for row in desktop_sources} == EXPECTED_DESKTOP_CLASSES \
        and len(desktop_sources) == len(desktop['classes']) == 5 \
        and len(set(desktop_group_names)) == 5 and set(desktop_group_names) == EXPECTED_DESKTOP_CLASSES \
        and len(desktop_expected) == len(set(desktop_expected)) == 37 \
        and set(desktop['expected_cases']) == set(desktop['successful_cases']) == set(desktop_expected) \
        and not any(desktop[key] for key in ('missing_cases', 'extra_cases', 'duplicate_cases', 'failed_cases', 'errored_cases', 'skipped_cases')) \
        and bool(desktop['checks']) and all(desktop['checks'].values()) and all(desktop_groups)

    recovery = []
    process_methods = set(source_tests['ProcessRecoveryInstrumentedTest']['declared_test_methods'])
    process_expected = {EXCLUDED_CLASS + '#' + method for method in process_methods}
    for row in process['cases']:
        name = row['case']
        if name not in EXPECTED_PROCESS_CASES:
            raise ValueError('Unexpected process recovery case: ' + name)
        prepare_path = OUT / f'process-recovery/{name}-prepare.log'
        verify_path = OUT / f'process-recovery/{name}-verify.log'
        verification_path = OUT / f'process-recovery/{name}-verified.json'
        prepare, verify = runner(prepare_path), runner(verify_path)
        verification = read(f'process-recovery/{name}-verified.json')
        before_pid, after_pid = str(row['before_pid']), str(row['after_pid'])
        valid_pids = before_pid.isdigit() and after_pid.isdigit() and int(before_pid) > 0 and int(after_pid) > 0
        recovery.append({
            'case': name, 'before_pid': before_pid, 'after_pid': after_pid, 'prepare': prepare, 'verify': verify,
            'all_user_tables_equal': verification['all_user_tables_equal_before_further_actions'],
            'passed': row['stopped_pid_empty'] and valid_pids and before_pid != after_pid
            and prepare['passed'] and verify['passed'] and prepare['tests'] == verify['tests'] == 1
            and set(prepare['cases']) == set(verify['cases']) == process_expected
            and verification['passed'] and verification['case'] == name
            and verification['all_user_tables_equal_before_further_actions']
            and row['database'] == 'memento-test-process-recovery.db',
        })
        evidence_paths.extend((prepare_path, verify_path, verification_path))

    snapshots = []
    for name, (test_class, tags) in SNAPSHOT_TAGS.items():
        path = OUT / 'full-suite/screenshots' / f'{name}.png'
        tree_path = path.with_suffix('.tree.txt')
        snapshot = png_info(path)
        tree = text(tree_path)
        snapshot.update(tree_path=tree_path.relative_to(ROOT).as_posix(), expected_tags=list(tags),
                        expected_tags_present=all(tag in tree for tag in tags),
                        source=('production MementoApp with mutable-clock test Activity' if test_class == 'StatisticsTimeUiInstrumentedTest' else 'production MainActivity'))
        snapshot['passed'] = snapshot['passed'] and snapshot['expected_tags_present'] and bool(tree)
        snapshots.append(snapshot)
        evidence_paths.extend((path, tree_path))

    projects, core = preservation['projects'], preservation['memento_preserved_core']
    project_counts = {target: project['unchanged_files'] for target, project in projects.items()}
    references_valid = all(
        project['passed'] and project['all_registered_files_unchanged'] and project['git_matches_baseline']
        and len(project['files']) == project['registered_files'] == project['unchanged_files']
        and project['git_current']['exit_code'] == 0
        and project['git_current']['porcelain'] == project['git_baseline']['porcelain']
        and all(row['exists'] and row['unchanged'] and row['bytes'] == row['baseline_bytes']
                and row['sha256'] == row['baseline_sha256'] and legacy_file_matches(row, target) for row in project['files'])
        for target, project in projects.items()
    )
    allowed_core = [row for row in core['files'] if rooted(row['path']) == (ROOT / STUDY).resolve()]
    unchanged_core = [row for row in core['files'] if rooted(row['path']) != (ROOT / STUDY).resolve()]
    protected_valid = all(
        row['exists'] and row['unchanged'] and row['bytes'] == row['baseline_bytes']
        and row['sha256'] == row['baseline_sha256']
        and rooted(row['path']).stat().st_size == row['baseline_bytes']
        and digest(rooted(row['path'])) == row['baseline_sha256']
        for row in unchanged_core + core['converted_content_packages']
    )
    changed_rows = compatibility['changed_production_files']
    changed_names = {row['path'] for row in changed_rows}
    compatibility_valid = compatibility['passed'] and compatibility['stage'] == 'UI7' \
        and compatibility['baseline_matches_ui6'] and compatibility['outside_configure_unchanged'] \
        and compatibility['outside_import_perform_unchanged'] and compatibility['compatible_write_sites'] == 5 \
        and len(changed_rows) == 2 and changed_names == {STUDY, IMPORTER} \
        and all(row['baseline_matches_ui6'] and row['changed']
                and digest(rooted(row['path'])) == row['current_sha256']
                and digest(rooted(row['baseline_path'])) == row['baseline_sha256'] == baseline[row['path']]['sha256']
                and rooted(row['baseline_path']).stat().st_size == baseline[row['path']]['bytes'] for row in changed_rows) \
        and compatibility['new_helper']['path'] == HELPER \
        and compatibility['new_helper']['sha256'] == digest(ROOT / HELPER) \
        and compatibility['new_production_files'] == [HELPER] \
        and not compatibility['missing_production_files'] and not compatibility['runtime_modern_upsert_files'] \
        and compatibility['helper_transaction_and_constraint_rules']
    for row in compatibility['other_production_files']:
        expected = baseline[row['path']]
        compatibility_valid = compatibility_valid and row['unchanged'] \
            and rooted(row['path']).stat().st_size == expected['bytes'] and digest(rooted(row['path'])) == expected['sha256']
    expected_other_sources = {name for name in baseline if name.startswith(PREFIX) and name not in {STUDY, IMPORTER}}
    current_production_paths = {path.relative_to(ROOT).as_posix() for path in (ROOT / PREFIX).rglob('*.kt')}
    baseline_production_paths = {name for name in baseline if name.startswith(PREFIX) and name.endswith('.kt')}
    compatibility_valid = compatibility_valid \
        and {row['path'] for row in compatibility['other_production_files']} == expected_other_sources \
        and len(compatibility['other_production_files']) == len(expected_other_sources) \
        and current_production_paths == baseline_production_paths | {HELPER}
    study_before = text(OUT / 'baseline-source/StudyRepository.kt')
    study_after = text(ROOT / STUDY)
    legacy_before = text(OUT / 'baseline-source/LegacyImporter.kt')
    legacy_after = text(ROOT / IMPORTER).replace('import com.scott.memento.data.user.SqliteWrites\n', '')
    compatibility_valid = compatibility_valid and before_method(study_before, '    fun configure(', '    /** Freeze existing plan zones.') == \
        before_method(study_after, '    fun configure(', '    /** Freeze existing plan zones.') \
        and before_method(legacy_before, '    private fun perform(', '    private fun noRegression(') == \
        before_method(legacy_after, '    private fun perform(', '    private fun noRegression(')

    formal_embedded = {}
    assets_valid = assets['passed'] and assets['test_fixtures_excluded'] and assets['unified_manifest_matches_verified_output']
    with ZipFile(release) as archive:
        for target in ('fr', 'es'):
            formal_embedded[target] = archive_digest(archive, f'assets/content/{target}.db')
            row = assets['embedded_formal_assets'][target]
            assets_valid = assets_valid and row['matches_verified_package'] and row['matches_audited_source_identity'] \
                and row['sha256'] == formal_embedded[target] == packages[target]['sha256'] \
                and row['source_db_sha256'] == packages[target]['source_db_sha256']
        for name, row in assets['embedded_contracts'].items():
            assets_valid = assets_valid and row['matches_source'] \
                and row['sha256'] == archive_digest(archive, 'assets/' + name) == digest(ROOT / 'schemas' / name)
        for name, row in assets['embedded_licenses'].items():
            assets_valid = assets_valid and row['matches_notice'] \
                and row['sha256'] == archive_digest(archive, 'assets/licenses/' + name) == digest(ROOT / 'app/src/main/assets/licenses' / name)
    content_valid = all(
        row['passed'] and row['target'] == target and row['package_sha256'] == formal_embedded[target] == packages[target]['sha256']
        and row['source_sha256'] == packages[target]['source_db_sha256'] == digest(ROOT / 'data/source-snapshots' / packages[target]['source_file'])
        and row['package_sha256'] == digest(ROOT / 'data/content-packages' / f'{target}.db')
        and bool(row['roundtrip']) and all(item['passed'] and item['source_rows'] == item['roundtrip_rows']
                                         and item['missing_or_changed'] == item['extra_or_changed'] == 0 for item in row['roundtrip'].values())
        and bool(row['normalized_checks']) and all(row['normalized_checks'].values())
        and set(row['coverage']) == set(packages[target]['learning_languages'])
        and all(item['passed'] and item['lexemes'] == item['complete_lexemes'] == packages[target]['counts']['lexeme']
                and item['missing_senses'] == item['missing_examples'] == 0 for item in row['coverage'].values())
        and row['counts'] == packages[target]['counts'] for target, row in content.items()
    )

    matrix_rows, matrix_snapshots = [], []
    global_expected = {'com.scott.memento.GlobalExperienceInstrumentedTest#' + method
                       for method in source_tests['GlobalExperienceInstrumentedTest']['declared_test_methods']}
    expected_configs = {(width, font) for width in (320, 360, 412) for font in (1.0, 1.5, 2.0)}
    for row in matrix['cases']:
        path = rooted(row['log'])
        result = runner(path)
        width, font = int(row['width_dp_requested']), float(row['font_scale_requested'])
        label = row['configuration']
        expected_label = f'w{width}-f{font:.1f}'.replace('.', '_')
        size = re.search(r'Override size: (\d+)x(\d+)', row['display_size'])
        if size is None:
            raise ValueError('Missing actual matrix display size: ' + label)
        pixels, height = int(size[1]), int(size[2])
        matrix_rows.append({**row, 'runner': result, 'methods_match': set(result['cases']) == global_expected,
                            'expected_path': path == (OUT / 'matrix' / expected_label / 'instrumentation.log').resolve(),
                            'configuration_matches': label == expected_label})
        evidence_paths.append(path)
        for target in ('fr', 'es'):
            for page, tag in PAGE_TAGS.items():
                name = f'ui6-{target}-w{width}-f{font:.1f}-{page}'.replace(f'f{font:.1f}', f'f{font:.1f}'.replace('.', '_'))
                png = OUT / 'matrix' / label / 'screenshots' / (name + '.png')
                tree_path = png.with_name(name + '-tree.txt')
                capture = png_info(png)
                tree = text(tree_path)
                capture.update(configuration=label, target=target, page=page, tree_tag=tag,
                               passed=capture['passed'] and capture['width'] == pixels and capture['height'] == height and tag in tree,
                               tree_path=tree_path.relative_to(ROOT).as_posix())
                matrix_snapshots.append(capture)
                evidence_paths.extend((png, tree_path))

    apk_hashes = {
        'release': checksum, 'candidate_copy': digest(candidate), 'candidate_checksum': expected_checksum,
        'release_checksum': release_checksum, 'current_build': digest(app_build),
        'full_suite_before': full['application_apk_sha256'], 'full_suite_after': full['application_apk_sha256_after'],
        'embedded_assets': assets['apk_sha256'], 'process_recovery': process['application_apk_sha256'],
        'matrix': matrix['application_apk_sha256'], 'install_input': install['apk_sha256'],
        'installed_device': install['installed_apk_sha256'],
        **{'matrix_' + row['configuration']: row['application_apk_sha256'] for row in matrix_rows},
    }
    test_apk_hashes = {
        'current_build': test_checksum, 'full_suite_before': full['test_apk_sha256'],
        'full_suite_after': full['test_apk_sha256_after'], 'process_recovery': process['test_apk_sha256'],
        'matrix': matrix['test_apk_sha256'],
        **{'matrix_' + row['configuration']: row['test_apk_sha256'] for row in matrix_rows},
    }
    runtime_version = old_sqlite.get('provenance', {}).get('runtime_version') or old_sqlite.get('runtime', {}).get('version')
    old_sources = old_sqlite['source_sha256']
    old_provenance = old_sqlite['provenance']
    old_sqlite_valid = old_sqlite['passed'] and runtime_version == '3.18.0' \
        and bool(old_sqlite['checks']) and all(row['passed'] for row in old_sqlite['checks']) \
        and old_sqlite['checks_total'] == old_sqlite['checks_passed'] == len(old_sqlite['checks']) \
        and len({row['name'] for row in old_sqlite['checks']}) == old_sqlite['checks_total'] \
        and {STUDY, IMPORTER, HELPER, 'schemas/user-v1.sql', 'schemas/user-v2-upgrade.sql', 'schemas/user-v3-upgrade.sql'} <= set(old_sources) \
        and all(digest(rooted(path)) == value for path, value in old_sources.items()) \
        and old_provenance['runtime_source_id'] == old_provenance['official_release_source_id'] \
        and old_provenance['download_url'] == 'https://www.sqlite.org/2017/sqlite-dll-win64-x64-3180000.zip' \
        and digest(rooted(old_provenance['archive'])) == old_provenance['archive_sha256'] \
        and digest(rooted(old_provenance['dll'])) == old_provenance['dll_sha256'] \
        and len(old_sqlite['sites']) == 5 \
        and all(site['source'] in {STUDY, IMPORTER} and site['constraint_errors']['update']['code'] == 19
                and site['constraint_errors']['insert']['code'] == 19 for site in old_sqlite['sites']) \
        and old_sqlite['scope']['android_26_device_or_emulator_verified'] is False \
        and old_sqlite['scope']['android_binding_verified_by_this_script'] is False
    gradle = text(ROOT / 'app/build.gradle.kts')
    checks = {
        'source_identity_correction_preserves_frozen_inputs': source_correction_valid,
        'complete_android_suite': full['passed'] and full['stage'] == 'UI7' and full['runner_completed']
        and not full['runner_error'] and full['instrumentation_invocations'] == full['runner_invocations'] == 1
        and full['excluded_class'] == EXCLUDED_CLASS and full['avd'] == 'MementoApi35' and full['serial'] == 'emulator-5562'
        and frozen == current == full['source'] and current['classes'] == 27
        and full['expected_tests'] == full['tests'] == current['tests'] == actual['tests'] == 182
        and actual['passed'] and full['instrumentation'] == actual and classes_valid
        and set(actual['successful_cases']) == set(full['successful_cases']) == expected_cases
        and set(full['expected_cases']) == expected_cases
        and not any(full[key] for key in ('missing_cases', 'extra_cases', 'duplicate_cases', 'failed_cases'))
        and bool(full['checks']) and all(full['checks'].values())
        and rooted(full['log']) == (OUT / 'full-suite/instrumentation.log').resolve()
        and full['log_sha256'] == digest(OUT / 'full-suite/instrumentation.log'),
        'jvm': jvm_match and len(jvm) == len(EXPECTED_JVM_CLASSES)
        and {row['name']: int(row['tests']) for row in jvm} == EXPECTED_JVM_CLASSES
        and sum(int(row['tests']) for row in jvm) == 44
        and all(int(row['failures']) == int(row['errors']) == row['skipped'] == row['case_failures'] == 0
                and row['actual_cases'] == int(row['tests']) and row['source_methods_match'] and row['current_build_xml_match'] for row in jvm),
        'desktop_contracts': desktop_valid,
        'formal_content_full_comparison': content_valid,
        'sqlite_318_engine_execution': old_sqlite_valid,
        'scoped_compatibility_changes': compatibility_valid,
        'lint': lint is not None and int(lint[1]) == 0 and int(lint[2]) == 5,
        'build': 'BUILD SUCCESSFUL' in build and 'BUILD FAILED' not in build,
        'process_recovery': process['passed'] and len(recovery) == 4
        and {row['case'] for row in recovery} == EXPECTED_PROCESS_CASES
        and process['instrumentation_invocations'] == 8 and process['uses_force_stop']
        and process['avd'] == 'MementoApi35' and process['emulator'] == 'emulator-5562'
        and not process['clears_default_database'] and all(row['passed'] for row in recovery),
        'formal_install': install['passed'] and install['ui_ready'] and install['application_id'] == 'com.scott.memento'
        and install['avd'] == 'MementoApi35' and install['serial'] == 'emulator-5562'
        and install['default_database_existed_before'] and install['default_database_preserved_during_install']
        and bool(install['default_database_before_install_sha256'])
        and install['default_database_before_install_sha256'] == install['default_database_after_install_sha256']
        and not install['clears_data'] and not install['uses_qa_extras'],
        'apk_association': set(apk_hashes.values()) == {checksum}
        and candidate.stat().st_size == release.stat().st_size == assets['bytes'],
        'test_apk_association': set(test_apk_hashes.values()) == {test_checksum}
        and re.fullmatch(r'[0-9a-f]{64}', test_checksum) is not None,
        'signature': 'Verifies' in signature and 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
        and 'CN=Android Debug' in signature,
        'identity': all(value in badging for value in ("name='com.scott.memento'", "versionCode='2'", "versionName='0.2.0'", "sdkVersion:'26'", "targetSdkVersion:'35'"))
        and re.search(r'\bversionCode\s*=\s*2\b', gradle) is not None
        and re.search(r'\bversionName\s*=\s*"0\.2\.0"', gradle) is not None,
        'embedded_assets': assets_valid,
        # Overall preservation.passed is expected false because the allowed StudyRepository changed.
        'registered_reference_preservation': preservation['stage'] == 'UI7'
        and project_counts == {'fr': 84, 'es': 68} and references_valid,
        'core_preservation_except_configure': len(core['files']) == 15 and len(unchanged_core) == core['unchanged_files'] == 14
        and len(allowed_core) == 1 and allowed_core[0]['exists'] and not allowed_core[0]['unchanged']
        and allowed_core[0]['sha256'] == digest(ROOT / STUDY)
        and allowed_core[0]['baseline_sha256'] == baseline[STUDY]['sha256']
        and len(core['converted_content_packages']) == 2 and core['schema_file_set_matches_p8']
        and core['schema_current_paths'] == core['schema_baseline_paths'] and protected_valid and compatibility_valid,
        'fresh_statistics_screenshots': len(snapshots) == 6 and all(row['passed'] for row in snapshots),
        'width_font_matrix': matrix['passed'] and 'UI7' in matrix['scope'] and matrix['display_settings_restored']
        and matrix['original_size'] == matrix['restored_size'] and matrix['original_density'] == matrix['restored_density']
        and matrix['original_font_scale'] == matrix['restored_font_scale']
        and int(matrix['tests']) == 36 and len(matrix_rows) == 9
        and {(int(row['width_dp_requested']), float(row['font_scale_requested'])) for row in matrix_rows} == expected_configs
        and all(row['passed'] and row['runner']['passed'] and row['tests'] == row['runner']['tests'] == 4
                and row['methods_match'] and row['expected_path'] and row['configuration_matches']
                and float(row['font_scale_effective']) == float(row['font_scale_requested']) for row in matrix_rows),
        'matrix_screenshot_association': len(matrix_snapshots) == 216 and all(row['passed'] for row in matrix_snapshots),
    }

    files = set()
    for directory in ('app/src', 'schemas', 'gradle', 'tools', 'data/fixtures/p7/generated'):
        files.update(path for path in (ROOT / directory).rglob('*')
                     if path.is_file() and '__pycache__' not in path.parts and path.suffix != '.pyc')
    files.update(path for path in (ROOT / 'docs').rglob('*.md') if path.is_file())
    files.update(ROOT / name for name in (
        'gradlew', 'gradlew.bat', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'local.properties',
        'app/build.gradle.kts', '.gitignore', 'AGENTS.md', 'README.md', 'MULTILINGUAL_REFACTOR_PLAN.md',
        'data/manifests/resolved-build-artifacts.json', 'data/manifests/content-packages.json',
        'docs/qa/ui-restoration/ui7/ACCEPTANCE.md', 'docs/qa/ui-restoration/ui7/VISUAL_REVIEW.md',
        'docs/qa/ui-restoration/ui7/sqlite-318/README.md', 'docs/qa/ui-restoration/ui7/sql-compatibility.patch',
        'docs/qa/ui-restoration/ui7/baseline-source/StudyRepository.kt',
        'docs/qa/ui-restoration/ui7/baseline-source/LegacyImporter.kt',
    ))
    manifest = {path.relative_to(ROOT).as_posix(): descriptor(path) for path in sorted(files)}
    manifest_path = OUT / 'source-manifest.json'
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    delivery = {
        'stage': 'UI7', 'verified_at_utc': datetime.now(timezone.utc).isoformat(),
        'scope': 'One complete ordinary Android runner, separate process recovery and nine matrix runners; final local UI restoration candidate',
        'application_id': 'com.scott.memento', 'version_name': '0.2.0', 'version_code': 2,
        'min_sdk': 26, 'target_sdk': 35, 'user_schema_version': 3, 'signing': 'Android Debug / APK v2',
        'apk': str(release), 'candidate_copy': str(candidate), 'bytes': release.stat().st_size, 'sha256': checksum,
        'apk_association': apk_hashes, 'test_apk_association': test_apk_hashes, 'checks': checks, 'passed': all(checks.values()),
        'android': {'tests': actual['tests'], 'runner_invocations': 1, 'classes': full['classes'], 'new_tests_since_ui6': 4,
                    'excluded_class': EXCLUDED_CLASS, 'current_source_methods': current, 'instrumentation': actual},
        'jvm': {'tests': sum(int(row['tests']) for row in jvm), 'classes': jvm},
        'desktop': desktop, 'content': content,
        'sqlite_318': {'passed': old_sqlite_valid, 'runtime_version': runtime_version,
                       'checks': old_sqlite['checks_total'], 'android_binding_execution': False,
                       'source_sha256': old_sources, 'provenance': old_sqlite.get('provenance', {})},
        'compatibility': compatibility,
        'lint': {'errors': int(lint[1]), 'warnings': int(lint[2])} if lint else None,
        'process_recovery': {'cases': recovery, 'runner_invocations': process['instrumentation_invocations']},
        'reference_preservation': {'old_registered_files': project_counts, 'protected_memento_files_unchanged': 14,
                                   'allowed_modified_core_file': STUDY, 'converted_packages': 2,
                                   'raw_preservation_passed': preservation['passed'],
                                   'interpretation': '14 protected core files unchanged; configure-only compatibility change audited separately; not 15 unchanged files'},
        'screenshots': snapshots,
        'width_font_matrix': {'cases': matrix_rows, 'tests': 36, 'captures': matrix_snapshots,
                              'display_settings_restored': matrix['display_settings_restored']},
        'actual_test_source_descriptors': source_tests,
        'source_identity_correction': correction,
        'source_manifest': {'path': str(manifest_path), 'files': len(manifest), 'sha256': digest(manifest_path)},
        'evidence_manifest': {path.relative_to(ROOT).as_posix(): descriptor(path) for path in sorted(set(evidence_paths))},
        'limitations': [
            'Actual Android binding and UI acceptance use API 35 MementoApi35 only; SQLite 3.18 desktop execution is not an Android API 26 device or emulator run',
            'Portrait 320/360/412 dp and font 1.0/1.5/2.0 matrix only; landscape, physical devices/OEM and live TalkBack remain unverified',
            'PNG and semantics are captured sequentially; paired files do not certify an atomic frame or exhaustive linguistic review',
            'Named fixtures validate state, cancellation and imports; no real private user backup acquired or migrated',
            'Offline French/Spanish voice playback remains unverified; callback probes do not establish actual sound',
            'Process recovery covers persisted study state, not permanent restoration of every unsaved UI draft',
            'Earlier UI5 loading timeouts were not reliably reproduced; subsequent passing suites do not identify their exact causes',
            'Existing avoir/etre source-content inconsistencies and machine-assisted provenance remain unmodified',
            'Registered legacy inputs and Git-visible baseline checked; ignored caches/history not exhaustively scanned',
            'Language color schemes remain deferred; 0.2.0/versionCode 2 is Debug signed with no commit, push, release signing, tag or publication',
        ],
    }
    (OUT / 'delivery.json').write_text(json.dumps(delivery, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'passed': delivery['passed'], 'checks': checks, 'source_files': len(manifest),
                      'android': actual['tests'], 'jvm': delivery['jvm']['tests'], 'desktop': desktop['tests'],
                      'process_cases': len(recovery), 'sha256': checksum}, indent=2))
    return 0 if delivery['passed'] else 1


if __name__ == '__main__':
    try:
        raise SystemExit(collect())
    except (OSError, KeyError, ValueError, IndexError, TypeError, AttributeError, ET.ParseError, BadZipFile) as error:
        OUT.mkdir(parents=True, exist_ok=True)
        failure = {'stage': 'UI7', 'passed': False, 'status': 'incomplete-evidence', 'error': str(error),
                   'instruction': 'Complete or repair all UI7 evidence gates before running this collector.'}
        (OUT / 'delivery.json').write_text(json.dumps(failure, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(failure, indent=2))
        raise SystemExit(1)

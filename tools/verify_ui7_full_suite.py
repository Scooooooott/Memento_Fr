"""Freeze and verify one complete UI7 Android runner; never builds or controls a device."""
import argparse
from collections import Counter
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED_CLASS = 'com.scott.memento.ProcessRecoveryInstrumentedTest'


def digest(path):
    value = sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def source_methods():
    sources, cases = [], []
    for path in sorted((ROOT / 'app/src/androidTest/java').rglob('*.kt')):
        text = path.read_text(encoding='utf-8-sig')
        methods = re.findall(r'@Test\s+fun\s+(\w+)\s*\(', text)
        if not methods:
            continue
        package = re.search(r'^package\s+([\w.]+)\s*$', text, re.MULTILINE)
        class_name = re.search(r'^class\s+(\w+)\b', text, re.MULTILINE)
        if not package or not class_name:
            raise ValueError(f'Cannot determine test class: {path}')
        if class_name[1] != path.stem:
            raise ValueError(f'Top-level test class must match its source filename: {path}')
        identity = package[1] + '.' + class_name[1]
        if identity == EXCLUDED_CLASS:
            continue
        declared = [identity + '#' + method for method in methods]
        sources.append({'path': path.relative_to(ROOT).as_posix(), 'sha256': digest(path),
                        'class': identity, 'declared_test_methods': methods})
        cases.extend(declared)
    duplicate = sorted(case for case, count in Counter(cases).items() if count > 1)
    if not cases or duplicate:
        raise ValueError(f'Invalid complete source method set: {duplicate}')
    return {'excluded_class': EXCLUDED_CLASS, 'classes': len(sources), 'tests': len(cases),
            'cases': sorted(cases), 'sources': sources}


def instrumentation(log):
    fields, cases, terminal = {}, {}, []
    reported = []
    for line in log.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, _, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            fields[key] = value
            if key == 'numtests':
                reported.append(int(value))
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.split(': ', 1)[1])
            if code != 1 and 'class' in fields and 'test' in fields:
                case = fields['class'] + '#' + fields['test']
                terminal.append({'case': case, 'status_code': code})
                cases[case] = code == 0 and cases.get(case, True)
            fields = {}
    summaries = [int(value) for value in re.findall(r'OK \((\d+) tests?\)', log)]
    duplicates = sorted(case for case, count in Counter(row['case'] for row in terminal).items() if count > 1)
    failed = sorted(row['case'] for row in terminal if row['status_code'] != 0)
    summary_valid = len(summaries) == 1 and bool(reported) and set(reported) == {summaries[0]} and summaries[0] == len(cases)
    return {'tests': len(cases), 'expected': reported[0] if reported else 0,
            'summary_counts': summaries, 'reported_counts': sorted(set(reported)), 'cases': cases,
            'successful_cases': sorted(case for case, passed in cases.items() if passed),
            'duplicate_cases': duplicates, 'failed_cases': failed, 'terminal_statuses': terminal,
            'passed': summary_valid and bool(cases) and all(cases.values()) and not duplicates
            and not any(marker in log for marker in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed.'))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence-directory', default='docs/qa/ui-restoration/ui7/full-suite')
    parser.add_argument('--prepare', action='store_true')
    parser.add_argument('--runner-completed', action='store_true')
    parser.add_argument('--runner-error', default='')
    parser.add_argument('--started-at', default='')
    for name in ('application-before', 'application-after', 'test-before', 'test-after'):
        parser.add_argument('--' + name, default='')
    args = parser.parse_args()
    output = (ROOT / args.evidence_directory).resolve()
    if not output.is_relative_to((ROOT / 'docs/qa/ui-restoration/ui7').resolve()) or output == (ROOT / 'docs/qa/ui-restoration/ui7').resolve():
        parser.error('UI7 full-suite evidence must stay inside docs/qa/ui-restoration/ui7')
    output.mkdir(parents=True, exist_ok=True)
    snapshot_path = output / 'expected-source.json'
    if args.prepare:
        snapshot = source_methods()
        snapshot_path.write_text(json.dumps(snapshot, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({'source_classes': snapshot['classes'], 'expected_tests': snapshot['tests'],
                          'excluded_class': EXCLUDED_CLASS}))
        return 0
    result = {'stage': 'UI7', 'passed': False, 'status': 'collecting', 'scope': 'One complete ordinary Android runner',
              'started_at_utc': args.started_at, 'verified_at_utc': datetime.now(timezone.utc).isoformat(),
              'instrumentation_invocations': 1, 'runner_invocations': 1, 'excluded_class': EXCLUDED_CLASS,
              'runner_completed': args.runner_completed, 'runner_error': args.runner_error,
              'avd': 'MementoApi35', 'serial': 'emulator-5562',
              'application_apk_sha256': args.application_before,
              'application_apk_sha256_after': args.application_after,
              'apk_sha256': args.application_before, 'apk_sha256_after': args.application_after,
              'test_apk_sha256': args.test_before, 'test_apk_sha256_after': args.test_after}
    try:
        frozen = json.loads(snapshot_path.read_text(encoding='utf-8-sig'))
        current = source_methods()
        log_path = output / 'instrumentation.log'
        actual = instrumentation(log_path.read_text(encoding='utf-8-sig'))
        expected_cases = set(frozen['cases'])
        actual_cases = set(actual['cases'])
        hashes = [args.application_before, args.application_after, args.test_before, args.test_after]
        checks = {
            'runner_completed': args.runner_completed and not args.runner_error,
            'source_unchanged': frozen == current,
            'instrumentation_passed': actual['passed'],
            'exact_method_set': expected_cases == actual_cases,
            'no_duplicate_cases': not actual['duplicate_cases'],
            'no_failed_or_skipped_cases': not actual['failed_cases'],
            'apk_hashes_valid': all(re.fullmatch(r'[0-9a-f]{64}', value) is not None for value in hashes),
            'application_apk_unchanged': args.application_before == args.application_after,
            'test_apk_unchanged': args.test_before == args.test_after,
        }
        classes = []
        for source in frozen['sources']:
            identity = source['class']
            declared = {identity + '#' + method for method in source['declared_test_methods']}
            actual_group = {case: passed for case, passed in actual['cases'].items()
                            if case.startswith(identity + '#')}
            classes.append({'class': identity, 'source_path': source['path'],
                            'source_sha256': source['sha256'], 'source': source,
                            'declared_test_methods': source['declared_test_methods'],
                            'declared_tests': len(declared), 'tests': len(actual_group),
                            'expected_cases': sorted(declared), 'actual_cases': actual_group,
                            'passed': declared == set(actual_group) and all(actual_group.values())
                            and not declared.intersection(actual['duplicate_cases'])})
        checks['all_classes_passed'] = all(row['passed'] for row in classes)
        result.update({'source': frozen, 'expected_tests': len(expected_cases), 'tests': actual['tests'],
                       'classes': classes,
                       'expected_cases': sorted(expected_cases), 'successful_cases': actual['successful_cases'],
                       'missing_cases': sorted(expected_cases - actual_cases), 'extra_cases': sorted(actual_cases - expected_cases),
                       'duplicate_cases': actual['duplicate_cases'], 'failed_cases': actual['failed_cases'],
                       'instrumentation': actual, 'log': log_path.relative_to(ROOT).as_posix(),
                       'log_sha256': digest(log_path), 'checks': checks,
                       'passed': all(checks.values()), 'status': 'complete'})
    except Exception as error:
        result.update({'status': 'failed', 'error': str(error)})
    (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: result[key] for key in ('stage', 'passed', 'status')}
                     | {key: result[key] for key in ('tests', 'expected_tests', 'checks', 'error') if key in result}, indent=2))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())

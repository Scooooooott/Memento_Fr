"""Audit the deliberately scoped UI7 SQLite compatibility changes against UI6."""
from datetime import datetime, timezone
import difflib
from hashlib import sha256
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/qa/ui-restoration/ui7'
PREFIX = 'app/src/main/java/com/scott/memento/'
CHANGED = (PREFIX + 'data/user/StudyRepository.kt', PREFIX + 'data/migration/LegacyImporter.kt')
HELPER = PREFIX + 'data/user/SqliteWrites.kt'


def digest(path):
    return sha256(path.read_bytes()).hexdigest()


def outside(text, start, end):
    begin = text.index(start)
    finish = text.index(end, begin)
    return text[:begin] + text[finish:]


def main():
    baseline = json.loads((ROOT / 'docs/qa/ui-restoration/ui6/source-manifest.json').read_text(encoding='utf-8-sig'))
    rows, patches = [], []
    for name in CHANGED:
        old = OUT / 'baseline-source' / Path(name).name
        current = ROOT / name
        before, after = old.read_text(encoding='utf-8-sig'), current.read_text(encoding='utf-8-sig')
        matches = old.stat().st_size == baseline[name]['bytes'] and digest(old) == baseline[name]['sha256']
        patches.extend(difflib.unified_diff(before.splitlines(True), after.splitlines(True),
                                          fromfile='UI6/' + name, tofile='UI7/' + name))
        rows.append({'path': name, 'baseline_path': old.relative_to(ROOT).as_posix(),
                     'baseline_sha256': digest(old), 'current_sha256': digest(current),
                     'baseline_matches_ui6': matches, 'changed': before != after})
    study_before = (OUT / 'baseline-source/StudyRepository.kt').read_text(encoding='utf-8-sig')
    study_after = (ROOT / CHANGED[0]).read_text(encoding='utf-8-sig')
    outside_configure = outside(study_before, '    fun configure(', '    /** Freeze existing plan zones.') == \
        outside(study_after, '    fun configure(', '    /** Freeze existing plan zones.')
    legacy_before = (OUT / 'baseline-source/LegacyImporter.kt').read_text(encoding='utf-8-sig')
    legacy_after = (ROOT / CHANGED[1]).read_text(encoding='utf-8-sig')
    # Only perform() contains the four write sites; every other importer method stays identical.
    legacy_outside = outside(legacy_before, '    private fun perform(', '    private fun noRegression(') == \
        outside(legacy_after.replace('import com.scott.memento.data.user.SqliteWrites\n', ''),
                '    private fun perform(', '    private fun noRegression(')
    production = []
    for name, expected in baseline.items():
        if name.startswith(PREFIX) and name not in CHANGED:
            path = ROOT / name
            production.append({'path': name, 'unchanged': path.is_file() and path.stat().st_size == expected['bytes']
                               and digest(path) == expected['sha256']})
    current_paths = {p.relative_to(ROOT).as_posix() for p in (ROOT / PREFIX).rglob('*.kt')}
    old_paths = {name for name in baseline if name.startswith(PREFIX) and name.endswith('.kt')}
    new_files = sorted(current_paths - old_paths)
    missing_files = sorted(old_paths - current_paths)
    runtime_upsert = []
    for path in (ROOT / PREFIX).rglob('*.kt'):
        if re.search(r'ON\s+CONFLICT\s*\([^)]*\)\s*DO\s+UPDATE', path.read_text(encoding='utf-8-sig'), re.I):
            runtime_upsert.append(path.relative_to(ROOT).as_posix())
    helper = (ROOT / HELPER).read_text(encoding='utf-8-sig')
    helper_rules = all(value in helper for value in ('check(db.inTransaction())', 'executeUpdateDelete()',
                       'DatabaseUtils.bindObjectToProgram', 'db.insertOrThrow', 'check(updated in 0..1)'))
    sites = study_after.count('SqliteWrites.updateOrInsert(') + legacy_after.count('SqliteWrites.updateOrInsert(')
    passed = all(row['baseline_matches_ui6'] and row['changed'] for row in rows) and outside_configure and legacy_outside \
        and all(row['unchanged'] for row in production) and new_files == [HELPER] and not missing_files \
        and not runtime_upsert and helper_rules and sites == 5
    result = {'stage': 'UI7', 'verified_at_utc': datetime.now(timezone.utc).isoformat(), 'passed': passed,
              'baseline_matches_ui6': all(row['baseline_matches_ui6'] for row in rows),
              'outside_configure_unchanged': outside_configure, 'outside_import_perform_unchanged': legacy_outside,
              'changed_production_files': rows, 'new_helper': {'path': HELPER, 'sha256': digest(ROOT / HELPER)},
              'other_production_files': production, 'new_production_files': new_files,
              'missing_production_files': missing_files, 'runtime_modern_upsert_files': runtime_upsert,
              'helper_transaction_and_constraint_rules': helper_rules, 'compatible_write_sites': sites,
              'manual_review_boundary': 'SQL write replacements inside configure/perform must also be reviewed; this gate is not a Kotlin semantic equivalence proof',
              'device_boundary': 'Actual Android checks use MementoApi35; SQLite 3.18 desktop execution is a separate engine check'}
    (OUT / 'sql-compatibility.patch').write_text(''.join(patches), encoding='utf-8')
    (OUT / 'compatibility-scope.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: result[key] for key in ('passed', 'baseline_matches_ui6', 'outside_configure_unchanged',
                     'outside_import_perform_unchanged', 'compatible_write_sites', 'new_production_files')}, indent=2))
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())

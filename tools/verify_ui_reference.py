"""Refresh the registered read-only boundary checks for a UI restoration stage."""
import argparse
from datetime import datetime, timezone
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import subprocess

ROOT=Path(__file__).resolve().parents[1]


def digest(path):
    value=sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):
            value.update(block)
    return value.hexdigest()


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--stage',required=True)
    stage=parser.parse_args().stage.lower()
    if not re.fullmatch(r'ui[2-7]',stage):
        parser.error('Expected ui2 through ui7')
    report=json.loads((ROOT/'docs/qa/ui-restoration/ui1/reference-preservation.json').read_text(encoding='utf-8-sig'))
    report.update(stage=stage.upper(),verified_at_utc=datetime.now(timezone.utc).isoformat())
    allowed=[ROOT.resolve(),Path('L:/Words').resolve(),Path('L:/Words_esp').resolve()]

    def check_file(row):
        path=Path(row['path']).resolve()
        if not any(path.is_relative_to(root) for root in allowed):
            raise ValueError('Reference input must stay in a named project')
        row.update(exists=path.is_file(),bytes=path.stat().st_size if path.is_file() else None,
                   sha256=digest(path) if path.is_file() else None)
        row['unchanged']=row['bytes']==row['baseline_bytes'] and row['sha256']==row['baseline_sha256']

    for project in report['projects'].values():
        for row in project['files']:
            check_file(row)
        env=os.environ.copy();env['GIT_OPTIONAL_LOCKS']='0'
        result=subprocess.run(project['git_command'],cwd=ROOT,env=env,capture_output=True,text=True,check=False)
        current={'exit_code':result.returncode,'porcelain':result.stdout.strip(),'stderr':result.stderr.strip()}
        project['git_current']=current
        project['git_matches_baseline']=current['exit_code']==0 and current['porcelain']==project['git_baseline']['porcelain']
        project['unchanged_files']=sum(row['unchanged'] for row in project['files'])
        project['all_registered_files_unchanged']=all(row['unchanged'] for row in project['files'])
        project['passed']=project['all_registered_files_unchanged'] and project['git_matches_baseline']
    core=report['memento_preserved_core']
    for row in core['files']+core['converted_content_packages']:
        check_file(row)
    core['schema_current_paths']=sorted(p.relative_to(ROOT).as_posix() for p in (ROOT/'schemas').glob('*') if p.is_file())
    core['schema_file_set_matches_p8']=core['schema_current_paths']==core['schema_baseline_paths']
    core['unchanged_files']=sum(row['unchanged'] for row in core['files'])
    core['passed']=core['schema_file_set_matches_p8'] and all(row['unchanged'] for row in core['files']+core['converted_content_packages'])
    report['passed']=core['passed'] and all(project['passed'] for project in report['projects'].values())
    out=ROOT/f'docs/qa/ui-restoration/{stage}'
    out.mkdir(parents=True,exist_ok=True)
    (out/'reference-preservation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'passed':report['passed'],'old_registered_files':{k:v['unchanged_files'] for k,v in report['projects'].items()},
                      'protected_memento_files':core['unchanged_files'],'converted_packages':len(core['converted_content_packages'])},indent=2))
    return 0 if report['passed'] else 1


if __name__=='__main__':
    raise SystemExit(main())

"""Convert, independently verify, then deploy two formal packages inside Memento."""
from hashlib import sha256
import argparse
import json
import os
from pathlib import Path
import shutil
import tempfile
from content_packager import package,SCHEMA
from content_search import VERSION as SEARCH_VERSION
from legacy_content_adapter import INPUTS,adapt
from validate_content_packages import verify

ROOT=Path(__file__).resolve().parents[1]

def digest(path):
    with Path(path).open('rb') as stream:
        return sha256(stream.read()).hexdigest()

def main(deploy):
    baseline=json.loads((ROOT/'docs/qa/baseline/reference-audit.json').read_text(encoding='utf-8'))
    (ROOT/'.tools/tmp').mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='content-build-',dir=ROOT/'.tools/tmp') as temp:
        staging=Path(temp)
        manifest={'schema_version':1,'conversion_version':'memento-legacy-adapter-1','normalization_version':SEARCH_VERSION,
                  'schema_sha256':digest(SCHEMA),'packages':{},'licenses':{}}
        reports={}
        for target,config in INPUTS.items():
            source=ROOT/'data/source-snapshots'/config['file']
            source_hash=digest(source)
            if source_hash!=baseline['projects'][target]['formal_content']['sha256']:
                raise ValueError('Frozen formal input differs from audited baseline')
            notice=(ROOT/'app/src/main/assets/licenses'/f'{target}-content.md').read_text(encoding='utf-8')
            print(f'{target}: read frozen formal input',flush=True)
            content=adapt(target,source,notice,source_hash)
            intermediate=staging/f'{target}.content-v1.json'
            with intermediate.open('w',encoding='utf-8',newline='\n') as output:
                json.dump(content,output,ensure_ascii=False,separators=(',',':'))
                output.write('\n')
            print(f'{target}: validate normalized records and build unified package',flush=True)
            candidate=staging/f'{target}.db'
            package(content,candidate)
            del content
            print(f'{target}: round-trip every legacy field, association and translation',flush=True)
            reports[target]=verify(target,source,candidate,notice)
            if not reports[target]['passed']:
                raise ValueError(f'{target}: full comparison failed: {json.dumps(reports[target])}')
            manifest['packages'][target]={'target_language':target,'asset':f'content/{target}.db','schema_version':1,
                'sha256':digest(candidate),'bytes':candidate.stat().st_size,'content_version':source_hash,
                'source_file':config['file'],'source_db_sha256':source_hash,'source_schema_version':config['schema'],
                'learning_languages':list(config['translations']),'pronunciation_locale':config['locale'],
                'default_book':config['default_book'],'counts':reports[target]['counts'],
                'intermediate_sha256':digest(intermediate),'review_status':'legacy-preserved-unassessed'}
            print(f'{target}: full comparison passed',flush=True)
        for notice in sorted((ROOT/'app/src/main/assets/licenses').iterdir()):
            if notice.is_file():
                manifest['licenses'][notice.name]={'sha256':digest(notice),'bytes':notice.stat().st_size}
        for target in INPUTS:
            for directory,filename in [('data/content-packages',f'{target}.db'),('data/normalized',f'{target}.content-v1.json')]:
                destination=ROOT/directory/filename
                destination.parent.mkdir(parents=True,exist_ok=True)
                os.replace(staging/filename,destination)
            report_path=ROOT/'docs/qa/p4'/f'{target}-content-diff.json'
            report_path.parent.mkdir(parents=True,exist_ok=True)
            report_path.write_text(json.dumps(reports[target],ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        manifest_path=ROOT/'data/manifests/content-packages.json'
        manifest_path.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        if deploy:
            destination=ROOT/'app/src/main/assets/content'
            destination.mkdir(parents=True,exist_ok=True)
            for target in INPUTS:
                shutil.copyfile(ROOT/'data/content-packages'/f'{target}.db',destination/f'{target}.db')
            shutil.copyfile(manifest_path,destination/'manifest.json')
        print(json.dumps({'passed':True,'deployed':deploy,'packages':{t:{'sha256':p['sha256'],'bytes':p['bytes']} for t,p in manifest['packages'].items()}},indent=2),flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--deploy',action='store_true')
    main(parser.parse_args().deploy)

"""Current contract and full content comparison; an explicit output preserves P8 evidence."""
import argparse
from collections import Counter
from hashlib import sha256
import json
from pathlib import Path
import sys
import unittest
from validate_data_contracts import Contracts
from validate_p3_contracts import UpgradeContracts
from test_content_contract import Contract
from test_content_conversion import Conversion
from test_extension_packages import ExtensionPackages
from validate_content_packages import verify
from check_content_assets import check_assets

ROOT=Path(__file__).resolve().parents[1]

class MethodResult(unittest.TextTestResult):
    """Record method identities without replacing the assertions or their execution."""
    def __init__(self,*args,**kwargs):
        super().__init__(*args,**kwargs)
        self.started_cases=[]
        self.successful_cases=[]
        self.failed_cases=[]
        self.errored_cases=[]
        self.skipped_cases=[]
    def startTest(self,test):
        self.started_cases.append(test.id())
        super().startTest(test)
    def addSuccess(self,test):
        self.successful_cases.append(test.id())
        super().addSuccess(test)
    def addFailure(self,test,error):
        self.failed_cases.append(test.id())
        super().addFailure(test,error)
    def addError(self,test,error):
        self.errored_cases.append(test.id())
        super().addError(test,error)
    def addSkip(self,test,reason):
        self.skipped_cases.append(test.id())
        super().addSkip(test,reason)

def source_descriptor(cls):
    path=Path(sys.modules[cls.__module__].__file__).resolve()
    if not path.is_relative_to(ROOT):
        raise ValueError('Desktop test source must stay inside Memento: '+str(path))
    methods=unittest.defaultTestLoader.getTestCaseNames(cls)
    return {'class':cls.__module__+'.'+cls.__name__,'path':path.relative_to(ROOT).as_posix(),
            'sha256':sha256(path.read_bytes()).hexdigest(),'declared_test_methods':methods,
            'expected_cases':[cls.__module__+'.'+cls.__name__+'.'+method for method in methods]}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence-directory',help='Output below Memento; omitted keeps the historical P8 entry point')
    args=parser.parse_args()
    out=(ROOT/(args.evidence_directory or 'docs/qa/p8')).resolve()
    if not out.is_relative_to(ROOT.resolve()) or out==ROOT.resolve():
        parser.error('Desktop evidence must stay inside Memento')
    if args.evidence_directory is not None and out.is_relative_to((ROOT/'docs/qa/p8').resolve()):
        parser.error('Explicit desktop output must preserve P8; use docs/qa/ui-restoration/ui7/desktop')
    out.mkdir(parents=True,exist_ok=True)
    # Clear prior success before any execution, so an interrupted comparison cannot reuse stale evidence.
    for name in ('desktop-contracts.json','fr-content-diff.json','es-content-diff.json'):
        (out/name).write_text(json.dumps({'passed':False,'status':'running'})+'\n',encoding='utf-8')
    classes=(Contracts,UpgradeContracts,Contract,Conversion,ExtensionPackages)
    sources=[source_descriptor(cls) for cls in classes]
    suite=unittest.TestSuite(unittest.defaultTestLoader.loadTestsFromTestCase(cls) for cls in classes)
    expected_cases=sorted(case.id() for group in suite for case in group)
    result=unittest.TextTestRunner(verbosity=2,resultclass=MethodResult).run(suite)
    started=set(result.started_cases)
    successful=set(result.successful_cases)
    duplicate_cases=sorted(case for case,count in Counter(result.started_cases).items() if count>1)
    checks={'unittest_passed':result.wasSuccessful(),
            'exact_started_set':started==set(expected_cases),
            'exact_successful_set':successful==set(expected_cases),
            'no_duplicate_cases':not duplicate_cases,
            'no_skipped_cases':not result.skipped_cases,
            'source_unchanged':sources==[source_descriptor(cls) for cls in classes]}
    groups=[]
    for source in sources:
        expected=set(source['expected_cases'])
        actual=sorted(case for case in successful if case.startswith(source['class']+'.'))
        groups.append({'class':source['class'],'tests':len(actual),'declared_tests':len(expected),
                       'successful_cases':actual,'passed':set(actual)==expected})
    checks['all_classes_passed']=all(row['passed'] for row in groups)
    contracts={'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),
               'passed':all(checks.values()),'suites':[cls.__module__+'.'+cls.__name__ for cls in classes],
               'expected_cases':expected_cases,'successful_cases':sorted(successful),
               'missing_cases':sorted(set(expected_cases)-successful),'extra_cases':sorted(started-set(expected_cases)),
               'duplicate_cases':duplicate_cases,'failed_cases':result.failed_cases,
               'errored_cases':result.errored_cases,'skipped_cases':result.skipped_cases,
               'source_descriptors':sources,'classes':groups,'checks':checks}
    (out/'desktop-contracts.json').write_text(json.dumps(contracts,indent=2)+'\n',encoding='utf-8')
    check_assets()
    content={}
    for target,filename in [('fr','french_content.db'),('es','spanish_content.db')]:
        report=verify(target,ROOT/'data/source-snapshots'/filename,ROOT/'data/content-packages'/f'{target}.db',
                      (ROOT/'app/src/main/assets/licenses'/f'{target}-content.md').read_text(encoding='utf-8'))
        (out/f'{target}-content-diff.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        content[target]=report['passed']
        print(f"Full current source/package comparison {target}: {report['passed']}",flush=True)
    passed=contracts['passed'] and all(content.values())
    print(json.dumps({'passed':passed,'desktop_tests':result.testsRun,'content':content},indent=2))
    return 0 if passed else 1

if __name__=='__main__':
    raise SystemExit(main())

from copy import deepcopy
import json
from pathlib import Path
import unittest
from jsonschema import ValidationError
from content_contract import validate_intermediate


def fixture():
    translation={'text':'to be','review_status':'legacy-preserved','source_json':'{}'}
    word={'uid':'fr:être:verb:1','lemma':'être','part_of_speech':'verb','homonym':1,'level_system':'CEFR','level_value':'A1',
          'gender':'','order':0,'provenance_json':'{}','pronunciations':[],'forms':[],'grammar_features':{},'paradigms':[],
          'senses':[{'id':'old-sense','order':0,'is_core':True,'translations':{'en':translation},
                     'examples':[{'id':'old-example','order':0,'source_text':'Je suis ici.','translations':{'en':dict(translation,text='I am here.')}}]}]}
    return {'schema_version':1,'target_language':'fr','content_version':'fixture','legacy_meta':{},'lexemes':[word],
            'books':[{'id':'old-book','order':0,'level_system':'CEFR','level_value':'A1','translations':{'zh-Hans':{'title':'测试词书','description':''}},'members':[{'uid':word['uid'],'order':0}]}], 'attribution':[]}


class Contract(unittest.TestCase):
    def test_old_ids_and_new_learning_language_records_need_no_new_fixed_columns(self):
        content=fixture()
        content['lexemes'][0]['senses'][0]['translations']['de']={'text':'sein','review_status':'fixture-only','source_json':'{}'}
        validate_intermediate(content)
        self.assertEqual('old-sense',content['lexemes'][0]['senses'][0]['id'])

    def test_english_target_and_blank_answer_are_rejected(self):
        for target in ['en','en-US','eng']:
            content=fixture();content['target_language']=target
            with self.assertRaises(ValidationError):validate_intermediate(content)
        content=fixture();content['lexemes'][0]['senses'][0]['translations']['en']['text']='   '
        with self.assertRaises(ValidationError):validate_intermediate(content)

    def test_duplicate_id_and_broken_book_link_are_rejected(self):
        content=fixture();content['lexemes'].append(deepcopy(content['lexemes'][0]))
        with self.assertRaises(ValueError):validate_intermediate(content)
        content=fixture();content['books'][0]['members'][0]['uid']='fr:missing:noun:1'
        with self.assertRaises(ValueError):validate_intermediate(content)

    def test_mixed_target_and_duplicate_example_identity_are_rejected(self):
        content=fixture();content['lexemes'][0]['uid']='es:ser:verb:1'
        with self.assertRaises(ValueError):validate_intermediate(content)
        content=fixture();sense=content['lexemes'][0]['senses'][0];sense['examples'].append(deepcopy(sense['examples'][0]))
        with self.assertRaises(ValueError):validate_intermediate(content)


if __name__=='__main__':
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Contract))
    destination=Path(__file__).resolve().parents[1]/'docs/qa/p2/intermediate-contract.json'
    destination.parent.mkdir(parents=True,exist_ok=True)
    destination.write_text(json.dumps({'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'passed':result.wasSuccessful()},indent=2)+'\n',encoding='utf-8')
    raise SystemExit(0 if result.wasSuccessful() else 1)

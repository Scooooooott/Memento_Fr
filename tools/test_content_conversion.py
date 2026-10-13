"""Focused conversion tests use tiny new fixture stores, never old project outputs."""
from hashlib import sha256
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from content_packager import package
from content_search import search_key
from legacy_content_adapter import adapt,readonly
from validate_content_packages import verify
from test_content_contract import Contract

ROOT=Path(__file__).resolve().parents[1]
NOTICE='Fixture-only license notice; no formal content.\n'

def fixture(path,target):
    original=ROOT/'data/source-snapshots'/('french_content.db' if target=='fr' else 'spanish_content.db')
    with closing(readonly(original)) as source:
        schema=[row[0] for row in source.execute("SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' ORDER BY type DESC,name")]
    db=sqlite3.connect(path)
    db.execute('PRAGMA foreign_keys=ON')
    for statement in schema:
        db.execute(statement)
    db.execute('PRAGMA user_version='+('2' if target=='fr' else '1'))
    locale='fr-FR' if target=='fr' else 'es-ES'
    db.execute('INSERT INTO content_meta VALUES(?,?)',('language',locale))
    db.execute('INSERT INTO content_meta VALUES(?,?)',('historical_count','99999'))
    words=[('être','verb') if target=='fr' else ('ser','verb'),('prêt','adjective') if target=='fr' else ('listo','adjective')]
    uids=[]
    for order,(lemma,pos) in zip([0,7],words):
        uid=f'{target}:{lemma}:{pos}:1';uids.append(uid)
        db.execute('INSERT INTO lexeme VALUES(?,?,?,?,?,?,?,?,?)',(uid,locale,lemma,pos,1,'A1','',order,'{"original":"unchanged"}'))
        db.execute('INSERT INTO pronunciation VALUES(?,?,?,?)',(uid,locale,'/'+lemma+'/',None))
        db.execute('INSERT INTO word_form VALUES(?,?,?,?)',(uid,5,'original form',lemma+'s'))
    for sid,uid,order,meaning in [('old-sense-a',uids[0],0,'first meaning'),('old-sense-b',uids[0],4,'second meaning'),('old-sense-c',uids[1],0,'other meaning')]:
        values=(sid,uid,order,meaning,'sens '+meaning,'译文 '+meaning)
        db.execute('INSERT INTO sense VALUES('+','.join('?' for _ in range(7 if target=='fr' else 6))+')',values+((1,) if target=='fr' else ()))
    for eid,sid,order,text in [('old-example-a','old-sense-a',0,'source sentence one'),('old-example-b','old-sense-a',3,'source sentence two'),('old-example-c','old-sense-b',0,'source sentence three')]:
        db.execute('INSERT INTO example VALUES(?,?,?,?,?,?,?)',(eid,sid,order,text,'English '+text,'other '+text,'中文 '+text))
    if target=='fr':
        db.execute('INSERT INTO verb_info VALUES(?,?,?,?,?)',(uids[0],'3e groupe','être','été','étant'))
        for tense,order in [('présent',0),('imparfait',2)]:
            for person in [1,6]:
                db.execute('INSERT INTO conjugation_form VALUES(?,?,?,?,?,?,?)',(uids[0],'indicatif',tense,order,person,'subject '+str(person),'form '+str(person)+tense))
    else:
        db.execute('INSERT INTO verb_info VALUES(?,?,?,?,?,?,?)',(uids[0],'-er','haber','sido','siendo',1,1))
        for pid,mood,tense,order in [('indicativo_presente','indicativo','presente',0),('regional_fixture','imperativo','afirmativo',3)]:
            db.execute('INSERT INTO conjugation_paradigm VALUES(?,?,?,?,?,?,?,?)',(uids[0],pid,mood,tense,'label '+pid,'América','voseo fixture',order))
            for order,subject in [(0,'yo'),(3,'vos')]:
                db.execute('INSERT INTO conjugation_form VALUES(?,?,?,?,?,?)',(uids[0],pid,subject,'pronoun '+subject,'form '+subject,order))
    db.execute('INSERT INTO vocabulary_book VALUES(?,?,?,?)',('stable-book','原词书','原说明',8))
    for order,uid in zip([0,5],uids):
        db.execute('INSERT INTO book_lexeme VALUES(?,?,?)',('stable-book',uid,order))
    db.commit();db.close()

class Conversion(unittest.TestCase):
    def setUp(self):
        (ROOT/'.tools/tmp').mkdir(parents=True,exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='p4-test-',dir=ROOT/'.tools/tmp')
        self.root=Path(self.temp.name)
    def tearDown(self):
        self.temp.cleanup()
    def build(self,target='fr'):
        source=self.root/(target+'-source.db');fixture(source,target)
        content=adapt(target,source,NOTICE)
        output=self.root/(target+'-package.db');package(content,output)
        return source,output,content
    def test_french_every_field_and_gapped_identity_order_roundtrip(self):
        source,output,_=self.build()
        result=verify('fr',source,output,NOTICE)
        self.assertTrue(result['passed'],result)
        self.assertEqual(2,result['counts']['lexeme'])
        self.assertEqual(2,result['counts']['conjugation_paradigm'])
        self.assertEqual(4,result['counts']['conjugation_form'])
    def test_spanish_variable_paradigms_regions_notes_and_flags_roundtrip(self):
        source,output,_=self.build('es')
        result=verify('es',source,output,NOTICE)
        self.assertTrue(result['passed'],result)
        with closing(sqlite3.connect(output)) as db, db:
            self.assertEqual([('América','voseo fixture')]*2,list(db.execute('SELECT region,note FROM conjugation_paradigm ORDER BY sort_order')))
            self.assertEqual('1',db.execute("SELECT value FROM grammar_feature WHERE name='is_reflexive'").fetchone()[0])
    def test_equal_counts_do_not_hide_swapped_sense_translations(self):
        source,output,_=self.build()
        with closing(sqlite3.connect(output)) as db, db:
            db.execute("UPDATE sense_translation SET text=CASE sense_id WHEN 'old-sense-a' THEN 'second meaning' WHEN 'old-sense-b' THEN 'first meaning' ELSE text END WHERE learning_language='en'")
        result=verify('fr',source,output,NOTICE)
        self.assertFalse(result['passed'])
        self.assertEqual(result['roundtrip']['sense']['source_rows'],result['roundtrip']['sense']['roundtrip_rows'])
        self.assertEqual(2,result['roundtrip']['sense']['missing_or_changed'])
    def test_example_reparenting_is_detected_with_counts_unchanged(self):
        source,output,_=self.build()
        with closing(sqlite3.connect(output)) as db, db:
            db.execute("UPDATE example SET sense_id='old-sense-c',sort_order=8 WHERE example_id='old-example-a'")
        self.assertFalse(verify('fr',source,output,NOTICE)['roundtrip']['example']['passed'])
    def test_book_order_changes_are_detected(self):
        source,output,_=self.build()
        with closing(sqlite3.connect(output)) as db, db:
            db.execute('UPDATE book_lexeme SET sort_order=9 WHERE sort_order=0')
            db.execute('UPDATE book_lexeme SET sort_order=0 WHERE sort_order=5')
            db.execute('UPDATE book_lexeme SET sort_order=5 WHERE sort_order=9')
        self.assertFalse(verify('fr',source,output,NOTICE)['roundtrip']['book_lexeme']['passed'])
    def test_french_derived_ids_and_labels_are_checked_independently(self):
        source,output,_=self.build()
        with closing(sqlite3.connect(output)) as db, db:
            db.execute("UPDATE conjugation_paradigm SET label='wrong label'")
        result=verify('fr',source,output,NOTICE)
        self.assertTrue(result['roundtrip']['conjugation_form']['passed'])
        self.assertFalse(result['normalized_checks']['french_paradigm_identity_and_metadata'])
    def test_source_reference_and_license_changes_are_detected(self):
        source,output,_=self.build()
        with closing(sqlite3.connect(output)) as db, db:
            db.execute("UPDATE example_translation SET source_json='{}' WHERE learning_language='en'")
            db.execute("UPDATE attribution SET license_notice='wrong license'")
        result=verify('fr',source,output,NOTICE)
        self.assertFalse(result['normalized_checks']['example_translation_source_reference'])
        self.assertFalse(result['normalized_checks']['license_notice_verbatim'])
    def test_identical_input_builds_identical_bytes_in_this_toolchain(self):
        for target in ['fr','es']:
            _,output,content=self.build(target)
            second=self.root/(target+'-second.db');package(content,second)
            self.assertEqual(sha256(output.read_bytes()).hexdigest(),sha256(second.read_bytes()).hexdigest())
    def test_bad_intermediate_cannot_overwrite_existing_package(self):
        _,output,content=self.build()
        before=output.read_bytes()
        with self.assertRaises(ValueError):package(content,output)
        self.assertEqual(before,output.read_bytes())
        content['lexemes'][0]['senses'][0]['translations']['en']['text']=' '
        from jsonschema import ValidationError
        with self.assertRaises(ValidationError):package(content,self.root/'bad.db')
        self.assertFalse((self.root/'bad.db').exists())
    def test_search_key_contract_preserves_non_latin_marks_and_escapable_literals(self):
        for text,expected in [(' ÊTRE ','etre'),('negociación','negociacion'),('ＡＢＣ','abc'),('ÉCOLE','ecole'),('100%_\\','100%_\\'),('日本語','日本語'),('が','が'),('مُدَرِّس','مُدَرِّس'),('😀中文','😀中文')]:
            self.assertEqual(expected,search_key(text))

if __name__=='__main__':
    suite=unittest.TestSuite([unittest.defaultTestLoader.loadTestsFromTestCase(Conversion),unittest.defaultTestLoader.loadTestsFromTestCase(Contract)])
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    destination=ROOT/'docs/qa/p4/conversion-tests.json'
    destination.parent.mkdir(parents=True,exist_ok=True)
    destination.write_text(json.dumps({'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'passed':result.wasSuccessful()},indent=2)+'\n',encoding='utf-8')
    raise SystemExit(0 if result.wasSuccessful() else 1)

"""P7 independent content/storage assertions against only the small authored packages."""
from copy import deepcopy
from hashlib import sha256
import json
from pathlib import Path
import sqlite3
import unittest
from content_contract import validate_intermediate
from content_packager import package
from content_search import search_key

ROOT=Path(__file__).resolve().parents[1]
FIXTURES=ROOT/'data/fixtures/p7/generated'

class ExtensionPackages(unittest.TestCase):
    def db(self,target):return sqlite3.connect((FIXTURES/f'{target}.db').as_uri()+'?mode=ro',uri=True)
    def content(self,target):return json.loads((FIXTURES/f'{target}.json').read_text(encoding='utf-8'))
    def test_all_four_packages_have_valid_structure_identity_and_metadata(self):
        manifest=json.loads((FIXTURES/'manifest.json').read_text(encoding='utf-8'))
        self.assertTrue(manifest['fixture_only'])
        for target,row in manifest['packages'].items():
            content=self.content(target);validate_intermediate(content)
            self.assertEqual(row['sha256'],sha256((FIXTURES/f'{target}.db').read_bytes()).hexdigest())
            with self.db(target) as db:
                self.assertEqual('ok',db.execute('PRAGMA integrity_check').fetchone()[0]);self.assertEqual([],list(db.execute('PRAGMA foreign_key_check')))
                self.assertEqual([(target,)],list(db.execute('SELECT language_id FROM package_language')))
                self.assertEqual({w['uid'] for w in content['lexemes']},{uid for uid, in db.execute('SELECT lexeme_uid FROM lexeme')})
    def test_level_systems_and_variable_forms_survive_generic_packager(self):
        for target,system,rows in [('de','CEFR',4),('nl','CEFR',2),('ja','JLPT',2),('ko','TOPIK',4)]:
            with self.db(target) as db:
                self.assertEqual(system,db.execute('SELECT level_system FROM vocabulary_book').fetchone()[0])
                self.assertEqual(rows,db.execute('SELECT COUNT(*) FROM conjugation_form WHERE lexeme_uid=?',(f'{target}:fixture:0:1',)).fetchone()[0])
    def test_kana_and_hangul_search_do_not_collapse_distinct_words(self):
        with self.db('ja') as db:
            a=db.execute('SELECT lemma_key FROM lexeme_search WHERE lexeme_uid=?',('ja:fixture:1:1',)).fetchone()[0]
            b=db.execute('SELECT lemma_key FROM lexeme_search WHERE lexeme_uid=?',('ja:fixture:2:1',)).fetchone()[0]
            self.assertEqual('か',a);self.assertEqual('が',b);self.assertNotEqual(a,b)
        self.assertNotEqual(search_key('가'),search_key('각'));self.assertEqual(search_key('가'),search_key('가'))
    def test_reading_types_and_nonperson_grammar_keys_are_preserved(self):
        with self.db('ja') as db:self.assertEqual({'KANA','ROMAJI'},{row[0] for row in db.execute('SELECT reading_type FROM pronunciation WHERE lexeme_uid=?',('ja:fixture:0:1',))})
        with self.db('ko') as db:self.assertEqual({'plain','polite','formal','honorific'},{row[0] for row in db.execute('SELECT subject_key FROM conjugation_form')})
    def test_new_learning_language_rows_need_no_added_columns_and_missing_stays_missing(self):
        with self.db('ja') as db:
            before=list(db.execute('PRAGMA table_info(sense_translation)'))
            self.assertEqual(1,db.execute("SELECT COUNT(*) FROM sense_translation WHERE learning_language='de'").fetchone()[0])
        memory=sqlite3.connect(':memory:');memory.executescript((ROOT/'schemas/content-v1.sql').read_text(encoding='utf-8'))
        try:
            memory.execute("INSERT INTO package_language(language_id) VALUES('ja')")
            memory.execute("INSERT INTO lexeme VALUES('ja:stable:1','ja','猫','noun',1,'JLPT','N5','',0,'{}')")
            memory.execute("INSERT INTO sense VALUES('stable-sense','ja:stable:1',0,1)")
            memory.execute("INSERT INTO sense_translation VALUES('stable-sense','de','Katze','fixture-only','{}')")
            self.assertEqual(before,list(memory.execute('PRAGMA table_info(sense_translation)')))
            self.assertEqual('Katze',memory.execute("SELECT text FROM sense_translation WHERE learning_language='de'").fetchone()[0])
        finally:memory.close()
    def test_english_and_regional_english_are_rejected_by_package_and_user_contracts(self):
        for target in ['en','en-US','en_GB','eng','eng-GB']:
            content=self.content('ja');content['target_language']=target
            with self.assertRaises(Exception):validate_intermediate(content)
            for schema in ['content','user']:
                db=sqlite3.connect(':memory:');db.executescript((ROOT/'schemas'/f'{schema}-v1.sql').read_text(encoding='utf-8'))
                table='package_language' if schema=='content' else 'target_language_registry'
                try:
                    with self.assertRaises(sqlite3.IntegrityError):db.execute(f'INSERT INTO {table}(language_id) VALUES(?)',(target,))
                finally:db.close()

if __name__=='__main__':
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(ExtensionPackages))
    output=ROOT/'docs/qa/p7/extension-packages.json';output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps({'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'passed':result.wasSuccessful()},indent=2)+'\n',encoding='utf-8')
    raise SystemExit(0 if result.wasSuccessful() else 1)

"""Exercise storage invariants using only new, isolated in-memory databases."""
from pathlib import Path
import json
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]


def database(name):
    db = sqlite3.connect(':memory:')
    db.executescript((ROOT / 'schemas' / f'{name}-v1.sql').read_text(encoding='utf-8'))
    return db


class Contracts(unittest.TestCase):
    def setUp(self):
        self.db = database('user')
        self.db.executemany('INSERT INTO target_language_registry VALUES(?)', [('fr',), ('es',)])
        self.db.commit()

    def tearDown(self):
        self.assertEqual([], list(self.db.execute('PRAGMA foreign_key_check')))
        self.db.close()

    def plan(self, language='fr', day='2026-10-09'):
        return self.db.execute("""INSERT INTO daily_plan(target_language,business_date,zone_id,created_at,due_cutoff_at,
            study_mode,target_snapshot,book_snapshot,content_version,policy_version,eligible_review_count,status)
            VALUES(?,?,'Asia/Shanghai',100,200,'STANDARD',20,'book','fixture',1,28,'ACTIVE')""", (language, day)).lastrowid

    def item(self, plan, language='fr', uid='fr:être:verb:1', position=0):
        return self.db.execute("""INSERT INTO daily_plan_item(plan_id,target_language,lexeme_uid,queue_kind,position,is_new,revealed)
            VALUES(?,?,?,'NORMAL',?,1,1)""", (plan, language, uid, position)).lastrowid

    def log(self, plan, item, request='request-1', kind='NORMAL'):
        day = self.db.execute('SELECT business_date FROM daily_plan WHERE id=?', (plan,)).fetchone()[0]
        self.db.execute("""INSERT INTO review_log(target_language,lexeme_uid,kind,plan_id,item_id,request_id,rating,is_new,
            reviewed_at,business_date,zone_id,previous_stability,previous_difficulty,next_stability,next_difficulty,
            next_due_at,elapsed_days,scheduler_version,desired_retention)
            VALUES('fr','fr:être:verb:1',?,?,?,?, 'GOOD',1,100,?,'Asia/Shanghai',0,0,2.3,2.1,86400100,0,'FSRS-6',0.9)""",
            (kind, plan, item, request, day))

    def test_english_and_regional_aliases_rejected_in_both_schemas(self):
        for name, table in [('user', 'target_language_registry'), ('content', 'package_language')]:
            with database(name) as db:
                for language in ['en', 'en-us', 'en-gb', 'en_latn_us', 'eng', 'eng-us', 'EN-US']:
                    with self.assertRaises(sqlite3.IntegrityError, msg=language):
                        db.execute(f'INSERT INTO {table}(language_id) VALUES(?)', (language,))

    def test_review_all_requires_no_target_and_standard_requires_positive_target(self):
        self.db.execute("INSERT INTO language_settings(target_language,study_mode,daily_target,book_id) VALUES('fr','REVIEW_ALL',NULL,'book')")
        for mode, target in [('REVIEW_ALL', 20), ('STANDARD', None), ('STANDARD', 0)]:
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute("INSERT INTO language_settings(target_language,study_mode,daily_target,book_id) VALUES('es',?,?, 'book')", (mode, target))

    def test_each_language_has_independent_plan_but_same_day_cannot_repeat(self):
        first = self.plan(); second = self.plan('es')
        self.assertNotEqual(first, second)
        with self.assertRaises(sqlite3.IntegrityError):
            self.plan()
        with self.assertRaises(sqlite3.IntegrityError):
            self.plan(day='2026-10-10')

    def test_cross_day_close_does_not_pretend_completion(self):
        first = self.plan()
        self.db.execute("UPDATE daily_plan SET status='CLOSED_INCOMPLETE',closed_at=300,close_reason='NEW_DAY' WHERE id=?", (first,))
        self.plan(day='2026-10-10')
        self.assertEqual(('CLOSED_INCOMPLETE', None), self.db.execute('SELECT status,completed_at FROM daily_plan WHERE id=?', (first,)).fetchone())

    def test_plan_item_cannot_cross_language_or_use_another_language_uid(self):
        plan = self.plan()
        with self.assertRaises(sqlite3.IntegrityError):
            self.item(plan, 'es', 'es:ser:verb:1')
        with self.assertRaises(sqlite3.IntegrityError):
            self.item(plan, 'fr', 'es:ser:verb:1')

    def test_same_uid_cannot_reappear_in_extra_queue_or_twice_in_normal_queue(self):
        plan = self.plan(); self.item(plan)
        with self.assertRaises(sqlite3.IntegrityError):
            self.item(plan, position=1)
        batch = self.db.execute("INSERT INTO extra_review_batch(plan_id,target_language,decision,created_at) VALUES(?,'fr','ACCEPTED',100)", (plan,)).lastrowid
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO daily_plan_item(plan_id,target_language,lexeme_uid,queue_kind,extra_batch_id,position,is_new) VALUES(?,'fr','fr:être:verb:1','EXTRA',?,0,0)", (plan, batch))

    def test_duplicate_rating_and_wrong_queue_type_are_rejected(self):
        plan=self.plan(); item=self.item(plan)
        with self.assertRaises(sqlite3.IntegrityError):
            self.log(plan, item, kind='EXTRA')
        self.log(plan, item)
        with self.assertRaises(sqlite3.IntegrityError):
            self.log(plan, item, request='different-request')

    def test_real_review_time_can_keep_previous_business_day(self):
        plan=self.plan(day='2026-10-08'); item=self.item(plan); self.log(plan,item)
        self.assertEqual((100,'2026-10-08'),self.db.execute('SELECT reviewed_at,business_date FROM review_log').fetchone())

    def test_partial_scoring_failure_rolls_back_card_log_and_queue(self):
        plan=self.plan(); item=self.item(plan); self.db.commit()
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.db.execute("INSERT INTO learning_card VALUES('fr','fr:être:verb:1',2.3,2.1,86400100,100,1,0,'REVIEW',1)")
                self.log(plan,item)
                self.db.execute("UPDATE daily_plan_item SET state='GRADED',rating='GOOD' WHERE id=?", (item,))
                self.item(plan,position=1)
        self.assertEqual(0,self.db.execute('SELECT COUNT(*) FROM learning_card').fetchone()[0])
        self.assertEqual(0,self.db.execute('SELECT COUNT(*) FROM review_log').fetchone()[0])
        self.assertEqual(('PENDING',None),self.db.execute('SELECT state,rating FROM daily_plan_item').fetchone())

    def test_legacy_source_ids_can_overlap_across_languages_without_duplicates(self):
        self.db.execute("INSERT INTO migration_batch(source_identity,input_sha256,input_schema_version,imported_at,report_json) VALUES('fr-device','a',1,100,'{}')")
        self.db.execute("INSERT INTO migration_batch(source_identity,input_sha256,input_schema_version,imported_at,report_json) VALUES('es-device','b',1,100,'{}')")
        self.db.execute("INSERT INTO migration_ledger VALUES('fr-device','log','7','new-fr','hash',1)")
        self.db.execute("INSERT INTO migration_ledger VALUES('es-device','log','7','new-es','hash',2)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO migration_ledger VALUES('fr-device','log','7','newer-fr','other-hash',1)")

    def test_language_package_keeps_sense_example_translation_identity(self):
        db=database('content')
        try:
            db.execute("INSERT INTO package_language(language_id) VALUES('fr')")
            db.execute("INSERT INTO lexeme VALUES('fr:être:verb:1','fr','être','verb',1,'CEFR','A1','',0,'{}')")
            db.execute("INSERT INTO sense VALUES('sense-old-id','fr:être:verb:1',0,1)")
            db.execute("INSERT INTO sense_translation VALUES('sense-old-id','en','to be','legacy-preserved','{}')")
            db.execute("INSERT INTO example VALUES('example-old-id','sense-old-id',0,'Je suis ici.')")
            db.execute("INSERT INTO example_translation VALUES('example-old-id','en','I am here.','legacy-preserved','{}')")
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute("INSERT INTO example_translation VALUES('missing-example','en','wrong','legacy-preserved','{}')")
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute("INSERT INTO package_language(language_id) VALUES('es')")
            self.assertEqual([],list(db.execute('PRAGMA foreign_key_check')))
        finally:
            db.close()

    def test_invalid_blank_translation_cannot_become_a_learning_answer(self):
        db=database('content')
        try:
            db.execute("INSERT INTO package_language(language_id) VALUES('fr')")
            db.execute("INSERT INTO lexeme VALUES('fr:être:verb:1','fr','être','verb',1,'CEFR','A1','',0,'{}')")
            db.execute("INSERT INTO sense VALUES('sense','fr:être:verb:1',0,1)")
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute("INSERT INTO sense_translation VALUES('sense','en','   ','legacy-preserved','{}')")
        finally:
            db.close()


if __name__ == '__main__':
    suite=unittest.defaultTestLoader.loadTestsFromTestCase(Contracts)
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    destination=ROOT / 'docs/qa/p2/schema-contracts.json'
    destination.parent.mkdir(parents=True,exist_ok=True)
    destination.write_text(json.dumps({'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),
                                      'passed':result.wasSuccessful()},indent=2)+'\n',encoding='utf-8')
    raise SystemExit(0 if result.wasSuccessful() else 1)

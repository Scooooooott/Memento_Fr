"""Additive v1 -> v2 checks; all stores are isolated in memory."""
import json
from pathlib import Path
import sqlite3
import unittest

ROOT=Path(__file__).resolve().parents[1]
V1=(ROOT/'schemas/user-v1.sql').read_text(encoding='utf-8')
V2=(ROOT/'schemas/user-v2-upgrade.sql').read_text(encoding='utf-8')


class UpgradeContracts(unittest.TestCase):
    def setUp(self):
        self.db=sqlite3.connect(':memory:')
        self.db.executescript(V1)
        self.db.execute("INSERT INTO target_language_registry VALUES('fr')")
        self.db.execute("INSERT INTO target_language_registry VALUES('es')")
        self.db.execute("INSERT INTO app_settings(id,study_zone_id) VALUES(1,'Europe/Paris')")
        self.db.execute("INSERT INTO language_settings(target_language,study_mode,book_id) VALUES('fr','REVIEW_ALL','saved-book')")
        self.db.execute("INSERT INTO learning_card VALUES('fr','fr:stable:verb:1',2.3,5,100,50,8,2,'REVIEW',1)")
        self.db.execute("""INSERT INTO daily_plan(target_language,business_date,zone_id,created_at,due_cutoff_at,study_mode,
            target_snapshot,book_snapshot,content_version,policy_version,eligible_review_count,status)
            VALUES('fr','2026-10-09','Europe/Paris',1,100,'STANDARD',3,'saved-book','fixture',1,1,'ACTIVE')""")
        self.db.execute("INSERT INTO daily_plan_item(plan_id,target_language,lexeme_uid,queue_kind,position,is_new) VALUES(1,'fr','fr:stable:verb:1','NORMAL',0,0)")
        self.db.commit()

    def tearDown(self):
        self.assertEqual([],list(self.db.execute('PRAGMA foreign_key_check')))
        self.db.close()

    def test_populated_upgrade_preserves_settings_cards_and_queue_identity(self):
        before=list(self.db.execute('SELECT * FROM learning_card'))
        self.db.executescript(V2)
        self.assertEqual(2,self.db.execute('PRAGMA user_version').fetchone()[0])
        self.assertEqual(before,list(self.db.execute('SELECT * FROM learning_card')))
        self.assertEqual(('REVIEW_ALL',None,'saved-book'),self.db.execute('SELECT study_mode,daily_target,book_id FROM language_settings').fetchone())
        self.assertEqual((1,0),self.db.execute('SELECT id,offered FROM daily_plan_item').fetchone())

    def test_upgrade_failure_can_roll_back_as_one_transaction(self):
        with self.assertRaises(sqlite3.OperationalError):
            self.db.executescript('BEGIN;\n'+V2+'\nINSERT INTO missing_table VALUES(1);\nCOMMIT;')
        self.db.rollback()
        self.assertEqual(1,self.db.execute('PRAGMA user_version').fetchone()[0])
        self.assertFalse(list(self.db.execute("SELECT name FROM sqlite_master WHERE name='study_clock'")))
        self.assertEqual(8,self.db.execute('SELECT repetitions FROM learning_card').fetchone()[0])

    def test_candidate_rejects_other_language_and_duplicate_uid_or_position(self):
        self.db.executescript(V2)
        self.db.execute("INSERT INTO plan_review_candidate VALUES(1,'fr','fr:stable:verb:1',0)")
        for row in [(1,'es','es:ser:verb:1',1),(1,'fr','es:ser:verb:1',1),
                    (1,'fr','fr:stable:verb:1',1),(1,'fr','fr:other:verb:1',0)]:
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute('INSERT INTO plan_review_candidate VALUES(?,?,?,?)',row)

    def test_offered_state_is_boolean_and_clock_cannot_be_negative(self):
        self.db.executescript(V2)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('UPDATE daily_plan_item SET offered=2')
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('UPDATE study_clock SET max_observed_at=-1')

    def test_upgrade_keeps_daily_plan_unique_and_english_prohibited(self):
        self.db.executescript(V2)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO target_language_registry VALUES('en-us')")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("""INSERT INTO daily_plan(target_language,business_date,zone_id,created_at,due_cutoff_at,
                study_mode,target_snapshot,book_snapshot,content_version,policy_version,eligible_review_count,status)
                SELECT target_language,business_date,zone_id,created_at,due_cutoff_at,study_mode,target_snapshot,
                book_snapshot,content_version,policy_version,eligible_review_count,status FROM daily_plan""")


if __name__=='__main__':
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(UpgradeContracts))
    path=ROOT/'docs/qa/p3/schema-upgrade.json'
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps({'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'passed':result.wasSuccessful()},indent=2)+'\n',encoding='utf-8')
    raise SystemExit(0 if result.wasSuccessful() else 1)

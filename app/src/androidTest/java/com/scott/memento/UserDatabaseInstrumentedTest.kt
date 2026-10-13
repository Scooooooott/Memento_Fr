package com.scott.memento

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.memento.data.user.UserDatabase
import org.junit.Assert.*
import org.junit.Test

class UserDatabaseInstrumentedTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun database(block:(SQLiteDatabase)->Unit) {
        val name="memento-test-contracts.db"
        context.deleteDatabase(name)
        try { UserDatabase(context,name).use{block(it.writableDatabase)} }
        finally { context.deleteDatabase(name) }
    }
    @Test fun actualAndroidHelperCreatesUnifiedStoreAndFixedInitialZone() = database { db ->
        assertEquals(3,db.version)
        db.rawQuery("SELECT COUNT(*) FROM target_language_registry",null).use{it.moveToFirst();assertEquals(2,it.getInt(0))}
        db.rawQuery("SELECT onboarding_completed,study_zone_id FROM app_settings WHERE id=1",null).use{
            it.moveToFirst();assertEquals(0,it.getInt(0));assertTrue(it.getString(1).isNotBlank())
        }
        db.rawQuery("PRAGMA foreign_key_check",null).use{assertFalse(it.moveToFirst())}
    }
    @Test fun actualAndroidConstraintsRejectEnglishAndKeepReviewAllWithoutTarget() = database { db ->
        for(language in listOf("en","en-us","en_gb","eng-us")) {
            assertThrows(SQLiteConstraintException::class.java){db.execSQL("INSERT INTO target_language_registry VALUES(?)",arrayOf(language))}
        }
        db.execSQL("INSERT INTO language_settings(target_language,study_mode,daily_target,book_id) VALUES('fr','REVIEW_ALL',NULL,'book')")
        db.rawQuery("SELECT daily_target FROM language_settings WHERE target_language='fr'",null).use{it.moveToFirst();assertTrue(it.isNull(0))}
        assertThrows(SQLiteConstraintException::class.java){db.execSQL("INSERT INTO language_settings(target_language,study_mode,daily_target,book_id) VALUES('es','REVIEW_ALL',20,'book')")}
    }
    @Test fun actualAndroidForeignKeysRejectCrossLanguagePlans() = database { db ->
        db.execSQL("""INSERT INTO daily_plan(target_language,business_date,zone_id,created_at,due_cutoff_at,study_mode,
            target_snapshot,book_snapshot,content_version,policy_version,eligible_review_count,status)
            VALUES('fr','2026-10-09','Asia/Shanghai',1,2,'STANDARD',20,'book','fixture',1,0,'ACTIVE')""")
        assertThrows(SQLiteConstraintException::class.java){db.execSQL("""INSERT INTO daily_plan_item(plan_id,target_language,lexeme_uid,queue_kind,position,is_new)
            VALUES(1,'es','es:ser:verb:1','NORMAL',0,1)""")}
    }
    @Test fun versionOneUpgradesWithoutErasingCardsOrSettings() {
        val name="memento-test-upgrade.db"
        context.deleteDatabase(name)
        try {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null).use { db ->
                UserDatabase.installSchema(db,context.assets.open("user-v1.sql").bufferedReader().use{it.readText()})
                db.execSQL("INSERT INTO target_language_registry VALUES('fr')")
                db.execSQL("INSERT INTO app_settings(id,study_zone_id) VALUES(1,'Europe/Paris')")
                db.execSQL("INSERT INTO language_settings(target_language,study_mode,book_id) VALUES('fr','REVIEW_ALL','old-book')")
                db.execSQL("INSERT INTO learning_card VALUES('fr','fr:stable:verb:1',2.3,5,100,50,8,2,'REVIEW',1)")
                db.version=1
            }
            UserDatabase(context,name).use { helper ->
                val db=helper.writableDatabase
                assertEquals(3,db.version)
                db.rawQuery("SELECT study_zone_id FROM app_settings",null).use { it.moveToFirst();assertEquals("Europe/Paris",it.getString(0)) }
                db.rawQuery("SELECT repetitions,lapses FROM learning_card",null).use { it.moveToFirst();assertEquals(8,it.getInt(0));assertEquals(2,it.getInt(1)) }
                db.rawQuery("SELECT study_mode,daily_target FROM language_settings",null).use { it.moveToFirst();assertEquals("REVIEW_ALL",it.getString(0));assertTrue(it.isNull(1)) }
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun versionTwoUpgradesWithoutErasingPendingPlanOrFavorites() {
        val name="memento-test-upgrade-vtwo.db";context.deleteDatabase(name)
        try {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null).use{db->
                for(file in listOf("user-v1.sql","user-v2-upgrade.sql"))UserDatabase.installSchema(db,context.assets.open(file).bufferedReader().use{it.readText()})
                db.execSQL("INSERT INTO target_language_registry VALUES('fr')")
                db.execSQL("INSERT INTO app_settings(id,study_zone_id) VALUES(1,'Europe/Paris')")
                db.execSQL("INSERT INTO language_settings(target_language,study_mode,daily_target,book_id) VALUES('fr','STANDARD',3,'book')")
                db.execSQL("INSERT INTO favorites VALUES('fr','fr:stable:noun:1')")
                db.execSQL("""INSERT INTO daily_plan(target_language,business_date,zone_id,created_at,due_cutoff_at,study_mode,target_snapshot,book_snapshot,content_version,policy_version,eligible_review_count,status)
                    VALUES('fr','2026-10-10','Europe/Paris',1,2,'STANDARD',3,'book','fixture',1,0,'ACTIVE')""")
                db.execSQL("INSERT INTO daily_plan_item(plan_id,target_language,lexeme_uid,queue_kind,position,is_new,revealed) VALUES(1,'fr','fr:stable:noun:1','NORMAL',0,1,1)")
                db.version=2
            }
            UserDatabase(context,name).use{helper->val db=helper.writableDatabase;assertEquals(3,db.version)
                db.rawQuery("SELECT lexeme_uid,revealed FROM daily_plan_item",null).use{assertTrue(it.moveToFirst());assertEquals("fr:stable:noun:1",it.getString(0));assertEquals(1,it.getInt(1))}
                db.rawQuery("SELECT COUNT(*) FROM favorites",null).use{it.moveToFirst();assertEquals(1,it.getInt(0))}
                db.execSQL("INSERT INTO plan_refresh_request VALUES('fr',1)")
                db.rawQuery("PRAGMA foreign_key_check",null).use{assertFalse(it.moveToFirst())}
            }
        } finally {context.deleteDatabase(name)}
    }
}

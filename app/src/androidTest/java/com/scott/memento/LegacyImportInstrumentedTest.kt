package com.scott.memento

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.memento.data.migration.*
import com.scott.memento.data.user.*
import com.scott.memento.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class LegacyImportInstrumentedTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val clock=Clock.fixed(Instant.ofEpochMilli(LegacyFixture.NOW),ZoneOffset.UTC)
    private val name="memento-test-import.db"
    private fun index(target:String="fr")=StudyContentIndex(target,"zh-Hans","fixture",(listOf("a","b","c","d").map{"$target:$it:noun:1"}).toSet(),mapOf("book" to listOf("a","b","c","d").map{"$target:$it:noun:1"}))
    private fun input(target:String="fr",version:Int=2,limit:Int=10)=LegacyFixture.create(File(context.cacheDir,"memento-test-legacy-${java.util.UUID.randomUUID()}.db"),target,version,limit)
    private fun preview(file:File,target:String="fr")=LegacySource.preview(file,target,index(target).available)
    private fun options(goal:Int?=3)=LegacyImportOptions("主手机","Asia/Shanghai",LegacyPreferenceChoice(if(goal==null) StudyPolicy(StudyMode.REVIEW_ALL,null) else StudyPolicy(StudyMode.STANDARD,goal),"book"))
    private fun store(block:(UserDatabase)->Unit) {
        context.deleteDatabase(name)
        try {UserDatabase(context,name).use{store->store.writableDatabase.execSQL("UPDATE app_settings SET study_zone_id='Asia/Shanghai'");block(store)}}
        finally {context.deleteDatabase(name);context.cacheDir.listFiles()?.filter{it.name.startsWith("memento-test-legacy-")}?.forEach{it.delete()}}
    }
    private fun scalar(db:SQLiteDatabase,sql:String)=db.rawQuery(sql,null).use{it.moveToFirst();it.getInt(0)}
    @Test fun frenchVersionsAndSpanishPreserveAllFieldsAndSourceUnchanged()=store {store->
        for((target,version) in listOf("fr" to 1,"fr" to 2,"es" to 1)) {
            val file=input(target,version);val hash=LegacySource.sha(file)
            fun sidecars()=listOf("-journal","-wal","-shm").associateWith{suffix->File(file.path+suffix).let{if(it.isFile)LegacySource.sha(it) else null}}
            val beforeSidecars=sidecars()
            val expected=mutableListOf<Triple<String,String,JSONObject>>()
            LegacySource.open(file,target).use{it.records{type,id,row->expected+=Triple(type,id,row)}}
            val result=LegacyImporter(store,clock).import(preview(file,target),index(target),options().copy(sourceName="设备-$version-$target"))
            assertFalse(result.repeated);assertEquals(hash,LegacySource.sha(file))
            assertEquals(beforeSidecars,sidecars())
            val sourceIdentity="legacy:$target:设备-$version-$target"
            for((type,id,row) in expected) {
                store.readableDatabase.rawQuery("SELECT payload_json FROM legacy_record_history WHERE source_identity=? AND record_type=? AND source_record_id=?",arrayOf(sourceIdentity,type,id)).use{c->assertTrue(c.moveToFirst());assertEquals(row.toString(),c.getString(0))}
                if(type=="learning_card" || type=="review_log") {
                    val query=if(type=="learning_card") "SELECT * FROM learning_card WHERE target_language=? AND lexeme_uid=?" else "SELECT * FROM review_log WHERE source_identity=? AND source_record_id=?"
                    store.readableDatabase.rawQuery(query,if(type=="learning_card") arrayOf(target,id) else arrayOf(sourceIdentity,id)).use{c->
                        assertTrue(c.moveToFirst())
                        row.keys().forEach{key->if(key !in setOf("id","session_id","position")) {
                            val column=if(key=="review_day") "legacy_review_day" else key
                            val position=c.getColumnIndexOrThrow(column)
                            when(val value=row.get(key)){JSONObject.NULL->assertTrue(c.isNull(position));is Double->assertEquals(value,c.getDouble(position),0.0);is Long->assertEquals(value,c.getLong(position));else->assertEquals(value,c.getString(position))}
                        }}
                    }
                }
            }
            val evidence=File(context.getExternalFilesDir(null),"qa").apply{mkdirs()}
            File(evidence,"p6-$target-v$version-import.json").writeText(result.report.toString(2))
            store.readableDatabase.rawQuery("SELECT * FROM learning_card WHERE target_language=? AND lexeme_uid=?",arrayOf(target,"$target:a:noun:1")).use{c->
                assertTrue(c.moveToFirst())
                fun double(key:String)=c.getDouble(c.getColumnIndexOrThrow(key))
                fun long(key:String)=c.getLong(c.getColumnIndexOrThrow(key))
                assertEquals(3.123456789,double("stability"),0.0);assertEquals(5.987654321,double("difficulty"),0.0)
                assertEquals(LegacyFixture.NOW+86400000,long("due_at"));assertEquals(LegacyFixture.NOW,long("last_review_at"));assertEquals(7L,long("repetitions"));assertEquals(2L,long("lapses"))
            }
        }
        val db=store.readableDatabase
        assertEquals(3,scalar(db,"SELECT COUNT(*) FROM review_log WHERE source_record_id='1'"))
        assertEquals(2,scalar(db,"SELECT COUNT(*) FROM favorites"))
        assertEquals(3,scalar(db,"SELECT COUNT(*) FROM legacy_session_history"))
        db.rawQuery("SELECT payload_json FROM legacy_session_history",null).use{while(it.moveToNext()){val json=JSONObject(it.getString(0));assertTrue(json.isNull("completed_at"));assertEquals(1,json.getInt("position"));assertEquals(2,json.getJSONArray("items").length())}}
        assertEquals(3,scalar(db,"SELECT COUNT(*) FROM review_log WHERE legacy_review_day='2026-10-10' AND reviewed_at=${LegacyFixture.NOW} AND scheduler_version='fsrs-old-fixture'"))
        db.rawQuery("PRAGMA foreign_key_check",null).use{assertFalse(it.moveToFirst())}
    }
    @Test fun repeatedRenamedAndVacuumedSnapshotsDoNotReissueQuotaOrFavorites()=store {store->
        val file=input();val importer=LegacyImporter(store,clock);val first=importer.import(preview(file),index(),options())
        val study=StudyRepository(store,clock);val initial=study.ensureDailyPlan(index())
        assertEquals(1,initial.stats.uniqueWords);assertEquals(2,initial.normalRemaining);assertEquals("fr:b:noun:1",initial.next!!.uid)
        store.writableDatabase.execSQL("DELETE FROM favorites")
        val copy=File(context.cacheDir,"memento-test-legacy-renamed.db");file.copyTo(copy)
        SQLiteDatabase.openDatabase(copy.path,null,SQLiteDatabase.OPEN_READWRITE).use{it.execSQL("VACUUM");it.execSQL("PRAGMA user_version=2")}
        val repeated=importer.import(preview(copy),index(),options(9))
        assertTrue(repeated.repeated);assertEquals(first.batchId,repeated.batchId);assertEquals(initial,study.ensureDailyPlan(index()))
        assertEquals(0,scalar(store.readableDatabase,"SELECT COUNT(*) FROM favorites"));assertEquals(1,scalar(store.readableDatabase,"SELECT COUNT(*) FROM migration_batch"))
    }
    @Test fun incrementalUpdatesAndNativeConflictArchiveBothStatesWithoutDuplicateLogs()=store {store->
        val file=input();val importer=LegacyImporter(store,clock);importer.import(preview(file),index(),options())
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use{it.execSQL("UPDATE learning_card SET stability=4.25,repetitions=8 WHERE lexeme_uid='fr:a:noun:1'")}
        val updated=importer.import(preview(file),index(),options())
        assertEquals(1,updated.report.getJSONObject("totals").getInt("updated"))
        store.writableDatabase.execSQL("UPDATE learning_card SET stability=9,repetitions=9 WHERE lexeme_uid='fr:a:noun:1'")
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use{it.execSQL("UPDATE learning_card SET stability=6,repetitions=10 WHERE lexeme_uid='fr:a:noun:1'")}
        val conflict=importer.import(preview(file),index(),options())
        assertEquals(1,conflict.report.getJSONObject("totals").getInt("conflicts"))
        assertEquals(9,scalar(store.readableDatabase,"SELECT stability FROM learning_card WHERE lexeme_uid='fr:a:noun:1'"))
        assertEquals(3,scalar(store.readableDatabase,"SELECT COUNT(*) FROM legacy_record_history WHERE record_type='learning_card' AND source_record_id='fr:a:noun:1'"))
        assertEquals(1,scalar(store.readableDatabase,"SELECT COUNT(*) FROM review_log"))
    }
    @Test fun zeroLimitRestoresAllReviewsAndMissingContentRemainsArchived()=store {store->
        val file=input(limit=0)
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use{it.execSQL("INSERT INTO learning_card VALUES('fr:removed:noun:1',1,5,1,2,3,1,'REVIEW')");it.execSQL("UPDATE learning_card SET due_at=1 WHERE lexeme_uid='fr:a:noun:1'")}
        val imported=preview(file);assertEquals(1,imported.unavailable)
        LegacyImporter(store,clock).import(imported,index(),options(null))
        val snapshot=StudyRepository(store,clock).ensureDailyPlan(index())
        assertEquals(StudyMode.REVIEW_ALL,snapshot.policy!!.mode);assertNull(snapshot.policy.dailyTarget);assertEquals(0,snapshot.normalRemaining) // already rated today
        assertEquals(1,scalar(store.readableDatabase,"SELECT COUNT(*) FROM learning_card WHERE content_available=0"))
    }
    @Test fun importRevisesExistingPlanAndRejectsOldRatingRequest()=store {store->
        val study=StudyRepository(store,clock);val old=study.configure(index(),StudyPolicy(StudyMode.STANDARD,3),"book")
        val request=old.request("fr","before-import")
        val file=input();LegacyImporter(store,clock).import(preview(file),index(),LegacyImportOptions("主手机","Asia/Shanghai"))
        val next=study.ensureDailyPlan(index());assertEquals(old.planId,next.planId);assertTrue(next.revision>old.revision);assertEquals(2,next.normalRemaining)
        assertEquals(ScoreResult.STALE,study.rate(index(),request,Rating.GOOD).result)
    }
    @Test fun injectedSqlFailureRollsBackCardsLogsSettingsArchiveAndPlan()=store {store->
        val db=store.writableDatabase
        db.execSQL("CREATE TRIGGER fail_import BEFORE INSERT ON review_log BEGIN SELECT RAISE(ABORT,'injected import failure'); END")
        val file=input()
        assertThrows(Exception::class.java){LegacyImporter(store,clock).import(preview(file),index(),options())}
        for(table in listOf("learning_card","favorites","review_log","language_settings","migration_batch","migration_ledger","legacy_record_history","legacy_session_history","migration_snapshot","daily_plan","plan_refresh_request"))assertEquals(table,0,scalar(db,"SELECT COUNT(*) FROM $table"))
        db.execSQL("DROP TRIGGER fail_import")
        assertFalse(LegacyImporter(store,clock).import(preview(file),index(),options()).repeated)
    }
    @Test fun damagedWrongTargetOrChangedAfterPreviewRejectBeforeWriting()=store {store->
        val file=input();val preview=preview(file)
        assertThrows(Exception::class.java){LegacySource.preview(file,"es",index("es").available)}
        assertThrows(Exception::class.java){LegacySource.preview(file,"en-US",emptySet())}
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use{it.execSQL("UPDATE learning_card SET stability=10")}
        assertThrows(Exception::class.java){LegacyImporter(store,clock).import(preview,index(),options())}
        assertEquals(0,scalar(store.readableDatabase,"SELECT COUNT(*) FROM migration_batch"))
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use{it.execSQL("UPDATE review_log SET rating='HARD'")}
        assertThrows(Exception::class.java){preview(file)}
        val corrupt=File(context.cacheDir,"memento-test-legacy-corrupt.db").apply{writeText("not sqlite".repeat(40))}
        assertThrows(Exception::class.java){preview(corrupt)}
    }
    @Test fun sameDayAndCrossDaySessionsUseLogDateRatherThanSessionStart()=store {store->
        val file=input()
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use{db->
            db.execSQL("UPDATE study_session SET started_at=?",arrayOf(LegacyFixture.NOW-3*86400000))
            db.execSQL("INSERT INTO study_session VALUES(2,1,0,?,?, '{}')",arrayOf(LegacyFixture.NOW,LegacyFixture.NOW))
            db.execSQL("INSERT INTO study_session_item VALUES(2,0,'fr:c:noun:1',1,'GOOD')")
            LegacyFixture.log(db,2,2,"fr:c:noun:1",LegacyFixture.NOW,"2026-10-10")
        }
        LegacyImporter(store,clock).import(preview(file),index(),options(1))
        val snapshot=StudyRepository(store,clock).ensureDailyPlan(index())
        assertEquals(2,snapshot.stats.uniqueWords);assertEquals(0,snapshot.normalRemaining);assertEquals(2,scalar(store.readableDatabase,"SELECT COUNT(*) FROM legacy_session_history"));assertEquals(1,scalar(store.readableDatabase,"SELECT COUNT(*) FROM daily_plan"))
    }
    @Test fun missingWalAndNamespaceOrPreferenceErrorsHaveNoDestinationWrites()=store {store->
        val file=input();val wal=File(file.path+"-wal").apply{writeText("active WAL")}
        assertThrows(Exception::class.java){preview(file)};wal.delete()
        val preview=preview(file);val importer=LegacyImporter(store,clock)
        for(options in listOf(options().copy(sourceName=""),options().copy(oldZone="invalid"),options().copy(preferences=LegacyPreferenceChoice(StudyPolicy(StudyMode.STANDARD,3),"unknown"))))
            assertThrows(Exception::class.java){importer.import(preview,index(),options)}
        assertEquals(0,scalar(store.readableDatabase,"SELECT COUNT(*) FROM migration_batch"))
    }
    @Test fun incrementalLogsAndChangedOldLogKeepStableMappings()=store {store->
        val file=input();val importer=LegacyImporter(store,clock);importer.import(preview(file),index(),options())
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use{db->
            db.execSQL("INSERT INTO study_session VALUES(2,1,0,?,?, '{}')",arrayOf(LegacyFixture.NOW,LegacyFixture.NOW))
            db.execSQL("INSERT INTO study_session_item VALUES(2,0,'fr:c:noun:1',1,'GOOD')")
            LegacyFixture.log(db,2,2,"fr:c:noun:1",LegacyFixture.NOW,"2026-10-10")
            db.execSQL("INSERT INTO learning_card VALUES('fr:c:noun:1',3,5,?,?,1,0,'REVIEW')",arrayOf(LegacyFixture.NOW+86400000,LegacyFixture.NOW))
            db.execSQL("UPDATE review_log SET previous_stability=1 WHERE id=1")
        }
        val result=importer.import(preview(file),index(),options())
        assertEquals(1,result.report.getJSONObject("totals").getInt("conflicts"))
        assertEquals(2,scalar(store.readableDatabase,"SELECT COUNT(*) FROM review_log"));assertEquals(2,scalar(store.readableDatabase,"SELECT COUNT(*) FROM legacy_record_history WHERE record_type='review_log' AND source_record_id='1'"))
        assertEquals(1,StudyRepository(store,clock).ensureDailyPlan(index()).normalRemaining)
        store.readableDatabase.rawQuery("SELECT previous_stability FROM review_log WHERE source_record_id='1'",null).use{it.moveToFirst();assertEquals(0.2,it.getDouble(0),0.0)}
    }
    @Test fun midnightImportWaitsForChoiceThenCountsOldDayThroughTwoAm()=store {store->
        val original=StudyRepository(store,clock).configure(index(),StudyPolicy(StudyMode.STANDARD,3),"book")
        val midnight=Clock.fixed(Instant.parse("2026-10-10T16:30:00Z"),ZoneOffset.UTC)
        val file=input();val result=LegacyImporter(store,midnight).import(preview(file),index(),LegacyImportOptions("主手机","Asia/Shanghai"))
        assertEquals("MIDNIGHT_CHOICE",result.report.getString("planGuard"));assertEquals(1,scalar(store.readableDatabase,"SELECT COUNT(*) FROM plan_refresh_request"))
        val study=StudyRepository(store,midnight);val deferred=study.chooseRollover(index(),original.planId!!,RolloverChoice.DEFER)
        assertEquals("2026-10-10",deferred.businessDate);assertEquals(2,deferred.normalRemaining);assertEquals(0,scalar(store.readableDatabase,"SELECT COUNT(*) FROM plan_refresh_request"))
        val two=StudyRepository(store,Clock.fixed(Instant.parse("2026-10-10T18:00:00Z"),ZoneOffset.UTC)).ensureDailyPlan(index())
        assertEquals("2026-10-11",two.businessDate);assertEquals(0,two.stats.uniqueWords)
    }
    @Test fun lateFailureRollsBackPreferencesAndArchiveAsWell()=store {store->
        val db=store.writableDatabase
        db.execSQL("CREATE TRIGGER fail_plan BEFORE INSERT ON plan_revision BEGIN SELECT RAISE(ABORT,'late failure'); END")
        val file=input();assertThrows(Exception::class.java){LegacyImporter(store,clock).import(preview(file),index(),options())}
        for(table in listOf("learning_card","favorites","review_log","language_settings","legacy_session_history","legacy_record_history","migration_ledger","migration_batch","daily_plan","plan_review_candidate","plan_refresh_request"))assertEquals(table,0,scalar(db,"SELECT COUNT(*) FROM $table"))
        assertEquals(0,scalar(db,"SELECT max_observed_at FROM study_clock"))
    }
    @Test fun futureOldClockRetainsDataAndBlocksScoringUntilCorrected()=store {store->
        val file=input();SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use{it.execSQL("UPDATE learning_card SET last_review_at=? WHERE lexeme_uid='fr:a:noun:1'",arrayOf(LegacyFixture.NOW+86400000))}
        val result=LegacyImporter(store,clock).import(preview(file),index(),options())
        assertEquals("CLOCK_ROLLBACK",result.report.getString("planGuard"));assertEquals(2,scalar(store.readableDatabase,"SELECT COUNT(*) FROM learning_card"));assertEquals(1,scalar(store.readableDatabase,"SELECT COUNT(*) FROM plan_refresh_request"))
    }
    @Test fun zeroModeAndContentDatabaseAreRejectedAtPublicBoundary()=store {store->
        val zero=input(limit=0);val importer=LegacyImporter(store,clock)
        val failure=assertThrows(LegacyImportFailure::class.java){importer.import(preview(zero),index(),LegacyImportOptions("主手机","Asia/Shanghai"))}
        assertTrue(failure.report.getBoolean("rolledBack"));assertEquals("validation",failure.report.getString("phase"))
        val content=File(context.cacheDir,"memento-test-legacy-content.db")
        SQLiteDatabase.openOrCreateDatabase(content,null).use{db->db.execSQL("CREATE TABLE lexeme(uid TEXT PRIMARY KEY)");db.version=2}
        assertThrows(Exception::class.java){preview(content)}
        val unknown=input();SQLiteDatabase.openDatabase(unknown.path,null,SQLiteDatabase.OPEN_READWRITE).use{it.execSQL("CREATE TABLE unexpected(payload TEXT)")}
        assertThrows(Exception::class.java){preview(unknown)}
        assertEquals(0,scalar(store.readableDatabase,"SELECT COUNT(*) FROM migration_batch"))
    }
}

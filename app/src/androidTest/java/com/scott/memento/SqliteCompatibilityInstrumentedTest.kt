package com.scott.memento

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Build
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
import java.time.ZoneOffset
import java.util.UUID

/** These exercise the production write paths; API 35 execution alone is not API 26 certification. */
class SqliteCompatibilityInstrumentedTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val database="memento-test-ui7-sqlite.db"
    private val inputs=mutableListOf<File>()
    private val clock=Clock.fixed(Instant.ofEpochMilli(LegacyFixture.NOW),ZoneOffset.UTC)
    private fun index()=StudyContentIndex("fr","zh-Hans","sqlite-fixture",
        listOf("a","b","c","d","e","f").map{"fr:$it:noun:1"}.toSet(),
        mapOf("book" to listOf("a","b","c","d","e","f").map{"fr:$it:noun:1"}))
    private fun property(key:String)=ProcessBuilder("/system/bin/getprop",key).start().let {process->
        process.inputStream.bufferedReader().use {it.readText().trim()}.also {check(process.waitFor()==0)}
    }
    private fun guard() {
        check(context.packageName=="com.scott.memento")
        check(Build.HARDWARE in setOf("ranchu","goldfish") || Build.FINGERPRINT.contains("generic"))
        check(property("ro.kernel.qemu")=="1")
        check(listOf("ro.boot.qemu.avd_name","ro.kernel.qemu.avd_name").any {property(it)=="MementoApi35"})
        check(database=="memento-test-ui7-sqlite.db")
    }
    private fun store(block:(UserDatabase)->Unit) {
        guard();context.deleteDatabase(database)
        try {UserDatabase(context,database).use {store->
            store.writableDatabase.execSQL("UPDATE app_settings SET study_zone_id='Asia/Shanghai'")
            block(store)
            store.readableDatabase.rawQuery("PRAGMA foreign_key_check",null).use {assertFalse(it.moveToFirst())}
        }} finally {
            context.deleteDatabase(database)
            inputs.forEach {file->listOf("","-journal","-wal","-shm").forEach {suffix->File(file.path+suffix).delete()}}
            inputs.clear()
        }
    }
    private fun input()=File(context.cacheDir,"memento-test-ui7-legacy-${UUID.randomUUID()}.db")
        .also {inputs+=it;LegacyFixture.create(it)}
    private fun options(goal:Int=3)=LegacyImportOptions("兼容夹具","Asia/Shanghai",
        LegacyPreferenceChoice(StudyPolicy(StudyMode.STANDARD,goal),"book"))
    private fun preview(file:File)=LegacySource.preview(file,"fr",index().available)
    private fun <T> rows(db:SQLiteDatabase,sql:String,args:Array<String> = emptyArray(),map:Cursor.()->T):List<T> =
        db.rawQuery(sql,args).use {cursor->buildList {while(cursor.moveToNext())add(cursor.map())}}
    private fun number(db:SQLiteDatabase,sql:String)=rows(db,sql){getLong(0)}.single()
    private fun persisted(db:SQLiteDatabase)=rows(db,"SELECT name FROM sqlite_master WHERE type='table' ORDER BY name") {getString(0)}
        .associateWith {table->rows(db,"SELECT rowid,* FROM $table ORDER BY rowid") {
            (0 until columnCount).map {column->if(isNull(column)) null else "${getType(column)}:${getString(column)}"}
        }}

    @Test fun updateInsertOutsideTransactionRejectsBothMissingAndExistingRowsWithoutWrites()=store {store->
        val db=store.writableDatabase
        fun attempt()=SqliteWrites.updateOrInsert(db,"language_settings",ContentValues().apply {
            put("target_language","fr");put("study_mode","STANDARD");put("daily_target",9);put("book_id","book")
        },"UPDATE language_settings SET daily_target=?,revision=revision+1 WHERE target_language=?",arrayOf(9,"fr"))
        var before=persisted(db)
        assertThrows(IllegalStateException::class.java) {attempt()}
        assertEquals(before,persisted(db))
        StudyRepository(store,clock).configure(index(),StudyPolicy(StudyMode.STANDARD,3),"book")
        before=persisted(db)
        assertThrows(IllegalStateException::class.java) {attempt()}
        assertEquals(before,persisted(db))
    }

    @Test fun configurationPreservesRowIdentityPlaybackAndRevisionWithoutReissuingQuota()=store {store->
        val db=store.writableDatabase
        val study=StudyRepository(store,clock)
        val app=AppRepository(store,study,clock)
        val playback=PlaybackPreferences(AutoPlay.NEVER,false,false)
        var state=app.save(index(),StudyPolicy(StudyMode.STANDARD,3),"book","Asia/Shanghai",playback)
        val rowId=number(db,"SELECT rowid FROM language_settings WHERE target_language='fr'")
        assertEquals(1L,number(db,"SELECT revision FROM language_settings WHERE target_language='fr'"))
        val request=state.request("fr","sqlite-first-rating")
        state=study.reveal(index(),request).snapshot
        state=study.rate(index(),state.request("fr",request.requestId),Rating.GOOD).snapshot
        val planId=state.planId
        state=study.configure(index(),StudyPolicy(StudyMode.STANDARD,5),"book")
        assertEquals(planId,state.planId);assertEquals(1,state.stats.uniqueWords);assertEquals(4,state.normalRemaining)
        assertEquals(rowId,number(db,"SELECT rowid FROM language_settings WHERE target_language='fr'"))
        assertEquals(2L,number(db,"SELECT revision FROM language_settings WHERE target_language='fr'"))
        assertEquals(playback,app.language("fr")!!.playback)
        val before=persisted(db)
        assertEquals(state,study.configure(index(),StudyPolicy(StudyMode.STANDARD,5),"book"))
        assertEquals(before,persisted(db))
        state=study.configure(index(),StudyPolicy(StudyMode.REVIEW_ALL,null),"book")
        assertEquals(0,state.normalRemaining);assertNull(app.language("fr")!!.policy.dailyTarget)
        assertEquals(3L,number(db,"SELECT revision FROM language_settings WHERE target_language='fr'"))
        state=study.configure(index(),StudyPolicy(StudyMode.STANDARD,5),"book")
        assertEquals(1,state.stats.uniqueWords);assertEquals(4,state.normalRemaining)
        assertEquals(rowId,number(db,"SELECT rowid FROM language_settings WHERE target_language='fr'"))
        assertEquals(4L,number(db,"SELECT revision FROM language_settings WHERE target_language='fr'"))
        assertEquals(playback,app.language("fr")!!.playback)
        assertEquals(1L,number(db,"SELECT COUNT(*) FROM review_log"))
        assertEquals(1L,number(db,"SELECT COUNT(*) FROM daily_plan"))
    }

    @Test fun incrementalLegacySessionAndMappingsUpdateInPlaceAndKeepStableLogIds()=store {store->
        val db=store.writableDatabase
        val file=input()
        val first=LegacyImporter(store,clock).import(preview(file),index(),options())
        fun mappingIds()=rows(db,"SELECT record_type,source_record_id,rowid,target_record_id FROM migration_ledger ORDER BY record_type,source_record_id") {
            (getString(0) to getString(1)) to (getLong(2) to getString(3))
        }.toMap()
        val mappings=mappingIds()
        val sessionRow=number(db,"SELECT rowid FROM legacy_session_history")
        val settingsRow=number(db,"SELECT rowid FROM language_settings")
        val firstLog=number(db,"SELECT id FROM review_log WHERE source_record_id='1'")
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use {source->
            source.execSQL("UPDATE study_session SET position=2,revealed=0,completed_at=? WHERE id=1",arrayOf(LegacyFixture.NOW+1000))
            source.execSQL("UPDATE study_session_item SET rating='GOOD' WHERE session_id=1 AND position=1")
            source.execSQL("""INSERT INTO review_log SELECT 2,1,1,'fr:b:noun:1',rating,is_new,?,review_day,
                previous_stability,previous_difficulty,next_stability,next_difficulty,?,elapsed_days,scheduler_version,desired_retention
                FROM review_log WHERE id=1""",arrayOf(LegacyFixture.NOW+1000,LegacyFixture.NOW+86401000))
            source.execSQL("UPDATE learning_card SET stability=3,difficulty=5,due_at=?,last_review_at=?,repetitions=1,state='REVIEW' WHERE lexeme_uid='fr:b:noun:1'",
                arrayOf(LegacyFixture.NOW+86401000,LegacyFixture.NOW+1000))
        }
        val later=Clock.fixed(Instant.ofEpochMilli(LegacyFixture.NOW+2000),ZoneOffset.UTC)
        val second=LegacyImporter(store,later).import(preview(file),index(),options(4))
        assertFalse(second.repeated);assertTrue(second.batchId>first.batchId)
        assertEquals(sessionRow,number(db,"SELECT rowid FROM legacy_session_history"))
        val updatedMappings=mappingIds()
        mappings.forEach {(key,identity)->assertEquals("Stable mapping for $key",identity,updatedMappings[key])}
        rows(db,"SELECT payload_json,batch_id FROM legacy_session_history") {
            val payload=JSONObject(getString(0))
            assertEquals(2,payload.getInt("position"));assertEquals(LegacyFixture.NOW+1000,payload.getLong("completed_at"))
            assertEquals(second.batchId,getLong(1))
        }
        assertEquals(settingsRow,number(db,"SELECT rowid FROM language_settings"))
        assertEquals(2L,number(db,"SELECT revision FROM language_settings"))
        assertEquals(4L,number(db,"SELECT daily_target FROM language_settings"))
        assertEquals(firstLog,number(db,"SELECT id FROM review_log WHERE source_record_id='1'"))
        assertEquals(2L,number(db,"SELECT COUNT(*) FROM review_log"))
        assertEquals(2L,number(db,"SELECT COUNT(*) FROM legacy_record_history WHERE record_type='study_session'"))
        assertEquals(2,StudyRepository(store,later).ensureDailyPlan(index()).normalRemaining)
    }

    @Test fun failedSettingsUpdateRollsBackTheEntireIncrementalImportAndRetryRemainsIdempotent()=store {store->
        val db=store.writableDatabase
        val file=input()
        val importer=LegacyImporter(store,clock)
        importer.import(preview(file),index(),options())
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use {source->
            source.execSQL("UPDATE learning_card SET stability=5,repetitions=8 WHERE lexeme_uid='fr:a:noun:1'")
            source.execSQL("UPDATE settings SET daily_new_limit=11,auto_play='ALL'")
        }
        db.execSQL("""CREATE TEMP TRIGGER fail_ui7_settings_update BEFORE UPDATE OF study_mode ON language_settings
            BEGIN SELECT RAISE(ABORT,'fixture update failure'); END""")
        val before=persisted(db)
        val failure=assertThrows(LegacyImportFailure::class.java) {importer.import(preview(file),index(),options(4))}
        assertTrue(failure.cause is SQLiteException)
        assertTrue(failure.report.getBoolean("rolledBack"))
        assertEquals("preferences_and_plan",failure.report.getString("phase"))
        assertEquals(before,persisted(db))
        db.execSQL("DROP TRIGGER fail_ui7_settings_update")
        val successful=importer.import(preview(file),index(),options(4))
        assertFalse(successful.repeated)
        assertEquals(2L,number(db,"SELECT COUNT(*) FROM migration_batch"))
        assertEquals(2L,number(db,"SELECT revision FROM language_settings"))
        assertEquals(AutoPlay.ALL,AppRepository(store,StudyRepository(store,clock),clock).language("fr")!!.playback.autoPlay)
        assertEquals(5L,number(db,"SELECT stability FROM learning_card WHERE lexeme_uid='fr:a:noun:1'"))
        val after=persisted(db)
        assertTrue(importer.import(preview(file),index(),options(6)).repeated)
        assertEquals(after,persisted(db))
    }
}

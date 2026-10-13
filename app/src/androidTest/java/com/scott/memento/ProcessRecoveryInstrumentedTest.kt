package com.scott.memento

import android.content.Intent
import android.database.Cursor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.memento.data.content.ContentCatalog
import com.scott.memento.data.user.*
import com.scott.memento.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.time.*

/** Two separate runner invocations, with a host-verified force-stop and cold launch between them. */
class ProcessRecoveryInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private val name="memento-test-process-recovery.db"
    private val output get()=File(context.getExternalFilesDir(null),"qa").apply{mkdirs()}
    private class FixtureClock:Clock() {
        var value=Instant.parse("2026-10-10T15:30:00Z")
        override fun instant()=value
        override fun getZone()=ZoneOffset.UTC
        override fun withZone(zone:ZoneId):Clock=Clock.fixed(value,zone)
    }
    private fun await(tag:String)=compose.waitUntil(30000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
    private fun click(tag:String){await(tag);compose.waitUntil(30000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any{!it.config.contains(SemanticsProperties.Disabled)}};compose.onNodeWithTag(tag).performClick()}
    private fun launch(epoch:Long)=ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)
        .putExtra("qa_user_database",name).putExtra("qa_clock_millis",epoch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun snapshot(store:UserDatabase):JSONObject {
        val result=JSONObject()
        val db=store.readableDatabase
        db.beginTransaction()
        try {
            val tables=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name<>'android_metadata' ORDER BY name",null).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
            for(table in tables)db.rawQuery("SELECT * FROM $table",null).use {cursor->
                val rows=mutableListOf<String>()
                while(cursor.moveToNext()) {
                    val row=JSONArray()
                    for(i in 0 until cursor.columnCount) row.put(when(cursor.getType(i)) {
                        Cursor.FIELD_TYPE_NULL->JSONObject.NULL
                        Cursor.FIELD_TYPE_INTEGER->cursor.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT->cursor.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB->android.util.Base64.encodeToString(cursor.getBlob(i),android.util.Base64.NO_WRAP)
                        else->cursor.getString(i)
                    })
                    rows.add(row.toString())
                }
                result.put(table,JSONObject().put("columns",JSONArray(cursor.columnNames.toList())).put("rows",JSONArray(rows.sorted())))
            }
        } finally {db.endTransaction()}
        return result
    }
    private fun seed(case:String,clock:FixtureClock) {
        context.deleteDatabase(name)
        UserDatabase(context,name).use {store->
            val study=StudyRepository(store,clock);val app=AppRepository(store,study,clock)
            for(target in listOf("fr","es"))ContentCatalog(context,Languages.get(target)).use{catalog->
                val index=catalog.studyIndex("zh-Hans")
                if(case=="extra" && target=="fr")index.books.getValue(catalog.language.defaultBook).take(3).forEach {uid->
                    store.writableDatabase.execSQL("INSERT INTO learning_card VALUES(?, ?,2,5,?,?,1,0,'REVIEW',1)",arrayOf(target,uid,clock.millis()-1000,clock.millis()-86400000))
                }
                var state=app.save(index,StudyPolicy(StudyMode.STANDARD,if(case=="completed" || case=="extra")1 else 3),catalog.language.defaultBook,"Asia/Shanghai",PlaybackPreferences(AutoPlay.NEVER,false))
                val request=state.request(target,"p8-$case-$target")
                assertEquals(ScoreResult.APPLIED,study.reveal(index,request).result)
                state=study.rate(index,request,Rating.GOOD).snapshot
                app.toggleFavorite(target,request.identity.lexemeUid)
                if(case=="extra" && target=="fr")state=study.chooseExtra(index,checkNotNull(state.planId),true)
                if(state.next!=null)assertEquals(ScoreResult.APPLIED,study.reveal(index,state.request(target,"p8-reveal-$target")).result)
            }
            app.select(Selection("fr","en"))
            if(case=="grace") {
                clock.value=Instant.parse("2026-10-10T16:30:00Z")
                ContentCatalog(context,Languages.get("fr")).use {catalog->
                    val index=catalog.studyIndex("en");val state=study.ensureDailyPlan(index)
                    assertEquals(StudyGuard.MIDNIGHT_CHOICE,state.guard)
                    assertEquals(StudyGuard.READY,study.chooseRollover(index,checkNotNull(state.planId),RolloverChoice.DEFER).guard)
                }
            }
        }
    }
    @Test fun processRecoveryPhase() {
        val arguments=InstrumentationRegistry.getArguments()
        val phase=checkNotNull(arguments.getString("qa_process_phase")){"Run through tools/verify-process-recovery.ps1"}
        val case=checkNotNull(arguments.getString("qa_process_case"))
        require(phase in listOf("prepare","verify") && case in listOf("pending","completed","extra","grace"))
        val clock=FixtureClock()
        if(phase=="prepare")seed(case,clock)
        if(case=="grace")clock.value=Instant.parse("2026-10-10T16:30:00Z")
        val expectedFile=File(output,"p8-process-$case-expected.json")
        try {launch(clock.millis()).use {
            await("open_study");compose.onNodeWithTag("language_context").assertTextEquals(context.getString(R.string.language_context_format,context.getString(R.string.french),context.getString(R.string.english)))
            UserDatabase(context,name).use {store->
                val actual=snapshot(store)
                if(phase=="prepare") {
                    expectedFile.writeText(JSONObject().put("epoch",clock.millis()).put("database",name).put("case",case).put("tables",actual).toString(2))
                } else {
                    val expected=JSONObject(expectedFile.readText()).getJSONObject("tables")
                    assertEquals(expected.toString(),actual.toString())
                    val study=StudyRepository(store,clock);val app=AppRepository(store,study,clock)
                    for(target in listOf("fr","es"))ContentCatalog(context,Languages.get(target)).use{catalog->
                        val state=study.ensureDailyPlan(catalog.studyIndex(if(target=="fr")"en" else "zh-Hans"))
                        assertEquals(1,app.favorites(target).size)
                        assertEquals(1,state.stats.uniqueWords)
                        if(case=="completed" || (case=="extra" && target=="es")) {
                            assertNull(state.next);assertEquals(0,state.normalRemaining)
                        } else if(!(case=="grace" && target=="es")) {
                            assertTrue(checkNotNull(state.next).revealed)
                            if(case=="extra") {assertEquals("EXTRA",state.next!!.kind);assertEquals(2,state.extraRemaining)}
                        }
                    }
                }
            }
            if(phase=="verify") {
                if(case=="completed") compose.onNodeWithTag("open_study").assertIsNotEnabled()
                else {click("open_study");await("rating_GOOD")}
                compose.waitForIdle()
                val bitmap=instrument.uiAutomation.takeScreenshot()
                FileOutputStream(File(output,"p8-process-$case-restored.png")).use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                if(case=="grace") {
                    // A restored choice must still attribute a 00:30 rating to the previous date.
                    click("rating_GOOD");await("study_reveal")
                    UserDatabase(context,name).use{store->store.readableDatabase.rawQuery("SELECT business_date,reviewed_at FROM review_log WHERE target_language='fr' ORDER BY id DESC LIMIT 1",null).use{cursor->
                        assertTrue(cursor.moveToFirst());assertEquals("2026-10-10",cursor.getString(0));assertEquals(clock.millis(),cursor.getLong(1))
                    }}
                }
                File(output,"p8-process-$case-verified.json").writeText(JSONObject().put("passed",true).put("case",case).put("all_user_tables_equal_before_further_actions",true).toString(2))
            }
        }} finally {if(phase=="verify")context.deleteDatabase(name)}
    }
}

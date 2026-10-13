package com.scott.memento

import android.content.Intent
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.memento.data.content.ContentCatalog
import com.scott.memento.data.user.AppRepository
import com.scott.memento.data.user.StudyRepository
import com.scott.memento.data.user.UserDatabase
import com.scott.memento.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** UI6 uses MainActivity, formal packages and guarded test data. These are not component mockups. */
class StatisticsLayoutInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private val database="memento-test-ui6-statistics-layout.db"
    private val clock=Clock.fixed(Instant.parse("2026-10-10T04:00:00Z"),ZoneOffset.UTC)
    private var windowSize=7
    private fun property(key:String)=ProcessBuilder("/system/bin/getprop",key).start().let {process->
        process.inputStream.bufferedReader().use {it.readText().trim()}.also {check(process.waitFor()==0)}
    }
    private fun guard() {
        assertEquals("com.scott.memento",context.packageName)
        check(Build.HARDWARE in setOf("ranchu","goldfish") || Build.FINGERPRINT.contains("generic"))
        check(property("ro.kernel.qemu")=="1")
        check(listOf("ro.boot.qemu.avd_name","ro.kernel.qemu.avd_name").any {property(it)=="MementoApi35"})
        check(database.startsWith("memento-test-") && database.endsWith(".db"))
    }
    private fun legacy(store:UserDatabase,target:String,uid:String,record:String,date:String,isNew:Boolean=false) {
        store.writableDatabase.execSQL("""INSERT INTO review_log(target_language,lexeme_uid,kind,rating,is_new,
            reviewed_at,business_date,zone_id,previous_stability,previous_difficulty,next_stability,next_difficulty,
            next_due_at,elapsed_days,scheduler_version,desired_retention,source_identity,source_record_id)
            VALUES(?,?,'LEGACY','GOOD',?,?,?,'Asia/Shanghai',0,0,2,5,?,0,'fixture',0.9,'ui6-layout',?)""",
            arrayOf(target,uid,if(isNew) 1 else 0,clock.millis(),date,clock.millis()+86400000,"$target-$record"))
    }
    private fun seed(target:String,empty:Boolean=false) {
        ContentCatalog(context,Languages.get(target)).use {catalog->
            val index=catalog.studyIndex("zh-Hans")
            val uids=index.books.getValue(catalog.language.defaultBook).take(3)
            UserDatabase(context,database).use {store->
                val study=StudyRepository(store,clock)
                val app=AppRepository(store,study,clock)
                if(!empty) {
                    uids.take(2).forEachIndexed {ordinal,uid->store.writableDatabase.execSQL(
                        "INSERT INTO learning_card VALUES(?,?,2,5,?,?,?,0,'REVIEW',1)",
                        arrayOf(target,uid,clock.millis()-1000,clock.millis()-86400000,ordinal))}
                    store.writableDatabase.execSQL("INSERT INTO learning_card VALUES(?,?,0,0,?,NULL,9,0,'NEW',1)",
                        arrayOf(target,uids[2],clock.millis()))
                    val missing="$target:ui6-unavailable:noun:1"
                    store.writableDatabase.execSQL("INSERT INTO learning_card VALUES(?,?,2,5,?,?,3,0,'REVIEW',0)",
                        arrayOf(target,missing,clock.millis()-1000,clock.millis()-86400000))
                    listOf(uids.first(),missing).forEach {uid->store.writableDatabase.execSQL("INSERT INTO favorites VALUES(?,?)",arrayOf(target,uid))}
                }
                var snapshot=app.save(index,StudyPolicy(StudyMode.STANDARD,1),catalog.language.defaultBook,
                    "Asia/Shanghai",PlaybackPreferences(AutoPlay.NEVER,false))
                if(!empty) {
                    fun score() {
                        val request=snapshot.request(target,"ui6-seed-$target-${snapshot.next!!.id}")
                        assertEquals(ScoreResult.APPLIED,study.reveal(index,request).result)
                        val scored=study.rate(index,request,Rating.GOOD)
                        assertEquals(ScoreResult.APPLIED,scored.result)
                        snapshot=scored.snapshot
                    }
                    score()
                    snapshot=study.chooseExtra(index,snapshot.planId!!,true)
                    score()
                    legacy(store,target,uids[0],"today-one","2026-10-10",true)
                    legacy(store,target,uids[0],"today-again","2026-10-10")
                    legacy(store,target,uids[1],"yesterday","2026-10-09")
                    legacy(store,target,uids[0],"older","2026-10-02")
                }
            }
        }
    }
    private fun isolated(target:String="fr",empty:Boolean=false,both:Boolean=false,
        block:(ActivityScenario<MainActivity>)->Unit) {
        guard();context.deleteDatabase(database);windowSize=7
        try {
            if(both) seed(if(target=="fr") "es" else "fr",empty)
            seed(target,empty)
            val intent=Intent(context,MainActivity::class.java).putExtra("qa_user_database",database)
                .putExtra("qa_clock_millis",clock.millis()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<MainActivity>(intent).use {scenario->
                await("home_screen")
                try {block(scenario)} catch(error:Throwable) {
                    runCatching {capture("ui6-statistics-failure")}
                    throw error
                }
            }
        } finally {context.deleteDatabase(database)}
    }
    private fun await(tag:String)=compose.waitUntil(30000) {
        compose.onAllNodesWithTag(tag,useUnmergedTree=true).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()
    }
    private fun click(tag:String) {
        await(tag)
        compose.waitUntil(30000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any {!it.config.contains(SemanticsProperties.Disabled)}}
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
        if(tag=="statistics_window_toggle") windowSize=if(windowSize==7) 30 else 7
    }
    private fun scroll(tag:String) {
        if(tag.startsWith("statistics_") || tag.startsWith("history_")) {
            val index=when {
                tag.startsWith("history_")->2+ChronoUnit.DAYS.between(LocalDate.parse(tag.takeLast(10)),LocalDate.parse("2026-10-10")).toInt()
                tag in setOf("statistics_window_title","statistics_window_toggle","statistics_empty")->1
                tag=="statistics_window_total"->windowSize+2
                tag.startsWith("statistics_coverage") || tag in setOf("statistics_unavailable","statistics_due","statistics_new","statistics_favorites")->windowSize+3
                else->0
            }
            compose.onNodeWithTag("statistics").performScrollToIndex(index)
        }
        await(tag);compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun open() {click("nav_statistics");await("statistics")}
    private fun tables(filter:Boolean=false):Map<String,List<String>> = UserDatabase(context,database).use {store->
        val names=store.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",null).use {cursor->
            buildList {while(cursor.moveToNext()) add(cursor.getString(0))}
        }.filter {if(filter) it!="app_settings" else true}
        names.associateWith {name->store.readableDatabase.rawQuery("SELECT * FROM \"$name\" ORDER BY rowid",null).use {cursor->
            buildList {while(cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") {if(cursor.isNull(it)) "<null>" else cursor.getString(it)})}
        }}
    }
    private fun select(target:String?=null,learning:String?=null) {
        click("nav_settings");await("settings_form")
        target?.let {scroll("settings_target_$it");click("settings_target_$it")}
        learning?.let {scroll("settings_learning_$it");click("settings_learning_$it")}
        scroll("language_apply");click("language_apply");await("statistics")
    }
    private fun capture(label:String) {
        compose.waitForIdle()
        // captureToImage forces a committed Compose draw before the full-screen capture.
        compose.onRoot(useUnmergedTree=true).captureToImage().asAndroidBitmap().recycle()
        val directory=File(context.getExternalFilesDir(null),"qa").apply {mkdirs()}
        val bitmap=checkNotNull(instrument.uiAutomation.takeScreenshot())
        try {
            FileOutputStream(File(directory,"$label.png")).use {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
        } finally {
            bitmap.recycle()
        }
        File(directory,"$label.tree.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString())
    }

    @Test fun defaultSevenDaysExpandThirtyAndRecreatePreservesChoiceWithoutDatabaseWrites()=isolated {scenario->
        val before=tables()
        open()
        compose.onNodeWithTag("statistics_window_title").assertTextEquals("最近 7 天")
        scroll("history_2026-10-04")
        compose.onNodeWithTag("history_2026-10-03").assertDoesNotExist()
        scroll("statistics_window_toggle");click("statistics_window_toggle")
        compose.onNodeWithTag("statistics_window_title").assertTextEquals("最近 30 天")
        scroll("history_2026-09-11")
        scenario.recreate();await("statistics")
        scroll("statistics_window_title")
        compose.onNodeWithTag("statistics_window_title").assertTextEquals("最近 30 天")
        scroll("statistics_window_toggle");click("statistics_window_toggle")
        compose.onNodeWithTag("statistics_window_title").assertTextEquals("最近 7 天")
        assertEquals(before,tables())
    }
    @Test fun repeatedWordsAndRatingSourcesHaveIndependentLabelsAndHistoricalBarsExplainTheirScale()=isolated {
        open()
        compose.onNodeWithTag("statistics_today_value",useUnmergedTree=true).assertTextEquals("2")
        scroll("history_2026-10-10")
        compose.onNodeWithTag("history_2026-10-10").assertTextEquals("2026-10-10：2 词（新 1 / 复习 1）")
        compose.onNodeWithTag("history_kinds_2026-10-10").assertTextEquals("普通评分 1 次 · 额外评分 1 次 · 旧记录评分 2 次")
        compose.onNodeWithTag("history_bar_2026-10-10").assertContentDescriptionEquals(
            "2026-10-10，2 个不同词条；所选日期范围最高 2 个。仅比较数量。")
        scroll("statistics_window_total")
        compose.onNodeWithTag("statistics_window_total").assertTextEquals("7 天日词数合计 3 · 评分 5 次")
    }
    @Test fun coverageUsesAvailableReviewedContentAndRetainsUnavailableHistoryAndFavorites()=isolated {
        open()
        compose.onNodeWithTag("statistics_learned_value",useUnmergedTree=true).assertTextEquals("2")
        scroll("statistics_coverage_count")
        compose.onNodeWithTag("statistics_coverage_count").assertTextEquals("当前可用词库已学 2 / 19465")
        scroll("statistics_unavailable")
        compose.onNodeWithTag("statistics_unavailable").assertTextEquals("另有 1 个已学词当前内容不可用，历史仍保留，不计入内容覆盖率。")
        scroll("statistics_favorites")
        compose.onNodeWithTag("statistics_favorites_value",useUnmergedTree=true).assertTextEquals("2")
        capture("ui6-fr-statistics-coverage")
    }
    @Test fun frenchSpanishAndLearningLanguageSwitchKeepStatisticsAndAllLearningTablesIsolated()=isolated(both=true) {
        open();capture("ui6-fr-statistics")
        val before=tables(filter=true)
        select(learning="en")
        compose.onNodeWithTag("statistics_today_value",useUnmergedTree=true).assertTextEquals("2")
        assertEquals(before,tables(filter=true))
        select(target="es")
        scroll("statistics_coverage_count")
        compose.onNodeWithTag("statistics_coverage_count").assertTextEquals("当前可用词库已学 2 / 14290")
        scroll("statistics_date");capture("ui6-es-statistics")
        select(target="fr")
        compose.onNodeWithTag("statistics_today_value",useUnmergedTree=true).assertTextEquals("2")
        assertEquals(before,tables(filter=true))
    }
    @Test fun zeroRecordsHaveExplicitPromptAndViewingStatisticsDoesNotGenerateMoreTasks()=isolated(empty=true) {
        val before=tables()
        open()
        compose.onNodeWithTag("statistics_today_value",useUnmergedTree=true).assertTextEquals("0")
        scroll("statistics_empty")
        compose.onNodeWithTag("statistics_empty").assertTextEquals("还没有学习记录，开始第一轮学习吧。")
        scroll("history_2026-10-10")
        compose.onNodeWithTag("history_bar_2026-10-10").assertContentDescriptionEquals(
            "2026-10-10，0 个不同词条；所选日期范围最高 0 个。仅比较数量。")
        scroll("statistics_window_toggle");click("statistics_window_toggle")
        scroll("history_2026-09-11")
        click("nav_home");await("home_screen");open()
        assertEquals(before,tables())
    }
    @Test fun thirtyDayTotalsUseDailyUidCountsAndPreserveBusinessDateAndFrozenZoneLabels()=isolated(target="es") {
        open()
        compose.onNodeWithTag("statistics_date").assertTextEquals("统计归属日期 2026-10-10 · 学习时区 Asia/Shanghai")
        scroll("statistics_window_toggle");click("statistics_window_toggle")
        scroll("statistics_window_total")
        compose.onNodeWithTag("statistics_window_total").assertTextEquals("30 天日词数合计 4 · 评分 6 次")
        scroll("history_2026-10-02")
        compose.onNodeWithTag("history_kinds_2026-10-02").assertTextEquals("普通评分 0 次 · 额外评分 0 次 · 旧记录评分 1 次")
        capture("ui6-es-statistics-30-days")
    }
}

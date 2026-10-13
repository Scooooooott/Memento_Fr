package com.scott.memento

import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.memento.data.content.ContentCatalog
import com.scott.memento.data.user.AppRepository
import com.scott.memento.data.user.StudyRepository
import com.scott.memento.data.user.UserDatabase
import com.scott.memento.domain.*
import com.scott.memento.ui.MementoApp
import com.scott.memento.ui.MementoController
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Mutable-clock production MementoApp test; it does not claim MainActivity clock mutation. */
class StatisticsTimeUiInstrumentedTest {
    @get:Rule val compose=createComposeRule()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private val database="memento-test-ui6-statistics-time.db"
    private class MutableClock:Clock() {
        @Volatile var now=Instant.parse("2026-10-10T15:30:00Z")
        override fun getZone()=ZoneOffset.UTC
        override fun withZone(zone:ZoneId):Clock=Clock.fixed(now,zone)
        override fun instant()=now
    }
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
    private fun await(tag:String)=compose.waitUntil(30000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
    private fun click(tag:String) {
        await(tag)
        compose.waitUntil(30000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any {!it.config.contains(SemanticsProperties.Disabled)}}
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
    }
    private fun capture(label:String) {
        compose.waitForIdle()
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
    @Test fun deferredStatisticsStayOnYesterdayUntilTwoAmAndStaleRatingDoesNotWrite() {
        guard();context.deleteDatabase(database)
        val clock=MutableClock()
        val owner=ViewModelStore()
        try {
            ContentCatalog(context,Languages.get("fr")).use {catalog->UserDatabase(context,database).use {store->
                val index=catalog.studyIndex("zh-Hans")
                val study=StudyRepository(store,clock)
                val initial=AppRepository(store,study,clock).save(index,StudyPolicy(StudyMode.STANDARD,3),
                    catalog.language.defaultBook,"Asia/Shanghai",PlaybackPreferences(AutoPlay.NEVER,false))
                val request=initial.request("fr","ui6-before-midnight")
                assertEquals(ScoreResult.APPLIED,study.reveal(index,request).result)
                assertEquals(ScoreResult.APPLIED,study.rate(index,request,Rating.GOOD).result)
            }}
            lateinit var controller:MementoController
            compose.runOnUiThread {controller=MementoController(context,database,clock);owner.put("app",controller)}
            compose.setContent {MaterialTheme {MementoApp(controller)}}
            await("home_screen");click("nav_statistics");await("statistics")
            clock.now=Instant.parse("2026-10-10T17:59:00Z")
            compose.runOnIdle {controller.refresh()}
            click("reset_later");await("statistics_grace")
            compose.onNodeWithTag("statistics_grace").assertTextEquals("延迟至 02:00 刷新；期间评分计入 2026-10-10。")
            compose.onNodeWithTag("statistics_date").assertTextEquals("统计归属日期 2026-10-10 · 学习时区 Asia/Shanghai")
            click("nav_home");await("home_screen")
            compose.onNodeWithTag("open_study").performScrollTo();click("open_study")
            click("study_reveal");click("rating_GOOD")
            compose.waitUntil(30000) {controller.state.value.study?.stats?.uniqueWords==2 && !controller.state.value.busy}
            click("study_pause");await("home_screen");click("nav_statistics");await("statistics")
            compose.onNodeWithTag("statistics_today_value",useUnmergedTree=true).assertTextEquals("2")
            capture("ui6-statistics-grace")
            val stale=controller.state.value.study!!.request("fr","ui6-stale-after-two")
            compose.runOnIdle {controller.reveal(stale)}
            compose.waitUntil(30000) {controller.state.value.study?.next?.revealed==true && !controller.state.value.busy}
            clock.now=Instant.parse("2026-10-10T18:00:00Z")
            compose.runOnIdle {controller.rate(stale,Rating.GOOD)}
            compose.waitUntil(30000) {controller.state.value.study?.businessDate=="2026-10-11" && !controller.state.value.busy}
            compose.onNodeWithTag("statistics_date").assertTextEquals("统计归属日期 2026-10-11 · 学习时区 Asia/Shanghai")
            compose.onNodeWithTag("statistics_grace").assertDoesNotExist()
            compose.onNodeWithTag("statistics_today_value",useUnmergedTree=true).assertTextEquals("0")
            UserDatabase(context,database).use {store->
                store.readableDatabase.rawQuery("SELECT business_date,COUNT(*) FROM review_log GROUP BY business_date",null).use {cursor->
                    assertTrue(cursor.moveToFirst());assertEquals("2026-10-10",cursor.getString(0));assertEquals(2,cursor.getInt(1));assertFalse(cursor.moveToNext())
                }
            }
            capture("ui6-statistics-two-am")
        } finally {
            compose.runOnUiThread {owner.clear()}
            context.deleteDatabase(database)
        }
    }
}

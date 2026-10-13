package com.scott.memento

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.memento.data.content.ContentCatalog
import com.scott.memento.data.user.*
import com.scott.memento.domain.*
import com.scott.memento.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.*
import java.io.File
import java.io.FileOutputStream

/** Simulates dates through injected Clock, without changing device time or any real user's data. */
class MementoTimeUiInstrumentedTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="memento-test-p5-time-ui.db"
    private class MutableClock:Clock() {
        @Volatile var value=Instant.parse("2026-10-10T15:30:00Z")
        override fun instant()=value
        override fun getZone()=ZoneOffset.UTC
        override fun withZone(zone:ZoneId):Clock=Clock.fixed(value,zone)
    }
    private fun await(tag:String)=compose.waitUntil(30000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
    private fun click(tag:String){await(tag);compose.waitUntil(30000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any{!it.config.contains(SemanticsProperties.Disabled)}};compose.onNodeWithTag(tag).assertIsDisplayed().performClick()}
    private fun screenshot(name:String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(350) // Wait for the Android dialog window to reach the captured frame.
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory=File(context.getExternalFilesDir(null),"qa").apply{mkdirs()}
        FileOutputStream(File(directory,"$name.png")).use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private fun seed(clock:Clock,reviews:Int=0) {
        context.deleteDatabase(name)
        ContentCatalog(context,Languages.get("fr")).use{catalog->UserDatabase(context,name).use{store->
            store.writableDatabase.execSQL("UPDATE app_settings SET study_zone_id='Asia/Shanghai'")
            val index=catalog.studyIndex("zh-Hans")
            index.books[catalog.language.defaultBook]!!.take(reviews).forEach {uid->
                store.writableDatabase.execSQL("INSERT INTO learning_card VALUES('fr',?,2,5,?, ?,1,0,'REVIEW',1)",arrayOf(uid,clock.millis()-1000,clock.millis()-86400000))
            }
            val study=StudyRepository(store,clock)
            AppRepository(store,study,clock).save(index,StudyPolicy(StudyMode.STANDARD,1),catalog.language.defaultBook,"Asia/Shanghai",PlaybackPreferences(AutoPlay.NEVER,false))
        }}
    }
    private fun runApp(clock:Clock,block:(MementoController)->Unit) {
        val owner=ViewModelStore();lateinit var controller:MementoController
        compose.runOnUiThread{controller=MementoController(context,name,clock);owner.put("app",controller)}
        compose.setContent{MaterialTheme{MementoApp(controller)}}
        try{await("open_study");block(controller)}finally{compose.runOnUiThread{owner.clear()};context.deleteDatabase(name)}
    }
    @Test fun midnightPromptDefersScoresToYesterdayThenTwoOclockCreatesTodayPlan() {
        val clock=MutableClock();seed(clock)
        runApp(clock){controller->
            click("open_study");await("study_reveal")
            clock.value=Instant.parse("2026-10-10T16:30:00Z");compose.runOnIdle{controller.refresh()}
            await("reset_later");screenshot("p5-midnight-choice");click("reset_later");await("study_reveal")
            click("study_reveal");click("rating_GOOD");await("summary_screen")
            UserDatabase(context,name).use{store->store.readableDatabase.rawQuery("SELECT business_date,reviewed_at FROM review_log",null).use{it.moveToFirst();assertEquals("2026-10-10",it.getString(0));assertEquals(clock.millis(),it.getLong(1))}}
            clock.value=Instant.parse("2026-10-10T18:00:00Z");compose.runOnIdle{controller.refresh()}
            compose.onNodeWithTag("summary_home").performScrollTo();click("summary_home")
            compose.onNodeWithTag("open_study").performScrollTo();click("open_study");await("study_reveal")
            assertEquals("2026-10-11",controller.state.value.study!!.businessDate)
            assertEquals(0,controller.state.value.study!!.stats.uniqueWords)
        }
    }
    @Test fun settingsMidnightChoiceKeepsDraftAndCancelDoesNotApplyItsQuantity() {
        val clock=MutableClock();seed(clock)
        runApp(clock){controller->
            click("open_study");await("study_reveal")
            val plan=controller.state.value.study!!.planId
            click("nav_settings");await("settings_form")
            compose.waitUntil(30000){compose.onAllNodesWithTag("study_target").fetchSemanticsNodes().any{!it.config.contains(SemanticsProperties.Disabled)}}
            compose.onNodeWithTag("study_target").performScrollTo().performTextReplacement("8")
            compose.onNodeWithTag("study_target").performImeAction()
            clock.value=Instant.parse("2026-10-10T16:30:00Z");compose.runOnIdle{controller.refresh()}
            await("settings_date_pending");compose.onNodeWithTag("study_save").assertIsNotEnabled()
            compose.onNodeWithTag("study_target").assertTextContains("8")
            compose.onNodeWithTag("settings_reset_later").performScrollTo();click("settings_reset_later")
            compose.waitUntil(30000){controller.state.value.study?.guard==StudyGuard.READY}
            compose.onNodeWithTag("study_target").assertTextContains("8")
            compose.onNodeWithTag("study_save").assertIsEnabled()
            click("settings_cancel");await("study_reveal")
            assertEquals(1,controller.state.value.language!!.policy.dailyTarget)
            assertEquals(plan,controller.state.value.study!!.planId)
            assertEquals("2026-10-10",controller.state.value.study!!.businessDate)
            UserDatabase(context,name).use{store->
                store.readableDatabase.rawQuery("SELECT COUNT(*) FROM review_log",null).use{it.moveToFirst();assertEquals(0,it.getInt(0))}
                store.readableDatabase.rawQuery("SELECT daily_target FROM language_settings WHERE target_language='fr'",null).use{it.moveToFirst();assertEquals(1,it.getInt(0))}
            }
        }
    }
    @Test fun ordinaryReviewCompletionAsksForOnlyRemainingReviewsAndRestoresChoice() {
        val clock=MutableClock();seed(clock,3)
        runApp(clock){controller->
            click("open_study");click("study_reveal");click("rating_GOOD")
            await("extra_accept");screenshot("p5-extra-review-choice");click("extra_accept");await("study_reveal")
            assertEquals("EXTRA",controller.state.value.study!!.next!!.kind);assertEquals(2,controller.state.value.study!!.extraRemaining)
            click("study_reveal");click("rating_GOOD");await("study_reveal")
            assertEquals(2,controller.state.value.study!!.stats.uniqueWords);assertEquals(0,controller.state.value.study!!.stats.newWords)
            compose.runOnIdle{controller.refresh()};compose.onNodeWithTag("extra_accept").assertDoesNotExist()
            assertEquals(1,controller.state.value.study!!.extraRemaining)
        }
    }
}

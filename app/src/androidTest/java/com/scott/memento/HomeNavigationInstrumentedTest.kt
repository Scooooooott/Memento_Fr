package com.scott.memento

import android.content.Intent
import android.os.Build
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
import java.time.ZoneOffset

/** UI1 acceptance uses the production Activity, formal content and an isolated debug database. */
class HomeNavigationInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="memento-test-ui1-navigation.db"
    private val clock=Clock.fixed(Instant.parse("2026-10-10T04:00:00Z"),ZoneOffset.UTC)

    private data class Fixture(val book:Book,val firstUid:String)
    private data class QueueState(val plans:List<String>,val items:List<String>,val logCount:Int)

    private fun intent()=Intent(context,MainActivity::class.java)
        .putExtra("qa_user_database",name)
        .putExtra("qa_clock_millis",clock.millis())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun requireIsolatedEmulator() {
        assertEquals("com.scott.memento",context.packageName)
        check(Build.HARDWARE in setOf("ranchu","goldfish") || Build.FINGERPRINT.contains("generic")) {
            "UI1 database cleanup is restricted to an emulator; real devices are not allowed."
        }
        check(name.startsWith("memento-test-") && name.endsWith(".db"))
    }

    private fun seed(target:String,goal:Int):Fixture {
        ContentCatalog(context,Languages.get(target)).use {catalog->
            val book=catalog.books().single{it.id==catalog.language.defaultBook}
            val index=catalog.studyIndex("zh-Hans")
            UserDatabase(context,name).use {store->
                val study=StudyRepository(store,clock)
                AppRepository(store,study,clock).save(index,StudyPolicy(StudyMode.STANDARD,goal),book.id,
                    "Asia/Shanghai",PlaybackPreferences(AutoPlay.NEVER,false))
            }
            return Fixture(book,catalog.page("",book.id,0).words.first().uid)
        }
    }

    private fun isolated(target:String="fr",goal:Int=2,block:(ActivityScenario<MainActivity>,Fixture)->Unit) {
        requireIsolatedEmulator()
        context.deleteDatabase(name)
        try {
            val fixture=seed(target,goal)
            ActivityScenario.launch<MainActivity>(intent()).use {scenario->
                await("home_screen")
                block(scenario,fixture)
            }
        } catch(error:Throwable) {
            runCatching {diagnostic("ui1-navigation-failure")}
            throw error
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun await(tag:String) {
        compose.waitUntil(30000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
    }
    private fun click(tag:String) {
        await(tag)
        compose.waitUntil(30000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
            .any{!it.config.contains(SemanticsProperties.Disabled)}}
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
    }
    private fun scrollClick(tag:String) {
        await(tag)
        compose.onNodeWithTag(tag).performScrollTo()
        click(tag)
    }
    private fun systemBack(scenario:ActivityScenario<MainActivity>) {
        scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()}
    }
    private fun word():String=compose.onNodeWithTag("study_word").fetchSemanticsNode()
        .config[SemanticsProperties.Text].joinToString(""){it.text}
    private fun number(sql:String,args:Array<String> = emptyArray()):Int=UserDatabase(context,name).use {store->
        store.readableDatabase.rawQuery(sql,args).use{cursor->check(cursor.moveToFirst());cursor.getInt(0)}
    }
    private fun rows(sql:String,args:Array<String> = emptyArray()):List<String> = UserDatabase(context,name).use {store->
        store.readableDatabase.rawQuery(sql,args).use{cursor->buildList {
            while(cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|"){cursor.getString(it).orEmpty()})
        }}
    }
    private fun queue(target:String)=QueueState(
        rows("SELECT id,revision,status,book_snapshot,target_snapshot FROM daily_plan WHERE target_language=? ORDER BY id",arrayOf(target)),
        rows("SELECT id,plan_id,lexeme_uid,queue_kind,position,state,revealed FROM daily_plan_item WHERE target_language=? ORDER BY id",arrayOf(target)),
        number("SELECT COUNT(*) FROM review_log WHERE target_language=?",arrayOf(target)),
    )
    private fun diagnostic(label:String) {
        compose.waitForIdle()
        // The semantics tree can be ready before the Activity's surface reaches the screenshot frame.
        android.os.SystemClock.sleep(350)
        val directory=File(context.getExternalFilesDir(null),"qa").apply{mkdirs()}
        File(directory,"$label-tree.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString())
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        FileOutputStream(File(directory,"$label.png")).use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
        bitmap.recycle()
    }

    private fun verifyHomeAndNavigation(target:String) {
        isolated(target) {_,fixture->
            val initial=queue(target)
            compose.onNodeWithTag("main_navigation").assertIsDisplayed()
            compose.onNodeWithTag("nav_home").assertIsSelected()
            val targetLabel=context.getString(if(target=="fr") R.string.french else R.string.spanish)
            compose.onNodeWithTag("language_context").assertTextEquals(context.getString(R.string.language_context_format,targetLabel,context.getString(R.string.chinese)))
            compose.onNodeWithTag("home_remaining").assertTextContains("2")
            compose.onNodeWithTag("home_today_done").assertTextContains("0")
            compose.onNodeWithTag("home_book_title").assertTextContains(fixture.book.title)
            compose.onNodeWithTag("home_progress").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                    androidx.compose.ui.semantics.ProgressBarRangeInfo(0f,0f..1f)))
            diagnostic("ui1-$target-home")
            // The home card opens its book without reconfiguring the language's saved new-word book.
            scrollClick("current_book_progress")
            await("word_list")
            compose.onNodeWithTag("open_catalog").assertIsSelected()
            compose.onNodeWithTag("choose_book",useUnmergedTree=true).assertTextContains(fixture.book.title)
            compose.onNodeWithTag("result_count").assertTextEquals(context.getString(R.string.result_count,targetLabel,fixture.book.count))
            compose.onNodeWithTag("word_${fixture.firstUid}").assertExists()
            assertEquals(listOf(fixture.book.id),rows("SELECT book_id FROM language_settings WHERE target_language=?",arrayOf(target)))
            click("nav_statistics");await("statistics")
            compose.onNodeWithTag("nav_statistics").assertIsSelected()
            click("nav_home");await("home_screen")
            click("nav_settings");await("settings_form")
            compose.onNodeWithTag("main_navigation").assertDoesNotExist()
            click("app_back");await("home_screen")
            compose.onNodeWithTag("nav_home").assertIsSelected()
            assertEquals(initial,queue(target))
            assertEquals(0,number("SELECT COUNT(*) FROM review_log"))
        }
    }

    @Test fun frenchHomeHasBookProgressAndSelectedMainNavigationWithoutMutatingPlan()=verifyHomeAndNavigation("fr")
    @Test fun spanishHomeHasBookProgressAndSelectedMainNavigationWithoutMutatingPlan()=verifyHomeAndNavigation("es")

    @Test fun revealedStudyDetailReturnsToSameAnswerWithoutMainNavigation() {
        isolated {_,_->
            click("open_study");await("study_reveal")
            compose.onNodeWithTag("main_navigation").assertDoesNotExist()
            val first=word()
            click("study_reveal");await("rating_GOOD")
            val revealed=queue("fr")
            scrollClick("study_details");await("word_detail")
            compose.onNodeWithTag("main_navigation").assertDoesNotExist()
            click("detail_back");await("rating_GOOD")
            assertEquals(first,word())
            compose.onNodeWithTag("study_reveal").assertDoesNotExist()
            assertEquals(revealed,queue("fr"))
            assertEquals(0,number("SELECT COUNT(*) FROM review_log"))
        }
    }

    @Test fun cancellingSettingsDraftReturnsToStudyWithoutScoringOrRevisingPlan() {
        isolated {_,_->
            click("open_study");click("study_reveal");await("rating_GOOD")
            val first=word();val revealed=queue("fr")
            diagnostic("ui1-study-layout")
            click("nav_settings");await("settings_form")
            compose.onNodeWithTag("study_target").performScrollTo().performTextReplacement("7")
            click("settings_cancel");await("rating_GOOD")
            assertEquals(first,word())
            assertEquals(revealed,queue("fr"))
            assertEquals(2,number("SELECT daily_target FROM language_settings WHERE target_language='fr'"))
            compose.onNodeWithTag("main_navigation").assertDoesNotExist()
        }
    }

    @Test fun detailRecreationRetainsLibraryOriginForSystemBack() {
        isolated("es") {scenario,fixture->
            scrollClick("current_book_progress");await("word_list")
            click("word_${fixture.firstUid}");await("word_detail")
            val initial=queue("es")
            scenario.recreate();await("word_detail")
            compose.onNodeWithTag("main_navigation").assertDoesNotExist()
            systemBack(scenario);await("word_list")
            compose.onNodeWithTag("open_catalog").assertIsSelected()
            compose.onNodeWithTag("choose_book",useUnmergedTree=true).assertTextContains(fixture.book.title)
            assertEquals(initial,queue("es"))
        }
    }

    @Test fun recreatedBookPickerAndSettingsReturnToRevealedStudyOrigin() {
        isolated {scenario,_->
            click("open_study");click("study_reveal");await("rating_GOOD")
            val first=word();val initial=queue("fr")
            click("nav_settings");await("settings_form")
            compose.onNodeWithTag("study_target").performScrollTo().performTextReplacement("6")
            scrollClick("settings_choose_book");await("book_picker")
            scenario.recreate();await("book_picker")
            systemBack(scenario);await("settings_form")
            compose.onNodeWithTag("study_target").assertTextContains("6")
            systemBack(scenario);await("rating_GOOD")
            assertEquals(first,word())
            assertEquals(initial,queue("fr"))
            compose.onNodeWithTag("main_navigation").assertDoesNotExist()
        }
    }

    @Test fun completedHomeHasZeroRemainingDisabledStartAndNeverGrantsQuotaAgain() {
        isolated(goal=1) {_,_->
            click("open_study");click("study_reveal");click("rating_GOOD");await("summary_screen")
            scrollClick("summary_home");await("home_screen")
            compose.onNodeWithTag("home_remaining").assertTextContains("0")
            compose.onNodeWithTag("home_today_done").assertTextContains("1")
            compose.onNodeWithTag("open_study").assertIsNotEnabled()
            compose.onNodeWithTag("home_progress").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                    androidx.compose.ui.semantics.ProgressBarRangeInfo(1f,0f..1f)))
            val complete=queue("fr")
            repeat(3) {
                click("nav_statistics");await("statistics")
                click("nav_home");await("home_screen")
                compose.onNodeWithTag("open_study").assertIsNotEnabled()
                assertEquals(complete,queue("fr"))
            }
            assertEquals(1,number("SELECT COUNT(*) FROM daily_plan"))
            assertEquals(1,number("SELECT COUNT(*) FROM daily_plan_item"))
            assertEquals(1,number("SELECT COUNT(*) FROM review_log"))
        }
    }

    @Test fun selectingUnconfiguredSpanishAndCancellingConfigurationDoesNotCreateItsQuota() {
        isolated {_,_->
            val french=queue("fr")
            click("nav_settings");await("settings_form")
            scrollClick("settings_target_es")
            scrollClick("language_apply");await("home_screen")
            compose.onNodeWithTag("language_context").assertTextContains(context.getString(R.string.spanish),substring=true)
            compose.onNodeWithTag("open_study").assertIsEnabled()
                .assertTextContains(context.getString(R.string.configure_plan))
            assertEquals(0,number("SELECT COUNT(*) FROM language_settings WHERE target_language='es'"))
            assertEquals(0,number("SELECT COUNT(*) FROM daily_plan WHERE target_language='es'"))
            scrollClick("study_configure");await("settings_form")
            click("settings_cancel");await("home_screen")
            compose.onNodeWithTag("study_configure").assertExists()
            assertEquals(0,number("SELECT COUNT(*) FROM daily_plan WHERE target_language='es'"))
            assertEquals(french,queue("fr"))
            assertEquals(listOf("es"),rows("SELECT target_language FROM app_settings WHERE id=1"))
        }
    }

    @Test fun displayOnlyLearningLanguageChangeReturnsToSameRevealedStudyAndQuota() {
        isolated {_,_->
            click("open_study");click("study_reveal");await("rating_GOOD")
            val first=word();val initial=queue("fr")
            click("nav_settings");await("settings_form")
            scrollClick("settings_learning_en");scrollClick("language_apply");await("rating_GOOD")
            compose.onNodeWithTag("language_context").assertTextContains("英语",substring=true)
            assertEquals(first,word())
            assertEquals(initial,queue("fr"))
            assertEquals(listOf("en"),rows("SELECT learning_language FROM app_settings WHERE id=1"))
            compose.onNodeWithTag("study_reveal").assertDoesNotExist()
        }
    }
}

package com.scott.memento

import android.content.Intent
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.memento.data.content.ContentCatalog
import com.scott.memento.data.user.AppRepository
import com.scott.memento.data.user.StudyRepository
import com.scott.memento.data.user.UserDatabase
import com.scott.memento.domain.*
import com.scott.memento.ui.StudyRatingBar
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** UI2 uses formal content and the production Activity, never the default user database. */
class StudyLayoutInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val database="memento-test-ui2-layout.db"
    private val clock=Clock.fixed(Instant.parse("2026-10-10T04:00:00Z"),ZoneOffset.UTC)

    private data class Fixture(val target:String,val uids:List<String>,val details:Map<String,Detail>)

    private fun systemProperty(key:String)=ProcessBuilder("/system/bin/getprop",key).start().let {process->
        process.inputStream.bufferedReader().use {it.readText().trim()}.also {check(process.waitFor()==0)}
    }
    private fun requireIsolatedEmulator() {
        assertEquals("com.scott.memento",context.packageName)
        check(Build.HARDWARE in setOf("ranchu","goldfish") || Build.FINGERPRINT.contains("generic")) {
            "UI2 database cleanup is restricted to an emulator."
        }
        check(systemProperty("ro.kernel.qemu")=="1") {"Physical devices are not permitted."}
        check(listOf("ro.boot.qemu.avd_name","ro.kernel.qemu.avd_name").any {systemProperty(it)=="MementoApi35"}) {
            "UI2 tests require the dedicated MementoApi35 emulator."
        }
        check(database.startsWith("memento-test-") && database.endsWith(".db"))
    }
    private fun intent()=Intent(context,MainActivity::class.java)
        .putExtra("qa_user_database",database).putExtra("qa_clock_millis",clock.millis())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun seed(target:String,goal:Int,reviews:Int,mode:StudyMode):Fixture {
        ContentCatalog(context,Languages.get(target)).use {catalog->
            val index=catalog.studyIndex("zh-Hans")
            val book=catalog.language.defaultBook
            val uids=index.books.getValue(book).take(maxOf(goal,reviews,1))
            val details=uids.associateWith {checkNotNull(catalog.detail(it))}
            UserDatabase(context,database).use {store->
                uids.take(reviews).forEach {uid->
                    store.writableDatabase.execSQL("INSERT INTO learning_card VALUES(?,?,2,5,?,?,1,0,'REVIEW',1)",
                        arrayOf(target,uid,clock.millis()-1000,clock.millis()-86400000))
                }
                val study=StudyRepository(store,clock)
                AppRepository(store,study,clock).save(index,
                    StudyPolicy(mode,if(mode==StudyMode.STANDARD) goal else null),book,"Asia/Shanghai",
                    PlaybackPreferences(AutoPlay.NEVER,false))
            }
            return Fixture(target,uids,details)
        }
    }
    private fun isolated(target:String="fr",goal:Int=2,reviews:Int=0,mode:StudyMode=StudyMode.STANDARD,
        block:(ActivityScenario<MainActivity>,Fixture)->Unit) {
        requireIsolatedEmulator()
        context.deleteDatabase(database)
        try {
            val fixture=seed(target,goal,reviews,mode)
            ActivityScenario.launch<MainActivity>(intent()).use {scenario->
                await("home_screen")
                block(scenario,fixture)
            }
        } catch(error:Throwable) {
            runCatching {capture("ui2-layout-failure")}
            throw error
        } finally {context.deleteDatabase(database)}
    }
    private fun await(tag:String)=compose.waitUntil(30000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun enabled(tag:String) {
        await(tag)
        compose.waitUntil(30000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
            .any {!it.config.contains(SemanticsProperties.Disabled)}}
    }
    private fun click(tag:String) {
        enabled(tag)
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
    }
    private fun scrollClick(tag:String) {
        await(tag);compose.onNodeWithTag(tag).performScrollTo();click(tag)
    }
    private fun rows(sql:String,args:Array<String> = emptyArray()):List<String> = UserDatabase(context,database).use {store->
        store.readableDatabase.rawQuery(sql,args).use {cursor->buildList {
            while(cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") {cursor.getString(it).orEmpty()})
        }}
    }
    private fun number(sql:String,args:Array<String> = emptyArray()):Int = UserDatabase(context,database).use {store->
        store.readableDatabase.rawQuery(sql,args).use {cursor->check(cursor.moveToFirst());cursor.getInt(0)}
    }
    private fun currentUid(target:String,kind:String="NORMAL")=rows(
        "SELECT lexeme_uid FROM daily_plan_item WHERE target_language=? AND queue_kind=? AND state='PENDING' ORDER BY position LIMIT 1",
        arrayOf(target,kind)).single()
    private fun word()=compose.onNodeWithTag("study_word").fetchSemanticsNode().config[SemanticsProperties.Text]
        .joinToString("") {it.text}
    private fun persistedStudy(target:String)=listOf("daily_plan","daily_plan_item","learning_card","review_log","extra_review_batch")
        .associateWith {table->rows("SELECT * FROM $table WHERE target_language=? ORDER BY 1,2",arrayOf(target))}
    private fun assertUnscored(target:String,uid:String) {
        assertEquals(listOf("0"),rows("SELECT revealed FROM daily_plan_item WHERE target_language=? AND lexeme_uid=?",arrayOf(target,uid)))
        assertEquals(0,number("SELECT COUNT(*) FROM review_log WHERE target_language=?",arrayOf(target)))
        compose.onNodeWithTag("study_reveal").assertExists()
        compose.onNodeWithTag("rating_GOOD").assertDoesNotExist()
    }
    private fun score(rating:Rating) {
        click("study_reveal");click("rating_${rating.name}")
    }
    private fun assertSummary(normal:Int,extra:Int,new:Int,review:Int,again:Int=0,good:Int=0,hard:Int=0,easy:Int=0) {
        await("summary_screen")
        compose.onNodeWithTag("summary_normal_count").assertTextEquals(context.getString(R.string.summary_queue_count,normal,0))
        if(compose.onAllNodesWithTag("summary_extra_count").fetchSemanticsNodes().isEmpty()) {
            assertEquals("An unoffered extra queue must have no completed extra words",0,extra)
        } else compose.onNodeWithTag("summary_extra_count").assertTextEquals(context.getString(R.string.summary_queue_count,extra,0))
        compose.onNodeWithTag("summary_new_count").assertTextEquals(context.getString(R.string.summary_new_count,new))
        compose.onNodeWithTag("summary_review_count").assertTextEquals(context.getString(R.string.summary_review_count,review))
        listOf("summary_rating_AGAIN" to again,"summary_rating_HARD" to hard,
            "summary_rating_GOOD" to good,"summary_rating_EASY" to easy).forEach {(tag,count)->
            compose.onNodeWithTag(tag).assertTextEquals(count.toString())
        }
        compose.onNodeWithTag("main_navigation").assertDoesNotExist()
    }
    private fun capture(label:String) {
        compose.waitForIdle();android.os.SystemClock.sleep(350)
        val directory=File(context.getExternalFilesDir(null),"qa").apply {mkdirs()}
        File(directory,"$label-tree.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString())
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        FileOutputStream(File(directory,"$label.png")).use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }

    private fun formalLearningFlow(target:String) {
        isolated(target) {_,fixture->
            click("open_study");await("study_card")
            val first=currentUid(target)
            val detail=fixture.details.getValue(first)
            assertEquals(detail.summary.lemma,word())
            if(detail.summary.partOfSpeech.isNotBlank()) compose.onNodeWithTag("study_metadata").assertTextContains(detail.summary.partOfSpeech,substring=true)
            if(detail.gender.isNotBlank()) compose.onNodeWithTag("study_metadata").assertTextContains(detail.gender,substring=true)
            compose.onNodeWithTag("meaning_${detail.senses.first().id}").assertDoesNotExist()
            compose.onNodeWithTag("main_navigation").assertDoesNotExist()
            capture("ui2-$target-front")
            click("study_reveal");await("rating_AGAIN")
            val sense=detail.senses.first()
            compose.onNodeWithTag("meaning_${sense.id}").assertTextEquals(sense.translations.getValue("zh-Hans"))
            sense.examples.firstOrNull()?.let {example->
                compose.onNodeWithTag("study_example_${example.id}").assertTextEquals(example.source)
                compose.onNodeWithTag("study_example_translation_${example.id}").assertTextEquals(example.translations.getValue("zh-Hans"))
            }
            val forms=(if(detail.formDetails.isEmpty()) detail.forms else detail.formDetails.sortedBy {it.order}.map {it.label to it.form})
                .filter {it.second.isNotBlank()}.take(4)
            forms.forEachIndexed {index,(label,form)->
                compose.onNodeWithTag("study_form_$index").assert(hasAnyDescendant(hasText(label)) and hasAnyDescendant(hasText(form)))
            }
            compose.onNodeWithTag("study_form_4").assertDoesNotExist()
            capture("ui2-$target-back")
            click("rating_AGAIN");await("study_reveal")
            val second=currentUid(target)
            assertNotEquals(first,second)
            assertEquals(fixture.details.getValue(second).summary.lemma,word())
            assertEquals(listOf("$first|AGAIN|NORMAL|2026-10-10|${clock.millis()}"),rows(
                "SELECT lexeme_uid,rating,kind,business_date,reviewed_at FROM review_log WHERE target_language=?",arrayOf(target)))
            score(Rating.GOOD)
            assertSummary(normal=2,extra=0,new=2,review=0,again=1,good=1)
            assertEquals(2,number("SELECT COUNT(*) FROM review_log WHERE target_language=?",arrayOf(target)))
            assertEquals(2,number("SELECT COUNT(DISTINCT item_id) FROM review_log WHERE target_language=?",arrayOf(target)))
            assertEquals(2,number("SELECT COUNT(*) FROM daily_plan_item WHERE target_language=? AND state='GRADED'",arrayOf(target)))
            assertEquals(0,number("SELECT COUNT(*) FROM review_log WHERE target_language<>?",arrayOf(target)))
            assertEquals(1,number("SELECT COUNT(*) FROM daily_plan WHERE target_language=?",arrayOf(target)))
            assertEquals(listOf("COMPLETED"),rows("SELECT status FROM daily_plan WHERE target_language=?",arrayOf(target)))
            capture("ui2-$target-summary")
        }
    }

    @Test fun frenchFrontAnswerAndCompletionUseFormalContentAndExactlyOneLogPerItem()=formalLearningFlow("fr")
    @Test fun spanishFrontAnswerAndCompletionUseFormalContentAndExactlyOneLogPerItem()=formalLearningFlow("es")

    @Test fun cardAudioFavoriteAndDragDoNotRevealButIndependentBlankAreaDoes() {
        isolated {_,_->
            click("open_study");await("study_card")
            val uid=currentUid("fr")
            compose.onNodeWithTag("study_card").performTouchInput {click(center)}
            assertUnscored("fr",uid)
            click("study_speak")
            assertUnscored("fr",uid)
            click("study_favorite")
            compose.waitUntil(30000) {number("SELECT COUNT(*) FROM favorites WHERE target_language='fr'")==1}
            assertEquals(listOf(uid),rows("SELECT lexeme_uid FROM favorites WHERE target_language='fr'"))
            assertUnscored("fr",uid)
            compose.onNodeWithTag("study_card").performTouchInput {swipeUp()}
            assertUnscored("fr",uid)
            compose.onNodeWithTag("study_blank_reveal").performScrollTo().assertIsDisplayed().performTouchInput {click(center)}
            await("rating_GOOD")
            assertEquals(listOf("1"),rows("SELECT revealed FROM daily_plan_item WHERE lexeme_uid=?",arrayOf(uid)))
            assertEquals(0,number("SELECT COUNT(*) FROM review_log"))
            compose.onNodeWithTag("study_reveal").assertDoesNotExist()
            compose.onNodeWithTag("study_blank_reveal").assertDoesNotExist()
        }
    }

    @Test fun revealedAnswerSurvivesActivityRecreationAndDisplayLanguageChangeWithoutScoring() {
        isolated {scenario,fixture->
            click("open_study");click("study_reveal");await("rating_GOOD")
            val uid=currentUid("fr")
            val before=persistedStudy("fr")
            scenario.recreate();await("rating_GOOD")
            assertEquals(fixture.details.getValue(uid).summary.lemma,word())
            compose.onNodeWithTag("study_reveal").assertDoesNotExist()
            assertEquals(before,persistedStudy("fr"))
            click("nav_settings");await("settings_form")
            scrollClick("settings_learning_en");scrollClick("language_apply");await("rating_GOOD")
            val sense=fixture.details.getValue(uid).senses.first()
            compose.onNodeWithTag("meaning_${sense.id}").assertTextEquals(sense.translations.getValue("en"))
            assertEquals(before,persistedStudy("fr"))
            assertEquals(listOf("fr|en"),rows("SELECT target_language,learning_language FROM app_settings WHERE id=1"))
            assertEquals(0,number("SELECT COUNT(*) FROM review_log"))
        }
    }

    @Test fun summaryFocusDetailAndRecentSummaryAreReadOnlyAndPreserveNavigationOrigin() {
        isolated(goal=1) {scenario,_->
            click("open_study");val uid=currentUid("fr");score(Rating.AGAIN)
            assertSummary(1,0,1,0,again=1)
            val finished=persistedStudy("fr")
            scrollClick("summary_focus_$uid");await("word_detail")
            scenario.recreate();await("word_detail")
            click("detail_back");await("summary_screen")
            assertEquals(finished,persistedStudy("fr"))
            scrollClick("summary_home");await("home_screen")
            compose.onNodeWithTag("open_study").assertIsNotEnabled()
            repeat(2) {
                scrollClick("home_recent_summary");assertSummary(1,0,1,0,again=1)
                assertEquals(finished,persistedStudy("fr"))
                scrollClick("summary_home");await("home_screen")
            }
            assertEquals(1,number("SELECT COUNT(*) FROM daily_plan"))
            assertEquals(1,number("SELECT COUNT(*) FROM daily_plan_item"))
            assertEquals(1,number("SELECT COUNT(*) FROM review_log"))
        }
    }

    @Test fun completedPlanColdLaunchStaysHomeAndNeverAutomaticallyShowsAnotherSummaryOrQuota() {
        isolated(goal=1) {scenario,_->
            click("open_study");score(Rating.GOOD);assertSummary(1,0,1,0,good=1)
            scenario.recreate();await("summary_screen")
            val finished=persistedStudy("fr")
            scrollClick("summary_home");await("home_screen")
            scenario.recreate();await("home_screen")
            compose.onNodeWithTag("summary_screen").assertDoesNotExist()
            // A fresh Activity launch is independent of rememberSaveable route restoration.
            scenario.close()
            ActivityScenario.launch<MainActivity>(intent()).use {
                await("home_screen")
                compose.onNodeWithTag("summary_screen").assertDoesNotExist()
                compose.onNodeWithTag("open_study").assertIsNotEnabled()
                compose.onNodeWithTag("home_recent_summary").assertExists()
                assertEquals(finished,persistedStudy("fr"))
            }
        }
    }

    @Test fun declinedExtraReviewsShowOrdinarySummaryAndPreserveUnscheduledDueCards() {
        isolated(goal=1,reviews=3) {scenario,_->
            click("open_study");score(Rating.GOOD);await("extra_decline")
            compose.onNodeWithTag("summary_screen").assertDoesNotExist()
            click("extra_decline");assertSummary(1,0,0,1,good=1)
            assertEquals(listOf("DECLINED"),rows("SELECT decision FROM extra_review_batch WHERE target_language='fr'"))
            assertEquals(0,number("SELECT COUNT(*) FROM daily_plan_item WHERE queue_kind='EXTRA' AND state='PENDING'"))
            assertEquals(2,number("SELECT COUNT(*) FROM daily_plan_item WHERE queue_kind='EXTRA' AND state='REMOVED'"))
            assertEquals(2,number("SELECT COUNT(*) FROM learning_card WHERE target_language='fr' AND due_at=?",arrayOf((clock.millis()-1000).toString())))
            val finished=persistedStudy("fr")
            scenario.recreate();await("summary_screen")
            compose.onNodeWithTag("extra_decline").assertDoesNotExist()
            scrollClick("summary_home");await("home_screen")
            scrollClick("home_recent_summary");await("summary_screen")
            assertEquals(finished,persistedStudy("fr"))
        }
    }

    @Test fun acceptedExtraQueueRestoresThenCompletesSummaryWithoutNewWordsOrOrdinaryQuota() {
        isolated(goal=1,reviews=3) {scenario,_->
            click("open_study");score(Rating.GOOD);click("extra_accept");await("study_reveal")
            assertEquals(listOf("ACCEPTED"),rows("SELECT decision FROM extra_review_batch WHERE target_language='fr'"))
            assertEquals(2,number("SELECT COUNT(*) FROM daily_plan_item WHERE queue_kind='EXTRA' AND is_new=0"))
            assertEquals(0,number("SELECT COUNT(*) FROM daily_plan_item WHERE is_new=1"))
            compose.onNodeWithTag("summary_screen").assertDoesNotExist()
            score(Rating.HARD);await("study_reveal")
            val uid=currentUid("fr","EXTRA")
            val middle=persistedStudy("fr")
            scenario.recreate();await("study_reveal")
            assertEquals(uid,currentUid("fr","EXTRA"))
            assertEquals(middle,persistedStudy("fr"))
            compose.onNodeWithTag("extra_accept").assertDoesNotExist()
            score(Rating.EASY);assertSummary(1,2,0,3,good=1,hard=1,easy=1)
            assertEquals(listOf("EXTRA|2","NORMAL|1"),rows("SELECT kind,COUNT(*) FROM review_log GROUP BY kind ORDER BY kind"))
            assertEquals(3,number("SELECT COUNT(DISTINCT lexeme_uid) FROM review_log"))
            assertEquals(1,number("SELECT COUNT(*) FROM daily_plan"))
            assertEquals(1,number("SELECT COUNT(*) FROM daily_plan_item WHERE queue_kind='NORMAL'"))
            scrollClick("summary_home");await("home_screen")
            compose.onNodeWithTag("open_study").assertIsNotEnabled()
            scrollClick("home_recent_summary");assertSummary(1,2,0,3,good=1,hard=1,easy=1)
        }
    }

    @Test fun emptyReviewAllPlanNeverCreatesSuccessfulSummaryOrRatings() {
        isolated(mode=StudyMode.REVIEW_ALL) {scenario,_->
            compose.onNodeWithTag("open_study").assertIsNotEnabled()
            compose.onNodeWithTag("home_recent_summary").assertDoesNotExist()
            compose.onNodeWithTag("summary_screen").assertDoesNotExist()
            scenario.recreate();await("home_screen")
            click("nav_statistics");await("statistics");click("nav_home");await("home_screen")
            compose.onNodeWithTag("summary_screen").assertDoesNotExist()
            compose.onNodeWithTag("home_recent_summary").assertDoesNotExist()
            assertEquals(0,number("SELECT COUNT(*) FROM review_log"))
            assertEquals(0,number("SELECT COUNT(*) FROM daily_plan_item"))
            assertEquals(listOf("REVIEW_ALL|"),rows("SELECT study_mode,target_snapshot FROM daily_plan"))
        }
    }

    @Test fun enlargedFontRatingBarUsesTwoRowsAndFortyEightDpTargetsWithBusyProtection() {
        isolated {scenario,_->
            val busy=mutableStateOf(false)
            val ratings=mutableListOf<Rating>()
            val density=context.resources.displayMetrics.density
            scenario.onActivity {activity->activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(density,fontScale=1.6f)) {
                    MaterialTheme {StudyRatingBar(busy.value,{ratings.add(it)})}
                }
            }}
            await("rating_bar")
            val nodes=Rating.entries.associateWith {rating->
                compose.onNodeWithTag("rating_${rating.name}").assertIsDisplayed().assertIsEnabled().fetchSemanticsNode()
            }
            nodes.values.forEach {assertTrue("The rating target must be at least 48 dp high",it.boundsInRoot.height>=48f*density-1f)}
            assertEquals(nodes.getValue(Rating.AGAIN).boundsInRoot.top,nodes.getValue(Rating.HARD).boundsInRoot.top,1f)
            assertEquals(nodes.getValue(Rating.GOOD).boundsInRoot.top,nodes.getValue(Rating.EASY).boundsInRoot.top,1f)
            assertTrue(nodes.getValue(Rating.GOOD).boundsInRoot.top>nodes.getValue(Rating.AGAIN).boundsInRoot.bottom)
            assertTrue(nodes.getValue(Rating.HARD).boundsInRoot.left>nodes.getValue(Rating.AGAIN).boundsInRoot.left)
            assertTrue(nodes.getValue(Rating.EASY).boundsInRoot.left>nodes.getValue(Rating.GOOD).boundsInRoot.left)
            compose.runOnIdle {busy.value=true}
            Rating.entries.forEach {compose.onNodeWithTag("rating_${it.name}").assertIsNotEnabled().performClick()}
            assertTrue(ratings.isEmpty())
            compose.runOnIdle {busy.value=false}
            Rating.entries.forEach {click("rating_${it.name}")}
            assertEquals(Rating.entries.toList(),ratings)
            assertEquals(0,number("SELECT COUNT(*) FROM review_log"))
            capture("ui2-large-font-rating-grid")
        }
    }
}

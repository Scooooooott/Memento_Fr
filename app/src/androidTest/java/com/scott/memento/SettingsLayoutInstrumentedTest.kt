package com.scott.memento

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
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

/** UI5 acceptance uses the production Activity and formal content in one guarded, named database. */
class SettingsLayoutInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private val database="memento-test-ui5-settings.db"
    private var bookOrder=emptyList<Book>()
    private val clock=Clock.fixed(Instant.parse("2026-10-10T04:00:00Z"),ZoneOffset.UTC)

    private data class Fixture(
        val target:String,val index:StudyContentIndex,val books:List<Book>,val learned:Set<String>,
    ) {
        val defaultBook get()=books.single {it.id==Languages.get(target).defaultBook}
        val otherBook get()=books.first {it.id!=defaultBook.id}
    }

    private fun systemProperty(key:String)=ProcessBuilder("/system/bin/getprop",key).start().let {process->
        process.inputStream.bufferedReader().use {it.readText().trim()}.also {check(process.waitFor()==0)}
    }
    private fun requireIsolatedEmulator() {
        assertEquals("com.scott.memento",context.packageName)
        check(Build.HARDWARE in setOf("ranchu","goldfish") || Build.FINGERPRINT.contains("generic")) {
            "UI5 database cleanup is restricted to an emulator."
        }
        check(systemProperty("ro.kernel.qemu")=="1") {"Physical devices are not permitted."}
        check(listOf("ro.boot.qemu.avd_name","ro.kernel.qemu.avd_name").any {systemProperty(it)=="MementoApi35"}) {
            "UI5 tests require the dedicated MementoApi35 emulator."
        }
        check(database=="memento-test-ui5-settings.db")
    }
    private fun intent()=Intent(context,MainActivity::class.java)
        .putExtra("qa_user_database",database).putExtra("qa_clock_millis",clock.millis())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun seed(target:String,goal:Int,graded:Int,reviews:Int,progress:Boolean):Fixture {
        ContentCatalog(context,Languages.get(target)).use {catalog->
            val index=catalog.studyIndex("zh-Hans")
            val books=catalog.books()
            val learned=linkedSetOf<String>()
            UserDatabase(context,database).use {store->
                val db=store.writableDatabase
                val dueUids=index.books.getValue(catalog.language.defaultBook).take(reviews)
                dueUids.forEach {uid->
                    learned.add(uid)
                    db.execSQL("INSERT INTO learning_card VALUES(?,?,2,5,?,?,1,0,'REVIEW',1)",
                        arrayOf(target,uid,clock.millis()-1000,clock.millis()-86400000))
                }
                if(progress) {
                    // A valid timestamp is authoritative even with zero repetitions, as with imported cards.
                    index.books.values.flatMap {it.take(4)}.distinct().forEach {uid->
                        learned.add(uid)
                        db.execSQL("INSERT OR REPLACE INTO learning_card VALUES(?,?,2,5,?,?,0,0,'REVIEW',1)",
                            arrayOf(target,uid,clock.millis()+86400000,clock.millis()-86400000))
                    }
                    val unreviewed=index.books.values.flatten().first {it !in learned}
                    db.execSQL("INSERT INTO learning_card VALUES(?,?,0,0,?,NULL,9,0,'NEW',1)",
                        arrayOf(target,unreviewed,clock.millis()))
                    // The other target's history must never contribute to this language's book progress.
                    val other=if(target=="fr") "es" else "fr"
                    val otherUid=ContentCatalog(context,Languages.get(other)).use {it.studyIndex("zh-Hans").available.first()}
                    db.execSQL("INSERT INTO learning_card VALUES(?,?,2,5,?,?,3,0,'REVIEW',1)",
                        arrayOf(other,otherUid,clock.millis()+86400000,clock.millis()-86400000))
                }
                val study=StudyRepository(store,clock)
                AppRepository(store,study,clock).save(index,StudyPolicy(StudyMode.STANDARD,goal),
                    catalog.language.defaultBook,"Asia/Shanghai",PlaybackPreferences(AutoPlay.NEVER,false,true))
                repeat(graded) {ordinal->
                    val before=study.ensureDailyPlan(index)
                    val request=before.request(target,"ui5-seed-$target-$ordinal")
                    assertEquals(ScoreResult.APPLIED,study.reveal(index,request).result)
                    assertEquals(ScoreResult.APPLIED,study.rate(index,request,Rating.GOOD).result)
                    learned.add(checkNotNull(before.next).uid)
                }
            }
            return Fixture(target,index,books,learned)
        }
    }

    private fun isolated(target:String="fr",goal:Int=20,graded:Int=0,reviews:Int=0,progress:Boolean=false,
        block:(ActivityScenario<MainActivity>,Fixture)->Unit) {
        requireIsolatedEmulator()
        context.deleteDatabase(database)
        try {
            val fixture=seed(target,goal,graded,reviews,progress)
            bookOrder=fixture.books
            ActivityScenario.launch<MainActivity>(intent()).use {scenario->await("home_screen");block(scenario,fixture)}
        } catch(error:Throwable) {
            runCatching {capture("ui5-settings-failure")}
            throw error
        } finally {context.deleteDatabase(database)}
    }
    private fun await(tag:String) {
        compose.waitUntil(30000) {
            compose.onAllNodesWithTag(tag,useUnmergedTree=true).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()
        }
        if(tag=="settings_book_title") compose.waitUntil(30000) {
            compose.onAllNodesWithTag("settings_choose_book").fetchSemanticsNodes()
                .any {!it.config.contains(SemanticsProperties.Disabled)}
        }
    }
    private fun click(tag:String) {
        await(tag)
        compose.waitUntil(30000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
            .any {!it.config.contains(SemanticsProperties.Disabled)}}
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
    }
    private fun scrollClick(tag:String) {await(tag);compose.onNodeWithTag(tag).performScrollTo();click(tag)}
    private fun replace(tag:String,value:String) {
        await(tag)
        compose.waitUntil(30000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
            .any {!it.config.contains(SemanticsProperties.Disabled)}}
        compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
        compose.onNodeWithTag(tag).performImeAction()
    }
    private fun settings() {click("nav_settings");await("settings_form");await("settings_book_title")}
    private fun picker() {scrollClick("settings_choose_book");await("book_picker")}
    private fun bookRow(book:Book) {
        val position=bookOrder.indexOfFirst{it.id==book.id}
        assertTrue("Formal book must exist in the rendered order",position>=0)
        compose.onNodeWithTag("book_picker").performScrollToIndex(position)
        compose.onNodeWithTag("book_${book.id}").assertIsDisplayed()
    }
    private fun chooseBook(book:Book) {bookRow(book);click("book_${book.id}")}
    private fun back(scenario:ActivityScenario<MainActivity>) {scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}}
    private fun number(sql:String,args:Array<String> = emptyArray()):Int=UserDatabase(context,database).use {store->
        store.readableDatabase.rawQuery(sql,args).use {cursor->check(cursor.moveToFirst());cursor.getInt(0)}
    }
    private fun rows(sql:String,args:Array<String> = emptyArray()):List<String> = UserDatabase(context,database).use {store->
        store.readableDatabase.rawQuery(sql,args).use {cursor->buildList {
            while(cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") {cursor.getString(it).orEmpty()})
        }}
    }
    private fun persisted()=rows("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        .associateWith {table->rows("SELECT * FROM $table ORDER BY rowid")}
    private fun study(target:String)=listOf("daily_plan","daily_plan_item","plan_revision","learning_card","review_log","extra_review_batch","plan_review_candidate")
        .associateWith {table->
            val filter=if(table=="plan_revision") "plan_id IN (SELECT id FROM daily_plan WHERE target_language=?)" else "target_language=?"
            rows("SELECT * FROM $table WHERE $filter ORDER BY rowid",arrayOf(target))
        }
    private fun logs(target:String)=rows("SELECT * FROM review_log WHERE target_language=? ORDER BY id",arrayOf(target))
    private fun gradedItems(target:String)=rows("SELECT * FROM daily_plan_item WHERE target_language=? AND state='GRADED' ORDER BY id",arrayOf(target))
    private fun cards(target:String)=rows("SELECT * FROM learning_card WHERE target_language=? ORDER BY lexeme_uid",arrayOf(target))
    private fun currentUid(target:String)=rows("SELECT lexeme_uid FROM daily_plan_item WHERE target_language=? AND state='PENDING' AND queue_kind='NORMAL' ORDER BY position LIMIT 1",arrayOf(target)).single()
    private fun planId(target:String)=number("SELECT id FROM daily_plan WHERE target_language=?",arrayOf(target))
    private fun revision(target:String)=number("SELECT revision FROM daily_plan WHERE target_language=?",arrayOf(target))
    private fun word()=compose.onNodeWithTag("study_word").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") {it.text}
    private fun capture(label:String) {
        compose.waitForIdle();android.os.SystemClock.sleep(350)
        val directory=File(context.getExternalFilesDir(null),"qa").apply {mkdirs()}
        val bitmap=instrument.uiAutomation.takeScreenshot()
        FileOutputStream(File(directory,"$label.png")).use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
        File(directory,"$label-tree.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString())
    }

    @Test fun systemBackDiscardsQuantityLanguageAndPlaybackDraftWithoutAnyDatabaseWrites() {
        isolated {scenario,_->
            val before=persisted()
            settings();replace("study_target","31");scrollClick("settings_learning_en")
            scrollClick("autoplay_ALL");scrollClick("show_reading");replace("study_zone","Europe/Paris")
            back(scenario);await("home_screen")
            assertEquals(before,persisted())
            settings()
            compose.onNodeWithTag("study_target").assertTextContains("20")
            compose.onNodeWithTag("settings_learning_zh-Hans").assertIsSelected()
            compose.onNodeWithTag("autoplay_NEVER").assertIsSelected()
            compose.onNodeWithTag("show_reading").assertIsOn()
            compose.onNodeWithTag("study_zone").assertTextContains("Asia/Shanghai")
            click("settings_cancel");await("home_screen");assertEquals(before,persisted())
        }
    }

    private fun formalBookProgress(target:String) {
        isolated(target=target,progress=true) {_,fixture->
            val before=persisted()
            settings()
            val default=fixture.defaultBook
            val learned=fixture.index.books.getValue(default.id).count {it in fixture.learned}
            compose.onNodeWithTag("settings_book_title",useUnmergedTree=true).assertTextEquals(default.title)
            compose.onNodeWithTag("settings_book_description",useUnmergedTree=true).assertTextEquals(default.description)
            compose.onNodeWithTag("settings_book_progress",useUnmergedTree=true).assertTextEquals(context.getString(R.string.book_progress,default.title,learned,default.count))
            compose.onNodeWithTag("settings_book_percent",useUnmergedTree=true).assertTextEquals(context.getString(R.string.home_book_percent,(learned*100L/default.count).toInt()))
            capture("ui5-$target-settings")
            picker()
            assertTrue("Formal content must offer more than one book",fixture.books.size>1)
            fixture.books.forEach {book->
                bookRow(book)
                val count=fixture.index.books.getValue(book.id).count {it in fixture.learned}
                compose.onNodeWithTag("book_${book.id}").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.RadioButton))
                compose.onNodeWithTag("book_radio_${book.id}",useUnmergedTree=true).assertExists()
                compose.onNodeWithTag("book_description_${book.id}",useUnmergedTree=true).assertTextEquals(book.description)
                compose.onNodeWithTag("book_progress_${book.id}",useUnmergedTree=true).assertTextEquals(context.getString(R.string.book_progress,book.title,count,book.count))
                compose.onNodeWithTag("book_percent_${book.id}",useUnmergedTree=true).assertTextEquals(context.getString(R.string.home_book_percent,(count*100L/book.count).toInt()))
                compose.onNodeWithTag("book_indicator_${book.id}",useUnmergedTree=true).assert(SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,ProgressBarRangeInfo(count.toFloat()/book.count,0f..1f)))
                if(book.id==default.id) {
                    compose.onNodeWithTag("book_${book.id}").assertIsSelected()
                    compose.onNodeWithTag("book_current_${book.id}",useUnmergedTree=true).assertExists()
                } else {
                    compose.onNodeWithTag("book_${book.id}").assertIsNotSelected()
                    compose.onNodeWithTag("book_current_${book.id}",useUnmergedTree=true).assertDoesNotExist()
                }
            }
            compose.onNodeWithTag("book_picker").performScrollToIndex(0)
            capture("ui5-$target-books")
            click("cancel_book");click("settings_cancel");await("home_screen")
            assertEquals(before,persisted())
        }
    }
    @Test fun frenchBooksExposeRadioDescriptionsAndAuthoritativeTimestampProgress()=formalBookProgress("fr")
    @Test fun spanishBooksExposeRadioDescriptionsAndLanguageScopedProgress()=formalBookProgress("es")

    @Test fun cancellingBookPickerLeavesAllOtherDraftsAndPersistedBookUnchanged() {
        isolated {_,fixture->
            val before=persisted()
            settings();replace("study_target","33");scrollClick("show_reading")
            picker();chooseBook(fixture.otherBook);click("cancel_book");await("settings_form")
            compose.onNodeWithTag("settings_book_title",useUnmergedTree=true).assertTextEquals(fixture.defaultBook.title)
            compose.onNodeWithTag("study_target").assertTextContains("33")
            compose.onNodeWithTag("show_reading").assertIsOff()
            assertEquals(before,persisted())
            click("settings_cancel");await("home_screen");assertEquals(before,persisted())
        }
    }

    @Test fun confirmingBookOnlyChangesDraftAndCancellingWholeSettingsDoesNotPersistIt() {
        isolated {_,fixture->
            val before=persisted()
            settings();replace("study_target","35")
            picker();chooseBook(fixture.otherBook);click("save_book");await("settings_form")
            compose.onNodeWithTag("settings_book_title",useUnmergedTree=true).assertTextEquals(fixture.otherBook.title)
            compose.onNodeWithTag("study_target").assertTextContains("35")
            assertEquals(before,persisted())
            // The current-use marker refers to committed preference, even when a different draft is selected.
            picker();bookRow(fixture.otherBook);compose.onNodeWithTag("book_${fixture.otherBook.id}").assertIsSelected()
            compose.onNodeWithTag("book_current_${fixture.otherBook.id}",useUnmergedTree=true).assertDoesNotExist()
            bookRow(fixture.defaultBook);compose.onNodeWithTag("book_current_${fixture.defaultBook.id}",useUnmergedTree=true).assertExists()
            click("cancel_book");click("settings_cancel");await("home_screen")
            assertEquals(before,persisted())
        }
    }

    @Test fun recreationKeepsPickerSelectionAndPerTargetDraftsWithoutCommittingEitherLanguage() {
        isolated {scenario,fixture->
            val before=persisted()
            settings();replace("study_target","32");scrollClick("show_reading")
            picker();chooseBook(fixture.otherBook)
            scenario.recreate();await("book_picker");bookRow(fixture.otherBook)
            compose.onNodeWithTag("book_${fixture.otherBook.id}").assertIsSelected()
            click("save_book");await("settings_form")
            scenario.recreate();await("settings_form");await("settings_book_title")
            compose.onNodeWithTag("study_target").assertTextContains("32")
            compose.onNodeWithTag("show_reading").assertIsOff()
            compose.onNodeWithTag("settings_book_title",useUnmergedTree=true).assertTextEquals(fixture.otherBook.title)
            scrollClick("settings_target_es");await("study_target")
            compose.waitUntil(30000) {compose.onAllNodesWithTag("study_target").fetchSemanticsNodes().any {!it.config.contains(SemanticsProperties.Disabled)}}
            compose.onNodeWithTag("study_target").assertTextEquals("",context.getString(R.string.goal_label))
            replace("study_target","11");scrollClick("autoplay_ALL")
            scrollClick("settings_target_fr")
            compose.waitUntil(30000) {compose.onNodeWithTag("study_target").fetchSemanticsNode().config[SemanticsProperties.EditableText].text=="32"}
            compose.onNodeWithTag("show_reading").assertIsOff()
            compose.onNodeWithTag("settings_book_title",useUnmergedTree=true).assertTextEquals(fixture.otherBook.title)
            scrollClick("settings_target_es")
            compose.waitUntil(30000) {compose.onNodeWithTag("study_target").fetchSemanticsNode().config[SemanticsProperties.EditableText].text=="11"}
            compose.onNodeWithTag("autoplay_ALL").assertIsSelected()
            back(scenario);await("home_screen");assertEquals(before,persisted())
        }
    }

    @Test fun importAndSystemPickerCancelReturnToRecreatedSettingsDraftWithoutWrites() {
        isolated {scenario,fixture->
            val before=persisted()
            settings();replace("study_target","34");scrollClick("show_reading")
            picker();chooseBook(fixture.otherBook);click("save_book")
            scrollClick("open_import");await("legacy_import")
            compose.onNodeWithTag("import_choose_file").assertIsDisplayed().assertIsEnabled()
            capture("ui5-import")
            scenario.recreate();await("legacy_import")
            click("import_choose_file")
            val deadline=android.os.SystemClock.uptimeMillis()+15000
            var opened=false
            while(android.os.SystemClock.uptimeMillis()<deadline) {
                if(instrument.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true) {opened=true;break}
                android.os.SystemClock.sleep(100)
            }
            assertTrue("The real system document picker must be reachable from UI5",opened)
            val cancelDeadline=android.os.SystemClock.uptimeMillis()+10000
            var returned=false
            while(android.os.SystemClock.uptimeMillis()<cancelDeadline) {
                val active=instrument.uiAutomation.rootInActiveWindow?.packageName?.toString()
                if(active==context.packageName) {returned=true;break}
                // The picker window can remain cached while MainActivity has already resumed.
                // Never send another Back into the app during that closing transition.
                if(active?.contains("documentsui")==true && scenario.state!=androidx.lifecycle.Lifecycle.State.RESUMED)
                    instrument.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                android.os.SystemClock.sleep(350)
            }
            assertTrue("System picker cancel must return to Memento",returned)
            await("legacy_import");compose.onNodeWithTag("import_preview").assertDoesNotExist()
            click("import_back");await("settings_form");await("settings_book_title")
            compose.onNodeWithTag("study_target").assertTextContains("34")
            compose.onNodeWithTag("show_reading").assertIsOff()
            compose.onNodeWithTag("settings_book_title",useUnmergedTree=true).assertTextEquals(fixture.otherBook.title)
            assertEquals(before,persisted())
            click("settings_cancel");await("home_screen");assertEquals(before,persisted())
        }
    }

    private fun adjustAfterSeven(goal:Int) {
        isolated(goal=20,graded=7) {_,_->
            val id=planId("fr");val oldRevision=revision("fr")
            val oldLogs=logs("fr");val oldGraded=gradedItems("fr");val oldCards=cards("fr")
            settings();replace("study_target",goal.toString());click("study_save");await("home_screen")
            assertEquals(id,planId("fr"));assertTrue(revision("fr")>oldRevision)
            assertEquals(1,number("SELECT COUNT(*) FROM daily_plan WHERE target_language='fr'"))
            assertEquals(goal-7,number("SELECT COUNT(*) FROM daily_plan_item WHERE target_language='fr' AND queue_kind='NORMAL' AND state='PENDING'"))
            assertEquals(7,number("SELECT COUNT(*) FROM daily_plan_item WHERE target_language='fr' AND state='GRADED'"))
            assertEquals(goal,number("SELECT COUNT(*) FROM daily_plan_item WHERE target_language='fr' AND queue_kind='NORMAL' AND state IN('PENDING','GRADED')"))
            assertEquals(oldLogs,logs("fr"));assertEquals(oldGraded,gradedItems("fr"));assertEquals(oldCards,cards("fr"))
            assertEquals(listOf(goal.toString()),rows("SELECT daily_target FROM language_settings WHERE target_language='fr'"))
            compose.onNodeWithTag("home_remaining").assertTextEquals((goal-7).toString())
            if(goal==10) {
                // Lowering below the seven already completed words ends normal work without erasing them.
                settings();replace("study_target","5");click("study_save");await("home_screen")
                assertEquals(0,number("SELECT COUNT(*) FROM daily_plan_item WHERE target_language='fr' AND state='PENDING' AND queue_kind='NORMAL'"))
                compose.onNodeWithTag("open_study").assertIsNotEnabled()
                assertEquals(oldLogs,logs("fr"));assertEquals(oldGraded,gradedItems("fr"));assertEquals(oldCards,cards("fr"))
                settings();replace("study_target","10");click("study_save");await("home_screen")
                assertEquals(id,planId("fr"))
                assertEquals(3,number("SELECT COUNT(*) FROM daily_plan_item WHERE target_language='fr' AND state='PENDING' AND queue_kind='NORMAL'"))
                assertEquals(oldLogs,logs("fr"));assertEquals(7,number("SELECT COUNT(*) FROM review_log"))
            }
        }
    }
    @Test fun reducingTwentyToTenAfterSevenScoresLeavesThreeAndBelowSevenDoesNotEraseProgress()=adjustAfterSeven(10)
    @Test fun raisingTwentyToThirtyAfterSevenScoresAddsOnlyTwentyThreeRemaining()=adjustAfterSeven(30)

    @Test fun sameDayBookChangePreservesGradedAndPendingReviewsAndReplacesOnlyUnscoredNewWords() {
        isolated(goal=10,graded=1,reviews=3) {_,fixture->
            val id=planId("fr");val oldLogs=logs("fr");val oldGraded=gradedItems("fr");val oldCards=cards("fr")
            val reviews=rows("SELECT lexeme_uid FROM daily_plan_item WHERE target_language='fr' AND state='PENDING' AND is_new=0 ORDER BY lexeme_uid")
            assertEquals(2,reviews.size)
            settings();picker();chooseBook(fixture.otherBook);click("save_book");click("study_save");await("home_screen")
            assertEquals(id,planId("fr"));assertEquals(1,number("SELECT COUNT(*) FROM daily_plan"))
            assertEquals(oldLogs,logs("fr"));assertEquals(oldGraded,gradedItems("fr"));assertEquals(oldCards,cards("fr"))
            assertEquals(reviews,rows("SELECT lexeme_uid FROM daily_plan_item WHERE target_language='fr' AND state='PENDING' AND is_new=0 ORDER BY lexeme_uid"))
            val newWords=rows("SELECT lexeme_uid FROM daily_plan_item WHERE target_language='fr' AND state='PENDING' AND is_new=1 ORDER BY position")
            val expected=fixture.index.books.getValue(fixture.otherBook.id).filter {it !in fixture.learned}.take(7)
            assertEquals(expected,newWords)
            assertEquals(9,number("SELECT COUNT(*) FROM daily_plan_item WHERE target_language='fr' AND state='PENDING' AND queue_kind='NORMAL'"))
            assertEquals(listOf(fixture.otherBook.id),rows("SELECT book_id FROM language_settings WHERE target_language='fr'"))
            settings();click("study_save");await("home_screen")
            assertEquals(9,number("SELECT COUNT(*) FROM daily_plan_item WHERE target_language='fr' AND state='PENDING' AND queue_kind='NORMAL'"))
            assertEquals(oldLogs,logs("fr"));assertEquals(id,planId("fr"))
        }
    }

    @Test fun displayOnlyLearningChangeReturnsSameRevealedAnswerAndDoesNotApplyOtherDrafts() {
        isolated(goal=3) {_,_->
            click("open_study");click("study_reveal");await("rating_GOOD")
            val uid=currentUid("fr");val lemma=word();val before=study("fr")
            settings();replace("study_target","41");scrollClick("settings_learning_en");scrollClick("language_apply")
            await("rating_GOOD")
            assertEquals(lemma,word());assertEquals(uid,currentUid("fr"));assertEquals(before,study("fr"))
            compose.onNodeWithTag("study_reveal").assertDoesNotExist()
            assertEquals(listOf("fr|en"),rows("SELECT target_language,learning_language FROM app_settings WHERE id=1"))
            assertEquals(listOf("3"),rows("SELECT daily_target FROM language_settings WHERE target_language='fr'"))
            val sense=ContentCatalog(context,Languages.get("fr")).use {checkNotNull(it.detail(uid)).senses.first()}
            compose.onNodeWithTag("meaning_${sense.id}").assertTextEquals(sense.translations.getValue("en"))
            assertEquals(0,number("SELECT COUNT(*) FROM review_log"))
        }
    }

    @Test fun savedQuantityReturnsToCurrentUnrevealedRevisionAndScoresOnlyItsCurrentItem() {
        isolated(goal=3) {_,_->
            click("open_study");click("study_reveal");await("rating_GOOD")
            val id=planId("fr");val oldRevision=revision("fr");val oldUid=currentUid("fr")
            settings();replace("study_target","4");click("study_save");await("study_reveal")
            compose.onNodeWithTag("rating_GOOD").assertDoesNotExist()
            assertEquals(id,planId("fr"));assertTrue(revision("fr")>oldRevision)
            assertEquals(oldUid,currentUid("fr"))
            assertEquals(0,number("SELECT revealed FROM daily_plan_item WHERE target_language='fr' AND lexeme_uid=?",arrayOf(oldUid)))
            val item=number("SELECT id FROM daily_plan_item WHERE target_language='fr' AND lexeme_uid=?",arrayOf(oldUid))
            click("study_reveal");click("rating_GOOD");await("study_reveal")
            assertEquals(listOf("$id|$item|$oldUid|NORMAL"),rows("SELECT plan_id,item_id,lexeme_uid,kind FROM review_log"))
            assertEquals(1,number("SELECT COUNT(*) FROM review_log"))
            assertEquals(3,number("SELECT COUNT(*) FROM daily_plan_item WHERE target_language='fr' AND state='PENDING' AND queue_kind='NORMAL'"))
            compose.onNodeWithTag("app_error").assertDoesNotExist()
        }
    }

    @Test fun onboardingHasNoImplicitTwentyOrEnglishTargetAndExplicitReviewAllCreatesNoNewQuota() {
        requireIsolatedEmulator();context.deleteDatabase(database)
        try {
            ActivityScenario.launch<MainActivity>(intent()).use {scenario->
                await("onboarding");await("study_target")
                compose.waitUntil(30000) {compose.onAllNodesWithTag("study_target").fetchSemanticsNodes().any {!it.config.contains(SemanticsProperties.Disabled)}}
                compose.onNodeWithTag("study_target").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("")))
                compose.onNodeWithTag("study_save").assertIsNotEnabled()
                listOf("en","en-US","en-GB","eng").forEach {compose.onNodeWithTag("settings_target_$it").assertDoesNotExist()}
                compose.onNodeWithTag("settings_target_fr").assertExists();compose.onNodeWithTag("settings_target_es").assertExists()
                assertEquals(0,number("SELECT COUNT(*) FROM daily_plan"));assertEquals(0,number("SELECT COUNT(*) FROM language_settings"))
                capture("ui5-onboarding")
                replace("study_target","0");compose.onNodeWithTag("study_save").assertIsNotEnabled()
                replace("study_target","2");scrollClick("settings_target_es");scrollClick("settings_learning_en")
                scrollClick("review_all");compose.onNodeWithTag("study_target").assertDoesNotExist()
                scenario.recreate();await("onboarding");await("review_all")
                compose.onNodeWithTag("review_all").assertIsOn();compose.onNodeWithTag("study_target").assertDoesNotExist()
                click("study_save");await("home_screen")
                assertEquals(listOf("es|en"),rows("SELECT target_language,learning_language FROM app_settings WHERE id=1"))
                assertEquals(listOf("es|REVIEW_ALL|"),rows("SELECT target_language,study_mode,daily_target FROM language_settings"))
                assertEquals(listOf("es|REVIEW_ALL|"),rows("SELECT target_language,study_mode,target_snapshot FROM daily_plan"))
                assertEquals(0,number("SELECT COUNT(*) FROM daily_plan_item"));assertEquals(0,number("SELECT COUNT(*) FROM review_log"))
                compose.onNodeWithTag("open_study").assertIsNotEnabled()
            }
        } catch(error:Throwable) {runCatching {capture("ui5-settings-failure")};throw error}
        finally {context.deleteDatabase(database)}
    }

    @Test fun languageVoiceReadingAndEditableZoneDraftSurviveRotationThenSaveAtomically() {
        isolated {scenario,_->
            val id=planId("fr");val oldRevision=revision("fr");val before=persisted()
            settings();scrollClick("settings_learning_en");scrollClick("autoplay_ALL")
            scrollClick("tts_fallback");scrollClick("show_reading");replace("study_zone","Not/AZone")
            compose.onNodeWithTag("study_save").assertIsNotEnabled();assertEquals(before,persisted())
            replace("study_zone","Europe/Paris")
            scenario.recreate();await("settings_form");await("settings_book_title")
            compose.onNodeWithTag("settings_learning_en").assertIsSelected()
            compose.onNodeWithTag("autoplay_ALL").assertIsSelected()
            compose.onNodeWithTag("tts_fallback").assertIsOn();compose.onNodeWithTag("show_reading").assertIsOff()
            compose.onNodeWithTag("study_zone").assertTextContains("Europe/Paris")
            assertEquals(before,persisted())
            click("study_save");await("home_screen")
            assertEquals(listOf("fr|en|Europe/Paris"),rows("SELECT target_language,learning_language,study_zone_id FROM app_settings WHERE id=1"))
            assertEquals(listOf("ALL|1|0"),rows("SELECT auto_play,tts_fallback,show_reading FROM language_settings WHERE target_language='fr'"))
            assertEquals(id,planId("fr"));assertTrue(revision("fr")>oldRevision)
            assertEquals(listOf("Asia/Shanghai"),rows("SELECT zone_id FROM daily_plan WHERE target_language='fr'"))
            assertEquals(0,number("SELECT COUNT(*) FROM review_log"));assertEquals(20,number("SELECT COUNT(*) FROM daily_plan_item WHERE state='PENDING'"))
            settings()
            compose.onNodeWithTag("settings_learning_en").assertIsSelected();compose.onNodeWithTag("autoplay_ALL").assertIsSelected()
            compose.onNodeWithTag("tts_fallback").assertIsOn();compose.onNodeWithTag("show_reading").assertIsOff()
            compose.onNodeWithTag("study_zone").assertTextContains("Europe/Paris")
            click("settings_cancel");await("home_screen")
        }
    }
}

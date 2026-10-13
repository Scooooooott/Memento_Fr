package com.scott.memento

import android.content.Intent
import android.accessibilityservice.AccessibilityService
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import android.view.WindowInsets
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

/** Repeated by the guarded matrix runner after it sets a real device width and font scale. */
class GlobalExperienceInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private val database="memento-test-ui6-global.db"
    private val clock=Clock.fixed(Instant.parse("2026-10-11T04:00:00Z"),ZoneOffset.UTC)
    private var screenshotPrefix="ui6-global"

    private fun property(key:String)=ProcessBuilder("/system/bin/getprop",key).start().let {process->
        process.inputStream.bufferedReader().use {it.readText().trim()}.also {check(process.waitFor()==0)}
    }
    private fun requireIsolatedEmulator() {
        assertEquals("com.scott.memento",context.packageName)
        check(Build.HARDWARE in setOf("ranchu","goldfish") || Build.FINGERPRINT.contains("generic")) {
            "UI6 database cleanup is restricted to an emulator."
        }
        check(property("ro.kernel.qemu")=="1") {"Physical devices are not permitted."}
        check(listOf("ro.boot.qemu.avd_name","ro.kernel.qemu.avd_name").any {property(it)=="MementoApi35"}) {
            "UI6 requires the dedicated MementoApi35 emulator."
        }
        check(database=="memento-test-ui6-global.db")
    }
    private fun await(tag:String)=compose.waitUntil(30000) {
        compose.onAllNodesWithTag(tag,useUnmergedTree=true).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()
    }
    private fun click(tag:String) {
        await(tag)
        compose.waitUntil(30000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
            .any {!it.config.contains(SemanticsProperties.Disabled)}}
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
    }
    private fun scrollClick(tag:String) {
        if(tag=="statistics_window_toggle") compose.onNodeWithTag("statistics").performScrollToIndex(1)
        await(tag);ensureVisible(tag);click(tag)
    }
    private fun ensureVisible(tag:String) {
        val node=compose.onNodeWithTag(tag)
        if(runCatching{node.assertIsDisplayed()}.isFailure) node.performScrollTo()
        node.assertIsDisplayed()
    }
    private fun minTouchHeight(tag:String) {
        compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
        val pixels=compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height
        val minimum=context.resources.displayMetrics.density*48f
        assertTrue("$tag needs at least 48 dp height; pixels=$pixels minimum=$minimum",pixels+1f>=minimum)
    }
    private fun rows(sql:String):List<String> = UserDatabase(context,database).use {store->
        store.readableDatabase.rawQuery(sql,null).use {cursor->buildList {
            while(cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") {cursor.getString(it).orEmpty()})
        }}
    }
    private fun persisted()=rows("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        .associateWith {table->rows("SELECT * FROM $table ORDER BY rowid")}
    private fun capture(page:String) {
        compose.waitForIdle()
        compose.onRoot(useUnmergedTree=true).captureToImage().asAndroidBitmap().recycle()
        val directory=File(context.getExternalFilesDir(null),"qa").apply {mkdirs()}
        val label="$screenshotPrefix-$page"
        val bitmap=checkNotNull(instrument.uiAutomation.takeScreenshot())
        try {
            FileOutputStream(File(directory,"$label.png")).use {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
        } finally {bitmap.recycle()}
        File(directory,"$label-tree.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString())
    }

    private fun flow(target:String,uid:String,lemma:String) {
        requireIsolatedEmulator();context.deleteDatabase(database)
        val configuration=context.resources.configuration
        val width=configuration.screenWidthDp
        val font=configuration.fontScale.toString().replace('.','_')
        screenshotPrefix="ui6-$target-w$width-f$font"
        try {
            ContentCatalog(context,Languages.get(target)).use {catalog->
                val formal=checkNotNull(catalog.detail(uid))
                assertEquals(lemma,formal.summary.lemma)
                assertTrue("Audio failure must use actual content without a recording",formal.readings.none {it.audioAsset!=null})
                assertTrue("The matrix must visit real nonempty verb grammar",formal.paradigms.isNotEmpty())
                UserDatabase(context,database).use {store->
                    store.writableDatabase.execSQL("INSERT INTO learning_card VALUES(?,?,2,5,?,?,1,0,'REVIEW',1)",
                        arrayOf(target,uid,clock.millis()-1000,clock.millis()-86400000))
                    val study=StudyRepository(store,clock)
                    AppRepository(store,study,clock).save(catalog.studyIndex("zh-Hans"),StudyPolicy(StudyMode.STANDARD,2),
                        catalog.language.defaultBook,"Asia/Shanghai",PlaybackPreferences(AutoPlay.NEVER,false,true))
                }
            }
            val intent=Intent(context,MainActivity::class.java).putExtra("qa_user_database",database)
                .putExtra("qa_clock_millis",clock.millis()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<MainActivity>(intent).use {scenario->
                try {
                await("home_screen")
                compose.onNodeWithTag("main_navigation").assertIsDisplayed()
                capture("home")
                scrollClick("open_study");await("study_word")
                compose.onNodeWithTag("study_word").assertTextEquals(lemma)
                compose.onNodeWithTag("main_navigation").assertDoesNotExist()
                minTouchHeight("study_reveal")

                // A normal unsupported-audio request uses the production player and does not move the card.
                compose.onNodeWithTag("study_speak").performScrollTo()
                val cardBefore=compose.onNodeWithTag("study_card").fetchSemanticsNode().boundsInRoot
                click("study_speak");await("speech_status")
                compose.onNodeWithTag("speech_status",useUnmergedTree=true)
                    .assertTextEquals(context.getString(R.string.speech_no_recording)).assertIsDisplayed()
                minTouchHeight("speech_dismiss")
                val cardAfter=compose.onNodeWithTag("study_card").fetchSemanticsNode().boundsInRoot
                assertEquals(cardBefore.top,cardAfter.top,1f)
                assertEquals(cardBefore.bottom,cardAfter.bottom,1f)
                compose.onNodeWithTag("study_reveal").assertIsDisplayed().assertIsEnabled()
                capture("speech")
                click("speech_dismiss")
                compose.onNodeWithTag("speech_status",useUnmergedTree=true).assertDoesNotExist()

                click("study_reveal");await("rating_GOOD")
                Rating.entries.forEach {minTouchHeight("rating_${it.name}")}
                capture("study")
                val afterReveal=persisted()

                scrollClick("study_details");await("word_detail")
                compose.onNodeWithTag("word_detail_title").assertTextEquals(lemma)
                compose.onNodeWithTag("detail_tab_meanings").assertIsSelected()
                listOf("detail_tab_meanings","detail_tab_forms","detail_tab_grammar").forEach {
                    ensureVisible(it);minTouchHeight(it)
                }
                scrollClick("detail_tab_forms");await("detail_forms")
                compose.onNodeWithTag("detail_tab_forms").assertIsSelected()
                scrollClick("detail_tab_grammar");await("grammar_rows")
                compose.onNodeWithTag("detail_tab_grammar").assertIsSelected()
                compose.onNodeWithTag("grammar_rows").assertIsDisplayed()
                assertTrue("Grammar needs a usable vertical viewport at large text",
                    compose.onNodeWithTag("grammar_rows").fetchSemanticsNode().boundsInRoot.height>=context.resources.displayMetrics.density*96f)
                compose.onNodeWithTag("grammar_horizontal_header").performTouchInput {swipeLeft()}
                assertTrue("The wide formal table must be horizontally reachable",
                    compose.onNodeWithTag("grammar_horizontal_header").fetchSemanticsNode()
                        .config[SemanticsProperties.HorizontalScrollAxisRange].value()>0f)
                capture("grammar")
                scrollClick("detail_tab_meanings");await("detail_meanings")
                scrollClick("detail_speak");await("speech_status")
                click("detail_back");await("rating_GOOD")
                compose.onNodeWithTag("speech_status",useUnmergedTree=true).assertDoesNotExist()
                compose.onNodeWithTag("study_word").assertTextEquals(lemma)

                click("nav_settings");await("settings_form")
                scrollClick("settings_choose_book");await("book_picker")
                compose.onNodeWithTag("book_picker").assertIsDisplayed()
                minTouchHeight("save_book");minTouchHeight("cancel_book")
                capture("books")
                click("cancel_book");await("settings_form")
                compose.onNodeWithTag("study_target").performScrollTo().performTextReplacement("3")
                compose.onNodeWithTag("study_target").performImeAction()
                minTouchHeight("study_save");minTouchHeight("settings_cancel")
                scrollClick("settings_learning_en")
                capture("settings")
                click("settings_cancel");await("rating_GOOD")
                compose.onNodeWithTag("study_word").assertTextEquals(lemma)
                assertEquals(afterReveal,persisted())

                click("study_pause");await("home_screen")
                click("open_catalog");await("search")
                compose.onNodeWithTag("search").performTextReplacement(lemma)
                compose.onNodeWithTag("search").performImeAction()
                await("word_$uid")
                click("search_clear")
                compose.onNodeWithTag("search").assert(SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("")))
                // Enter another query and dismiss the real IME through Android Back before navigating.
                compose.onNodeWithTag("search").performClick().performTextInput(lemma)
                compose.waitUntil(10000) {
                    var visible=false
                    scenario.onActivity {visible=it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true}
                    visible
                }
                assertTrue(instrument.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                compose.waitUntil(10000) {
                    var visible=true
                    scenario.onActivity {visible=it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true}
                    !visible
                }
                await("word_$uid")
                compose.onNodeWithTag("search").assertIsDisplayed()
                compose.onNodeWithTag("word_$uid").assertIsDisplayed()
                capture("library")
                click("nav_statistics");await("statistics")
                compose.onNodeWithTag("main_navigation").assertIsDisplayed()
                capture("stats7")
                scrollClick("statistics_window_toggle")
                capture("stats30")
                click("nav_home");await("home_screen")
                assertEquals(afterReveal,persisted())
                assertEquals(emptyList<String>(),rows("SELECT * FROM review_log"))
                assertEquals(listOf("2"),rows("SELECT COUNT(*) FROM daily_plan_item WHERE state='PENDING'"))
                compose.onNodeWithTag("app_error").assertDoesNotExist()
                compose.onNodeWithTag("speech_status",useUnmergedTree=true).assertDoesNotExist()
                } catch(error:Throwable) {runCatching {capture("failure")};throw error}
            }
        } finally {context.deleteDatabase(database)}
    }

    @Test fun frenchEssentialPagesAndAudioNoticeRemainOperableAtDeviceConfiguration()=flow("fr","fr:être:verb:1","être")
    @Test fun spanishEssentialPagesAndAudioNoticeRemainOperableAtDeviceConfiguration()=flow("es","es:ser:verb:1","ser")

    private fun configuredPrefix(target:String) {
        val config=context.resources.configuration
        screenshotPrefix="ui6-$target-w${config.screenWidthDp}-f${config.fontScale.toString().replace('.','_')}"
    }
    private fun launchForm(block:(ActivityScenario<MainActivity>)->Unit) {
        val intent=Intent(context,MainActivity::class.java).putExtra("qa_user_database",database)
            .putExtra("qa_clock_millis",clock.millis()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {scenario->
            try {block(scenario)} catch(error:Throwable) {runCatching{capture("form-failure")};throw error}
        }
    }
    @Test fun onboardingBothLanguageDraftsAndFixedSaveRemainReachableWithoutWritingData() {
        requireIsolatedEmulator();context.deleteDatabase(database)
        try {
            UserDatabase(context,database).use{it.writableDatabase.execSQL("UPDATE app_settings SET study_zone_id='Asia/Shanghai'")}
            val before=persisted()
            launchForm {
                await("onboarding")
                compose.onNodeWithTag("study_save").assertIsNotEnabled()
                for(target in listOf("fr","es")) {
                    configuredPrefix(target)
                    scrollClick("settings_target_$target")
                    await("study_target")
                    compose.waitUntil(30000){compose.onAllNodesWithTag("study_target").fetchSemanticsNodes().any{!it.config.contains(SemanticsProperties.Disabled)}}
                    compose.onNodeWithTag("study_target").performScrollTo().performTextReplacement("3")
                    compose.onNodeWithTag("study_target").performImeAction()
                    scrollClick("settings_learning_en")
                    scrollClick("settings_choose_book");await("book_picker")
                    minTouchHeight("save_book");minTouchHeight("cancel_book")
                    click("cancel_book");await("onboarding")
                    compose.onNodeWithTag("study_zone").performScrollTo().assertIsDisplayed()
                    minTouchHeight("study_save")
                    capture("onboarding")
                }
                assertEquals(before,persisted())
                compose.onNodeWithTag("settings_target_en").assertDoesNotExist()
            }
        } finally {context.deleteDatabase(database)}
    }
    @Test fun completedSummaryAndImportEntryRemainReachableAndCancelPreservesAllTables() {
        requireIsolatedEmulator()
        for(target in listOf("fr","es")) {
            context.deleteDatabase(database);configuredPrefix(target)
            try {
                ContentCatalog(context,Languages.get(target)).use {catalog->UserDatabase(context,database).use {store->
                    val index=catalog.studyIndex("zh-Hans")
                    val study=StudyRepository(store,clock)
                    var snapshot=AppRepository(store,study,clock).save(index,StudyPolicy(StudyMode.STANDARD,2),
                        catalog.language.defaultBook,"Asia/Shanghai",PlaybackPreferences(AutoPlay.NEVER,false))
                    repeat(2) {ordinal->
                        val request=snapshot.request(target,"ui6-form-$target-$ordinal")
                        assertEquals(ScoreResult.APPLIED,study.reveal(index,request).result)
                        val result=study.rate(index,request,Rating.GOOD)
                        assertEquals(ScoreResult.APPLIED,result.result);snapshot=result.snapshot
                    }
                }}
                val before=persisted()
                launchForm {
                    await("home_screen");scrollClick("home_recent_summary");await("summary_screen")
                    compose.onNodeWithTag("summary_count").assertTextEquals(context.getString(R.string.summary_word_total,2))
                    compose.onNodeWithTag("summary_home").performScrollTo();minTouchHeight("summary_home")
                    capture("summary")
                    click("language_context");await("settings_form")
                    scrollClick("open_import");await("legacy_import")
                    compose.onNodeWithTag("import_choose_file").performScrollTo();minTouchHeight("import_choose_file")
                    minTouchHeight("import_back");capture("import")
                    click("import_back");await("settings_form");click("settings_cancel");await("summary_screen")
                    compose.onNodeWithTag("summary_home").performScrollTo();click("summary_home");await("home_screen")
                    assertEquals(before,persisted())
                }
            } finally {context.deleteDatabase(database)}
        }
    }
}

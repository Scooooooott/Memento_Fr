package com.scott.memento

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.memento.data.user.UserDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class MementoUiInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="memento-test-p5-ui.db"
    private fun intent()=Intent(context,MainActivity::class.java).putExtra("qa_user_database",name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    private fun await(tag:String) {
        try {compose.waitUntil(30000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
        catch(e:Throwable) {
            val directory=File(context.getExternalFilesDir(null),"qa").apply{mkdirs()}
            File(directory,"p5-missing-${tag.replace(':','_')}.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString())
            screenshot("p5-missing-${tag.replace(':','_')}")
            throw e
        }
    }
    private fun click(tag:String){await(tag);compose.waitUntil(30000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any{!it.config.contains(SemanticsProperties.Disabled)}};compose.onNodeWithTag(tag).performClick()}
    private fun goal(value:String){compose.waitUntil(30000){compose.onAllNodesWithTag("study_target").fetchSemanticsNodes().any{!it.config.contains(SemanticsProperties.Disabled)}};compose.onNodeWithTag("study_target").performScrollTo().performTextReplacement(value)}
    private fun number(sql:String)=UserDatabase(context,name).use{store->store.readableDatabase.rawQuery(sql,null).use{it.moveToFirst();it.getInt(0)}}
    private fun text(sql:String)=UserDatabase(context,name).use{store->store.readableDatabase.rawQuery(sql,null).use{it.moveToFirst();it.getString(0)}}
    private fun onboard(value:String="2") {await("onboarding");goal(value);click("study_save");await("open_study")}
    private fun selectPair(target:String?=null,learning:String?=null) {
        click("language_context");await("settings_form")
        target?.let{compose.onNodeWithTag("settings_target_$it").performScrollTo();click("settings_target_$it")}
        learning?.let{compose.onNodeWithTag("settings_learning_$it").performScrollTo();click("settings_learning_$it")}
        compose.onNodeWithTag("language_apply").performScrollTo();click("language_apply")
    }
    private fun assertPair(target:String,learning:String) {
        await("language_context")
        val targetLabel=context.getString(if(target=="fr") R.string.french else R.string.spanish)
        val learningLabel=context.getString(when(learning){"fr"->R.string.french;"es"->R.string.spanish;"en"->R.string.english;else->R.string.chinese})
        compose.onNodeWithTag("language_context").assertTextEquals(context.getString(R.string.language_context_format,targetLabel,learningLabel))
        assertEquals(target,text("SELECT target_language FROM app_settings WHERE id=1"))
        assertEquals(learning,text("SELECT learning_language FROM app_settings WHERE id=1"))
    }
    private fun screenshot(name:String) {
        compose.waitForIdle();android.os.SystemClock.sleep(350)
        val instrument=InstrumentationRegistry.getInstrumentation()
        val directory=File(context.getExternalFilesDir(null),"qa").apply{mkdirs()}
        val bitmap=instrument.uiAutomation.takeScreenshot()
        FileOutputStream(File(directory,"$name.png")).use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun bookPickerCancelSaveAndRecreationPreserveOtherDraftsAndAdjustOnlyRemainder() {
        context.deleteDatabase(name)
        try {ActivityScenario.launch<MainActivity>(intent()).use {scenario->
            onboard();click("open_study");click("study_reveal");click("rating_GOOD");await("study_reveal")
            click("nav_settings");await("study_target");goal("4")
            compose.onNodeWithTag("settings_choose_book").performScrollTo().performClick();await("book_picker")
            compose.onNodeWithTag("book_essential-fr").performClick()
            scenario.recreate();await("book_picker");click("cancel_book")
            compose.onNodeWithTag("study_target").performScrollTo().assertTextContains("4")
            assertEquals(2,number("SELECT daily_target FROM language_settings WHERE target_language='fr'"))
            compose.onNodeWithTag("settings_choose_book").performScrollTo().performClick();await("book_picker")
            compose.onNodeWithTag("book_essential-fr").performClick();click("save_book")
            click("settings_cancel");await("study_reveal")
            assertEquals(2,number("SELECT daily_target FROM language_settings WHERE target_language='fr'"))
            assertEquals("flelex-a1-high-1000",text("SELECT book_id FROM language_settings WHERE target_language='fr'"))
            click("nav_settings");await("study_target");goal("4");click("study_save");await("study_reveal")
            assertEquals(4,number("SELECT daily_target FROM language_settings WHERE target_language='fr'"))
            assertEquals(3,number("SELECT COUNT(*) FROM daily_plan_item WHERE queue_kind='NORMAL' AND state='PENDING'"))
            assertEquals(1,number("SELECT COUNT(*) FROM review_log"));assertEquals(1,number("SELECT COUNT(*) FROM daily_plan"))
            screenshot("p5-settings-goal-adjusted")
        }} finally {context.deleteDatabase(name)}
    }
    @Test fun firstPairPersistsFavoritesStatsAndUnavailableAudioAreVisibleAndIsolated() {
        context.deleteDatabase(name)
        try {
            ActivityScenario.launch<MainActivity>(intent()).use {scenario->
                await("onboarding");click("settings_target_es");click("settings_learning_fr");click("review_all");click("study_save");await("open_study")
                assertEquals(0,number("SELECT COUNT(*) FROM daily_plan_item"))
                scenario.recreate();await("open_study");assertPair("es","fr")
                click("nav_settings");await("settings_form")
                compose.onNodeWithTag("tts_fallback").performScrollTo().performClick();click("study_save")
                click("open_catalog");await("search");compose.onNodeWithTag("search").performTextInput("ser");click("word_es:ser:verb:1");await("word_detail")
                click("detail_favorite");compose.waitUntil(30000){number("SELECT COUNT(*) FROM favorites WHERE target_language='es'")==1}
                click("detail_speak");await("speech_status");compose.onNodeWithTag("speech_status").assertTextContains("暂无本地录音",substring=true)
                screenshot("p5-es-favorite-audio")
                click("detail_back");await("search");click("nav_favorites");await("word_es:ser:verb:1")
                selectPair(target="fr");compose.waitUntil(30000){compose.onAllNodesWithTag("result_count").fetchSemanticsNodes().any{it.config.toString().contains("0 个词条")}}
                compose.onNodeWithTag("word_es:ser:verb:1").assertDoesNotExist()
                selectPair(target="es");await("word_es:ser:verb:1");selectPair(learning="fr");await("word_es:ser:verb:1");click("nav_statistics");await("statistics")
                compose.onNodeWithTag("statistics_learned_value",useUnmergedTree=true).assertTextEquals("0");compose.onNodeWithTag("statistics").performScrollToIndex(10);compose.onNodeWithText("累计已学 0 词").assertExists();screenshot("p5-statistics")
            }
            // New Activity and controller reload persisted pair instead of defaulting to French.
            ActivityScenario.launch<MainActivity>(intent()).use {await("open_study");assertPair("es","fr")}
        } finally {context.deleteDatabase(name)}
    }
}

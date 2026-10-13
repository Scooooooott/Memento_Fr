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

class StudyUiInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun realLearningFlowSavesProgressRestoresAndSwitchesLanguageWithoutNewQuota() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        val name="memento-test-ui.db"
        context.deleteDatabase(name)
        val intent=Intent(context,MainActivity::class.java).putExtra("qa_user_database",name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                await("onboarding")
                awaitEnabled("study_target")
                compose.onNodeWithTag("study_target").performTextInput("2")
                awaitEnabled("study_save")
                compose.onNodeWithTag("study_save").performClick()
                await("open_study");compose.onNodeWithTag("open_study").performScrollTo().performClick()
                await("study_reveal")
                val first=word()
                compose.onNodeWithTag("study_reveal").performClick()
                await("rating_GOOD")
                applyLanguage(learning="en")
                awaitEnabled("rating_GOOD");assertEquals(first,word())
                screenshot("p5-learning-answer")
                compose.onNodeWithTag("rating_GOOD").assertIsDisplayed().assertIsEnabled().performClick()
                await("study_reveal")
                val second=word();assertNotEquals(first,second)
                scenario.recreate()
                await("study_reveal");assertEquals(second,word())
                compose.onNodeWithTag("study_reveal").performClick();await("rating_GOOD")
                compose.onNodeWithTag("rating_GOOD").assertIsDisplayed().assertIsEnabled().performClick();await("summary_screen")
                screenshot("p5-learning-completed")
                applyLanguage(target="es")
                await("study_configure")
                applyLanguage(target="fr");await("home_screen")
                awaitEnabled("home_recent_summary");compose.onNodeWithTag("home_recent_summary").performScrollTo().performClick();await("study_finished")
                UserDatabase(context,name).use { store ->
                    store.readableDatabase.rawQuery("SELECT COUNT(*) FROM review_log WHERE target_language='fr'",null).use{it.moveToFirst();assertEquals(2,it.getInt(0))}
                    store.readableDatabase.rawQuery("SELECT COUNT(*) FROM daily_plan WHERE target_language='fr'",null).use{it.moveToFirst();assertEquals(1,it.getInt(0))}
                }
            }
        } catch(e:Throwable) {
            runCatching {
                screenshot("p5-ui-failure")
                File(context.getExternalFilesDir(null),"qa/p5-ui-failure-tree.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString())
            }
            throw e
        } finally { context.deleteDatabase(name) }
    }
    private fun applyLanguage(target:String?=null,learning:String?=null) {
        awaitEnabled("language_context");compose.onNodeWithTag("language_context").performClick();await("settings_form")
        if(target!=null){awaitEnabled("settings_target_$target");compose.onNodeWithTag("settings_target_$target").performScrollTo().performClick()}
        if(learning!=null){awaitEnabled("settings_learning_$learning");compose.onNodeWithTag("settings_learning_$learning").performScrollTo().performClick()}
        awaitEnabled("language_apply");compose.onNodeWithTag("language_apply").performScrollTo().performClick()
    }
    private fun await(tag:String)=compose.waitUntil(30000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
    private fun awaitEnabled(tag:String)=compose.waitUntil(30000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any{!it.config.contains(SemanticsProperties.Disabled)}}
    private fun word()=compose.onNodeWithTag("study_word").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(""){it.text}
    private fun screenshot(name:String) {
        compose.waitForIdle();android.os.SystemClock.sleep(350)
        val instrument=InstrumentationRegistry.getInstrumentation()
        val directory=File(instrument.targetContext.getExternalFilesDir(null),"qa").apply{mkdirs()}
        val bitmap=instrument.uiAutomation.takeScreenshot()
        FileOutputStream(File(directory,"$name.png")).use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
}

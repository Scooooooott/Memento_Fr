package com.scott.memento

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.memento.qa.ExtensionFixtureActivity
import com.scott.memento.ui.WordDetailContent
import com.scott.memento.ui.WordReadings
import com.scott.memento.ui.Meanings
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class ExtensionUiInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private fun screenshot(label:String) {
        compose.waitForIdle();android.os.SystemClock.sleep(350)
        val directory=File(instrument.targetContext.getExternalFilesDir(null),"qa").apply{mkdirs()}
        val bitmap=instrument.uiAutomation.takeScreenshot()
        FileOutputStream(File(directory,"$label.png")).use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun realDetailRendererDisplaysKanaJlptNewTranslationAndTwoRowGrammar() {
        ExtensionFixture.read("ja"){reader,_->
            val word=reader.detail("ja:fixture:0:1")!!
            ActivityScenario.launch<ExtensionFixtureActivity>(Intent(instrument.targetContext,ExtensionFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use{scenario->
                val show=mutableStateOf(true);val learning=mutableStateOf("de")
                scenario.onActivity{it.setContent{MaterialTheme{Surface(Modifier.fillMaxSize()){WordDetailContent(word,learning.value,show.value,Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding())}}}}
                compose.onNodeWithTag("reading_KANA_ja-JP").assertTextContains("たべる",substring=true)
                compose.onNodeWithTag("reading_ROMAJI_ja-JP").assertTextContains("taberu",substring=true)
                compose.onNodeWithTag("detail_metadata").assertTextContains("fixture",substring=true)
                    .assertTextContains("JLPT N5",substring=true)
                compose.onNodeWithText("essen").assertExists()
                screenshot("p7-ja-readings")
                compose.onNodeWithTag("detail_tab_grammar").performClick()
                compose.onNodeWithTag("grammar_table").assertExists()
                compose.onNodeWithTag("grammar_cell_fixture-paradigm_polite").assert(
                    hasAnyDescendant(hasText("礼貌体")) and hasAnyDescendant(hasText("食べます")))
                compose.onNodeWithTag("grammar_cell_fixture-paradigm_plain").assert(
                    hasAnyDescendant(hasText("普通体")) and hasAnyDescendant(hasText("食べる")))
                screenshot("ui3-ja-grammar")
                compose.runOnIdle{show.value=false;learning.value="en"}
                compose.onNodeWithTag("reading_KANA_ja-JP").assertDoesNotExist();compose.onNodeWithTag("reading_ROMAJI_ja-JP").assertDoesNotExist()
                compose.onNodeWithTag("detail_tab_grammar").assertIsSelected()
                compose.onNodeWithTag("detail_tab_meanings").performClick()
                compose.onNodeWithText("eat").assertExists()
            }
        }
    }
    @Test fun sharedStudyReadingAndMeaningComponentsDisplayKoreanWithoutIpa() {
        ExtensionFixture.read("ko"){reader,_->val word=reader.detail("ko:fixture:0:1")!!
            ActivityScenario.launch<ExtensionFixtureActivity>(Intent(instrument.targetContext,ExtensionFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use{scenario->
                scenario.onActivity{it.setContent{MaterialTheme{Surface(Modifier.fillMaxSize()){Column(Modifier.fillMaxSize().statusBarsPadding()){WordReadings(word);Meanings(word,"de")}}}}}
                compose.onNodeWithTag("reading_HANGUL_ko-KR").assertTextContains("먹다",substring=true)
                compose.onNodeWithTag("reading_ROMANIZATION_ko-KR").assertTextContains("meokda",substring=true)
                compose.onNodeWithText("essen").assertExists();screenshot("p7-ko-study-components")
            }
        }
    }
}

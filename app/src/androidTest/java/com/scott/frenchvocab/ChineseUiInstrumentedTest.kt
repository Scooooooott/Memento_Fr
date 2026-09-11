package com.scott.frenchvocab

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.frenchvocab.data.content.ContentRepository
import com.scott.frenchvocab.domain.*
import com.scott.frenchvocab.feature.BrowseScreen
import com.scott.frenchvocab.feature.StudyScreen
import com.scott.frenchvocab.feature.WordDetailScreen
import com.scott.frenchvocab.ui.theme.FrenchVocabTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChineseUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun word(fixture: String = "chinese"): Lexeme = ContentRepository(instrumentation.targetContext,
        instrumentation.context.assets.open("content-fixtures/$fixture.db").use { it.readBytes() }
    ).use { it.find("fr:être:verb:1")!! }
    private val chineseOnly = UserSettings(showChinese = true, showEnglish = false, showSpanish = false)

    @Test fun chineseAnswerUsesSelectedLanguageAndKeepsCoreSenseLimit() {
        val base = word()
        val expanded = base.copy(senses = base.senses + Sense("second", "segundo", "第二义项") + Sense("third", "tercero", "第三义项"))
        val session = StudySession(1, listOf(SessionItem(base.uid, true)), 0, true, 1, settings = chineseOnly)
        compose.setContent { FrenchVocabTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            StudyScreen(expanded, session, false, false, {}, {}, {}, {})
        } } }
        compose.onAllNodesWithTag("sense_zh").assertCountEquals(2)
        compose.onAllNodesWithTag("sense_en").assertCountEquals(0)
        compose.onAllNodesWithTag("sense_es").assertCountEquals(0)
        compose.onNodeWithText("第三义项", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("example_zh").assertTextContains("我在家。", substring = true)
        compose.onAllNodesWithTag("example_en").assertCountEquals(0)
        screenshot("10-chinese-answer")
    }

    @Test fun detailShowsAllThreeLanguagesInOrderAndAllSenses() {
        val base = word()
        val expanded = base.copy(senses = base.senses + Sense("second", "segundo", "第二义项") + Sense("third", "tercero", "第三义项"))
        compose.setContent { FrenchVocabTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            WordDetailScreen(expanded, UserSettings(), false, false, {}, {})
        } } }
        compose.onAllNodesWithTag("sense_zh").assertCountEquals(3)
        compose.onAllNodesWithTag("sense_en").assertCountEquals(3)
        compose.onAllNodesWithTag("sense_es").assertCountEquals(3)
        val zh = compose.onAllNodesWithTag("sense_zh").onFirst().fetchSemanticsNode().boundsInRoot.top
        val en = compose.onAllNodesWithTag("sense_en").onFirst().fetchSemanticsNode().boundsInRoot.top
        val es = compose.onAllNodesWithTag("sense_es").onFirst().fetchSemanticsNode().boundsInRoot.top
        assertTrue(zh < en && en < es)
        compose.onNodeWithTag("example_zh").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("example_en").assertExists()
        compose.onNodeWithTag("example_es").assertExists()
        screenshot("11-chinese-detail")
    }

    @Test fun missingChineseShowsExplicitMessagesWithoutOtherLanguages() {
        val legacy = word("legacy")
        compose.setContent { FrenchVocabTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            WordDetailScreen(legacy, chineseOnly, false, false, {}, {})
        } } }
        compose.onNodeWithTag("sense_missing").assertTextEquals("所选语言的释义暂未收录")
        compose.onNodeWithTag("example_missing").assertTextEquals("所选语言的译文暂未收录")
        compose.onAllNodesWithTag("sense_en").assertCountEquals(0)
        compose.onAllNodesWithTag("sense_es").assertCountEquals(0)
        compose.onAllNodesWithTag("example_en").assertCountEquals(0)
        compose.onNodeWithText(legacy.examples.first().french).assertExists()
    }

    @Test fun chineseSearchFindsWordAndPreviewRespectsLanguageChoice() {
        val chinese = word()
        val other = chinese.copy(uid = "qa:other", lemma = "avoir", senses = listOf(Sense("to have", "tener", "有")))
        val snapshot = AppSnapshot(chineseOnly, null, StudyStats())
        compose.setContent { FrenchVocabTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            BrowseScreen(listOf(chinese, other), emptyList(), snapshot, "", {}, {})
        } } }
        compose.onNodeWithTag("word_search").performTextInput("处于")
        compose.onNodeWithTag("word_${chinese.uid}").assertExists()
        compose.onNodeWithTag("word_${other.uid}").assertDoesNotExist()
        compose.onNodeWithTag("browse_meaning_${chinese.uid}", useUnmergedTree = true).assertTextEquals("中文  是；处于")
        screenshot("12-chinese-search")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(350)
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "qa").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

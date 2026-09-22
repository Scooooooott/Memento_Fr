package com.scott.frenchvocab

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.frenchvocab.data.content.ContentRepository
import com.scott.frenchvocab.data.user.StudyRepository
import com.scott.frenchvocab.domain.AutoPlayMode
import com.scott.frenchvocab.domain.UserSettings
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppFlowInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before fun setup() {
        context.deleteDatabase("french_user.db")
        ContentRepository(context).use { content ->
            StudyRepository(context, content).use { repo ->
                repo.saveSettings(UserSettings(dailyNewLimit = 2, autoPlay = AutoPlayMode.NEVER, ttsFallback = false))
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitTag("start_study")
    }
    @After fun cleanup() { scenario.close() }

    @Test fun completeLearningFlowWithRotationDetailAndSettings() {
        screenshot("01-home")
        compose.onNodeWithTag("start_study").performClick()
        awaitTag("reveal_answer")
        compose.onAllNodesWithTag("sense_en").assertCountEquals(0)
        compose.onNodeWithTag("study_position").assertTextEquals("1 / 2")
        screenshot("02-question")
        compose.onNodeWithTag("reveal_blank_space").performScrollTo().performTouchInput { click(center) }
        awaitTag("rate_AGAIN")
        compose.onAllNodesWithTag("sense_en").onFirst().assertExists()
        scenario.recreate()
        awaitTag("rate_AGAIN")
        compose.onNodeWithTag("study_position").assertTextEquals("1 / 2")
        screenshot("03-answer")
        compose.onNodeWithTag("open_word_detail").performScrollTo().performClick()
        awaitTag("word_detail")
        compose.onNodeWithTag("detail_tab_2").performClick()
        screenshot("04-conjugation")
        compose.onNodeWithTag("back").performClick()
        awaitTag("rate_AGAIN")
        compose.onNodeWithTag("rate_AGAIN").performClick()
        awaitTag("reveal_answer")
        compose.onNodeWithTag("study_position").assertTextEquals("2 / 2")
        compose.onAllNodesWithTag("sense_en").assertCountEquals(0)
        compose.onNodeWithTag("reveal_answer").performSemanticsAction(SemanticsActions.OnClick) { it() }
        awaitTag("rate_EASY")
        compose.onNodeWithTag("rate_EASY").performClick()
        awaitTag("session_summary")
        compose.onNodeWithTag("summary_count").assertTextEquals("本轮完成 2 个词条")
        screenshot("05-summary")
        compose.onNodeWithTag("nav_home").performScrollTo().performClick()
        awaitTag("start_study")
        compose.onNodeWithTag("start_study").assertIsNotEnabled()
        compose.onNodeWithTag("today_completed").assertTextEquals("今日完成 2")
        compose.onNodeWithTag("nav_settings").performClick()
        awaitTag("daily_new_limit")
        compose.onNodeWithTag("daily_new_limit").performTextReplacement("3")
        compose.onNodeWithTag("daily_new_limit").performImeAction()
        compose.onNodeWithTag("save_settings").performScrollTo()
        screenshot("06-settings")
        compose.onNodeWithTag("save_settings").performClick()
        awaitTag("start_study")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("start_study") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("start_study").performClick()
        awaitTag("reveal_answer")
        compose.onNodeWithTag("study_position").assertTextEquals("1 / 1")
        compose.onNodeWithTag("reveal_blank_space").performScrollTo().performTouchInput { click(center) }
        awaitTag("rate_GOOD")
    }

    @Test fun searchFavoritesTabsAndMissingAudioAreFunctional() {
        compose.onNodeWithTag("nav_words").performClick()
        awaitTag("word_search")
        compose.onNodeWithTag("word_search").performTextInput("etre")
        awaitTag("browse_list_etre")
        // Larger books add matching words before être; it may start off-screen.
        compose.onNodeWithTag("browse_list_etre")
            .performScrollToNode(hasTestTag("word_fr:être:verb:1"))
        compose.onNodeWithTag("word_fr:être:verb:1").performClick()
        awaitTag("word_detail")
        compose.onNodeWithTag("toggle_favorite").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("★ 已收藏").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("detail_tab_1").performClick()
        compose.onAllNodesWithText("avoir", substring = false).onFirst().assertExists()
        compose.onNodeWithTag("detail_tab_2").performClick()
        compose.onNodeWithText("je suis").assertExists()
        compose.onNodeWithTag("play_audio").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("此词暂无本地录音", substring = true).fetchSemanticsNodes().isNotEmpty() }
        screenshot("07-audio-unavailable")
        compose.onNodeWithTag("back").performClick()
        awaitTag("word_search")
        compose.onNodeWithTag("favorites_filter").performClick()
        compose.onNodeWithTag("word_fr:être:verb:1").assertExists()
        compose.onNodeWithTag("word_search").performTextReplacement("zzzz")
        compose.onNodeWithText("没有符合条件的词条").assertExists()
    }

    @Test fun settingsAcceptLargeLimitRejectOverflowAndKeepOneMeaningLanguage() {
        compose.onNodeWithTag("nav_settings").performClick()
        awaitTag("daily_new_limit")
        compose.onNodeWithTag("daily_new_limit").performTextReplacement("2147483648")
        compose.onNodeWithTag("save_settings").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("daily_new_limit").performScrollTo().performTextReplacement("10000")
        compose.onNodeWithTag("daily_new_limit").performImeAction()
        compose.onNodeWithTag("save_settings").performScrollTo().assertIsEnabled().performClick()
        awaitTag("start_study")
        // The saved snackbar floats over the bottom of the next screen. Wait
        // for it to leave before physically tapping switches near that edge.
        compose.waitUntil(15_000) { compose.onAllNodesWithText("设置已保存", substring = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("nav_settings").performClick()
        awaitTag("daily_new_limit")
        compose.onNodeWithTag("daily_new_limit").assertTextContains("10000")
        compose.onNodeWithTag("daily_new_limit").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("daily_new_limit").performImeAction()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(350)
        compose.onNodeWithTag("show_chinese").performScrollTo().performClick()
        compose.onNodeWithTag("show_chinese").assertIsOff()
        compose.onNodeWithTag("show_english").performScrollTo().performClick()
        compose.onNodeWithTag("show_english").assertIsOff()
        compose.onNodeWithTag("show_spanish").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("save_settings").performScrollTo().performClick()
        awaitTag("start_study")
        compose.onNodeWithTag("start_study").assertIsNotEnabled()
        scenario.recreate()
        awaitTag("start_study")
        compose.onNodeWithTag("start_study").assertIsNotEnabled()
    }

    @Test fun blankTapRevealsButCardControlsAndDraggingDoNot() {
        compose.onNodeWithTag("start_study").performClick()
        awaitTag("reveal_answer")
        compose.onNodeWithText("揭示答案").assertDoesNotExist()
        compose.onNodeWithTag("reveal_hint").assertTextEquals("点击空白处揭示答案")
        for (tag in listOf("study_card", "study_lemma", "word_ipa", "study_metadata")) {
            compose.onNodeWithTag(tag).performTouchInput { click(center) }
            compose.onNodeWithTag("reveal_answer").assertExists()
            compose.onAllNodesWithTag("sense_en").assertCountEquals(0)
        }
        compose.onNodeWithTag("play_audio").performTouchInput { click(center) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("此词暂无本地录音", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reveal_answer").assertExists()
        // At large font sizes the audio snackbar covers more of the lower
        // blank area. It is a message, not an exposed blank reveal target.
        compose.waitUntil(15_000) { compose.onAllNodesWithText("此词暂无本地录音", substring = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("toggle_favorite").performTouchInput { click(center) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("★ 已收藏").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reveal_answer").assertExists()
        compose.onNodeWithTag("reveal_blank_space").performScrollTo().performTouchInput { swipeUp() }
        compose.onNodeWithTag("reveal_answer").assertExists()
        compose.onNodeWithTag("nav_settings").performClick()
        awaitTag("daily_new_limit")
        compose.onNodeWithTag("back").performClick()
        awaitTag("reveal_answer")
        compose.onNodeWithTag("reveal_blank_space").performScrollTo().performTouchInput { click(center) }
        awaitTag("rate_GOOD")
        compose.onAllNodesWithTag("sense_en").onFirst().assertExists()
        screenshot("09-blank-tap-answer")
    }

    @Test fun backgroundReturnShowsHomeAndContinueRestoresRevealedCard() {
        compose.onNodeWithTag("start_study").performClick()
        awaitTag("reveal_answer")
        compose.onNodeWithTag("reveal_blank_space").performScrollTo().performTouchInput { click(center) }
        awaitTag("rate_GOOD")
        compose.onNodeWithTag("study_position").assertTextEquals("1 / 2")
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        awaitTag("start_study")
        compose.onNodeWithText("继续学习").assertExists()
        compose.onNodeWithTag("start_study").performClick()
        awaitTag("rate_GOOD")
        compose.onNodeWithTag("study_position").assertTextEquals("1 / 2")
    }

    @Test fun wordLibraryFirstPageTiming() {
        val started = SystemClock.elapsedRealtime()
        compose.onNodeWithTag("nav_words").performClick()
        awaitTag("browse_list_")
        val elapsed = SystemClock.elapsedRealtime() - started
        println("FRENCH_BROWSE_FIRST_PAGE_MS=$elapsed")
        assertTrue("First vocabulary page took ${elapsed}ms", elapsed < 5_000)
        compose.onNodeWithTag("word_search").assertIsDisplayed()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        // UiAutomation captures SurfaceFlinger; allow the committed Compose frame to be presented.
        android.os.SystemClock.sleep(350)
        val directory = File(context.getExternalFilesDir(null), "qa")
        directory.mkdirs()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

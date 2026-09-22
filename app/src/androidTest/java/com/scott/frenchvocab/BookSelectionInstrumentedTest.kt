package com.scott.frenchvocab

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.frenchvocab.data.content.ContentRepository
import com.scott.frenchvocab.data.user.StudyRepository
import com.scott.frenchvocab.domain.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BookSelectionInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var content: ContentRepository
    private lateinit var originalBook: VocabularyBook
    private lateinit var otherBook: VocabularyBook
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before fun setup() {
        context.deleteDatabase("french_user.db")
        content = ContentRepository(context)
        originalBook = content.books().first { it.id == "essential-fr" }
        otherBook = content.books().last { it.id != originalBook.id }
        StudyRepository(context, content).use { repo ->
            repo.saveSettings(UserSettings(
                dailyNewLimit = 1, bookId = originalBook.id,
                autoPlay = AutoPlayMode.NEVER, ttsFallback = false,
            ))
            val session = repo.startSession().session!!
            val uid = session.current!!.lexemeUid
            repo.reveal(session.id, uid)
            repo.rate(session.id, uid, Rating.GOOD)
            repo.saveSettings(repo.snapshot().settings.copy(dailyNewLimit = 2))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitTag("start_study")
    }

    @After fun cleanup() {
        scenario.close()
        content.close()
    }

    @Test fun homeAndSettingsOnlyShowCurrentBookAndBackCancelsSelection() {
        compose.onNodeWithTag("current_book_progress").assertTextContains(originalBook.title)
            .assertTextContains("1 / ${originalBook.lexemeUids.size} 已学习")
        compose.onNodeWithText("可在设置页切换词书").assertExists()
        content.books().filter { it.id != originalBook.id }.forEach { book ->
            compose.onAllNodesWithText(book.title).assertCountEquals(0)
        }
        screenshot("01-home")
        compose.onNodeWithTag("nav_settings").performClick()
        awaitTag("choose_book")
        compose.onNodeWithTag("choose_book").assertTextContains(originalBook.title)
            .assertTextContains(progressText(originalBook))
        compose.onAllNodesWithText(otherBook.title).assertCountEquals(0)
        screenshot("02-settings")
        compose.onNodeWithTag("choose_book").performScrollTo().performClick()
        awaitTag("book_selection_screen")
        scrollToBook(originalBook)
        compose.onNodeWithTag("book_option_${originalBook.id}").assertIsSelected()
        // Every book remains reachable, including entries beyond the first viewport.
        content.books().forEach { book ->
            scrollToBook(book)
            compose.onNodeWithTag("book_option_${book.id}").assertTextContains(progressText(book))
        }
        compose.onNodeWithTag("book_option_${otherBook.id}").performClick().assertIsSelected()
        assertEquals(originalBook.id, readSnapshot().settings.bookId)
        compose.onNodeWithTag("save_book").assertIsDisplayed().assertIsEnabled()
        screenshot("03-book-selection")
        pressBack()
        awaitTag("choose_book")
        compose.onNodeWithTag("choose_book").assertTextContains(originalBook.title)
        compose.onNodeWithTag("choose_book").performScrollTo().performClick()
        awaitTag("book_selection_screen")
        scrollToBook(originalBook)
        compose.onNodeWithTag("book_option_${originalBook.id}").assertIsSelected()
    }

    @Test fun saveBookPersistsIndependentlyAndPreservesOtherSettingsDraftAcrossRecreation() {
        val savedBefore = readSnapshot().settings
        compose.onNodeWithTag("nav_settings").performClick()
        awaitTag("daily_new_limit")
        compose.onNodeWithTag("daily_new_limit").performTextReplacement("7")
        compose.onNodeWithTag("daily_new_limit").performImeAction()
        compose.onNodeWithTag("show_ipa").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithTag("choose_book").performScrollTo().performClick()
        awaitTag("book_selection_screen")
        scrollToBook(otherBook)
        compose.onNodeWithTag("book_option_${otherBook.id}").performClick()
        scenario.recreate()
        awaitTag("book_selection_screen")
        scrollToBook(otherBook)
        compose.onNodeWithTag("book_option_${otherBook.id}").assertIsSelected()
        compose.onNodeWithTag("save_book").performClick()
        awaitTag("choose_book")
        compose.onNodeWithTag("choose_book").assertTextContains(otherBook.title)
            .assertTextContains(progressText(otherBook))
        assertEquals(savedBefore.copy(bookId = otherBook.id), readSnapshot().settings)
        compose.onNodeWithTag("daily_new_limit").assertTextContains("7")
        compose.onNodeWithTag("show_ipa").assertIsOff()
        dismissSavedMessage()
        compose.onNodeWithTag("save_settings").performScrollTo().performClick()
        awaitTag("start_study")
        assertEquals(savedBefore.copy(bookId = otherBook.id, dailyNewLimit = 7, showIpa = false), readSnapshot().settings)
        compose.onNodeWithTag("current_book_progress").assertTextContains(otherBook.title)
        compose.onAllNodesWithText(originalBook.title).assertCountEquals(0)
        // A new Activity/ViewModel reads the saved book from the database.
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitTag("start_study")
        compose.onNodeWithTag("current_book_progress").assertTextContains(otherBook.title)
        compose.onNodeWithTag("nav_settings").performClick()
        awaitTag("choose_book")
        compose.onNodeWithTag("choose_book").assertTextContains(otherBook.title)
        compose.onNodeWithTag("daily_new_limit").assertTextContains("7")
    }

    @Test fun switchingDuringStudyPreservesActiveSessionAndReturnDestination() {
        compose.onNodeWithTag("start_study").performClick()
        awaitTag("reveal_answer")
        val activeBefore = readSnapshot().session!!
        compose.onNodeWithTag("nav_settings").performClick()
        awaitTag("choose_book")
        compose.onNodeWithTag("choose_book").performScrollTo().performClick()
        awaitTag("book_selection_screen")
        compose.onNodeWithText("当前学习进度已保留，切换词书从下一轮生效。").assertExists()
        scrollToBook(otherBook)
        compose.onNodeWithTag("book_option_${otherBook.id}").performClick()
        compose.onNodeWithTag("save_book").performClick()
        awaitTag("choose_book")
        val after = readSnapshot()
        assertEquals(otherBook.id, after.settings.bookId)
        assertEquals(activeBefore, after.session)
        compose.onNodeWithTag("back").performClick()
        awaitTag("reveal_answer")
        compose.onNodeWithTag("study_position").assertTextEquals("1 / 1")
        compose.onNodeWithTag("back").performClick()
        awaitTag("start_study")
        compose.onNodeWithTag("current_book_progress").assertTextContains(otherBook.title)
    }

    private fun readSnapshot(): AppSnapshot = StudyRepository(context, content).use { it.snapshot() }

    private fun progressText(book: VocabularyBook): String {
        val cards = readSnapshot().cards
        val learned = book.lexemeUids.count { (cards[it]?.repetitions ?: 0) > 0 }
        val total = book.lexemeUids.size
        val percent = if (total == 0) 0 else learned * 100 / total
        return "$learned / $total 已学习 · $percent%"
    }

    private fun scrollToBook(book: VocabularyBook) {
        compose.onNodeWithTag("book_selection_list").performScrollToNode(hasTestTag("book_option_${book.id}"))
    }

    private fun dismissSavedMessage() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("词书已保存", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("知道了").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("词书已保存", substring = true).fetchSemanticsNodes().isEmpty() }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(350)
        val directory = File(context.getExternalFilesDir(null), "qa/book-picker").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

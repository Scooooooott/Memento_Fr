package com.scott.frenchvocab

import android.database.sqlite.SQLiteDatabase
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scott.frenchvocab.data.content.ContentRepository
import com.scott.frenchvocab.data.user.StudyRepository
import com.scott.frenchvocab.domain.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class RepositoryInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var content: ContentRepository
    private lateinit var repo: StudyRepository
    private val noon get() = LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setup() {
        context.deleteDatabase("french_user.db")
        content = ContentRepository(context, InstrumentationRegistry.getInstrumentation().context.assets
            .open("content-fixtures/legacy.db").use { it.readBytes() })
        repo = StudyRepository(context, content)
    }
    @After fun tearDown() { repo.close(); content.close() }

    @Test fun contentLoadsWithStableIdsAndCompleteCoreVerbTables() {
        val words = content.allWords()
        assertTrue(words.size in 30..50)
        assertEquals(words.size, words.map { it.uid }.toSet().size)
        words.forEach { word ->
            assertTrue(word.uid.startsWith("fr:"))
            assertEquals(word, content.find(word.uid))
            assertTrue(word.ipa.isNotBlank())
            assertTrue(word.senses.isNotEmpty())
            assertTrue(word.examples.isNotEmpty())
            word.senses.forEach { assertTrue(it.english.isNotBlank() && it.spanish.isNotBlank()) }
            if (word.conjugations.isNotEmpty()) {
                assertEquals(3, word.conjugations.groupBy { it.tense }.size)
                word.conjugations.groupBy { it.tense }.values.forEach { assertEquals(6, it.size) }
            }
        }
        assertEquals(words.size, content.books().first { it.id == "essential-fr" }.lexemeUids.size)
    }

    @Test fun fixedQueueAnswerAndSettingsSurviveRepositoryReopen() {
        val first = repo.startSession(noon).session!!
        assertEquals(10, first.items.size)
        val word = first.current!!.lexemeUid
        repo.reveal(first.id, word)
        repo.saveSettings(UserSettings(dailyNewLimit = 2, showIpa = false, autoPlay = AutoPlayMode.NEVER))
        repo.close()
        repo = StudyRepository(context, content)
        val restored = repo.startSession(noon).session!!
        assertEquals(first.id, restored.id)
        assertEquals(first.items, restored.items)
        assertTrue(restored.answerRevealed)
        assertEquals(10, restored.settings.dailyNewLimit)
        assertTrue(restored.settings.showIpa)
        assertEquals(2, repo.snapshot(noon).settings.dailyNewLimit)
        val rated = repo.rate(first.id, word, Rating.GOOD, noon)
        assertEquals(1, rated.session!!.position)
        assertFalse(rated.session!!.answerRevealed)
        val stale = repo.rate(first.id, word, Rating.AGAIN, noon)
        assertEquals(1, stale.stats.todayReviews)
        assertEquals(1, stale.cards[word]!!.repetitions)
        assertEquals(rated.session, stale.session)
    }

    @Test fun ratingsRequireRevealAndPersistAllFourOutcomes() {
        repo.saveSettings(UserSettings(dailyNewLimit = 4))
        var snapshot = repo.startSession(noon)
        val first = snapshot.session!!
        snapshot = repo.rate(first.id, first.current!!.lexemeUid, Rating.GOOD, noon)
        assertEquals(0, snapshot.stats.todayReviews)
        assertEquals(0, snapshot.session!!.position)
        for (rating in Rating.entries) {
            val session = snapshot.session!!
            val uid = session.current!!.lexemeUid
            repo.reveal(session.id, uid)
            snapshot = repo.rate(session.id, uid, rating, noon)
            assertTrue(snapshot.cards[uid]!!.dueAt > noon)
            assertTrue(snapshot.cards[uid]!!.stability > 0)
            assertTrue(snapshot.cards[uid]!!.difficulty in 1.0..10.0)
        }
        assertNull(snapshot.session)
        assertEquals(4, snapshot.stats.todayReviews)
        assertEquals(Rating.entries.toList(), snapshot.latestCompletedSession!!.items.map { it.rating })
        assertEquals(4, snapshot.cards.size)
    }

    @Test fun dailyNewLimitIsSharedAcrossSessionsAndZeroMeansReviewsOnly() {
        repo.saveSettings(UserSettings(dailyNewLimit = 3))
        finish(repo.startSession(noon), noon)
        assertNull(repo.startSession(noon).session)
        repo.saveSettings(UserSettings(dailyNewLimit = 5))
        val second = repo.startSession(noon)
        assertEquals(2, second.session!!.items.size)
        finish(second, noon)
        assertEquals(5, repo.snapshot(noon).stats.todayNew)
        repo.saveSettings(UserSettings(dailyNewLimit = 0))
        assertNull(repo.startSession(noon).session)
        val later = repo.startSession(noon + 365L * 86_400_000).session!!
        assertEquals(5, later.items.size)
        assertTrue(later.items.none { it.isNew })
    }

    @Test fun midnightChargesNewCardsToActualReviewDayAndKeepsQueue() {
        val boundary = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        repo.saveSettings(UserSettings(dailyNewLimit = 3))
        val first = repo.startSession(boundary - 60_000).session!!
        val uid = first.current!!.lexemeUid
        repo.reveal(first.id, uid)
        repo.rate(first.id, uid, Rating.GOOD, boundary - 60_000)
        repo.close()
        repo = StudyRepository(context, content)
        val restored = repo.startSession(boundary + 60_000)
        assertEquals(first.items.map { it.lexemeUid }, restored.session!!.items.map { it.lexemeUid })
        finish(restored, boundary + 60_000)
        assertEquals(2, repo.snapshot(boundary + 60_000).stats.todayNew)
        assertEquals(1, repo.startSession(boundary + 60_000).session!!.items.count { it.isNew })
    }

    @Test fun changingBookDoesNotHidePreviouslyLearnedDueCards() {
        repo.saveSettings(UserSettings(dailyNewLimit = 100))
        finish(repo.startSession(noon), noon)
        val count = content.allWords().size
        assertEquals(count, repo.snapshot(noon).cards.size)
        repo.saveSettings(UserSettings(dailyNewLimit = 0, bookId = "verbs-fr"))
        val session = repo.startSession(noon + 365L * 86_400_000).session!!
        assertEquals(count, session.items.size)
        assertTrue(session.items.none { it.isNew })
    }

    @Test fun failedTransactionRollsBackCardLogAndSessionTogether() {
        val session = repo.startSession(noon).session!!
        val uid = session.current!!.lexemeUid
        repo.reveal(session.id, uid)
        val db = SQLiteDatabase.openDatabase(context.getDatabasePath("french_user.db").path, null, SQLiteDatabase.OPEN_READWRITE)
        db.execSQL("CREATE TRIGGER qa_reject_progress BEFORE UPDATE OF position ON study_session BEGIN SELECT RAISE(ABORT, 'QA rollback'); END")
        try {
            repo.rate(session.id, uid, Rating.GOOD, noon)
            fail("Rating must fail when the transaction cannot update its session")
        } catch (_: android.database.SQLException) { /* deliberate failure */ }
        val snapshot = repo.snapshot(noon)
        assertEquals(0, snapshot.session!!.position)
        assertTrue(snapshot.session!!.answerRevealed)
        assertTrue(snapshot.cards.isEmpty())
        assertEquals(0, snapshot.stats.todayReviews)
        db.execSQL("DROP TRIGGER qa_reject_progress")
        db.close()
        assertEquals(1, repo.rate(session.id, uid, Rating.GOOD, noon).stats.todayReviews)
    }

    @Test fun favoritesAndValidatedSettingsPersistIndependentlyOfContentCopy() {
        val uid = content.allWords().first().uid
        repo.toggleFavorite(uid)
        repo.saveSettings(UserSettings(dailyNewLimit = -4, showEnglish = false, showSpanish = false, bookId = "missing"))
        repo.close(); content.close()
        content = ContentRepository(context, InstrumentationRegistry.getInstrumentation().context.assets
            .open("content-fixtures/legacy.db").use { it.readBytes() })
        repo = StudyRepository(context, content)
        val snapshot = repo.snapshot(noon)
        assertTrue(uid in snapshot.favorites)
        assertEquals(0, snapshot.settings.dailyNewLimit)
        assertTrue(snapshot.settings.showChinese || snapshot.settings.showEnglish || snapshot.settings.showSpanish)
        assertEquals("essential-fr", snapshot.settings.bookId)
        assertTrue(repo.toggleFavorite(uid).favorites.isEmpty())
    }

    @Test fun contentUpgradeKeepsCardsLogsFavoritesAndUnfinishedSession() {
        val first = repo.startSession(noon).session!!
        val uid = first.current!!.lexemeUid
        repo.reveal(first.id, uid)
        repo.rate(first.id, uid, Rating.GOOD, noon)
        repo.toggleFavorite(uid)
        val current = repo.snapshot(noon).session!!
        repo.reveal(current.id, current.current!!.lexemeUid)
        val before = repo.snapshot(noon)
        val original = content.find("fr:être:verb:1")!!.senses.first().english
        repo.close()
        val upgradeContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getAssets(): AssetManager = InstrumentationRegistry.getInstrumentation().context.assets
        }
        ContentRepository(upgradeContext).use { upgraded ->
            assertNotEquals(original, upgraded.find("fr:être:verb:1")!!.senses.first().english)
            assertEquals("to be (QA updated content)", upgraded.find("fr:être:verb:1")!!.senses.first().english)
            repo = StudyRepository(context, upgraded)
            val after = repo.snapshot(noon)
            assertEquals(before.cards, after.cards)
            assertEquals(before.session, after.session)
            assertEquals(before.favorites, after.favorites)
            assertEquals(before.stats.todayReviews, after.stats.todayReviews)
            assertEquals(before.settings, after.settings)
        }
    }

    private fun finish(initial: AppSnapshot, now: Long): AppSnapshot {
        var snapshot = initial
        while (snapshot.session != null) {
            val session = snapshot.session!!
            val uid = session.current!!.lexemeUid
            repo.reveal(session.id, uid)
            snapshot = repo.rate(session.id, uid, Rating.GOOD, now)
        }
        return snapshot
    }
}

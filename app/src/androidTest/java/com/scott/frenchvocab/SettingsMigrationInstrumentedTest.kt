package com.scott.frenchvocab

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.frenchvocab.data.content.ContentRepository
import com.scott.frenchvocab.data.user.StudyRepository
import com.scott.frenchvocab.domain.AutoPlayMode
import com.scott.frenchvocab.domain.Rating
import com.scott.frenchvocab.domain.UserSettings
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** A frozen v1 schema/fixture exercises SQLiteOpenHelper's actual upgrade path. */
@RunWith(AndroidJUnit4::class)
class SettingsMigrationInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var content: ContentRepository
    private var repository: StudyRepository? = null
    private val noon get() = LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setup() {
        context.deleteDatabase("french_user.db")
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets
            .open("content-fixtures/legacy.db").use { it.readBytes() }
        content = ContentRepository(context, fixture)
    }

    @After fun cleanup() { repository?.close(); content.close() }

    @Test fun realV1UpgradeKeepsAllSixTablesAndFreezesLegacySessionSettings() {
        val now = noon
        createVersionOneFixture(now)
        val before = storedRows()
        assertEquals(6, before.size)
        before.forEach { (table, rows) -> assertTrue("Fixture must populate $table", rows.isNotEmpty()) }
        val beforeSequences = readDatabase { rows(it, "SELECT name,seq FROM sqlite_sequence ORDER BY name") }
        assertEquals(1, readDatabase { it.version })

        val repo = openRepository()
        val migrated = repo.snapshot(now) // Opening the helper invokes onUpgrade, not a test-only migration.
        assertEquals(2, readDatabase { it.version })
        assertEquals("Every pre-existing field in all six tables must survive", before, storedRows())
        assertEquals(beforeSequences, readDatabase { rows(it, "SELECT name,seq FROM sqlite_sequence ORDER BY name") })
        assertTrue(readDatabase { rows(it, "PRAGMA foreign_key_check") }.isEmpty())
        assertEquals(listOf(listOf("3:ok")), readDatabase { rows(it, "PRAGMA integrity_check") })
        assertEquals(2, migrated.cards.size)
        assertEquals(2, migrated.favorites.size)
        assertEquals(9L, migrated.session!!.id)
        assertEquals(1, migrated.session!!.position)
        assertTrue(migrated.session!!.answerRevealed)
        assertEquals(7L, migrated.latestCompletedSession!!.id)
        assertTrue(migrated.stats.todayReviews > 0)
        assertEquals(37, migrated.settings.dailyNewLimit)
        assertFalse(migrated.settings.showEnglish)
        assertTrue(migrated.settings.showSpanish)
        assertTrue(migrated.settings.showChinese)
        assertFalse(migrated.session!!.settings.showChinese)
        assertFalse(migrated.latestCompletedSession!!.settings.showChinese)
        assertEquals(legacySettings(), migrated.session!!.settings)
        assertEquals(legacySettings(), migrated.latestCompletedSession!!.settings)

        // This also proves the old <=100 CHECK has really been removed from the persisted schema.
        val nextSettings = UserSettings(dailyNewLimit = Int.MAX_VALUE, autoPlay = AutoPlayMode.NEVER,
            ttsFallback = false, showIpa = false, showEnglish = false, showSpanish = false, showChinese = true)
        assertEquals(nextSettings, repo.saveSettings(nextSettings).settings)
        repository!!.close()
        val reopened = openRepository()
        val restored = reopened.snapshot(now)
        assertEquals(nextSettings, restored.settings)
        assertEquals(migrated.session, restored.session)
        assertEquals(migrated.latestCompletedSession, restored.latestCompletedSession)
        assertEquals(before.filterKeys { it != "settings" }, storedRows().filterKeys { it != "settings" })

        // A revealed legacy session remains actionable and new rounds use the new settings.
        val oldSession = restored.session!!
        val completed = reopened.rate(oldSession.id, oldSession.current!!.lexemeUid, Rating.GOOD, now)
        assertNull(completed.session)
        assertEquals(9L, completed.latestCompletedSession!!.id)
        assertFalse(completed.latestCompletedSession!!.settings.showChinese)
        val next = reopened.startSession(now).session!!
        assertTrue(next.id > 9L)
        assertEquals(nextSettings, next.settings)
        val eligible = content.books().first { it.id == nextSettings.bookId }.lexemeUids
            .count { completed.cards[it]?.lastReviewAt == null }
        assertEquals(eligible, next.items.size)
        assertTrue(next.items.all { it.isNew })
    }

    @Test fun freshV2PersistsLargeLimitsAndAtLeastOneOfThreeMeaningLanguages() {
        val repo = openRepository()
        assertTrue(repo.snapshot(noon).settings.showChinese)
        for (limit in listOf(101, 150, Int.MAX_VALUE)) {
            val desired = UserSettings(dailyNewLimit = limit, showChinese = true, showEnglish = false, showSpanish = false)
            assertEquals(desired, repo.saveSettings(desired).settings)
        }
        repo.close()
        val reopened = openRepository()
        val saved = reopened.snapshot(noon).settings
        assertEquals(Int.MAX_VALUE, saved.dailyNewLimit)
        assertTrue(saved.showChinese)
        assertFalse(saved.showEnglish)
        assertFalse(saved.showSpanish)
        val session = reopened.startSession(noon).session!!
        assertEquals(content.books().first { it.id == saved.bookId }.lexemeUids.size, session.items.size)
        assertEquals(saved, session.settings)
        val allOff = reopened.saveSettings(saved.copy(showChinese = false, showEnglish = false, showSpanish = false))
        assertTrue(allOff.settings.showChinese)
        assertFalse(allOff.settings.showEnglish)
        assertFalse(allOff.settings.showSpanish)
        val englishOnly = saved.copy(showChinese = false, showEnglish = true, showSpanish = false)
        assertEquals(englishOnly, reopened.saveSettings(englishOnly).settings)
        assertEquals(session, reopened.snapshot(noon).session)
        reopened.close()
        val again = openRepository().snapshot(noon)
        assertEquals(englishOnly, again.settings)
        assertEquals(session, again.session) // v2 JSON retains its Chinese-only snapshot on restart.
    }

    @Test fun zeroLimitStaysReviewOnlyAndNegativeInputIsNormalized() {
        val repo = openRepository()
        assertEquals(0, repo.saveSettings(UserSettings(dailyNewLimit = Int.MIN_VALUE)).settings.dailyNewLimit)
        assertNull(repo.startSession(noon).session)
        assertEquals(0, repo.snapshot(noon).stats.newCount)
    }

    private fun openRepository() = StudyRepository(context, content).also { repository = it }

    private inline fun <T> readDatabase(block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(context.getDatabasePath("french_user.db").path, null, SQLiteDatabase.OPEN_READONLY).use(block)

    private fun storedRows(): Map<String, List<List<String?>>> = readDatabase { db ->
        linkedMapOf(
            // Compare all v1 columns; show_chinese is intentionally the only added column.
            "settings" to rows(db, "SELECT id,daily_new_limit,book_id,auto_play,tts_fallback,show_ipa,show_english,show_spanish FROM settings ORDER BY id"),
            "learning_card" to rows(db, "SELECT * FROM learning_card ORDER BY lexeme_uid"),
            "review_log" to rows(db, "SELECT * FROM review_log ORDER BY id"),
            "study_session" to rows(db, "SELECT * FROM study_session ORDER BY id"),
            "study_session_item" to rows(db, "SELECT * FROM study_session_item ORDER BY session_id,position"),
            "favorites" to rows(db, "SELECT * FROM favorites ORDER BY lexeme_uid"),
        )
    }

    private fun rows(db: SQLiteDatabase, sql: String): List<List<String?>> = db.rawQuery(sql, null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
                if (cursor.getType(column) == Cursor.FIELD_TYPE_NULL) null else "${cursor.getType(column)}:${cursor.getString(column)}"
            })
        }
    }

    private fun legacySettings() = UserSettings(dailyNewLimit = 2, autoPlay = AutoPlayMode.NEW_ONLY,
        ttsFallback = true, showIpa = true, showEnglish = true, showSpanish = false, showChinese = false)

    private fun createVersionOneFixture(now: Long) {
        val path = context.getDatabasePath("french_user.db")
        check(path.parentFile!!.isDirectory || path.parentFile!!.mkdirs())
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            // Deliberately independent of UserDatabase.onCreate, which now creates schema v2.
            db.execSQL("""CREATE TABLE settings (
                id INTEGER PRIMARY KEY CHECK(id=1), daily_new_limit INTEGER NOT NULL CHECK(daily_new_limit BETWEEN 0 AND 100),
                book_id TEXT NOT NULL, auto_play TEXT NOT NULL, tts_fallback INTEGER NOT NULL,
                show_ipa INTEGER NOT NULL, show_english INTEGER NOT NULL, show_spanish INTEGER NOT NULL,
                CHECK(show_english=1 OR show_spanish=1))""")
            db.execSQL("INSERT INTO settings VALUES(1,37,'essential-fr','NEVER',0,0,0,1)")
            db.execSQL("""CREATE TABLE learning_card (
                lexeme_uid TEXT PRIMARY KEY NOT NULL, stability REAL NOT NULL, difficulty REAL NOT NULL,
                due_at INTEGER NOT NULL, last_review_at INTEGER, repetitions INTEGER NOT NULL,
                lapses INTEGER NOT NULL, state TEXT NOT NULL)""")
            db.execSQL("CREATE INDEX cards_due ON learning_card(due_at)")
            db.execSQL("""CREATE TABLE study_session (
                id INTEGER PRIMARY KEY AUTOINCREMENT, position INTEGER NOT NULL DEFAULT 0,
                revealed INTEGER NOT NULL DEFAULT 0, started_at INTEGER NOT NULL, completed_at INTEGER,
                settings_json TEXT NOT NULL)""")
            db.execSQL("CREATE UNIQUE INDEX one_active_session ON study_session((1)) WHERE completed_at IS NULL")
            db.execSQL("""CREATE TABLE study_session_item (
                session_id INTEGER NOT NULL REFERENCES study_session(id), position INTEGER NOT NULL,
                lexeme_uid TEXT NOT NULL, is_new INTEGER NOT NULL, rating TEXT,
                PRIMARY KEY(session_id,position), UNIQUE(session_id,lexeme_uid))""")
            db.execSQL("""CREATE TABLE review_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL, position INTEGER NOT NULL,
                lexeme_uid TEXT NOT NULL, rating TEXT NOT NULL, is_new INTEGER NOT NULL,
                reviewed_at INTEGER NOT NULL, review_day TEXT NOT NULL,
                previous_stability REAL NOT NULL, previous_difficulty REAL NOT NULL,
                next_stability REAL NOT NULL, next_difficulty REAL NOT NULL, next_due_at INTEGER NOT NULL,
                elapsed_days INTEGER NOT NULL, scheduler_version TEXT NOT NULL, desired_retention REAL NOT NULL,
                UNIQUE(session_id,position), FOREIGN KEY(session_id,position) REFERENCES study_session_item(session_id,position))""")
            db.execSQL("CREATE INDEX review_log_day ON review_log(review_day,is_new)")
            db.execSQL("CREATE TABLE favorites (lexeme_uid TEXT PRIMARY KEY NOT NULL)")
            val uids = content.books().first { it.id == "essential-fr" }.lexemeUids.take(3)
            assertEquals(3, uids.size)
            val (a, b, c) = uids
            val yesterday = now - 86_400_000L
            val oldJson = """{"dailyNewLimit":2,"bookId":"essential-fr","autoPlay":"NEW_ONLY","ttsFallback":true,"showIpa":true,"showEnglish":true,"showSpanish":false}"""
            db.execSQL("INSERT INTO study_session VALUES(7,2,0,?,?,?)", arrayOf(yesterday, yesterday, oldJson))
            db.execSQL("INSERT INTO study_session VALUES(9,1,1,?,NULL,?)", arrayOf(now, oldJson))
            db.execSQL("INSERT INTO study_session_item VALUES(7,0,?,1,'GOOD')", arrayOf(a))
            db.execSQL("INSERT INTO study_session_item VALUES(7,1,?,1,'EASY')", arrayOf(b))
            db.execSQL("INSERT INTO study_session_item VALUES(9,0,?,0,'HARD')", arrayOf(a))
            db.execSQL("INSERT INTO study_session_item VALUES(9,1,?,1,NULL)", arrayOf(c))
            db.execSQL("INSERT INTO learning_card VALUES(?,4.5,5.5,?,?,2,0,'REVIEW')", arrayOf(a, now + 86_400_000L, now))
            db.execSQL("INSERT INTO learning_card VALUES(?,8.2956,1.0,?,?,1,0,'REVIEW')", arrayOf(b, now + 7 * 86_400_000L, yesterday))
            for ((id, session, position, uid, time, isNew, rating) in listOf(
                LogFixture(31, 7, 0, a, yesterday, 1, "GOOD"),
                LogFixture(32, 7, 1, b, yesterday, 1, "EASY"),
                LogFixture(40, 9, 0, a, now, 0, "HARD"),
            )) {
                val date = java.time.Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate().toString()
                db.execSQL("""INSERT INTO review_log VALUES(?,?,?,?,?,?,?,?,0.0,0.0,4.5,5.5,?,1,'v1-fixture',0.9)""",
                    arrayOf(id, session, position, uid, rating, isNew, time, date, time + 86_400_000L))
            }
            db.execSQL("INSERT INTO favorites VALUES(?)", arrayOf(a))
            db.execSQL("INSERT INTO favorites VALUES(?)", arrayOf(c))
            db.version = 1
        }
    }

    private data class LogFixture(val id: Int, val session: Int, val position: Int, val uid: String,
        val time: Long, val isNew: Int, val rating: String)
}

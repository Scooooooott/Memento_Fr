package com.scott.frenchvocab.data.user

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.scott.frenchvocab.data.content.ContentRepository
import com.scott.frenchvocab.domain.*
import com.scott.frenchvocab.domain.fsrs.FsrsScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import org.json.JSONObject

/** Synchronous, serialized repository. Call from an IO dispatcher, never the UI thread. */
class StudyRepository(context: Context, private val content: ContentRepository) : AutoCloseable {
    private val helper = UserDatabase(context.applicationContext)
    private val scheduler = FsrsScheduler()
    private val db: SQLiteDatabase get() = helper.writableDatabase

    @Synchronized
    fun snapshot(now: Long = System.currentTimeMillis()): AppSnapshot = transaction { readSnapshot(now) }

    @Synchronized
    fun startSession(now: Long = System.currentTimeMillis()): AppSnapshot = transaction {
        if (readSession(active = true) == null) {
            val settings = readSettings()
            val cards = readCards()
            val book = content.books().firstOrNull { it.id == settings.bookId }
            val items = SessionPlanner.plan(
                allUids = content.allWords().map { it.uid },
                bookUids = book?.lexemeUids.orEmpty(), cards = cards,
                dailyNewLimit = settings.dailyNewLimit, todayNew = countNew(day(now)), now = now,
                random = Random.Default,
            )
            if (items.isNotEmpty()) {
                val sessionId = db.insertOrThrow("study_session", null, ContentValues().apply {
                    put("position", 0); put("revealed", 0); put("started_at", now)
                    put("settings_json", encodeSettings(settings))
                })
                items.forEachIndexed { index, item ->
                    db.insertOrThrow("study_session_item", null, ContentValues().apply {
                        put("session_id", sessionId); put("position", index)
                        put("lexeme_uid", item.lexemeUid); put("is_new", item.isNew.asInt())
                    })
                }
            }
        }
        readSnapshot(now)
    }

    @Synchronized
    fun reveal(sessionId: Long, expectedUid: String): AppSnapshot = transaction {
        val session = readSession(active = true)
        if (session?.id == sessionId && session.current?.lexemeUid == expectedUid) {
            db.update("study_session", ContentValues().apply { put("revealed", 1) }, "id=?", arrayOf(sessionId.toString()))
        }
        readSnapshot(System.currentTimeMillis())
    }

    @Synchronized
    fun rate(sessionId: Long, expectedUid: String, rating: Rating, now: Long = System.currentTimeMillis()): AppSnapshot = transaction {
        val session = readSession(active = true)
        val item = session?.current
        if (session?.id == sessionId && item?.lexemeUid == expectedUid && item.rating == null && session.answerRevealed) {
            val previous = readCards()[expectedUid] ?: LearningCard(expectedUid)
            // Reject clock rollback by retaining the prior review instant for scheduling.
            val reviewTime = maxOf(now, previous.lastReviewAt ?: now)
            val next = scheduler.review(previous, rating, reviewTime)
            db.insertOrThrow("review_log", null, ContentValues().apply {
                put("session_id", sessionId); put("position", session.position); put("lexeme_uid", expectedUid)
                put("rating", rating.name); put("is_new", item.isNew.asInt()); put("reviewed_at", reviewTime)
                put("review_day", day(reviewTime)); put("previous_stability", previous.stability)
                put("previous_difficulty", previous.difficulty); put("next_stability", next.stability)
                put("next_difficulty", next.difficulty); put("next_due_at", next.dueAt)
                put("elapsed_days", scheduler.elapsedDays(previous, reviewTime))
                put("scheduler_version", FsrsScheduler.VERSION); put("desired_retention", FsrsScheduler.DESIRED_RETENTION)
            })
            check(db.insertWithOnConflict("learning_card", null, next.toValues(), SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
                "学习记录保存失败"
            }
            check(db.update("study_session_item", ContentValues().apply { put("rating", rating.name) },
                "session_id=? AND position=? AND rating IS NULL", arrayOf(sessionId.toString(), session.position.toString())) == 1)
            check(db.update("study_session", ContentValues().apply {
                put("position", session.position + 1); put("revealed", 0)
                if (session.position + 1 == session.items.size) put("completed_at", reviewTime)
            }, "id=?", arrayOf(sessionId.toString())) == 1)
        }
        readSnapshot(now)
    }

    @Synchronized
    fun saveSettings(settings: UserSettings): AppSnapshot = transaction {
        val bookId = settings.bookId.takeIf { id -> content.books().any { it.id == id } }
            ?: content.books().firstOrNull()?.id ?: "essential-fr"
        val safe = settings.copy(dailyNewLimit = settings.dailyNewLimit.coerceAtLeast(0), bookId = bookId,
            showChinese = settings.showChinese || (!settings.showEnglish && !settings.showSpanish))
        check(db.insertWithOnConflict("settings", null, ContentValues().apply {
            put("id", 1); put("daily_new_limit", safe.dailyNewLimit); put("book_id", safe.bookId)
            put("auto_play", safe.autoPlay.name); put("tts_fallback", safe.ttsFallback.asInt())
            put("show_ipa", safe.showIpa.asInt()); put("show_english", safe.showEnglish.asInt())
            put("show_spanish", safe.showSpanish.asInt()); put("show_chinese", safe.showChinese.asInt())
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "设置保存失败" }
        readSnapshot(System.currentTimeMillis())
    }

    @Synchronized
    fun toggleFavorite(uid: String): AppSnapshot = transaction {
        require(content.find(uid) != null) { "词条不存在：$uid" }
        if (db.delete("favorites", "lexeme_uid=?", arrayOf(uid)) == 0) {
            db.insertOrThrow("favorites", null, ContentValues().apply { put("lexeme_uid", uid) })
        }
        readSnapshot(System.currentTimeMillis())
    }

    @Synchronized
    override fun close() = helper.close()

    private fun readSnapshot(now: Long): AppSnapshot {
        val settings = readSettings()
        val cards = readCards()
        val today = day(now)
        val counts = linkedMapOf<String, Int>()
        db.rawQuery("SELECT review_day, COUNT(*) FROM review_log GROUP BY review_day", null).use { cursor ->
            while (cursor.moveToNext()) counts[cursor.getString(0)] = cursor.getInt(1)
        }
        val todayNew = countNew(today)
        val validUids = content.allWords().map { it.uid }.toSet()
        val bookUids = content.books().firstOrNull { it.id == settings.bookId }?.lexemeUids.orEmpty().toSet()
        val favorites = mutableSetOf<String>()
        db.rawQuery("SELECT lexeme_uid FROM favorites", null).use { cursor ->
            while (cursor.moveToNext()) favorites += cursor.getString(0)
        }
        val stats = StudyStats(
            dueCount = cards.values.count { it.lexemeUid in validUids && it.lastReviewAt != null && it.dueAt <= now },
            newCount = minOf(bookUids.count { cards[it]?.lastReviewAt == null }, SessionPlanner.remainingNew(settings.dailyNewLimit, todayNew)),
            learnedCount = cards.values.count { it.lexemeUid in bookUids && it.lastReviewAt != null },
            todayReviews = counts[today] ?: 0, todayNew = todayNew,
            streakDays = SessionPlanner.streak(LocalDate.parse(today), counts.keys),
            lastSevenDays = (6 downTo 0).map { days -> LocalDate.parse(today).minusDays(days.toLong()).toString().let { DailyCount(it, counts[it] ?: 0) } },
        )
        return AppSnapshot(settings, readSession(active = true), stats, cards, favorites, readSession(active = false))
    }

    private fun readSettings(): UserSettings = db.rawQuery("SELECT * FROM settings WHERE id=1", null).use { cursor ->
        if (!cursor.moveToFirst()) UserSettings() else UserSettings(
            dailyNewLimit = cursor.int("daily_new_limit"), bookId = cursor.string("book_id"),
            autoPlay = AutoPlayMode.valueOf(cursor.string("auto_play")), ttsFallback = cursor.int("tts_fallback") != 0,
            showIpa = cursor.int("show_ipa") != 0, showEnglish = cursor.int("show_english") != 0,
            showSpanish = cursor.int("show_spanish") != 0, showChinese = cursor.int("show_chinese") != 0,
        )
    }

    private fun readCards(): Map<String, LearningCard> = buildMap {
        db.rawQuery("SELECT * FROM learning_card", null).use { cursor ->
            while (cursor.moveToNext()) {
                val uid = cursor.string("lexeme_uid")
                put(uid, LearningCard(uid, cursor.double("stability"), cursor.double("difficulty"), cursor.long("due_at"),
                    cursor.nullableLong("last_review_at"), cursor.int("repetitions"), cursor.int("lapses"), cursor.string("state")))
            }
        }
    }

    private fun readSession(active: Boolean): StudySession? {
        val condition = if (active) "IS NULL" else "IS NOT NULL"
        return db.rawQuery("SELECT * FROM study_session WHERE completed_at $condition ORDER BY id DESC LIMIT 1", null).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.long("id")
            val items = mutableListOf<SessionItem>()
            db.rawQuery("SELECT * FROM study_session_item WHERE session_id=? ORDER BY position", arrayOf(id.toString())).use { itemCursor ->
                while (itemCursor.moveToNext()) {
                    val rating = itemCursor.getString(itemCursor.getColumnIndexOrThrow("rating"))?.let(Rating::valueOf)
                    items += SessionItem(itemCursor.string("lexeme_uid"), itemCursor.int("is_new") != 0, rating)
                }
            }
            StudySession(id, items, cursor.int("position"), cursor.int("revealed") != 0, cursor.long("started_at"),
                cursor.nullableLong("completed_at"), decodeSettings(cursor.string("settings_json")))
        }
    }

    private fun countNew(date: String): Int = db.rawQuery(
        "SELECT COUNT(*) FROM review_log WHERE review_day=? AND is_new=1", arrayOf(date),
    ).use { it.moveToFirst(); it.getInt(0) }

    private inline fun <T> transaction(block: () -> T): T {
        val database = db
        database.beginTransaction()
        try { val result = block(); database.setTransactionSuccessful(); return result }
        finally { database.endTransaction() }
    }

    private fun day(now: Long): String = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    private fun encodeSettings(settings: UserSettings): String = JSONObject().apply {
        put("dailyNewLimit", settings.dailyNewLimit); put("bookId", settings.bookId); put("autoPlay", settings.autoPlay.name)
        put("ttsFallback", settings.ttsFallback); put("showIpa", settings.showIpa)
        put("showEnglish", settings.showEnglish); put("showSpanish", settings.showSpanish)
        put("showChinese", settings.showChinese)
    }.toString()
    private fun decodeSettings(json: String): UserSettings = JSONObject(json).let {
        UserSettings(
            dailyNewLimit = it.getInt("dailyNewLimit"), bookId = it.getString("bookId"),
            autoPlay = AutoPlayMode.valueOf(it.getString("autoPlay")), ttsFallback = it.getBoolean("ttsFallback"),
            showIpa = it.getBoolean("showIpa"), showEnglish = it.getBoolean("showEnglish"),
            showSpanish = it.getBoolean("showSpanish"),
            // A v1 session must keep its original EN/ES display until the next session.
            showChinese = it.optBoolean("showChinese", false),
        )
    }
    private fun Boolean.asInt() = if (this) 1 else 0
    private fun Cursor.string(column: String) = getString(getColumnIndexOrThrow(column))
    private fun Cursor.int(column: String) = getInt(getColumnIndexOrThrow(column))
    private fun Cursor.long(column: String) = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.double(column: String) = getDouble(getColumnIndexOrThrow(column))
    private fun Cursor.nullableLong(column: String): Long? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
    private fun LearningCard.toValues() = ContentValues().apply {
        put("lexeme_uid", lexemeUid); put("stability", stability); put("difficulty", difficulty); put("due_at", dueAt)
        put("last_review_at", lastReviewAt); put("repetitions", repetitions); put("lapses", lapses); put("state", state)
    }
}

internal class UserDatabase(context: Context) : SQLiteOpenHelper(context, "french_user.db", null, 2) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        createSettingsTable(db, "settings")
        db.execSQL("""INSERT INTO settings
            (id,daily_new_limit,book_id,auto_play,tts_fallback,show_ipa,show_english,show_spanish,show_chinese)
            VALUES(1,10,'essential-fr','NEW_ONLY',1,1,1,1,1)""")
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
    }

    private fun createSettingsTable(db: SQLiteDatabase, tableName: String) {
        check(tableName == "settings" || tableName == "settings_v2")
        db.execSQL("""CREATE TABLE $tableName (
            id INTEGER PRIMARY KEY CHECK(id=1), daily_new_limit INTEGER NOT NULL CHECK(daily_new_limit >= 0),
            book_id TEXT NOT NULL, auto_play TEXT NOT NULL, tts_fallback INTEGER NOT NULL,
            show_ipa INTEGER NOT NULL, show_english INTEGER NOT NULL, show_spanish INTEGER NOT NULL,
            show_chinese INTEGER NOT NULL DEFAULT 1,
            CHECK(show_english=1 OR show_spanish=1 OR show_chinese=1))""")
    }

    // SQLiteOpenHelper wraps onUpgrade and the schema-version change in one transaction.
    // Rebuild only settings: SQLite cannot remove its old CHECK with ALTER COLUMN.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "Unsupported user database migration $oldVersion → $newVersion" }
        createSettingsTable(db, "settings_v2")
        db.execSQL("""INSERT INTO settings_v2
            (id,daily_new_limit,book_id,auto_play,tts_fallback,show_ipa,show_english,show_spanish,show_chinese)
            SELECT id,daily_new_limit,book_id,auto_play,tts_fallback,show_ipa,show_english,show_spanish,1
            FROM settings""")
        db.execSQL("DROP TABLE settings")
        db.execSQL("ALTER TABLE settings_v2 RENAME TO settings")
    }
}

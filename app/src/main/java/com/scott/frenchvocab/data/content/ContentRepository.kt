package com.scott.frenchvocab.data.content

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.scott.frenchvocab.domain.Conjugation
import com.scott.frenchvocab.domain.Example
import com.scott.frenchvocab.domain.Lexeme
import com.scott.frenchvocab.domain.Sense
import com.scott.frenchvocab.domain.VocabularyBook
import com.scott.frenchvocab.domain.WordForm
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Immutable content snapshot. Construct on an IO dispatcher.
 *
 * Each bundled asset has its own SHA-256-named file. Content updates therefore
 * cannot overwrite a file held by an old reader, and never touch the user DB.
 * Only this content directory is written. SQLite is opened READONLY and closed
 * after loading; getters subsequently perform no IO and remain valid after close.
 */
class ContentRepository internal constructor(context: Context, assetBytes: ByteArray) : AutoCloseable {
    constructor(context: Context) : this(context, context.assets.open(ASSET_NAME).use { it.readBytes() })
    private val wordsByUid: Map<String, Lexeme>
    private val wordList: List<Lexeme>
    private val bookList: List<VocabularyBook>

    init {
        val contentFile = synchronized(copyLock) { installAsset(context.applicationContext, assetBytes) }
        SQLiteDatabase.openDatabase(contentFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            require(db.version in SUPPORTED_SCHEMA_VERSIONS) { "不支持的法语词库格式：${db.version}" }
            db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") { "法语词库校验失败" }
            }
            db.rawQuery("SELECT value FROM content_meta WHERE key = 'language'", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "fr-FR") { "词库语言必须是 fr-FR" }
            }
            // Optional additive columns keep old bilingual books readable during content rollout.
            val senseChinese = if (db.hasColumn("sense", "chinese")) "COALESCE(chinese, '')" else "''"
            val exampleChinese = if (db.hasColumn("example", "chinese")) "COALESCE(e.chinese, '')" else "''"
            val senses = db.rows("SELECT lexeme_uid, english, spanish, $senseChinese FROM sense ORDER BY lexeme_uid, sort_order") {
                getString(0) to Sense(english = getString(1), spanish = getString(2), chinese = getString(3))
            }.groupValues()
            val examples = db.rows(
                "SELECT s.lexeme_uid, e.french, e.english, e.spanish, $exampleChinese FROM example e " +
                    "JOIN sense s ON e.sense_id = s.sense_id ORDER BY s.lexeme_uid, s.sort_order, e.sort_order"
            ) { getString(0) to Example(french = getString(1), english = getString(2), spanish = getString(3), chinese = getString(4)) }.groupValues()
            val forms = db.rows("SELECT lexeme_uid, label, form FROM word_form ORDER BY lexeme_uid, sort_order") {
                getString(0) to WordForm(getString(1), getString(2))
            }.groupValues()
            val conjugations = db.rows(
                "SELECT lexeme_uid, tense, pronoun, form FROM conjugation_form " +
                    "ORDER BY lexeme_uid, tense_order, person"
            ) { getString(0) to Conjugation(getString(1), getString(2), getString(3)) }.groupValues()
            wordList = db.rows(
                "SELECT l.lexeme_uid, l.lemma, p.ipa, l.part_of_speech, l.level, l.gender, " +
                    "v.verb_group, v.auxiliary, p.audio_asset FROM lexeme l " +
                    "JOIN pronunciation p ON p.lexeme_uid = l.lexeme_uid AND p.locale = 'fr-FR' " +
                    "LEFT JOIN verb_info v ON v.lexeme_uid = l.lexeme_uid ORDER BY l.sort_order"
            ) {
                val uid = getString(0)
                Lexeme(
                    uid = uid, lemma = getString(1), ipa = getString(2), partOfSpeech = getString(3),
                    level = getString(4), gender = getString(5), verbGroup = nullableString(6).orEmpty(),
                    auxiliary = nullableString(7).orEmpty(), senses = senses[uid].orEmpty(),
                    examples = examples[uid].orEmpty(), forms = forms[uid].orEmpty(),
                    conjugations = conjugations[uid].orEmpty(), audioAsset = nullableString(8),
                )
            }
            check(wordList.isNotEmpty() && wordList.all { it.senses.isNotEmpty() && it.examples.isNotEmpty() }) {
                "法语词库缺少词条内容"
            }
            wordsByUid = wordList.associateBy { it.uid }
            val members = db.rows("SELECT book_id, lexeme_uid FROM book_lexeme ORDER BY book_id, sort_order") {
                getString(0) to getString(1)
            }.groupValues()
            bookList = db.rows("SELECT book_id, title, description FROM vocabulary_book ORDER BY sort_order") {
                VocabularyBook(getString(0), getString(1), getString(2), members[getString(0)].orEmpty())
            }
            check(bookList.any { it.id == "essential-fr" } && bookList.all { book ->
                book.lexemeUids.isNotEmpty() && book.lexemeUids.all(wordsByUid::containsKey)
            }) { "法语词书关联不完整" }
        }
    }

    fun allWords(): List<Lexeme> = wordList
    fun books(): List<VocabularyBook> = bookList
    fun find(uid: String): Lexeme? = wordsByUid[uid]

    /** The SQLite handle is already closed; immutable snapshots are safe to retain. */
    override fun close() = Unit

    private fun <T> SQLiteDatabase.rows(sql: String, mapper: Cursor.() -> T): List<T> =
        rawQuery(sql, null).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.mapper()) } }

    private fun Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun SQLiteDatabase.hasColumn(table: String, column: String): Boolean =
        rows("PRAGMA table_info($table)") { getString(getColumnIndexOrThrow("name")) }.contains(column)

    private fun <T> List<Pair<String, T>>.groupValues(): Map<String, List<T>> =
        groupBy({ it.first }, { it.second })

    companion object {
        private val SUPPORTED_SCHEMA_VERSIONS = 1..2
        private const val ASSET_NAME = "french_content.db"
        private val copyLock = Any()

        private fun digest(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun installAsset(context: Context, bytes: ByteArray): File {
            val hash = digest(bytes)
            val directory = File(context.filesDir, "content")
            check(directory.isDirectory || directory.mkdirs()) { "无法创建词库目录" }
            val destination = File(directory, "french-content-$hash.db")
            if (destination.isFile && digest(destination.readBytes()) == hash) return destination
            val staging = File(directory, "french-content-$hash.copying")
            try {
                FileOutputStream(staging).use { stream ->
                    stream.write(bytes)
                    stream.fd.sync()
                }
                // Only a corrupt copy of this exact bundled asset can be replaced.
                check(!destination.exists() || destination.delete()) { "无法修复法语词库副本" }
                check(staging.renameTo(destination)) { "无法安装法语词库" }
            } finally {
                staging.delete()
            }
            return destination
        }
    }
}

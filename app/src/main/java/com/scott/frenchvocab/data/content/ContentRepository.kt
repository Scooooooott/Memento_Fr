package com.scott.frenchvocab.data.content

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.scott.frenchvocab.domain.BrowsePage
import com.scott.frenchvocab.domain.Conjugation
import com.scott.frenchvocab.domain.DEFAULT_BOOK_ID
import com.scott.frenchvocab.domain.Example
import com.scott.frenchvocab.domain.Lexeme
import com.scott.frenchvocab.domain.LexemePreview
import com.scott.frenchvocab.domain.Sense
import com.scott.frenchvocab.domain.VocabularyBook
import com.scott.frenchvocab.domain.WordForm
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

private sealed interface ContentSource {
    fun open(context: Context): InputStream

    data object Bundled : ContentSource {
        override fun open(context: Context): InputStream = context.assets.open("french_content.db")
    }

    data class Bytes(private val bytes: ByteArray) : ContentSource {
        override fun open(context: Context): InputStream = ByteArrayInputStream(bytes)
    }
}

/**
 * Immutable content catalog backed by the bundled read-only SQLite database.
 *
 * Startup retains only lightweight previews, UID membership and book metadata.
 * Full senses, examples, forms and conjugations are read by UID on the single
 * repository IO lane. The database stays open until [close].
 */
class ContentRepository private constructor(context: Context, source: ContentSource) : AutoCloseable {
    constructor(context: Context) : this(context, ContentSource.Bundled)
    internal constructor(context: Context, assetBytes: ByteArray) : this(context, ContentSource.Bytes(assetBytes))

    private val database: SQLiteDatabase
    private val uidList: List<String>
    private val previewList: List<LexemePreview>
    private val previewsByUid: Map<String, LexemePreview>
    private val searchableByUid: Map<String, String>
    private val allUidSet: Set<String>
    private val bookList: List<VocabularyBook>
    private val bookMembersById: Map<String, Set<String>>
    private val senseHasChinese: Boolean
    private val exampleHasChinese: Boolean

    init {
        val contentFile = synchronized(copyLock) { installAsset(context.applicationContext, source) }
        val opened = SQLiteDatabase.openDatabase(contentFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            require(opened.version in SUPPORTED_SCHEMA_VERSIONS) { "不支持的法语词库格式：${opened.version}" }
            opened.rawQuery("PRAGMA quick_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") { "法语词库校验失败" }
            }
            opened.rawQuery("SELECT value FROM content_meta WHERE key = 'language'", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "fr-FR") { "词库语言必须是 fr-FR" }
            }
            senseHasChinese = opened.hasColumn("sense", "chinese")
            exampleHasChinese = opened.hasColumn("example", "chinese")
            val senseChinese = if (senseHasChinese) "COALESCE(chinese, '')" else "''"
            val allSenses = opened.rows(
                "SELECT lexeme_uid, english, spanish, $senseChinese FROM sense ORDER BY lexeme_uid, sort_order"
            ) {
                getString(0) to Triple(getString(1), getString(2), getString(3))
            }.groupValues()
            val contentOrderPreviews = opened.rows(
                "SELECT l.lexeme_uid, l.lemma, l.part_of_speech, l.level FROM lexeme l ORDER BY l.sort_order"
            ) {
                val uid = getString(0)
                val first = allSenses[uid]?.firstOrNull() ?: error("法语词条缺少释义：$uid")
                LexemePreview(
                    uid = uid, lemma = getString(1), partOfSpeech = getString(2), level = getString(3),
                    english = first.first, spanish = first.second, chinese = first.third,
                )
            }
            uidList = contentOrderPreviews.map(LexemePreview::uid)
            previewList = contentOrderPreviews.sortedWith(
                compareBy<LexemePreview>({ it.lemma.searchKey() }, { it.uid })
            )
            check(previewList.isNotEmpty()) { "法语词库没有词条" }
            previewsByUid = previewList.associateBy(LexemePreview::uid)
            allUidSet = previewsByUid.keys
            searchableByUid = previewList.associate { preview ->
                preview.uid to buildList {
                    add(preview.lemma)
                    allSenses.getValue(preview.uid).forEach { sense ->
                        add(sense.first)
                        add(sense.second)
                        add(sense.third)
                    }
                }.joinToString("\u0000").searchKey()
            }
            val members = opened.rows(
                "SELECT book_id, lexeme_uid FROM book_lexeme ORDER BY book_id, sort_order"
            ) { getString(0) to getString(1) }.groupValues()
            bookList = opened.rows("SELECT book_id, title, description FROM vocabulary_book ORDER BY sort_order") {
                VocabularyBook(getString(0), getString(1), getString(2), members[getString(0)].orEmpty())
            }
            check(bookList.any { it.id == "essential-fr" } && bookList.all { book ->
                book.lexemeUids.isNotEmpty() && book.lexemeUids.all(allUidSet::contains)
            }) { "法语词书关联不完整" }
            bookMembersById = bookList.associate { it.id to it.lexemeUids.toSet() }
            database = opened
        } catch (error: Throwable) {
            opened.close()
            throw error
        }
    }

    val wordCount: Int get() = previewList.size

    fun allUids(): List<String> = uidList
    fun contains(uid: String): Boolean = uid in allUidSet
    fun containsBook(bookId: String): Boolean = bookId in bookMembersById
    fun books(): List<VocabularyBook> = bookList
    fun preview(uid: String): LexemePreview? = previewsByUid[uid]

    fun defaultBookId(): String =
        when {
            bookMembersById.containsKey(DEFAULT_BOOK_ID) -> DEFAULT_BOOK_ID
            bookMembersById.containsKey("essential-fr") -> "essential-fr"
            else -> bookList.first().id
        }

    /**
     * Filters and sorts lightweight previews off the main thread, returning one
     * stable 50-item window. The ViewModel discards stale query generations.
     */
    @Synchronized
    fun browse(
        bookId: String,
        query: String,
        favoritesOnly: Boolean,
        favoriteUids: Set<String>,
        offset: Int,
        limit: Int,
    ): BrowsePage {
        require(offset >= 0 && limit > 0)
        val members = bookId.takeIf(String::isNotBlank)?.let { bookMembersById[it] ?: emptySet() }
        val search = query.trim().searchKey()
        val filtered = previewList.filter { preview ->
            (members == null || preview.uid in members) &&
                (!favoritesOnly || preview.uid in favoriteUids) &&
                (search.isEmpty() || searchableByUid.getValue(preview.uid).contains(search))
        }
        val items = filtered.drop(offset).take(limit)
        return BrowsePage(items, filtered.size, offset + items.size < filtered.size)
    }

    /** Compatibility helper for repository tests and offline validation only. */
    fun allWords(): List<Lexeme> = allUids().mapNotNull(::find)

    @Synchronized
    fun find(uid: String): Lexeme? {
        if (uid !in allUidSet) return null
        val base = database.rawQuery(
            "SELECT l.lexeme_uid,l.lemma,p.ipa,l.part_of_speech,l.level,l.gender," +
                "v.verb_group,v.auxiliary,p.audio_asset FROM lexeme l " +
                "JOIN pronunciation p ON p.lexeme_uid=l.lexeme_uid AND p.locale='fr-FR' " +
                "LEFT JOIN verb_info v ON v.lexeme_uid=l.lexeme_uid WHERE l.lexeme_uid=?",
            arrayOf(uid),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else LexemeBase(
                uid = cursor.getString(0), lemma = cursor.getString(1), ipa = cursor.getString(2),
                partOfSpeech = cursor.getString(3), level = cursor.getString(4), gender = cursor.getString(5),
                verbGroup = cursor.nullableString(6).orEmpty(), auxiliary = cursor.nullableString(7).orEmpty(),
                audioAsset = cursor.nullableString(8),
            )
        } ?: return null
        val exampleChinese = if (exampleHasChinese) "COALESCE(e.chinese, '')" else "''"
        val examples = database.rows(
            "SELECT e.sense_id,e.french,e.english,e.spanish,$exampleChinese FROM example e " +
                "JOIN sense s ON s.sense_id=e.sense_id WHERE s.lexeme_uid=? " +
                "ORDER BY s.sort_order,e.sort_order",
            arrayOf(uid),
        ) {
            getString(0) to Example(getString(1), getString(2), getString(3), getString(4))
        }.groupValues()
        val senseChinese = if (senseHasChinese) "COALESCE(chinese, '')" else "''"
        val senses = database.rows(
            "SELECT sense_id,english,spanish,$senseChinese FROM sense WHERE lexeme_uid=? ORDER BY sort_order",
            arrayOf(uid),
        ) {
            val senseId = getString(0)
            Sense(getString(1), getString(2), getString(3), examples[senseId].orEmpty())
        }
        check(senses.isNotEmpty()) { "法语词条缺少释义：$uid" }
        val forms = database.rows(
            "SELECT label,form FROM word_form WHERE lexeme_uid=? ORDER BY sort_order", arrayOf(uid)
        ) { WordForm(getString(0), getString(1)) }
        val conjugations = database.rows(
            "SELECT tense,pronoun,form FROM conjugation_form WHERE lexeme_uid=? ORDER BY tense_order,person",
            arrayOf(uid),
        ) { Conjugation(getString(0), getString(1), getString(2)) }
        return Lexeme(
            uid = base.uid, lemma = base.lemma, ipa = base.ipa, partOfSpeech = base.partOfSpeech,
            level = base.level, gender = base.gender, verbGroup = base.verbGroup,
            auxiliary = base.auxiliary, senses = senses, examples = senses.flatMap(Sense::examples), forms = forms,
            conjugations = conjugations, audioAsset = base.audioAsset,
        )
    }

    @Synchronized
    override fun close() {
        if (database.isOpen) database.close()
    }

    private fun <T> SQLiteDatabase.rows(
        sql: String,
        selectionArgs: Array<String>? = null,
        mapper: Cursor.() -> T,
    ): List<T> = rawQuery(sql, selectionArgs).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.mapper()) }
    }

    private fun Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun SQLiteDatabase.hasColumn(table: String, column: String): Boolean =
        rows("PRAGMA table_info($table)") { getString(getColumnIndexOrThrow("name")) }.contains(column)

    private fun <T> List<Pair<String, T>>.groupValues(): Map<String, List<T>> =
        groupBy({ it.first }, { it.second })

    private data class LexemeBase(
        val uid: String,
        val lemma: String,
        val ipa: String,
        val partOfSpeech: String,
        val level: String,
        val gender: String,
        val verbGroup: String,
        val auxiliary: String,
        val audioAsset: String?,
    )

    companion object {
        private val SUPPORTED_SCHEMA_VERSIONS = 1..2
        private val copyLock = Any()

        private fun String.searchKey(): String =
            Normalizer.normalize(lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(COMBINING_MARKS, "")

        private val COMBINING_MARKS = Regex("\\p{M}+")

        private fun digest(file: File): String = FileInputStream(file).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().hex()
        }

        private fun ByteArray.hex(): String =
            joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun installAsset(context: Context, source: ContentSource): File {
            val directory = File(context.filesDir, "content")
            check(directory.isDirectory || directory.mkdirs()) { "无法创建词库目录" }
            val staging = File.createTempFile("french-content-", ".copying", directory)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                source.open(context).use { input ->
                    FileOutputStream(staging).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                val hash = digest.digest().hex()
                val destination = File(directory, "french-content-$hash.db")
                if (destination.isFile && digest(destination) == hash) return destination
                check(!destination.exists() || destination.delete()) { "无法修复法语词库副本" }
                check(staging.renameTo(destination)) { "无法安装法语词库" }
                return destination
            } finally {
                staging.delete()
            }
        }
    }
}

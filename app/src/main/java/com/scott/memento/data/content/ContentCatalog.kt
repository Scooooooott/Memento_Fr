package com.scott.memento.data.content

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.scott.memento.domain.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** One read-only schema for all packages; target-specific SQL is confined to conversion tools. */
class ContentCatalog private constructor(val language:LanguageSpec,installed:InstalledPackage,private val registry:LanguageCapabilities):AutoCloseable {
    constructor(context:Context,language:LanguageSpec):this(language,install(context,language),LanguageRegistry.formal)
    private val db:SQLiteDatabase
    val contentVersion:String=installed.description.contentVersion
    val packageHash:String=installed.description.sha256
    private val detailCache=object:LinkedHashMap<String,Detail>(64,0.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Detail>?)=size>64
    }
    init {
        validate(language,installed.description,registry)
        val opened=SQLiteDatabase.openDatabase(installed.file.path,null,SQLiteDatabase.OPEN_READONLY)
        try {
            require(opened.version==1)
            opened.rawQuery("PRAGMA quick_check",null).use{check(it.moveToFirst() && it.getString(0)=="ok")}
            opened.rawQuery("PRAGMA foreign_key_check",null).use{check(!it.moveToFirst())}
            opened.rawQuery("SELECT language_id FROM package_language",null).use{require(it.moveToFirst() && it.getString(0)==language.id && !it.moveToNext())}
            fun metadata(key:String)=opened.rawQuery("SELECT value FROM content_meta WHERE key=?",arrayOf(key)).use{require(it.moveToFirst());it.getString(0)}
            require(metadata("content_version")==contentVersion && metadata("normalization_version")==SearchKey.VERSION)
            opened.rawQuery("SELECT 1 FROM vocabulary_book WHERE book_id=? AND target_language=?",arrayOf(language.defaultBook,language.id)).use{require(it.moveToFirst()) { "默认词书不在内容包中" }}
            db=opened
        } catch(e:Throwable){opened.close();throw e}
    }
    @Synchronized fun count():Int=rows("SELECT COUNT(*) FROM lexeme"){getInt(0)}.single()
    @Synchronized fun books(displayLanguage:String="zh-Hans"):List<Book> = rows("""SELECT b.book_id,t.title,t.description,COUNT(m.lexeme_uid),b.level_system,b.level_value
        FROM vocabulary_book b JOIN book_translation t ON t.book_id=b.book_id AND t.learning_language=?
        LEFT JOIN book_lexeme m ON m.book_id=b.book_id GROUP BY b.book_id,t.title,t.description,b.sort_order ORDER BY b.sort_order""",arrayOf(displayLanguage)) {
        Book(getString(0),getString(1),getString(2),getInt(3),getString(4),getString(5))
    }
    @Synchronized fun studyIndex(learning:String):StudyContentIndex {
        val id=registry.requireLearningId(language.id,learning)
        val available=rows("""SELECT l.lexeme_uid FROM lexeme l WHERE EXISTS(SELECT 1 FROM sense s WHERE s.lexeme_uid=l.lexeme_uid)
            AND NOT EXISTS(SELECT 1 FROM sense s WHERE s.lexeme_uid=l.lexeme_uid AND NOT EXISTS
                (SELECT 1 FROM sense_translation t WHERE t.sense_id=s.sense_id AND t.learning_language=? AND trim(t.text)<>''))
            AND NOT EXISTS(SELECT 1 FROM example e JOIN sense s ON s.sense_id=e.sense_id WHERE s.lexeme_uid=l.lexeme_uid AND NOT EXISTS
                (SELECT 1 FROM example_translation t WHERE t.example_id=e.example_id AND t.learning_language=? AND trim(t.text)<>''))""",arrayOf(id,id)){getString(0)}.toSet()
        val members=rows("SELECT book_id,lexeme_uid FROM book_lexeme ORDER BY book_id,sort_order,lexeme_uid"){getString(0) to getString(1)}.groupBy({it.first},{it.second})
        val bookIds=rows("SELECT book_id FROM vocabulary_book ORDER BY sort_order"){getString(0)}
        return StudyContentIndex(language.id,id,contentVersion,available,bookIds.associateWith{members[it].orEmpty().filter(available::contains)},registry)
    }
    @Synchronized fun page(query:String,book:String,offset:Int=0):WordPage {
        require(offset>=0)
        val key=SearchKey.normalize(query)
        val join=if(book.isBlank()) "" else " JOIN book_lexeme b ON b.lexeme_uid=l.lexeme_uid AND b.book_id=?"
        val args=mutableListOf<String>().apply{if(book.isNotBlank()) add(book)}
        val filter=if(key.isEmpty()) "" else " JOIN lexeme_search k ON k.lexeme_uid=l.lexeme_uid WHERE k.search_key LIKE ? ESCAPE '\\'"
        if(key.isNotEmpty()) args.add("%${key.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")}%")
        val total=rows("SELECT COUNT(*) FROM lexeme l$join$filter",args.toTypedArray()){getInt(0)}.single()
        val ordering=if(key.isEmpty()) "" else "CASE WHEN k.lemma_key=? THEN 0 ELSE 1 END,"
        if(key.isNotEmpty()) args.add(key)
        args.add(offset.toString())
        val uids=rows("SELECT l.lexeme_uid FROM lexeme l$join$filter ORDER BY ${ordering}l.lemma COLLATE NOCASE,l.sort_order,l.lexeme_uid LIMIT 50 OFFSET ?",args.toTypedArray()){getString(0)}
        val summaries=summaries(uids)
        return WordPage(uids.mapNotNull{summaries[it]},total,offset+uids.size<total)
    }
    @Synchronized fun summary(uid:String):WordSummary? {
        registry.requireUid(language.id,uid)
        return summaries(listOf(uid))[uid]
    }
    /** Favorites remain in the user store. Only one page of translations is materialized. */
    @Synchronized fun favoritePage(favorites:Set<String>,query:String,offset:Int=0,book:String=""):WordPage {
        require(offset>=0)
        favorites.forEach{registry.requireUid(language.id,it)}
        val key=SearchKey.normalize(query)
        val pattern="%${key.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")}%"
        data class Candidate(val uid:String,val lemma:String,val rank:Int,val order:Int)
        val bookJoin=if(book.isBlank()) "" else " JOIN book_lexeme b ON b.lexeme_uid=l.lexeme_uid AND b.book_id=?"
        val candidates=favorites.toList().chunked(400).flatMap { chunk ->
            rows("""SELECT l.lexeme_uid,l.lemma,CASE WHEN k.lemma_key=? THEN 0 ELSE 1 END,l.sort_order
                FROM lexeme l$bookJoin JOIN lexeme_search k ON k.lexeme_uid=l.lexeme_uid
                WHERE l.lexeme_uid IN (${chunk.joinToString(","){"?"}}) AND k.search_key LIKE ? ESCAPE '\'""",
                (listOf(key)+(if(book.isBlank()) emptyList() else listOf(book))+chunk+pattern).toTypedArray()) {
                    Candidate(getString(0),getString(1),if(key.isEmpty()) 0 else getInt(2),getInt(3))
                }
        }.sortedWith(compareBy<Candidate>{it.rank}
            .thenComparator{a,b->compareSqliteText(a.lemma,b.lemma,ignoreAsciiCase=true)}
            .thenBy{it.order}.thenComparator{a,b->compareSqliteText(a.uid,b.uid)})
        val selected=candidates.drop(offset).take(50).map{it.uid}
        val summaries=summaries(selected)
        return WordPage(selected.mapNotNull{summaries[it]},candidates.size,offset+selected.size<candidates.size)
    }
    /** SQLite NOCASE folds ASCII only. Unicode case folding would disagree with ordinary page(). */
    private fun compareSqliteText(left:String,right:String,ignoreAsciiCase:Boolean=false):Int {
        fun bytes(value:String)=if(ignoreAsciiCase) value.map{if(it in 'A'..'Z') it+32 else it}.joinToString("").toByteArray(Charsets.UTF_8)
            else value.toByteArray(Charsets.UTF_8)
        val a=bytes(left);val b=bytes(right)
        for(index in 0 until minOf(a.size,b.size)) {
            val difference=(a[index].toInt() and 255)-(b[index].toInt() and 255)
            if(difference!=0) return difference
        }
        return a.size-b.size
    }
    private fun summaries(uids:List<String>):Map<String,WordSummary> {
        if(uids.isEmpty()) return emptyMap()
        val result=linkedMapOf<String,WordSummary>()
        rows("""SELECT l.lexeme_uid,l.lemma,l.part_of_speech,l.level_value,l.level_system,t.learning_language,t.text
            FROM lexeme l JOIN sense s ON s.sense_id=(SELECT sense_id FROM sense WHERE lexeme_uid=l.lexeme_uid ORDER BY sort_order,sense_id LIMIT 1)
            JOIN sense_translation t ON t.sense_id=s.sense_id WHERE l.lexeme_uid IN (${uids.joinToString(","){"?"}})
            ORDER BY l.sort_order,t.learning_language""",uids.toTypedArray()) {
            val uid=getString(0)
            val previous=result[uid]
            val translations=previous?.translations.orEmpty()+(getString(5) to getString(6))
            result[uid]=WordSummary(uid,getString(1),getString(2),getString(3),translations,getString(4))
        }
        return result
    }
    @Synchronized fun detail(uid:String):Detail? {
        registry.requireUid(language.id,uid)
        detailCache[uid]?.let{return it}
        val summary=summary(uid) ?: return null
        val gender=rows("SELECT gender FROM lexeme WHERE lexeme_uid=?",arrayOf(uid)){getString(0)}.single()
        val readings=rows("SELECT locale,reading_type,reading_text,audio_asset FROM pronunciation WHERE lexeme_uid=? ORDER BY locale,reading_type",arrayOf(uid)) {
            Reading(getString(0),getString(1),getString(2),if(isNull(3)) null else getString(3))
        }
        val meanings=rows("""SELECT t.sense_id,t.learning_language,t.text FROM sense_translation t JOIN sense s ON s.sense_id=t.sense_id
            WHERE s.lexeme_uid=? ORDER BY s.sort_order,t.learning_language""",arrayOf(uid)){Triple(getString(0),getString(1),getString(2))}.groupBy{it.first}
        val examples=rows("SELECT e.example_id,e.sense_id,e.source_text FROM example e JOIN sense s ON s.sense_id=e.sense_id WHERE s.lexeme_uid=? ORDER BY s.sort_order,e.sort_order,e.example_id",arrayOf(uid)) {
            Triple(getString(0),getString(1),getString(2))
        }
        val exampleTranslations=rows("""SELECT t.example_id,t.learning_language,t.text FROM example_translation t
            JOIN example e ON e.example_id=t.example_id JOIN sense s ON s.sense_id=e.sense_id WHERE s.lexeme_uid=? ORDER BY t.learning_language""",arrayOf(uid)) {
            Triple(getString(0),getString(1),getString(2))
        }.groupBy{it.first}
        val groupedExamples=examples.groupBy{it.second}
        val senses=rows("SELECT sense_id FROM sense WHERE lexeme_uid=? ORDER BY sort_order,sense_id",arrayOf(uid)) {
            val id=getString(0)
            Sense(id,meanings[id].orEmpty().associate{it.second to it.third},groupedExamples[id].orEmpty().map { row ->
                Example(row.first,row.third,exampleTranslations[row.first].orEmpty().associate{it.second to it.third})
            })
        }
        val forms=rows("SELECT label,form,sort_order,features_json FROM word_form WHERE lexeme_uid=? ORDER BY sort_order",arrayOf(uid)) {
            WordForm(getString(0),getString(1),getInt(2),getString(3))
        }
        val features=rows("SELECT name,value FROM grammar_feature WHERE lexeme_uid=? ORDER BY name",arrayOf(uid)){getString(0) to getString(1)}.toMap()
        val paradigmForms=rows("SELECT paradigm_id,subject_key,pronoun,form,sort_order FROM conjugation_form WHERE lexeme_uid=? ORDER BY sort_order",arrayOf(uid)) {
            getString(0) to ParadigmForm(getString(1),getString(2),getString(3),getInt(4))
        }.groupBy({it.first},{it.second})
        val paradigms=rows("SELECT paradigm_id,mood,tense,label,region,note,sort_order FROM conjugation_paradigm WHERE lexeme_uid=? ORDER BY sort_order",arrayOf(uid)) {
            Paradigm(getString(0),getString(1),getString(2),getString(3),getString(4),getString(5),getInt(6),paradigmForms[getString(0)].orEmpty())
        }
        return Detail(summary,readings.firstOrNull{it.type=="IPA" && it.locale==language.locale}?.text.orEmpty(),gender,senses,
            forms.map{it.label to it.form},paradigms.flatMap{p->p.forms.map{"${p.label} · ${it.pronoun}" to it.form}},
            readings,features,paradigms,forms).also{detailCache[uid]=it}
    }
    @Synchronized override fun close(){detailCache.clear();db.close()}
    private fun <T> rows(sql:String,args:Array<String> = emptyArray(),mapper:Cursor.()->T):List<T> = db.rawQuery(sql,args).use{c->buildList{while(c.moveToNext()) add(c.mapper())}}
    companion object {
    /** A verified package plus an explicit capability registry uses the same reader as the formal app. */
    fun openVerifiedPackage(file:File,language:LanguageSpec,description:ContentPackageDescription,registry:LanguageCapabilities):ContentCatalog {
        validate(language,description,registry)
        require(file.isFile && file.length()==description.bytes && hash(file)==description.sha256) { "词库校验和不匹配" }
        return ContentCatalog(language,InstalledPackage(file,description),registry)
    }
    private fun validate(language:LanguageSpec,description:ContentPackageDescription,registry:LanguageCapabilities) {
        require(language==registry.get(language.id)) { "词库声明与语言注册不一致" }
        require(description.target==language.id && description.asset==language.asset)
        require(description.schemaVersion==1 && description.normalizationVersion==SearchKey.VERSION)
        require(description.learningLanguages==language.learningLanguages && description.contentVersion.isNotBlank())
        require(description.sha256.matches(Regex("[0-9a-f]{64}")) && description.bytes>0)
    }
    private fun install(context:Context,language:LanguageSpec):InstalledPackage {
        val manifest=JSONObject(context.assets.open("content/manifest.json").bufferedReader().use{it.readText()})
        require(manifest.getInt("schema_version")==1 && manifest.getString("normalization_version")==SearchKey.VERSION)
        val description=ContentPackageDescription.from(manifest.getJSONObject("packages").getJSONObject(language.id),manifest.getString("normalization_version"))
        validate(language,description,LanguageRegistry.formal)
        val directory=File(context.filesDir,"content").apply{check(isDirectory || mkdirs())}
        val destination=File(directory,"${language.id}-${description.sha256}.db")
        if(!destination.isFile || destination.length()!=description.bytes || hash(destination)!=description.sha256) {
            val staging=File.createTempFile("${language.id}-",".copying",directory)
            try {
                context.assets.open(language.asset).use {input->FileOutputStream(staging).use{output->input.copyTo(output);output.fd.sync()}}
                check(staging.length()==description.bytes && hash(staging)==description.sha256) { "词库校验和不匹配" }
                check(staging.renameTo(destination)) { "无法安装已校验词库" }
            } finally {staging.delete()}
        }
        return InstalledPackage(destination,description)
    }
    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer=ByteArray(64*1024);while(true){val size=input.read(buffer);if(size<0) break;digest.update(buffer,0,size)} }
        return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    }
}
private data class InstalledPackage(val file:File,val description:ContentPackageDescription)
data class ContentPackageDescription(val target:String,val asset:String,val schemaVersion:Int,val normalizationVersion:String,
    val sha256:String,val bytes:Long,val contentVersion:String,val learningLanguages:Set<String>) {
    companion object {
        fun from(entry:JSONObject,normalizationVersion:String)=ContentPackageDescription(entry.getString("target_language"),entry.getString("asset"),entry.getInt("schema_version"),normalizationVersion,
            entry.getString("sha256"),entry.getLong("bytes"),entry.getString("content_version"),entry.getJSONArray("learning_languages").let{array->(0 until array.length()).map{array.getString(it)}.toSet()})
    }
}

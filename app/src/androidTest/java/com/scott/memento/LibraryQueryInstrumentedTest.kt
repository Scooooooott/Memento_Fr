package com.scott.memento

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.memento.data.content.ContentCatalog
import com.scott.memento.domain.Languages
import com.scott.memento.domain.SearchKey
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Real formal packs, shallow queries only; this class never creates, clears or scores a user DB. */
class LibraryQueryInstrumentedTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun dedicatedEmulatorOnly() {
        fun property(name:String)=InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("getprop $name").use{descriptor->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use{it.readText().trim()}
            }
        check(property("ro.kernel.qemu")=="1") { "UI4 checks require the dedicated emulator" }
        check(property("ro.boot.qemu.avd_name")=="MementoApi35") { "UI4 checks require MementoApi35" }
    }

    @Test fun ordinaryAndFavoritePagesShareSqliteOrderAndFiftyToOneHundredFiftyHasNoDuplicates() {
        for(target in listOf("fr","es")) ContentCatalog(context,Languages.get(target)).use{catalog->
            val ordinary=(0..3).flatMap{catalog.page("","",it*50).words}
            val favorites=ordinary.map{it.uid}.toSet()
            val actual=(0..2).map{catalog.favoritePage(favorites,"",it*50)}
            assertEquals(listOf(50,50,50),actual.map{it.words.size})
            assertTrue(actual.all{it.total==200 && it.hasMore})
            assertEquals(ordinary.take(150),actual.flatMap{it.words})
            assertEquals(150,actual.flatMap{it.words}.map{it.uid}.toSet().size)
            assertEquals(catalog.page("","",0),catalog.page("","",0))
            assertEquals(ordinary.take(150).map{it.uid},rawExpected(catalog,"","",favorites).take(150))
            if(target=="fr") {
                // NOCASE does not fold É to é, unlike JVM CASE_INSENSITIVE_ORDER.
                val mixed=listOf("eau","exemple","ensemble","Équateur","école","étage")
                    .flatMap{lemma->catalog.page(lemma,"").words.filter{it.lemma==lemma}}
                    .map{it.uid}.toSet()
                assertTrue(mixed.size>=6)
                assertEquals(rawExpected(catalog,"","",mixed),catalog.favoritePage(mixed,"").words.map{it.uid})
            }
        }
    }

    @Test fun bookSearchAndFavoritesConjunctionUsesAllThreeConditionsIncludingChunkedIds() {
        for(target in listOf("fr","es")) ContentCatalog(context,Languages.get(target)).use{catalog->
            val ordered=rawExpected(catalog,"","",null)
            val favorites=ordered.take(1001).toSet() // Three <=400-ID SQL chunks.
            val book=catalog.language.defaultBook
            for(query in listOf("","a","e")) {
                val expected=rawExpected(catalog,query,book,favorites)
                val first=catalog.favoritePage(favorites,query,0,book)
                val next=catalog.favoritePage(favorites,query,50,book)
                assertEquals(expected.size,first.total);assertEquals(expected.size,next.total)
                assertEquals(expected.take(50),first.words.map{it.uid})
                assertEquals(expected.drop(50).take(50),next.words.map{it.uid})
                assertEquals(50<expected.size,first.hasMore)
                assertEquals(50+next.words.size<expected.size,next.hasMore)
            }
            assertTrue(catalog.favoritePage(favorites,"",0,book).total<1001)
            assertEquals(0,catalog.favoritePage(favorites,"",0,"missing-book").total)
        }
    }

    @Test fun removingFavoriteAndReloadingFromZeroFillsTheGapWithoutLosingNextUid() {
        for(target in listOf("fr","es")) ContentCatalog(context,Languages.get(target)).use{catalog->
            val favorites=rawExpected(catalog,"","",null).take(220).toSet()
            val before=(0..2).flatMap{catalog.favoritePage(favorites,"",it*50).words}
            val removed=before[7].uid
            val remaining=favorites-removed
            val refreshed=(0..2).flatMap{catalog.favoritePage(remaining,"",it*50).words}
            val next=catalog.favoritePage(remaining,"",150).words
            val expected=rawExpected(catalog,"","",remaining)
            assertEquals(expected.take(150),refreshed.map{it.uid})
            assertEquals(expected.drop(150).take(50),next.map{it.uid})
            assertFalse(refreshed.any{it.uid==removed})
            assertEquals(before.drop(8).map{it.uid},refreshed.drop(7).take(142).map{it.uid})
            assertEquals(200,(refreshed+next).map{it.uid}.toSet().size)
        }
    }

    @Test fun literalWildcardsBoundParametersAccentsAndNonLatinTextRemainCorrectInCombinations() {
        for(target in listOf("fr","es")) ContentCatalog(context,Languages.get(target)).use{catalog->
            val favorites=(rawExpected(catalog,"","",null).take(1001)+
                listOf("être","negociación","谈判").flatMap{catalog.page(it,"").words.map{word->word.uid}}).toSet()
            val book=catalog.language.defaultBook
            for(query in listOf("%","_","\\","' OR 1=1 --","unlikely_literal_百分号%\\","谈判")) {
                for(scopeBook in listOf("",book)) {
                    val expected=rawExpected(catalog,query,scopeBook,favorites)
                    val actual=catalog.favoritePage(favorites,query,0,scopeBook)
                    assertEquals("$target / $scopeBook / $query",expected.size,actual.total)
                    assertEquals(expected.take(50),actual.words.map{it.uid})
                    assertTrue(actual.total<catalog.count())
                }
            }
            val accented=if(target=="fr") "être" else "negociación"
            val plain=if(target=="fr") "etre" else "negociacion"
            assertEquals(catalog.favoritePage(favorites,accented),catalog.favoritePage(favorites,plain))
            assertTrue(catalog.favoritePage(favorites,accented).words.isNotEmpty())
            assertEquals(0,catalog.favoritePage(emptySet(),"",0,book).total)
            assertEquals(0,catalog.favoritePage(setOf("$target:missing:noun:1"),"",0,book).total)
        }
    }

    @Test fun shallowPreviewsHaveAllSixCurrentLearningDirectionsAndMatchTheFirstSense() {
        var directions=0
        for(target in listOf("fr","es")) ContentCatalog(context,Languages.get(target)).use{catalog->
            val favorites=(0..2).flatMap{catalog.page("",catalog.language.defaultBook,it*50).words}.map{it.uid}.toSet()
            val page=catalog.favoritePage(favorites,"",0,catalog.language.defaultBook)
            assertEquals(50,page.words.size)
            readOnly(catalog).use {db->
                for(learning in catalog.language.learningLanguages) {
                    directions++
                    for(word in page.words) {
                        val expected=db.rawQuery("""SELECT t.text FROM sense s JOIN sense_translation t ON t.sense_id=s.sense_id
                            WHERE s.lexeme_uid=? AND t.learning_language=? ORDER BY s.sort_order,s.sense_id LIMIT 1""",
                            arrayOf(word.uid,learning)).use{cursor->check(cursor.moveToFirst());cursor.getString(0)}
                        assertTrue(expected.isNotBlank());assertEquals(expected,word.translations[learning])
                    }
                }
            }
        }
        assertEquals(6,directions)
    }

    private fun readOnly(catalog:ContentCatalog)=SQLiteDatabase.openDatabase(
        File(context.filesDir,"content/${catalog.language.id}-${catalog.packageHash}.db").path,
        null,SQLiteDatabase.OPEN_READONLY)

    /** Independent SQL oracle using the ordinary query's actual SQLite collation, not the Kotlin comparator. */
    private fun rawExpected(catalog:ContentCatalog,query:String,book:String,favorites:Set<String>?):List<String> {
        val key=SearchKey.normalize(query)
        val args=mutableListOf<String>()
        val join=if(book.isBlank()) "" else " JOIN book_lexeme b ON b.lexeme_uid=l.lexeme_uid AND b.book_id=?".also{args+=book}
        val search=if(key.isEmpty()) "" else " JOIN lexeme_search k ON k.lexeme_uid=l.lexeme_uid WHERE k.search_key LIKE ? ESCAPE '\\'".also {
            args+="%${key.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")}%"
        }
        val rank=if(key.isEmpty()) "" else "CASE WHEN k.lemma_key=? THEN 0 ELSE 1 END,".also{args+=key}
        val sql="SELECT l.lexeme_uid FROM lexeme l$join$search ORDER BY ${rank}l.lemma COLLATE NOCASE,l.sort_order,l.lexeme_uid"
        return readOnly(catalog).use{db->db.rawQuery(sql,args.toTypedArray()).use{cursor->
            buildList {while(cursor.moveToNext()){val uid=cursor.getString(0);if(favorites==null || uid in favorites) add(uid)}}
        }}
    }
}

package com.scott.memento

import androidx.test.platform.app.InstrumentationRegistry
import com.scott.memento.data.content.ContentCatalog
import com.scott.memento.data.user.StudyRepository
import com.scott.memento.data.user.UserDatabase
import com.scott.memento.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class UnifiedContentInstrumentedTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun allSixDirectionsHaveCompleteStudyIndexesAndOrderedBooks() {
        for(language in Languages.available) ContentCatalog(context,language).use { catalog ->
            val expected=if(language.id=="fr") 19465 else 14290
            val all=language.learningLanguages.map{catalog.studyIndex(it)}
            assertTrue(all.all{it.available.size==expected})
            assertTrue(all.all{it.version==catalog.contentVersion})
            assertTrue(all.all{it.available==all.first().available && it.books==all.first().books})
            assertEquals(catalog.books().associate{it.id to it.count},all.first().books.mapValues{it.value.size})
            val first=all.first().books.getValue(language.defaultBook).first()
            assertEquals(if(language.id=="fr") "fr:le:determiner:1" else "es:el:determiner:1",first)
        }
    }
    @Test fun genericDetailsKeepReadingsGrammarAndVariableParadigmMetadata() {
        ContentCatalog(context,Languages.get("fr")).use { catalog ->
            val detail=catalog.detail("fr:être:verb:1")!!
            assertEquals("CEFR",detail.summary.levelSystem)
            assertEquals(3,detail.paradigms.size);assertEquals(18,detail.conjugations.size)
            assertTrue(detail.paradigms.all{it.forms.size==6 && it.forms.map{f->f.subjectKey}==listOf("1","2","3","4","5","6")})
            assertTrue(detail.grammarFeatures.keys.containsAll(listOf("verb_group","auxiliary","past_participle","present_participle")))
            assertTrue(detail.readings.any{it.locale=="fr-FR" && it.type=="IPA" && it.text==detail.ipa && it.audioAsset==null})
            assertEquals(detail.forms,detail.formDetails.map{it.label to it.form})
        }
        ContentCatalog(context,Languages.get("es")).use { catalog ->
            val detail=catalog.detail("es:ser:verb:1")!!
            assertTrue(detail.paradigms.isNotEmpty())
            assertEquals("1",detail.grammarFeatures["is_irregular"])
            assertEquals("0",detail.grammarFeatures["is_reflexive"])
            assertTrue(detail.grammarFeatures.keys.containsAll(listOf("participle","gerund")))
            assertTrue(detail.paradigms.any{it.id=="indicativo_presente" && it.mood=="indicativo" && it.tense=="presente"})
            assertTrue(detail.paradigms.flatMap{it.forms}.any{it.subjectKey=="yo" && it.form=="soy"})
            assertEquals(detail.paradigms.sumOf{it.forms.size},detail.conjugations.size)
        }
    }
    @Test fun precomputedSearchHandlesAccentsLiteralWildcardsAndStablePagination() {
        ContentCatalog(context,Languages.get("fr")).use { catalog ->
            assertEquals(catalog.page("être",""),catalog.page("etre",""))
            assertEquals("fr:être:verb:1",catalog.page("etre","").words.first().uid)
            assertEquals(0,catalog.page("unlikely_literal_百分号%\\","").total)
            assertTrue(catalog.page("%","").total<catalog.count())
            val first=catalog.page("","",0);val second=catalog.page("","",50)
            assertEquals(first,catalog.page("","",0))
            assertTrue(first.words.map{it.uid}.intersect(second.words.map{it.uid}.toSet()).isEmpty())
        }
        ContentCatalog(context,Languages.get("es")).use { catalog ->
            assertEquals(catalog.page("negociación",""),catalog.page("negociacion",""))
            assertTrue(catalog.page("谈判","").words.isNotEmpty())
        }
    }
    @Test fun favoritePagesUseLiteralSearchBoundParametersAndKeepPagingStable() {
        for(target in listOf("fr","es")) ContentCatalog(context,Languages.get(target)).use{catalog->
            val favorites=catalog.studyIndex("zh-Hans").available.take(1001).toSet()
            val first=catalog.favoritePage(favorites,"",0);val second=catalog.favoritePage(favorites,"",50)
            assertEquals(1001,first.total);assertEquals(50,first.words.size);assertEquals(50,second.words.size)
            assertEquals(first,catalog.favoritePage(favorites,"",0))
            assertTrue(first.words.map{it.uid}.intersect(second.words.map{it.uid}.toSet()).isEmpty())
            assertEquals(0,catalog.favoritePage(favorites,"unlikely_literal_百分号%\\").total)
            assertEquals(0,catalog.favoritePage(setOf("$target:missing:noun:1"),"").total)
        }
        ContentCatalog(context,Languages.get("fr")).use{catalog->
            val favorites=setOf("fr:être:verb:1","fr:avoir:verb:1")
            assertEquals("fr:être:verb:1",catalog.favoritePage(favorites,"etre").words.single().uid)
        }
    }
    @Test fun convertedPackageKeepsP3PlanIdentityOrderAndRevealState() {
        val name="memento-test-p4-compat.db"
        context.deleteDatabase(name)
        try {
            ContentCatalog(context,Languages.get("fr")).use { catalog -> UserDatabase(context,name).use { store ->
                val oldVersion="3309f470e1191357c688ba7f701515234a5463aa9ea23b30472d2d0b714cc585"
                assertEquals(oldVersion,catalog.contentVersion);assertNotEquals(oldVersion,catalog.packageHash)
                val now=Instant.parse("2026-10-09T02:00:00Z").toEpochMilli()
                val db=store.writableDatabase
                db.execSQL("UPDATE app_settings SET study_zone_id='Asia/Shanghai'")
                db.execSQL("INSERT INTO language_settings(target_language,study_mode,daily_target,book_id) VALUES('fr','STANDARD',2,?)",arrayOf(catalog.language.defaultBook))
                db.execSQL("""INSERT INTO daily_plan(target_language,business_date,zone_id,created_at,due_cutoff_at,study_mode,
                    target_snapshot,book_snapshot,content_version,policy_version,eligible_review_count,status)
                    VALUES('fr','2026-10-09','Asia/Shanghai',?,?,'STANDARD',2,?,?,1,0,'ACTIVE')""",
                    arrayOf(now,Instant.parse("2026-10-09T16:00:00Z").toEpochMilli(),catalog.language.defaultBook,oldVersion))
                db.execSQL("INSERT INTO plan_revision VALUES(1,1,?,'CREATED','{}')",arrayOf(now))
                db.execSQL("""INSERT INTO daily_plan_item(plan_id,target_language,lexeme_uid,queue_kind,position,is_new,revealed)
                    VALUES(1,'fr','fr:le:determiner:1','NORMAL',0,1,1),(1,'fr','fr:de:preposition:1','NORMAL',1,1,0)""")
                val repo=StudyRepository(store,Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC))
                val index=catalog.studyIndex("zh-Hans")
                val state=repo.ensureDailyPlan(index)
                assertEquals(1L,state.planId);assertEquals(1,state.revision)
                assertEquals("fr:le:determiner:1",state.next!!.uid);assertTrue(state.next.revealed)
                val result=repo.rate(index,state.request("fr","p4-compat"),Rating.GOOD)
                assertEquals(ScoreResult.APPLIED,result.result)
                assertEquals("fr:de:preposition:1",result.snapshot.next!!.uid)
                assertEquals(1,result.snapshot.stats.uniqueWords)
            } }
        } finally { context.deleteDatabase(name) }
    }
}

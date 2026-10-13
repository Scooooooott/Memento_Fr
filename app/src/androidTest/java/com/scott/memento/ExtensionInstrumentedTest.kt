package com.scott.memento

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.memento.data.content.ContentCatalog
import com.scott.memento.data.user.*
import com.scott.memento.data.migration.LegacySource
import com.scott.memento.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ExtensionInstrumentedTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    @Test fun fourTargetsUseSameReaderForLevelsReadingsBooksAndVariableGrammar() {
        val expected=mapOf("de" to 4,"nl" to 2,"ja" to 2,"ko" to 4)
        val evidence=JSONObject()
        for((target,rows) in expected)ExtensionFixture.read(target){reader,registry->
            val index=reader.studyIndex("zh-Hans");val detail=reader.detail("$target:fixture:0:1")!!
            val system=when(target){"ja"->"JLPT";"ko"->"TOPIK";else->"CEFR"}
            assertEquals(system,detail.summary.levelSystem);assertEquals(system,reader.books().single().levelSystem)
            assertEquals(reader.count(),index.available.size);assertEquals(rows,detail.paradigms.single().forms.size)
            assertEquals("测试能力",detail.grammarFeatures["fixture_grammar"]);assertTrue(detail.formDetails.single().featuresJson.contains("fixture"))
            assertEquals("测试地区",detail.paradigms.single().region);assertTrue(detail.readings.all{it.audioAsset==null})
            if(target in setOf("ja","ko")){assertEquals("",detail.ipa);assertTrue(detail.readings.any{it.type in setOf("KANA","HANGUL")});assertTrue(detail.readings.any{it.type in setOf("ROMAJI","ROMANIZATION")})}
            else assertTrue(detail.ipa.isNotBlank())
            if("de" in registry.get(target).learningLanguages)assertTrue(reader.books("de").single().title.endsWith("/ de"))
            evidence.put(target,JSONObject().put("words",reader.count()).put("levelSystem",system).put("paradigmRows",rows).put("readingTypes",JSONArray(detail.readings.map{it.type})))
        }
        File(File(context.getExternalFilesDir(null),"qa").apply{mkdirs()},"p7-extension-reader.json").writeText(evidence.toString(2))
        assertEquals(listOf("fr","es"),Languages.available.map{it.id})
    }
    @Test fun japaneseSearchKeepsVoicingAndKoreanCompatibilityAndTranslationSearch() {
        ExtensionFixture.read("ja"){reader,_->
            assertEquals("ja:fixture:1:1",reader.page("か","").words.single().uid)
            assertEquals("ja:fixture:2:1",reader.page("が","").words.single().uid)
            assertEquals(reader.page("が",""),reader.page("か\u3099",""))
            assertEquals("ja:fixture:0:1",reader.page("essen","").words.single().uid)
            assertEquals("ja:fixture:2:1",reader.favoritePage(setOf("ja:fixture:1:1","ja:fixture:2:1"),"が").words.single().uid)
        }
        ExtensionFixture.read("ko"){reader,_->assertEquals(reader.page("한글",""),reader.page("한글",""));assertEquals("ko:fixture:0:1",reader.page("吃","").words.single().uid)}
    }
    @Test fun packagedPythonKeysAndActualAndroidNormalizationAgree() {
        val vectors=JSONArray(instrument.context.assets.open("search-vectors.json").bufferedReader().use{it.readText()})
        assertEquals(41,vectors.length())
        for(i in 0 until vectors.length()){val row=vectors.getJSONObject(i);assertEquals(row.getString("input"),row.getString("expected"),SearchKey.normalize(row.getString("input")))}
    }
    @Test fun newLearningLanguageCoverageChangesOnlyRowsAndNeverFallsBack() {
        ExtensionFixture.read("ja"){reader,_->
            val zh=reader.studyIndex("zh-Hans");val de=reader.studyIndex("de")
            assertEquals(3,zh.available.size);assertEquals(setOf("ja:fixture:0:1"),de.available)
            val missing=reader.detail("ja:fixture:1:1")!!;assertFalse(missing.senses.single().translations.containsKey("de"))
            assertFalse(missing.senses.single().examples.single().translations.containsKey("de"))
            assertEquals(zh.version,de.version);assertEquals("essen",reader.detail("ja:fixture:0:1")!!.senses.single().translations["de"])
        }
        val file=ExtensionFixture.copy("ja")
        try {SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use{db->
            for(table in listOf("sense_translation","example_translation","book_translation"))db.rawQuery("PRAGMA table_info($table)",null).use{c->
                val fields=buildList{while(c.moveToNext())add(c.getString(1))};assertTrue("learning_language" in fields);assertTrue(fields.none{it in setOf("german","english","japanese","korean","de","en","ja","ko")})
            }
        }} finally{file.delete()}
    }
    @Test fun sharedUserRepositoryPlansScoresAndSwitchesLearningWithoutChangingIdentityOrQuota() {
        val name="memento-test-p7-user.db";context.deleteDatabase(name)
        val clock=Clock.fixed(Instant.parse("2026-10-10T04:00:00Z"),ZoneOffset.UTC)
        try {ExtensionFixture.read("ja"){reader,registry->UserDatabase(context,name).use{store->
            val db=store.writableDatabase;db.execSQL("INSERT INTO target_language_registry VALUES('ja')")
            val study=StudyRepository(store,clock);val app=AppRepository(store,study,clock,registry)
            val index=reader.studyIndex("zh-Hans")
            val start=app.save(index,StudyPolicy(StudyMode.STANDARD,2),"p7-ja","Asia/Shanghai",PlaybackPreferences(AutoPlay.NEVER))
            val first=start.next!!.uid;val request=start.request("ja","p7-rate",registry)
            val revealed=study.reveal(index,request);assertEquals(ScoreResult.APPLIED,revealed.result)
            val scored=study.rate(index,request,Rating.GOOD);assertEquals(ScoreResult.APPLIED,scored.result)
            assertEquals(ScoreResult.ALREADY_APPLIED,study.rate(index,request,Rating.GOOD).result)
            app.toggleFavorite("ja",first);assertEquals(setOf(first),app.favorites("ja"))
            app.select(Selection("ja","en",registry));assertEquals("en",app.preferences().selection!!.learning)
            val switched=study.ensureDailyPlan(reader.studyIndex("en"));assertEquals(scored.snapshot,switched)
            assertEquals(1,app.overview(reader.studyIndex("en"),switched).learned.size)
            assertEquals(1,switched.normalRemaining);assertEquals(1,switched.stats.uniqueWords)
            assertNull(app.language("ko"))
            assertThrows(IllegalArgumentException::class.java){AppRepository(store,study,clock).select(Selection("ja","en",registry))}
            assertEquals("en",app.preferences().selection!!.learning)
            db.rawQuery("PRAGMA foreign_key_check",null).use{assertFalse(it.moveToFirst())}
        }}} finally{context.deleteDatabase(name)}
    }
    @Test fun verifiedPackageBoundaryRejectsHashManifestRegistryAndEnglishMismatches() {
        val file=ExtensionFixture.copy("ja");val registry=LanguageCapabilities(ExtensionFixture.specifications());val description=ExtensionFixture.description("ja")
        val hash=LegacySource.sha(file)
        try {
            for(bad in listOf(description.copy(sha256="0".repeat(64)),description.copy(target="ko"),description.copy(learningLanguages=setOf("en")),description.copy(normalizationVersion="unknown")))
                assertThrows(IllegalArgumentException::class.java){ContentCatalog.openVerifiedPackage(file,registry.get("ja"),bad,registry)}
            assertThrows(IllegalArgumentException::class.java){ContentCatalog.openVerifiedPackage(file,registry.get("ja"),description,LanguageRegistry.formal)}
            assertThrows(IllegalArgumentException::class.java){ContentCatalog.openVerifiedPackage(file,registry.get("ja").copy(id="en"),description.copy(target="en"),registry)}
            assertEquals(hash,LegacySource.sha(file))
        } finally{file.delete()}
    }
}

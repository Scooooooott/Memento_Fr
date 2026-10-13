package com.scott.memento

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import android.content.Intent
import com.scott.memento.data.user.UserDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.memento.data.content.ContentCatalog
import com.scott.memento.domain.Languages
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.io.File
import java.io.FileOutputStream

class CatalogInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario:ActivityScenario<MainActivity>?=null
    private val name="memento-test-catalog-ui.db"
    @Before fun prepare() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(name)
        UserDatabase(context,name).use{store->
            store.writableDatabase.execSQL("INSERT INTO language_settings(target_language,study_mode,daily_target,book_id,auto_play) VALUES('fr','STANDARD',2,'flelex-a1-high-1000','NEVER')")
            store.writableDatabase.execSQL("UPDATE app_settings SET onboarding_completed=1,target_language='fr',learning_language='zh-Hans' WHERE id=1")
        }
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java).putExtra("qa_user_database",name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    @After fun finish(){scenario?.close();InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name)}

    @Test fun bothUnifiedCatalogsKeepIdsBooksSensesExamplesAndConjugations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for((id,count,bookCount,uid) in listOf(Case("fr",19465,13,"fr:être:verb:1"),Case("es",14290,17,"es:ser:verb:1"))) {
            ContentCatalog(context,Languages.get(id)).use { catalog ->
                assertEquals(count,catalog.count()); assertEquals(bookCount,catalog.books().size)
                val first=catalog.page("","",0); val next=catalog.page("","",50)
                assertEquals(50,first.words.size); assertTrue(first.hasMore)
                assertTrue(first.words.map{it.uid}.toSet().intersect(next.words.map{it.uid}.toSet()).isEmpty())
                val detail=catalog.detail(uid)!!
                assertEquals(uid,detail.summary.uid); assertTrue(detail.ipa.isNotBlank()); assertTrue(detail.conjugations.isNotEmpty())
                assertTrue(detail.senses.all{it.translations.values.all(String::isNotBlank) && it.examples.isNotEmpty()})
            }
        }
    }
    @Test fun browseBothTargetsAndSwitchTranslationOnTheSameDetail() {
        await("open_catalog");compose.onNodeWithTag("open_catalog").performClick()
        await("search")
        compose.onNodeWithTag("search").performTextInput("être")
        await("word_fr:être:verb:1")
        compose.onNodeWithTag("word_fr:être:verb:1").performClick()
        await("word_detail")
        applyLanguage(learning="en");await("word_detail")
        compose.onNodeWithTag("language_context").assertTextContains("英语",substring=true)
        compose.onNodeWithTag("word_detail").assert(hasAnyDescendant(hasText("être")))
        screenshot("p5-fr-detail")
        applyLanguage(target="es")
        await("search")
        compose.waitUntil(15000) { compose.onAllNodesWithTag("result_count").fetchSemanticsNodes().any { it.config.toString().contains("14290") } }
        compose.onNodeWithTag("search").performTextInput("ser")
        await("word_es:ser:verb:1")
        compose.onNodeWithTag("word_es:ser:verb:1").performClick()
        await("word_detail")
        applyLanguage(learning="fr");await("word_detail")
        compose.onNodeWithTag("language_context").assertTextContains("法语",substring=true)
        compose.onNodeWithTag("word_detail").assert(hasAnyDescendant(hasText("ser")))
        screenshot("p5-es-detail")
    }
    private fun applyLanguage(target:String?=null,learning:String?=null) {
        clickReady("language_context");await("settings_form")
        if(target!=null){await("settings_target_$target");compose.onNodeWithTag("settings_target_$target").performScrollTo();clickReady("settings_target_$target")}
        if(learning!=null){await("settings_learning_$learning");compose.onNodeWithTag("settings_learning_$learning").performScrollTo();clickReady("settings_learning_$learning")}
        await("language_apply");compose.onNodeWithTag("language_apply").performScrollTo();clickReady("language_apply")
    }
    private fun await(tag:String) = compose.waitUntil(20000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun clickReady(tag:String) {
        await(tag)
        try {
            compose.waitUntil(20000){compose.onNodeWithTag(tag).fetchSemanticsNode().config.let{!it.contains(SemanticsProperties.Disabled)}}
        } catch(error:Throwable) {
            runCatching {
                screenshot("ui5-catalog-language-failure")
                val directory=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"qa")
                File(directory,"ui5-catalog-language-failure-tree.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString())
            }
            throw error
        }
        compose.onNodeWithTag(tag).performClick()
    }
    private fun screenshot(name:String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(350) // Capture after the IME/window transition settles.
        val instrument=InstrumentationRegistry.getInstrumentation()
        val directory=File(instrument.targetContext.getExternalFilesDir(null),"qa").apply{mkdirs()}
        val bitmap=instrument.uiAutomation.takeScreenshot()
        FileOutputStream(File(directory,"$name.png")).use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    private data class Case(val language:String,val count:Int,val books:Int,val uid:String)
}

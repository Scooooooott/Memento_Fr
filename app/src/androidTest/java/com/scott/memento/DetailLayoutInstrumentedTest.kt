package com.scott.memento

import android.content.Intent
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.memento.data.content.ContentCatalog
import com.scott.memento.data.user.AppRepository
import com.scott.memento.data.user.StudyRepository
import com.scott.memento.data.user.UserDatabase
import com.scott.memento.domain.*
import com.scott.memento.qa.ExtensionFixtureActivity
import com.scott.memento.ui.WordDetailContent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Actual shared renderer plus production navigation, with formal content and an isolated user DB. */
class DetailLayoutInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private val database="memento-test-ui3-detail.db"
    private val clock=Clock.fixed(Instant.parse("2026-10-10T04:00:00Z"),ZoneOffset.UTC)

    private fun property(key:String)=ProcessBuilder("/system/bin/getprop",key).start().let {process->
        process.inputStream.bufferedReader().use {it.readText().trim()}.also {check(process.waitFor()==0)}
    }
    private fun requireIsolatedEmulator() {
        assertEquals("com.scott.memento",context.packageName)
        check(Build.HARDWARE in setOf("ranchu","goldfish") || Build.FINGERPRINT.contains("generic"))
        check(property("ro.kernel.qemu")=="1") {"Physical devices must never run UI3 cleanup."}
        check(listOf("ro.boot.qemu.avd_name","ro.kernel.qemu.avd_name").any {property(it)=="MementoApi35"}) {
            "UI3 acceptance requires the dedicated MementoApi35 emulator."
        }
        check(database.startsWith("memento-test-") && database.endsWith(".db"))
    }
    private fun intent()=Intent(context,MainActivity::class.java)
        .putExtra("qa_user_database",database).putExtra("qa_clock_millis",clock.millis())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    private fun isolated(target:String="fr",block:(ActivityScenario<MainActivity>)->Unit) {
        requireIsolatedEmulator();context.deleteDatabase(database)
        try {
            ContentCatalog(context,Languages.get(target)).use {catalog->
                UserDatabase(context,database).use {store->
                    val study=StudyRepository(store,clock)
                    AppRepository(store,study,clock).save(catalog.studyIndex("zh-Hans"),
                        StudyPolicy(StudyMode.STANDARD,2),catalog.language.defaultBook,"Asia/Shanghai",
                        PlaybackPreferences(AutoPlay.NEVER,false))
                }
            }
            ActivityScenario.launch<MainActivity>(intent()).use {scenario->await("home_screen");block(scenario)}
        } catch(error:Throwable) {runCatching {capture("ui3-detail-failure")};throw error}
        finally {context.deleteDatabase(database)}
    }
    private fun formal(target:String,uid:String):Detail=ContentCatalog(context,Languages.get(target)).use {
        checkNotNull(it.detail(uid))
    }
    private fun renderer(word:Detail,block:()->Unit) {
        requireIsolatedEmulator()
        ActivityScenario.launch<ExtensionFixtureActivity>(Intent(context,ExtensionFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {scenario->
            scenario.onActivity {activity->activity.setContent {MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    WordDetailContent(word,"zh-Hans",true,
                        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding())
                }
            }}}
            await("word_detail");block()
        }
    }
    private fun await(tag:String)=compose.waitUntil(30000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun click(tag:String) {
        await(tag);compose.waitUntil(30000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
            .any {!it.config.contains(SemanticsProperties.Disabled)}}
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
    }
    private fun scrollClick(tag:String) {await(tag);compose.onNodeWithTag(tag).performScrollTo();click(tag)}
    private fun texts(node:SemanticsNode):List<String> = buildList {
        node.config.getOrNull(SemanticsProperties.Text)?.let {addAll(it.map {text->text.text})}
        node.children.forEach {addAll(texts(it))}
    }
    private fun texts(tag:String)=texts(compose.onNodeWithTag(tag,useUnmergedTree=true).fetchSemanticsNode())
    private fun contains(tag:String,text:String)=assertTrue("$tag should retain '$text', got ${texts(tag)}",
        texts(tag).any {it.contains(text)})
    private fun rows(sql:String,args:Array<String> = emptyArray()):List<String> = UserDatabase(context,database).use {store->
        store.readableDatabase.rawQuery(sql,args).use {cursor->buildList {
            while(cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") {cursor.getString(it).orEmpty()})
        }}
    }
    private fun persisted(target:String)=listOf(
        rows("SELECT * FROM daily_plan WHERE target_language=? ORDER BY id",arrayOf(target)),
        rows("SELECT * FROM daily_plan_item WHERE target_language=? ORDER BY id",arrayOf(target)),
        rows("SELECT * FROM learning_card WHERE target_language=? ORDER BY lexeme_uid",arrayOf(target)),
        rows("SELECT * FROM review_log WHERE target_language=? ORDER BY id",arrayOf(target)),
    )
    private fun openDetail(uid:String,query:String) {
        click("open_catalog");await("search")
        compose.onNodeWithTag("search").performTextReplacement(query)
        await("word_$uid");compose.onNodeWithTag("word_$uid").performScrollTo().performClick()
        await("word_detail");compose.onNodeWithTag("detail_tab_meanings").assertIsSelected()
    }
    private fun horizontal():Float=compose.onNodeWithTag("grammar_horizontal_header")
        .fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
    private fun vertical():Float=compose.onNodeWithTag("grammar_rows")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun capture(label:String) {
        compose.waitForIdle();android.os.SystemClock.sleep(350)
        val directory=File(context.getExternalFilesDir(null),"qa").apply {mkdirs()}
        File(directory,"$label-tree.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString())
        val bitmap=instrument.uiAutomation.takeScreenshot()
        FileOutputStream(File(directory,"$label.png")).use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }

    /** Expected cells come directly from the formal catalog, not the table projection being tested. */
    private fun assertAllFormalRows(word:Detail,french:Boolean) {
        click("detail_tab_grammar");await("grammar_rows")
        val keys=if(french) (1..6).map {it.toString()} else word.paradigms.flatMap {it.forms}.map {it.subjectKey}.distinct()
        assertTrue(word.paradigms.isNotEmpty())
        word.paradigms.sortedBy {it.order}.forEachIndexed {index,paradigm->
            compose.onNodeWithTag("grammar_rows").performScrollToIndex(index)
            compose.onNodeWithTag("grammar_row_${paradigm.id}").assertExists()
            contains("grammar_label_${paradigm.id}",paradigm.label)
            if(paradigm.region.isNotBlank()) contains("grammar_label_${paradigm.id}",paradigm.region)
            if(paradigm.note.isNotBlank()) contains("grammar_note_${paradigm.id}",paradigm.note)
            keys.forEach {key->
                val entries=paradigm.forms.filter {it.subjectKey==key}
                val tag="grammar_cell_${paradigm.id}_$key"
                if(entries.isEmpty()) assertEquals(listOf("—"),texts(tag))
                else entries.forEach {entry->
                    if(french) {
                        val phrase=entry.pronoun+(if(entry.pronoun.endsWith("’") || entry.pronoun.endsWith("'")) "" else " ")+entry.form
                        contains(tag,phrase)
                    } else {contains(tag,entry.pronoun);contains(tag,entry.form)}
                }
            }
        }
        compose.onNodeWithTag("grammar_rows").performScrollToIndex(0)
    }

    @Test fun frenchEtreAllSixKeysAndEveryFormalCellRemainInTheirPersonColumns() {
        val word=formal("fr","fr:être:verb:1")
        assertEquals(3,word.paradigms.size);assertEquals(18,word.paradigms.sumOf {it.forms.size})
        renderer(word) {
            assertAllFormalRows(word,true)
            assertTrue(word.paradigms.flatMap {it.forms}.any {it.pronoun.endsWith("’")})
            compose.onNodeWithTag("grammar_horizontal_header").performTouchInput {swipeLeft()}
            assertTrue(horizontal()>0)
            val row=word.paradigms.minBy {it.order}
            val rowHorizontal=compose.onNodeWithTag("grammar_horizontal_${row.id}").fetchSemanticsNode()
                .config[SemanticsProperties.HorizontalScrollAxisRange].value()
            assertEquals(horizontal(),rowHorizontal,0.1f)
            compose.onNodeWithTag("grammar_header_6").assertExists()
            capture("ui3-fr-grammar")
        }
    }

    @Test fun frenchAvoirKeepsElisionCompoundSourceAndKeyFormMetadata() {
        val word=formal("fr","fr:avoir:verb:1")
        assertEquals("avoir",word.grammarFeatures["auxiliary"])
        assertEquals("eu",word.grammarFeatures["past_participle"])
        renderer(word) {
            click("detail_tab_forms");await("detail_forms")
            assertTrue(texts("detail_forms").any {it.contains("avoir")})
            assertTrue(texts("detail_forms").any {it.contains("eu")})
            capture("ui3-fr-forms")
            assertAllFormalRows(word,true)
            val present=word.paradigms.single {it.forms.any {form->form.pronoun=="j’" && form.form=="ai"}}
            compose.onNodeWithTag("grammar_rows").performScrollToIndex(word.paradigms.sortedBy {it.order}.indexOf(present))
            contains("grammar_cell_${present.id}_1","j’ai")
            // The renderer must retain source compound strings, including pre-existing content defects.
            val compound=word.paradigms.single {it.forms.any {form->form.form.contains("eu")}}
            compose.onNodeWithTag("grammar_rows").performScrollToIndex(word.paradigms.sortedBy {it.order}.indexOf(compound))
            compound.forms.forEach {contains("grammar_cell_${compound.id}_${it.subjectKey}",it.form)}
        }
    }

    @Test fun spanishSerAllRowsDistinguishSameNamedMoodsAndMissingImperativeYo() {
        val word=formal("es","es:ser:verb:1")
        assertEquals(11,word.paradigms.size);assertEquals(64,word.paradigms.sumOf {it.forms.size})
        val present=word.paradigms.filter {it.tense=="presente"}
        assertEquals(2,present.size);assertEquals(1,present.map {it.label}.distinct().size)
        assertEquals(setOf("indicativo","subjuntivo"),present.map {it.mood}.toSet())
        renderer(word) {
            assertAllFormalRows(word,false)
            present.forEach {row->
                compose.onNodeWithTag("grammar_rows").performScrollToIndex(word.paradigms.sortedBy {it.order}.indexOf(row))
                contains("grammar_label_${row.id}",row.mood)
            }
            word.paradigms.filter {it.mood=="imperativo"}.forEach {row->
                compose.onNodeWithTag("grammar_rows").performScrollToIndex(word.paradigms.sortedBy {it.order}.indexOf(row))
                assertTrue(row.forms.none {it.subjectKey=="yo"})
                assertEquals(listOf("—"),texts("grammar_cell_${row.id}_yo"))
                contains("grammar_note_${row.id}",row.note)
            }
            capture("ui3-es-grammar")
        }
    }

    @Test fun spanishHablarKeepsFormalVosRegionAndEveryParadigmReachable() {
        val word=formal("es","es:hablar:verb:1")
        val regional=word.paradigms.filter {it.forms.any {form->form.subjectKey=="vos"}}
        assertEquals(2,regional.size);assertTrue(regional.all {it.region.isNotBlank()})
        renderer(word) {
            assertAllFormalRows(word,false)
            compose.onNodeWithTag("grammar_header_vos").assertExists()
            val row=regional.last()
            compose.onNodeWithTag("grammar_rows").performScrollToIndex(word.paradigms.sortedBy {it.order}.indexOf(row))
            contains("grammar_label_${row.id}",row.region)
            contains("grammar_cell_${row.id}_vos",row.forms.single {it.subjectKey=="vos"}.form)
            capture("ui3-es-regional")
        }
    }

    @Test fun productionDetailKeepsTabAndBothTableAxesThroughSettingsAndActivityRecreation() {
        isolated("es") {scenario->
            openDetail("es:ser:verb:1","ser")
            val before=persisted("es")
            click("detail_tab_grammar");await("grammar_rows")
            compose.onNodeWithTag("grammar_rows").performScrollToIndex(6)
            compose.onNodeWithTag("grammar_horizontal_header").performTouchInput {swipeLeft()}
            val x=horizontal();val y=vertical();assertTrue(x>0);assertTrue(y>0)
            click("language_context");await("settings_form");click("settings_cancel")
            await("grammar_rows");compose.onNodeWithTag("detail_tab_grammar").assertIsSelected()
            assertEquals(x,horizontal(),0.1f);assertEquals(y,vertical(),0.1f)
            scenario.recreate();await("grammar_rows")
            compose.onNodeWithTag("detail_tab_grammar").assertIsSelected()
            assertEquals(x,horizontal(),0.1f);assertEquals(y,vertical(),0.1f)
            assertEquals(before,persisted("es"));capture("ui3-tab-restore")
            click("detail_back");await("word_list")
            assertEquals(before,persisted("es"))
        }
    }

    @Test fun changingLearningLanguageRetainsGrammarStateThenDisplaysEnglishMeaningsWithoutScoring() {
        isolated("fr") {_->
            val word=formal("fr","fr:être:verb:1")
            openDetail(word.summary.uid,"être");val before=persisted("fr")
            click("detail_tab_grammar");await("grammar_rows")
            compose.onNodeWithTag("grammar_horizontal_header").performTouchInput {swipeLeft()}
            val x=horizontal()
            click("language_context");await("settings_form")
            scrollClick("settings_learning_en");scrollClick("language_apply")
            await("grammar_rows");compose.onNodeWithTag("detail_tab_grammar").assertIsSelected()
            assertEquals(x,horizontal(),0.1f)
            click("detail_tab_meanings");await("detail_meanings")
            word.senses.forEachIndexed {index,sense->
                compose.onNodeWithTag("detail_meanings").performScrollToIndex(index)
                compose.onNodeWithTag("meaning_${sense.id}").assertTextEquals(sense.translations.getValue("en"))
                sense.examples.forEach {example->
                    compose.onNodeWithTag("detail_example_${example.id}").assertTextEquals(example.source)
                    compose.onNodeWithTag("detail_example_translation_${example.id}").assertTextEquals(example.translations.getValue("en"))
                }
            }
            assertEquals(before,persisted("fr"))
            assertEquals(listOf("fr|en"),rows("SELECT target_language,learning_language FROM app_settings WHERE id=1"))
        }
    }

    @Test fun switchingTargetDiscardsOldWordAndStartsNewTargetDetailAtMeanings() {
        isolated {_->
            openDetail("fr:être:verb:1","être");val before=persisted("fr")
            click("detail_tab_grammar");await("grammar_table")
            click("language_context");await("settings_form")
            scrollClick("settings_target_es");scrollClick("language_apply")
            await("search");compose.onNodeWithTag("word_detail").assertDoesNotExist()
            compose.onNodeWithTag("search").performTextReplacement("ser")
            await("word_es:ser:verb:1");compose.onNodeWithTag("word_es:ser:verb:1").performScrollTo().performClick()
            await("word_detail");compose.onNodeWithTag("detail_tab_meanings").assertIsSelected()
            compose.onNodeWithTag("grammar_table").assertDoesNotExist()
            assertTrue(texts("word_detail").any {it=="ser"})
            assertFalse(texts("word_detail").any {it=="être"})
            assertEquals(before,persisted("fr"))
            assertTrue(rows("SELECT id FROM daily_plan WHERE target_language='es'").isEmpty())
        }
    }

    @Test fun revealedLearningCardCanVisitEveryTabAndReturnsSameUidWithoutAnyGrade() {
        isolated {scenario->
            click("open_study");click("study_reveal");await("rating_GOOD")
            val uid=rows("SELECT lexeme_uid FROM daily_plan_item WHERE state='PENDING' AND revealed=1").single()
            val before=persisted("fr")
            scrollClick("study_details");await("word_detail")
            listOf("meanings","forms","grammar").forEach {tab->click("detail_tab_$tab");compose.onNodeWithTag("detail_tab_$tab").assertIsSelected()}
            scenario.recreate();await("word_detail");compose.onNodeWithTag("detail_tab_grammar").assertIsSelected()
            click("detail_back");await("rating_GOOD")
            compose.onNodeWithTag("study_reveal").assertDoesNotExist()
            assertEquals(before,persisted("fr"))
            assertEquals(listOf(uid),rows("SELECT lexeme_uid FROM daily_plan_item WHERE state='PENDING' AND revealed=1"))
            assertTrue(rows("SELECT id FROM review_log").isEmpty())
        }
    }

    @Test fun nonVerbKeepsFormalExampleAndExplicitGrammarEmptyStateWithoutInventedRows() {
        val word=formal("fr","fr:bonjour:noun:1")
        assertTrue(word.paradigms.isEmpty())
        renderer(word) {
            val sense=word.senses.first();val example=sense.examples.first()
            assertTrue(texts("detail_meanings").any {it==sense.translations.getValue("zh-Hans")})
            assertTrue(texts("detail_meanings").any {it==example.source})
            click("detail_tab_forms");await("detail_forms")
            word.formDetails.forEach {form->assertTrue(texts("detail_forms").any {it.contains(form.form)})}
            click("detail_tab_grammar");compose.onNodeWithTag("grammar_table").assertDoesNotExist()
            compose.onNodeWithTag("grammar_empty").assertExists()
            capture("ui3-nonverb")
        }
    }

    @Test fun allThreeTabsRetainTheirOwnScrollWhenSwitchingBackAndForth() {
        val base=formal("es","es:ser:verb:1")
        val word=base.copy(senses=(0 until 32).map {index->
            Sense("ui3-sense-$index",mapOf("zh-Hans" to "独立释义 $index"),emptyList())
        },formDetails=(0 until 32).map {index->WordForm("形式 $index","source-form-$index",index,"{}")})
        renderer(word) {
            compose.onNodeWithTag("detail_meanings").performScrollToIndex(20)
            val meanings=compose.onNodeWithTag("detail_meanings").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value();assertTrue(meanings>0)
            click("detail_tab_forms");compose.onNodeWithTag("detail_forms").performScrollToIndex(20)
            val forms=compose.onNodeWithTag("detail_forms").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value();assertTrue(forms>0)
            click("detail_tab_grammar");compose.onNodeWithTag("grammar_rows").performScrollToIndex(7)
            val grammar=vertical();assertTrue(grammar>0)
            click("detail_tab_meanings")
            assertEquals(meanings,compose.onNodeWithTag("detail_meanings").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value(),0.1f)
            click("detail_tab_forms")
            assertEquals(forms,compose.onNodeWithTag("detail_forms").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value(),0.1f)
            click("detail_tab_grammar");assertEquals(grammar,vertical(),0.1f)
        }
    }

    @Test fun longNotesUnknownSubjectsAndReflexiveSourceFormsSurviveTheSharedRenderer() {
        val base=formal("es","es:ser:verb:1")
        val note=(1..48).joinToString(" ") {"第${it}段：这是必须完整保留的地区、反身代词与长注释。"}
        val paradigm=Paradigm("ui3-extension","custom-mood","custom-tense","扩展范式","未知地区",note,0,
            listOf(ParadigmForm("unknown-person","unknown-pronoun","source-reflexive-form",0),
                ParadigmForm("tu","tú","lávate",1),ParadigmForm("tu","tú","no te laves",2)))
        // Synthetic capability fixture only; formal Spanish has no is_reflexive=1 entries.
        val word=base.copy(summary=base.summary.copy(uid="xx:ui3:verb:1"),paradigms=listOf(paradigm),
            forms=emptyList(),formDetails=emptyList(),senses=emptyList())
        renderer(word) {
            compose.onNodeWithText(context.getString(R.string.detail_meanings_empty)).assertExists()
            click("detail_tab_forms")
            compose.onNodeWithTag("detail_forms_empty").assertIsDisplayed()
            click("detail_tab_grammar");await("grammar_rows")
            contains("grammar_label_${paradigm.id}",paradigm.mood)
            contains("grammar_label_${paradigm.id}",paradigm.region)
            contains("grammar_note_${paradigm.id}",note)
            compose.onNodeWithTag("grammar_header_unknown-person").assertExists()
            contains("grammar_cell_${paradigm.id}_unknown-person","source-reflexive-form")
            contains("grammar_cell_${paradigm.id}_tu","lávate")
            contains("grammar_cell_${paradigm.id}_tu","no te laves")
            compose.onNodeWithTag("grammar_rows").performTouchInput {swipeUp()}
            assertTrue(vertical()>0)
            capture("ui3-long-note")
        }
    }
}

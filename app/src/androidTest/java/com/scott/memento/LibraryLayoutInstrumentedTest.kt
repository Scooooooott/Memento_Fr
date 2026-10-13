package com.scott.memento

import android.content.Intent
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
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
import com.scott.memento.ui.AppState
import com.scott.memento.ui.library.LibraryPager
import com.scott.memento.ui.library.LibraryScreen
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/** Production shallow pages and navigation; failure injection stays in an isolated renderer. */
class LibraryLayoutInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private val database="memento-test-ui4-library.db"
    private val clock=Clock.fixed(Instant.parse("2026-10-10T04:00:00Z"),ZoneOffset.UTC)
    private data class Fixture(val words:List<WordSummary>,val books:List<Book>,val studyUid:String)

    private fun property(key:String)=ProcessBuilder("/system/bin/getprop",key).start().let {process->
        process.inputStream.bufferedReader().use {it.readText().trim()}.also {check(process.waitFor()==0)}
    }
    private fun requireIsolatedEmulator() {
        assertEquals("com.scott.memento",context.packageName)
        check(Build.HARDWARE in setOf("ranchu","goldfish") || Build.FINGERPRINT.contains("generic"))
        check(property("ro.kernel.qemu")=="1") {"Physical devices must never run UI4 cleanup."}
        check(listOf("ro.boot.qemu.avd_name","ro.kernel.qemu.avd_name").any {property(it)=="MementoApi35"}) {
            "UI4 acceptance requires the dedicated MementoApi35 emulator."
        }
        check(database.startsWith("memento-test-") && database.endsWith(".db"))
    }
    private fun intent()=Intent(context,MainActivity::class.java)
        .putExtra("qa_user_database",database).putExtra("qa_clock_millis",clock.millis())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    private fun isolated(target:String="fr",seed:(UserDatabase,List<WordSummary>)->Unit={_,_->},
                         block:(ActivityScenario<MainActivity>,Fixture)->Unit) {
        requireIsolatedEmulator();context.deleteDatabase(database)
        try {
            val fixture=ContentCatalog(context,Languages.get(target)).use {catalog->
                // Materialize only four independent shallow reference pages, never whole-library detail.
                val words=(0..3).flatMap {catalog.page("","",it*50).words}
                UserDatabase(context,database).use {store->
                    seed(store,words)
                    val study=StudyRepository(store,clock)
                    val snapshot=AppRepository(store,study,clock).save(catalog.studyIndex("zh-Hans"),
                        StudyPolicy(StudyMode.STANDARD,2),catalog.language.defaultBook,"Asia/Shanghai",
                        PlaybackPreferences(AutoPlay.NEVER,false))
                    Fixture(words,catalog.books(),checkNotNull(snapshot.next).uid)
                }
            }
            ActivityScenario.launch<MainActivity>(intent()).use {scenario->
                try {await("home_screen");block(scenario,fixture)}
                catch(error:Throwable) {runCatching {capture("ui4-library-failure")};throw error}
            }
        } catch(error:Throwable) {runCatching {capture("ui4-library-failure")};throw error}
        finally {context.deleteDatabase(database)}
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
    private fun text(tag:String,unmerged:Boolean=false)=compose.onNodeWithTag(tag,useUnmergedTree=unmerged)
        .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") {it.text}
    private fun waitText(tag:String,expected:String,unmerged:Boolean=false)=compose.waitUntil(30000) {
        runCatching {text(tag,unmerged)==expected}.getOrDefault(false)
    }
    private fun loaded(count:Int,total:Int?=null) {
        await("library_loaded_count")
        compose.waitUntil(30000) {
            val values=Regex("\\d+").findAll(text("library_loaded_count")).map {it.value.toInt()}.toList()
            values.size==2 && values[0]==count && (total==null || values[1]==total)
        }
    }
    private fun total(target:String,count:Int)=compose.onNodeWithTag("result_count")
        .assertTextEquals(context.getString(R.string.result_count,
            context.getString(if(target=="fr") R.string.french else R.string.spanish),count))
    private fun listIndex(index:Int)=compose.onNodeWithTag("word_list").performScrollToIndex(index)
    private fun listWord(uid:String)=compose.onNodeWithTag("word_list").performScrollToNode(hasTestTag("word_$uid"))
    private fun position():Float=compose.onNodeWithTag("word_list").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun assertPreview(word:WordSummary,learning:String) {
        listWord(word.uid)
        compose.onNodeWithTag("word_preview_${word.uid}",useUnmergedTree=true)
            .assertTextEquals(checkNotNull(word.translations[learning]))
    }
    private fun query(value:String) {
        await("search");compose.onNodeWithTag("search").performTextReplacement(value)
    }
    private fun assertQuery(value:String)=assertEquals(value,compose.onNodeWithTag("search")
        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    private fun selectPair(target:String?=null,learning:String?=null) {
        click("language_context");await("settings_form")
        target?.let {scrollClick("settings_target_$it")}
        learning?.let {scrollClick("settings_learning_$it")}
        scrollClick("language_apply");await("word_list")
    }
    private fun number(sql:String,args:Array<String> = emptyArray()):Int=UserDatabase(context,database).use {store->
        store.readableDatabase.rawQuery(sql,args).use {cursor->check(cursor.moveToFirst());cursor.getInt(0)}
    }
    private fun persisted(target:String):List<List<String>> = UserDatabase(context,database).use {store->
        listOf("daily_plan","daily_plan_item","learning_card","review_log").map {table->
            store.readableDatabase.rawQuery("SELECT * FROM $table WHERE target_language=? ORDER BY 1,2",arrayOf(target))
                .use {cursor->buildList {
                    while(cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") {cursor.getString(it).orEmpty()})
                }}
        }
    }
    private fun seedFavorites(store:UserDatabase,target:String,words:List<WordSummary>) {
        store.writableDatabase.apply {
            beginTransaction()
            try {words.forEach {execSQL("INSERT INTO favorites(target_language,lexeme_uid) VALUES(?,?)",arrayOf(target,it.uid))}
                setTransactionSuccessful()}
            finally {endTransaction()}
        }
    }
    private fun capture(label:String) {
        compose.waitForIdle();android.os.SystemClock.sleep(350)
        val directory=File(context.getExternalFilesDir(null),"qa").apply {mkdirs()}
        val bitmap=instrument.uiAutomation.takeScreenshot()
        FileOutputStream(File(directory,"$label.png")).use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
        File(directory,"$label-tree.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString())
    }

    private fun verifyPagination(target:String) {
        isolated(target) {_,fixture->
            val before=persisted(target)
            click("open_catalog");loaded(50)
            assertEquals(150,fixture.words.take(150).map {it.uid}.distinct().size)
            compose.onNodeWithTag("load_more").assertDoesNotExist()
            listIndex(45);loaded(100)
            listIndex(60);val second=fixture.words[60];val secondPosition=position()
            compose.onNodeWithTag("word_${second.uid}").assertExists().performClick();await("word_detail")
            compose.onNodeWithTag("word_detail_title").assertTextEquals(second.lemma)
            click("detail_back");loaded(100)
            assertEquals(secondPosition,position(),0.1f)
            compose.onNodeWithTag("word_${second.uid}").assertIsDisplayed()
            listIndex(95);loaded(150)
            listIndex(110);val third=fixture.words[110];val thirdPosition=position()
            compose.onNodeWithTag("word_${third.uid}").assertExists().performClick();await("word_detail")
            compose.onNodeWithTag("word_detail_title").assertTextEquals(third.lemma)
            click("detail_back");loaded(150)
            assertEquals(thirdPosition,position(),0.1f)
            compose.onNodeWithTag("word_${third.uid}").assertIsDisplayed()
            capture(if(target=="fr") "ui4-page-return" else "ui4-es-library")
            // Independent catalog boundaries remain reachable after appending, including page one.
            listOf(0,49,50,99,100,110).forEach {index->
                listIndex(index);compose.onNodeWithTag("word_${fixture.words[index].uid}").assertExists()
            }
            loaded(150);assertEquals(before,persisted(target))
            listIndex(0)
            if(target=="fr") capture("ui4-fr-library")
        }
    }
    @Test fun frenchAutomaticPagesAndSecondThirdPageDetailRestoreKeepEarlierRows()=verifyPagination("fr")
    @Test fun spanishAutomaticPagesAndSecondThirdPageDetailRestoreKeepEarlierRows()=verifyPagination("es")

    @Test fun recreationAndCancelledSettingsRetainLoadedPagesQueryBookAndExactPosition() {
        isolated("es") {scenario,fixture->
            val book=fixture.books.single {it.id==Languages.get("es").defaultBook}
            scrollClick("current_book_progress");loaded(50,book.count)
            listIndex(45);loaded(100,book.count)
            val word=ContentCatalog(context,Languages.get("es")).use {it.page("",book.id,50).words[10]}
            listIndex(60);val initial=position();assertTrue(initial>0)
            scenario.recreate();await("word_list");loaded(100,book.count)
            assertEquals(initial,position(),0.1f)
            compose.onNodeWithTag("word_${word.uid}").assertIsDisplayed()
            compose.onNodeWithTag("choose_book",useUnmergedTree=true).assertTextEquals(book.title)
            click("nav_settings");await("settings_form");click("settings_cancel");await("word_list")
            loaded(100,book.count);assertEquals(initial,position(),0.1f)
            compose.onNodeWithTag("choose_book",useUnmergedTree=true).assertTextEquals(book.title)
            val beforeLanguageChange=persisted("es")
            selectPair(learning="en");loaded(100,book.count)
            assertEquals(initial,position(),0.1f)
            compose.onNodeWithTag("word_preview_${word.uid}",useUnmergedTree=true)
                .assertTextEquals(checkNotNull(word.translations["en"]))
            assertEquals(beforeLanguageChange,persisted("es"))
            // A nonempty query has the same preservation contract, not merely offset zero.
            query(word.lemma);await("word_${word.uid}")
            click("word_${word.uid}");await("word_detail");scenario.recreate();await("word_detail")
            click("detail_back");await("word_list")
            compose.onNodeWithTag("search").assertTextContains(word.lemma)
            compose.onNodeWithTag("library_book_${book.id}").assertIsSelected()
            compose.onNodeWithTag("word_${word.uid}").assertIsDisplayed()
            assertEquals(0,number("SELECT COUNT(*) FROM review_log"))
        }
    }

    @Test fun allSixLearningDirectionsUseCurrentShallowTranslationWithAccentSearchAndClear() {
        listOf("fr" to "fr:être:verb:1","es" to "es:canción:noun:1").forEach {(target,uid)->
            isolated(target) {_,_->
                val word=ContentCatalog(context,Languages.get(target)).use {checkNotNull(it.summary(uid))}
                val before=persisted(target)
                click("open_catalog");loaded(50)
                query(if(target=="fr") "etre" else "cancion");await("word_$uid")
                Languages.get(target).learningLanguages.forEach {learning->
                    selectPair(learning=learning)
                    assertPreview(word,learning)
                    assertQuery(if(target=="fr") "etre" else "cancion")
                    assertEquals(before,persisted(target))
                }
                click("search_clear");loaded(50)
                assertQuery("")
                compose.onNodeWithTag("search_clear").assertDoesNotExist()
                assertEquals(before,persisted(target))
            }
        }
    }

    @Test fun targetSwitchClearsOldCriteriaAndPagesAndCannotLeakTheOtherLanguage() {
        isolated {_,fixture->
            click("open_catalog");loaded(50);listIndex(45);loaded(100)
            query("etre");await("word_fr:être:verb:1")
            val french=persisted("fr")
            selectPair(target="es");loaded(50)
            assertQuery("")
            compose.onNodeWithTag("library_book_all").assertIsSelected()
            compose.onNodeWithTag("word_fr:être:verb:1").assertDoesNotExist()
            val spanish=ContentCatalog(context,Languages.get("es")).use {it.page("","",0)}
            total("es",spanish.total);compose.onNodeWithTag("word_${spanish.words.first().uid}").assertExists()
            assertEquals(0,number("SELECT COUNT(*) FROM daily_plan WHERE target_language='es'"))
            selectPair(target="fr");loaded(50)
            compose.onNodeWithTag("word_${fixture.words.first().uid}").assertExists()
            compose.onNodeWithTag("word_${spanish.words.first().uid}").assertDoesNotExist()
            assertEquals(french,persisted("fr"))
        }
    }

    @Test fun horizontalBookChipsKeepTitleCountMembershipAndCombineWithFavorites() {
        isolated(seed={store,words->seedFavorites(store,"fr",words.take(151))}) {_,fixture->
            val before=persisted("fr")
            click("open_catalog");loaded(50)
            val books=listOf(fixture.books.single {it.id==Languages.get("fr").defaultBook},
                fixture.books.single {it.id=="essential-fr"})
            books.forEach {book->
                scrollClick("library_book_${book.id}");loaded(50,book.count)
                compose.onNodeWithTag("library_book_${book.id}").assertIsSelected()
                compose.onNodeWithTag("choose_book",useUnmergedTree=true).assertTextEquals(book.title)
                total("fr",book.count)
                val reference=ContentCatalog(context,Languages.get("fr")).use {it.page("",book.id,0)}
                assertEquals(book.count,reference.total)
                listOf(0,10,20).forEach {index->
                    listIndex(index);compose.onNodeWithTag("word_${reference.words[index].uid}").assertExists()
                }
                click("nav_favorites")
                val members=ContentCatalog(context,Languages.get("fr")).use {it.studyIndex("zh-Hans").books.getValue(book.id).toSet()}
                val expected=fixture.words.take(151).filter {it.uid in members}
                assertTrue("Fixture must contain book-scoped favorites",expected.isNotEmpty())
                loaded(minOf(50,expected.size),expected.size);total("fr",expected.size)
                compose.onNodeWithTag("choose_book",useUnmergedTree=true).assertTextEquals(book.title)
                expected.take(3).forEach {word->
                    listWord(word.uid);compose.onNodeWithTag("word_favorite_${word.uid}",useUnmergedTree=true).assertExists()
                }
                // A favorite outside this book must not survive the AND filter.
                val outside=fixture.words.take(151).firstOrNull {it.uid !in members}
                assertNotNull("Need a real favorite outside $book",outside)
                compose.onNodeWithTag("word_${outside!!.uid}").assertDoesNotExist()
                capture("ui4-book-favorites")
                click("nav_favorites")
            }
            assertEquals(listOf(Languages.get("fr").defaultBook),UserDatabase(context,database).use {store->
                store.readableDatabase.rawQuery("SELECT book_id FROM language_settings WHERE target_language='fr'",null)
                    .use {cursor->buildList {while(cursor.moveToNext()) add(cursor.getString(0))}}
            })
            assertEquals(before,persisted("fr"))
        }
    }

    @Test fun removingFavoriteOnALaterPageRefillsItsCapacityWithoutMissingTheNextWord() {
        isolated(seed={store,words->seedFavorites(store,"fr",words.take(151))}) {_,fixture->
            click("open_catalog");loaded(50);click("nav_favorites");loaded(50,151)
            listIndex(45);loaded(100,151);listIndex(95);loaded(150,151)
            val anchor=fixture.words[60];val removed=fixture.words[62]
            listIndex(60)
            compose.onNodeWithTag("word_${anchor.uid}").assertIsDisplayed()
            val anchorTop=compose.onNodeWithTag("word_${anchor.uid}").fetchSemanticsNode().boundsInRoot.top
            val anchorPosition=position()
            // Delete another visible row, so the first visible UID itself remains valid.
            compose.onNodeWithTag("word_${removed.uid}").assertIsDisplayed().performClick();await("word_detail")
            click("detail_favorite");compose.waitUntil(30000) {number("SELECT COUNT(*) FROM favorites")==150}
            click("detail_back");loaded(150,150);total("fr",150)
            compose.onNodeWithTag("word_${anchor.uid}").assertIsDisplayed()
            assertEquals(anchorPosition,position(),0.1f)
            assertEquals(anchorTop,compose.onNodeWithTag("word_${anchor.uid}").fetchSemanticsNode().boundsInRoot.top,0.1f)
            val expected=fixture.words.take(151).filterNot {it.uid==removed.uid}
            listOf(0,49,50,59,60,99,100,149).forEach {index->
                listIndex(index);compose.onNodeWithTag("word_${expected[index].uid}").assertExists()
                compose.onNodeWithTag("word_favorite_${expected[index].uid}",useUnmergedTree=true).assertExists()
            }
            compose.onNodeWithTag("word_${removed.uid}").assertDoesNotExist()
            assertEquals(0,number("SELECT COUNT(*) FROM review_log"))
        }
    }

    @Test fun learnedBadgesRequireLastReviewAndChangeOnlyAfterActualScoring() {
        isolated(seed={store,words->
            // Existing card states mirror imported records; last_review_at is the authoritative signal.
            listOf(words[0] to (clock.millis()-86400000),words[1] to null).forEach {(word,last)->
                store.writableDatabase.execSQL("""INSERT INTO learning_card(target_language,lexeme_uid,
                    stability,difficulty,due_at,last_review_at,repetitions,lapses,state)
                    VALUES(?,?,?,?,?,?,?,?,?)""",arrayOf("fr",word.uid,4.0,5.0,clock.millis()+604800000,last,
                    if(last==null) 0 else 1,0,if(last==null) "NEW" else "REVIEW"))
            }
        }) {_,fixture->
            val studyWord=ContentCatalog(context,Languages.get("fr")).use {checkNotNull(it.summary(fixture.studyUid))}
            click("open_study");click("study_reveal");await("rating_GOOD")
            click("study_pause");await("home_screen");click("open_catalog");query(studyWord.lemma)
            await("word_${studyWord.uid}")
            compose.onNodeWithTag("word_learned_${studyWord.uid}",useUnmergedTree=true)
                .assertTextEquals(context.getString(R.string.library_not_learned))
            assertEquals(0,number("SELECT COUNT(*) FROM review_log"))
            click("nav_home");await("home_screen");click("open_study");await("rating_GOOD")
            click("rating_GOOD");await("study_reveal");click("study_pause");click("open_catalog")
            query(studyWord.lemma);await("word_${studyWord.uid}")
            waitText("word_learned_${studyWord.uid}",context.getString(R.string.library_learned),true)
            assertEquals(1,number("SELECT COUNT(*) FROM review_log"))
            listOf(fixture.words[0] to true,fixture.words[1] to false).forEach {(word,learned)->
                query(word.lemma);await("word_${word.uid}")
                compose.onNodeWithTag("word_learned_${word.uid}",useUnmergedTree=true)
                    .assertTextEquals(context.getString(if(learned) R.string.library_learned else R.string.library_not_learned))
            }
        }
    }

    @Test fun emptySearchAndEmptyFavoritesExplainTheActiveScopeAndClearRestoresFirstPage() {
        isolated {_,_->
            click("open_catalog");loaded(50)
            query("etre");query("bonjour");await("word_fr:bonjour:noun:1")
            compose.onNodeWithTag("word_fr:être:verb:1").assertDoesNotExist()
            query("__ui4_missing_词条_123456__")
            loaded(0,0);await("library_empty")
            compose.onNodeWithTag("library_empty").assertTextEquals(context.getString(R.string.library_no_results))
            click("search_clear");loaded(50);click("nav_favorites");loaded(0,0)
            compose.onNodeWithTag("nav_favorites").assertIsSelected()
            compose.onNodeWithTag("library_empty").assertTextEquals(context.getString(R.string.favorites_empty))
            capture("ui4-empty")
            click("nav_favorites");loaded(50)
            assertEquals(0,number("SELECT COUNT(*) FROM review_log"))
        }
    }

    private fun renderer(load:suspend (Int)->WordPage,block:()->Unit) {
        requireIsolatedEmulator()
        ActivityScenario.launch<ExtensionFixtureActivity>(Intent(context,ExtensionFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {scenario->
            scenario.onActivity {activity->activity.setContent {MaterialTheme {
                val scope=rememberCoroutineScope()
                val pager=remember {LibraryPager(scope) {_,offset->load(offset)}}
                var query by remember {mutableStateOf("")}
                val state=remember {AppState(loaded=true,preferences=AppPreferences(Selection("fr","zh-Hans"),"Asia/Shanghai"),
                    overview=LearningOverview(emptySet(),emptySet(),0,0,emptyList()))}
                Surface(Modifier.fillMaxSize()) {
                    LibraryScreen(state,pager,query,{query=it},"",{},false,{}, {},
                        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding())
                }
            }}}
            await("word_list");block()
        }
    }

    @Test fun failedAppendKeepsTheFirstPageStopsAutomaticRetriesAndExplicitRetryAddsNextPage() {
        val reference=ContentCatalog(context,Languages.get("fr")).use {catalog->
            catalog.page("","",0).words+catalog.page("","",50).words
        }
        val appendCalls=AtomicInteger();val appendGate=CompletableDeferred<Unit>()
        renderer({offset->
            if(offset==50 && appendCalls.incrementAndGet()==1) {
                appendGate.await();error("UI4 injected append failure")
            }
            WordPage(reference.drop(offset).take(50),100,offset==0)
        }) {
            loaded(50,100);listIndex(45)
            compose.waitUntil(30000) {appendCalls.get()==1}
            // The loader is item 50. Reaching the five-row threshold starts a request before
            // that footer enters the LazyColumn's composed viewport; scroll to observe it.
            listIndex(50);await("library_append_loading")
            compose.onNodeWithTag("library_append_loading").assertIsDisplayed()
            loaded(50,100);appendGate.complete(Unit);await("library_error")
            compose.onNodeWithTag("library_error").performScrollTo().assertTextEquals(
                context.getString(R.string.library_append_failed,"UI4 injected append failure"))
            loaded(50,100);assertEquals(1,appendCalls.get())
            listIndex(0);compose.onNodeWithTag("word_${reference.first().uid}").assertExists()
            listIndex(45);compose.waitForIdle();assertEquals(1,appendCalls.get())
            listIndex(50)
            scrollClick("library_retry");loaded(100,100)
            assertEquals(2,appendCalls.get());compose.onNodeWithTag("library_error").assertDoesNotExist()
            listIndex(60);compose.onNodeWithTag("word_${reference[60].uid}").assertExists()
            listIndex(0);compose.onNodeWithTag("word_${reference.first().uid}").assertExists()
            // Capture the actual error state on a fresh renderer below, not a stale historical screenshot.
        }
        val again=AtomicInteger()
        renderer({offset->
            if(offset==50) {again.incrementAndGet();error("UI4 injected append failure")}
            WordPage(reference.take(50),100,true)
        }) {
            loaded(50,100);listIndex(45)
            compose.waitUntil(30000) {again.get()==1}
            listIndex(50);await("library_error")
            compose.onNodeWithTag("library_error").assertTextEquals(
                context.getString(R.string.library_append_failed,"UI4 injected append failure"))
            compose.onNodeWithTag("library_error").performScrollTo();capture("ui4-error")
            assertEquals(1,again.get())
        }
    }

    @Test fun initialLoadingAndFailureHaveNoPartialRowsAndRetryLoadsTheFirstPage() {
        val reference=ContentCatalog(context,Languages.get("fr")).use {it.page("","",0).words}
        val gate=CompletableDeferred<Unit>();val calls=AtomicInteger()
        renderer({offset->
            assertEquals(0,offset)
            if(calls.incrementAndGet()==1) {gate.await();error("UI4 injected initial failure")}
            WordPage(reference,50,false)
        }) {
            await("library_initial_loading");loaded(0,0)
            compose.onNodeWithTag("word_${reference.first().uid}").assertDoesNotExist()
            gate.complete(Unit);await("library_error")
            compose.onNodeWithTag("library_error").assertTextEquals(
                context.getString(R.string.library_initial_failed,"UI4 injected initial failure"))
            assertEquals(1,calls.get());click("library_retry");loaded(50,50)
            assertEquals(2,calls.get())
            compose.onNodeWithTag("word_${reference.first().uid}").assertExists()
            compose.onNodeWithTag("library_error").assertDoesNotExist()
        }
    }
}

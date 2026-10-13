package com.scott.memento.ui

import android.content.Context
import android.annotation.SuppressLint
import androidx.lifecycle.ViewModel
import com.scott.memento.data.content.ContentCatalog
import com.scott.memento.data.user.*
import com.scott.memento.data.migration.*
import android.net.Uri
import com.scott.memento.domain.*
import com.scott.memento.ui.library.LibraryPager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

data class AppState(
    val loaded:Boolean=false,val preferences:AppPreferences?=null,val language:LanguagePreferences?=null,
    val books:List<Book> = emptyList(),val index:StudyContentIndex?=null,val study:StudySnapshot?=null,
    val overview:LearningOverview?=null,val word:Detail?=null,val busy:Boolean=false,val error:String?=null,val generation:Long=0,
    val home:HomeProgress?=null,
    val summary:PlanSummary?=null,val recentSummary:PlanSummary?=null,val completion:StudyCompletion?=null,
)
data class SettingsData(val books:List<Book>,val preferences:LanguagePreferences?,val bookProgress:Map<String,Int>)
data class SummaryData(val summary:PlanSummary,val focusLabels:Map<String,String?>)

/** Activity recreation keeps this owner. Selection and scoring share one serialized mutation path. */
class MementoController(context:Context,database:String,clock:Clock=Clock.systemUTC()):ViewModel() {
    @SuppressLint("StaticFieldLeak") // Only the application context is retained, never an Activity or View.
    private val context=context.applicationContext
    private val store=UserDatabase(this.context,database)
    private val study=StudyRepository(store,clock)
    private val app=AppRepository(store,study,clock)
    private val summaries=PlanSummaryRepository(store)
    private val readers=mutableMapOf<String,ContentCatalog>()
    private val indexes=mutableMapOf<Selection,StudyContentIndex>()
    private val mutex=Mutex()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    // Shallow pages belong to this ViewModel, independent of a screen's composition.
    val library=LibraryPager(scope) { criteria,offset->
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val catalog=reader(criteria.target)
                if(criteria.favoritesOnly) catalog.favoritePage(criteria.favorites,criteria.query,offset,criteria.book)
                else catalog.page(criteria.query,criteria.book,offset)
            }
        }
    }
    private val mutable=MutableStateFlow(AppState())
    val state=mutable.asStateFlow()
    /** Dismissing presentation feedback does not retry a write or change a daily plan. */
    fun dismissError() {mutable.value=mutable.value.copy(error=null)}
    private val importState=MutableStateFlow(LegacyUiState())
    val legacy=importState.asStateFlow()
    private val importer=LegacyImporter(store,clock)
    private var closed=false
    private var completionSequence=0L
    init { reload() }
    fun reload()=mutate {val preferences=app.preferences();if(preferences.selection==null) mutable.value=AppState(loaded=true,preferences=preferences,busy=true) else publish(preferences.selection)}

    private fun reader(target:String)=readers.getOrPut(target){ContentCatalog(context,Languages.get(target))}
    private fun index(selection:Selection)=indexes.getOrPut(selection){reader(selection.target).studyIndex(selection.learning)}
    private suspend fun publish(selection:Selection,snapshot:StudySnapshot?=null) {
        val content=index(selection)
        val current=snapshot ?: study.ensureDailyPlan(content)
        val summary=current.planId?.let{summaries.current(selection.target,it)}
        val next=AppState(true,app.preferences(),app.language(selection.target),reader(selection.target).books(),content,current,
            app.overview(content,current),current.next?.let{reader(selection.target).detail(it.uid)},mutable.value.busy,null,mutable.value.generation+1,
            home=app.homeProgress(current,selection.target),summary=summary,
            recentSummary=if(summary?.isCompleted==true) summary else summaries.latestCompleted(selection.target),
            completion=mutable.value.completion)
        withContext(Dispatchers.Main.immediate){mutable.value=next}
    }
    private fun mutate(action:suspend ()->Unit) {
        scope.launch {
            // Set busy before accepting another UI mutation. Once a write starts, it finishes before a switch.
            if(mutable.value.busy) return@launch
            mutable.value=mutable.value.copy(busy=true,error=null)
            try { mutex.withLock { withContext(Dispatchers.IO+NonCancellable){action()} } }
            catch(e:CancellationException){throw e}
            catch(e:Exception){mutable.value=mutable.value.copy(error=e.message ?: e.javaClass.simpleName)}
            finally { if(!closed) mutable.value=mutable.value.copy(busy=false) }
        }
    }
    fun select(selection:Selection,onDone:()->Unit={})=mutate {
        app.select(selection);publish(selection)
        withContext(Dispatchers.Main.immediate){onDone()}
    }
    fun save(selection:Selection,policy:StudyPolicy,book:String,zone:String,playback:PlaybackPreferences,onDone:()->Unit)=mutate {
        val snapshot=app.save(index(selection),policy,book,zone,playback)
        publish(selection,snapshot)
        withContext(Dispatchers.Main.immediate){onDone()}
    }
    fun reveal(request:RatingRequest)=score(request,null)
    fun rate(request:RatingRequest,rating:Rating)=score(request,rating)
    private fun score(request:RatingRequest,rating:Rating?)=mutate {
        val selection=checkNotNull(app.preferences().selection)
        if(selection.target!=request.identity.targetLanguage) return@mutate
        val outcome=if(rating==null) study.reveal(index(selection),request) else study.rate(index(selection),request,rating)
        publish(selection,outcome.snapshot)
        if(rating!=null && outcome.result==ScoreResult.APPLIED) emitCompletion(selection.target)
        if(outcome.result !in setOf(ScoreResult.APPLIED,ScoreResult.ALREADY_APPLIED))
            withContext(Dispatchers.Main.immediate){mutable.value=mutable.value.copy(error="卡片已更新，请根据当前计划继续。")}
    }
    fun chooseRollover(planId:Long,choice:RolloverChoice)=mutate {
        val selection=checkNotNull(app.preferences().selection)
        publish(selection,study.chooseRollover(index(selection),planId,choice))
    }
    fun chooseExtra(planId:Long,accept:Boolean)=mutate {
        val selection=checkNotNull(app.preferences().selection)
        val wasPending=mutable.value.study?.let{it.planId==planId && it.overflowChoice}==true
        publish(selection,study.chooseExtra(index(selection),planId,accept))
        if(!accept && wasPending) emitCompletion(selection.target)
    }
    private suspend fun emitCompletion(target:String) {
        val current=mutable.value.study ?: return
        val summary=mutable.value.summary ?: return
        if(current.guard!=StudyGuard.READY || current.next!=null || current.overflowChoice || !summary.isCompleted) return
        withContext(Dispatchers.Main.immediate) {
            completionSequence++
            mutable.value=mutable.value.copy(completion=StudyCompletion(target,summary.planId,summary.revision,
                "$target:${summary.planId}:${summary.revision}:${summary.extraDecision}:$completionSequence"))
        }
    }
    fun favorite(selection:Selection,uid:String)=mutate {
        if(app.preferences().selection!=selection) return@mutate
        check(reader(selection.target).summary(uid)!=null)
        app.toggleFavorite(selection.target,uid);publish(selection)
    }
    fun refresh() {
        if(!mutable.value.loaded || mutable.value.busy || closed) return
        val selection=mutable.value.preferences?.selection ?: return
        scope.launch {
            if(!mutex.tryLock()) return@launch
            try {
                withContext(Dispatchers.IO) {
                    val current=study.ensureDailyPlan(index(selection))
                    // No redundant detail/overview loading on each foreground tick.
                    if(current!=mutable.value.study) publish(selection,current)
                }
            } catch(e:CancellationException){throw e}
            catch(e:Exception){mutable.value=mutable.value.copy(error=e.message)}
            finally { mutex.unlock() }
        }
    }
    suspend fun draftData(target:String):SettingsData = withContext(Dispatchers.IO) {
        mutex.withLock {
            val learned=app.learned(target)
            SettingsData(reader(target).books(),app.language(target),index(Selection(target,"zh-Hans")).books.mapValues{(_,uids)->uids.count{it in learned}})
        }
    }
    suspend fun page(selection:Selection,query:String,book:String,offset:Int,favorites:Boolean):WordPage=withContext(Dispatchers.IO) {
        mutex.withLock {
            if(favorites) reader(selection.target).favoritePage(app.favorites(selection.target),query,offset,book)
            else reader(selection.target).page(query,book,offset)
        }
    }
    suspend fun detail(selection:Selection,uid:String):Detail?=withContext(Dispatchers.IO) {mutex.withLock {reader(selection.target).detail(uid)}}
    suspend fun summary(selection:Selection,planId:Long):SummaryData?=withContext(Dispatchers.IO) {
        mutex.withLock {
            val result=summaries.current(selection.target,planId) ?: return@withLock null
            SummaryData(result,result.focusUids.associateWith{uid->reader(selection.target).summary(uid)?.lemma})
        }
    }
    fun previewLegacy(uri:Uri,target:String)=mutate {
        importState.value.preview?.file?.delete()
        importState.value=LegacyUiState()
        var copy:java.io.File?=null
        try {
            copy=LegacyDocuments.copy(context,uri)
            val preview=LegacySource.preview(copy,target,index(Selection(target,"zh-Hans")).available)
            withContext(Dispatchers.Main.immediate){importState.value=LegacyUiState(preview=preview)}
        } catch(e:Exception){copy?.delete();throw e}
    }
    fun clearLegacy()=mutate {importState.value.preview?.file?.delete();withContext(Dispatchers.Main.immediate){importState.value=LegacyUiState()}}
    fun importLegacy(options:LegacyImportOptions)=mutate {
        val preview=checkNotNull(importState.value.preview) { "请重新选择备份" }
        val result=try {importer.import(preview,index(Selection(preview.target,"zh-Hans")),options)}
        catch(e:LegacyImportFailure){
            runCatching {
                val directory=java.io.File(context.filesDir,"migration-reports").apply{check(isDirectory || mkdirs())}
                java.io.File(directory,"failed-${java.util.UUID.randomUUID()}.json").writeText(e.report.toString(2))
            }
            withContext(Dispatchers.Main.immediate){importState.value=importState.value.copy(failure=e.report)}
            throw e
        }
        // SQLite retains the complete authoritative report even if a report file cannot be written.
        runCatching{
            val directory=java.io.File(context.filesDir,"migration-reports").apply{check(isDirectory || mkdirs())}
            java.io.File(directory,"batch-${result.batchId}.json").writeText(result.report.toString(2))
        }
        // Keep the committed report even if a subsequent UI refresh fails.
        withContext(Dispatchers.Main.immediate){importState.value=importState.value.copy(result=result,failure=null)}
        app.preferences().selection?.let{publish(it)}
    }
    override fun onCleared() {
        closed=true;scope.cancel()
        CoroutineScope(Dispatchers.IO).launch {mutex.withLock{importState.value.preview?.file?.delete();readers.values.forEach{it.close()};store.close()}}
    }
}
data class LegacyUiState(val preview:LegacyPreview?=null,val result:LegacyImportResult?=null,val failure:org.json.JSONObject?=null)

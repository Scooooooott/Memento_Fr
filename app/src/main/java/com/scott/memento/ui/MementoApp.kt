package com.scott.memento.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.scott.memento.R
import com.scott.memento.domain.*
import com.scott.memento.ui.components.*
import com.scott.memento.ui.home.HomeScreen
import com.scott.memento.ui.navigation.*
import com.scott.memento.ui.summary.SummaryPane
import com.scott.memento.ui.detail.WordDetailScreen
import com.scott.memento.ui.library.LibraryScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import com.scott.memento.ui.statistics.StatisticsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MementoApp(controller:MementoController) {
    val state by controller.state.collectAsState()
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val player=remember{PronunciationPlayer(context)}
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val dispatcher=LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val navigation=rememberSaveable(saver=AppNavigation.Saver){AppNavigation()}
    val settingsState=rememberSaveableStateHolder()
    val importFormState=rememberSaveableStateHolder()
    val detailsState=rememberSaveableStateHolder()
    val libraryState=rememberSaveableStateHolder()
    val statisticsState=rememberSaveableStateHolder()
    var studyControlsHeight by remember{mutableIntStateOf(0)}
    var speechRequest by remember{mutableLongStateOf(0)}
    val density=LocalDensity.current
    var detailKeys by rememberSaveable {mutableStateOf(listOf<String>())}
    var query by rememberSaveable{mutableStateOf("")}
    var browseBook by rememberSaveable{mutableStateOf("")}
    var displayedTarget by rememberSaveable{mutableStateOf<String?>(null)}
    var speech by remember{mutableStateOf<SpeechStatus?>(null)}
    var autoPlayed by rememberSaveable{mutableStateOf<String?>(null)}
    val selection=state.preferences?.selection
    val route=navigation.route
    fun stopAudio(){speechRequest++;player.stop();speech=null}
    fun prepareNavigation(){focus.clearFocus(force=true);keyboard?.hide();stopAudio()}
    fun back() {
        prepareNavigation()
        if(navigation.route==AppRoute.SETTINGS) settingsState.removeState("settings")
        navigation.back()
    }
    fun navigate(destination:AppRoute) {
        prepareNavigation()
        if(destination==AppRoute.SETTINGS) settingsState.removeState("settings")
        navigation.open(destination)
    }
    fun openDetail(uid:String){
        val target=selection?.target ?: return
        prepareNavigation()
        val key="$target:$uid"
        val history=detailKeys.filter{it!=key}+key
        history.dropLast(16).forEach{detailsState.removeState(it)}
        detailKeys=history.takeLast(16)
        navigation.detail(uid)
    }
    fun openSummary(planId:Long,token:String?=null){prepareNavigation();navigation.summary(planId,token)}
    fun speak(word:Detail){
        val chosen=selection ?: return
        val request=++speechRequest
        player.speak(word,chosen.target,state.language?.playback?.ttsFallback!=false){if(request==speechRequest) speech=it}
    }
    DisposableEffect(player){onDispose{player.close()}}
    LaunchedEffect(lifecycle,controller) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {player.refreshAvailability();try {while(true){controller.refresh();delay(1000)}} finally {stopAudio()}}
    }
    LaunchedEffect(selection?.target) {
        if(displayedTarget!=null && displayedTarget!=selection?.target){navigation.targetChanged();query="";browseBook=""}
        displayedTarget=selection?.target;stopAudio()
    }
    LaunchedEffect(state.completion,route,state.busy,state.study) {
        val event=state.completion
        if(event!=null && !state.busy && route==AppRoute.STUDY && event.target==selection?.target
            && event.token!=navigation.handledCompletion && state.study?.guard==StudyGuard.READY
            && state.study?.planId==event.planId && state.study?.revision==event.revision
            && state.summary?.isCompleted==true && state.study?.next==null && state.study?.overflowChoice!=true)
            openSummary(event.planId,event.token)
    }
    LaunchedEffect(route,navigation.detailUid,state.study?.next?.id,selection?.target) {
        stopAudio()
        if(route==AppRoute.STUDY && state.study?.guard==StudyGuard.READY) {
            val item=state.study?.next
            val word=state.word
            val mode=state.language?.playback?.autoPlay ?: AutoPlay.NEVER
            val token="${selection?.target}:${state.study?.planId}:${item?.id}"
            if(item!=null && word?.summary?.uid==item.uid && token!=autoPlayed && (mode==AutoPlay.ALL || (mode==AutoPlay.NEW_ONLY && item.isNew))) {
                autoPlayed=token;speak(word)
            }
        }
    }
    BackHandler(selection!=null && route!=AppRoute.HOME){if(!state.busy) back()}
    val mainRoute=route in setOf(AppRoute.HOME,AppRoute.LIBRARY,AppRoute.FAVORITES,AppRoute.STATISTICS)
    Scaffold(
        topBar={AppTopBar(route,selection?.let{stringResource(R.string.language_context_format,languageLabel(it.target),languageLabel(it.learning))},state.busy,
            onBack={dispatcher?.onBackPressed() ?: back()},onSettings={navigate(AppRoute.SETTINGS)},onCatalog={browseBook="";query="";navigate(AppRoute.LIBRARY)})},
        bottomBar={if(selection!=null && mainRoute) MainNavigation(route,state.busy,::navigate)},
        snackbarHost={AppNotice(state.error,speech,selection?.let{Languages.get(it.target).locale}.orEmpty(),
            onDismissError=controller::dismissError,onDismissSpeech=::stopAudio,
            modifier=Modifier.imePadding().padding(bottom=if(route==AppRoute.STUDY) with(density){studyControlsHeight.toDp()} else 0.dp))},
    ) {padding->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().padding(horizontal=20.dp)) {
            if(!state.loaded || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("loading"))
            if(!state.loaded) {
                Text(stringResource(if(state.busy) R.string.loading else R.string.ui6_loading_retry_hint))
                if(state.error!=null || !state.busy) TextButton(onClick={controller.reload()},enabled=!state.busy,
                    modifier=Modifier.heightIn(min=48.dp).testTag("app_retry")){Text(stringResource(R.string.retry))}
            }
            else if(selection==null) SettingsPane(state,controller,true,onDone={navigate(AppRoute.HOME)},onCancel={})
            else {
                when(route) {
                    AppRoute.SETTINGS->settingsState.SaveableStateProvider("settings") {
                        SettingsPane(state,controller,false,onDone=::back,onCancel=::back,onImport={prepareNavigation();navigation.open(AppRoute.IMPORT)})
                    }
                    AppRoute.IMPORT->importFormState.SaveableStateProvider("import") {
                        ImportPane(state,controller,onBack=::back)
                    }
                    AppRoute.LIBRARY,AppRoute.FAVORITES,AppRoute.DETAIL-> {
                        val libraryOrigin=if(route==AppRoute.DETAIL) navigation.detailFrom else route
                        if(libraryOrigin in setOf(AppRoute.LIBRARY,AppRoute.FAVORITES)) Box(Modifier.weight(1f)) {
                            // The ViewModel owns loaded pages; this holder saves viewport state across secondary pages.
                            val hidden=if(route==AppRoute.DETAIL) Modifier.alpha(0f).clearAndSetSemantics{} else Modifier
                            libraryState.SaveableStateProvider("${selection.target}:${libraryOrigin.name}") {
                                LibraryScreen(state,controller.library,query,{query=it},browseBook,{browseBook=it},libraryOrigin==AppRoute.FAVORITES,
                                    onFavorites={navigate(if(libraryOrigin==AppRoute.FAVORITES) AppRoute.LIBRARY else AppRoute.FAVORITES)},onOpen=::openDetail,
                                    modifier=Modifier.fillMaxSize().then(hidden),visible=route!=AppRoute.DETAIL)
                            }
                            if(route==AppRoute.DETAIL) Surface(Modifier.fillMaxSize()) {
                                detailsState.SaveableStateProvider("${selection.target}:${navigation.detailUid}") {
                                    DetailPane(selection,checkNotNull(navigation.detailUid),state,controller,onSpeak=::speak,modifier=Modifier.fillMaxSize())
                                }
                            }
                        } else detailsState.SaveableStateProvider("${selection.target}:${navigation.detailUid}") {
                            DetailPane(selection,checkNotNull(navigation.detailUid),state,controller,onSpeak=::speak,modifier=Modifier.weight(1f))
                        }
                    }
                    AppRoute.STUDY->StudyPane(state,controller,onSettings={navigate(AppRoute.SETTINGS)},onSpeak=::speak,onDetail=::openDetail,modifier=Modifier.weight(1f),onControlsHeightChanged={studyControlsHeight=it})
                    AppRoute.SUMMARY->SummaryPane(state,controller,navigation.summaryPlanId,onDetail=::openDetail,
                        onHome={navigate(AppRoute.HOME)},onResume={navigate(AppRoute.STUDY)},modifier=Modifier.weight(1f))
                    AppRoute.STATISTICS->statisticsState.SaveableStateProvider(selection.target) {
                        state.overview?.let{StatisticsScreen(it,Modifier.weight(1f))}
                    }
                    AppRoute.HOME->HomeScreen(state,onStudy={navigate(AppRoute.STUDY)},onSettings={navigate(AppRoute.SETTINGS)},onBook={browseBook=it;query="";navigate(AppRoute.LIBRARY)},
                        onRecentSummary={state.recentSummary?.let{openSummary(it.planId)}},modifier=Modifier.weight(1f))
                }
            }
        }
    }
    if(selection!=null && route !in setOf(AppRoute.SETTINGS,AppRoute.IMPORT) && state.study?.guard==StudyGuard.MIDNIGHT_CHOICE) AlertDialog(onDismissRequest={},title={Text(stringResource(R.string.new_day))},text={Text(stringResource(R.string.rollover_hint))},
        confirmButton={TextButton(onClick={controller.chooseRollover(state.study!!.planId!!,RolloverChoice.IMMEDIATE)},enabled=!state.busy,modifier=Modifier.testTag("reset_now")){Text(stringResource(R.string.reset_now))}},
        dismissButton={TextButton(onClick={controller.chooseRollover(state.study!!.planId!!,RolloverChoice.DEFER)},enabled=!state.busy,modifier=Modifier.testTag("reset_later")){Text(stringResource(R.string.reset_later))}})
    if(selection!=null && route !in setOf(AppRoute.SETTINGS,AppRoute.IMPORT) && state.study?.guard==StudyGuard.READY && state.study?.overflowChoice==true) AlertDialog(onDismissRequest={},title={Text(stringResource(R.string.overflow_title))},text={Text(stringResource(R.string.overflow_hint,state.study!!.extraRemaining))},
        confirmButton={TextButton(onClick={controller.chooseExtra(state.study!!.planId!!,true)},enabled=!state.busy,modifier=Modifier.testTag("extra_accept")){Text(stringResource(R.string.continue_review))}},
        dismissButton={TextButton(onClick={controller.chooseExtra(state.study!!.planId!!,false)},enabled=!state.busy,modifier=Modifier.testTag("extra_decline")){Text(stringResource(R.string.finish_today))}})
}

@Composable internal fun languageLabel(id:String):String=when(id){
    "fr"->stringResource(R.string.french);"es"->stringResource(R.string.spanish);"en"->stringResource(R.string.english)
    "zh-Hans","zh-Hant"->stringResource(R.string.chinese);else->Languages.available.firstOrNull{it.id==id}?.label ?: id
}

@Composable private fun DetailPane(selection:Selection,uid:String,state:AppState,controller:MementoController,onSpeak:(Detail)->Unit,modifier:Modifier) {
    var detail by remember(selection.target,uid){mutableStateOf<Detail?>(null)}
    var loaded by remember(selection.target,uid){mutableStateOf(false)}
    var error by remember(selection.target,uid){mutableStateOf<String?>(null)}
    LaunchedEffect(selection,uid) {
        withContext(Dispatchers.Main.immediate){error=null;loaded=false}
        try{
            val result=controller.detail(selection,uid)
            coroutineContext.ensureActive()
            withContext(Dispatchers.Main.immediate){detail=result;loaded=true}
        }
        catch(e:CancellationException){throw e}
        catch(e:Exception){withContext(Dispatchers.Main.immediate){error=e.message;loaded=true}}
    }
    Column(modifier) {
        error?.let{Text(stringResource(R.string.operation_failed,it))}
        if(!loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if(detail==null) Text(stringResource(R.string.word_missing))
        detail?.let{word->
            WordDetailContent(word,selection.learning,state.language?.playback?.showReading!=false,Modifier.fillMaxSize()) {
                Row {
                    TextButton(onClick={onSpeak(word)},modifier=Modifier.heightIn(min=48.dp).testTag("detail_speak")){Text(stringResource(R.string.pronounce))}
                    TextButton(onClick={controller.favorite(selection,uid)},enabled=!state.busy,modifier=Modifier.heightIn(min=48.dp).testTag("detail_favorite")){Text(stringResource(if(uid in state.overview?.favorites.orEmpty()) R.string.unfavorite else R.string.favorite))}
                }
            }
        }
    }
}


@Composable internal fun WordDetailContent(word:Detail,learning:String,showReading:Boolean,modifier:Modifier=Modifier,actions:@Composable ()->Unit={}) {
    WordDetailScreen(word,learning,showReading,modifier,actions)
}

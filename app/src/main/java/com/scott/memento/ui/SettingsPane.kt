package com.scott.memento.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.scott.memento.R
import com.scott.memento.BuildConfig
import com.scott.memento.domain.*
import com.scott.memento.ui.settings.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.time.ZoneId

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ColumnScope.SettingsPane(
    state:AppState,controller:MementoController,onboarding:Boolean,onDone:()->Unit,onCancel:()->Unit,onImport:()->Unit={},
) {
    val initialSelection=state.preferences?.selection
    var target by rememberSaveable{mutableStateOf(initialSelection?.target ?: "fr")}
    var drafts by rememberSaveable(stateSaver=SettingsDraftsSaver){mutableStateOf(emptyMap<String,SettingsDraft>())}
    var zone by rememberSaveable{mutableStateOf(state.preferences?.zone.orEmpty())}
    var picker by rememberSaveable{mutableStateOf(false)}
    var pickerTarget by rememberSaveable{mutableStateOf("")}
    var pickerBook by rememberSaveable{mutableStateOf("")}
    var metadata by remember{mutableStateOf(SettingsMetadata(""))}
    var retry by remember{mutableIntStateOf(0)}
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    fun dismissInput(){focus.clearFocus(force=true);keyboard?.hide()}
    fun updateDraft(transform:(SettingsDraft)->SettingsDraft) {
        drafts[target]?.let{drafts=drafts+(target to transform(it))}
    }
    LaunchedEffect(target,retry) {
        val requestedTarget=target
        withContext(Dispatchers.Main.immediate) {
            metadata=SettingsMetadata(requestedTarget)
            if(BuildConfig.DEBUG) android.util.Log.d("MementoSettings","load $requestedTarget started")
        }
        try {
            val data=controller.draftData(requestedTarget)
            coroutineContext.ensureActive()
            withContext(Dispatchers.Main.immediate) {
                if(requestedTarget==target) {
                    if(requestedTarget !in drafts) {
                        val learning=initialSelection?.takeIf{it.target==requestedTarget}?.learning ?: "zh-Hans"
                        drafts=drafts+(requestedTarget to SettingsDraft.initial(requestedTarget,data.preferences,learning))
                    }
                    metadata=SettingsMetadata(requestedTarget,data)
                    if(BuildConfig.DEBUG) android.util.Log.d("MementoSettings","load $requestedTarget completed")
                }
            }
        } catch(e:CancellationException){throw e}
        catch(e:Exception){
            withContext(Dispatchers.Main.immediate) {
                if(requestedTarget==target) metadata=SettingsMetadata(requestedTarget,error=e.message ?: e.javaClass.simpleName)
                if(BuildConfig.DEBUG) android.util.Log.w("MementoSettings","load $requestedTarget failed: ${e.javaClass.simpleName}")
            }
        }
    }
    val data=metadata.data.takeIf{metadata.target==target}
    val loading=metadata.target!=target || metadata.data==null && metadata.error==null
    val loadError=metadata.error.takeIf{metadata.target==target}
    val books=data?.books.orEmpty()
    val bookProgress=data?.bookProgress.orEmpty()
    val savedBook=data?.preferences?.book
    val configured=data?.preferences!=null
    val draft=drafts[target] ?: SettingsDraft.initial(target,null,initialSelection?.learning ?: "zh-Hans")
    val ready=data!=null && target in drafts
    val editable=ready && !state.busy
    val validGoal=draft.goal.trim().toIntOrNull()?.takeIf{it>0}
    val validZone=runCatching{ZoneId.of(zone.trim()).id}.getOrNull()
    val datePending=initialSelection?.target==target && state.study?.guard==StudyGuard.MIDNIGHT_CHOICE
    BackHandler(picker){if(!state.busy){dismissInput();picker=false}}
    if(picker && pickerTarget==target) {
        if(!ready) {
            Text(stringResource(R.string.choose_book),style=MaterialTheme.typography.headlineSmall)
            if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            loadError?.let{Text(stringResource(R.string.operation_failed,it),color=MaterialTheme.colorScheme.error)}
            if(loadError!=null) TextButton(onClick={retry++},enabled=!state.busy,modifier=Modifier.testTag("settings_retry")){Text(stringResource(R.string.retry))}
            Spacer(Modifier.weight(1f))
            TextButton(onClick={picker=false},enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("cancel_book")){Text(stringResource(R.string.cancel))}
        } else BookPicker(books,bookProgress,pickerBook,savedBook,state.busy,
            onSelect={pickerBook=it},onConfirm={updateDraft{it.copy(book=pickerBook)};picker=false},onCancel={picker=false})
        return
    }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag(if(onboarding) "onboarding" else "settings_form"),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(onboarding) {
            Text(stringResource(R.string.welcome),style=MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.welcome_hint))
        } else Text(stringResource(R.string.ui5_settings_draft_hint),style=MaterialTheme.typography.bodySmall)
        SettingsSection(stringResource(R.string.ui5_language_section),"settings_language_section") {
            Text(stringResource(R.string.target_language))
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Languages.available.forEach{option->FilterChip(selected=target==option.id,onClick={dismissInput();target=option.id},enabled=!state.busy,
                    label={Text(languageLabel(option.id))},modifier=Modifier.testTag("settings_target_${option.id}"))}
            }
            Text(stringResource(R.string.learning_language))
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Languages.get(target).learningLanguages.forEach{id->FilterChip(selected=draft.learning==id,onClick={updateDraft{it.copy(learning=id)}},enabled=editable,
                    label={Text(languageLabel(id))},modifier=Modifier.testTag("settings_learning_$id"))}
            }
            if(!onboarding) {
                Text(stringResource(R.string.language_apply_hint),style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick={dismissInput();controller.select(Selection(target,draft.learning),onDone)},enabled=editable,
                    modifier=Modifier.testTag("language_apply")){Text(stringResource(R.string.language_apply))}
            }
        }
        if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        loadError?.let {
            Text(stringResource(R.string.operation_failed,it),color=MaterialTheme.colorScheme.error)
            TextButton(onClick={retry++},enabled=!state.busy,modifier=Modifier.testTag("settings_retry")){Text(stringResource(R.string.retry))}
        }
        SettingsSection(stringResource(R.string.ui5_plan_section),"settings_plan_section") {
            if(ready && !configured) Text(stringResource(R.string.ui5_plan_unconfigured),style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("settings_unconfigured"))
            Row(Modifier.fillMaxWidth().toggleable(value=draft.reviewAll,enabled=editable,role=Role.Checkbox,onValueChange={value->updateDraft{it.copy(reviewAll=value)}})
                .testTag("review_all"),verticalAlignment=Alignment.CenterVertically) {
                Checkbox(draft.reviewAll,onCheckedChange=null,enabled=editable)
                Text(stringResource(R.string.review_all),Modifier.padding(start=8.dp))
            }
            if(!draft.reviewAll) OutlinedTextField(draft.goal,{value->updateDraft{it.copy(goal=value)}},enabled=editable,
                label={Text(stringResource(R.string.goal_label))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number,imeAction=ImeAction.Done),
                keyboardActions=KeyboardActions(onDone={dismissInput()}),
                isError=draft.goal.isNotEmpty() && validGoal==null,supportingText={if(draft.goal.isNotEmpty() && validGoal==null) Text(stringResource(R.string.goal_invalid))},
                modifier=Modifier.fillMaxWidth().testTag("study_target"))
            Text(stringResource(R.string.same_day_hint),style=MaterialTheme.typography.bodySmall)
            val currentBook=books.firstOrNull{it.id==draft.book}
            Row(Modifier.fillMaxWidth().clickable(enabled=editable && books.isNotEmpty(),role=Role.Button) {
                dismissInput();pickerTarget=target;pickerBook=draft.book;picker=true
            }.testTag("settings_choose_book").padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.choose_book),fontWeight=FontWeight.SemiBold)
                    Text(currentBook?.title ?: stringResource(R.string.ui5_book_missing),modifier=Modifier.testTag("settings_book_title"))
                    if(currentBook!=null) {
                        val learned=bookProgress[currentBook.id] ?: 0
                        if(currentBook.description.isNotBlank()) Text(currentBook.description,style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("settings_book_description"))
                        Text(stringResource(R.string.book_progress,currentBook.title,learned,currentBook.count),style=MaterialTheme.typography.bodySmall,
                            modifier=Modifier.testTag("settings_book_progress"))
                        Text(stringResource(R.string.home_book_percent,bookPercent(learned,currentBook.count)),style=MaterialTheme.typography.bodySmall,
                            modifier=Modifier.testTag("settings_book_percent"))
                    }
                }
                Text("›",style=MaterialTheme.typography.headlineSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SettingsSection(stringResource(R.string.ui5_display_section),"settings_display_section") {
            SettingsSwitch(stringResource(R.string.show_reading),stringResource(R.string.ui5_reading_hint),draft.reading,editable,"show_reading") {value->updateDraft{it.copy(reading=value)}}
        }
        SettingsSection(stringResource(R.string.ui5_voice_section),"settings_voice_section") {
            Text(stringResource(R.string.autoplay))
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                AutoPlay.entries.forEach{mode->FilterChip(selected=draft.autoplay==mode.name,onClick={updateDraft{it.copy(autoplay=mode.name)}},enabled=editable,
                    label={Text(stringResource(when(mode){AutoPlay.NEVER->R.string.autoplay_never;AutoPlay.NEW_ONLY->R.string.autoplay_new;AutoPlay.ALL->R.string.autoplay_all}))},
                    modifier=Modifier.testTag("autoplay_${mode.name}"))}
            }
            SettingsSwitch(stringResource(R.string.tts_fallback),stringResource(R.string.ui5_tts_hint),draft.tts,editable,"tts_fallback") {value->updateDraft{it.copy(tts=value)}}
        }
        SettingsSection(stringResource(R.string.ui5_time_section),"settings_time_section") {
            OutlinedTextField(zone,{zone=it},enabled=!state.busy,label={Text(stringResource(R.string.study_zone))},singleLine=true,isError=validZone==null,
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={dismissInput()}),
                supportingText={Text(stringResource(if(validZone==null) R.string.zone_invalid else R.string.zone_hint))},modifier=Modifier.fillMaxWidth().testTag("study_zone"))
            if(!onboarding) Text(stringResource(R.string.zone_transition),style=MaterialTheme.typography.bodySmall)
        }
        if(!onboarding) SettingsSection(stringResource(R.string.ui5_import_section),"settings_import_section") {
            Text(stringResource(R.string.import_boundary),style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={dismissInput();onImport()},enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("open_import")){Text(stringResource(R.string.import_title))}
        }
        if(datePending) {
            Text(stringResource(R.string.midnight_pending),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("settings_date_pending"))
            Text(stringResource(R.string.rollover_hint),style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={dismissInput();controller.chooseRollover(checkNotNull(state.study?.planId),RolloverChoice.IMMEDIATE)},
                enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("settings_reset_now")){Text(stringResource(R.string.reset_now))}
            OutlinedButton(onClick={dismissInput();controller.chooseRollover(checkNotNull(state.study?.planId),RolloverChoice.DEFER)},
                enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("settings_reset_later")){Text(stringResource(R.string.reset_later))}
        }
        Spacer(Modifier.height(8.dp))
    }
    HorizontalDivider()
    Button(onClick={
        dismissInput()
        val policy=if(draft.reviewAll) StudyPolicy(StudyMode.REVIEW_ALL,null) else StudyPolicy(StudyMode.STANDARD,validGoal!!)
        controller.save(Selection(target,draft.learning),policy,draft.book,validZone!!,
            PlaybackPreferences(AutoPlay.valueOf(draft.autoplay),draft.tts,draft.reading),onDone)
    },enabled=editable && !datePending && (draft.reviewAll || validGoal!=null) && validZone!=null && books.any{it.id==draft.book},
        modifier=Modifier.fillMaxWidth().padding(top=8.dp).heightIn(min=52.dp).testTag("study_save")) {
        Text(stringResource(if(onboarding) R.string.begin else R.string.save))
    }
    if(!onboarding) TextButton(onClick={dismissInput();onCancel()},enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("settings_cancel")){Text(stringResource(R.string.cancel))}
}

@Composable private fun SettingsSection(title:String,tag:String,content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().testTag(tag),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics{heading()})
        content()
        HorizontalDivider(Modifier.padding(top=8.dp))
    }
}

@Composable private fun SettingsSwitch(title:String,hint:String,checked:Boolean,enabled:Boolean,tag:String,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value=checked,enabled=enabled,role=Role.Switch,onValueChange=onChange).testTag(tag).padding(vertical=8.dp),
        verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title)
            Text(hint,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked,onCheckedChange=null,enabled=enabled)
    }
}

private data class SettingsMetadata(val target:String,val data:SettingsData?=null,val error:String?=null)

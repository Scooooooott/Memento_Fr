package com.scott.memento.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.scott.memento.R
import com.scott.memento.data.migration.*
import com.scott.memento.domain.*
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ColumnScope.ImportPane(state:AppState,controller:MementoController,onBack:()->Unit) {
    val legacy by controller.legacy.collectAsState()
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val done=KeyboardActions(onDone={focus.clearFocus(force=true);keyboard?.hide()})
    var target by rememberSaveable{mutableStateOf(state.preferences?.selection?.target ?: "fr")}
    var source by rememberSaveable{mutableStateOf("")}
    var zone by rememberSaveable{mutableStateOf(state.preferences?.zone.orEmpty())}
    var restore by rememberSaveable{mutableStateOf(false)}
    var goal by rememberSaveable{mutableStateOf("")}
    var book by rememberSaveable{mutableStateOf("")}
    var books by remember{mutableStateOf(emptyList<Book>())}
    var confirmed by rememberSaveable{mutableStateOf(false)}
    var draftError by remember{mutableStateOf<String?>(null)}
    var initialized by rememberSaveable{mutableStateOf("")}
    val preview=legacy.preview
    val result=legacy.result
    val zero=preview?.settings?.getInt("daily_new_limit")==0
    val restoring=restore || zero
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)controller.previewLegacy(uri,target)}
    LaunchedEffect(preview?.file?.path) {
        if(preview!=null){
            target=preview.target
            if(initialized!=preview.file.name){confirmed=false;book=preview.settings.getString("book_id");initialized=preview.file.name}
            try{
                val result=controller.draftData(preview.target).books
                coroutineContext.ensureActive()
                withContext(Dispatchers.Main.immediate){books=result;draftError=null}
            }
            catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){withContext(Dispatchers.Main.immediate){draftError=e.message;books=emptyList()}}
        }
    }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("legacy_import"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        ImportSection(R.string.ui5_import_source_section,"import_source_section") {
            Text(stringResource(R.string.import_boundary),style=MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.import_snapshot_hint),style=MaterialTheme.typography.bodySmall)
            if(preview==null) FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Languages.available.forEach{language->FilterChip(selected=target==language.id,onClick={target=language.id},enabled=!state.busy,label={Text(languageLabel(language.id))},modifier=Modifier.testTag("import_target_${language.id}"))}
        }
        OutlinedButton(onClick={picker.launch(arrayOf("*/*"))},enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("import_choose_file")){Text(stringResource(R.string.import_choose_file))}
        if(preview!=null) TextButton(onClick={initialized="";controller.clearLegacy()},enabled=!state.busy,modifier=Modifier.testTag("import_clear")){Text(stringResource(R.string.import_change_source))}
        }
        if(preview!=null) {
            ImportSection(R.string.ui5_import_preview_section,"import_preview_section") {
                Text(stringResource(R.string.import_preview,languageLabel(preview.target),preview.version,preview.counts.getValue("learning_card"),preview.counts.getValue("favorites"),preview.counts.getValue("review_log"),preview.counts.getValue("study_session")),modifier=Modifier.testTag("import_preview"))
                if(preview.unavailable>0) Text(stringResource(R.string.import_missing,preview.unavailable))
                Text(stringResource(R.string.import_translation_hint))
            }
            ImportSection(R.string.ui5_import_identity_section,"import_identity_section") {
                OutlinedTextField(source,{source=it},label={Text(stringResource(R.string.import_source))},supportingText={Text(stringResource(R.string.import_source_hint))},enabled=!state.busy && result==null,singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=done,modifier=Modifier.fillMaxWidth().testTag("import_source"))
                OutlinedTextField(zone,{zone=it},label={Text(stringResource(R.string.import_old_zone))},supportingText={Text(stringResource(R.string.import_zone_hint))},enabled=!state.busy && result==null,singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=done,modifier=Modifier.fillMaxWidth().testTag("import_zone"))
            }
            ImportSection(R.string.ui5_import_settings_section,"import_settings_section") {
                if(zero) Text(stringResource(R.string.import_zero_hint))
                else {
                    Text(stringResource(R.string.import_positive_hint,preview.settings.getInt("daily_new_limit")))
                    Row(Modifier.fillMaxWidth().toggleable(value=restore,enabled=!state.busy && result==null,role=Role.Checkbox,onValueChange={restore=it}).testTag("import_restore_settings"),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(restore,null,enabled=!state.busy && result==null,modifier=Modifier)
                        Text(stringResource(R.string.import_restore_settings),Modifier.weight(1f))
                    }
            }
            if(restoring) {
                if(!zero) OutlinedTextField(goal,{goal=it},label={Text(stringResource(R.string.goal_label))},enabled=!state.busy && result==null,singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=done,modifier=Modifier.fillMaxWidth().testTag("import_goal"))
                Text(stringResource(R.string.choose_book))
                draftError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                Column(Modifier.selectableGroup()) {
                    books.forEach{option->
                        Row(Modifier.fillMaxWidth().selectable(selected=book==option.id,enabled=!state.busy && result==null,role=Role.RadioButton,onClick={book=option.id})
                            .testTag("import_book_${option.id}").padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            RadioButton(selected=book==option.id,onClick=null,enabled=!state.busy && result==null)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.book_option,option.title,option.count))
                                if(option.description.isNotBlank()) Text(option.description,style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            }
            ImportSection(R.string.ui5_import_confirm_section,"import_confirm_section") {
                Text(stringResource(R.string.import_merge_hint))
                Row(Modifier.fillMaxWidth().toggleable(value=confirmed,enabled=!state.busy && result==null,role=Role.Checkbox,onValueChange={confirmed=it}).testTag("import_confirm_snapshot"),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(confirmed,null,enabled=!state.busy && result==null,modifier=Modifier)
                    Text(stringResource(R.string.import_confirm_snapshot),Modifier.weight(1f))
                }
            }
        }
        if(result!=null) {
            ImportSection(R.string.ui5_import_result_section,"import_result_section") {
                Text(stringResource(if(result.repeated) R.string.import_repeated else R.string.import_done),style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("import_result"))
                val totals=result.report.getJSONObject("totals")
                Text(stringResource(R.string.import_report,result.batchId,totals.getInt("inserted"),totals.getInt("updated"),totals.getInt("duplicates"),totals.getInt("conflicts")))
                Text(stringResource(R.string.import_report_hint))
                val conflicts=result.report.getJSONArray("conflicts")
                for(i in 0 until minOf(conflicts.length(),20)){val item=conflicts.getJSONObject(i);Text("${item.getString("type")} / ${item.getString("id")}：${item.getString("reason")}")}
            }
        }
        legacy.failure?.let{Text(stringResource(R.string.import_failed_report,it.getString("phase"),it.optString("sourceRecordId"),it.getString("error")),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("import_failure"))}
    }
    val validSource=runCatching{LegacyImportOptions(source,zone).identity(target)}.isSuccess
    val validZone=runCatching{ZoneId.of(zone.trim())}.isSuccess
    val validGoal=goal.trim().toIntOrNull()?.takeIf{it>0}
    if(preview!=null && result==null) Button(onClick={
        val preference=if(restoring) LegacyPreferenceChoice(if(zero) StudyPolicy(StudyMode.REVIEW_ALL,null) else StudyPolicy(StudyMode.STANDARD,validGoal),book) else null
        controller.importLegacy(LegacyImportOptions(source,zone.trim(),preference))
    },enabled=!state.busy && confirmed && validSource && validZone && (!restoring || (book in books.map{it.id} && (zero || validGoal!=null))),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("import_commit")){Text(stringResource(R.string.import_commit))}
    TextButton(onClick=onBack,enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("import_back")){Text(stringResource(R.string.back))}
}

@Composable private fun ImportSection(title:Int,tag:String,content:@Composable ColumnScope.()->Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(title),style=MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

package com.scott.memento.ui.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.scott.memento.R
import com.scott.memento.domain.Detail
import com.scott.memento.ui.WordReadings
import com.scott.memento.ui.language.GrammarTable
import com.scott.memento.ui.language.LanguageUiProfiles

/** Each tab has independent saved scroll state; the caller scopes history by target and UID. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordDetailScreen(word:Detail,learning:String,showReading:Boolean,modifier:Modifier=Modifier,
                     actions:@Composable ()->Unit={}) {
    key(word.summary.uid) {
        var selected by rememberSaveable {mutableIntStateOf(0)}
        val meaningsState=rememberLazyListState()
        val formsState=rememberLazyListState()
        val grammarState=rememberLazyListState()
        val grammarHorizontal=rememberScrollState()
        val headerScroll=rememberScrollState()
        BoxWithConstraints(modifier.fillMaxSize().testTag("word_detail")) {
        val largeText=LocalDensity.current.fontScale>1.3f
        val availableHeight=maxHeight
        val availableWidth=maxWidth
        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            // Keep a usable content viewport when a long lemma, readings, and actions grow.
            // The header remains fully readable through its own saved scroll state.
            DetailHeader(word,showReading,actions,Modifier.fillMaxWidth().heightIn(max=availableHeight*(if(largeText) 0.25f else 0.4f))
                .verticalScroll(headerScroll).testTag("detail_header_scroll"))
            val labels=listOf(R.string.detail_tab_meanings,R.string.detail_tab_forms,R.string.detail_tab_grammar)
            val tags=listOf("detail_tab_meanings","detail_tab_forms","detail_tab_grammar")
            val tabs:@Composable ()->Unit={
                labels.forEachIndexed {index,label->
                    Tab(selected=selected==index,onClick={selected=index},modifier=Modifier.heightIn(min=48.dp).testTag(tags[index]),
                        text={Text(stringResource(label),style=MaterialTheme.typography.labelLarge)})
                }
            }
            if(availableWidth<320.dp || largeText) {
                ScrollableTabRow(selectedTabIndex=selected,edgePadding=0.dp,
                    modifier=Modifier.fillMaxWidth().testTag("detail_tabs")) {tabs()}
            } else TabRow(selectedTabIndex=selected,modifier=Modifier.fillMaxWidth().testTag("detail_tabs")) {tabs()}
            when(selected) {
                0->LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("detail_meanings"),state=meaningsState,
                    contentPadding=PaddingValues(bottom=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    if(word.senses.isEmpty()) item {Text(stringResource(R.string.detail_meanings_empty))}
                    itemsIndexed(word.senses,key={_,sense->sense.id}) {index,sense->
                        Column(Modifier.fillMaxWidth().testTag("detail_sense_${sense.id}"),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                            Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
                                Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                    Text(stringResource(R.string.ui2_meaning_number,index+1),style=MaterialTheme.typography.labelSmall)
                                    Text(sense.translations[learning]?.takeIf(String::isNotBlank) ?: stringResource(R.string.ui2_missing_meaning),
                                        style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("meaning_${sense.id}"))
                                }
                            }
                            if(sense.examples.isEmpty()) Text(stringResource(R.string.detail_examples_empty),style=MaterialTheme.typography.bodySmall)
                            sense.examples.forEach {example->
                                Text(example.source,modifier=Modifier.testTag("detail_example_${example.id}"))
                                Text(example.translations[learning]?.takeIf(String::isNotBlank) ?: stringResource(R.string.ui2_missing_example),
                                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier=Modifier.testTag("detail_example_translation_${example.id}"))
                            }
                        }
                    }
                }
                1->LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("detail_forms"),state=formsState,
                    contentPadding=PaddingValues(bottom=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(word.gender.isNotBlank() || word.grammarFeatures.any{it.value.isNotBlank()}) item {
                        Text(stringResource(R.string.grammar),style=MaterialTheme.typography.titleMedium)
                        if(word.gender.isNotBlank()) Text(stringResource(R.string.detail_feature_value,stringResource(R.string.detail_gender),word.gender))
                    }
                    items(word.grammarFeatures.entries.filter{it.value.isNotBlank()}.sortedBy{it.key},key={it.key}) {(name,value)->
                        val label=featureLabel(name)
                        val shown=if(name in setOf("is_irregular","is_reflexive") && value in setOf("0","1"))
                            stringResource(if(value=="1") R.string.yes else R.string.no) else value
                        Text(stringResource(R.string.detail_feature_value,label,shown),modifier=Modifier.testTag("detail_feature_$name"))
                    }
                    item {Text(stringResource(R.string.detail_tab_forms),style=MaterialTheme.typography.titleMedium)}
                    val forms=if(word.formDetails.isNotEmpty()) word.formDetails.sortedBy{it.order}.map{it.label to it.form} else word.forms
                    if(forms.isEmpty()) item {Text(stringResource(R.string.detail_forms_empty),modifier=Modifier.testTag("detail_forms_empty"))}
                    itemsIndexed(forms) {index,(label,form)->
                        Text(stringResource(R.string.detail_feature_value,label,form),style=MaterialTheme.typography.bodyLarge,
                            modifier=Modifier.fillMaxWidth().padding(vertical=4.dp).testTag("detail_form_$index"))
                    }
                }
                else->GrammarTable(word,grammarHorizontal,grammarState,Modifier.weight(1f).fillMaxWidth())
            }
        }
        }
    }
}

@Composable
private fun DetailHeader(word:Detail,showReading:Boolean,actions:@Composable ()->Unit,modifier:Modifier=Modifier) {
    val target=word.summary.uid.substringBefore(':')
    val metadata=LanguageUiProfiles.forTarget(target).metadata(word)
    val values=metadata.values+listOfNotNull(
        if(metadata.irregular) stringResource(R.string.feature_irregular) else null,
        if(metadata.reflexive) stringResource(R.string.feature_reflexive) else null,
        listOf(word.summary.levelSystem,word.summary.level).filter(String::isNotBlank).joinToString(" ").takeIf(String::isNotBlank),
    )
    OutlinedCard(modifier,shape=RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(word.summary.lemma,style=MaterialTheme.typography.headlineMedium,modifier=Modifier.testTag("word_detail_title"))
            if(showReading) WordReadings(word)
            if(values.isNotEmpty()) Text(values.joinToString(" · "),style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("detail_metadata"))
            actions()
        }
    }
}

@Composable
private fun featureLabel(name:String):String=when(name) {
    "auxiliary"->stringResource(R.string.feature_auxiliary)
    "past_participle","participle"->stringResource(R.string.feature_past_participle)
    "present_participle"->stringResource(R.string.feature_present_participle)
    "gerund"->stringResource(R.string.feature_gerund)
    "is_irregular"->stringResource(R.string.feature_irregular)
    "is_reflexive"->stringResource(R.string.feature_reflexive)
    "verb_group"->stringResource(R.string.feature_group)
    else->name
}

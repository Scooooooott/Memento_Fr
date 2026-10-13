package com.scott.memento.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scott.memento.R
import com.scott.memento.domain.*
import com.scott.memento.ui.language.LanguageUiProfiles
import java.util.UUID

/** Layout follows the two legacy apps; the current repository owns reveal and score state. */
@Composable
fun StudyPane(
    state:AppState,
    controller:MementoController,
    onSettings:()->Unit,
    onSpeak:(Detail)->Unit,
    onDetail:(String)->Unit,
    modifier:Modifier=Modifier,
    onControlsHeightChanged:(Int)->Unit={},
) {
    val reportControlsHeight by rememberUpdatedState(onControlsHeightChanged)
    DisposableEffect(Unit) {onDispose {reportControlsHeight(0)}}
    val current=state.study
    val selection=state.preferences?.selection
    if(current==null || selection==null) {
        LaunchedEffect(Unit) {reportControlsHeight(0)}
        return
    }
    val item=current.next
    val word=state.word?.takeIf{it.summary.uid==item?.uid}
    val answerAvailable=word!=null && hasStudyAnswer(word,selection.learning)
    val controlsVisible=current.guard==StudyGuard.READY && item!=null && word!=null
    LaunchedEffect(controlsVisible) {if(!controlsVisible) reportControlsHeight(0)}
    val canReveal=current.guard==StudyGuard.READY && item!=null && word!=null && !item.revealed && !state.busy
    val revealLatest by rememberUpdatedState<()->Unit> {
        if(canReveal) controller.reveal(current.request(selection.target,UUID.randomUUID().toString()))
    }
    // Child card/buttons consume taps. verticalScroll cancels tap recognition after a drag.
    val revealArea=if(canReveal) Modifier.pointerInput(item?.id,current.revision,state.busy) {
        detectTapGestures(onTap={revealLatest()})
    } else Modifier

    Column(modifier.fillMaxWidth().testTag("study_screen")) {
        key(current.planId,item?.id) {
            Column(
                Modifier.weight(1f).fillMaxWidth().then(revealArea)
                    .verticalScroll(rememberScrollState()).padding(top=12.dp,bottom=16.dp),
                verticalArrangement=Arrangement.spacedBy(if(item?.revealed==true) 10.dp else 14.dp),
            ) {
                PlanSummary(current)
                if(state.overview?.deferredUntil!=null && current.businessDate!=null) Text(
                    stringResource(R.string.ui6_deferred_hint,current.businessDate),style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.primary,modifier=Modifier.testTag("study_grace"),
                )
                when(current.guard) {
                    StudyGuard.NOT_CONFIGURED -> {
                        Text(stringResource(R.string.configure_hint))
                        Button(onClick=onSettings,enabled=!state.busy,modifier=Modifier.heightIn(min=48.dp).testTag("study_configure")) {
                            Text(stringResource(R.string.configure_plan))
                        }
                    }
                    StudyGuard.READY -> {
                        if(item==null) {
                            Text(stringResource(if(current.overflowChoice) R.string.normal_finished_more else R.string.plan_finished),modifier=Modifier.testTag("study_finished"))
                            TextButton(onClick=onSettings,enabled=!state.busy,modifier=Modifier.heightIn(min=48.dp).testTag("study_edit")) {
                                Text(stringResource(R.string.adjust_plan))
                            }
                        } else if(word==null) {
                            Text(stringResource(if(state.busy) R.string.loading else R.string.word_missing),modifier=Modifier.testTag("study_content_missing"))
                        } else {
                            StudyQueueProgress(state,current,item,word)
                            StudyWordCard(
                                word,selection.target,item.revealed,state.language?.playback?.showReading!=false,
                                word.summary.uid in state.overview?.favorites.orEmpty(),state.busy,
                                onSpeak={onSpeak(word)},onFavorite={controller.favorite(selection,word.summary.uid)},
                            )
                            if(item.revealed) {
                                Column(Modifier.fillMaxWidth().testTag("study_answer"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                    StudyMeaningBlocks(word,selection.learning)
                                    val forms=LanguageUiProfiles.forTarget(selection.target).keyForms(word)
                                    if(forms.isNotEmpty()) {
                                        Column(Modifier.fillMaxWidth().testTag("study_key_forms"),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                            Text(stringResource(R.string.ui2_key_forms),style=MaterialTheme.typography.titleSmall)
                                            forms.forEachIndexed {index,(label,form)->
                                                Row(Modifier.fillMaxWidth().padding(vertical=3.dp).testTag("study_form_$index"),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                                    Text(label,Modifier.weight(0.4f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                                    Text(form,Modifier.weight(0.6f),style=MaterialTheme.typography.bodyMedium)
                                                }
                                            }
                                        }
                                    }
                                    TextButton(onClick={onDetail(item.uid)},enabled=!state.busy,
                                        modifier=Modifier.align(Alignment.End).heightIn(min=48.dp).testTag("study_details")) {
                                        Text(stringResource(R.string.ui2_all_details))
                                    }
                                }
                            } else {
                                Text(stringResource(R.string.ui2_reveal_hint),Modifier.align(Alignment.CenterHorizontally).padding(vertical=8.dp).testTag("study_reveal_hint"),
                                    style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                val blankDescription=stringResource(R.string.ui2_reveal_hint)
                                Spacer(Modifier.fillMaxWidth().height(96.dp).testTag("study_blank_reveal").semantics {
                                    contentDescription=blankDescription
                                    if(canReveal) onClick(label=blankDescription){revealLatest();true}
                                })
                            }
                        }
                    }
                    else -> Unit // PlanSummary contains the authoritative guard message.
                }
            }
        }
        if(controlsVisible) {
            Column(Modifier.fillMaxWidth().onSizeChanged {reportControlsHeight(it.height)}) {
            if(item.revealed) {
                if(answerAvailable) StudyRatingBar(state.busy,onRate={rating->
                    controller.rate(current.request(selection.target,UUID.randomUUID().toString()),rating)
                }) else Text(stringResource(R.string.ui2_answer_blocked),Modifier.fillMaxWidth().padding(vertical=12.dp).testTag("study_answer_blocked"),
                    color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)
            } else Button(onClick={revealLatest()},enabled=!state.busy,
                modifier=Modifier.fillMaxWidth().padding(vertical=8.dp).heightIn(min=48.dp).testTag("study_reveal")) {
                Text(stringResource(R.string.reveal))
            }
            }
        }
    }
}

/** An answer must have every core sense in the selected language, never a fallback translation. */
internal fun hasStudyAnswer(word:Detail,learning:String)=word.senses.isNotEmpty() &&
    word.senses.all { !it.translations[learning].isNullOrBlank() }

@Composable
private fun StudyQueueProgress(state:AppState,current:StudySnapshot,item:StudyItem,word:Detail) {
    val extra=item.kind=="EXTRA"
    val completed=state.home?.let{if(extra) it.extraGraded else it.normalGraded}
    val remaining=if(extra) current.extraRemaining else current.normalRemaining
    val total=completed?.plus(remaining)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(
            listOf(stringResource(if(extra) R.string.extra_review else if(item.isNew) R.string.new_word else R.string.review_word),
                listOf(word.summary.levelSystem,word.summary.level).filter(String::isNotBlank).joinToString(" "))
                .filter(String::isNotBlank).joinToString(" · "),
            modifier=Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary,
        )
        if(completed!=null && total!=null && total>0) {
            Text(stringResource(R.string.ui2_study_position,completed+1,total),modifier=Modifier.testTag("study_position"),style=MaterialTheme.typography.labelLarge)
        }
    }
    if(completed!=null && total!=null && total>0) {
        LinearProgressIndicator(progress={completed.toFloat()/total},modifier=Modifier.fillMaxWidth().testTag("study_queue_progress"))
    }
}

@Composable
private fun StudyWordCard(
    word:Detail,target:String,revealed:Boolean,showReading:Boolean,favorite:Boolean,busy:Boolean,
    onSpeak:()->Unit,onFavorite:()->Unit,
) {
    val profile=LanguageUiProfiles.forTarget(target)
    val metadata=profile.metadata(word)
    val metadataText=(metadata.values+listOfNotNull(
        if(metadata.irregular) stringResource(R.string.feature_irregular) else null,
        if(metadata.reflexive) stringResource(R.string.feature_reflexive) else null,
    )).joinToString(" · ")
    val consumeCardTap=if(revealed) Modifier else Modifier.pointerInput(word.summary.uid) {detectTapGestures(onTap={})}
    OutlinedCard(
        modifier=Modifier.fillMaxWidth().testTag("study_card").then(consumeCardTap),shape=RoundedCornerShape(22.dp),
        colors=CardDefaults.outlinedCardColors(containerColor=MaterialTheme.colorScheme.surface),
    ) {
        if(revealed) {
            Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val compactActions=maxWidth<260.dp || LocalDensity.current.fontScale>1.2f
                    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end=if(compactActions) 0.dp else 8.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                Text(word.summary.lemma,fontSize=28.sp,lineHeight=32.sp,fontWeight=FontWeight.Bold,modifier=Modifier.testTag("study_word"))
                                if(showReading) WordReadings(word)
                            }
                            if(!compactActions) StudyCardActions(favorite,busy,onSpeak,onFavorite)
                        }
                        if(compactActions) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {StudyCardActions(favorite,busy,onSpeak,onFavorite)}
                    }
                }
                if(metadataText.isNotBlank()) Text(metadataText,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary,modifier=Modifier.testTag("study_metadata"))
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(word.summary.lemma,fontSize=36.sp,lineHeight=42.sp,fontWeight=FontWeight.Bold,modifier=Modifier.testTag("study_word"))
                if(showReading) WordReadings(word)
                if(metadataText.isNotBlank()) Text(metadataText,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary,modifier=Modifier.testTag("study_metadata"))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {StudyCardActions(favorite,busy,onSpeak,onFavorite)}
            }
        }
    }
}

@Composable
private fun RowScope.StudyCardActions(favorite:Boolean,busy:Boolean,onSpeak:()->Unit,onFavorite:()->Unit) {
    TextButton(onClick=onSpeak,enabled=!busy,contentPadding=PaddingValues(horizontal=8.dp),
        modifier=Modifier.heightIn(min=48.dp).testTag("study_speak")) {Text(stringResource(R.string.pronounce))}
    val favoriteDescription=stringResource(if(favorite) R.string.unfavorite else R.string.favorite)
    IconButton(onClick=onFavorite,enabled=!busy,
        modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).testTag("study_favorite").semantics{contentDescription=favoriteDescription}) {
        Text(if(favorite) "★" else "☆",fontSize=22.sp,color=MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun StudyMeaningBlocks(word:Detail,learning:String) {
    if(word.senses.isEmpty()) Text(stringResource(R.string.ui2_missing_meaning),modifier=Modifier.testTag("study_meaning_missing"))
    word.senses.forEachIndexed {index,sense->
        Column(Modifier.fillMaxWidth().testTag("study_sense_${sense.id}"),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(14.dp)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.ui2_meaning_number,index+1),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(sense.translations[learning]?.takeIf(String::isNotBlank) ?: stringResource(R.string.ui2_missing_meaning),
                    style=MaterialTheme.typography.bodyMedium,modifier=Modifier.testTag("meaning_${sense.id}"))
            }
            sense.examples.forEach {example->
                Column(Modifier.fillMaxWidth().padding(vertical=3.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(example.source,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.SemiBold,modifier=Modifier.testTag("study_example_${example.id}"))
                    Text(example.translations[learning]?.takeIf(String::isNotBlank) ?: stringResource(R.string.ui2_missing_example),
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("study_example_translation_${example.id}"))
                }
            }
        }
    }
}

/** Fixed at the bottom of StudyPane; wide normal text gets four buttons, otherwise two rows. */
@Composable
internal fun StudyRatingBar(busy:Boolean,onRate:(Rating)->Unit,modifier:Modifier=Modifier) {
    Surface(modifier=modifier.fillMaxWidth().testTag("rating_bar"),shadowElevation=5.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical=10.dp)) {
            val singleRow=maxWidth>=320.dp && LocalDensity.current.fontScale<=1.2f
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.ui2_rating_hint),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Rating.entries.chunked(if(singleRow) 4 else 2).forEachIndexed {index,ratings->
                    Row(Modifier.fillMaxWidth().testTag("rating_row_$index"),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        ratings.forEach {rating->
                            OutlinedButton(onClick={onRate(rating)},enabled=!busy,
                                modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("rating_${rating.name}"),
                                shape=RoundedCornerShape(12.dp),contentPadding=PaddingValues(horizontal=6.dp,vertical=8.dp)) {
                                Text(stringResource(when(rating){Rating.AGAIN->R.string.rating_again;Rating.HARD->R.string.rating_hard;Rating.GOOD->R.string.rating_good;Rating.EASY->R.string.rating_easy}),fontWeight=FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlanSummary(current:StudySnapshot) {
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
        current.businessDate?.let {Text(stringResource(R.string.plan_date,it,current.zone.orEmpty()),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Text(stringResource(R.string.daily_counts,current.stats.uniqueWords,current.stats.newWords,current.stats.reviewWords),
            style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("study_stats"))
        Text(stringResource(R.string.remaining_counts,current.normalRemaining,current.extraRemaining),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(current.guard!=StudyGuard.READY && current.guard!=StudyGuard.NOT_CONFIGURED) {
            Text(stringResource(when(current.guard){StudyGuard.CLOCK_ROLLBACK->R.string.clock_rollback;StudyGuard.WAIT_FOR_DATE->R.string.wait_date;else->R.string.midnight_pending}))
        }
    }
}

/** Existing detail rendering stays available until its own UI3 stage. */
@Composable
internal fun Meanings(word:Detail,learning:String) {
    word.senses.forEach {sense->
        Text(sense.translations[learning].orEmpty(),style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("meaning_${sense.id}"))
        sense.examples.forEach {example->Text(example.source);Text(example.translations[learning].orEmpty(),color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

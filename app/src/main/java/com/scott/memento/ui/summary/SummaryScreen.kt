package com.scott.memento.ui.summary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.scott.memento.R
import com.scott.memento.domain.Languages
import com.scott.memento.domain.PlanSummary
import com.scott.memento.domain.Rating
import com.scott.memento.domain.RatingDistribution
import com.scott.memento.domain.SummaryQueueCounts

/** Historical summaries never own a live scoring queue. Root explicitly grants resume capability. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SummaryScreen(
    summary:PlanSummary,focusLabels:Map<String,String?>,busy:Boolean,
    onDetail:(String)->Unit,onHome:()->Unit,onResume:()->Unit,
    modifier:Modifier=Modifier,canResume:Boolean=false,
) {
    val title=when {
        summary.planStatus=="CLOSED_INCOMPLETE" -> R.string.summary_incomplete_title
        summary.isExtraInProgress -> R.string.summary_extra_active_title
        summary.isAwaitingExtraChoice -> R.string.summary_extra_choice_title
        summary.isCompleted -> R.string.summary_complete_title
        summary.ratingEvents==0 -> R.string.summary_empty_title
        else -> R.string.summary_active_title
    }
    Column(modifier.fillMaxSize().testTag("summary_screen").verticalScroll(rememberScrollState()),
        verticalArrangement=Arrangement.spacedBy(14.dp)) {
        OutlinedCard(Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(22.dp),horizontalAlignment=Alignment.CenterHorizontally,
                verticalArrangement=Arrangement.spacedBy(9.dp)) {
                Text(stringResource(title),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,
                    modifier=Modifier.testTag(if(summary.isCompleted) "study_finished" else "summary_status"))
                Text(stringResource(R.string.summary_plan_date,summary.businessDate,summary.zone,Languages.label(summary.target)),
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.summary_word_total,summary.uniqueWords),style=MaterialTheme.typography.titleLarge,
                    modifier=Modifier.testTag("summary_count"))
                FlowRow(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.summary_new_count,summary.newWords),modifier=Modifier.testTag("summary_new_count"))
                    Text(stringResource(R.string.summary_review_count,summary.reviewWords),modifier=Modifier.testTag("summary_review_count"))
                }
                Text(stringResource(R.string.summary_events,summary.ratingEvents),style=MaterialTheme.typography.bodySmall)
            }
        }
        QueueSummary(stringResource(R.string.summary_normal),summary.normal,"normal")
        if(summary.extraDecision!=null || summary.extraGraded>0) {
            QueueSummary(stringResource(R.string.summary_extra),summary.extra,"extra")
            when {
                summary.extraDecision=="DECLINED" -> Text(stringResource(R.string.summary_extra_declined),
                    style=MaterialTheme.typography.bodySmall)
                summary.isAwaitingExtraChoice -> Text(stringResource(R.string.summary_extra_offered,summary.extra.offered),
                    style=MaterialTheme.typography.bodySmall)
            }
        }
        Text(stringResource(R.string.summary_rating_title),style=MaterialTheme.typography.titleMedium)
        val total=RatingDistribution(
            summary.normal.ratings.again+summary.extra.ratings.again,
            summary.normal.ratings.hard+summary.extra.ratings.hard,
            summary.normal.ratings.good+summary.extra.ratings.good,
            summary.normal.ratings.easy+summary.extra.ratings.easy,
        )
        Rating.entries.forEach { rating ->
            val label=when(rating) {
                Rating.AGAIN->R.string.rating_again
                Rating.HARD->R.string.rating_hard
                Rating.GOOD->R.string.rating_good
                Rating.EASY->R.string.rating_easy
            }
            val count=when(rating) {
                Rating.AGAIN->total.again
                Rating.HARD->total.hard
                Rating.GOOD->total.good
                Rating.EASY->total.easy
            }
            Row(Modifier.fillMaxWidth().padding(vertical=3.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(stringResource(label))
                Text(count.toString(),fontWeight=FontWeight.Bold,modifier=Modifier.testTag("summary_rating_${rating.name}"))
            }
        }
        HorizontalDivider()
        Text(stringResource(R.string.summary_focus_title),style=MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.summary_focus_hint),style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(summary.focusUids.isEmpty()) Text(stringResource(R.string.summary_focus_empty))
        summary.focusUids.forEach { uid ->
            val lemma=focusLabels[uid]
            OutlinedButton(onClick={onDetail(uid)},enabled=!busy && !lemma.isNullOrBlank(),
                modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("summary_focus_$uid")) {
                Text(lemma?.takeIf(String::isNotBlank) ?: stringResource(R.string.summary_focus_missing,uid))
            }
        }
        if(summary.legacy.ratingEvents>0) {
            HorizontalDivider()
            Text(stringResource(R.string.summary_legacy_title),style=MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.summary_legacy_counts,summary.legacy.uniqueWords,summary.legacy.newWords,
                summary.legacy.reviewWords,summary.legacy.ratingEvents),modifier=Modifier.testTag("summary_legacy"))
            Text(stringResource(R.string.summary_legacy_hint),style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(summary.ratingEvents>0) Text(stringResource(R.string.summary_saved_hint),style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(canResume) Button(onClick=onResume,enabled=!busy,
            modifier=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("summary_resume")) {
            Text(stringResource(R.string.summary_resume))
        }
        Button(onClick=onHome,enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("summary_home")) {
            Text(stringResource(R.string.summary_home))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable private fun QueueSummary(title:String,queue:SummaryQueueCounts,kind:String) {
    OutlinedCard(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            Text(stringResource(R.string.summary_queue_count,queue.graded,queue.pending),
                modifier=Modifier.testTag("summary_${kind}_count"))
            Text(stringResource(R.string.summary_queue_distribution,queue.ratings.again,queue.ratings.hard,
                queue.ratings.good,queue.ratings.easy),style=MaterialTheme.typography.bodySmall)
        }
    }
}

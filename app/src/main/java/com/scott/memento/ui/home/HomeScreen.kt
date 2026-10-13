package com.scott.memento.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scott.memento.R
import com.scott.memento.domain.StudyGuard
import com.scott.memento.domain.StudyMode
import com.scott.memento.ui.AppState

/** The homepage displays the published plan; navigation never allocates another daily quota. */
@Composable
fun HomeScreen(
    state: AppState,
    onStudy: () -> Unit,
    onSettings: () -> Unit,
    onBook: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRecentSummary: () -> Unit = {},
) {
    val study = state.study ?: return
    val progress = state.home
    val overview = state.overview
    val book = state.books.firstOrNull { it.id == (study.bookId ?: state.language?.book) }
    val reviewAll = study.policy?.mode == StudyMode.REVIEW_ALL
    val normalRemaining = progress?.normalRemaining ?: study.normalRemaining
    val normalCompleted = progress?.ordinaryCompleted ?: 0
    val normalTotal = progress?.ordinaryTotal ?: normalRemaining
    val acceptedExtra = study.extraDecision == "ACCEPTED"
    val continuingExtra = normalRemaining == 0 && acceptedExtra && study.extraRemaining > 0
    val remaining = if (continuingExtra) study.extraRemaining else normalRemaining
    val canStudy = study.guard == StudyGuard.READY && study.next != null
    val buttonLabel = when {
        study.guard == StudyGuard.NOT_CONFIGURED -> R.string.configure_plan
        study.guard != StudyGuard.READY -> R.string.home_study_unavailable
        continuingExtra && canStudy -> R.string.home_continue_extra
        canStudy && (normalCompleted > 0 || study.next?.revealed == true) -> R.string.home_continue
        canStudy -> R.string.home_start
        reviewAll && normalTotal == 0 -> R.string.home_review_all_empty
        reviewAll && normalRemaining == 0 -> R.string.home_review_all_completed
        normalRemaining == 0 -> R.string.home_normal_completed
        else -> R.string.home_study_unavailable
    }

    Column(
        modifier.fillMaxSize().testTag("home_screen")
            .verticalScroll(rememberScrollState()).padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    if (study.businessDate != null && study.zone != null) {
                        stringResource(R.string.plan_date, study.businessDate, study.zone)
                    } else stringResource(R.string.home_date_unconfigured),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if(overview?.deferredUntil!=null && study.businessDate!=null) Text(
                    stringResource(R.string.ui6_deferred_hint,study.businessDate),style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.primary,modifier=Modifier.testTag("home_grace"),
                )
                HomeTaskHeading(book?.title ?: stringResource(R.string.home_no_book),remaining,when {
                    continuingExtra -> R.string.home_extra_remaining_label
                    reviewAll -> R.string.home_review_remaining_label
                    else -> R.string.home_remaining_label
                })
                LinearProgressIndicator(
                    progress = { progress?.fraction ?: 0f },
                    modifier = Modifier.fillMaxWidth().height(6.dp).testTag("home_progress"),
                )
                Text(
                    stringResource(R.string.home_progress_count, normalCompleted, normalTotal),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HomeDailyMetrics(
                    reviewRemaining = progress?.normalReviewRemaining ?: 0,
                    newRemaining = progress?.normalNewRemaining ?: 0,
                    done = study.stats.uniqueWords,
                )
                if (acceptedExtra && (study.extraRemaining > 0 || (progress?.extraGraded ?: 0) > 0)) {
                    Text(
                        stringResource(R.string.home_extra_progress, progress?.extraGraded ?: 0, study.extraRemaining),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (study.extraDecision == "PENDING" && normalRemaining > 0 && study.extraRemaining > 0) {
                    Text(stringResource(R.string.home_overflow_pending, study.extraRemaining), style = MaterialTheme.typography.bodySmall)
                } else if (normalRemaining == 0 && !reviewAll) {
                    val backlog = if (study.extraDecision == "DECLINED") overview?.due ?: 0 else study.extraRemaining
                    if (backlog > 0) Text(
                        stringResource(R.string.home_review_backlog, backlog),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                when (study.guard) {
                    StudyGuard.CLOCK_ROLLBACK -> Text(stringResource(R.string.clock_rollback), color = MaterialTheme.colorScheme.error)
                    StudyGuard.WAIT_FOR_DATE -> Text(stringResource(R.string.wait_date), style = MaterialTheme.typography.bodySmall)
                    StudyGuard.MIDNIGHT_CHOICE -> Text(stringResource(R.string.midnight_pending), style = MaterialTheme.typography.bodySmall)
                    else -> Unit
                }
                Button(
                    onClick = if (study.guard == StudyGuard.NOT_CONFIGURED) onSettings else onStudy,
                    enabled = !state.busy && (canStudy || study.guard == StudyGuard.NOT_CONFIGURED),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("open_study"),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(stringResource(buttonLabel)) }
                TextButton(
                    onClick = onSettings,
                    enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("study_configure"),
                ) { Text(stringResource(R.string.adjust_plan)) }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(stringResource(R.string.home_book_section), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.book_settings_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (book != null) {
            val members = state.index?.books?.get(book.id)
            val total = members?.size ?: book.count
            val learned = members.orEmpty().count { it in overview?.learned.orEmpty() }
            val percentage = if (total > 0) (learned.toLong() * 100 / total).toInt() else 0
            Column(
                Modifier.fillMaxWidth().testTag("current_book_progress")
                    .heightIn(min = 48.dp).clickable(enabled = !state.busy,role = Role.Button) { onBook(book.id) }.padding(vertical = 12.dp),
            ) {
                HomeBookSummary(book.title,percentage,learned,total)
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
            }
        } else {
            Text(stringResource(R.string.home_no_book), style = MaterialTheme.typography.bodyMedium)
        }

        Text(stringResource(R.string.home_status_title), style = MaterialTheme.typography.titleMedium)
        OutlinedCard(
            modifier = Modifier.fillMaxWidth().testTag("home_status"),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeStatusMetrics(overview?.streak ?: 0,overview?.learned?.size ?: study.stats.totalLearned)
                if (reviewAll) Text(stringResource(R.string.review_all), style = MaterialTheme.typography.bodySmall)
                else study.policy?.dailyTarget?.let { target ->
                    Text(stringResource(R.string.total_goal, target), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.due_count, overview?.due ?: 0), style = MaterialTheme.typography.bodySmall)
            }
        }
        if(state.recentSummary!=null) TextButton(onClick=onRecentSummary,enabled=!state.busy,
            modifier=Modifier.heightIn(min=48.dp).testTag("home_recent_summary")) {Text(stringResource(R.string.ui2_recent_summary))}
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun HomeTaskHeading(bookTitle:String,remaining:Int,remainingLabel:Int) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked=maxWidth<260.dp || LocalDensity.current.fontScale>1.3f
        val heading:@Composable (Modifier)->Unit={modifier->
            Column(modifier) {
                Text(stringResource(R.string.study_today),style=MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(5.dp))
                Text(bookTitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier=Modifier.testTag("home_book_title"))
            }
        }
        val quantity:@Composable ()->Unit={
            Column(horizontalAlignment=if(stacked) Alignment.Start else Alignment.End) {
                Text(remaining.toString(),fontSize=34.sp,fontWeight=FontWeight.Bold,modifier=Modifier.testTag("home_remaining"))
                Text(stringResource(remainingLabel),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(stacked) Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {heading(Modifier.fillMaxWidth());quantity()}
        else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {heading(Modifier.weight(1f).padding(end=12.dp));quantity()}
    }
}

@Composable
private fun HomeBookSummary(title:String,percentage:Int,learned:Int,total:Int) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked=maxWidth<280.dp || LocalDensity.current.fontScale>1.3f
        val badge:@Composable ()->Unit={Surface(shape=RoundedCornerShape(12.dp)) {
            Text(stringResource(R.string.home_book_percent,percentage),Modifier.padding(10.dp),style=MaterialTheme.typography.labelMedium)
        }}
        val bookText:@Composable (Modifier)->Unit={modifier->Column(modifier) {
            Text(title,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.home_book_learned,learned,total),style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
        if(stacked) Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                badge();Text("›",fontSize=26.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            bookText(Modifier.fillMaxWidth())
        } else Row(verticalAlignment=Alignment.CenterVertically) {
            badge();bookText(Modifier.weight(1f).padding(horizontal=12.dp))
            Text("›",fontSize=26.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HomeStatusMetrics(streak:Int,totalLearned:Int) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked=maxWidth<240.dp || LocalDensity.current.fontScale>1.3f
        val streakMetric:@Composable (Modifier)->Unit={modifier->Column(modifier) {
            Text(stringResource(R.string.home_streak_label),style=MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.home_streak_days,streak),style=MaterialTheme.typography.titleLarge)
        }}
        val totalMetric:@Composable (Modifier)->Unit={modifier->Column(modifier,horizontalAlignment=if(stacked) Alignment.Start else Alignment.End) {
            Text(stringResource(R.string.home_total_learned_label),style=MaterialTheme.typography.labelMedium)
            Text(totalLearned.toString(),style=MaterialTheme.typography.titleLarge)
        }}
        if(stacked) Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {streakMetric(Modifier.fillMaxWidth());totalMetric(Modifier.fillMaxWidth())}
        else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(18.dp)) {streakMetric(Modifier.weight(1f));totalMetric(Modifier.weight(1f))}
    }
}

@Composable
private fun HomeDailyMetrics(reviewRemaining: Int, newRemaining: Int, done: Int) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 220.dp && fontScale <= 1.3f) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeMetric(reviewRemaining, R.string.home_review_remaining_label, "home_review_remaining", Modifier.weight(1f))
                HomeMetric(newRemaining, R.string.home_new_remaining_label, "home_new_remaining", Modifier.weight(1f))
                HomeMetric(done, R.string.home_today_done_label, "home_today_done", Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeMetric(reviewRemaining, R.string.home_review_remaining_label, "home_review_remaining")
                HomeMetric(newRemaining, R.string.home_new_remaining_label, "home_new_remaining")
                HomeMetric(done, R.string.home_today_done_label, "home_today_done")
            }
        }
    }
}

@Composable
private fun HomeMetric(value: Int, label: Int, tag: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value.toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(tag))
        Text(stringResource(label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

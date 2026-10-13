package com.scott.memento.ui.statistics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.scott.memento.R
import com.scott.memento.domain.HistoryDay
import com.scott.memento.domain.LearningOverview

/** Layout only: reads the controller's business-date projection without creating or revising plans. */
@Composable
fun StatisticsScreen(overview:LearningOverview,modifier:Modifier=Modifier) {
    var expanded by rememberSaveable {mutableStateOf(false)}
    val windowSize=if(expanded) 30 else 7
    val window=StatisticsWindow(overview.days.take(windowSize))
    val today=overview.days.firstOrNull {it.date==overview.businessDate}
    LazyColumn(modifier.testTag("statistics"),verticalArrangement=Arrangement.spacedBy(16.dp),
        contentPadding=PaddingValues(bottom=12.dp)) {
        item(key="metrics") {
            Text(stringResource(R.string.statistics_date_zone,overview.businessDate,overview.zone),
                style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("statistics_date"))
            if(overview.deferredUntil!=null) Text(stringResource(R.string.statistics_grace,overview.businessDate),
                style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("statistics_grace"))
            Text(stringResource(R.string.stats_basis),style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Metrics(today?.words ?: 0,overview.streak,overview.availableLearnedCount)
        }
        item(key="window") {
            Text(stringResource(R.string.statistics_recent,windowSize),style=MaterialTheme.typography.titleLarge,
                modifier=Modifier.testTag("statistics_window_title").semantics {heading()})
            Text(stringResource(R.string.statistics_bar_basis),style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick={expanded=!expanded},modifier=Modifier.heightIn(min=48.dp).testTag("statistics_window_toggle")) {
                Text(stringResource(if(expanded) R.string.statistics_collapse else R.string.statistics_expand))
            }
            if(window.days.all {it.ratings==0}) Text(stringResource(R.string.statistics_empty),
                modifier=Modifier.testTag("statistics_empty"))
        }
        items(window.days,key={it.date}) {day->HistoryRow(day,window)}
        item(key="totals") {
            Text(stringResource(R.string.statistics_window_total,windowSize,window.dailyWords,window.ratings),
                style=MaterialTheme.typography.titleSmall,modifier=Modifier.testTag("statistics_window_total"))
            Text(stringResource(R.string.statistics_window_basis),style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item(key="coverage") {
            OutlinedCard(Modifier.fillMaxWidth().testTag("statistics_coverage")) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.statistics_coverage_title),style=MaterialTheme.typography.titleMedium,
                        modifier=Modifier.semantics {heading()})
                    Text(stringResource(R.string.learned_total,overview.availableLearnedCount))
                    Text(stringResource(R.string.statistics_coverage,overview.availableLearnedCount,overview.availableCount),
                        modifier=Modifier.testTag("statistics_coverage_count"))
                    LinearProgressIndicator(progress={contentCoverage(overview.availableLearnedCount,overview.availableCount)},
                        modifier=Modifier.fillMaxWidth())
                    if(overview.unavailableLearnedCount>0) Text(
                        stringResource(R.string.statistics_unavailable,overview.unavailableLearnedCount),
                        style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("statistics_unavailable"))
                    StatisticDetail(R.string.statistics_due,overview.due,"statistics_due")
                    StatisticDetail(R.string.statistics_new,today?.newWords ?: 0,"statistics_new")
                    StatisticDetail(R.string.statistics_favorites,overview.favorites.size,"statistics_favorites")
                    Text(stringResource(R.string.statistics_favorites_basis),style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable private fun Metrics(today:Int,streak:Int,learned:Int) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if(maxWidth>=300.dp && LocalDensity.current.fontScale<=1.2f) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Metric(today,R.string.statistics_today_words,"statistics_today",Modifier.weight(1f))
                Metric(streak,R.string.statistics_streak,"statistics_streak",Modifier.weight(1f))
                Metric(learned,R.string.statistics_learned,"statistics_learned",Modifier.weight(1f))
            }
        } else Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Metric(today,R.string.statistics_today_words,"statistics_today",Modifier.fillMaxWidth())
            Metric(streak,R.string.statistics_streak,"statistics_streak",Modifier.fillMaxWidth())
            Metric(learned,R.string.statistics_learned,"statistics_learned",Modifier.fillMaxWidth())
        }
    }
}

@Composable private fun Metric(number:Int,label:Int,tag:String,modifier:Modifier) {
    Card(modifier.testTag(tag)) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(number.toString(),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,
                modifier=Modifier.testTag("${tag}_value"))
            Text(stringResource(label),style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun HistoryRow(day:HistoryDay,window:StatisticsWindow) {
    val description=stringResource(R.string.statistics_bar_description,day.date,day.words,window.peak)
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.history_count,day.date,day.words,day.newWords,day.reviews),
            modifier=Modifier.testTag("history_${day.date}"),style=MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress={window.fraction(day)},modifier=Modifier.fillMaxWidth().height(8.dp)
            .testTag("history_bar_${day.date}").clearAndSetSemantics {contentDescription=description})
        Text(stringResource(R.string.history_ratings,day.ratings,day.extraRatings),
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.statistics_kinds,day.normalRatings,day.extraRatings,day.legacyRatings),
            modifier=Modifier.testTag("history_kinds_${day.date}"),style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun StatisticDetail(label:Int,number:Int,tag:String) {
    // FlowRow permits the value to move to its own line at large font sizes.
    FlowRow(Modifier.fillMaxWidth().testTag(tag),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(stringResource(label),modifier=Modifier.weight(1f))
        Text(number.toString(),fontWeight=FontWeight.SemiBold,modifier=Modifier.testTag("${tag}_value"))
    }
}

package com.scott.frenchvocab.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scott.frenchvocab.domain.AppSnapshot
import com.scott.frenchvocab.domain.VocabularyBook
import com.scott.frenchvocab.feature.SectionTitle
import com.scott.frenchvocab.ui.theme.AccentDark
import com.scott.frenchvocab.ui.theme.AccentSoft
import com.scott.frenchvocab.ui.theme.Line
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(snapshot: AppSnapshot, books: List<VocabularyBook>, busy: Boolean, onStart: () -> Unit, onBook: (String) -> Unit, onSummary: () -> Unit) {
    val stats = snapshot.stats
    val currentBook = books.find { it.id == snapshot.settings.bookId }
    val active = snapshot.session?.takeUnless { it.completed }
    val planned = stats.dueCount + stats.newCount
    val remaining = active?.let { it.items.size - it.position } ?: planned
    val progress = active?.let { it.position.toFloat() / it.items.size.coerceAtLeast(1) }
        ?: (stats.todayReviews.toFloat() / (stats.todayReviews + planned).coerceAtLeast(1))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedCard(shape = RoundedCornerShape(22.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Bonjour · ${LocalDate.now().format(DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH))}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Aujourd’hui", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(5.dp))
                        Text(currentBook?.title ?: "尚未选择词书", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("$remaining", color = AccentDark, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("today_remaining"))
                }
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("到期 ${stats.dueCount}", style = MaterialTheme.typography.bodySmall)
                    Text("可学新词 ${stats.newCount}", style = MaterialTheme.typography.bodySmall)
                    Text("今日完成 ${stats.todayReviews}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("today_completed"))
                }
                if (active != null) Text("本轮已完成 ${active.position} / ${active.items.size}，进度已保存", color = AccentDark, style = MaterialTheme.typography.bodySmall)
                Button(onClick = onStart, enabled = !busy && remaining > 0, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("start_study"), shape = RoundedCornerShape(14.dp)) {
                    Text(if (active != null) "继续学习" else if (planned > 0) "开始学习" else "今日任务已完成")
                }
                if (remaining == 0) Text("À demain ! 可以先浏览词库，或在设置中调整新词额度。", style = MaterialTheme.typography.bodySmall)
            }
        }
        SectionTitle("当前词书进度", "可在设置页切换词书")
        currentBook?.let { book ->
            val learned = book.lexemeUids.count { (snapshot.cards[it]?.repetitions ?: 0) > 0 }
            val total = book.lexemeUids.size
            val percent = if (total == 0) 0 else learned * 100 / total
            Column(Modifier.fillMaxWidth().testTag("current_book_progress").clickable { onBook(book.id) }.padding(vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(12.dp), color = AccentSoft) { Text("$percent%", Modifier.padding(10.dp), color = AccentDark, style = MaterialTheme.typography.labelMedium) }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(book.title, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("$learned / $total 已学习", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Line)
            }
        }
        SectionTitle("今日状态")
        Row(Modifier.fillMaxWidth().background(AccentSoft, RoundedCornerShape(16.dp)).padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("连续学习 ${stats.streakDays} 天", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("今日新学 ${stats.todayNew} / ${snapshot.settings.dailyNewLimit}", style = MaterialTheme.typography.bodySmall)
            }
            Text("${snapshot.cards.values.count { it.repetitions > 0 }}\n累计词条", color = AccentDark, style = MaterialTheme.typography.titleSmall)
        }
        if (snapshot.latestCompletedSession != null) OutlinedButton(onClick = onSummary, modifier = Modifier.fillMaxWidth().testTag("open_last_summary")) { Text("查看最近一轮总结") }
        Text("离线法语 · 开发词库\n目前提供 ${books.flatMap { it.lexemeUids }.distinct().size} 个审校词条；完整教材词库仍在整理。", Modifier.padding(top = 8.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

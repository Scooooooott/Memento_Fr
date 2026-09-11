package com.scott.frenchvocab.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scott.frenchvocab.domain.*
import com.scott.frenchvocab.ui.theme.*

@Composable
fun SectionTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (subtitle != null) Text(subtitle, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyState(title: String, description: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StudyScreen(word: Lexeme, session: StudySession, favorite: Boolean, busy: Boolean, onPlay: () -> Unit, onFavorite: () -> Unit, onDetail: () -> Unit, onReveal: () -> Unit) {
    val revealed = session.answerRevealed
    val revealLatest by rememberUpdatedState(onReveal)
    val revealArea = if (revealed) Modifier else Modifier
        .testTag("reveal_answer")
        .semantics {
            contentDescription = "点击空白处揭示答案"
            if (!busy) onClick(label = "揭示答案") { revealLatest(); true }
        }
        .pointerInput(busy) {
            if (!busy) detectTapGestures(onTap = { revealLatest() })
        }
    val metadata = listOf(word.partOfSpeech, word.gender, word.verbGroup).filter(String::isNotBlank).joinToString(" · ")
    Column(Modifier.fillMaxSize().then(revealArea).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = if (revealed) 12.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(if (revealed) 10.dp else 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${if (session.current?.isNew == true) "新词" else "复习"} · ${word.level}", color = AccentDark, style = MaterialTheme.typography.labelLarge)
            Text("${session.position + 1} / ${session.items.size}", modifier = Modifier.testTag("study_position"), style = MaterialTheme.typography.labelLarge)
        }
        LinearProgressIndicator(progress = { session.position.toFloat() / session.items.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = AccentSoft)
        // A card tap is consumed locally. Child buttons retain their own gestures;
        // verticalScroll consumes dragging and cancels tap detectors at both levels.
        val cardTaps = if (revealed) Modifier else Modifier.pointerInput(Unit) { detectTapGestures(onTap = {}) }
        OutlinedCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().testTag("study_card").then(cardTaps), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            if (revealed) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(word.lemma, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("study_lemma"))
                            if (session.settings.showIpa && word.ipa.isNotBlank()) Text(word.ipa, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("word_ipa"))
                        }
                        TextButton(onClick = onPlay, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("play_audio").semantics { contentDescription = "播放法语发音" }) { Text("发音") }
                        IconButton(onClick = onFavorite, enabled = !busy, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("toggle_favorite").semantics { contentDescription = if (favorite) "取消收藏" else "收藏词条" }) { Text(if (favorite) "★" else "☆", color = AccentDark, fontSize = 22.sp) }
                    }
                    Text(metadata, style = MaterialTheme.typography.bodySmall, color = AccentDark, modifier = Modifier.testTag("study_metadata"))
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(word.lemma, fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("study_lemma"))
                    if (session.settings.showIpa && word.ipa.isNotBlank()) Text(word.ipa, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("word_ipa"))
                    Text(metadata, style = MaterialTheme.typography.bodySmall, color = AccentDark, modifier = Modifier.testTag("study_metadata"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPlay, modifier = Modifier.testTag("play_audio")) { Text("播放发音") }
                        TextButton(onClick = onFavorite, enabled = !busy, modifier = Modifier.testTag("toggle_favorite")) { Text(if (favorite) "★ 已收藏" else "☆ 收藏") }
                    }
                }
            }
        }
        if (revealed) {
            Column(Modifier.testTag("study_answer"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                word.senses.take(2).forEachIndexed { index, sense -> SenseBlock(index + 1, sense, session.settings, compact = true) }
                if (word.senses.isEmpty()) Text("所选语言的释义暂未收录", modifier = Modifier.testTag("sense_missing"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                word.examples.firstOrNull()?.let { ExampleBlock(it, session.settings, compact = true) }
                if (word.forms.isNotEmpty()) {
                    Column {
                        Text("关键形式", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
                        word.forms.take(4).forEach { FormRow(it.label, it.value, compact = true) }
                    }
                }
                TextButton(onClick = onDetail, modifier = Modifier.align(Alignment.End).testTag("open_word_detail")) { Text("全部释义与变位 ›") }
            }
        } else {
            Text("点击空白处揭示答案", Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp).testTag("reveal_hint"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.fillMaxWidth().height(96.dp).testTag("reveal_blank_space"))
        }
    }
}

@Composable
fun StudyActions(busy: Boolean, onRate: (Rating) -> Unit) {
    Surface(shadowElevation = 5.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
            val singleRow = maxWidth >= 320.dp && LocalDensity.current.fontScale <= 1.2f
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("根据这次回忆评分", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Rating.entries.chunked(if (singleRow) 4 else 2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { rating ->
                            val color = when (rating) { Rating.AGAIN -> Danger; Rating.HARD -> Warning; Rating.GOOD -> AccentDark; Rating.EASY -> TextPrimary }
                            OutlinedButton(onClick = { onRate(rating) }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("rate_${rating.name}"), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = color)) { Text(rating.label(), fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
}

fun Rating.label(): String = when (this) { Rating.AGAIN -> "忘记"; Rating.HARD -> "模糊"; Rating.GOOD -> "记得"; Rating.EASY -> "很熟" }

@Composable
private fun SenseBlock(number: Int, sense: Sense, settings: UserSettings, compact: Boolean = false) {
    val textStyle = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
    Column(Modifier.fillMaxWidth().background(AccentSoft, RoundedCornerShape(14.dp)).padding(if (compact) 12.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 7.dp)) {
        val translations = selectedTranslations(settings, sense.chinese, sense.english, sense.spanish)
        translations.forEach { translation ->
            Text("$number · ${translation.label}  ${translation.text}", modifier = Modifier.testTag("sense_${translation.tag}"), style = textStyle)
        }
        if (translations.isEmpty()) Text("所选语言的释义暂未收录", modifier = Modifier.testTag("sense_missing"), style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExampleBlock(example: Example, settings: UserSettings, compact: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = if (compact) 2.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
        Text(example.french, fontWeight = FontWeight.SemiBold, style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge)
        val translations = selectedTranslations(settings, example.chinese, example.english, example.spanish)
        translations.forEach { translation ->
            Text("${translation.label}  ${translation.text}", modifier = Modifier.testTag("example_${translation.tag}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (translations.isEmpty()) Text("所选语言的译文暂未收录", modifier = Modifier.testTag("example_missing"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FormRow(label: String, value: String, compact: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = if (compact) 3.dp else 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun WordDetailScreen(word: Lexeme, settings: UserSettings, favorite: Boolean, busy: Boolean, onFavorite: () -> Unit, onPlay: () -> Unit) {
    var tab by rememberSaveable(word.uid) { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().testTag("word_detail")) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(word.lemma, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            if (settings.showIpa) Text(word.ipa, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(listOf(word.partOfSpeech, word.gender, word.verbGroup, word.level).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = AccentDark)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onPlay, modifier = Modifier.testTag("play_audio")) { Text("播放发音") }
                TextButton(onClick = onFavorite, enabled = !busy, modifier = Modifier.testTag("toggle_favorite")) { Text(if (favorite) "★ 已收藏" else "☆ 收藏") }
            }
        }
        TabRow(selectedTabIndex = tab) {
            listOf("释义与例句", "关键形式", "动词变位").forEachIndexed { index, text ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(text, style = MaterialTheme.typography.labelMedium) }, modifier = Modifier.testTag("detail_tab_$index"))
            }
        }
        key(word.uid, tab) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (tab) {
                    0 -> {
                        word.senses.forEachIndexed { index, sense -> SenseBlock(index + 1, sense, settings) }
                        if (word.senses.isEmpty()) Text("所选语言的释义暂未收录", modifier = Modifier.testTag("sense_missing"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SectionTitle("例句 · Exemples")
                        if (word.examples.isEmpty()) Text("此词条尚未收录例句。")
                        word.examples.forEach { ExampleBlock(it, settings) }
                    }
                    1 -> {
                        if (word.auxiliary.isNotBlank()) FormRow("助动词", word.auxiliary)
                        word.forms.forEach { FormRow(it.label, it.value); HorizontalDivider(color = Line) }
                        if (word.forms.isEmpty()) Text("此词条没有已收录的关键形式。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    2 -> {
                        if (word.conjugations.isEmpty()) Text("此词条没有已收录的动词变位。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        word.conjugations.groupBy { it.tense }.forEach { (tense, entries) ->
                            SectionTitle(tense)
                            entries.forEach { entry ->
                                val phrase = entry.pronoun + (if (entry.pronoun.endsWith("’") || entry.pronoun.endsWith("'")) "" else " ") + entry.form
                                Text(phrase, Modifier.fillMaxWidth().padding(vertical = 7.dp))
                                HorizontalDivider(color = Line)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SummaryScreen(session: StudySession?, words: List<Lexeme>, onHome: () -> Unit, onDetail: (Lexeme) -> Unit) {
    if (session == null) { EmptyState("还没有完成的学习记录", "完成一轮学习后，在这里查看总结。"); return }
    val scored = session.items.filter { it.rating != null }
    val focus = scored.filter { it.rating == Rating.AGAIN || it.rating == Rating.HARD }
    Column(Modifier.fillMaxSize().testTag("session_summary").verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.fillMaxWidth().background(AccentSoft, RoundedCornerShape(22.dp)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Bien joué !", color = AccentDark, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("本轮完成 ${scored.size} 个词条", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("summary_count"))
            Spacer(Modifier.height(6.dp))
            Text("新词 ${scored.count { it.isNew }} · 复习 ${scored.count { !it.isNew }}", style = MaterialTheme.typography.bodyMedium)
        }
        Rating.entries.forEach { rating ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(rating.label()); Text("${scored.count { it.rating == rating }}", fontWeight = FontWeight.Bold) }
        }
        HorizontalDivider(color = Line)
        SectionTitle("重点复习", "本轮标记为「忘记」或「模糊」的词条")
        if (focus.isEmpty()) Text("本轮没有重点复习词。继续保持！", color = AccentDark)
        focus.forEach { item -> words.find { it.uid == item.lexemeUid }?.let { word ->
            OutlinedButton(onClick = { onDetail(word) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(word.lemma); Text(item.rating!!.label()) }
            }
        } }
        Text("评分已保存，下次复习时间已更新。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onHome, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("nav_home")) { Text("返回首页") }
    }
}

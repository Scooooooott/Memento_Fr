package com.scott.frenchvocab.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.scott.frenchvocab.domain.*
import com.scott.frenchvocab.ui.theme.*
import java.text.Normalizer

private fun String.searchKey(): String = Normalizer.normalize(lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

@Composable
fun BrowseScreen(words: List<Lexeme>, books: List<VocabularyBook>, snapshot: AppSnapshot, selectedBook: String, onBook: (String) -> Unit, onDetail: (Lexeme) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    val members = books.find { it.id == selectedBook }?.lexemeUids?.toSet()
    val filtered = remember(query, words, members, favoritesOnly, snapshot.favorites) {
        val search = query.trim().searchKey()
        words.filter { word ->
            (members == null || word.uid in members) && (!favoritesOnly || word.uid in snapshot.favorites) &&
                (search.isEmpty() || word.lemma.searchKey().contains(search) || word.senses.any { it.chinese.searchKey().contains(search) || it.english.searchKey().contains(search) || it.spanish.searchKey().contains(search) })
        }.sortedBy { it.lemma.searchKey() }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).testTag("word_search"), label = { Text("搜索法语 / 中文 / EN / ES") }, singleLine = true, trailingIcon = {
            if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清除") }
        }, shape = RoundedCornerShape(14.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selectedBook.isBlank(), { onBook("") }, label = { Text("全部词条") })
            books.forEach { book -> FilterChip(selectedBook == book.id, { onBook(book.id) }, label = { Text(book.title) }) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${filtered.size} 个词条", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilterChip(favoritesOnly, { favoritesOnly = !favoritesOnly }, label = { Text("只看收藏") }, modifier = Modifier.testTag("favorites_filter"))
        }
        if (filtered.isEmpty()) EmptyState("没有符合条件的词条", "试试其他拼写，或调整词书与收藏筛选。")
        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            items(filtered, key = { it.uid }) { word ->
                Column(Modifier.fillMaxWidth().clickable { onDetail(word) }.testTag("word_${word.uid}").padding(vertical = 14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(word.lemma, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        if (word.uid in snapshot.favorites) Text("★", color = AccentDark, modifier = Modifier.padding(end = 8.dp))
                        Text(if ((snapshot.cards[word.uid]?.repetitions ?: 0) > 0) "已学习" else "未学习", color = AccentDark, style = MaterialTheme.typography.labelSmall)
                    }
                    Text("${word.partOfSpeech} · ${word.level}", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val preview = selectedTranslations(
                        settings = snapshot.settings,
                        chinese = word.senses.firstOrNull { it.chinese.isNotBlank() }?.chinese.orEmpty(),
                        english = word.senses.firstOrNull { it.english.isNotBlank() }?.english.orEmpty(),
                        spanish = word.senses.firstOrNull { it.spanish.isNotBlank() }?.spanish.orEmpty(),
                    ).firstOrNull()
                    Text(preview?.let { "${it.label}  ${it.text}" } ?: "所选语言的释义暂未收录", Modifier.padding(top = 6.dp).testTag("browse_meaning_${word.uid}"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = Line)
            }
        }
    }
}

@Composable
fun SettingsScreen(snapshot: AppSnapshot, books: List<VocabularyBook>, busy: Boolean, activeSession: Boolean, onChooseBook: () -> Unit, onSave: (UserSettings) -> Unit) {
    val settings = snapshot.settings
    val currentBook = books.find { it.id == settings.bookId }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var limit by rememberSaveable { mutableStateOf(settings.dailyNewLimit.toString()) }
    var autoPlay by rememberSaveable { mutableStateOf(settings.autoPlay.name) }
    var tts by rememberSaveable { mutableStateOf(settings.ttsFallback) }
    var ipa by rememberSaveable { mutableStateOf(settings.showIpa) }
    var chinese by rememberSaveable { mutableStateOf(settings.showChinese) }
    var english by rememberSaveable { mutableStateOf(settings.showEnglish) }
    var spanish by rememberSaveable { mutableStateOf(settings.showSpanish) }
    val count = limit.toIntOrNull()
    val digitsOnly = limit.isNotEmpty() && limit.all { it in '0'..'9' }
    val countError = when {
        !digitsOnly -> "请输入非负整数。"
        count == null -> "数值过大，请输入较小的数量。"
        else -> null
    }
    val valid = countError == null && count != null && (chinese || english || spanish) && currentBook != null
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp).testTag("settings_screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Français · fr-FR", style = MaterialTheme.typography.titleLarge, color = AccentDark)
        Text(if (activeSession) "本轮队列与显示设置已固定。以下更改从下一轮学习生效。" else "设置保存在此设备。新词与到期复习会在开始时合并成固定的一轮。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionTitle("每日新词数量")
        OutlinedTextField(value = limit, onValueChange = { limit = it },
            label = { Text("每日新词数量") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() }),
            isError = countError != null, supportingText = { Text(countError ?: "输入非负整数；0 表示只复习，今日已学习的新词会计入额度。") },
            modifier = Modifier.fillMaxWidth().testTag("daily_new_limit"))
        Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Button) {
            keyboard?.hide()
            focus.clearFocus()
            onChooseBook()
        }.testTag("choose_book").padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("选择词书", fontWeight = FontWeight.SemiBold)
                Text(currentBook?.title ?: "尚未选择词书", style = MaterialTheme.typography.bodyMedium)
                if (currentBook != null) {
                    val learned = currentBook.lexemeUids.count { (snapshot.cards[it]?.repetitions ?: 0) > 0 }
                    val total = currentBook.lexemeUids.size
                    val percent = if (total == 0) 0 else learned * 100 / total
                    Text("$learned / $total 已学习 · $percent%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = Line)
        SectionTitle("释义与音标")
        ToggleSetting("显示 IPA", "法语国际音标", ipa, true, "show_ipa") { ipa = it }
        ToggleSetting("中文 · ZH", "中文释义与例句翻译", chinese, !chinese || english || spanish, "show_chinese") { chinese = it }
        ToggleSetting("English · EN", "英语释义与例句翻译", english, !english || chinese || spanish, "show_english") { english = it }
        ToggleSetting("Español · ES", "西班牙语释义与例句翻译", spanish, !spanish || chinese || english, "show_spanish") { spanish = it }
        Text("至少保留一种释义语言。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(color = Line)
        SectionTitle("发音")
        Text("自动播放", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AutoPlayMode.entries.forEach { mode ->
                FilterChip(selected = autoPlay == mode.name, onClick = { autoPlay = mode.name }, label = { Text(when (mode) { AutoPlayMode.NEVER -> "从不"; AutoPlayMode.NEW_ONLY -> "仅新词"; AutoPlayMode.ALL -> "全部词条" }) }, modifier = Modifier.testTag("autoplay_${mode.name}"))
            }
        }
        ToggleSetting("离线 TTS 回退", "无本地音频时使用已安装的 fr-FR 离线语音", tts, true, "tts_fallback") { tts = it }
        Text("发音优先使用词条本地音频。设备缺少法语离线语音时会显示提示。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = {
            keyboard?.hide()
            focus.clearFocus()
            onSave(UserSettings(
                dailyNewLimit = count!!, bookId = settings.bookId, autoPlay = AutoPlayMode.valueOf(autoPlay),
                ttsFallback = tts, showIpa = ipa, showEnglish = english, showSpanish = spanish, showChinese = chinese,
            ))
        }, enabled = valid && !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(top = 4.dp).testTag("save_settings"), shape = RoundedCornerShape(14.dp)) { Text("保存设置") }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ToggleSetting(title: String, subtitle: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).testTag(tag).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            Text(subtitle, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun StatisticsScreen(snapshot: AppSnapshot, wordCount: Int) {
    val stats = snapshot.stats
    val learnedTotal = snapshot.cards.values.count { it.repetitions > 0 }
    val max = stats.lastSevenDays.maxOfOrNull { it.reviews }?.coerceAtLeast(1) ?: 1
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).testTag("statistics_screen"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Chaque jour compte.", style = MaterialTheme.typography.headlineSmall, color = AccentDark)
        Row(Modifier.fillMaxWidth().background(AccentSoft, RoundedCornerShape(18.dp)).padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Statistic("${stats.todayReviews}", "今日完成")
            Statistic("${stats.streakDays}", "连续天数")
            Statistic("$learnedTotal", "累计词条")
        }
        SectionTitle("最近 7 天", "每次评分计为一次完成；数据保存在设备上")
        stats.lastSevenDays.forEach { day ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(day.date.takeLast(5), Modifier.width(50.dp), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { day.reviews.toFloat() / max }, modifier = Modifier.weight(1f).height(10.dp))
                Text("${day.reviews}", Modifier.width(34.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
        if (stats.lastSevenDays.all { it.reviews == 0 }) Text("还没有学习记录，开始第一轮学习吧。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(color = Line)
        StatRow("全部词库已学", "$learnedTotal / $wordCount")
        StatRow("当前到期复习", "${stats.dueCount}")
        StatRow("今日已学新词", "${stats.todayNew}")
        StatRow("收藏词条", "${snapshot.favorites.size}")
        StatRow("最近 7 天完成", "${stats.lastSevenDays.sumOf { it.reviews }}")
    }
}

@Composable
private fun Statistic(number: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(number, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = AccentDark)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label); Text(value, fontWeight = FontWeight.SemiBold) }
}

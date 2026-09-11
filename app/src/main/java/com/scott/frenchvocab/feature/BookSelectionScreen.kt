package com.scott.frenchvocab.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.scott.frenchvocab.domain.AppSnapshot
import com.scott.frenchvocab.domain.VocabularyBook
import com.scott.frenchvocab.ui.theme.AccentDark
import com.scott.frenchvocab.ui.theme.Line

@Composable
fun BookSelectionScreen(snapshot: AppSnapshot, books: List<VocabularyBook>, busy: Boolean, activeSession: Boolean, onSave: (String) -> Unit) {
    // A tap is only a draft; leaving this page without saving discards it.
    var selectedBookId by rememberSaveable { mutableStateOf(snapshot.settings.bookId) }
    Column(Modifier.fillMaxSize().testTag("book_selection_screen")) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("共 ${books.size} 本词书", style = MaterialTheme.typography.titleMedium)
            Text("选择后点击「保存词书」完成切换，返回则取消本次选择。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (activeSession) Text("当前学习进度已保留，切换词书从下一轮生效。", style = MaterialTheme.typography.bodySmall, color = AccentDark)
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().selectableGroup().testTag("book_selection_list"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
        ) {
            items(books, key = { it.id }) { book ->
                val learned = book.lexemeUids.count { (snapshot.cards[it]?.repetitions ?: 0) > 0 }
                val total = book.lexemeUids.size
                val progress = if (total == 0) 0f else learned.toFloat() / total
                val percent = if (total == 0) 0 else learned * 100 / total
                Row(
                    Modifier.fillMaxWidth().selectable(
                        selected = selectedBookId == book.id,
                        enabled = !busy,
                        role = Role.RadioButton,
                        onClick = { selectedBookId = book.id },
                    ).testTag("book_option_${book.id}").padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selectedBookId == book.id, onClick = null, enabled = !busy)
                    Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(book.title, fontWeight = FontWeight.SemiBold)
                        if (book.id == snapshot.settings.bookId) Text("当前使用", color = AccentDark, style = MaterialTheme.typography.labelSmall)
                        Text("$learned / $total 已学习 · $percent%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp))
                    }
                }
                HorizontalDivider(color = Line)
            }
        }
        HorizontalDivider(color = Line)
        Button(
            onClick = { onSave(selectedBookId) },
            enabled = !busy && books.any { it.id == selectedBookId },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 52.dp).testTag("save_book"),
            shape = RoundedCornerShape(14.dp),
        ) { Text(if (busy) "正在保存…" else "保存词书") }
    }
}

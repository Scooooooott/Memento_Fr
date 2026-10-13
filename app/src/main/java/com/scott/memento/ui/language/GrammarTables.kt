package com.scott.memento.ui.language

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.scott.memento.R
import com.scott.memento.domain.Detail

/** The detail tab owns both states, so tab changes and recreation restore the same table. */
@Composable
fun GrammarTable(
    word: Detail,
    horizontalState: ScrollState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val model = remember(word) { projectGrammarTable(word) }
    if (model.rows.isEmpty()) {
        Column(modifier.fillMaxSize().padding(16.dp).testTag("grammar_empty")) {
            Text(stringResource(R.string.ui3_grammar_empty), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val labelWidth = when (model.style) {
        GrammarStyle.FRENCH -> 144.dp
        GrammarStyle.SPANISH -> 172.dp
        GrammarStyle.GENERIC -> 156.dp
    }
    val help=stringResource(R.string.ui3_grammar_help)
    BoxWithConstraints(modifier.fillMaxSize().testTag("grammar_table")) {
    // A complete person column can fit beside the fixed language-specific label on narrow screens.
    val cellWidth=(maxWidth-labelWidth).coerceIn(96.dp,150.dp)
    Column(Modifier.fillMaxSize()) {
        // Only the person columns scroll. The label remains visible at the left.
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).semantics{contentDescription=help}) {
            GrammarBox(labelWidth, header = true) {
                Text(stringResource(R.string.ui3_grammar_label_header), fontWeight = FontWeight.SemiBold)
            }
            Row(
                Modifier.weight(1f).fillMaxHeight().horizontalScroll(horizontalState)
                    .testTag("grammar_horizontal_header"),
            ) {
                model.columns.forEach { column ->
                    GrammarBox(cellWidth, header = true, tag = "grammar_header_${column.key}") {
                        Text(column.label, fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("grammar_rows"),
            state = listState,
        ) {
            items(model.rows, key = { it.paradigmId }) { row ->
                // Intrinsic height considers the complete note and all forms. All boxes then use
                // that same height; the scrolling content contains neither lazy nor subcomposed UI.
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag("grammar_row_${row.paradigmId}")) {
                    GrammarBox(labelWidth, header = true, tag = "grammar_label_${row.paradigmId}") {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            if (row.mood.isNotBlank()) {
                                Text(grammarMood(row.mood), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            Text(row.header, fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium)
                            if (row.tense.isNotBlank() && row.tense != row.header) {
                                Text(row.tense, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (row.region.isNotBlank()) {
                                Text(row.region, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.testTag("grammar_region_${row.paradigmId}"))
                            }
                            if (row.note.isNotBlank()) {
                                Text(row.note, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.testTag("grammar_note_${row.paradigmId}"))
                            }
                        }
                    }
                    Row(
                        Modifier.weight(1f).fillMaxHeight().horizontalScroll(horizontalState)
                            .testTag("grammar_horizontal_${row.paradigmId}"),
                    ) {
                        model.columns.forEach { column ->
                            GrammarBox(cellWidth, tag = "grammar_cell_${row.paradigmId}_${column.key}") {
                                val entries = row.cells[column.key].orEmpty()
                                if (entries.isEmpty()) {
                                    Text(stringResource(R.string.ui3_grammar_missing),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        entries.forEach { entry ->
                                            if (model.style == GrammarStyle.FRENCH) {
                                                Text(entry.phrase, style = MaterialTheme.typography.bodyMedium)
                                            } else {
                                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                    if (entry.pronoun.isNotBlank()) {
                                                        Text(entry.pronoun, style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                    Text(entry.form, style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "grammar_explanation") {
                // Guidance scrolls with the rows, leaving a usable table viewport at 2x text.
                Text(help,Modifier.padding(horizontal=12.dp,vertical=8.dp).testTag("grammar_help"),
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(if (model.style == GrammarStyle.SPANISH) R.string.ui3_grammar_spanish_note
                        else R.string.ui3_grammar_source_note),
                    Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    }
}

@Composable
private fun GrammarBox(
    width: Dp,
    header: Boolean = false,
    tag: String? = null,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.width(width).fillMaxHeight().heightIn(min = 64.dp)
            .background(if (header) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .then(if (tag == null) Modifier else Modifier.testTag(tag))
            .padding(horizontal = 10.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

@Composable
private fun grammarMood(mood: String): String = when (mood) {
    "indicatif", "indicativo" -> stringResource(R.string.ui3_grammar_indicative, mood)
    "subjonctif", "subjuntivo" -> stringResource(R.string.ui3_grammar_subjunctive, mood)
    "impératif", "imperatif", "imperativo" -> stringResource(R.string.ui3_grammar_imperative, mood)
    else -> mood
}

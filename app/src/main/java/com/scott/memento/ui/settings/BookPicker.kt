package com.scott.memento.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.scott.memento.R
import com.scott.memento.domain.Book

internal fun bookPercent(learned:Int,total:Int):Int=if(total>0) (learned*100L/total).toInt() else 0
internal fun bookRatio(learned:Int,total:Int):Float=if(total>0) (learned.toFloat()/total).coerceIn(0f,1f) else 0f

@Composable internal fun ColumnScope.BookPicker(
    books:List<Book>,progress:Map<String,Int>,selected:String,current:String?,busy:Boolean,
    onSelect:(String)->Unit,onConfirm:()->Unit,onCancel:()->Unit,
) {
    Column(Modifier.weight(1f).fillMaxWidth().testTag("book_selection_screen")) {
        Column(Modifier.padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.choose_book),style=MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.ui5_book_count,books.size),style=MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.book_draft_hint),style=MaterialTheme.typography.bodySmall)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().selectableGroup().testTag("book_picker")) {
            items(books,key={it.id}) {option->
                val learned=progress[option.id] ?: 0
                Row(
                    Modifier.fillMaxWidth().selectable(selected=selected==option.id,enabled=!busy,
                        role=Role.RadioButton,onClick={onSelect(option.id)}).testTag("book_${option.id}").padding(vertical=16.dp),
                    verticalAlignment=Alignment.CenterVertically,
                ) {
                    RadioButton(selected=selected==option.id,onClick=null,enabled=!busy,modifier=Modifier.testTag("book_radio_${option.id}"))
                    Column(Modifier.weight(1f).padding(start=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(option.title,fontWeight=FontWeight.SemiBold,modifier=Modifier.testTag("book_title_${option.id}"))
                        if(option.id==current) Text(stringResource(R.string.ui5_book_current),style=MaterialTheme.typography.labelSmall,
                            color=MaterialTheme.colorScheme.primary,modifier=Modifier.testTag("book_current_${option.id}"))
                        if(option.description.isNotBlank()) Text(option.description,style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("book_description_${option.id}"))
                        Text(stringResource(R.string.book_progress,option.title,learned,option.count),
                            style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("book_progress_${option.id}"))
                        Text(stringResource(R.string.home_book_percent,bookPercent(learned,option.count)),
                            style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("book_percent_${option.id}"))
                        LinearProgressIndicator(progress={bookRatio(learned,option.count)},
                            modifier=Modifier.fillMaxWidth().height(4.dp).testTag("book_indicator_${option.id}"))
                    }
                }
                HorizontalDivider()
            }
        }
        HorizontalDivider()
        Button(onClick=onConfirm,enabled=!busy && books.any{it.id==selected},
            modifier=Modifier.fillMaxWidth().padding(top=12.dp).heightIn(min=52.dp).testTag("save_book")) {
            Text(stringResource(R.string.ui5_book_confirm))
        }
        TextButton(onClick=onCancel,enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("cancel_book")) {
            Text(stringResource(R.string.cancel))
        }
    }
}

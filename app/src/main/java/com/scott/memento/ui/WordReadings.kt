package com.scott.memento.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.scott.memento.R
import com.scott.memento.domain.Detail

/** Shared by study and detail, including words with no IPA at all. */
@Composable internal fun WordReadings(word:Detail) {
    if(word.readings.isEmpty()) {if(word.ipa.isNotBlank())Text(word.ipa);return}
    word.readings.forEach{reading->
        val label=when(reading.type.uppercase(java.util.Locale.ROOT)) {
            "IPA"->"IPA";"KANA"->stringResource(R.string.reading_kana);"ROMAJI","ROMANIZATION"->stringResource(R.string.reading_romanization)
            "HANGUL"->stringResource(R.string.reading_hangul);else->reading.type
        }
        Text("$label · ${reading.locale}：${reading.text}",modifier=Modifier.testTag("reading_${reading.type}_${reading.locale}"))
    }
}

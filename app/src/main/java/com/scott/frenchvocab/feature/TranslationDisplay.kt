package com.scott.frenchvocab.feature

import com.scott.frenchvocab.domain.UserSettings

internal data class DisplayTranslation(val label: String, val tag: String, val text: String)

/** Available selected languages, consistently ordered Chinese, English, Spanish. */
internal fun selectedTranslations(settings: UserSettings, chinese: String, english: String, spanish: String): List<DisplayTranslation> = buildList {
    if (settings.showChinese && chinese.isNotBlank()) add(DisplayTranslation("中文", "zh", chinese))
    if (settings.showEnglish && english.isNotBlank()) add(DisplayTranslation("EN", "en", english))
    if (settings.showSpanish && spanish.isNotBlank()) add(DisplayTranslation("ES", "es", spanish))
}

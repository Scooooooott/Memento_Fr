package com.scott.frenchvocab.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FrenchLightColors = lightColorScheme(
    primary = AccentDark,
    onPrimary = Color.White,
    primaryContainer = AccentSoft,
    onPrimaryContainer = AccentDark,
    secondaryContainer = AccentSoft,
    onSecondaryContainer = AccentDark,
    background = ScreenBackground,
    surface = Color.White,
    onSurface = TextPrimary,
    onSurfaceVariant = TextMuted,
    outline = Line,
)

@Composable
fun FrenchVocabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FrenchLightColors,
        typography = FrenchTypography,
        content = content,
    )
}

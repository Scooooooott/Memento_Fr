package com.scott.frenchvocab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.scott.frenchvocab.feature.FrenchVocabApp
import com.scott.frenchvocab.ui.theme.FrenchVocabTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FrenchVocabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FrenchVocabApp()
                }
            }
        }
    }
}

@Composable
fun FrenchVocabAppPreview() {
    FrenchVocabTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            FrenchVocabApp()
        }
    }
}

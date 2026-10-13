package com.scott.memento

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.scott.memento.ui.MementoApp
import com.scott.memento.ui.MementoController
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val database=if(BuildConfig.DEBUG) intent.getStringExtra("qa_user_database") ?: "memento_user.db" else "memento_user.db"
        require(database=="memento_user.db" || (database.startsWith("memento-test-") && database.endsWith(".db") && '/' !in database && '\\' !in database))
        // Deterministic process-restart QA is restricted to a named debug test database.
        val clock=if(BuildConfig.DEBUG && database.startsWith("memento-test-") && intent.hasExtra("qa_clock_millis"))
            Clock.fixed(Instant.ofEpochMilli(intent.getLongExtra("qa_clock_millis",0)),ZoneOffset.UTC) else Clock.systemUTC()
        val controller=ViewModelProvider(this,object:ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=MementoController(applicationContext,database,clock) as T
        })[MementoController::class.java]
        setContent {MaterialTheme {Surface(Modifier.fillMaxSize()){MementoApp(controller)}}}
    }
}

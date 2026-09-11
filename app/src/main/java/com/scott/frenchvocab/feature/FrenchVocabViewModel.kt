package com.scott.frenchvocab.feature

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.scott.frenchvocab.data.content.ContentRepository
import com.scott.frenchvocab.data.user.StudyRepository
import com.scott.frenchvocab.domain.AppSnapshot
import com.scott.frenchvocab.domain.Lexeme
import com.scott.frenchvocab.domain.Rating
import com.scott.frenchvocab.domain.UserSettings
import com.scott.frenchvocab.domain.VocabularyBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The activity retains this owner across rotation; every database operation uses one IO lane. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FrenchVocabViewModel(context: Context) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val application = context.applicationContext
    private val io = Dispatchers.IO.limitedParallelism(1)
    private var content: ContentRepository? = null
    private var repository: StudyRepository? = null

    var snapshot by mutableStateOf<AppSnapshot?>(null)
        private set
    var words by mutableStateOf<List<Lexeme>>(emptyList())
        private set
    var books by mutableStateOf<List<VocabularyBook>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var loadFailed by mutableStateOf(false)
        private set

    init { load() }

    fun load() {
        if (busy) return
        busy = true
        loadFailed = false
        scope.launch {
            try {
                val loaded = withContext(io) {
                    val catalog = content ?: ContentRepository(application).also { content = it }
                    val study = repository ?: StudyRepository(application, catalog).also { repository = it }
                    Triple(catalog.allWords(), catalog.books(), study.snapshot())
                }
                words = loaded.first
                books = loaded.second
                snapshot = loaded.third
            } catch (error: Exception) {
                loadFailed = true
                message = "词库暂时无法打开：${error.message ?: "请重试"}"
            } finally {
                busy = false
            }
        }
    }

    // The guard is set synchronously before launching, so repeated taps cannot enqueue scores.
    private fun change(after: (AppSnapshot) -> Unit = {}, action: (StudyRepository) -> AppSnapshot) {
        val study = repository ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                val updated = withContext(io) { action(study) }
                snapshot = updated
                after(updated)
            } catch (error: Exception) {
                message = "操作未完成：${error.message ?: "请重试"}"
            } finally {
                busy = false
            }
        }
    }

    fun refresh() = change { it.snapshot() }
    fun start(after: (AppSnapshot) -> Unit) = change(after) { it.startSession() }
    fun reveal(sessionId: Long, uid: String) = change { it.reveal(sessionId, uid) }
    fun rate(sessionId: Long, uid: String, rating: Rating) = change { it.rate(sessionId, uid, rating) }
    fun favorite(uid: String) = change { it.toggleFavorite(uid) }
    fun save(settings: UserSettings, after: () -> Unit) = change({ after() }) { it.saveSettings(settings) }
    fun notify(text: String?) { if (text != null) message = text }
    fun dismissMessage() { message = null }

    override fun onCleared() {
        scope.cancel()
        // Dispatch after any in-flight SQLite operation; never close a connection underneath it.
        CoroutineScope(io).launch {
            repository?.close()
            content?.close()
        }
    }
}

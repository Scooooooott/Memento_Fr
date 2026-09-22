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
import com.scott.frenchvocab.domain.LexemePreview
import com.scott.frenchvocab.domain.Rating
import com.scott.frenchvocab.domain.UserSettings
import com.scott.frenchvocab.domain.VocabularyBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BrowseCriteria(
    val bookId: String = "",
    val query: String = "",
    val favoritesOnly: Boolean = false,
)

data class BrowseState(
    val criteria: BrowseCriteria = BrowseCriteria(),
    val items: List<LexemePreview> = emptyList(),
    val total: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
)

private data class BrowseRequest(val criteria: BrowseCriteria, val favoriteUids: Set<String>)
private data class LoadedApp(
    val books: List<VocabularyBook>,
    val wordCount: Int,
    val snapshot: AppSnapshot,
    val currentWord: Lexeme?,
)
private data class ChangedApp(val snapshot: AppSnapshot, val currentWord: Lexeme?)

/** The activity retains this owner across rotation; every database operation uses one IO lane. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FrenchVocabViewModel(context: Context) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val application = context.applicationContext
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val wordCache = object : LinkedHashMap<String, Lexeme>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lexeme>?): Boolean = size > 128
    }
    private var content: ContentRepository? = null
    private var repository: StudyRepository? = null
    private var browseJob: Job? = null
    private var browseGeneration = 0L
    private var browseRequest: BrowseRequest? = null
    private var detailJob: Job? = null
    private var detailUid = ""
    private var uiAttached = false

    var snapshot by mutableStateOf<AppSnapshot?>(null)
        private set
    var currentWord by mutableStateOf<Lexeme?>(null)
        private set
    var detailWord by mutableStateOf<Lexeme?>(null)
        private set
    var detailLoading by mutableStateOf(false)
        private set
    var books by mutableStateOf<List<VocabularyBook>>(emptyList())
        private set
    var wordCount by mutableStateOf(0)
        private set
    var browseState by mutableStateOf(BrowseState())
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var loadFailed by mutableStateOf(false)
        private set

    init {
        load()
    }

    /**
     * A retained ViewModel distinguishes rotation from a new process. A new
     * process must resolve navigation to Home even if Android restores a saved route.
     */
    fun claimUiAttachment(): Boolean {
        val first = !uiAttached
        uiAttached = true
        return first
    }

    fun load() {
        if (busy) return
        busy = true
        loadFailed = false
        scope.launch {
            try {
                val loaded = withContext(io) {
                    val catalog = content ?: ContentRepository(application).also { content = it }
                    val study = repository ?: StudyRepository(application, catalog).also { repository = it }
                    val current = study.snapshot()
                    LoadedApp(
                        books = catalog.books(),
                        wordCount = catalog.wordCount,
                        snapshot = current,
                        currentWord = current.session?.current?.lexemeUid?.let(::loadWord),
                    )
                }
                books = loaded.books
                wordCount = loaded.wordCount
                currentWord = loaded.currentWord
                snapshot = loaded.snapshot
            } catch (error: Exception) {
                loadFailed = true
                message = "词库暂时无法打开：${error.message ?: "请重试"}"
            } finally {
                busy = false
            }
        }
    }

    fun requestBrowse(
        bookId: String,
        query: String,
        favoritesOnly: Boolean,
        favoriteUids: Set<String>,
    ) {
        val criteria = BrowseCriteria(bookId, query.trim(), favoritesOnly)
        val request = BrowseRequest(criteria, if (favoritesOnly) favoriteUids.toSet() else emptySet())
        if (request == browseRequest) return
        browseRequest = request
        val generation = ++browseGeneration
        browseJob?.cancel()
        browseState = BrowseState(criteria = criteria, loading = true)
        browseJob = scope.launch {
            try {
                val page = withContext(io) {
                    content?.browse(
                        bookId = criteria.bookId,
                        query = criteria.query,
                        favoritesOnly = criteria.favoritesOnly,
                        favoriteUids = request.favoriteUids,
                        offset = 0,
                        limit = BROWSE_PAGE_SIZE,
                    )
                } ?: return@launch
                if (generation == browseGeneration && browseRequest == request) {
                    browseState = BrowseState(criteria, page.items, page.total, page.hasMore, loading = false)
                }
            } catch (error: Exception) {
                if (generation == browseGeneration) {
                    browseState = BrowseState(criteria = criteria)
                    message = "词库筛选未完成：${error.message ?: "请重试"}"
                }
            }
        }
    }

    fun loadMoreBrowse() {
        val request = browseRequest ?: return
        val before = browseState
        if (before.loading || !before.hasMore || before.criteria != request.criteria) return
        val generation = browseGeneration
        browseState = before.copy(loading = true)
        browseJob = scope.launch {
            try {
                val page = withContext(io) {
                    content?.browse(
                        bookId = request.criteria.bookId,
                        query = request.criteria.query,
                        favoritesOnly = request.criteria.favoritesOnly,
                        favoriteUids = request.favoriteUids,
                        offset = before.items.size,
                        limit = BROWSE_PAGE_SIZE,
                    )
                } ?: return@launch
                if (generation == browseGeneration && browseRequest == request) {
                    val combined = (before.items + page.items).distinctBy(LexemePreview::uid)
                    browseState = BrowseState(
                        criteria = request.criteria,
                        items = combined,
                        total = page.total,
                        hasMore = combined.size < page.total,
                        loading = false,
                    )
                }
            } catch (error: Exception) {
                if (generation == browseGeneration) {
                    browseState = before.copy(loading = false)
                    message = "下一页未加载：${error.message ?: "请重试"}"
                }
            }
        }
    }

    fun loadDetail(uid: String) {
        detailUid = uid
        detailJob?.cancel()
        currentWord?.takeIf { it.uid == uid }?.let {
            detailWord = it
            detailLoading = false
            return
        }
        detailWord = null
        detailLoading = true
        detailJob = scope.launch {
            try {
                val loaded = withContext(io) { loadWord(uid) }
                if (detailUid == uid) detailWord = loaded
            } catch (error: Exception) {
                if (detailUid == uid) message = "词条暂时无法打开：${error.message ?: "请重试"}"
            } finally {
                if (detailUid == uid) detailLoading = false
            }
        }
    }

    fun preview(uid: String): LexemePreview? = content?.preview(uid)

    // The guard is set synchronously before launching, so repeated taps cannot enqueue scores.
    private fun change(after: (AppSnapshot) -> Unit = {}, action: (StudyRepository) -> AppSnapshot) {
        val study = repository ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                val changed = withContext(io) {
                    val updated = action(study)
                    ChangedApp(updated, updated.session?.current?.lexemeUid?.let(::loadWord))
                }
                currentWord = changed.currentWord
                snapshot = changed.snapshot
                after(changed.snapshot)
            } catch (error: Exception) {
                message = "操作未完成：${error.message ?: "请重试"}"
            } finally {
                busy = false
            }
        }
    }

    private fun loadWord(uid: String): Lexeme? {
        wordCache[uid]?.let { return it }
        return content?.find(uid)?.also { wordCache[uid] = it }
    }

    fun refresh() = change { it.snapshot() }
    fun start(after: (AppSnapshot) -> Unit) = change(after) { it.startSession() }
    fun reveal(sessionId: Long, uid: String) = change { it.reveal(sessionId, uid) }
    fun rate(sessionId: Long, uid: String, rating: Rating) = change { it.rate(sessionId, uid, rating) }
    fun favorite(uid: String) = change { it.toggleFavorite(uid) }
    fun save(settings: UserSettings, after: () -> Unit) = change({ after() }) { it.saveSettings(settings) }
    fun notify(text: String?) {
        if (text != null) message = text
    }
    fun dismissMessage() {
        message = null
    }

    override fun onCleared() {
        scope.cancel()
        // Dispatch after any in-flight SQLite operation; never close a connection underneath it.
        CoroutineScope(io).launch {
            repository?.close()
            content?.close()
        }
    }

    companion object {
        const val BROWSE_PAGE_SIZE = 50
    }
}

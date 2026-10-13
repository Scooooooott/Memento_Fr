package com.scott.memento.ui.library

import com.scott.memento.domain.WordPage
import com.scott.memento.domain.WordSummary
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Display language is deliberately absent: each shallow summary already contains its translations. */
data class BrowseCriteria(
    val target:String,
    val query:String,
    val book:String,
    val favoritesOnly:Boolean=false,
    val favorites:Set<String> = emptySet(),
    val contentVersion:String="",
)

enum class BrowseErrorKind { INITIAL, APPEND, REFRESH }

data class BrowseUiState(
    val criteria:BrowseCriteria?=null,
    val rows:List<WordSummary> = emptyList(),
    val total:Int=0,
    val hasMore:Boolean=false,
    val loading:Boolean=false,
    val error:String?=null,
    val resetToken:String="",
    val errorKind:BrowseErrorKind?=null,
    val refreshing:Boolean=false,
)

/**
 * Controller-owned shallow pages. Calls and state publication belong to the supplied UI scope.
 * Cancellation saves work; explicit generation/criteria checks also reject non-cooperative loaders.
 */
class LibraryPager(
    private val scope:CoroutineScope,
    private val load:suspend(BrowseCriteria,Int)->WordPage,
) {
    private val mutable=MutableStateFlow(BrowseUiState())
    val state:StateFlow<BrowseUiState> = mutable.asStateFlow()
    private val instance=UUID.randomUUID().toString()
    private var generation=0L
    private var nextOffset=0
    private var refreshCapacity=50
    private var request:Job?=null

    fun show(criteria:BrowseCriteria) {
        val next=criteria.copy(favorites=criteria.favorites.toSet())
        val previous=mutable.value.criteria
        if(previous==next) return
        val favoritesChangedOnly=previous!=null && previous.copy(favorites=next.favorites)==next
        val debounce=previous!=null && previous.query!=next.query && next.query.isNotBlank() &&
            previous.copy(query=next.query,favorites=next.favorites)==next
        invalidate()
        if(favoritesChangedOnly) {
            refreshCapacity=maxOf(50,mutable.value.rows.size)
            val retained=if(next.favoritesOnly) mutable.value.rows.filter{it.uid in next.favorites} else mutable.value.rows
            mutable.value=mutable.value.copy(criteria=next,rows=retained,loading=true,error=null,
                errorKind=null,refreshing=true)
            start(next,BrowseErrorKind.REFRESH,0)
        } else {
            nextOffset=0
            mutable.value=BrowseUiState(criteria=next,loading=true,resetToken="$instance:$generation")
            start(next,BrowseErrorKind.INITIAL,0,if(debounce) 180L else 0L)
        }
    }

    fun next() {
        val current=mutable.value
        val criteria=current.criteria ?: return
        if(current.loading || current.error!=null || !current.hasMore) return
        mutable.value=current.copy(loading=true,error=null,errorKind=null)
        start(criteria,BrowseErrorKind.APPEND,nextOffset)
    }

    fun retry() {
        val current=mutable.value
        val criteria=current.criteria ?: return
        if(current.loading || current.error==null) return
        val kind=current.errorKind ?: BrowseErrorKind.INITIAL
        mutable.value=current.copy(loading=true,error=null,errorKind=null,refreshing=kind==BrowseErrorKind.REFRESH)
        start(criteria,kind,if(kind==BrowseErrorKind.APPEND) nextOffset else 0)
    }

    private fun invalidate() {
        generation++
        request?.cancel()
        request=null
    }

    private fun current(criteria:BrowseCriteria,identity:Long)=generation==identity && mutable.value.criteria==criteria

    private fun start(criteria:BrowseCriteria,kind:BrowseErrorKind,offset:Int,debounce:Long=0L) {
        val identity=generation
        request=scope.launch {
            try {
                if(debounce>0) delay(debounce)
                var consumed=offset
                val fetched=mutableListOf<WordSummary>()
                var page:WordPage
                do {
                    page=load(criteria,consumed)
                    if(!current(criteria,identity)) return@launch
                    require(page.total>=0) { "词库返回了无效的结果数量" }
                    require(page.words.all{it.uid.startsWith("${criteria.target}:")}) { "词库响应与目标语言不一致" }
                    check(page.words.isNotEmpty() || !page.hasMore) { "词库分页未推进，请重试" }
                    consumed+=page.words.size // Raw page size, never the deduplicated visible size.
                    fetched.addAll(page.words)
                } while(kind==BrowseErrorKind.REFRESH && page.hasMore && consumed<refreshCapacity)
                if(!current(criteria,identity)) return@launch
                val rows=(if(kind==BrowseErrorKind.APPEND) mutable.value.rows+fetched else fetched).distinctBy{it.uid}
                nextOffset=consumed
                mutable.value=mutable.value.copy(rows=rows,total=page.total,hasMore=page.hasMore,
                    loading=false,error=null,errorKind=null,refreshing=false)
            } catch(cancelled:CancellationException) {
                throw cancelled
            } catch(failure:Exception) {
                if(current(criteria,identity)) mutable.value=mutable.value.copy(loading=false,
                    error=failure.message?.takeIf{it.isNotBlank()} ?: "读取词库失败，请重试",
                    errorKind=kind,refreshing=false)
            }
        }
    }
}

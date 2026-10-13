package com.scott.memento.ui.library

import com.scott.memento.domain.WordPage
import com.scott.memento.domain.WordSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class LibraryPagerTest {
    @Test fun pagesStopAtEachExplicitRequestAndConsumeRawRowsWhileDeduplicatingUids()=runBlocking {
        val calls=mutableListOf<Int>()
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val pager=LibraryPager(scope) { criteria,offset ->
                calls+=offset
                val words=(offset until offset+50).map{word(criteria.target,it)}.toMutableList()
                if(offset==50) words[0]=word(criteria.target,49)
                WordPage(words,200,true)
            }
            pager.show(criteria());settled(pager)
            assertEquals(listOf(0),calls);assertEquals(50,pager.state.value.rows.size)
            pager.next();pager.next();settled(pager)
            assertEquals(listOf(0,50),calls);assertEquals(99,pager.state.value.rows.size)
            pager.next();settled(pager)
            assertEquals(listOf(0,50,100),calls);assertEquals(149,pager.state.value.rows.size)
            assertEquals(149,pager.state.value.rows.map{it.uid}.toSet().size)
            delay(20);assertEquals(3,calls.size) // No self-driven loop through the whole library.
        } finally {scope.cancel()}
    }

    @Test fun queryChangesDebounceButClearImmediatelyReplacesPendingQuery()=runBlocking {
        val calls=mutableListOf<String>()
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val pager=LibraryPager(scope){criteria,_->calls+=criteria.query;WordPage(listOf(word("fr",0)),1,false)}
            pager.show(criteria());settled(pager)
            pager.show(criteria(query="a"));delay(35)
            pager.show(criteria(query="ab"));delay(35)
            assertEquals(listOf(""),calls)
            pager.show(criteria());settled(pager)
            assertEquals(listOf("",""),calls)
            delay(210);assertEquals(listOf("",""),calls)
            pager.show(criteria(query="être"));settled(pager)
            assertEquals(listOf("","","être"),calls)
        } finally {scope.cancel()}
    }

    @Test fun lateQueryAndTargetResponsesAreRejectedEvenWhenLoaderIgnoresCancellation()=runBlocking {
        val calls=mutableListOf<Pending>()
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val pager=LibraryPager(scope){criteria,offset->
                val pending=Pending(criteria,offset);calls+=pending
                withContext(NonCancellable){pending.result.await()}
            }
            pager.show(criteria());awaitCalls(calls,1)
            pager.show(criteria(query="old"));awaitCalls(calls,2)
            pager.show(criteria(target="es"));awaitCalls(calls,3)
            calls[2].result.complete(WordPage(listOf(word("es",3)),1,false));settled(pager)
            calls[1].result.complete(WordPage(listOf(word("fr",2)),1,false))
            calls[0].result.complete(WordPage(listOf(word("fr",1)),1,false));delay(15)
            assertEquals("es",pager.state.value.criteria!!.target)
            assertEquals(listOf("es:word3:noun:1"),pager.state.value.rows.map{it.uid})
        } finally {calls.forEach{it.result.complete(WordPage(emptyList(),0,false))};scope.cancel()}
    }

    @Test fun lateAppendCannotMergeIntoNewBookResults()=runBlocking {
        val append=CompletableDeferred<WordPage>()
        val calls=mutableListOf<Pair<String,Int>>()
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val pager=LibraryPager(scope){criteria,offset->
                calls+=criteria.book to offset
                if(offset==50) withContext(NonCancellable){append.await()}
                else WordPage(listOf(word("fr",if(criteria.book=="new") 500 else 0)),100,true).let {
                    if(criteria.book=="new") it else it.copy(words=(0 until 50).map{n->word("fr",n)})
                }
            }
            pager.show(criteria());settled(pager)
            pager.next();withTimeout(1000){while(calls.size<2) delay(1)}
            pager.show(criteria(book="new"));settled(pager)
            append.complete(WordPage((50 until 100).map{word("fr",it)},100,false));delay(15)
            assertEquals("new",pager.state.value.criteria!!.book)
            assertEquals(listOf("fr:word500:noun:1"),pager.state.value.rows.map{it.uid})
            assertEquals(listOf("" to 0,"" to 50,"new" to 0),calls)
        } finally {append.complete(WordPage(emptyList(),0,false));scope.cancel()}
    }

    @Test fun appendFailureRetainsRowsBlocksAutomaticRetryAndRetriesSameOffset()=runBlocking {
        val calls=mutableListOf<Int>();var fail=true
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val pager=LibraryPager(scope){criteria,offset->
                calls+=offset
                if(offset==50 && fail) error("append failed")
                WordPage((offset until offset+50).map{word(criteria.target,it)},150,true)
            }
            pager.show(criteria());settled(pager)
            val first=pager.state.value.rows
            pager.next();settled(pager)
            assertEquals(first,pager.state.value.rows);assertEquals(BrowseErrorKind.APPEND,pager.state.value.errorKind)
            repeat(5){pager.next()};delay(10);assertEquals(listOf(0,50),calls)
            fail=false;pager.retry();settled(pager)
            assertEquals(listOf(0,50,50),calls);assertEquals(100,pager.state.value.rows.size)
            assertNull(pager.state.value.error)
        } finally {scope.cancel()}
    }

    @Test fun initialFailureRetriesAtZeroAndChangingFilterClearsFailure()=runBlocking {
        val calls=mutableListOf<Pair<String,Int>>();var fail=true
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val pager=LibraryPager(scope){criteria,offset->calls+=criteria.book to offset
                if(fail) error("initial failed") else WordPage(listOf(word(criteria.target,0)),1,false)}
            pager.show(criteria());settled(pager)
            assertEquals(BrowseErrorKind.INITIAL,pager.state.value.errorKind)
            assertTrue(pager.state.value.rows.isEmpty());pager.next();assertEquals(1,calls.size)
            fail=false;pager.retry();settled(pager)
            assertEquals(listOf("" to 0,"" to 0),calls)
            val token=pager.state.value.resetToken
            pager.show(criteria(book="filtered"));settled(pager)
            assertNotEquals(token,pager.state.value.resetToken);assertNull(pager.state.value.error)
        } finally {scope.cancel()}
    }

    @Test fun favoriteRemovalRebuildsLoadedCapacityFromZeroWithoutResettingScrollIdentity()=runBlocking {
        val calls=mutableListOf<Int>()
        val favorites=(0 until 220).map{word("fr",it).uid}.toSet()
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val pager=LibraryPager(scope){criteria,offset->
                calls+=offset
                val remaining=(0 until 220).map{word("fr",it)}.filter{it.uid in criteria.favorites}
                WordPage(remaining.drop(offset).take(50),remaining.size,offset+50<remaining.size)
            }
            pager.show(criteria(favoritesOnly=true,favorites=favorites));settled(pager)
            repeat(2){pager.next();settled(pager)}
            assertEquals(150,pager.state.value.rows.size)
            val token=pager.state.value.resetToken
            val retained=pager.state.value.rows[110].uid
            val removed=word("fr",2).uid
            pager.show(criteria(favoritesOnly=true,favorites=favorites-removed))
            assertEquals(token,pager.state.value.resetToken)
            assertTrue(pager.state.value.refreshing)
            assertFalse(pager.state.value.rows.any{it.uid==removed})
            assertTrue(pager.state.value.rows.any{it.uid==retained})
            settled(pager)
            assertEquals(listOf(0,50,100,0,50,100),calls)
            assertEquals(150,pager.state.value.rows.size)
            assertEquals(word("fr",150).uid,pager.state.value.rows.last().uid)
            pager.next();settled(pager)
            assertEquals(150,calls.last());assertEquals(200,pager.state.value.rows.size)
            assertEquals(200,pager.state.value.rows.map{it.uid}.toSet().size)
        } finally {scope.cancel()}
    }

    @Test fun favoriteRefreshFailureKeepsValidRowsAndRetryStartsNewSnapshotAtZero()=runBlocking {
        val calls=mutableListOf<Int>();var failRefresh=false
        val favorites=(0 until 120).map{word("fr",it).uid}.toSet()
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val pager=LibraryPager(scope){criteria,offset->
                calls+=offset
                if(failRefresh && offset==50) error("refresh failed")
                val words=(0 until 120).map{word("fr",it)}.filter{it.uid in criteria.favorites}
                WordPage(words.drop(offset).take(50),words.size,offset+50<words.size)
            }
            pager.show(criteria(favoritesOnly=true,favorites=favorites));settled(pager)
            pager.next();settled(pager)
            failRefresh=true
            pager.show(criteria(favoritesOnly=true,favorites=favorites-word("fr",0).uid));settled(pager)
            assertEquals(BrowseErrorKind.REFRESH,pager.state.value.errorKind)
            assertEquals(99,pager.state.value.rows.size);assertFalse(pager.state.value.rows.any{it.uid==word("fr",0).uid})
            pager.next();assertEquals(listOf(0,50,0,50),calls)
            failRefresh=false;pager.retry();settled(pager)
            assertEquals(listOf(0,50,0,50,0,50),calls)
            assertEquals(100,pager.state.value.rows.size)
            assertEquals(word("fr",100).uid,pager.state.value.rows.last().uid)
        } finally {scope.cancel()}
    }

    @Test fun identicalCriteriaReuseRowsAndEachPagerHasItsOwnResetIdentityAndImmutableFavoriteSnapshot()=runBlocking {
        var calls=0
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val loader:suspend(BrowseCriteria,Int)->WordPage={criteria,_->calls++
                WordPage(listOf(word(criteria.target,1)),1,false)}
            val first=LibraryPager(scope,loader);val second=LibraryPager(scope,loader)
            val favoriteSet=mutableSetOf(word("fr",1).uid)
            first.show(criteria(favorites=favoriteSet));settled(first)
            favoriteSet+=word("fr",2).uid
            assertEquals(setOf(word("fr",1).uid),first.state.value.criteria!!.favorites)
            val token=first.state.value.resetToken
            first.show(criteria(favorites=setOf(word("fr",1).uid)));settled(first)
            assertEquals(1,calls);assertEquals(token,first.state.value.resetToken)
            second.show(criteria());settled(second)
            assertNotEquals(token,second.state.value.resetToken)
            assertEquals(setOf("zh-Hans","en","es"),first.state.value.rows.single().translations.keys)
        } finally {scope.cancel()}
    }

    @Test fun nonAdvancingOrWrongTargetPageBecomesExplicitFailureWithoutAutomaticLoop()=runBlocking {
        var calls=0;var wrongTarget=false
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val pager=LibraryPager(scope){_,_->calls++
                if(wrongTarget) WordPage(listOf(word("es",1)),1,false) else WordPage(emptyList(),10,true)}
            pager.show(criteria());settled(pager)
            assertNotNull(pager.state.value.error);assertEquals(1,calls)
            repeat(5){pager.next()};assertEquals(1,calls)
            wrongTarget=true;pager.retry();settled(pager)
            assertTrue(pager.state.value.rows.isEmpty());assertNotNull(pager.state.value.error)
            assertEquals(2,calls)
        } finally {scope.cancel()}
    }

    private data class Pending(val criteria:BrowseCriteria,val offset:Int,val result:CompletableDeferred<WordPage> = CompletableDeferred())
    private fun criteria(target:String="fr",query:String="",book:String="",favoritesOnly:Boolean=false,favorites:Set<String> = emptySet())=
        BrowseCriteria(target,query,book,favoritesOnly,favorites,"content-v1")
    private fun word(target:String,index:Int)=WordSummary("$target:word$index:noun:1","word$index","noun","A1",
        if(target=="fr") mapOf("zh-Hans" to "词 $index","en" to "word $index","es" to "palabra $index")
        else mapOf("zh-Hans" to "词 $index","en" to "word $index","fr" to "mot $index"))
    private suspend fun settled(pager:LibraryPager)=withTimeout(4000){while(pager.state.value.loading) delay(1)}
    private suspend fun awaitCalls(calls:List<Pending>,count:Int)=withTimeout(4000){while(calls.size<count) delay(1)}
}

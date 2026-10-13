package com.scott.memento.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import com.scott.memento.R
import com.scott.memento.domain.WordSummary
import com.scott.memento.ui.AppState
import com.scott.memento.ui.languageLabel
import kotlinx.coroutines.flow.distinctUntilChanged

/** The owner keeps shallow pages; the caller saves this view separately for each target and origin. */
@Composable
fun LibraryScreen(
    state:AppState,
    pager:LibraryPager,
    query:String,
    onQuery:(String)->Unit,
    book:String,
    onBook:(String)->Unit,
    favorites:Boolean,
    onFavorites:()->Unit,
    onOpen:(String)->Unit,
    modifier:Modifier=Modifier,
    visible:Boolean=true,
) {
    val selection=state.preferences?.selection ?: return
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val favoriteIds=state.overview?.favorites.orEmpty()
    val learnedIds=state.overview?.learned.orEmpty()
    // Learning language only selects a translation already present in each shallow summary.
    val criteria=remember(selection.target,query,book,favorites,
        if(favorites) favoriteIds else emptySet(),state.index?.version) {
        BrowseCriteria(selection.target,query,book,favorites,
            if(favorites) favoriteIds else emptySet(),state.index?.version.orEmpty())
    }
    val ui by pager.state.collectAsState()
    val listState=rememberLazyListState()
    var lastResetToken by rememberSaveable {mutableStateOf<String?>(null)}
    val currentUi by rememberUpdatedState(ui)
    val matching=ui.criteria==criteria
    // A favorite change keeps the same browsing scope and scroll identity. Preserve surviving
    // keyed rows even in the frame before show() publishes REFRESH; replacing them with a single
    // loading row would clamp a later-page viewport to the top and lose its first visible UID.
    // Target, query, book, filter mode and content revision must all still match exactly.
    val favoritesTransition=!matching && criteria.favoritesOnly && ui.criteria?.let {previous->
        previous.favoritesOnly && previous.copy(favorites=criteria.favorites)==criteria
    }==true
    val showing=matching || favoritesTransition
    val rows=when {
        matching->ui.rows
        favoritesTransition->ui.rows.filter{it.uid in criteria.favorites}
        else->emptyList()
    }
    val refreshing=showing && (favoritesTransition || ui.refreshing)

    LaunchedEffect(pager,criteria) {pager.show(criteria)}
    LaunchedEffect(matching,ui.resetToken) {
        if(matching && lastResetToken!=ui.resetToken) {
            // A new owner after process death has no old pages: discard its obsolete saved offset.
            // The same owner/token after Activity recreation retains the actual LazyListState.
            listState.scrollToItem(0)
            lastResetToken=ui.resetToken
        }
    }
    LaunchedEffect(pager,visible,criteria) {
        if(!visible) return@LaunchedEffect
        snapshotFlow {
            val current=currentUi
            val last=listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if(current.criteria==criteria && lastResetToken==current.resetToken && current.rows.isNotEmpty() && current.hasMore &&
                !current.loading && !current.refreshing && current.error==null &&
                last>=current.rows.size-5) current.resetToken to current.rows.size else null
        }.distinctUntilChanged().collect {nearEnd->
            if(nearEnd!=null) pager.next()
        }
    }

    Column(modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value=query,onValueChange=onQuery,enabled=visible,singleLine=true,
            keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),
            keyboardActions=KeyboardActions(onSearch={focus.clearFocus(force=true);keyboard?.hide()}),
            label={Text(stringResource(when(selection.target) {
                "fr"->R.string.library_search_fr
                "es"->R.string.library_search_es
                else->R.string.search_hint
            }))},
            trailingIcon=if(query.isNotEmpty()) {{
                TextButton(onClick={onQuery("")},enabled=visible,
                    modifier=Modifier.heightIn(min=48.dp).testTag("search_clear")) {Text(stringResource(R.string.library_clear))}
            }} else null,
            modifier=Modifier.fillMaxWidth().testTag("search"),
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("library_books"),
            horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            LibraryBookChip("",stringResource(R.string.all_books),book.isEmpty(),visible,onBook)
            state.books.forEach {option->
                LibraryBookChip(option.id,option.title,option.id==book,visible,onBook)
            }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                // During a refresh total remains the previous query/book snapshot until the owner
                // has read its replacement. The footer exposes loading; a favorite-set size is not
                // a valid substitute for the book/query-constrained count.
                Text(stringResource(R.string.result_count,languageLabel(selection.target),if(showing) ui.total else 0),
                    style=MaterialTheme.typography.bodyMedium,modifier=Modifier.testTag("result_count"))
                Text(stringResource(R.string.library_loaded_count,rows.size,if(showing) ui.total else 0),
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier=Modifier.testTag("library_loaded_count"))
            }
            FilterChip(selected=favorites,onClick=onFavorites,enabled=visible,
                label={Text(stringResource(R.string.only_favorites))},modifier=Modifier.testTag("nav_favorites"))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("word_list"),state=listState,
            userScrollEnabled=visible,contentPadding=PaddingValues(bottom=16.dp)) {
            if(!showing || (rows.isEmpty() && ui.loading && !refreshing)) item(key="initial-loading") {
                LibraryLoading("library_initial_loading")
            }
            if(showing) {
                items(rows,key={it.uid}) {word->
                    WordListRow(word,selection.learning,word.uid in favoriteIds,word.uid in learnedIds,visible,onOpen)
                    HorizontalDivider()
                }
                if(matching && rows.isEmpty() && !ui.loading && !refreshing && ui.error==null) item(key="empty") {
                    val emptyFavorites=favorites && favoriteIds.isEmpty()
                    Text(stringResource(if(emptyFavorites) R.string.favorites_empty else R.string.library_no_results),
                        modifier=Modifier.fillMaxWidth().padding(vertical=18.dp).testTag("library_empty"))
                }
                if(matching && ui.error!=null) item(key="retry") {
                    Column(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(when(ui.errorKind) {
                            BrowseErrorKind.APPEND->R.string.library_append_failed
                            BrowseErrorKind.REFRESH->R.string.library_refresh_failed
                            else->R.string.library_initial_failed
                        },ui.error.orEmpty()),color=MaterialTheme.colorScheme.error,
                            modifier=Modifier.testTag("library_error"))
                        OutlinedButton(onClick={pager.retry()},enabled=visible && !ui.loading && !ui.refreshing,
                            modifier=Modifier.testTag("library_retry")) {Text(stringResource(R.string.retry))}
                    }
                }
                if(refreshing) item(key="refresh-loading") {LibraryLoading("library_refresh_loading")}
                else if(matching && rows.isNotEmpty() && ui.loading) item(key="append-loading") {LibraryLoading("library_append_loading")}
            }
        }
    }
}

@Composable
private fun LibraryBookChip(id:String,title:String,selected:Boolean,enabled:Boolean,onBook:(String)->Unit) {
    FilterChip(selected=selected,onClick={onBook(id)},enabled=enabled,
        label={Text(title,modifier=if(selected) Modifier.testTag("choose_book") else Modifier)},
        modifier=Modifier.testTag(if(id.isEmpty()) "library_book_all" else "library_book_$id"))
}

@Composable
private fun LibraryLoading(tag:String) {
    Column(Modifier.fillMaxWidth().padding(vertical=10.dp).testTag(tag),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(R.string.loading),style=MaterialTheme.typography.bodySmall)
    }
}

/** A summary row does not read detail, grammar or examples to draw a badge or translation. */
@Composable
fun WordListRow(word:WordSummary,learning:String,favorite:Boolean,learned:Boolean,enabled:Boolean,
                onOpen:(String)->Unit,modifier:Modifier=Modifier) {
    val favoriteDescription=stringResource(R.string.favorite)
    Column(modifier.fillMaxWidth().heightIn(min=48.dp).clickable(enabled=enabled){onOpen(word.uid)}
        .padding(vertical=12.dp).testTag("word_${word.uid}"),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            Text(word.lemma,style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
            if(favorite) Text("★",color=MaterialTheme.colorScheme.primary,
                modifier=Modifier.testTag("word_favorite_${word.uid}").semantics {contentDescription=favoriteDescription})
            Surface(shape=RoundedCornerShape(6.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
                Text(stringResource(if(learned) R.string.library_learned else R.string.library_not_learned),
                    style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(horizontal=6.dp,vertical=4.dp)
                        .testTag("word_learned_${word.uid}"))
            }
        }
        val level=listOf(word.levelSystem,word.level).filter(String::isNotBlank).joinToString(" ")
        Text(listOf(word.partOfSpeech,level).filter(String::isNotBlank).joinToString(" · "),
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(word.translations[learning]?.takeIf(String::isNotBlank) ?: stringResource(R.string.library_translation_missing),
            style=MaterialTheme.typography.bodyMedium,modifier=Modifier.testTag("word_preview_${word.uid}"))
    }
}

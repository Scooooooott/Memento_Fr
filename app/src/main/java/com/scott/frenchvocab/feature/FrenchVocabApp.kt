package com.scott.frenchvocab.feature

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.scott.frenchvocab.domain.AutoPlayMode
import com.scott.frenchvocab.domain.Lexeme
import com.scott.frenchvocab.domain.UserSettings
import com.scott.frenchvocab.domain.audio.PronunciationPlayer
import com.scott.frenchvocab.feature.home.HomeScreen

private fun Context.activity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.activity()
    else -> error("An activity is required")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrenchVocabApp() {
    val context = LocalContext.current
    val activity = context.activity()
    val model = remember(activity) {
        ViewModelProvider(activity, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FrenchVocabViewModel(activity.applicationContext) as T
        })[FrenchVocabViewModel::class.java]
    }
    val player = remember(context) { PronunciationPlayer(context.applicationContext) }
    DisposableEffect(player) { onDispose { player.close() } }
    DisposableEffect(activity, model, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && model.snapshot != null) model.refresh()
            if (event == Lifecycle.Event.ON_STOP) player.stop()
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    var route by rememberSaveable { mutableStateOf("loading") }
    var detailUid by rememberSaveable { mutableStateOf("") }
    var detailFrom by rememberSaveable { mutableStateOf("words") }
    var settingsFrom by rememberSaveable { mutableStateOf("home") }
    val settingsStateHolder = rememberSaveableStateHolder()
    var browseBook by rememberSaveable { mutableStateOf("") }
    var autoPlayedKey by rememberSaveable { mutableStateOf("") }
    val snapshot = model.snapshot
    val session = snapshot?.session
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(model.message) {
        model.message?.let { text ->
            snackbar.showSnackbar(text, actionLabel = "知道了", duration = SnackbarDuration.Long)
            model.dismissMessage()
        }
    }
    LaunchedEffect(snapshot, route) {
        if (snapshot != null && route == "loading") {
            route = if (snapshot.session?.current != null && !snapshot.session.completed) "study" else "home"
        }
        if (snapshot != null && route == "study" && (session == null || session.completed)) {
            route = if (snapshot.latestCompletedSession != null || session?.completed == true) "summary" else "home"
        }
    }
    val currentWord = model.words.find { it.uid == session?.current?.lexemeUid }
    DisposableEffect(player, route, currentWord?.uid) {
        onDispose { player.stop() }
    }
    fun play(word: Lexeme, settings: UserSettings) {
        player.speak(word, settings.ttsFallback, model::notify)
    }
    LaunchedEffect(route, session?.id, session?.position, currentWord?.uid) {
        if (route == "study" && session != null && currentWord != null) {
            val key = "${session.id}:${session.position}"
            val config = session.settings
            val shouldPlay = config.autoPlay == AutoPlayMode.ALL ||
                (config.autoPlay == AutoPlayMode.NEW_ONLY && session.current?.isNew == true)
            if (shouldPlay && autoPlayedKey != key) {
                autoPlayedKey = key
                play(currentWord, config)
            }
        }
    }

    fun openDetail(word: Lexeme) {
        detailFrom = route
        detailUid = word.uid
        route = "detail"
    }
    fun goBack() {
        if (route == "book_selection" && model.busy) return
        route = when (route) {
            "detail" -> detailFrom
            "book_selection" -> "settings"
            "settings" -> settingsFrom
            else -> "home"
        }
    }
    fun openSettings() {
        // Start a fresh form on a new visit; retain its draft during book selection.
        settingsStateHolder.removeState("settings")
        settingsFrom = route
        route = "settings"
    }
    BackHandler(enabled = route != "home" && route != "loading") { goBack() }

    val title = when (route) {
        "study" -> "Apprendre"
        "words" -> "词库 · Lexique"
        "stats" -> "学习统计"
        "settings" -> "学习设置"
        "book_selection" -> "选择词书"
        "detail" -> "词条详情"
        "summary" -> "本轮总结"
        else -> "Révision"
    }
    val mainRoute = route in listOf("home", "words", "stats")
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    if (!mainRoute && route != "loading") TextButton(
                        onClick = { goBack() }, modifier = Modifier.testTag("back"),
                        enabled = route != "book_selection" || !model.busy,
                    ) { Text(if (route == "study") "暂停" else "返回") }
                },
                actions = {
                    if (route == "home") TextButton(onClick = { browseBook = ""; route = "words" }) { Text("查词") }
                    if (route == "study") TextButton(
                        onClick = { openSettings() }, modifier = Modifier.testTag("nav_settings"),
                    ) { Text("设置") }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            if (mainRoute && snapshot != null) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf(Triple("home", "◉", "复习"), Triple("words", "≡", "词库"), Triple("stats", "▥", "统计"), Triple("settings", "⚙", "设置")).forEach { (destination, icon, label) ->
                    NavigationBarItem(
                        selected = route == destination,
                        onClick = { if (destination == "settings") openSettings() else route = destination },
                        icon = { Text(icon) }, label = { Text(label) },
                        modifier = Modifier.testTag("nav_$destination"),
                    )
                }
            } else if (route == "study" && session != null && currentWord != null && session.answerRevealed) {
                StudyActions(
                    busy = model.busy,
                    onRate = { model.rate(session.id, currentWord.uid, it) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (snapshot == null) {
                Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (model.loadFailed) {
                        Text("词库暂时无法打开")
                        Button(onClick = model::load) { Text("重新加载") }
                    } else {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("正在打开离线词库…")
                    }
                }
            } else when (route) {
                "home" -> HomeScreen(
                    snapshot, model.books, model.busy,
                    onStart = { model.start { updated ->
                        if (updated.session?.current != null) route = "study"
                        else model.notify("今天的任务已完成。可以浏览词库，或明天继续。")
                    } },
                    onBook = { browseBook = it; route = "words" },
                    onSummary = { route = "summary" },
                )
                "study" -> if (session != null && currentWord != null) key(session.id, currentWord.uid) {
                    StudyScreen(
                        currentWord, session, snapshot.favorites.contains(currentWord.uid), model.busy,
                        onPlay = { play(currentWord, session.settings) },
                        onFavorite = { model.favorite(currentWord.uid) },
                        onDetail = { openDetail(currentWord) },
                        onReveal = { model.reveal(session.id, currentWord.uid) },
                    )
                } else EmptyState("当前没有待学习词条", "返回首页查看今日任务")
                "words" -> BrowseScreen(model.words, model.books, snapshot, browseBook, { browseBook = it }, ::openDetail)
                "stats" -> StatisticsScreen(snapshot, model.words.size)
                "settings" -> settingsStateHolder.SaveableStateProvider("settings") {
                    SettingsScreen(snapshot, model.books, model.busy, session != null && !session.completed,
                        onChooseBook = { route = "book_selection" },
                        onSave = { settings ->
                            model.save(settings) { model.notify("设置已保存${if (session != null && !session.completed) "，从下一轮生效" else ""}"); goBack() }
                        },
                    )
                }
                "book_selection" -> BookSelectionScreen(snapshot, model.books, model.busy, session != null && !session.completed) { bookId ->
                    model.save(snapshot.settings.copy(bookId = bookId)) {
                        route = "settings"
                        model.notify("词书已保存${if (session != null && !session.completed) "，从下一轮生效" else ""}")
                    }
                }
                "detail" -> {
                    val word = model.words.find { it.uid == detailUid }
                    if (word != null) {
                        val config = if (detailFrom == "study") session?.settings ?: snapshot.settings else snapshot.settings
                        WordDetailScreen(word, config, snapshot.favorites.contains(word.uid), model.busy,
                            { model.favorite(word.uid) }, { play(word, config) })
                    } else EmptyState("未找到词条", "返回词库重新选择")
                }
                "summary" -> SummaryScreen(
                    snapshot.latestCompletedSession ?: session?.takeIf { it.completed }, model.words,
                    { route = "home" }, ::openDetail,
                )
            }
            if (model.busy && snapshot != null) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

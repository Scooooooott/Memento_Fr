package com.scott.memento.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.scott.memento.R
import com.scott.memento.ui.navigation.AppRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AppTopBar(route:AppRoute,languageContext:String?,busy:Boolean,onBack:()->Unit,onSettings:()->Unit,onCatalog:()->Unit) {
    val title=if(languageContext==null) R.string.app_name else when(route) {
        AppRoute.HOME->R.string.review_home
        AppRoute.LIBRARY,AppRoute.FAVORITES->R.string.library
        AppRoute.STATISTICS->R.string.statistics
        AppRoute.SETTINGS->R.string.settings
        AppRoute.STUDY->R.string.study_today
        AppRoute.DETAIL->R.string.word_detail_title
        AppRoute.IMPORT->R.string.import_title
        AppRoute.SUMMARY->R.string.ui2_summary_page_title
    }
    val hasBack=route !in setOf(AppRoute.HOME,AppRoute.LIBRARY,AppRoute.FAVORITES,AppRoute.STATISTICS)
    val hasAction=(route==AppRoute.HOME && languageContext!=null) || route==AppRoute.STUDY
    val backButton:@Composable ()->Unit={
        if(hasBack) TextButton(onClick=onBack,enabled=!busy,
            modifier=Modifier.heightIn(min=48.dp).testTag(when(route){AppRoute.STUDY->"study_pause";AppRoute.DETAIL->"detail_back";else->"app_back"})) {
                Text(stringResource(if(route==AppRoute.STUDY) R.string.pause_study else R.string.back))
        }
    }
    val actionButton:@Composable ()->Unit={
        if(route==AppRoute.HOME && languageContext!=null) TextButton(onClick=onCatalog,enabled=!busy,
            modifier=Modifier.heightIn(min=48.dp).testTag("top_catalog")){Text(stringResource(R.string.lookup))}
        if(route==AppRoute.STUDY) TextButton(onClick=onSettings,enabled=!busy,
            modifier=Modifier.heightIn(min=48.dp).testTag("nav_settings")){Text(stringResource(R.string.settings))}
    }
    // A fixed 64 dp toolbar clips the two-line title at large font scales. Its language
    // shortcut is also an action, so give it a real 48 dp region rather than a small text hitbox.
    Surface(color=MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
            val stacked=maxWidth<360.dp || LocalDensity.current.fontScale>1.3f
            Column(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=4.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                if(stacked) {
                    if(hasBack || hasAction) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,
                        verticalAlignment=Alignment.CenterVertically) {
                        Box {backButton()}
                        Box {actionButton()}
                    }
                    AppTitle(title,languageContext,busy,route,onSettings,Modifier.fillMaxWidth())
                } else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.width(64.dp),contentAlignment=Alignment.Center) {backButton()}
                    AppTitle(title,languageContext,busy,route,onSettings,Modifier.weight(1f))
                    Box(Modifier.width(64.dp),contentAlignment=Alignment.Center) {actionButton()}
                }
            }
        }
    }
}

@Composable
private fun AppTitle(title:Int,languageContext:String?,busy:Boolean,route:AppRoute,onSettings:()->Unit,modifier:Modifier) {
    Column(modifier.heightIn(min=48.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
        Text(stringResource(title),style=MaterialTheme.typography.titleMedium,textAlign=TextAlign.Center,
            modifier=Modifier.fillMaxWidth().semantics {heading()})
        if(languageContext!=null) TextButton(onClick=onSettings,
            enabled=!busy && route !in setOf(AppRoute.SETTINGS,AppRoute.IMPORT),
            modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("language_context"),contentPadding=PaddingValues(horizontal=4.dp,vertical=4.dp)) {
            Text(languageContext,style=MaterialTheme.typography.bodySmall,textAlign=TextAlign.Center)
        }
    }
}

@Composable fun MainNavigation(route:AppRoute,busy:Boolean,onNavigate:(AppRoute)->Unit) {
    NavigationBar(Modifier.fillMaxWidth().testTag("main_navigation")) {
        listOf(
            Triple(AppRoute.HOME,"◉",R.string.review_home),
            Triple(AppRoute.LIBRARY,"≡",R.string.library),
            Triple(AppRoute.STATISTICS,"▥",R.string.statistics),
            Triple(AppRoute.SETTINGS,"⚙",R.string.settings),
        ).forEach{(destination,icon,label)->
            NavigationBarItem(selected=route==destination || (destination==AppRoute.LIBRARY && route==AppRoute.FAVORITES),
                onClick={onNavigate(destination)},enabled=!busy,icon={Text(icon,Modifier.clearAndSetSemantics{})},label={Text(stringResource(label))},
                modifier=Modifier.testTag(when(destination){AppRoute.HOME->"nav_home";AppRoute.LIBRARY->"open_catalog";AppRoute.STATISTICS->"nav_statistics";else->"nav_settings"}))
        }
    }
}

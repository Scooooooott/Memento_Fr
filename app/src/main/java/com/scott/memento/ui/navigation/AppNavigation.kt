package com.scott.memento.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver

enum class AppRoute { HOME, LIBRARY, FAVORITES, STATISTICS, SETTINGS, STUDY, DETAIL, IMPORT, SUMMARY }

/** Only presentation state lives here; daily queues and revealed answers remain in SQLite. */
class AppNavigation {
    var route by mutableStateOf(AppRoute.HOME)
        private set
    var detailFrom by mutableStateOf(AppRoute.LIBRARY)
        private set
    var settingsFrom by mutableStateOf(AppRoute.HOME)
        private set
    var detailUid by mutableStateOf<String?>(null)
        private set
    var summaryPlanId by mutableStateOf<Long?>(null)
        private set
    var handledCompletion by mutableStateOf<String?>(null)
        private set

    fun open(destination:AppRoute) {
        if(destination==AppRoute.SETTINGS) settingsFrom=route
        if(destination !in setOf(AppRoute.SETTINGS,AppRoute.IMPORT,AppRoute.DETAIL)) detailUid=null
        if(destination !in setOf(AppRoute.SUMMARY,AppRoute.SETTINGS,AppRoute.IMPORT,AppRoute.DETAIL)) summaryPlanId=null
        route=destination
    }
    fun summary(planId:Long,completionToken:String?=null) {
        summaryPlanId=planId;detailUid=null;route=AppRoute.SUMMARY
        if(completionToken!=null) handledCompletion=completionToken
    }
    fun detail(uid:String) { detailFrom=route;detailUid=uid;route=AppRoute.DETAIL }
    fun back() {
        route=when(route) {
            AppRoute.DETAIL->detailFrom
            AppRoute.SETTINGS->settingsFrom
            AppRoute.IMPORT->AppRoute.SETTINGS
            else->AppRoute.HOME
        }
        if(route !in setOf(AppRoute.DETAIL,AppRoute.SETTINGS,AppRoute.IMPORT)) detailUid=null
    }
    fun targetChanged() {
        val destination=if(detailFrom==AppRoute.SUMMARY) AppRoute.HOME else detailFrom
        if(route==AppRoute.DETAIL) route=destination
        if(route==AppRoute.SUMMARY) route=AppRoute.HOME
        if(settingsFrom==AppRoute.DETAIL) settingsFrom=destination
        if(settingsFrom==AppRoute.SUMMARY) settingsFrom=AppRoute.HOME
        if(detailFrom==AppRoute.SUMMARY) detailFrom=AppRoute.HOME
        summaryPlanId=null
        detailUid=null
    }
    companion object {
        val Saver=listSaver<AppNavigation,String>(
            save={listOf(it.route.name,it.detailFrom.name,it.settingsFrom.name,it.detailUid.orEmpty(),it.summaryPlanId?.toString().orEmpty(),it.handledCompletion.orEmpty())},
            restore={values->AppNavigation().apply {
                route=AppRoute.valueOf(values[0]);detailFrom=AppRoute.valueOf(values[1]);settingsFrom=AppRoute.valueOf(values[2]);detailUid=values[3].ifEmpty{null}
                summaryPlanId=values.getOrNull(4)?.toLongOrNull();handledCompletion=values.getOrNull(5)?.ifEmpty{null}
            }},
        )
    }
}

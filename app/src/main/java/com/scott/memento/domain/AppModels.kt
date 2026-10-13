package com.scott.memento.domain

enum class AutoPlay { NEVER, NEW_ONLY, ALL }
data class PlaybackPreferences(val autoPlay:AutoPlay=AutoPlay.NEW_ONLY,val ttsFallback:Boolean=true,val showReading:Boolean=true)
data class Selection(val target:String,val learning:String,val registry:LanguageCapabilities=LanguageRegistry.formal) {
    init { require(registry.requireTargetId(target)==target); require(registry.requireLearningId(target,learning)==learning) }
}
data class AppPreferences(val selection:Selection?,val zone:String)
data class LanguagePreferences(val policy:StudyPolicy,val book:String,val playback:PlaybackPreferences)
data class HistoryDay(
    val date:String,val words:Int,val newWords:Int,val reviews:Int,val ratings:Int,val extraRatings:Int,
    val normalRatings:Int=(ratings-extraRatings).coerceAtLeast(0),val legacyRatings:Int=0,
)
/** Read projection only. Learned history remains complete even when its content is unavailable. */
data class LearningOverview(
    val learned:Set<String>,val favorites:Set<String>,val due:Int,val streak:Int,val days:List<HistoryDay>,
    val availableCount:Int=0,val availableLearnedCount:Int=learned.size,val unavailableLearnedCount:Int=0,
    val businessDate:String=days.firstOrNull()?.date.orEmpty(),val zone:String="",val deferredUntil:Long?=null,
)

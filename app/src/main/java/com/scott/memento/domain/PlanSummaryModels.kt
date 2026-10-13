package com.scott.memento.domain

/** Each value is a count of stored rating events, never a daily word quota. */
data class RatingDistribution(val again:Int=0,val hard:Int=0,val good:Int=0,val easy:Int=0) {
    val total:Int get()=again+hard+good+easy
}

/** Removed/unavailable entries are excluded from pending; offered entries have not been accepted. */
data class SummaryQueueCounts(
    val graded:Int=0,val pending:Int=0,val offered:Int=0,
    val newWords:Int=0,val reviewWords:Int=0,val ratings:RatingDistribution=RatingDistribution(),
) {
    val uniqueWords:Int get()=newWords+reviewWords
}

/** Imported events from the same business day are separate from this plan's ratings. */
data class LegacyDaySummary(
    val uniqueWords:Int=0,val newWords:Int=0,val reviewWords:Int=0,val ratingEvents:Int=0,
)

data class PlanSummary(
    val target:String,val planId:Long,val businessDate:String,val zone:String,val revision:Int,
    val planStatus:String,val completedAt:Long?,val closeReason:String?,
    val normal:SummaryQueueCounts,val extra:SummaryQueueCounts,val extraDecision:String?,
    val focusUids:List<String>,val legacy:LegacyDaySummary,
) {
    val normalGraded:Int get()=normal.graded
    val normalPending:Int get()=normal.pending
    val extraGraded:Int get()=extra.graded
    val extraPending:Int get()=extra.pending
    val newWords:Int get()=normal.newWords+extra.newWords
    val reviewWords:Int get()=normal.reviewWords+extra.reviewWords
    val uniqueWords:Int get()=newWords+reviewWords
    val ratingEvents:Int get()=normal.ratings.total+extra.ratings.total
    val isExtraInProgress:Boolean get()=extraDecision=="ACCEPTED" && extra.pending>0
    val isAwaitingExtraChoice:Boolean get()=normal.pending==0 && extraDecision=="PENDING" && extra.offered>0
    /** COMPLETED alone only means that the ordinary queue finished. */
    val isCompleted:Boolean get()=planStatus=="COMPLETED" && completedAt!=null && normal.pending==0 &&
        ratingEvents>0 && !isExtraInProgress && !isAwaitingExtraChoice
}

package com.scott.memento.domain

/** Read-only progress of the actual ordinary queue, independent of the configured target. */
data class HomeProgress(
    val normalGraded:Int=0,
    val normalNewRemaining:Int=0,
    val normalReviewRemaining:Int=0,
    val extraGraded:Int=0,
    /** Different words already charged to this business day outside this plan's graded items. */
    val carriedCompleted:Int=0,
) {
    val normalRemaining:Int get()=normalNewRemaining+normalReviewRemaining
    val ordinaryCompleted:Int get()=normalGraded+carriedCompleted
    val ordinaryTotal:Int get()=ordinaryCompleted+normalRemaining
    val fraction:Float get()=if(ordinaryTotal==0) 0f else ordinaryCompleted.toFloat()/ordinaryTotal
}

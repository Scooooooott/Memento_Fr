package com.scott.memento.domain

/** Immutable, language-scoped content snapshot; absent content never deletes learning history. */
data class StudyContentIndex(
    val target: String,
    val learning: String,
    val version: String,
    val available: Set<String>,
    val books: Map<String,List<String>>,
    val registry:LanguageCapabilities=LanguageRegistry.formal,
) {
    init {
        require(registry.requireTargetId(target)==target)
        require(registry.requireLearningId(target,learning)==learning)
        require(version.isNotBlank())
        available.forEach { registry.requireUid(target,it) }
        books.forEach { (id,uids) ->
            require(id.isNotBlank() && uids.distinct().size==uids.size)
            require(uids.all { it in available })
        }
    }
    // Display language is not a content revision and cannot regenerate the queue.
    val identity: String get()=version
}

enum class StudyGuard { READY, NOT_CONFIGURED, MIDNIGHT_CHOICE, CLOCK_ROLLBACK, WAIT_FOR_DATE }
enum class RolloverChoice { IMMEDIATE, DEFER }
enum class ScoreResult { APPLIED, ALREADY_APPLIED, STALE, NOT_REVEALED, OUT_OF_ORDER, BLOCKED, CONTENT_UNAVAILABLE }
data class StudyItem(val id:Long,val uid:String,val isNew:Boolean,val kind:String,val revealed:Boolean)
data class StudyStats(val uniqueWords:Int,val newWords:Int,val reviewWords:Int,val ratings:Int,val totalLearned:Int)
data class StudySnapshot(
    val guard:StudyGuard,
    val planId:Long?=null,
    val revision:Int=0,
    val businessDate:String?=null,
    val zone:String?=null,
    val policy:StudyPolicy?=null,
    val bookId:String?=null,
    val next:StudyItem?=null,
    val normalRemaining:Int=0,
    val extraRemaining:Int=0,
    val overflowChoice:Boolean=false,
    val extraDecision:String?=null,
    val stats:StudyStats=StudyStats(0,0,0,0,0),
) {
    fun request(target:String,requestId:String,registry:LanguageCapabilities=LanguageRegistry.formal):RatingRequest {
        check(guard==StudyGuard.READY)
        val item=checkNotNull(next)
        return RatingRequest(LearningIdentity.of(target,item.uid,registry),checkNotNull(planId),revision,item.id,requestId)
    }
}
data class ScoreOutcome(val result:ScoreResult,val snapshot:StudySnapshot)

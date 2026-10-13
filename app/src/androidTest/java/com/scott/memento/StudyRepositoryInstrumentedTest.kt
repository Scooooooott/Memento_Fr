package com.scott.memento

import android.database.sqlite.SQLiteException
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.memento.data.content.ContentCatalog
import com.scott.memento.data.user.StudyRepository
import com.scott.memento.data.user.UserDatabase
import com.scott.memento.domain.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class StudyRepositoryInstrumentedTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="memento-test-study.db"
    private lateinit var store:UserDatabase
    private lateinit var repo:StudyRepository
    private val clock=MutableClock()
    private val index=fixture()
    @Before fun start() {
        context.deleteDatabase(name);store=UserDatabase(context,name);repo=StudyRepository(store,clock)
        clock.at("2026-10-09T02:00:00Z")
        store.writableDatabase.execSQL("UPDATE app_settings SET study_zone_id='Asia/Shanghai'")
    }
    @After fun finish() {
        store.writableDatabase.rawQuery("PRAGMA foreign_key_check",null).use { assertFalse(it.moveToFirst()) }
        store.close();context.deleteDatabase(name)
    }
    private fun fixture(target:String="fr",version:String="fixture",learning:String="zh-Hans"):StudyContentIndex {
        val uids=(1..60).map { "$target:word$it:noun:1" }
        return StudyContentIndex(target,learning,version,uids.toSet(),mapOf("one" to uids.take(30),"two" to uids.drop(30)))
    }
    private fun configured(goal:Int=5,book:String="one",content:StudyContentIndex=index)=repo.configure(content,StudyPolicy(StudyMode.STANDARD,goal),book)
    private fun reviews(count:Int,target:String="fr",due:String="2026-10-09T12:00:00Z") {
        (1..count).forEach { number ->
            store.writableDatabase.execSQL("INSERT INTO learning_card VALUES(?,?,2.3,5,?, ?,1,0,'REVIEW',1)",
                arrayOf(target,"$target:word$number:noun:1",Instant.parse(due).toEpochMilli(),Instant.parse("2026-10-07T02:00:00Z").toEpochMilli()))
        }
    }
    private fun score(state:StudySnapshot,content:StudyContentIndex=index,rating:Rating=Rating.GOOD):ScoreOutcome {
        val req=state.request(content.target,UUID.randomUUID().toString())
        val reveal=repo.reveal(content,req)
        assertEquals(ScoreResult.APPLIED,reveal.result)
        return repo.rate(content,reveal.snapshot.request(content.target,req.requestId),rating)
    }
    private fun number(sql:String)=store.writableDatabase.rawQuery(sql,null).use{it.moveToFirst();it.getInt(0)}
    private fun text(sql:String)=store.writableDatabase.rawQuery(sql,null).use{it.moveToFirst();it.getString(0)}
    private fun all(state:StudySnapshot,content:StudyContentIndex=index):StudySnapshot {
        var current=state
        while(current.next!=null) { val result=score(current,content);assertEquals(ScoreResult.APPLIED,result.result);current=result.snapshot }
        return current
    }
    @Test fun stableRecoveryAndCompletionNeverIssueAnotherQuota() {
        val initial=configured()
        assertEquals(initial,repo.ensureDailyPlan(index))
        val finished=all(initial)
        assertEquals(5,finished.stats.uniqueWords);assertEquals(5,finished.stats.newWords)
        assertNull(finished.next);assertEquals(finished,StudyRepository(store,clock).ensureDailyPlan(index))
        assertEquals(1,number("SELECT COUNT(*) FROM daily_plan"))
    }
    @Test fun newDayReplansUnfinishedNewWordsAndClosesYesterdayWithoutCompletion() {
        var state=configured();state=score(state).snapshot
        val unfinished=state.next!!.uid
        clock.at("2026-10-10T02:00:00Z")
        val today=repo.ensureDailyPlan(index)
        assertNotEquals(state.planId,today.planId)
        assertEquals("CLOSED_INCOMPLETE",text("SELECT status FROM daily_plan WHERE id=${state.planId}"))
        assertEquals(1,number("SELECT COUNT(*) FROM daily_plan WHERE completed_at IS NULL AND status='CLOSED_INCOMPLETE'"))
        assertEquals(unfinished,today.next!!.uid)
    }
    @Test fun dueDateIncludesLaterTodayButNotTomorrowAndReviewsAcrossBooks() {
        reviews(2,due="2026-10-09T15:59:59Z")
        store.writableDatabase.execSQL("UPDATE learning_card SET due_at=? WHERE lexeme_uid='fr:word2:noun:1'",arrayOf(Instant.parse("2026-10-09T16:00:00Z").toEpochMilli()))
        val plan=configured(3,"two")
        assertEquals("fr:word1:noun:1",plan.next!!.uid);assertFalse(plan.next.isNew)
        val after=score(plan).snapshot
        assertEquals("fr:word31:noun:1",after.next!!.uid);assertTrue(after.next.isNew)
    }
    @Test fun overflowIsOptionalPersistentAndNeverContainsNewOrRepeatedWords() {
        reviews(7)
        val initial=configured(3)
        var done=all(initial)
        assertTrue(done.overflowChoice);assertEquals(4,done.extraRemaining);assertEquals(3,done.stats.uniqueWords)
        done=repo.chooseExtra(index,done.planId!!,true)
        assertEquals("EXTRA",done.next!!.kind);assertFalse(done.next.isNew)
        done=score(done,rating=Rating.AGAIN).snapshot
        assertEquals(3,done.extraRemaining)
        assertEquals(done,StudyRepository(store,clock).ensureDailyPlan(index))
        done=all(done)
        assertEquals(7,done.stats.uniqueWords);assertNull(done.next);assertFalse(done.overflowChoice)
        assertEquals(7,number("SELECT COUNT(DISTINCT lexeme_uid) FROM review_log"))
    }
    @Test fun resizingOverflowDoesNotReactivateRetiredExtraItems() {
        reviews(7)
        var state=all(configured(3))
        state=configured(6)
        assertEquals(3,state.normalRemaining)
        state=all(state)
        assertEquals(1,state.extraRemaining)
        state=repo.chooseExtra(index,state.planId!!,true)
        assertEquals(1,state.extraRemaining)
        state=all(state)
        assertEquals(7,state.stats.uniqueWords)
        assertEquals(7,number("SELECT COUNT(*) FROM review_log"))
    }
    @Test fun existingLegacyDayLogsUseDistinctWordsForQuotaAndSeparateRatingCount() {
        reviews(1)
        val now=clock.millis()
        repeat(2) { ordinal ->
            store.writableDatabase.execSQL("""INSERT INTO review_log(target_language,lexeme_uid,kind,rating,is_new,reviewed_at,business_date,zone_id,
                previous_stability,previous_difficulty,next_stability,next_difficulty,next_due_at,elapsed_days,scheduler_version,
                desired_retention,source_identity,source_record_id)
                VALUES('fr','fr:word1:noun:1','LEGACY','GOOD',?,?,'2026-10-09','Asia/Shanghai',0,0,2.3,5,?,0,'fixture',0.9,'legacy-fixture',?)""",
                arrayOf(if(ordinal==0) 1 else 0,now-1000+ordinal,now+86400000L,ordinal.toString()))
        }
        val state=configured(5)
        assertEquals(4,state.normalRemaining)
        assertEquals(StudyStats(1,1,0,2,1),state.stats)
        assertNotEquals("fr:word1:noun:1",state.next!!.uid)
        assertEquals(5,all(state).stats.uniqueWords)
    }
    @Test fun decliningOverflowSurvivesRestartAndReviewAllHasNoTarget() {
        reviews(7)
        val complete=all(configured(3))
        val declined=repo.chooseExtra(index,complete.planId!!,false)
        assertNull(declined.next);assertFalse(StudyRepository(store,clock).ensureDailyPlan(index).overflowChoice)
        val all=repo.configure(index,StudyPolicy(StudyMode.REVIEW_ALL,null),"one")
        assertNull(all.policy!!.dailyTarget);assertEquals(4,all.normalRemaining);assertFalse(all.next!!.isNew)
        assertEquals(7,this.all(all).stats.uniqueWords)
    }
    @Test fun reviewAllWithNoCardsHasNoNewOrArtificialGoal() {
        val state=repo.configure(index,StudyPolicy(StudyMode.REVIEW_ALL,null),"one")
        assertNull(state.next);assertNull(state.policy!!.dailyTarget);assertEquals(0,state.normalRemaining)
        assertEquals(state,repo.ensureDailyPlan(index))
    }
    @Test fun learningLanguageOnlyChangesDisplayAndRetainsQueueRevealAndQuota() {
        val state=configured(2)
        repo.reveal(index,state.request("fr","display"))
        val before=repo.ensureDailyPlan(index)
        val english=index.copy(learning="en")
        val after=repo.ensureDailyPlan(english)
        assertEquals(before,after);assertTrue(after.next!!.revealed)
        assertEquals(ScoreResult.APPLIED,repo.rate(english,after.request("fr","display"),Rating.GOOD).result)
    }
    @Test fun targetAboveAvailableNewUsesActualWordsAndDoesNotRepeatExhaustedBook() {
        var state=configured(Int.MAX_VALUE)
        assertEquals(30,state.normalRemaining)
        state=all(state)
        assertEquals(30,state.stats.uniqueWords);assertNull(repo.ensureDailyPlan(index).next)
    }
    @Test fun goalChangesApplyTodayPreserveSevenGradesAndOnlyAdjustDifference() {
        var state=configured(20)
        repeat(7) { state=score(state).snapshot }
        val old=state.request("fr","old-settings")
        state=configured(10);assertEquals(3,state.normalRemaining);assertEquals(7,state.stats.uniqueWords)
        assertEquals(ScoreResult.STALE,repo.reveal(index,old).result)
        state=configured(30);assertEquals(23,state.normalRemaining)
        state=configured(5);assertEquals(0,state.normalRemaining);assertNull(state.next)
        assertEquals(7,number("SELECT COUNT(*) FROM review_log"))
        assertEquals(1,number("SELECT COUNT(*) FROM daily_plan"))
    }
    @Test fun raisingFinishedGoalIsExplicitAndChangingBookOnlyReplacesUngradedNew() {
        reviews(1)
        var state=configured(3)
        state=score(state).snapshot
        state=score(state).snapshot
        state=configured(3,"two")
        assertEquals(1,state.normalRemaining);assertEquals("fr:word31:noun:1",state.next!!.uid)
        state=all(state);state=configured(5,"two")
        assertEquals(2,state.normalRemaining);assertEquals(3,state.stats.uniqueWords)
        assertEquals("fr:word32:noun:1",state.next!!.uid)
    }
    @Test fun newBookKeepsPendingReviewsFromOtherBooks() {
        reviews(2)
        val state=configured(4,"two")
        val switched=configured(4,"one")
        assertEquals(state.next!!.uid,switched.next!!.uid);assertFalse(switched.next.isNew)
        assertEquals(2,number("SELECT COUNT(*) FROM daily_plan_item WHERE state='PENDING' AND is_new=0"))
    }
    @Test fun unrevealedOutOfOrderWrongIdentityAndDuplicateRequestsCannotMutateTwice() {
        val state=configured(2)
        val request=state.request("fr","stable-request")
        assertEquals(ScoreResult.NOT_REVEALED,repo.rate(index,request,Rating.GOOD).result)
        val wrong=request.copy(identity=LearningIdentity.of("fr","fr:word60:noun:1"))
        assertEquals(ScoreResult.STALE,repo.reveal(index,wrong).result)
        val secondId=store.writableDatabase.rawQuery("SELECT id FROM daily_plan_item WHERE lexeme_uid='fr:word2:noun:1'",null).use{it.moveToFirst();it.getLong(0)}
        val outOfOrder=request.copy(identity=LearningIdentity.of("fr","fr:word2:noun:1"),itemId=secondId)
        assertEquals(ScoreResult.OUT_OF_ORDER,repo.reveal(index,outOfOrder).result)
        repo.reveal(index,request)
        assertEquals(ScoreResult.APPLIED,repo.rate(index,request,Rating.GOOD).result)
        assertEquals(ScoreResult.ALREADY_APPLIED,repo.rate(index,request,Rating.GOOD).result)
        assertEquals(ScoreResult.STALE,repo.rate(index,request,Rating.EASY).result)
        assertEquals(ScoreResult.ALREADY_APPLIED,repo.rate(index,request.copy(requestId="another"),Rating.GOOD).result)
        assertEquals(1,number("SELECT COUNT(*) FROM review_log"))
        assertEquals(1,number("SELECT repetitions FROM learning_card"))
    }
    @Test fun eachLanguageHasIndependentQuotaAndCannotSubmitOtherLanguageRequest() {
        val fr=configured(2)
        val esIndex=fixture("es")
        val es=configured(3,content=esIndex)
        assertNotEquals(fr.planId,es.planId)
        assertEquals(ScoreResult.STALE,repo.reveal(esIndex,fr.request("fr","cross-language")).result)
        assertEquals(2,all(fr).stats.uniqueWords)
        assertEquals(ScoreResult.STALE,repo.rate(esIndex,fr.request("fr","cross-language"),Rating.GOOD).result)
        assertEquals(0,repo.ensureDailyPlan(esIndex).stats.uniqueWords)
        assertEquals(3,all(es,esIndex).stats.uniqueWords)
    }
    @Test fun midnightNeedsChoiceAndImmediateChoiceInvalidatesOldRequest() {
        clock.at("2026-10-09T15:59:00Z")
        val state=configured();val old=state.request("fr","midnight")
        repo.reveal(index,old)
        clock.at("2026-10-09T16:01:00Z")
        assertEquals(StudyGuard.MIDNIGHT_CHOICE,repo.ensureDailyPlan(index).guard)
        assertEquals(ScoreResult.BLOCKED,repo.rate(index,old,Rating.GOOD).result)
        val today=repo.chooseRollover(index,state.planId!!,RolloverChoice.IMMEDIATE)
        assertEquals("2026-10-10",today.businessDate)
        assertEquals(ScoreResult.STALE,repo.rate(index,old,Rating.GOOD).result)
        assertEquals(0,number("SELECT COUNT(*) FROM review_log"))
    }
    @Test fun deferredReviewKeepsRealTimeAndYesterdayUntilExactlyTwoAM() {
        clock.at("2026-10-09T15:59:00Z")
        val state=configured(3)
        clock.at("2026-10-09T16:01:00Z")
        val deferred=repo.chooseRollover(index,state.planId!!,RolloverChoice.DEFER)
        assertEquals("2026-10-09",deferred.businessDate)
        clock.at("2026-10-09T17:59:59Z")
        var continued=repo.ensureDailyPlan(index);continued=score(continued).snapshot
        assertEquals("2026-10-09",text("SELECT business_date FROM review_log"))
        assertEquals("${clock.millis()}",text("SELECT reviewed_at FROM review_log"))
        val old=continued.request("fr","after-two")
        repo.reveal(index,old)
        clock.at("2026-10-09T18:00:00Z")
        val today=repo.ensureDailyPlan(index)
        assertEquals("2026-10-10",today.businessDate)
        assertEquals(ScoreResult.STALE,repo.rate(index,old,Rating.GOOD).result)
        assertEquals(1,number("SELECT COUNT(*) FROM review_log"))
    }
    @Test fun completedDayAndMultiDayOfflineRecoverWithoutMidnightPromptOrOldQueue() {
        val done=all(configured(2))
        clock.at("2026-10-09T16:01:00Z")
        assertEquals(StudyGuard.READY,repo.ensureDailyPlan(index).guard)
        clock.at("2026-10-15T02:00:00Z")
        val current=repo.ensureDailyPlan(index)
        assertEquals("2026-10-15",current.businessDate);assertNotEquals(done.planId,current.planId)
        assertEquals(3,number("SELECT COUNT(*) FROM daily_plan"))
    }
    @Test fun scoreFailureRollsBackCardLogQueueAndClockHighWater() {
        val state=configured(2);val req=state.request("fr","failing")
        repo.reveal(index,req)
        val oldClock=text("SELECT max_observed_at FROM study_clock")
        store.writableDatabase.execSQL("CREATE TRIGGER fail_log BEFORE INSERT ON review_log BEGIN SELECT RAISE(ABORT,'fixture failure'); END")
        clock.at("2026-10-09T02:01:00Z")
        assertThrows(SQLiteException::class.java){repo.rate(index,req,Rating.GOOD)}
        assertEquals(0,number("SELECT COUNT(*) FROM learning_card"));assertEquals(0,number("SELECT COUNT(*) FROM review_log"))
        assertEquals("PENDING",text("SELECT state FROM daily_plan_item WHERE id=${req.itemId}"))
        assertEquals(oldClock,text("SELECT max_observed_at FROM study_clock"))
        store.writableDatabase.execSQL("DROP TRIGGER fail_log")
        assertEquals(ScoreResult.APPLIED,repo.rate(index,req,Rating.GOOD).result)
    }
    @Test fun concurrentDuplicateThroughTwoHelpersCommitsOnlyOnce() {
        val state=configured(2);val req=state.request("fr","parallel")
        repo.reveal(index,req)
        val pool=Executors.newFixedThreadPool(2)
        try {
            UserDatabase(context,name).use { second ->
                val other=StudyRepository(second,clock)
                val results=pool.invokeAll(listOf(Callable{repo.rate(index,req,Rating.GOOD).result},Callable{other.rate(index,req,Rating.GOOD).result})).map{it.get()}
                assertEquals(setOf(ScoreResult.APPLIED,ScoreResult.ALREADY_APPLIED),results.toSet())
                assertEquals(1,number("SELECT COUNT(*) FROM review_log"))
            }
        } finally { pool.shutdownNow() }
    }
    @Test fun removedContentKeepsProgressInvalidatesRequestsAndCanRecover() {
        reviews(2)
        val state=configured(3);val old=state.request("fr","removed")
        val removed=index.copy(version="removed",available=index.available-"fr:word1:noun:1",books=index.books.mapValues{(_,v)->v.filter{it!="fr:word1:noun:1"}})
        val refreshed=repo.ensureDailyPlan(removed)
        assertNotEquals(state.revision,refreshed.revision)
        assertEquals(ScoreResult.STALE,repo.rate(removed,old,Rating.GOOD).result)
        assertEquals(2,number("SELECT COUNT(*) FROM learning_card"))
        assertEquals(0,number("SELECT content_available FROM learning_card WHERE lexeme_uid='fr:word1:noun:1'"))
        val restored=repo.ensureDailyPlan(index)
        assertEquals("fr:word1:noun:1",restored.next!!.uid)
    }
    @Test fun clockRollbackBlocksWithoutCreatingDateOrScoring() {
        val state=configured();val req=state.request("fr","rollback")
        repo.reveal(index,req)
        clock.at("2026-10-08T02:00:00Z")
        assertEquals(StudyGuard.CLOCK_ROLLBACK,repo.ensureDailyPlan(index).guard)
        assertEquals(ScoreResult.BLOCKED,repo.rate(index,req,Rating.GOOD).result)
        assertEquals(1,number("SELECT COUNT(*) FROM daily_plan"))
        clock.at("2026-10-09T02:00:00Z")
        assertEquals(ScoreResult.APPLIED,repo.rate(index,req,Rating.GOOD).result)
    }
    @Test fun zoneChangePreservesDayAndInvalidatesOldRequestWithoutExtraQuota() {
        val state=configured(3);val req=state.request("fr","zone")
        repo.changeZone("Pacific/Auckland")
        val same=repo.ensureDailyPlan(index)
        assertEquals(state.planId,same.planId);assertEquals("Asia/Shanghai",same.zone)
        assertEquals(ScoreResult.STALE,repo.reveal(index,req).result)
        clock.at("2026-10-09T18:00:00Z")
        val next=repo.ensureDailyPlan(index)
        assertEquals("Pacific/Auckland",next.zone);assertEquals("2026-10-10",next.businessDate)
        assertEquals(2,number("SELECT COUNT(*) FROM daily_plan"))
    }
    @Test fun westwardZoneDoesNotReopenPreviousDate() {
        val state=configured()
        repo.changeZone("America/Los_Angeles")
        clock.at("2026-10-09T18:00:00Z")
        assertEquals(StudyGuard.WAIT_FOR_DATE,repo.ensureDailyPlan(index).guard)
        assertEquals(1,number("SELECT COUNT(*) FROM daily_plan"))
        clock.at("2026-10-10T07:00:00Z")
        val next=repo.ensureDailyPlan(index)
        assertEquals("2026-10-10",next.businessDate);assertNotEquals(state.planId,next.planId)
    }
    @Test fun dstUsesLocalDateBoundariesInsteadOfFixedTwentyFourHours() {
        store.writableDatabase.execSQL("UPDATE app_settings SET study_zone_id='America/New_York'")
        clock.at("2026-03-08T05:00:00Z")
        configured()
        val start=Instant.parse("2026-03-08T05:00:00Z").toEpochMilli()
        assertEquals(start+23*3600000L,text("SELECT due_cutoff_at FROM daily_plan").toLong())
        clock.at("2026-03-09T04:30:00Z")
        assertEquals(StudyGuard.MIDNIGHT_CHOICE,repo.ensureDailyPlan(index).guard)
        val deferred=repo.chooseRollover(index,repo.ensureDailyPlan(index).planId!!,RolloverChoice.DEFER)
        assertEquals("2026-03-08",deferred.businessDate)
        clock.at("2026-03-09T06:00:00Z")
        assertEquals("2026-03-09",repo.ensureDailyPlan(index).businessDate)
    }
    @Test fun everyTransactionReadsTheClockOnlyOnce() {
        clock.reads=0
        val state=configured();assertEquals(1,clock.reads)
        clock.reads=0;repo.ensureDailyPlan(index);assertEquals(1,clock.reads)
        clock.reads=0;repo.reveal(index,state.request("fr","once"));assertEquals(1,clock.reads)
        clock.reads=0;repo.rate(index,state.request("fr","once"),Rating.GOOD);assertEquals(1,clock.reads)
    }
    @Test fun formalFrenchAndSpanishPackagesCreateAndScoreRealStableUids() {
        Languages.available.forEach { language ->
            ContentCatalog(context,language).use { catalog ->
                val content=catalog.studyIndex("zh-Hans")
                assertEquals(catalog.count(),content.available.size)
                val state=repo.configure(content,StudyPolicy(StudyMode.STANDARD,1),language.defaultBook)
                val uid=state.next!!.uid
                assertTrue(uid.startsWith("${language.id}:"));assertNotNull(catalog.detail(uid))
                val done=score(state,content).snapshot
                assertNull(done.next);assertEquals(1,done.stats.uniqueWords)
            }
        }
    }
    private class MutableClock:Clock() {
        private var value=Instant.parse("2026-10-09T02:00:00Z")
        var reads=0
        fun at(instant:String){value=Instant.parse(instant)}
        override fun getZone()=ZoneId.of("UTC")
        override fun withZone(zone:ZoneId):Clock=this
        override fun instant():Instant=value.also{reads++}
    }
}

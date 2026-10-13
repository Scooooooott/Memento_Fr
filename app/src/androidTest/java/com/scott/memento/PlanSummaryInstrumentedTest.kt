package com.scott.memento

import androidx.test.platform.app.InstrumentationRegistry
import com.scott.memento.data.user.PlanSummaryRepository
import com.scott.memento.data.user.StudyRepository
import com.scott.memento.data.user.UserDatabase
import com.scott.memento.domain.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class PlanSummaryInstrumentedTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="memento-test-plan-summary.db"
    private lateinit var store:UserDatabase
    private lateinit var study:StudyRepository
    private lateinit var summaries:PlanSummaryRepository
    private val clock=object:Clock() {
        var now=Instant.parse("2026-10-10T10:00:00Z")
        override fun getZone()=ZoneId.of("UTC")
        override fun withZone(zone:ZoneId):Clock=Clock.fixed(now,zone)
        override fun instant()=now
    }
    @Before fun before() {
        context.deleteDatabase(name)
        clock.now=Instant.parse("2026-10-10T10:00:00Z")
        store=UserDatabase(context,name)
        store.writableDatabase.execSQL("UPDATE app_settings SET study_zone_id='Asia/Shanghai'")
        study=StudyRepository(store,clock)
        summaries=PlanSummaryRepository(store)
    }
    @After fun after() {
        store.readableDatabase.rawQuery("PRAGMA foreign_key_check",null).use{assertFalse(it.moveToFirst())}
        store.close()
        context.deleteDatabase(name)
    }
    private fun index(target:String="fr",count:Int=10):StudyContentIndex {
        val uids=(1..count).map{"$target:word$it:noun:1"}
        return StudyContentIndex(target,"zh-Hans","summary-fixture",uids.toSet(),mapOf("book" to uids))
    }
    private fun configure(goal:Int=4,content:StudyContentIndex=index())=
        study.configure(content,StudyPolicy(StudyMode.STANDARD,goal),"book")
    private fun reviews(count:Int,target:String="fr") {
        repeat(count) { ordinal ->
            store.writableDatabase.execSQL("INSERT INTO learning_card VALUES(?,?,2.3,5,?,?,1,0,'REVIEW',1)",
                arrayOf(target,"$target:word${ordinal+1}:noun:1",clock.millis()-86400000L,clock.millis()-2*86400000L))
        }
    }
    private fun score(snapshot:StudySnapshot,rating:Rating=Rating.GOOD,content:StudyContentIndex=index()):StudySnapshot {
        val request=snapshot.request(content.target,UUID.randomUUID().toString())
        assertEquals(ScoreResult.APPLIED,study.reveal(content,request).result)
        val outcome=study.rate(content,request,rating)
        assertEquals(ScoreResult.APPLIED,outcome.result)
        return outcome.snapshot
    }
    private fun summary(snapshot:StudySnapshot,target:String="fr")=checkNotNull(summaries.current(target,snapshot.planId!!))
    private fun legacy(uid:String,record:String,date:String="2026-10-10",isNew:Boolean=false,rating:Rating=Rating.AGAIN) {
        store.writableDatabase.execSQL("""INSERT INTO review_log(target_language,lexeme_uid,kind,rating,is_new,
            reviewed_at,business_date,zone_id,previous_stability,previous_difficulty,next_stability,next_difficulty,
            next_due_at,elapsed_days,scheduler_version,desired_retention,source_identity,source_record_id)
            VALUES(?,?,'LEGACY',?,?,?,?,'Asia/Shanghai',0,0,2.3,5,?,0,'fixture',0.9,'summary-fixture',?)""",
            arrayOf(uid.substringBefore(':'),uid,rating.name,if(isNew) 1 else 0,clock.millis()-1000,date,
                clock.millis()+86400000L,record))
    }
    private fun count(sql:String)=store.readableDatabase.rawQuery(sql,null).use{it.moveToFirst();it.getInt(0)}

    @Test fun ordinaryCountsAndFourRatingsUseGradesNotReveals() {
        var plan=configure()
        val revealed=study.reveal(index(),plan.request("fr","revealed-not-scored")).snapshot
        assertEquals(summary(plan),summary(revealed))
        assertEquals(0,summary(plan).ratingEvents)
        assertEquals(4,summary(plan).normalPending)
        assertNull(summaries.latestCompleted("fr"))
        Rating.entries.forEach { rating -> plan=score(plan,rating) }
        val result=summary(plan)
        assertEquals(4,result.normalGraded)
        assertEquals(0,result.normalPending)
        assertEquals(4,result.newWords)
        assertEquals(0,result.reviewWords)
        assertEquals(4,result.uniqueWords)
        assertEquals(RatingDistribution(1,1,1,1),result.normal.ratings)
        assertEquals(RatingDistribution(),result.extra.ratings)
        assertEquals(listOf("fr:word1:noun:1","fr:word2:noun:1"),result.focusUids)
        assertTrue(result.isCompleted)
        assertEquals(result,summaries.latestCompleted("fr"))
    }

    @Test fun newAndReviewCountsAreDifferentWordCountsWithinThisPlan() {
        reviews(2)
        var plan=configure(goal=3)
        plan=score(plan,Rating.HARD)
        plan=score(plan,Rating.GOOD)
        plan=score(plan,Rating.EASY)
        val result=summary(plan)
        assertEquals(1,result.newWords)
        assertEquals(2,result.reviewWords)
        assertEquals(3,result.uniqueWords)
        assertEquals(3,result.ratingEvents)
        assertEquals(3,result.normalGraded)
        assertEquals(RatingDistribution(0,1,1,1),result.normal.ratings)
    }

    @Test fun ordinaryCompleteIsNotFullCompletionDuringExtraChoiceOrAcceptedQueue() {
        reviews(5)
        var plan=configure(goal=2)
        repeat(2){plan=score(plan,Rating.GOOD)}
        val ordinary=summary(plan)
        assertEquals("COMPLETED",ordinary.planStatus)
        assertEquals("PENDING",ordinary.extraDecision)
        assertEquals(3,ordinary.extra.offered)
        assertEquals(0,ordinary.extraPending)
        assertTrue(ordinary.isAwaitingExtraChoice)
        assertFalse(ordinary.isCompleted)
        assertNull(summaries.latestCompleted("fr"))
        plan=study.chooseExtra(index(),plan.planId!!,true)
        assertTrue(summary(plan).isExtraInProgress)
        assertEquals(3,summary(plan).extraPending)
        plan=score(plan,Rating.AGAIN)
        val partial=summary(plan)
        assertEquals(2,partial.normalGraded)
        assertEquals(1,partial.extraGraded)
        assertEquals(2,partial.extraPending)
        assertEquals(RatingDistribution(1,0,0,0),partial.extra.ratings)
        assertNull(summaries.latestCompleted("fr"))
        plan=score(plan,Rating.HARD)
        plan=score(plan,Rating.EASY)
        val complete=summary(plan)
        assertEquals(5,complete.reviewWords)
        assertEquals(0,complete.newWords)
        assertEquals(RatingDistribution(0,0,2,0),complete.normal.ratings)
        assertEquals(RatingDistribution(1,1,0,1),complete.extra.ratings)
        assertEquals(listOf("fr:word3:noun:1","fr:word4:noun:1"),complete.focusUids)
        assertTrue(complete.isCompleted)
        assertEquals(complete,summaries.latestCompleted("fr"))
    }

    @Test fun decliningExtraProducesAnOrdinarySummaryWithoutPretendingAllDueWordsFinished() {
        reviews(4)
        var plan=configure(goal=2)
        repeat(2){plan=score(plan,Rating.HARD)}
        plan=study.chooseExtra(index(),plan.planId!!,false)
        val result=summary(plan)
        assertEquals("DECLINED",result.extraDecision)
        assertEquals(2,result.normalGraded)
        assertEquals(0,result.extraGraded)
        assertEquals(0,result.extraPending)
        assertEquals(2,result.extra.offered)
        assertEquals(2,result.ratingEvents)
        assertTrue(result.isCompleted)
        assertFalse(result.isExtraInProgress)
        assertFalse(result.isAwaitingExtraChoice)
        assertEquals(result,summaries.latestCompleted("fr"))
        assertEquals(2,count("SELECT COUNT(*) FROM learning_card WHERE due_at<${clock.millis()}"))
    }

    @Test fun legacyDayEventsHaveDistinctCountsAndNeverEnterPlanDistributionOrFocus() {
        legacy("fr:word9:noun:1","same-one-new",isNew=true)
        legacy("fr:word9:noun:1","same-one-again")
        legacy("fr:word8:noun:1","previous-day","2026-10-09")
        legacy("es:word7:noun:1","other-target",isNew=true)
        var plan=configure(goal=2)
        assertEquals(1,plan.normalRemaining)
        plan=score(plan,Rating.GOOD)
        legacy("fr:word1:noun:1","duplicate-plan-word",rating=Rating.HARD)
        val result=summary(plan)
        assertEquals(1,result.normalGraded)
        assertEquals(1,result.newWords)
        assertEquals(1,result.ratingEvents)
        assertEquals(RatingDistribution(0,0,1,0),result.normal.ratings)
        assertTrue(result.focusUids.isEmpty())
        assertEquals(LegacyDaySummary(2,1,1,3),result.legacy)
        assertEquals(5,count("SELECT COUNT(*) FROM review_log WHERE kind='LEGACY'"))
    }

    @Test fun latestCompletedIsScopedByTargetAndSelectsLatestCompletedBusinessDay() {
        val old=score(configure(goal=1),Rating.HARD)
        val esIndex=index("es")
        val es=score(configure(goal=1,content=esIndex),Rating.EASY,esIndex)
        assertNull(summaries.current("es",old.planId!!))
        assertNull(summaries.current("fr",es.planId!!))
        assertNull(summaries.current("fr",Long.MAX_VALUE))
        assertEquals(old.planId,summaries.latestCompleted("fr")?.planId)
        assertEquals(es.planId,summaries.latestCompleted("es")?.planId)
        clock.now=Instant.parse("2026-10-11T10:00:00Z")
        var next=study.ensureDailyPlan(index())
        assertEquals(old.planId,summaries.latestCompleted("fr")?.planId)
        next=score(next,Rating.GOOD)
        assertEquals(next.planId,summaries.latestCompleted("fr")?.planId)
        assertEquals("2026-10-11",summaries.latestCompleted("fr")?.businessDate)
        assertEquals("2026-10-10",summary(old).businessDate)
        assertEquals(es.planId,summaries.latestCompleted("es")?.planId)
    }

    @Test fun crossDayClosedIncompleteRecordDoesNotBecomeACompletedSummary() {
        val old=score(configure(goal=3),Rating.AGAIN)
        clock.now=Instant.parse("2026-10-11T10:00:00Z")
        study.ensureDailyPlan(index())
        val record=summary(old)
        assertEquals("CLOSED_INCOMPLETE",record.planStatus)
        assertEquals("NEW_DAY",record.closeReason)
        assertNull(record.completedAt)
        assertEquals(1,record.ratingEvents)
        assertEquals(2,record.normalPending)
        assertFalse(record.isCompleted)
        assertNull(summaries.latestCompleted("fr"))
    }

    @Test fun emptyCompletedReviewAllPlanDoesNotDisplaceARealPreviousSummary() {
        val previous=score(configure(goal=1),Rating.GOOD)
        store.writableDatabase.execSQL("UPDATE learning_card SET due_at=?",arrayOf(clock.millis()+365L*86400000L))
        clock.now=Instant.parse("2026-10-11T10:00:00Z")
        val empty=study.configure(index(),StudyPolicy(StudyMode.REVIEW_ALL,null),"book")
        val result=summary(empty)
        assertEquals("COMPLETED",result.planStatus)
        assertEquals(0,result.ratingEvents)
        assertEquals(0,result.uniqueWords)
        assertFalse(result.isCompleted)
        assertEquals(previous.planId,summaries.latestCompleted("fr")?.planId)
    }

    @Test fun contentRemovalAndGoalRevisionPreserveScoredFocusIdsAndTrueCounts() {
        var plan=score(configure(goal=4),Rating.AGAIN)
        plan=configure(goal=1)
        assertEquals(2,plan.revision)
        assertEquals(1,summary(plan).normalGraded)
        assertEquals(0,summary(plan).normalPending)
        assertTrue(count("SELECT COUNT(*) FROM daily_plan_item WHERE state='REMOVED'")>0)
        val removed=index().let{it.copy(version="content-removed",available=it.available-"fr:word1:noun:1",
            books=it.books.mapValues{(_,uids)->uids.filter{it!="fr:word1:noun:1"}})}
        plan=study.ensureDailyPlan(removed)
        val result=summary(plan)
        assertEquals(listOf("fr:word1:noun:1"),result.focusUids)
        assertEquals(1,result.ratingEvents)
        assertEquals(1,result.newWords)
        assertEquals(plan.revision,result.revision)
        assertEquals(result,summaries.latestCompleted("fr"))
    }

    @Test fun queriesCannotWriteRefreshClockCreatePlansOrModifyAnyUserTable() {
        assertNull(summaries.latestCompleted("fr"))
        assertNull(summaries.current("es",1))
        assertEquals(0,count("SELECT COUNT(*) FROM daily_plan"))
        val plan=score(configure(goal=1),Rating.AGAIN)
        val expected=summary(plan)
        val tables=store.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",null).use { cursor ->
            buildList{while(cursor.moveToNext()) add(cursor.getString(0))}
        }
        tables.forEachIndexed { ordinal,table ->
            listOf("INSERT","UPDATE","DELETE").forEach { operation ->
                store.writableDatabase.execSQL("CREATE TEMP TRIGGER summary_read_${ordinal}_$operation BEFORE $operation ON \"$table\" BEGIN SELECT RAISE(ABORT,'summary must be read only'); END")
            }
        }
        clock.now=Instant.parse("2026-10-20T10:00:00Z")
        repeat(3) {
            assertEquals(expected,summary(plan))
            assertEquals(expected,summaries.latestCompleted("fr"))
            assertNull(summaries.latestCompleted("es"))
        }
        assertEquals(1,count("SELECT COUNT(*) FROM daily_plan"))
        assertEquals(1,count("SELECT COUNT(*) FROM review_log"))
        assertEquals(1,count("SELECT COUNT(*) FROM language_settings"))
        assertEquals(clock.now.minusSeconds(10*86400L).toEpochMilli(),store.readableDatabase.rawQuery(
            "SELECT max_observed_at FROM study_clock",null).use{it.moveToFirst();it.getLong(0)})
    }
}

package com.scott.memento

import androidx.test.platform.app.InstrumentationRegistry
import com.scott.memento.data.user.AppRepository
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

class HomeProgressInstrumentedTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="memento-test-home-progress.db"
    private lateinit var store:UserDatabase
    private lateinit var study:StudyRepository
    private lateinit var app:AppRepository
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
        app=AppRepository(store,study,clock)
    }

    @After fun after() {
        store.readableDatabase.rawQuery("PRAGMA foreign_key_check",null).use{assertFalse(it.moveToFirst())}
        store.close()
        context.deleteDatabase(name)
    }

    private fun index(target:String="fr",count:Int=10):StudyContentIndex {
        val uids=(1..count).map{"$target:word$it:noun:1"}
        return StudyContentIndex(target,"zh-Hans","fixture",uids.toSet(),mapOf("book" to uids))
    }

    private fun save(content:StudyContentIndex=index(),goal:Int=5)=
        app.save(content,StudyPolicy(StudyMode.STANDARD,goal),"book","Asia/Shanghai",PlaybackPreferences())

    private fun reviews(count:Int,target:String="fr") {
        (1..count).forEach { ordinal ->
            store.writableDatabase.execSQL("INSERT INTO learning_card VALUES(?,?,2.3,5,?,?,1,0,'REVIEW',1)",
                arrayOf(target,"$target:word$ordinal:noun:1",clock.millis(),clock.millis()-2*86400000L))
        }
    }

    private fun legacy(uid:String,record:String,date:String="2026-10-10",isNew:Boolean=false) {
        store.writableDatabase.execSQL("""INSERT INTO review_log(target_language,lexeme_uid,kind,rating,is_new,
                reviewed_at,business_date,zone_id,previous_stability,previous_difficulty,next_stability,next_difficulty,
                next_due_at,elapsed_days,scheduler_version,desired_retention,source_identity,source_record_id)
            VALUES(?,?,'LEGACY','GOOD',?,?,?,'Asia/Shanghai',0,0,2.3,5,?,0,'fixture',0.9,'home-fixture',?)""",
            arrayOf(uid.substringBefore(':'),uid,if(isNew) 1 else 0,clock.millis()-1000,date,clock.millis()+86400000L,record))
    }

    private fun score(snapshot:StudySnapshot,content:StudyContentIndex=index()):StudySnapshot {
        val request=snapshot.request(content.target,UUID.randomUUID().toString())
        val revealed=study.reveal(content,request)
        assertEquals(ScoreResult.APPLIED,revealed.result)
        val scored=study.rate(content,revealed.snapshot.request(content.target,request.requestId),Rating.GOOD)
        assertEquals(ScoreResult.APPLIED,scored.result)
        return scored.snapshot
    }

    private fun count(sql:String)=store.readableDatabase.rawQuery(sql,null).use{it.moveToFirst();it.getInt(0)}

    @Test fun normalProgressSeparatesPendingNewAndReviewAndOnlyCountsGrades() {
        reviews(2)
        val initial=save()
        assertEquals(HomeProgress(0,3,2,0,0),app.homeProgress(initial,"fr"))
        val revealed=study.reveal(index(),initial.request("fr","reveal-only")).snapshot
        assertEquals(app.homeProgress(initial,"fr"),app.homeProgress(revealed,"fr"))
        val scored=score(revealed)
        val progress=app.homeProgress(scored,"fr")
        assertEquals(HomeProgress(1,3,1,0,0),progress)
        assertEquals(4,progress.normalRemaining)
        assertEquals(1,progress.ordinaryCompleted)
        assertEquals(5,progress.ordinaryTotal)
        assertEquals(0.2f,progress.fraction,0.0001f)
    }

    @Test fun newWordShortageUsesActualQueueAndCompletionDoesNotIssueAnotherTarget() {
        val content=index(count=2)
        var plan=save(content,5)
        assertEquals(2,app.homeProgress(plan,"fr").ordinaryTotal)
        plan=score(plan,content)
        assertEquals(0.5f,app.homeProgress(plan,"fr").fraction,0.0001f)
        plan=score(plan,content)
        val complete=app.homeProgress(plan,"fr")
        assertEquals(HomeProgress(2,0,0,0,0),complete)
        assertEquals(1f,complete.fraction,0f)
        assertEquals(plan,study.ensureDailyPlan(content))
        assertEquals(complete,app.homeProgress(plan,"fr"))
        assertEquals(1,count("SELECT COUNT(*) FROM daily_plan"))
        assertEquals(2,count("SELECT COUNT(*) FROM review_log"))
    }

    @Test fun loweringGoalKeepsRealCompletedCountAndRaisingOnlyAddsDifference() {
        var plan=save(goal=9)
        repeat(7){plan=score(plan)}
        plan=save(goal=5)
        assertEquals(HomeProgress(7,0,0,0,0),app.homeProgress(plan,"fr"))
        assertEquals(7,app.homeProgress(plan,"fr").ordinaryTotal)
        assertEquals(1f,app.homeProgress(plan,"fr").fraction,0f)
        assertTrue(count("SELECT COUNT(*) FROM daily_plan_item WHERE state='REMOVED'")>0)
        plan=save(goal=10)
        assertEquals(HomeProgress(7,3,0,0,0),app.homeProgress(plan,"fr"))
        assertEquals(10,app.homeProgress(plan,"fr").ordinaryTotal)
        assertEquals(7,count("SELECT COUNT(*) FROM review_log"))
    }

    @Test fun removedAndUnavailableRowsAreNotRemainingWork() {
        var plan=score(save(goal=4))
        plan=save(goal=2)
        val missing=plan.next!!.uid
        val content=index().let{it.copy(version="missing",available=it.available-missing,
            books=it.books.mapValues{(_,uids)->uids.filter{uid->uid!=missing}})}
        plan=study.ensureDailyPlan(content)
        assertTrue(count("SELECT COUNT(*) FROM daily_plan_item WHERE state='REMOVED'")>0)
        assertTrue(count("SELECT COUNT(*) FROM daily_plan_item WHERE state='UNAVAILABLE'")>0)
        assertEquals(HomeProgress(1,1,0,0,0),app.homeProgress(plan,"fr"))
    }

    @Test fun reviewAllHasActualReviewsAndNoNewOrArtificialTarget() {
        reviews(4)
        var plan=app.save(index(),StudyPolicy(StudyMode.REVIEW_ALL,null),"book","Asia/Shanghai",PlaybackPreferences())
        assertNull(plan.policy!!.dailyTarget)
        assertEquals(HomeProgress(0,0,4,0,0),app.homeProgress(plan,"fr"))
        plan=score(plan)
        assertEquals(HomeProgress(1,0,3,0,0),app.homeProgress(plan,"fr"))
        assertEquals(0.25f,app.homeProgress(plan,"fr").fraction,0f)
        assertEquals(0,count("SELECT COUNT(*) FROM daily_plan_item WHERE is_new=1"))
    }

    @Test fun reviewAllWithNoDueCardsDoesNotInventProgress() {
        val plan=app.save(index(),StudyPolicy(StudyMode.REVIEW_ALL,null),"book","Asia/Shanghai",PlaybackPreferences())
        assertEquals(HomeProgress(),app.homeProgress(plan,"fr"))
        assertEquals(0f,app.homeProgress(plan,"fr").fraction,0f)
        assertNull(plan.next)
        assertEquals(0,count("SELECT COUNT(*) FROM daily_plan_item"))
    }

    @Test fun legacyDayQuotaCountsDistinctWordsAndExcludesCurrentGradedDuplicates() {
        reviews(1)
        legacy("fr:word1:noun:1","legacy-one-new",isNew=true)
        legacy("fr:word1:noun:1","legacy-one-again")
        legacy("fr:word2:noun:1","previous-day","2026-10-09")
        var plan=save()
        assertEquals(HomeProgress(0,4,0,0,1),app.homeProgress(plan,"fr"))
        assertEquals(5,app.homeProgress(plan,"fr").ordinaryTotal)
        val uid=plan.next!!.uid
        plan=score(plan)
        legacy(uid,"duplicate-of-current-grade")
        val progress=app.homeProgress(plan,"fr")
        assertEquals(HomeProgress(1,3,0,0,1),progress)
        assertEquals(2,progress.ordinaryCompleted)
        assertEquals(0.4f,progress.fraction,0.0001f)
    }

    @Test fun offeredAndGradedExtraReviewsDoNotIncreaseOrdinaryProgress() {
        reviews(5)
        var plan=save(goal=2)
        assertEquals(HomeProgress(0,0,2,0,0),app.homeProgress(plan,"fr"))
        repeat(2){plan=score(plan)}
        assertTrue(plan.overflowChoice)
        assertEquals(3,plan.extraRemaining)
        assertEquals(HomeProgress(2,0,0,0,0),app.homeProgress(plan,"fr"))
        plan=study.chooseExtra(index(),plan.planId!!,true)
        val extraUid=plan.next!!.uid
        plan=score(plan)
        legacy(extraUid,"duplicate-of-extra-grade")
        assertEquals(3,plan.stats.uniqueWords)
        val progress=app.homeProgress(plan,"fr")
        assertEquals(HomeProgress(2,0,0,1,0),progress)
        assertEquals(2,progress.ordinaryTotal)
        assertEquals(1f,progress.fraction,0f)
    }

    @Test fun projectionsAreScopedToPlanTargetAndBusinessDate() {
        val fr=score(save(goal=2))
        legacy("es:word4:noun:1","es-carried")
        val esIndex=index("es")
        var es=save(esIndex,3)
        repeat(2){es=score(es,esIndex)}
        assertEquals(HomeProgress(1,1,0,0,0),app.homeProgress(fr,"fr"))
        assertEquals(HomeProgress(2,0,0,0,1),app.homeProgress(es,"es"))
        assertEquals(HomeProgress(),app.homeProgress(fr,"es"))
        assertEquals(HomeProgress(),app.homeProgress(es,"fr"))
        assertEquals(HomeProgress(),app.homeProgress(fr.copy(businessDate="2026-10-09"),"fr"))
    }

    @Test fun projectionDoesNotConfigureOrRefreshOrWriteWhenClockAdvances() {
        assertEquals(HomeProgress(),app.homeProgress(StudySnapshot(StudyGuard.NOT_CONFIGURED),"fr"))
        assertNull(app.preferences().selection)
        assertEquals(0,count("SELECT COUNT(*) FROM daily_plan"))
        val plan=score(save(goal=3))
        val expected=app.homeProgress(plan,"fr")
        val tables=store.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",null).use { cursor ->
            buildList{while(cursor.moveToNext()) add(cursor.getString(0))}
        }
        // Any accidental ensure/configure/clock mutation must fail, even an update of the same value.
        tables.forEachIndexed { ordinal,table ->
            listOf("INSERT","UPDATE","DELETE").forEach { operation ->
                store.writableDatabase.execSQL("CREATE TEMP TRIGGER home_read_${ordinal}_$operation BEFORE $operation ON \"$table\" BEGIN SELECT RAISE(ABORT,'projection must be read only'); END")
            }
        }
        clock.now=Instant.parse("2026-10-12T10:00:00Z")
        repeat(3){assertEquals(expected,app.homeProgress(plan,"fr"))}
        assertEquals(HomeProgress(),app.homeProgress(null,"es"))
        assertEquals(1,count("SELECT COUNT(*) FROM daily_plan"))
        assertEquals(1,count("SELECT COUNT(*) FROM review_log"))
        assertEquals(Selection("fr","zh-Hans"),app.preferences().selection)
    }
}

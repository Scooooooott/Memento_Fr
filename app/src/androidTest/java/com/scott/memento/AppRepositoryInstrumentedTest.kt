package com.scott.memento

import androidx.test.platform.app.InstrumentationRegistry
import com.scott.memento.data.user.*
import com.scott.memento.domain.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class AppRepositoryInstrumentedTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="memento-test-app.db"
    private lateinit var store:UserDatabase
    private lateinit var study:StudyRepository
    private lateinit var app:AppRepository
    private val clock=object:Clock(){var now=Instant.parse("2026-10-10T10:00:00Z");override fun getZone()=ZoneId.of("UTC");override fun withZone(zone:ZoneId):Clock=Clock.fixed(now,zone);override fun instant()=now}
    private fun index(target:String="fr",learning:String="zh-Hans")=StudyContentIndex(target,learning,"fixture",(1..10).map{"$target:word$it:noun:1"}.toSet(),mapOf("book" to (1..10).map{"$target:word$it:noun:1"}))
    private fun save(content:StudyContentIndex=index(),goal:Int=2)=app.save(content,StudyPolicy(StudyMode.STANDARD,goal),"book","Asia/Shanghai",PlaybackPreferences())
    @Before fun before(){context.deleteDatabase(name);store=UserDatabase(context,name);store.writableDatabase.execSQL("UPDATE app_settings SET study_zone_id='Asia/Shanghai'");study=StudyRepository(store,clock);app=AppRepository(store,study,clock)}
    @After fun after(){store.readableDatabase.rawQuery("PRAGMA foreign_key_check",null).use{assertFalse(it.moveToFirst())};store.close();context.deleteDatabase(name)}
    private fun count(table:String)=store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table",null).use{it.moveToFirst();it.getInt(0)}
    @Test fun firstLaunchHasNoSelectionOrQuotaUntilExplicitSave() {
        assertNull(app.preferences().selection);assertEquals(0,count("daily_plan"))
        save();assertEquals(Selection("fr","zh-Hans"),app.preferences().selection);assertEquals(1,count("daily_plan"))
        assertEquals(2,app.language("fr")!!.policy.dailyTarget)
    }
    @Test fun firstChosenZoneAppliesBeforeCreatingTheFirstPlan() {
        store.writableDatabase.execSQL("UPDATE app_settings SET study_zone_id='UTC'")
        clock.now=Instant.parse("2026-10-10T23:30:00Z")
        val plan=save()
        assertEquals("Asia/Shanghai",plan.zone);assertEquals("2026-10-11",plan.businessDate)
    }
    @Test fun displayLanguageAndTargetSwitchPersistWithoutNewQuota() {
        val original=save();val revealed=study.reveal(index(),original.request("fr","reveal")).snapshot
        app.select(Selection("fr","en"));val translated=study.ensureDailyPlan(index(learning="en"))
        assertEquals(revealed,translated)
        app.select(Selection("es","fr"));assertEquals(StudyGuard.NOT_CONFIGURED,study.ensureDailyPlan(index("es","fr")).guard)
        assertEquals(1,count("daily_plan"));app.select(Selection("fr","en"));assertEquals(revealed,study.ensureDailyPlan(index(learning="en")))
        assertEquals(Selection("fr","en"),app.preferences().selection)
    }
    @Test fun failedSettingsCommitRollsBackPlanPairZoneAndPreferences() {
        store.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_pair BEFORE UPDATE OF onboarding_completed ON app_settings BEGIN SELECT RAISE(ABORT,'fixture failure'); END")
        try{save();fail("Expected abort")}catch(_:android.database.sqlite.SQLiteException){}
        assertNull(app.preferences().selection);assertEquals(0,count("language_settings"));assertEquals(0,count("daily_plan"));assertEquals(0,count("plan_revision"))
    }
    @Test fun sameDaySettingsPreserveScoredAndPlaybackAndBookPreferences() {
        var plan=save()
        val request=plan.request("fr",UUID.randomUUID().toString());plan=study.reveal(index(),request).snapshot
        plan=study.rate(index(),plan.request("fr",request.requestId),Rating.GOOD).snapshot
        val prefs=PlaybackPreferences(AutoPlay.NEVER,false,false)
        plan=app.save(index(learning="en"),StudyPolicy(StudyMode.STANDARD,4),"book","Asia/Shanghai",prefs)
        assertEquals(1,plan.stats.uniqueWords);assertEquals(3,plan.normalRemaining);assertEquals(prefs,app.language("fr")!!.playback)
        assertEquals(1,count("review_log"));assertEquals(1,count("daily_plan"))
    }
    @Test fun favoritesAndHistoricalStatisticsAreTargetScopedAndUseBusinessDate() {
        var plan=save()
        val request=plan.request("fr","score");plan=study.reveal(index(),request).snapshot
        plan=study.rate(index(),plan.request("fr",request.requestId),Rating.GOOD).snapshot
        app.toggleFavorite("fr","fr:word1:noun:1");app.toggleFavorite("es","es:word1:noun:1")
        clock.now=Instant.parse("2026-10-10T16:30:00Z")
        plan=study.chooseRollover(index(),plan.planId!!,RolloverChoice.DEFER)
        val next=plan.request("fr","late");plan=study.reveal(index(),next).snapshot;plan=study.rate(index(),plan.request("fr","late"),Rating.GOOD).snapshot
        val overview=app.overview(index(),plan)
        assertEquals("2026-10-10",overview.days.first().date);assertEquals(2,overview.days.first().words);assertEquals(2,overview.days.first().ratings)
        assertEquals(setOf("fr:word1:noun:1"),overview.favorites);assertEquals(1,overview.streak)
        assertEquals(setOf("es:word1:noun:1"),app.overview(index("es"),null).favorites)
    }
    @Test fun midnightGuardCannotPartiallySaveSettings() {
        val original=save();clock.now=Instant.parse("2026-10-10T16:30:00Z")
        try{save(goal=8);fail("Expected midnight guard")}catch(_:IllegalStateException){}
        assertEquals(2,app.language("fr")!!.policy.dailyTarget)
        assertEquals(original.revision,study.ensureDailyPlan(index()).revision)
    }
}

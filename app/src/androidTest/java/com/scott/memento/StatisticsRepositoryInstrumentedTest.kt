package com.scott.memento

import android.os.Build
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class StatisticsRepositoryInstrumentedTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="memento-test-ui6-statistics-repository.db"
    private lateinit var store:UserDatabase
    private lateinit var study:StudyRepository
    private lateinit var app:AppRepository
    private val clock=object:Clock() {
        var now=Instant.parse("2026-10-10T04:00:00Z")
        override fun getZone()=ZoneId.of("UTC")
        override fun withZone(zone:ZoneId):Clock=Clock.fixed(now,zone)
        override fun instant()=now
    }
    private fun property(key:String)=ProcessBuilder("/system/bin/getprop",key).start().let {process->
        process.inputStream.bufferedReader().use {it.readText().trim()}.also {check(process.waitFor()==0)}
    }
    @Before fun before() {
        check(context.packageName=="com.scott.memento")
        check(Build.HARDWARE in setOf("ranchu","goldfish") || Build.FINGERPRINT.contains("generic"))
        check(property("ro.kernel.qemu")=="1")
        check(listOf("ro.boot.qemu.avd_name","ro.kernel.qemu.avd_name").any {property(it)=="MementoApi35"})
        check(name.startsWith("memento-test-"))
        context.deleteDatabase(name)
        clock.now=Instant.parse("2026-10-10T04:00:00Z")
        store=UserDatabase(context,name)
        store.writableDatabase.execSQL("UPDATE app_settings SET study_zone_id='Asia/Shanghai'")
        study=StudyRepository(store,clock)
        app=AppRepository(store,study,clock)
    }
    @After fun after() {
        if(::store.isInitialized) {
            store.readableDatabase.rawQuery("PRAGMA foreign_key_check",null).use {assertFalse(it.moveToFirst())}
            store.close()
            context.deleteDatabase(name)
        }
    }
    private fun uid(number:Int,target:String="fr")="$target:stats$number:noun:1"
    private fun index(target:String="fr")=StudyContentIndex(target,"zh-Hans","fixture",
        (1..6).map {uid(it,target)}.toSet(),mapOf("book" to (1..6).map {uid(it,target)}))
    private fun configure(content:StudyContentIndex=index(),goal:Int=3)=
        app.save(content,StudyPolicy(StudyMode.STANDARD,goal),"book","Asia/Shanghai",PlaybackPreferences())
    private fun card(number:Int,target:String="fr",due:Long=clock.millis()-1000,reviewed:Long?=clock.millis()-86400000,
        repetitions:Int=1,available:Int=1) {
        store.writableDatabase.execSQL("INSERT INTO learning_card VALUES(?,?,2,5,?,?,?,0,'REVIEW',?)",
            arrayOf(target,uid(number,target),due,reviewed,repetitions,available))
    }
    private fun legacy(number:Int,record:String,target:String="fr",date:String="2026-10-10",isNew:Boolean=false) {
        store.writableDatabase.execSQL("""INSERT INTO review_log(target_language,lexeme_uid,kind,rating,is_new,
            reviewed_at,business_date,zone_id,previous_stability,previous_difficulty,next_stability,next_difficulty,
            next_due_at,elapsed_days,scheduler_version,desired_retention,source_identity,source_record_id)
            VALUES(?,?,'LEGACY','GOOD',?,?,?,'Asia/Shanghai',0,0,2,5,?,0,'fixture',0.9,'statistics-fixture',?)""",
            arrayOf(target,uid(number,target),if(isNew) 1 else 0,clock.millis(),date,clock.millis()+86400000,record))
    }
    private fun score(snapshot:StudySnapshot,content:StudyContentIndex=index()):StudySnapshot {
        val request=snapshot.request(content.target,UUID.randomUUID().toString())
        val revealed=study.reveal(content,request)
        assertEquals(ScoreResult.APPLIED,revealed.result)
        val result=study.rate(content,revealed.snapshot.request(content.target,request.requestId),Rating.GOOD)
        assertEquals(ScoreResult.APPLIED,result.result)
        return result.snapshot
    }
    private fun allTables():Map<String,List<String>> {
        val names=store.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",null).use {cursor->
            buildList {while(cursor.moveToNext()) add(cursor.getString(0))}
        }
        return names.associateWith {name->store.readableDatabase.rawQuery("SELECT * FROM \"$name\" ORDER BY rowid",null).use {cursor->
            buildList {while(cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") {if(cursor.isNull(it)) "<null>" else cursor.getString(it)})}
        }}
    }

    @Test fun repeatedUidCountsOnceWhileNormalExtraAndLegacyRatingsStaySeparate() {
        (1..3).forEach {card(it)}
        var snapshot=score(configure(goal=1))
        snapshot=study.chooseExtra(index(),snapshot.planId!!,true)
        snapshot=score(snapshot)
        legacy(1,"repeat-one",isNew=true)
        legacy(1,"repeat-two")
        legacy(4,"other")
        val expected=HistoryDay("2026-10-10",3,1,2,5,1,1,3)
        assertEquals(expected,app.overview(index(),snapshot).days.first())
        assertEquals(5,expected.normalRatings+expected.extraRatings+expected.legacyRatings)
    }
    @Test fun coverageIntersectsAuthoritativeLearnedAndAvailableWithoutDeletingMissingHistory() {
        card(1,repetitions=0)
        card(2,reviewed=null,repetitions=9)
        card(7,available=0)
        store.writableDatabase.execSQL("INSERT INTO favorites VALUES('fr',?)",arrayOf(uid(7)))
        val before=allTables()
        val overview=app.overview(index(),StudySnapshot(StudyGuard.NOT_CONFIGURED,businessDate="2026-10-10",zone="Asia/Shanghai"))
        assertEquals(setOf(uid(1),uid(7)),overview.learned)
        assertEquals(6,overview.availableCount)
        assertEquals(1,overview.availableLearnedCount)
        assertEquals(1,overview.unavailableLearnedCount)
        assertEquals(1,overview.due)
        assertEquals(setOf(uid(7)),overview.favorites)
        assertEquals(before,allTables())
    }
    @Test fun languageAndLearningDisplayAreIsolatedAndReadingDoesNotReviseCurrentQuota() {
        val fr=score(configure(goal=2))
        card(1,"es")
        legacy(2,"spanish-only","es",isNew=true)
        val es=configure(index("es"),goal=4)
        val before=allTables()
        val french=app.overview(index(),fr)
        assertEquals(french,app.overview(index().copy(learning="en"),fr))
        val spanish=app.overview(index("es"),es)
        assertEquals(setOf(uid(1)),french.learned)
        assertEquals(setOf(uid(1,"es")),spanish.learned)
        assertEquals(1,french.days.first().normalRatings)
        assertEquals(0,spanish.days.first().normalRatings)
        assertEquals(1,spanish.days.first().legacyRatings)
        assertEquals(before,allTables())
    }
    @Test fun midnightDeferredRatingBelongsToPreviousBusinessDateAndTwoAmReplacesWindow() {
        clock.now=Instant.parse("2026-10-10T15:59:00Z")
        var snapshot=score(configure(goal=3))
        clock.now=Instant.parse("2026-10-10T16:00:01Z")
        snapshot=study.chooseRollover(index(),snapshot.planId!!,RolloverChoice.DEFER)
        clock.now=Instant.parse("2026-10-10T17:59:59Z")
        snapshot=score(snapshot)
        store.writableDatabase.execSQL("UPDATE app_settings SET study_zone_id='Europe/Paris'")
        val grace=app.overview(index(),snapshot)
        assertEquals("2026-10-10",grace.businessDate)
        assertEquals("Asia/Shanghai",grace.zone)
        assertEquals(2,grace.days.first().words)
        assertEquals(Instant.parse("2026-10-10T18:00:00Z").toEpochMilli(),grace.deferredUntil)
        val stale=snapshot.request("fr","after-cutoff")
        clock.now=Instant.parse("2026-10-10T18:00:00Z")
        // Restore the selected zone so this checks 02:00, rather than a separate zone-transition rule.
        store.writableDatabase.execSQL("UPDATE app_settings SET study_zone_id='Asia/Shanghai'")
        assertNotEquals(ScoreResult.APPLIED,study.reveal(index(),stale).result)
        val current=study.ensureDailyPlan(index())
        val next=app.overview(index(),current)
        assertEquals("2026-10-11",next.businessDate)
        assertEquals(0,next.days.first().words)
        assertEquals(2,next.days[1].words)
        assertNull(next.deferredUntil)
    }
    @Test fun overviewIsReadOnlyAndFrozenSnapshotDoesNotRefreshEvenIfClockAdvances() {
        val snapshot=score(configure())
        val expected=app.overview(index(),snapshot)
        val before=allTables()
        before.keys.forEachIndexed {ordinal,table->
            listOf("INSERT","UPDATE","DELETE").forEach {operation->store.writableDatabase.execSQL(
                "CREATE TEMP TRIGGER stats_read_${ordinal}_$operation BEFORE $operation ON \"$table\" BEGIN SELECT RAISE(ABORT,'statistics must be read only'); END")}
        }
        clock.now=Instant.parse("2026-11-01T04:00:00Z")
        repeat(2) {assertEquals(expected,app.overview(index(),snapshot))}
        assertEquals(before,allTables())
    }
    @Test fun thirtyDayWindowHasZerosAndOnlyBusinessDatesInRangeAffectHistoryOrStreak() {
        legacy(1,"old","fr","2026-09-10")
        legacy(2,"boundary","fr","2026-09-11")
        legacy(3,"yesterday","fr","2026-10-09")
        legacy(4,"future","fr","2026-10-11")
        val overview=app.overview(index(),null)
        assertEquals(30,overview.days.size)
        assertEquals("2026-10-10",overview.days.first().date)
        assertEquals("2026-09-11",overview.days.last().date)
        assertEquals(1,overview.streak)
        assertEquals(0,overview.days.first().ratings)
        assertEquals(2,overview.days.sumOf {it.ratings})
        assertEquals(0,overview.days.sumOf {it.normalRatings+it.extraRatings})
    }
    @Test fun dueDateCutoffUsesFrozenZoneIncludingDaylightSavingDayRatherThanFixedHours() {
        val date=LocalDate.parse("2026-10-25")
        val cutoff=date.plusDays(1).atStartOfDay(ZoneId.of("Europe/Paris")).toInstant().toEpochMilli()
        card(1,due=cutoff-1)
        card(2,due=cutoff)
        val overview=app.overview(index(),StudySnapshot(StudyGuard.READY,businessDate=date.toString(),zone="Europe/Paris"))
        assertEquals(1,overview.due)
        assertEquals("Europe/Paris",overview.zone)
    }
}

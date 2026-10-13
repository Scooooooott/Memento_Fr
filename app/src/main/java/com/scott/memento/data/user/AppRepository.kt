package com.scott.memento.data.user

import android.database.Cursor
import com.scott.memento.domain.*
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** Called on IO under the app controller's single mutation lock. No content database is written. */
class AppRepository(private val store:UserDatabase,private val study:StudyRepository,private val clock:Clock=Clock.systemUTC(),private val registry:LanguageCapabilities=LanguageRegistry.formal) {
    fun preferences():AppPreferences=rows("SELECT onboarding_completed,target_language,learning_language,study_zone_id FROM app_settings WHERE id=1") {
        AppPreferences(if(getInt(0)==1) Selection(getString(1),getString(2),registry) else null,ZoneId.of(getString(3)).id)
    }.single()
    fun language(target:String):LanguagePreferences? {
        registry.requireTargetId(target)
        return rows("SELECT study_mode,daily_target,book_id,auto_play,tts_fallback,show_reading FROM language_settings WHERE target_language=?",arrayOf(target)) {
            LanguagePreferences(StudyPolicy(StudyMode.valueOf(getString(0)),if(isNull(1)) null else getInt(1)),getString(2),
                PlaybackPreferences(AutoPlay.valueOf(getString(3)),getInt(4)==1,getInt(5)==1))
        }.firstOrNull()
    }
    /** Pair, plan revision, zone and playback preferences commit together, or all roll back. */
    fun save(index:StudyContentIndex,policy:StudyPolicy,book:String,zone:String,playback:PlaybackPreferences):StudySnapshot {
        val selection=Selection(index.target,index.learning,registry)
        registry.requireLearningId(index.target,index.learning)
        val validZone=ZoneId.of(zone).id
        val db=store.writableDatabase
        db.beginTransaction()
        try {
            // A first plan must use the zone chosen in onboarding. Existing plans keep their frozen zone.
            study.changeZone(validZone)
            val configured=study.configure(index,policy,book)
            check(configured.guard==StudyGuard.READY) { "请先返回首页处理日期提示，再保存设置。" }
            db.execSQL("UPDATE language_settings SET auto_play=?,tts_fallback=?,show_reading=? WHERE target_language=?",
                arrayOf(playback.autoPlay.name,if(playback.ttsFallback) 1 else 0,if(playback.showReading) 1 else 0,index.target))
            db.execSQL("UPDATE app_settings SET onboarding_completed=1,target_language=?,learning_language=?,revision=revision+1 WHERE id=1",
                arrayOf(selection.target,selection.learning))
            val snapshot=study.ensureDailyPlan(index)
            db.setTransactionSuccessful()
            return snapshot
        } finally { db.endTransaction() }
    }
    /** Display-only selection changes do not configure a new quota. Unconfigured targets stay unconfigured. */
    fun select(selection:Selection) {
        registry.requireLearningId(selection.target,selection.learning)
        check(preferences().selection!=null) { "请先完成首次设置" }
        store.writableDatabase.execSQL("UPDATE app_settings SET target_language=?,learning_language=?,revision=revision+1 WHERE id=1",arrayOf(selection.target,selection.learning))
    }
    fun toggleFavorite(target:String,uid:String) {
        registry.requireUid(target,uid)
        val db=store.writableDatabase
        db.beginTransaction()
        try {
            if(db.delete("favorites","target_language=? AND lexeme_uid=?",arrayOf(target,uid))==0)
                db.execSQL("INSERT INTO favorites(target_language,lexeme_uid) VALUES(?,?)",arrayOf(target,uid))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun learned(target:String):Set<String> {
        registry.requireTargetId(target)
        return rows("SELECT lexeme_uid FROM learning_card WHERE target_language=? AND last_review_at IS NOT NULL",arrayOf(target)){getString(0)}.toSet()
    }
    fun favorites(target:String):Set<String> {
        registry.requireTargetId(target)
        return rows("SELECT lexeme_uid FROM favorites WHERE target_language=? ORDER BY lexeme_uid",arrayOf(target)){getString(0)}.toSet()
    }
    /** A single read of the supplied plan; never refreshes the clock, queue, settings or quota. */
    fun homeProgress(snapshot:StudySnapshot?,target:String):HomeProgress {
        registry.requireTargetId(target)
        val planId=snapshot?.planId ?: return HomeProgress()
        val date=snapshot.businessDate ?: return HomeProgress()
        return rows("""SELECT
                SUM(CASE WHEN item.queue_kind='NORMAL' AND item.state='GRADED' THEN 1 ELSE 0 END),
                SUM(CASE WHEN item.queue_kind='NORMAL' AND item.state='PENDING' AND item.is_new=1 THEN 1 ELSE 0 END),
                SUM(CASE WHEN item.queue_kind='NORMAL' AND item.state='PENDING' AND item.is_new=0 THEN 1 ELSE 0 END),
                SUM(CASE WHEN item.queue_kind='EXTRA' AND item.state='GRADED' THEN 1 ELSE 0 END),
                (SELECT COUNT(DISTINCT log.lexeme_uid) FROM review_log log
                    WHERE log.target_language=plan.target_language AND log.business_date=plan.business_date
                    AND NOT EXISTS (SELECT 1 FROM daily_plan_item graded
                        WHERE graded.plan_id=plan.id AND graded.target_language=plan.target_language
                        AND graded.state='GRADED' AND graded.queue_kind IN('NORMAL','EXTRA')
                        AND graded.lexeme_uid=log.lexeme_uid))
            FROM daily_plan plan LEFT JOIN daily_plan_item item
                ON item.plan_id=plan.id AND item.target_language=plan.target_language
            WHERE plan.id=? AND plan.target_language=? AND plan.business_date=?
            GROUP BY plan.id,plan.target_language,plan.business_date""",arrayOf(planId.toString(),target,date)) {
            HomeProgress(getInt(0),getInt(1),getInt(2),getInt(3),getInt(4))
        }.firstOrNull() ?: HomeProgress()
    }
    fun overview(index:StudyContentIndex,snapshot:StudySnapshot?):LearningOverview {
        val target=index.target
        val zone=snapshot?.zone ?: preferences().zone
        val date=LocalDate.parse(snapshot?.businessDate ?: LocalDate.now(clock.withZone(ZoneId.of(zone))).toString())
        val learned=learned(target)
        val favorites=favorites(target)
        val cutoff=date.plusDays(1).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()
        val due=rows("SELECT lexeme_uid FROM learning_card WHERE target_language=? AND last_review_at IS NOT NULL AND due_at<?",arrayOf(target,cutoff.toString())) {getString(0)}.count{it in index.available}
        val activity=rows("SELECT DISTINCT business_date FROM review_log WHERE target_language=? AND business_date<=? ORDER BY business_date DESC",arrayOf(target,date.toString())){getString(0)}.toSet()
        var day=if(date.toString() in activity) date else date.minusDays(1)
        var streak=0
        while(day.toString() in activity) { streak++; day=day.minusDays(1) }
        val counts=rows("""SELECT business_date,COUNT(*),SUM(is_new),COUNT(*)-SUM(is_new),SUM(ratings),SUM(extra),SUM(normal),SUM(legacy)
            FROM (SELECT business_date,lexeme_uid,MAX(is_new) is_new,COUNT(*) ratings,
                SUM(CASE WHEN kind='EXTRA' THEN 1 ELSE 0 END) extra,
                SUM(CASE WHEN kind='NORMAL' THEN 1 ELSE 0 END) normal,
                SUM(CASE WHEN kind='LEGACY' THEN 1 ELSE 0 END) legacy
                FROM review_log WHERE target_language=? AND business_date>=? AND business_date<=? GROUP BY business_date,lexeme_uid)
            GROUP BY business_date""",arrayOf(target,date.minusDays(29).toString(),date.toString())) {
            HistoryDay(getString(0),getInt(1),getInt(2),getInt(3),getInt(4),getInt(5),getInt(6),getInt(7))
        }.associateBy{it.date}
        val days=(0L..29L).map{date.minusDays(it).toString()}.map{counts[it] ?: HistoryDay(it,0,0,0,0,0)}
        val availableLearned=learned.count {it in index.available}
        val deferredUntil=snapshot?.planId?.let {planId->rows(
            "SELECT defer_until FROM rollover_decision WHERE plan_id=? AND target_language=? AND choice='DEFER' AND defer_until>?",
            arrayOf(planId.toString(),target,clock.millis().toString())) {getLong(0)}.firstOrNull()}
        return LearningOverview(learned,favorites,due,streak,days,index.available.size,availableLearned,
            learned.size-availableLearned,date.toString(),zone,deferredUntil)
    }
    private fun <T> rows(sql:String,args:Array<String> = emptyArray(),mapper:Cursor.()->T):List<T> = store.readableDatabase.rawQuery(sql,args).use {c->buildList{while(c.moveToNext()) add(c.mapper())}}
}

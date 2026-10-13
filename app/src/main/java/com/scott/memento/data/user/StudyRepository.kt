package com.scott.memento.data.user

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.scott.memento.domain.*
import com.scott.memento.domain.fsrs.FsrsScheduler
import org.json.JSONObject
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** All guards, queue changes and scoring run under the same SQLite write transaction. */
class StudyRepository(private val store:UserDatabase,private val clock:Clock=Clock.systemUTC()) {
    private val scheduler=FsrsScheduler()
    private data class Settings(val policy:StudyPolicy,val book:String)
    private data class Plan(val id:Long,val target:String,val date:LocalDate,val zone:ZoneId,val cutoff:Long,
        val policy:StudyPolicy,val book:String,val content:String,val revision:Int,val status:String)
    private data class Item(val id:Long,val uid:String,val kind:String,val isNew:Boolean,val state:String,val revealed:Boolean,val offered:Boolean)

    fun ensureDailyPlan(index:StudyContentIndex):StudySnapshot = transaction { db,now ->
        val (plan,guard)=prepare(db,index,now)
        snapshot(db,plan,guard)
    }

    /** Explicit setting changes can extend a completed plan; plain recovery never does. */
    fun configure(index:StudyContentIndex,policy:StudyPolicy,book:String):StudySnapshot = transaction { db,now ->
        require(book in index.books) { "词书不在当前内容包中" }
        val (old,guard)=prepare(db,index,now,create=false)
        if(guard !in setOf(StudyGuard.READY,StudyGuard.NOT_CONFIGURED)) return@transaction snapshot(db,old,guard)
        val previous=settings(db,index.target)
        if(previous!=Settings(policy,book)) {
            SqliteWrites.updateOrInsert(db,"language_settings",ContentValues().apply {
                put("target_language",index.target);put("study_mode",policy.mode.name)
                put("daily_target",policy.dailyTarget);put("book_id",book)
            },"""UPDATE language_settings SET study_mode=?,daily_target=?,book_id=?,revision=revision+1
                WHERE target_language=?""",arrayOf(policy.mode.name,policy.dailyTarget,book,index.target))
            if(old!=null) revise(db,old,index,Settings(policy,book),now,"SETTINGS")
        }
        val (plan,nextGuard)=prepare(db,index,now)
        snapshot(db,plan,nextGuard)
    }

    /** Freeze existing plan zones. The new zone first applies when their old-zone day ends. */
    fun changeZone(zoneId:String):Unit = transaction { db,now ->
        val zone=ZoneId.of(zoneId).id
        check(!rolledBack(db,now)) { "设备时间倒退，请先校正时间" }
        if(zone!=savedZone(db).id) {
            db.execSQL("UPDATE app_settings SET study_zone_id=?,revision=revision+1 WHERE id=1",arrayOf(zone))
            rows(db,"SELECT * FROM daily_plan WHERE status<>'CLOSED_INCOMPLETE' AND id IN (SELECT MAX(id) FROM daily_plan GROUP BY target_language)",mapper={plan()}).forEach { plan ->
                revision(db,plan,now,"TIME_ZONE",JSONObject().put("nextZone",zone).put("planZone",plan.zone.id))
                db.execSQL("UPDATE daily_plan_item SET revealed=0 WHERE plan_id=? AND state='PENDING'",arrayOf(plan.id))
            }
        }
    }

    fun chooseRollover(index:StudyContentIndex,planId:Long,choice:RolloverChoice):StudySnapshot = transaction { db,now ->
        val (plan,guard)=prepare(db,index,now)
        if(guard!=StudyGuard.MIDNIGHT_CHOICE || plan?.id!=planId) return@transaction snapshot(db,plan,guard)
        val expiry=plan.date.plusDays(1).atTime(2,0).atZone(plan.zone).toInstant().toEpochMilli()
        db.execSQL("INSERT INTO rollover_decision(plan_id,target_language,choice,decided_at,defer_until,zone_id) VALUES(?,?,?,?,?,?)",
            arrayOf(plan.id,plan.target,choice.name,now,if(choice==RolloverChoice.DEFER) expiry else null,plan.zone.id))
        val (next,nextGuard)=prepare(db,index,now)
        snapshot(db,next,nextGuard)
    }

    fun chooseExtra(index:StudyContentIndex,planId:Long,accept:Boolean):StudySnapshot = transaction { db,now ->
        val (plan,guard)=prepare(db,index,now)
        if(guard!=StudyGuard.READY || plan?.id!=planId) return@transaction snapshot(db,plan,guard)
        val current=snapshot(db,plan,guard)
        if(!current.overflowChoice) return@transaction current
        db.execSQL("UPDATE extra_review_batch SET decision=?,completed_at=? WHERE plan_id=? AND decision='PENDING'",
            arrayOf(if(accept) "ACCEPTED" else "DECLINED",if(accept) null else now,plan.id))
        revision(db,plan,now,"EXTRA_CHOICE",JSONObject().put("accepted",accept))
        if(accept) db.execSQL("UPDATE daily_plan_item SET state='PENDING' WHERE plan_id=? AND queue_kind='EXTRA' AND state='REMOVED' AND offered=1",arrayOf(plan.id))
        snapshot(db,loadPlan(db,plan.id),guard)
    }

    fun reveal(index:StudyContentIndex,request:RatingRequest):ScoreOutcome = transaction { db,now ->
        val (plan,guard)=prepare(db,index,now)
        val state=snapshot(db,plan,guard)
        val result=validate(db,index,request,plan,state)
        if(result!=null) return@transaction ScoreOutcome(result,state)
        db.execSQL("UPDATE daily_plan_item SET revealed=1 WHERE id=? AND state='PENDING'",arrayOf(request.itemId))
        ScoreOutcome(ScoreResult.APPLIED,snapshot(db,plan,guard))
    }

    fun rate(index:StudyContentIndex,request:RatingRequest,rating:Rating):ScoreOutcome = transaction { db,now ->
        val (plan,guard)=prepare(db,index,now)
        val state=snapshot(db,plan,guard)
        if(request.identity.targetLanguage!=index.target) return@transaction ScoreOutcome(ScoreResult.STALE,state)
        val replay=rows(db,"SELECT target_language,lexeme_uid,plan_id,item_id,rating FROM review_log WHERE request_id=?",arrayOf(request.requestId)) {
            getString(0)==request.identity.targetLanguage && getString(1)==request.identity.lexemeUid &&
                getLong(2)==request.planId && getLong(3)==request.itemId && getString(4)==rating.name
        }
        if(replay.isNotEmpty()) return@transaction ScoreOutcome(if(replay.single()) ScoreResult.ALREADY_APPLIED else ScoreResult.STALE,state)
        val rejected=validate(db,index,request,plan,state)
        if(rejected!=null) {
            val result=if(rejected==ScoreResult.ALREADY_APPLIED && rows(db,"SELECT rating FROM review_log WHERE item_id=?",arrayOf(request.itemId.toString())){getString(0)}.firstOrNull()!=rating.name) ScoreResult.STALE else rejected
            return@transaction ScoreOutcome(result,state)
        }
        val item=checkNotNull(state.next)
        if(!item.revealed) return@transaction ScoreOutcome(ScoreResult.NOT_REVEALED,state)
        val previous=card(db,index.target,item.uid) ?: LearningCard(item.uid)
        val next=scheduler.review(previous,rating,now)
        val values=ContentValues().apply {
            put("target_language",index.target);put("lexeme_uid",item.uid)
            put("stability",next.stability);put("difficulty",next.difficulty);put("due_at",next.dueAt)
            put("last_review_at",now);put("repetitions",next.repetitions);put("lapses",next.lapses)
            put("state",next.state);put("content_available",1)
        }
        // UPDATE/INSERT rather than REPLACE: future foreign keys must retain this row's identity.
        if(db.update("learning_card",values,"target_language=? AND lexeme_uid=?",arrayOf(index.target,item.uid))==0) db.insertOrThrow("learning_card",null,values)
        val active=checkNotNull(plan)
        db.insertOrThrow("review_log",null,ContentValues().apply {
            put("target_language",index.target);put("lexeme_uid",item.uid);put("kind",item.kind)
            put("plan_id",active.id);put("item_id",item.id);put("request_id",request.requestId)
            put("rating",rating.name);put("is_new",if(previous.lastReviewAt==null) 1 else 0)
            put("reviewed_at",now);put("business_date",active.date.toString());put("zone_id",active.zone.id)
            put("previous_stability",previous.stability);put("previous_difficulty",previous.difficulty)
            put("next_stability",next.stability);put("next_difficulty",next.difficulty);put("next_due_at",next.dueAt)
            put("elapsed_days",scheduler.elapsedDays(previous,now));put("scheduler_version",FsrsScheduler.VERSION)
            put("desired_retention",FsrsScheduler.DESIRED_RETENTION)
        })
        check(db.update("daily_plan_item",ContentValues().apply{put("state","GRADED");put("rating",rating.name)},"id=? AND state='PENDING'",arrayOf(item.id.toString()))==1)
        complete(db,active.id,now)
        ScoreOutcome(ScoreResult.APPLIED,snapshot(db,loadPlan(db,active.id),guard))
    }

    private fun validate(db:SQLiteDatabase,index:StudyContentIndex,request:RatingRequest,plan:Plan?,state:StudySnapshot):ScoreResult? {
        if(state.guard!=StudyGuard.READY) return ScoreResult.BLOCKED
        if(request.identity.targetLanguage!=index.target || request.planId!=plan?.id || request.revision!=plan.revision) return ScoreResult.STALE
        if(request.identity.lexemeUid !in index.available) return ScoreResult.CONTENT_UNAVAILABLE
        val owned=scalar(db,"SELECT COUNT(*) FROM daily_plan_item WHERE id=? AND plan_id=? AND target_language=? AND lexeme_uid=?",
            arrayOf(request.itemId.toString(),request.planId.toString(),index.target,request.identity.lexemeUid))==1
        if(!owned) return ScoreResult.STALE
        if(state.next?.id!=request.itemId || state.next.uid!=request.identity.lexemeUid) {
            val graded=scalar(db,"SELECT COUNT(*) FROM review_log WHERE item_id=?",arrayOf(request.itemId.toString()))>0
            return if(graded) ScoreResult.ALREADY_APPLIED else ScoreResult.OUT_OF_ORDER
        }
        return null
    }

    private fun prepare(db:SQLiteDatabase,index:StudyContentIndex,now:Long,create:Boolean=true):Pair<Plan?,StudyGuard> {
        var latest=rows(db,"SELECT * FROM daily_plan WHERE target_language=? ORDER BY id DESC LIMIT 1",arrayOf(index.target)){plan()}.firstOrNull()
        if(rolledBack(db,now)) return latest to StudyGuard.CLOCK_ROLLBACK
        if(latest!=null) {
            val local=Instant.ofEpochMilli(now).atZone(latest.zone)
            if(local.toLocalDate()<latest.date) return latest to StudyGuard.WAIT_FOR_DATE
            if(local.toLocalDate()>latest.date) {
                val expiry=latest.date.plusDays(1).atTime(LocalTime.of(2,0)).atZone(latest.zone).toInstant().toEpochMilli()
                val choice=rows(db,"SELECT choice,defer_until FROM rollover_decision WHERE plan_id=?",arrayOf(latest.id.toString())) { getString(0) to if(isNull(1)) null else getLong(1) }.firstOrNull()
                val work=snapshot(db,latest,StudyGuard.READY).let { it.next!=null || it.overflowChoice }
                if(now<expiry && choice==null && work) return latest to StudyGuard.MIDNIGHT_CHOICE
                if(now<expiry && choice?.first=="DEFER" && now<checkNotNull(choice.second)) return current(db,latest,index,now) to StudyGuard.READY
                val newDate=Instant.ofEpochMilli(now).atZone(savedZone(db)).toLocalDate()
                // Westward zone changes must not reopen an earlier date or create a second quota.
                if(newDate<=latest.date) return latest to StudyGuard.WAIT_FOR_DATE
                close(db,latest,now)
                latest=null
            }
        }
        val setting=settings(db,index.target) ?: return latest to StudyGuard.NOT_CONFIGURED
        if(latest==null && create) latest=create(db,index,setting,now)
        if(latest!=null) latest=current(db,latest,index,now)
        return latest to StudyGuard.READY
    }

    private fun current(db:SQLiteDatabase,plan:Plan,index:StudyContentIndex,now:Long):Plan {
        if(plan.status=="CLOSED_INCOMPLETE") return plan
        val unavailable=items(db,plan.id).any {
            ((it.state=="PENDING" || (it.state=="REMOVED" && it.offered)) && it.uid !in index.available) ||
                (it.state=="UNAVAILABLE" && it.uid in index.available)
        }
        val imported=scalar(db,"SELECT COUNT(*) FROM plan_refresh_request WHERE target_language=?",arrayOf(index.target))>0
        if(plan.content!=index.identity || unavailable || imported) {
            if(imported) db.delete("plan_review_candidate","plan_id=?",arrayOf(plan.id.toString()))
            revise(db,plan,index,settings(db,index.target) ?: Settings(plan.policy,plan.book),now,if(imported) "LEGACY_IMPORT" else "CONTENT_OR_LEARNING")
            db.delete("plan_refresh_request","target_language=?",arrayOf(index.target))
            return checkNotNull(loadPlan(db,plan.id))
        }
        return plan
    }

    private fun create(db:SQLiteDatabase,index:StudyContentIndex,setting:Settings,now:Long):Plan {
        val zone=savedZone(db)
        val date=Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val existing=rows(db,"SELECT * FROM daily_plan WHERE target_language=? AND business_date=?",arrayOf(index.target,date.toString())){plan()}.firstOrNull()
        if(existing!=null) return existing
        val id=db.insertOrThrow("daily_plan",null,ContentValues().apply {
            put("target_language",index.target);put("business_date",date.toString());put("zone_id",zone.id)
            put("created_at",now);put("due_cutoff_at",date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            put("study_mode",setting.policy.mode.name);put("target_snapshot",setting.policy.dailyTarget)
            put("book_snapshot",setting.book);put("content_version",index.identity);put("policy_version",1)
            put("eligible_review_count",0);put("status","ACTIVE")
        })
        val plan=checkNotNull(loadPlan(db,id))
        candidates(db,plan,index)
        rebuild(db,plan,index,setting,now)
        db.delete("plan_refresh_request","target_language=?",arrayOf(index.target))
        db.execSQL("INSERT INTO plan_revision VALUES(?,1,?,'CREATED',?)",arrayOf(id,now,settingsJson(setting).toString()))
        return checkNotNull(loadPlan(db,id))
    }

    private fun revise(db:SQLiteDatabase,plan:Plan,index:StudyContentIndex,setting:Settings,now:Long,reason:String) {
        revision(db,plan,now,reason,settingsJson(setting))
        db.execSQL("UPDATE daily_plan SET study_mode=?,target_snapshot=?,book_snapshot=?,content_version=? WHERE id=?",
            arrayOf(setting.policy.mode.name,setting.policy.dailyTarget,setting.book,index.identity,plan.id))
        candidates(db,plan,index)
        rebuild(db,checkNotNull(loadPlan(db,plan.id)),index,setting,now)
    }

    private fun revision(db:SQLiteDatabase,plan:Plan,now:Long,reason:String,json:JSONObject) {
        val revision=plan.revision+1
        db.execSQL("UPDATE daily_plan SET revision=? WHERE id=?",arrayOf(revision,plan.id))
        db.execSQL("INSERT INTO plan_revision VALUES(?,?,?,?,?)",arrayOf(plan.id,revision,now,reason,json.toString()))
    }

    /** Freeze original due candidates, append only on an explicit content/settings revision. */
    private fun candidates(db:SQLiteDatabase,plan:Plan,index:StudyContentIndex) {
        val cards=rows(db,"SELECT * FROM learning_card WHERE target_language=?",arrayOf(plan.target)){card()}
        cards.forEach { card -> db.execSQL("UPDATE learning_card SET content_available=? WHERE target_language=? AND lexeme_uid=?",
            arrayOf(if(card.lexemeUid in index.available) 1 else 0,plan.target,card.lexemeUid)) }
        val existing=rows(db,"SELECT lexeme_uid FROM plan_review_candidate WHERE plan_id=?",arrayOf(plan.id.toString())){getString(0)}.toSet()
        var position=scalar(db,"SELECT COALESCE(MAX(position),-1)+1 FROM plan_review_candidate WHERE plan_id=?",arrayOf(plan.id.toString()))
        cards.filter { it.lexemeUid in index.available && it.lastReviewAt!=null && it.dueAt<plan.cutoff && it.lexemeUid !in existing }
            .sortedWith(compareBy<LearningCard>{it.dueAt}.thenBy{it.lexemeUid}).forEach { card ->
                db.execSQL("INSERT INTO plan_review_candidate VALUES(?,?,?,?)",arrayOf(plan.id,plan.target,card.lexemeUid,position++))
            }
        db.execSQL("UPDATE daily_plan SET eligible_review_count=(SELECT COUNT(*) FROM plan_review_candidate WHERE plan_id=?) WHERE id=?",arrayOf(plan.id,plan.id))
    }

    private fun rebuild(db:SQLiteDatabase,plan:Plan,index:StudyContentIndex,setting:Settings,now:Long) {
        val graded=rows(db,"SELECT DISTINCT lexeme_uid FROM review_log WHERE target_language=? AND business_date=?",arrayOf(plan.target,plan.date.toString())){getString(0)}.toSet()
        val learned=rows(db,"SELECT lexeme_uid FROM learning_card WHERE target_language=? AND last_review_at IS NOT NULL",arrayOf(plan.target)){getString(0)}.toSet()
        val reviews=rows(db,"SELECT lexeme_uid FROM plan_review_candidate WHERE plan_id=? ORDER BY position",arrayOf(plan.id.toString())){getString(0)}
            .filter { it in index.available && it in learned && it !in graded }
        val allowance=if(setting.policy.mode==StudyMode.REVIEW_ALL) reviews.size else (checkNotNull(setting.policy.dailyTarget)-graded.size).coerceAtLeast(0)
        val selectedReviews=reviews.take(allowance)
        val new=if(setting.policy.mode==StudyMode.REVIEW_ALL) emptyList() else index.books[setting.book].orEmpty()
            .filter { it !in learned && it !in graded }.take((allowance-selectedReviews.size).coerceAtLeast(0))
        val overflow=if(setting.policy.mode==StudyMode.REVIEW_ALL) emptyList() else reviews.drop(selectedReviews.size)
        var batch=rows(db,"SELECT id,decision FROM extra_review_batch WHERE plan_id=?",arrayOf(plan.id.toString())){getLong(0) to getString(1)}.firstOrNull()
        if(overflow.isNotEmpty() && batch==null) {
            val id=db.insertOrThrow("extra_review_batch",null,ContentValues().apply{put("plan_id",plan.id);put("target_language",plan.target);put("decision","PENDING");put("created_at",now)})
            batch=id to "PENDING"
        }
        val old=items(db,plan.id).associateBy { it.uid }
        // Unique positions remain collision-free while items move between normal and extra queues.
        var position=scalar(db,"SELECT COALESCE(MAX(position),-1)+1 FROM daily_plan_item WHERE plan_id=?",arrayOf(plan.id.toString()))
        old.values.filter { it.state!="GRADED" }.forEach { item ->
            db.execSQL("UPDATE daily_plan_item SET state=?,revealed=0,offered=0 WHERE id=?",arrayOf(if(item.uid in index.available) "REMOVED" else "UNAVAILABLE",item.id))
        }
        fun assign(uid:String,kind:String,isNew:Boolean,state:String) {
            val values=ContentValues().apply {
                put("plan_id",plan.id);put("target_language",plan.target);put("lexeme_uid",uid);put("queue_kind",kind)
                if(kind=="EXTRA") put("extra_batch_id",checkNotNull(batch).first) else putNull("extra_batch_id")
                put("position",position++);put("is_new",if(isNew) 1 else 0);put("state",state);put("revealed",0)
                put("offered",if(kind=="EXTRA") 1 else 0)
            }
            val existing=old[uid]
            if(existing==null) db.insertOrThrow("daily_plan_item",null,values)
            else { check(existing.state!="GRADED");db.update("daily_plan_item",values,"id=?",arrayOf(existing.id.toString())) }
        }
        selectedReviews.forEach { assign(it,"NORMAL",false,"PENDING") }
        new.forEach { assign(it,"NORMAL",true,"PENDING") }
        overflow.forEach { assign(it,"EXTRA",false,if(batch?.second=="ACCEPTED") "PENDING" else "REMOVED") }
        if(overflow.isNotEmpty() && batch?.second=="ACCEPTED") db.execSQL("UPDATE extra_review_batch SET completed_at=NULL WHERE plan_id=?",arrayOf(plan.id))
        if(selectedReviews.isNotEmpty() || new.isNotEmpty()) db.execSQL("UPDATE daily_plan SET status='ACTIVE',completed_at=NULL,closed_at=NULL,close_reason=NULL WHERE id=?",arrayOf(plan.id))
        complete(db,plan.id,now)
    }

    private fun complete(db:SQLiteDatabase,planId:Long,now:Long) {
        if(scalar(db,"SELECT COUNT(*) FROM daily_plan_item WHERE plan_id=? AND queue_kind='NORMAL' AND state='PENDING'",arrayOf(planId.toString()))==0)
            db.execSQL("UPDATE daily_plan SET status='COMPLETED',completed_at=COALESCE(completed_at,?),closed_at=COALESCE(closed_at,?),close_reason='NORMAL_FINISHED' WHERE id=? AND status<>'CLOSED_INCOMPLETE'",arrayOf(now,now,planId))
        if(scalar(db,"SELECT COUNT(*) FROM daily_plan_item WHERE plan_id=? AND queue_kind='EXTRA' AND state='PENDING'",arrayOf(planId.toString()))==0)
            db.execSQL("UPDATE extra_review_batch SET completed_at=COALESCE(completed_at,?) WHERE plan_id=? AND decision='ACCEPTED'",arrayOf(now,planId))
    }

    private fun close(db:SQLiteDatabase,plan:Plan,now:Long) {
        db.execSQL("UPDATE daily_plan SET status='CLOSED_INCOMPLETE',closed_at=?,close_reason='NEW_DAY' WHERE id=? AND status='ACTIVE'",arrayOf(now,plan.id))
    }

    private fun snapshot(db:SQLiteDatabase,plan:Plan?,guard:StudyGuard):StudySnapshot {
        if(plan==null) return StudySnapshot(guard)
        val items=items(db,plan.id)
        val normal=items.filter { it.kind=="NORMAL" && it.state=="PENDING" }
        val batch=rows(db,"SELECT decision FROM extra_review_batch WHERE plan_id=?",arrayOf(plan.id.toString())){getString(0)}.firstOrNull()
        val extra=items.filter { it.kind=="EXTRA" && (it.state=="PENDING" || (batch=="PENDING" && it.state=="REMOVED" && it.offered)) }
        val next=if(guard==StudyGuard.READY && plan.status!="CLOSED_INCOMPLETE") normal.firstOrNull() ?: extra.firstOrNull().takeIf { batch=="ACCEPTED" } else null
        val stats=rows(db,"""SELECT COUNT(*),COALESCE(SUM(is_new),0),COUNT(*)-COALESCE(SUM(is_new),0),COALESCE(SUM(ratings),0)
            FROM (SELECT lexeme_uid,MAX(is_new) AS is_new,COUNT(*) AS ratings FROM review_log
                WHERE target_language=? AND business_date=? GROUP BY lexeme_uid)""",arrayOf(plan.target,plan.date.toString())) {
            StudyStats(getInt(0),getInt(1),getInt(2),getInt(3),scalar(db,"SELECT COUNT(*) FROM learning_card WHERE target_language=? AND last_review_at IS NOT NULL",arrayOf(plan.target)))
        }.single()
        return StudySnapshot(guard,plan.id,plan.revision,plan.date.toString(),plan.zone.id,plan.policy,plan.book,
            next?.let{StudyItem(it.id,it.uid,it.isNew,it.kind,it.revealed)},normal.size,extra.size,
            normal.isEmpty() && extra.isNotEmpty() && batch=="PENDING",batch,stats)
    }

    private fun rolledBack(db:SQLiteDatabase,now:Long):Boolean {
        require(now>=0)
        val high=rows(db,"SELECT max_observed_at FROM study_clock WHERE id=1"){getLong(0)}.single()
        if(now<high) return true
        db.execSQL("UPDATE study_clock SET max_observed_at=? WHERE id=1",arrayOf(now))
        return false
    }
    private fun settings(db:SQLiteDatabase,target:String)=rows(db,"SELECT study_mode,daily_target,book_id FROM language_settings WHERE target_language=?",arrayOf(target)) {
        Settings(StudyPolicy(StudyMode.valueOf(getString(0)),if(isNull(1)) null else getInt(1)),getString(2))
    }.firstOrNull()
    private fun settingsJson(setting:Settings)=JSONObject().put("mode",setting.policy.mode.name).put("target",setting.policy.dailyTarget ?: JSONObject.NULL).put("book",setting.book)
    private fun savedZone(db:SQLiteDatabase)=ZoneId.of(rows(db,"SELECT study_zone_id FROM app_settings WHERE id=1"){getString(0)}.single())
    private fun loadPlan(db:SQLiteDatabase,id:Long)=rows(db,"SELECT * FROM daily_plan WHERE id=?",arrayOf(id.toString())){plan()}.firstOrNull()
    private fun items(db:SQLiteDatabase,plan:Long)=rows(db,"SELECT * FROM daily_plan_item WHERE plan_id=? ORDER BY position,id",arrayOf(plan.toString())) {
        Item(long("id"),string("lexeme_uid"),string("queue_kind"),int("is_new")==1,string("state"),int("revealed")==1,int("offered")==1)
    }
    private fun card(db:SQLiteDatabase,target:String,uid:String)=rows(db,"SELECT * FROM learning_card WHERE target_language=? AND lexeme_uid=?",arrayOf(target,uid)){card()}.firstOrNull()
    private fun Cursor.card()=LearningCard(string("lexeme_uid"),getDouble(getColumnIndexOrThrow("stability")),getDouble(getColumnIndexOrThrow("difficulty")),long("due_at"),
        if(isNull(getColumnIndexOrThrow("last_review_at"))) null else long("last_review_at"),int("repetitions"),int("lapses"),string("state"))
    private fun Cursor.plan()=Plan(long("id"),string("target_language"),LocalDate.parse(string("business_date")),ZoneId.of(string("zone_id")),long("due_cutoff_at"),
        StudyPolicy(StudyMode.valueOf(string("study_mode")),if(isNull(getColumnIndexOrThrow("target_snapshot"))) null else int("target_snapshot")),string("book_snapshot"),string("content_version"),int("revision"),string("status"))
    private fun Cursor.string(name:String)=getString(getColumnIndexOrThrow(name))
    private fun Cursor.int(name:String)=getInt(getColumnIndexOrThrow(name))
    private fun Cursor.long(name:String)=getLong(getColumnIndexOrThrow(name))
    private fun scalar(db:SQLiteDatabase,sql:String,args:Array<String> = emptyArray())=rows(db,sql,args){getInt(0)}.single()
    private fun <T> rows(db:SQLiteDatabase,sql:String,args:Array<String> = emptyArray(),mapper:Cursor.()->T):List<T> = db.rawQuery(sql,args).use { c -> buildList{while(c.moveToNext()) add(c.mapper())} }
    private fun <T> transaction(action:(SQLiteDatabase,Long)->T):T {
        val db=store.writableDatabase
        db.beginTransaction()
        try { val now=clock.millis();val result=action(db,now);db.setTransactionSuccessful();return result }
        finally { db.endTransaction() }
    }
}
